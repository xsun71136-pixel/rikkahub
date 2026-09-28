package me.rerere.rikkahub.ext.keys

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.*
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ext.ui.*
import org.koin.compose.koinInject

private data class KeyTestResult(val running: Boolean = false, val success: Boolean = false, val text: String = "")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderKeyManagerSheet(provider: ProviderSetting, onDismissRequest: () -> Unit, onProviderChange: (ProviderSetting) -> Unit) {
    val context = LocalContext.current
    val providerManager = koinInject<ProviderManager>()
    val scope = rememberCoroutineScope()
    val keys = provider.getProviderApiKeys().normalizedProviderApiKeys()
    val providerId = provider.id.toString()
    val health by KeyRotationPolicy.healthFlow.collectAsState()
    val policy by KeyRotationPolicy.policyFlow.collectAsState()
    val testModel = provider.models.firstOrNull { it.type == ModelType.CHAT }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(health, policy) {
        now = System.currentTimeMillis()
        while (policy.enabled && health[providerId].orEmpty().values.any { it.until > now && it.until != Long.MAX_VALUE }) {
            delay(1000); now = System.currentTimeMillis()
        }
    }
    fun record(key: ProviderApiKey): KeyHealthRecord? = if (!policy.enabled) null else health[providerId]?.get(key.value)?.takeIf { it.until > now }
    fun category(key: ProviderApiKey): Int = when {
        !key.enabled -> 2
        record(key) == null -> 1
        record(key)?.state == KeyHealthState.COOLDOWN -> 4
        else -> 3
    }
    val results = remember(provider.id) { mutableStateMapOf<String, KeyTestResult>() }
    val latestProvider by rememberUpdatedState(provider)
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var editingKey by remember { mutableStateOf<ProviderApiKey?>(null) }
    var deletingKey by remember { mutableStateOf<ProviderApiKey?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var bulk by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    val labels = listOf(R.string.polish_all, R.string.polish_ready, R.string.polish_disabled, R.string.polish_suspended, R.string.polish_cooldown)
    val filtered = keys.filter { (filter == 0 || category(it) == filter) &&
        (query.isBlank() || it.alias.contains(query.trim(), true) || it.value.contains(query.trim(), true)) }
    fun updateKeys(updated: List<ProviderApiKey>) {
        onProviderChange(latestProvider.copyWithApiKeyConfig(multiKeyEnabled = true, apiKeys = updated.normalizedProviderApiKeys()).syncEnabledApiKeysToLegacyField())
    }
    fun runTest(apiKey: ProviderApiKey) {
        val model = testModel ?: return
        if (results[apiKey.value]?.running == true) return
        results[apiKey.value] = KeyTestResult(running = true)
        scope.launch {
            val start = SystemClock.elapsedRealtime()
            try {
                withTimeout(30000) {
                    val single = provider.withSingleApiKeyForRequest(apiKey.value)
                    providerManager.getProviderByType(single).generateText(
                        providerSetting = single, messages = listOf(UIMessage.user("hello")),
                        params = TextGenerationParams(model = model, customHeaders = model.customHeaders, customBody = model.customBodies),
                    )
                }
                // Do not clear an existing suspension until the explicit probe succeeds.
                KeyRotationPolicy.clearKeyHealth(provider.id.toString(), apiKey.value)
                results[apiKey.value] = KeyTestResult(success = true, text = context.getString(R.string.polish_test_ok, SystemClock.elapsedRealtime() - start))
            } catch (error: TimeoutCancellationException) {
                results[apiKey.value] = KeyTestResult(text = context.getString(R.string.polish_test_timeout))
            } catch (error: CancellationException) {
                results.remove(apiKey.value)
                throw error
            } catch (error: Throwable) {
                KeyRotationPolicy.reportFailure(provider.id.toString(), apiKey.value, error)
                // Never echo arbitrary upstream bodies: they can contain other credentials.
                val code = (error as? ProviderHttpException)?.statusCode
                results[apiKey.value] = KeyTestResult(text = context.getString(R.string.polish_test_error, code?.toString() ?: error.javaClass.simpleName))
            }
        }
    }
    PolicyScreen(stringResource(R.string.setting_provider_page_multi_key_manager),
        stringResource(R.string.polish_keys_intro, provider.name), onDismissRequest) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                PolicyCard(stringResource(R.string.polish_pool_status)) {
                    Text(stringResource(R.string.setting_provider_page_multi_key_summary, keys.count { category(it) == 1 }, keys.size), style = MaterialTheme.typography.headlineSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        labels.forEachIndexed { index, label ->
                            val count = if (index == 0) keys.size else keys.count { category(it) == index }
                            FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text("${stringResource(label)} $count") })
                        }
                    }
                    if (keys.isNotEmpty() && keys.none { category(it) == 1 }) PolicyHint(stringResource(R.string.polish_no_ready), true)
                    if (!policy.enabled) PolicyHint(stringResource(R.string.polish_health_ignored))
                }
            }
            item {
                PolicyCard(stringResource(R.string.polish_rotation)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ProviderKeyStrategy.entries.forEachIndexed { index, value ->
                            SegmentedButton(selected = provider.getProviderKeyStrategy() == value,
                                onClick = { onProviderChange(provider.copyWithApiKeyConfig(keyStrategy = value)) },
                                shape = SegmentedButtonDefaults.itemShape(index, 2)) {
                                Text(stringResource(if (value == ProviderKeyStrategy.RANDOM) R.string.setting_provider_page_multi_key_strategy_random else R.string.setting_provider_page_multi_key_strategy_round_robin))
                            }
                        }
                    }
                    PolicyHint(stringResource(if (provider.getProviderKeyStrategy() == ProviderKeyStrategy.RANDOM) R.string.polish_random_desc else R.string.polish_round_desc))
                }
            }
            item {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.polish_search_keys)) }, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text(stringResource(R.string.polish_clear)) } })
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { editingKey = ProviderApiKey() }) { Text(stringResource(R.string.setting_provider_page_multi_key_add)) }
                    FilledTonalButton(onClick = { showImportDialog = true }) { Text(stringResource(R.string.setting_provider_page_multi_key_import)) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { bulk = "enable" }, enabled = keys.any { !it.enabled }) { Text(stringResource(R.string.polish_enable_all)) }
                    TextButton(onClick = { bulk = "disable" }, enabled = keys.any { it.enabled }) { Text(stringResource(R.string.polish_disable_all)) }
                    TextButton(onClick = { bulk = "restore" }, enabled = health[providerId].orEmpty().isNotEmpty()) { Text(stringResource(R.string.setting_provider_page_multi_key_restore_all)) }
                }
                if (notice.isNotEmpty()) PolicyHint(notice)
                PolicyHint(stringResource(R.string.polish_test_cost))
                if (testModel == null) PolicyHint(stringResource(R.string.setting_provider_page_multi_key_test_needs_model), true)
            }
            if (filtered.isEmpty()) item {
                PolicyCard(stringResource(if (keys.isEmpty()) R.string.polish_empty_pool else R.string.polish_no_matches)) {
                    PolicyHint(stringResource(if (keys.isEmpty()) R.string.setting_provider_page_multi_key_import_desc else R.string.polish_search_hint))
                }
            }
            itemsIndexed(filtered, key = { _, key -> key.id.toString() }) { _, apiKey ->
                val index = keys.indexOfFirst { it.id == apiKey.id }
                val rec = record(apiKey)
                val state = category(apiKey)
                val result = results[apiKey.value]
                PolicyCard(apiKey.alias.ifBlank { stringResource(R.string.setting_provider_page_multi_key_default_alias, index + 1) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(maskProviderApiKey(apiKey.value), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(labels[state]), color = if (state == 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                        val suspendedByHealth = rec != null && rec.state != KeyHealthState.COOLDOWN
                        Switch(checked = apiKey.enabled && !suspendedByHealth, onCheckedChange = { enabled ->
                            if (enabled) KeyRotationPolicy.clearKeyHealth(providerId, apiKey.value)
                            updateKeys(keys.map { if (it.id == apiKey.id) it.copy(enabled = enabled) else it })
                        })
                    }
                    if (rec != null) {
                        val cause = when (rec.reason) { "quota" -> R.string.polish_quota_rule; "cooldown" -> R.string.polish_rate_rule; else -> R.string.polish_invalid_rule }
                        PolicyHint(stringResource(cause) + " · " + if (rec.until == Long.MAX_VALUE) stringResource(R.string.polish_manual_recovery) else stringResource(R.string.polish_recovers_in, formatRemaining(rec.until - now)))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { runTest(apiKey) }, enabled = testModel != null && result?.running != true) {
                            if (result?.running == true) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.setting_provider_page_multi_key_test))
                        }
                        TextButton(onClick = { editingKey = apiKey }, enabled = result?.running != true) { Text(stringResource(R.string.common_edit)) }
                        TextButton(onClick = { deletingKey = apiKey }, enabled = result?.running != true) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
                        if (rec != null) TextButton(onClick = { KeyRotationPolicy.clearKeyHealth(providerId, apiKey.value) }) { Text(stringResource(R.string.polish_restore)) }
                    }
                    if (result != null && !result.running) PolicyHint(result.text, warning = !result.success)
                }
            }
        }
    }
    editingKey?.let { initial ->
        ProviderApiKeyEditDialog(initial = initial, existingValues = keys.map { it.value }.toSet(), onDismissRequest = { editingKey = null }, onConfirm = { edited ->
            updateKeys(if (keys.any { it.id == edited.id }) keys.map { if (it.id == edited.id) edited else it } else keys + edited)
            editingKey = null
        })
    }
    if (showImportDialog) ProviderApiKeyImportDialog(initialText = "", existingValues = keys.map { it.value }.toSet(), onDismissRequest = { showImportDialog = false }, onImport = { raw ->
        val added = splitProviderApiKeys(raw).filterNot { value -> keys.any { it.value == value } }.map { ProviderApiKey(value = it) }
        updateKeys(keys + added)
        notice = context.getString(R.string.setting_provider_page_multi_key_imported, added.size)
        showImportDialog = false
    })
    deletingKey?.let { key ->
        PolicyConfirm(stringResource(R.string.setting_provider_page_multi_key_delete_title), stringResource(R.string.setting_provider_page_multi_key_delete_desc, key.alias.ifBlank { maskProviderApiKey(key.value) }), { deletingKey = null }, {
            updateKeys(keys.filterNot { it.id == key.id }); deletingKey = null
        })
    }
    if (bulk.isNotBlank()) {
        val title = when (bulk) { "enable" -> R.string.polish_enable_all; "disable" -> R.string.polish_disable_all; else -> R.string.setting_provider_page_multi_key_restore_all }
        PolicyConfirm(stringResource(title), stringResource(R.string.polish_bulk_desc), { bulk = "" }, {
            when (bulk) {
                "restore" -> KeyRotationPolicy.clearProviderHealth(providerId)
                "enable" -> updateKeys(keys.map { it.copy(enabled = true) })
                "disable" -> updateKeys(keys.map { it.copy(enabled = false) })
            }
            bulk = ""
        })
    }
}

