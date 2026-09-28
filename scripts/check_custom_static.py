#!/usr/bin/env python3
"""Read-only static gate. Never invokes Gradle, Kotlin, Node, Actions or a build.
Optional Kotlin parser: prebuilt tree-sitter and tree-sitter-kotlin Python wheels.
Usage: PYTHONPATH=/path/to/prebuilt/wheels python3 scripts/check_custom_static.py
"""
import argparse
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = '00c8d53a5c74554c92848cf821d551a17f576995'
parser = argparse.ArgumentParser()
parser.add_argument('--base', default=BASE)
parser.add_argument('--report')
args = parser.parse_args()

def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args], text=True)

def read(path):
    return (ROOT / path).read_text()

checks = []
def check(name, condition):
    checks.append({'check': name, 'passed': bool(condition)})

files = sorted(set(git('diff', args.base, '--name-only').splitlines() +
                   git('ls-files', '--others', '--exclude-standard').splitlines()))
kt = [f for f in files if f.endswith('.kt')]
r = subprocess.run(['git', '-C', str(ROOT), 'diff', args.base, '--check'], capture_output=True, text=True)
check('git diff whitespace', r.returncode == 0)
if r.returncode: print(r.stdout, r.stderr)

xml_count = 0
for p in (ROOT / 'app/src/main/res').rglob('*.xml'):
    try:
        ET.parse(p)
        xml_count += 1
    except ET.ParseError as e:
        check(f'XML {p.relative_to(ROOT)}: {e}', False)
check('resource XML parsed', xml_count > 0)

def strings(raw):
    tree = ET.fromstring(raw)
    names = [x.attrib['name'] for x in tree if x.tag == 'string']
    check('unique resource string keys', len(names) == len(set(names)))
    return {x.attrib['name']: ''.join(x.itertext()) for x in tree if x.tag == 'string'}

en = strings(read('app/src/main/res/values/strings.xml'))
zh = strings(read('app/src/main/res/values-zh/strings.xml'))
base_en = ET.fromstring(git('show', args.base + ':app/src/main/res/values/strings.xml'))
new_keys = set(en) - {x.attrib.get('name') for x in base_en}
check('all new strings translated', new_keys <= set(zh))
for key in sorted(new_keys):
    check('placeholder parity: ' + key, sorted(re.findall(r'%\d*\$?[dsf]', en[key])) == sorted(re.findall(r'%\d*\$?[dsf]', zh[key])))
for f in kt:
    source = read(f)
    if f.startswith('app/'):
        missing = set(re.findall(r'(?<!android\.)R\.string\.(\w+)', source)) - set(en)
        check('string references: ' + f, not missing)
    imports = re.findall(r'^import (.+)$', source, re.M)
    check('unique imports: ' + f, len(imports) == len(set(imports)))
    check('no conflict markers: ' + f, not re.search(r'^(<<<<<<<|=======|>>>>>>>)', source, re.M))

