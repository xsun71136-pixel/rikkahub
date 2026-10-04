package me.rerere.rikkahub.ext.retry

import androidx.compose.foundation.layout.*
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ArrowRight01
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ext.ui.PolicyConfirm
import me.rerere.rikkahub.ext.ui.PolicyToggle
import me.rerere.rikkahub.ext.keys.CompactKeySheet

@Composable
fun AutoRetrySettingsSheet(visible: Boolean, onDismissRequest: () -> Unit, settings: Settings, onUpdateSettings: (Settings) -> Unit) {
    if (!visible) return
    val originalNetwork = settings.networkSetting
    var enabled by rememberSaveable { mutableStateOf(originalNetwork.enableAutoRetry) }
    var serialized by rememberSaveable { mutableStateOf(Json.encodeToString(originalNetwork.autoRetry.clamped())) }
    val config = remember(serialized) { Json.decodeFromString<AutoRetryConfig>(serialized) }
    fun change(value: AutoRetryConfig) { serialized = Json.encodeToString(value.clamped()) }
    val dirty = enabled != originalNetwork.enableAutoRetry || config != originalNetwork.autoRetry.clamped()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var discard by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    var partialConfirm by remember { mutableStateOf(false) }
    var pendingSafety by remember { mutableStateOf<AutoRetryConfig?>(null) }
    var timingEditor by remember { mutableStateOf(false) }
    var ruleEditor by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val close = { if (dirty) discard = true else onDismissRequest() }

    CompactKeySheet(
        title = stringResource(R.string.auto_retry_sheet_title),
        subtitle = stringResource(R.string.polish_retry_intro),
        onClose = close,
        footer = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        TextButton(onClick = { menu = true }) { Text(stringResource(R.string.key3_more)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.auto_retry_reset_all)) }, onClick = { reset = true; menu = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.key3_help)) }, onClick = { ruleEditor = "help"; menu = false })
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = close) { Text(stringResource(R.string.cancel)) }
                    Button(enabled = dirty, onClick = {
                        onUpdateSettings(settings.copy(networkSetting = originalNetwork.copy(enableAutoRetry = enabled, autoRetry = config)))
                        onDismissRequest()
                    }) { Text(stringResource(if (dirty) R.string.polish_save_changes else R.string.polish_saved)) }
                }
            }
        },
    ) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.setting_page_preferences_network_auto_retry), style = MaterialTheme.typography.bodyLarge)
                        Text(if (enabled) stringResource(R.string.key3_on) else stringResource(R.string.key3_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(enabled, { enabled = it })
                }
                HorizontalDivider()
            }
            if (tab == 0) {
                item {
                    RetrySummary(config, enabled)
                    HorizontalDivider()
                }
                item {
                    RetryPresetRow(config, onSelect = ::change)
                    HorizontalDivider()
                }
                item {
                    CompactRetryRow(stringResource(R.string.polish_retry_timing), retryTimingSummary(config)) { timingEditor = true }
                    CompactRetryRow(stringResource(R.string.polish_safety), retrySafetySummary(config)) { ruleEditor = "safety" }
                    CompactRetryRow(stringResource(R.string.auto_retry_status_codes), stringResource(R.string.retry_codes_count, config.retryStatusCodes.size)) { tab = 1 }
                }
            } else {
                item {
                    CompactRetryRow(stringResource(R.string.auto_retry_status_codes), stringResource(R.string.retry_codes_count, config.retryStatusCodes.size)) { ruleEditor = "status" }
                    CompactRetryRow(stringResource(R.string.auto_retry_keywords), stringResource(R.string.retry_keywords_count, config.retryKeywords.size)) { ruleEditor = "retry" }
                    CompactRetryRow(stringResource(R.string.auto_retry_stop_keywords), stringResource(R.string.retry_stop_count, config.stopKeywords.size)) { ruleEditor = "stop" }
                }
            }
        }
    }

    if (discard) PolicyConfirm(stringResource(R.string.polish_discard), stringResource(R.string.polish_discard_desc), { discard = false }, onDismissRequest)
    if (reset) PolicyConfirm(stringResource(R.string.auto_retry_reset_all), stringResource(R.string.polish_reset_desc), { reset = false }, { enabled = true; change(AutoRetryConfig()); reset = false })
    if (partialConfirm) PolicyConfirm(stringResource(R.string.auto_retry_partial), stringResource(R.string.auto_retry_partial_desc), { pendingSafety = null; partialConfirm = false }, {
        change((pendingSafety ?: config).copy(retryAfterPartialResponse = true))
        pendingSafety = null
        partialConfirm = false
    })
    if (timingEditor) RetryTimingEditor(config, { timingEditor = false }) { change(it); timingEditor = false }
    when (ruleEditor) {
        "safety" -> RetrySafetyEditor(config, { ruleEditor = "" }) { value ->
            if (value.retryAfterPartialResponse && !config.retryAfterPartialResponse) {
                pendingSafety = value
                partialConfirm = true
            } else change(value)
            ruleEditor = ""
        }
        "status" -> StatusCodeEditor(config, { ruleEditor = "" }) { change(it); ruleEditor = "" }
        "retry", "stop" -> KeywordEditor(config, ruleEditor == "retry", { ruleEditor = "" }) { change(it); ruleEditor = "" }
        "help" -> AlertDialog(onDismissRequest = { ruleEditor = "" }, title = { Text(stringResource(R.string.key3_help)) }, text = { Text(stringResource(R.string.polish_retry_key_separate)) }, confirmButton = { TextButton(onClick = { ruleEditor = "" }) { Text(stringResource(R.string.common_confirm)) } })
    }
}