private fun formatRemaining(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return when {
        seconds >= 86400 -> "${seconds / 86400}d ${(seconds % 86400) / 3600}h"
        seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

@Composable
private fun ProviderApiKeyEditDialog(
    initial: ProviderApiKey,
    existingValues: Set<String>,
    onDismissRequest: () -> Unit,
    onConfirm: (ProviderApiKey) -> Unit,
) {
    var value by remember { mutableStateOf(initial.value) }
    var alias by remember { mutableStateOf(initial.alias) }
    var keyVisible by remember { mutableStateOf(false) }
    val duplicate = value.trim() != initial.value && value.trim() in existingValues
    val singleKeyValid = !duplicate && value.isNotBlank() && !Regex("[\\s,]+").containsMatchIn(value.trim())

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                stringResource(
                    if (initial.value.isBlank()) {
                        R.string.setting_provider_page_multi_key_add
                    } else {
                        R.string.setting_provider_page_multi_key_edit
                    }
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    isError = value.isNotBlank() && !singleKeyValid,
                    supportingText = {
                        if (value.isNotBlank() && !singleKeyValid) Text(stringResource(if (duplicate) R.string.polish_duplicate_key else R.string.multi_key_single_only))
                    },
                    label = { Text(stringResource(R.string.setting_provider_page_api_key)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (keyVisible) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                if (keyVisible) HugeIcons.ViewOff else HugeIcons.View,
                                contentDescription = stringResource(R.string.polish_visibility),
                            )
                        }
                    },
                )
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text(stringResource(R.string.setting_provider_page_multi_key_alias)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(initial.copy(value = value.trim(), alias = alias.trim()))
                },
                enabled = singleKeyValid,
            ) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ProviderApiKeyImportDialog(
    initialText: String,
    existingValues: Set<String>,
    onDismissRequest: () -> Unit,
    onImport: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    val parsed = remember(text) { splitProviderApiKeys(text) }
    val parsedCount = parsed.count { it !in existingValues }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.setting_provider_page_multi_key_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.setting_provider_page_multi_key_import_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 220.dp),
                    placeholder = {
                        Text(
                            stringResource(R.string.setting_provider_page_multi_key_import_input),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.polish_import_preview, parsedCount, parsed.size - parsedCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(text) }, enabled = parsedCount > 0) {
                Text(stringResource(R.string.setting_provider_page_multi_key_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
