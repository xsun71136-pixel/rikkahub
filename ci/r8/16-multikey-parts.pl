#!/usr/bin/perl
# 轮 8 · 16/17：多 Key 页的三块零件 —— 密钥行补复制 / 批量测试做实 / 批量导入弹层
#
# 1) KeyRow 补「复制密钥」：掩码看得见但抄不走，这个一直缺。
# 2) MultiKeyTestDialog 从「跑一遍列状态」做成能用的批量工具：
#    进度（已测 i/N）、汇总（可用 X · 警告 Y · 失败 Z）、只重测失败项、一次性删掉失败项（二次确认）。
#    仍然顺序跑不并发——并发容易自己把限流打出来，测出来的失败反而不准。
# 3) 新增 MultiKeyImportSheet：原来只有一个单行输入框挂在密钥列表末尾，粘多行会被压成一行、
#    也看不到解析结果，批量场景基本没法用。这里单独开底部弹层，多行输入 + 实时解析回显 +
#    追加/替换两种模式 + 粘贴脏数据清理（引号、反引号、Bearer 前缀）。
#
# 全部用内容锚点定位区间，不写死行号：本脚本紧跟 15-multikey-page.pl 跑，行号已经被它改过了。
use strict; use warnings;

my $F = 'app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/MultiKeyConfig.kt';
open(my $in, '<', $F) or die "open $F: $!";
my @L = <$in>; close $in;

