package me.rerere.rikkahub.ext.keys

import android.os.SystemClock
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
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
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Connect
import androidx.compose.ui.text.style.TextOverflow
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
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
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
    CompactKeySheet(stringResource(R.string.setting_provider_page_multi_key_manager),
        stringResource(R.string.setting_provider_page_multi_key_summary, keys.count { category(it) == 1 }, keys.size), onDismissRequest) {
        LazyColumn(contentPadding = PaddingValues(bottom = 4.dp)) {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ProviderKeyStrategy.entries.forEachIndexed { index, value ->
                        SegmentedButton(selected = provider.getProviderKeyStrategy() == value,
                            onClick = { onProviderChange(provider.copyWithApiKeyConfig(keyStrategy = value)) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2)) {
                            Text(stringResource(if (value == ProviderKeyStrategy.RANDOM) R.string.setting_provider_page_multi_key_strategy_random else R.string.setting_provider_page_multi_key_strategy_round_robin))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { editingKey = ProviderApiKey() }) { Text(stringResource(R.string.setting_provider_page_multi_key_add)) }
                    TextButton(onClick = { showImportDialog = true }) { Text(stringResource(R.string.setting_provider_page_multi_key_import)) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        searchOpen = !searchOpen
                        if (!searchOpen) { query = ""; filter = 0 }
                    }) { Icon(HugeIcons.Search01, stringResource(R.string.polish_search_keys)) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(HugeIcons.MoreVertical, stringResource(R.string.key3_more)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.polish_enable_all)) }, onClick = { bulk = "enable"; menuOpen = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.polish_disable_all)) }, onClick = { bulk = "disable"; menuOpen = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.setting_provider_page_multi_key_restore_all)) }, onClick = { bulk = "restore"; menuOpen = false })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text(stringResource(R.string.key3_help)) }, onClick = { helpOpen = true; menuOpen = false })
                        }
                    }
                }
                if (searchOpen) Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text(stringResource(R.string.polish_search_keys)) }, modifier = Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { filterOpen = true }) { Text(stringResource(labels[filter])) }
                        DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                            labels.forEachIndexed { index, label -> DropdownMenuItem(text = { Text(stringResource(label)) },
                                onClick = { filter = index; filterOpen = false }) }
                        }
                    }
                }
                if (notice.isNotEmpty()) Text(notice, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                if (!policy.enabled) Text(stringResource(R.string.key3_policy_off), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(top = 4.dp))
            }
            if (filtered.isEmpty()) item {
                Text(stringResource(if (keys.isEmpty()) R.string.polish_empty_pool else R.string.polish_no_matches),
                    Modifier.fillMaxWidth().padding(20.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(filtered, key = { _, key -> key.id.toString() }) { _, apiKey ->
                val index = keys.indexOfFirst { it.id == apiKey.id }
                val rec = record(apiKey)
                val state = category(apiKey)
                val result = results[apiKey.value]
                var more by remember(apiKey.id) { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(apiKey.alias.ifBlank { stringResource(R.string.setting_provider_page_multi_key_default_alias, index + 1) },
                                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(maskProviderApiKey(apiKey.value), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (state != 1) Text(stringResource(labels[state]) + if (rec != null && rec.until != Long.MAX_VALUE) " · " + formatRemaining(rec.until - now) else "",
                                style = MaterialTheme.typography.labelSmall, color = if (state == 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val suspendedByHealth = rec != null && rec.state != KeyHealthState.COOLDOWN
                        Switch(checked = apiKey.enabled && !suspendedByHealth, onCheckedChange = { enabled ->
                            if (enabled) KeyRotationPolicy.clearKeyHealth(providerId, apiKey.value)
                            updateKeys(keys.map { if (it.id == apiKey.id) it.copy(enabled = enabled) else it })
                        })
                        IconButton(onClick = { runTest(apiKey) }, enabled = testModel != null && result?.running != true) {
                            if (result?.running == true) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(HugeIcons.Connect, stringResource(R.string.setting_provider_page_multi_key_test), Modifier.size(20.dp))
                        }
                        Box {
                            IconButton(onClick = { more = true }) { Icon(HugeIcons.MoreVertical, stringResource(R.string.key3_more), Modifier.size(20.dp)) }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) }, enabled = result?.running != true,
                                    onClick = { editingKey = apiKey; more = false })
                                if (rec != null) DropdownMenuItem(text = { Text(stringResource(R.string.polish_restore)) },
                                    onClick = { KeyRotationPolicy.clearKeyHealth(providerId, apiKey.value); more = false })
                                DropdownMenuItem(text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }, enabled = result?.running != true,
                                    onClick = { deletingKey = apiKey; more = false })
                            }
                        }
                    }
                    if (result != null && !result.running) Text(result.text, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.labelSmall, color = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                }
            }
        }
    }
    if (helpOpen) AlertDialog(onDismissRequest = { helpOpen = false }, title = { Text(stringResource(R.string.key3_help)) },
        text = { Text(stringResource(R.string.polish_test_cost) + "\n\n" + stringResource(R.string.polish_bulk_desc) +
            if (testModel == null) "\n\n" + stringResource(R.string.setting_provider_page_multi_key_test_needs_model) else "") },
        confirmButton = { TextButton(onClick = { helpOpen = false }) { Text(stringResource(R.string.common_confirm)) } })
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
            Column(Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.6f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            Column(Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.6f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