@Composable private fun RetrySummary(config: AutoRetryConfig, enabled: Boolean) {
    val delays = (0 until config.maxRetries).map { RetryPolicy.backoffDelay(it, config.copy(jitter = false)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(stringResource(R.string.polish_retry_overview), style = MaterialTheme.typography.titleSmall)
        Text(if (enabled) stringResource(R.string.polish_retry_attempts, config.maxRetries + 1) else stringResource(R.string.polish_retry_off), style = MaterialTheme.typography.bodyMedium)
        if (enabled && delays.isNotEmpty()) {
            Text(delays.joinToString(" → ") { formatRetryDuration(it) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.polish_retry_total, (delays.sum() / 1000.0).toString()), style = MaterialTheme.typography.bodySmall)
            if (config.jitter) Text(stringResource(R.string.polish_jitter_preview), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun RetryPresetRow(config: AutoRetryConfig, onSelect: (AutoRetryConfig) -> Unit) {
    val presets = listOf(R.string.polish_recommended to AutoRetryConfig(), R.string.polish_fast to AutoRetryConfig(maxRetries = 2, initialDelayMs = 500, maxDelayMs = 5000), R.string.polish_patient to AutoRetryConfig(maxRetries = 5, initialDelayMs = 2000, maxDelayMs = 60000))
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.polish_quick_setup), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { presets.forEach { (label, value) -> FilterChip(selected = config == value, onClick = { onSelect(value) }, label = { Text(stringResource(label)) }) } }
    }
}

@Composable private fun CompactRetryRow(title: String, summary: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(HugeIcons.ArrowRight01, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun retryTimingSummary(c: AutoRetryConfig) = stringResource(R.string.retry_timing_summary, c.maxRetries, c.initialDelayMs, c.maxDelayMs, c.multiplier)
@Composable private fun retrySafetySummary(c: AutoRetryConfig) = stringResource(if (c.retryAfterPartialResponse) R.string.retry_partial_on else R.string.retry_partial_protected)
private fun formatRetryDuration(ms: Long): String = if (ms % 1000L == 0L) "${ms / 1000}s" else "${ms}ms"

@Composable private fun RetryTimingEditor(initial: AutoRetryConfig, onDismiss: () -> Unit, onSave: (AutoRetryConfig) -> Unit) {
    var retries by rememberSaveable { mutableStateOf(initial.maxRetries.toString()) }
    var initialMs by rememberSaveable { mutableStateOf(initial.initialDelayMs.toString()) }
    var multiplier by rememberSaveable { mutableStateOf(initial.multiplier.toString()) }
    var maxMs by rememberSaveable { mutableStateOf(initial.maxDelayMs.toString()) }
    var jitter by rememberSaveable { mutableStateOf(initial.jitter) }
    val initialValue = initialMs.toLongOrNull() ?: -1L
    val maxValue = maxMs.toLongOrNull() ?: -1L
    val valid = (retries.toIntOrNull() ?: -1) in 0..10 && initialValue in 0L..10000L && maxValue in initialValue..120000L && multiplier.toDoubleOrNull()?.let { it.isFinite() && it in 1.0..5.0 } == true
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.polish_retry_timing)) }, text = {
        Column(Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .65f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RetryNumberField(stringResource(R.string.auto_retry_max_retries), retries, { retries = it }, "0–10", KeyboardType.Number)
            RetryNumberField(stringResource(R.string.auto_retry_initial_delay), initialMs, { initialMs = it }, "0–10000 ms", KeyboardType.Number)
            RetryNumberField(stringResource(R.string.auto_retry_multiplier), multiplier, { multiplier = it }, "1–5", KeyboardType.Decimal)
            RetryNumberField(stringResource(R.string.auto_retry_max_delay), maxMs, { maxMs = it }, "0–120000 ms", KeyboardType.Number)
            PolicyToggle(stringResource(R.string.auto_retry_jitter), stringResource(R.string.auto_retry_jitter_desc), jitter, { jitter = it })
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = { onSave(initial.copy(maxRetries = retries.toInt(), initialDelayMs = initialMs.toLong(), multiplier = multiplier.toDouble(), maxDelayMs = maxMs.toLong(), jitter = jitter).clamped()) }) { Text(stringResource(R.string.common_confirm)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun RetrySafetyEditor(initial: AutoRetryConfig, onDismiss: () -> Unit, onSave: (AutoRetryConfig) -> Unit) {
    var network by rememberSaveable { mutableStateOf(initial.retryOnNetworkError) }
    var partial by rememberSaveable { mutableStateOf(initial.retryAfterPartialResponse) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.polish_safety)) }, text = { Column(Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .65f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PolicyToggle(stringResource(R.string.auto_retry_on_network_error), stringResource(R.string.auto_retry_on_network_error_desc), network, { network = it })
        PolicyToggle(stringResource(R.string.auto_retry_partial), stringResource(R.string.auto_retry_partial_desc), partial, { partial = it })
    } }, confirmButton = { TextButton(onClick = { onSave(initial.copy(retryOnNetworkError = network, retryAfterPartialResponse = partial)) }) { Text(stringResource(R.string.common_confirm)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun StatusCodeEditor(initial: AutoRetryConfig, onDismiss: () -> Unit, onSave: (AutoRetryConfig) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial.retryStatusCodes.sorted().joinToString(", ")) }
    val codes = text.split(Regex("[\\s,，]+" )).filter { it.isNotBlank() }
    val valid = codes.all { (it.toIntOrNull() ?: -1) in 400..599 }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.auto_retry_status_codes)) }, text = { Column { Text(stringResource(R.string.polish_status_priority), style = MaterialTheme.typography.bodySmall); RetryTextField(text, { text = it }, stringResource(R.string.auto_retry_status_code_hint), !valid) } }, confirmButton = { TextButton(enabled = valid, onClick = { onSave(initial.copy(retryStatusCodes = codes.map { it.toInt() }.toSet().clampedCodes())) }) { Text(stringResource(R.string.common_confirm)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

private fun Set<Int>.clampedCodes() = filter { it in 400..599 }.toSet()

@Composable private fun KeywordEditor(initial: AutoRetryConfig, retry: Boolean, onDismiss: () -> Unit, onSave: (AutoRetryConfig) -> Unit) {
    var text by rememberSaveable { mutableStateOf((if (retry) initial.retryKeywords else initial.stopKeywords).joinToString("\n")) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(if (retry) R.string.auto_retry_keywords else R.string.auto_retry_stop_keywords)) }, text = { Column { Text(stringResource(if (retry) R.string.polish_retry_keywords_desc else R.string.auto_retry_stop_keywords_desc), style = MaterialTheme.typography.bodySmall); RetryTextField(text, { text = it }, stringResource(R.string.key3_words), false) } }, confirmButton = { TextButton(onClick = { val words = text.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct(); onSave(if (retry) initial.copy(retryKeywords = words) else initial.copy(stopKeywords = words)) }) { Text(stringResource(R.string.common_confirm)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun RetryTextField(value: String, onChange: (String) -> Unit, label: String, error: Boolean) {
    OutlinedTextField(value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 8, label = { Text(label) }, isError = error, supportingText = { if (error) Text(stringResource(R.string.polish_status_invalid)) })
}

@Composable private fun RetryNumberField(title: String, value: String, onChange: (String) -> Unit, range: String, type: KeyboardType) {
    OutlinedTextField(value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(title) }, supportingText = { Text(range) }, keyboardOptions = KeyboardOptions(keyboardType = type))
}