app = 'app/src/main/java/me/rerere/rikkahub/'
ai = 'ai/src/main/java/me/rerere/ai/'
saver = read(app + 'ext/resilience/StreamDraftSaver.kt')
repo = read(app + 'data/repository/ConversationRepository.kt')
service = read(app + 'service/ChatService.kt')
loop = read(app + 'data/ai/GenerationLoop.kt')
keys = read(ai + 'util/KeyRoulette.kt')
config = read(app + 'ext/retry/AutoRetryConfig.kt')
policy = read(app + 'ext/retry/RetryPolicy.kt')
health = read(ai + 'util/KeyHealth.kt')
api_keys = read(ai + 'provider/ProviderApiKeys.kt')
checks_by_name = {
    'periodic not debounce': 'while (true)' in saver and 'delay(intervalMs)' in saver,
    'publish timer before start': saver.index('start = CoroutineStart.LAZY') < saver.index('worker?.start()'),
    'retain newer pending snapshots': 'current === snapshot' in saver,
    'draft and final share lock': saver.count('lockFor(conversationId).withLock') == 2,
    'read newest state under lock': 'val snapshot = latest()' in saver,
    'failed writes retain pending': saver.index('save(snapshot)') < saver.index('removeIfSame(conversationId, snapshot)'),
    'cancellation not swallowed in worker': 'catch (cancelled: CancellationException)' in saver and 'throw cancelled' in saver,
    'fixed lock table': 'Array(64) { Mutex() }' in saver,
    'draft does not recreate deleted conversation': 'if (conversationDAO.getConversationById(id) == null) return@withTransaction' in repo,
    'draft is transactional': 'suspend fun writeMessageNodesDraft' in repo and 'database.withTransaction' in repo.split('suspend fun writeMessageNodesDraft')[1],
    'ordinary saves protected against UI cancellation': 'suspend fun saveConversation' in service and 'withContext(kotlinx.coroutines.NonCancellable)' in service.split('suspend fun saveConversation')[1].split('// ---- 翻译')[0],
    'save keeps Unit API': 'conversation: Conversation): Unit =' in service,
    'save retains session': 'sessionManager.withSession(conversationId)' in service.split('suspend fun saveConversation')[1].split('// ---- 翻译')[0],
    'branch stops active generation first': 'if (sessionManager.get(conversationId)?.isGenerating == true) stopGeneration(conversationId)' in service,
    'crash does not initialize lazy DI': 'StreamDraftSaver.flushActiveBlocking' in read(app + 'RikkaHubApp.kt') and 'getOrNull<' not in read(app + 'RikkaHubApp.kt'),
    'onStop flush': 'StreamDraftSaver.flushActive()' in read(app + 'RouteActivity.kt'),
    'request-local key attribution': 'requestProvider.getApiKeyValue()' in loop and 'inFlight' not in keys,
    'single key copy pinned': 'provider.withSingleApiKeyForRequest(KeyRotationPolicy.pick(provider))' in loop,
    'single-test key attribution': 'reportFailure(provider.id.toString(), apiKey.value, error)' in read(app + 'ext/keys/ProviderKeyManagerSheet.kt'),
    'pool exhaustion is explicit': 'if (ready.isEmpty()) throw AllKeysSuspendedException(id)' in keys,
    'no cooled-key fallback': 'coolingSorted' not in keys,
    'no disabled raw-key fallback': 'apiKey = enabledKeys,' in api_keys and 'enabledKeys.ifBlank' not in api_keys,
    'service accounts bypass pool': 'provider.vertexAI && provider.useServiceAccount' in keys,
    'health persistence atomic': 'AtomicFile' in health and 'file.finishWrite(output)' in health and 'file.failWrite(output)' in health,
    'health modifications serialized': '@Synchronized\n    internal fun mark' in health,
    'health reason is category not credentials': 'verdict.name.lowercase()' in keys,
    '5xx does not suspend a key': 'status != null && status >= 500 -> KeyVerdict.NEUTRAL' in health,
    'status preserved': 'typed is ProviderHttpException' in health and 'typed is ProviderHttpException' in policy,
    'HTTP before IOException': policy.index('if (status != null) return') < policy.index('if (isNetworkTransportError(error))'),
    'partial answer safe by default': 'val retryAfterPartialResponse: Boolean = false' in config and 'receivedParts != baseParts' in loop,
    'cancel guard remains': 'currentCoroutineContext().ensureActive()' in loop and 'if (error is CancellationException) throw error' in loop,
    'downstream failures not retried': 'if (error is StreamChunkHandlingException)' in loop,
    'retry bounded': 'retryCount >= budget' in loop and 'const val MAX_MAX_RETRIES = 10' in config,
    'clipboard requires explicit paste': 'readClipboardText' not in read(app + 'ext/keys/ProviderKeyManagerSheet.kt'),
    'analytics wiring preserved': 'private val analytics: FirebaseAnalytics' in read(app + 'ui/pages/chat/ChatVM.kt'),
    # The final CI workflow is intentionally changed for the verified build configuration;
    # project Gradle/signing source files remain untouched by the feature patch.
    'no project build changes': (
        not any(f.endswith('.gradle.kts') or f == 'gradle/libs.versions.toml' for f in files)
        and all(f == '.github/workflows/daily-build.yml' or not f.startswith('.github/') for f in files)
    ),
}
key_policy = read(ai + 'util/KeyManagementPolicy.kt')
key_ui = read(app + 'ext/keys/ProviderKeyManagerSheet.kt')
retry_ui = read(app + 'ext/retry/AutoRetrySettingsSheet.kt')
policy_ui = read(app + 'ext/keys/KeyPolicySettingsScreen.kt')
shared_ui = read(app + 'ext/ui/PolicyUi.kt')
store = read(app + 'data/datastore/PreferencesStore.kt')
checks_by_name.update({
    'policy serialized with compatible defaults': '@Serializable' in key_policy and 'val enabled: Boolean = true' in key_policy and 'keyManagement:' in store,
    'policy clamps invalid numeric input': 'cooldownMultiplier.isFinite()' in key_policy and 'maxSwitches.coerceIn(0, 20)' in key_policy,
    'classification separate from action': 'config.action(verdict)' in health and 'action == KeyFailureAction.IGNORE' in health,
    'policy applies without opening UI': store.count('KeyRotationPolicy.configure(') == 2,
    'disabled policy ignores old health records': 'if (!policy.enabled) return null' in health,
    'manual recovery durable': 'Long.MAX_VALUE' in health,
    'switch budget is configurable': 'keyPolicy.maxSwitches' in loop and 'keyPolicy.switchDelayMs' in loop and 'KEY_SWITCH_BUDGET' not in loop,
    'all key health clear is explicit': 'confirm = "clear"' in policy_ui and 'KeyRotationPolicy.clearAllHealth()' in policy_ui,
    'fixed header footer and bounded body': 'Modifier.weight(1f).fillMaxWidth()' in shared_ui and 'footer()' in shared_ui,
    'key manager no fixed 480dp viewport': 'max = 480.dp' not in key_ui and 'LazyColumn' in key_ui,
    'key search plus state filter': 'query.trim()' in key_ui and 'category(it) == filter' in key_ui,
    'key tests stay request local and bounded': 'withTimeout(30000)' in key_ui and 'withSingleApiKeyForRequest(apiKey.value)' in key_ui,
    'key timeout is not penalized': key_ui.index('catch (error: TimeoutCancellationException)') < key_ui.index('catch (error: Throwable)'),
    'tests never echo upstream credentials': 'error.message' not in key_ui,
    'duplicate edit rejected': '!duplicate && value.isNotBlank()' in key_ui,
    'import previews additions and duplicates': 'parsed.size - parsedCount' in key_ui,
    'retry preview deterministic': 'config.copy(jitter = false)' in retry_ui,
    'partial replay requires confirmation': 'partialConfirm = true' in retry_ui,
    'settings drafts survive rotation': 'rememberSaveable' in retry_ui and 'rememberSaveable' in policy_ui,
    'discard prompts in both settings': 'polish_discard_desc' in retry_ui and 'polish_discard_desc' in policy_ui,
    'network key management entry': 'showKeyPolicy = true' in read(app + 'ui/pages/setting/SettingPreferencesNetworkPage.kt'),
})
for name, condition in checks_by_name.items(): check(name, condition)
for f in ['claude/ClaudeProvider.kt', 'google/GoogleProvider.kt', 'openai/ChatCompletionsAPI.kt', 'openai/ResponseAPI.kt']:
    source = read(ai + 'provider/providers/' + f)
    check('typed SSE failure: ' + f, 'close(providerStreamFailure(response, exception))' in source)