sub lineof {
    my ($re, $from) = @_;
    $from //= 0;
    for my $i ($from .. $#L) { return $i if $L[$i] =~ /$re/; }
    die "anchor not found: $re\n";
}

# ============ A) MultiKeyTestDialog：@Composable 起，到 if (showModelSheet) 之前 ============
my $newTest = <<'KT';
/**
 * 批量测试：对每个密钥跑一遍和单 Key「测试连接」相同的三项检查（非流式 / 流式 / 工具调用）。
 * 顺序跑不并发——并发容易自己把限流打出来，测出来的失败反而不准。
 * 测完可以只重测失败项，或者把失败项一次性删掉（走二次确认）。
 */
@Composable
private fun MultiKeyTestDialog(
    provider: ProviderSetting.ApiKey,
    keys: List<String>,
    onRemoveKeys: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val providerManager = koinInject<ProviderManager>()
    val scope = rememberCoroutineScope()
    val models = remember(provider) { provider.models.filter { it.type == ModelType.CHAT } }
    var model by remember(provider) { mutableStateOf(models.firstOrNull()) }
    var showModelSheet by remember { mutableStateOf(false) }
    val results = remember { mutableStateMapOf<String, KeyTestStatus>() }
    var running by remember { mutableStateOf(false) }
    var tested by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    val okCount = results.values.count { it is KeyTestStatus.Ok }
    val warnCount = results.values.count { it is KeyTestStatus.Warn }
    val failedKeys = results.filterValues { it is KeyTestStatus.Fail }.keys.toList()

    fun runTest(targets: List<String>) {
        val testModel = model ?: return
        if (running || targets.isEmpty()) return
        running = true
        tested = 0
        total = targets.size
        scope.launch {
            try {
                targets.forEach { key ->
                    results[key] = KeyTestStatus.Running
                    results[key] = testSingleKey(providerManager, provider, key, testModel)
                    tested++
                }
            } finally {
                running = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(stringResource(R.string.setting_provider_page_test_connection)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.multi_key_test_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ModelField(
                    model = model,
                    onClick = { showModelSheet = true },
                    placeholder = stringResource(R.string.multi_key_test_model),
                )
                // 进度 / 汇总：跑的时候显示「已测 i/N」，跑完显示「可用 X · 警告 Y · 失败 Z」
                if (total > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (running) {
                                stringResource(R.string.multi_key_test_progress, tested, total)
                            } else {
                                stringResource(
                                    R.string.multi_key_test_summary,
                                    okCount,
                                    warnCount,
                                    failedKeys.size,
                                )
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (running) {
                            ContainedLoadingIndicator(modifier = Modifier.size(20.dp))
                        }
                    }
                }
                // 失败项处置：只重测失败项 / 一次性删掉
                if (!running && failedKeys.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = { runTest(failedKeys) }) {
                            Text(stringResource(R.string.multi_key_test_retry_failed))
                        }
                        TextButton(onClick = { showRemoveConfirm = true }) {
                            Text(
                                text = stringResource(
                                    R.string.multi_key_test_remove_failed,
                                    failedKeys.size,
                                ),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                keys.forEach { key ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = maskKey(key),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        when (val status = results[key] ?: KeyTestStatus.Idle) {
                            KeyTestStatus.Idle -> Text(
                                "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            KeyTestStatus.Running -> ContainedLoadingIndicator(modifier = Modifier.size(24.dp))

                            is KeyTestStatus.Ok -> Text(
                                stringResource(R.string.multi_key_test_ok),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.extendColors.green6,
                            )

                            is KeyTestStatus.Warn -> Text(
                                stringResource(R.string.multi_key_test_tool_not_called),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )

                            is KeyTestStatus.Fail -> Text(
                                status.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.extendColors.red6,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !running,
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { runTest(keys) },
                enabled = !running && model != null,
            ) {
                Text(stringResource(R.string.setting_provider_page_test))
            }
        },
    )

    // 删掉的是密钥原文、不可撤销，必须二次确认
    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = {
                Text(stringResource(R.string.multi_key_test_remove_failed, failedKeys.size))
            },
            text = {
                Text(stringResource(R.string.multi_key_clear_all_message, failedKeys.size))
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveKeys(failedKeys)
                    failedKeys.forEach { results.remove(it) }
                    showRemoveConfirm = false
                    onDismiss()
                }) {
                    Text(
                        stringResource(R.string.multi_key_clear_all),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

KT

my $fnT   = lineof(qr{^private fun MultiKeyTestDialog\($});
my $startT = $fnT - 1;
die "expected \@Composable above MultiKeyTestDialog, got: $L[$startT]" unless $L[$startT] =~ /^\@Composable\n$/;
my $sheetT = lineof(qr{^    if \(showModelSheet\) \{$}, $fnT);
splice(@L, $startT, $sheetT - $startT, $newTest);

# ============ B) KeyRow：补复制入口 ============
my $newKeyRow = <<'KT';
/** 密钥行：状态点（绿=可用/橙=暂停/灰=未用）+ 掩码 + 使用统计 + 复制/恢复/删除 */
@Composable
private fun KeyRow(
    keyValue: String,
    state: MultiKeyState?,
    onRecover: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val paused = state?.isPaused() == true
    val statusText = when {
        state != null && paused && state.pauseForever ->
            stringResource(R.string.multi_key_paused_forever)

        state != null && paused ->
            stringResource(R.string.multi_key_paused_until, pauseTimeFormat.format(Date(state.pausedUntil)))

        else -> stringResource(R.string.multi_key_available)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        // 4dp：两个 IconButton 自带内边距，再给 8dp 会把中间的掩码挤成省略号
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 状态点：绿=可用 / 橙=暂停中（点一下立即恢复）/ 灰=还没用过
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    when {
                        paused -> MaterialTheme.extendColors.orange6
                        state == null -> MaterialTheme.colorScheme.outlineVariant
                        else -> MaterialTheme.extendColors.green6
                    }
                )
                .then(if (paused) Modifier.clickable(onClick = onRecover) else Modifier),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = maskKey(keyValue),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.multi_key_uses, state?.uses ?: 0) + " · " + statusText,
                style = MaterialTheme.typography.labelSmall,
                color = if (paused) {
                    MaterialTheme.extendColors.red6
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (paused) {
            TextButton(onClick = onRecover) {
                Text(stringResource(R.string.multi_key_recover))
            }
        }
        // 掩码看得见但抄不走，补一个复制真实密钥的入口
        IconButton(onClick = onCopy) {
            Icon(
                HugeIcons.Copy01,
                contentDescription = stringResource(R.string.multi_key_copy),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                HugeIcons.Delete01,
                contentDescription = stringResource(R.string.multi_key_delete),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
KT

my $docK = lineof(qr{^/\*\* 密钥行});
my $fnK  = lineof(qr{^private fun KeyRow\($}, $docK);
my $endK = lineof(qr{^\}\n$}, $fnK);
splice(@L, $docK, $endK - $docK + 1, $newKeyRow);

# ============ C) 文件末尾追加：批量导入弹层 + 解析函数 ============
my $append = <<'KT';

/**
 * 批量导入：粘一整屏密钥进来，自动按换行 / 空白 / 中英文逗号分号顿号切分并去重，
 * 实时回显「识别到 T 个 · 新增 N 个 · 重复 M 个」，可选「追加」或「替换全部」。
 *
 * 原来只有一个单行输入框挂在密钥列表末尾，粘多行会被压成一行、也看不到解析结果，
 * 批量场景基本没法用；这里单独开一个底部弹层，输入区给足高度，解析结果即时可见。
 */
@Composable
private fun MultiKeyImportSheet(
    existing: List<String>,
    onImport: (incoming: List<String>, replace: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var replace by rememberSaveable { mutableStateOf(false) }
    val parsed = remember(text) { parseImportedKeys(text) }
    val existingSet = remember(existing) { existing.toSet() }
    val fresh = remember(parsed, existingSet) { parsed.filter { it !in existingSet } }
    val importable = if (replace) parsed.size else fresh.size

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.multi_key_import_title),
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            Text(
                text = stringResource(R.string.multi_key_import_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 132.dp),
                placeholder = { Text(stringResource(R.string.multi_key_import_placeholder)) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                minLines = 5,
                maxLines = 10,
            )
            if (parsed.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.multi_key_import_parsed,
                        parsed.size,
                        fresh.size,
                        parsed.size - fresh.size,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ConnectedChoiceButtons(
                options = listOf(false, true),
                selected = replace,
                onSelect = { replace = it },
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                minHeight = 40.dp,
            ) { isReplace, selected ->
                ConnectedChoiceLabel(
                    text = stringResource(
                        if (isReplace) R.string.multi_key_import_mode_replace
                        else R.string.multi_key_import_mode_append
                    ),
                    selected = selected,
                )
            }
            if (replace && existing.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.multi_key_import_replace_warn),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    onImport(if (replace) parsed else fresh, replace)
                    onDismiss()
                },
                enabled = importable > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.multi_key_import_action, importable))
            }
        }
    }
}

/**
 * 解析粘贴进来的密钥串：按换行 / 空白 / 中英文逗号分号顿号切分，
 * 顺手清掉粘贴常见的脏数据（首尾引号、反引号、`Bearer ` 前缀），最后去重保序。
 */
private fun parseImportedKeys(raw: String): List<String> =
    raw.split(Regex("[\\s,;，；、]+"))
        .map { item ->
            item.trim()
                .trim('"', '\'', '`')
                .removePrefix("Bearer ")
                .removePrefix("bearer ")
                .trim()
        }
        .filter { it.isNotBlank() }
        .distinct()
KT

push @L, $append;

my $s = join('', @L);
open(my $o, '>', $F) or die "write $F: $!"; print $o $s; close $o;
my $lines = () = $s =~ /\n/g;
print "multikey parts: keyrow + test dialog + import sheet done, lines=$lines\n";