for p in (ROOT / (ai + 'provider/providers')).rglob('*.kt'):
    check('no legacy auth bypass: ' + p.name, 'keyRoulette.next(providerSetting.apiKey' not in p.read_text())

# This parser is not the Kotlin compiler. Compare known grammar limitations to the official baseline.
parser_info = {'available': False, 'files': len(kt), 'baseline_limitations': []}
try:
    from tree_sitter import Language, Parser
    import tree_sitter_kotlin
    kotlin = Parser(Language(tree_sitter_kotlin.language()))
    parser_info['available'] = True
    def errors(raw):
        found = []
        def visit(node):
            if node.type == 'ERROR' or node.is_missing: found.append(node.type)
            for child in node.children: visit(child)
        visit(kotlin.parse(raw).root_node)
        return found
    for f in kt:
        actual = errors((ROOT / f).read_bytes())
        old = subprocess.run(['git', '-C', str(ROOT), 'show', args.base + ':' + f], capture_output=True)
        baseline = errors(old.stdout) if old.returncode == 0 else []
        check('Kotlin syntax regression: ' + f, len(actual) <= len(baseline))
        if actual:
            parser_info['baseline_limitations'].append({'file': f, 'current': len(actual), 'baseline': len(baseline)})
except ImportError:
    print('NOTE: optional Kotlin syntax parser unavailable; install prebuilt wheels to enable it.')

failed = [x for x in checks if not x['passed']]
report = {'base': args.base, 'changed_files': len(files), 'kotlin_files': len(kt),
          'xml_files': xml_count, 'new_strings': len(new_keys), 'checks': len(checks),
          'failures': failed, 'parser': parser_info,
          'compiled': False, 'kotlin_tests_executed': False}
print(json.dumps(report, ensure_ascii=False, indent=2))
if args.report: Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
sys.exit(1 if failed else 0)
