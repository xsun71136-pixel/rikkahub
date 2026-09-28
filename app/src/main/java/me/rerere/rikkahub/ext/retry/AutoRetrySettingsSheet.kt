package me.rerere.rikkahub.ext.retry

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ext.ui.*
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoRetrySettingsSheet(visible: Boolean, onDismissRequest: () -> Unit, settings: Settings, onUpdateSettings: (Settings) -> Unit) {
    if (!visible) return
    val network = settings.networkSetting
    var enabled by rememberSaveable { mutableStateOf(network.enableAutoRetry) }
    var serialized by rememberSaveable { mutableStateOf(Json.encodeToString(network.autoRetry.clamped())) }
    val config = remember(serialized) { Json.decodeFromString<AutoRetryConfig>(serialized) }
    fun change(value: AutoRetryConfig) { serialized = Json.encodeToString(value.clamped()) }
    val dirty = enabled != network.enableAutoRetry || config != network.autoRetry.clamped()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var discard by remember { mutableStateOf(false) }
    var partialConfirm by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    val close = { if (dirty) discard = true else onDismissRequest() }
    val presets = listOf(
        R.string.polish_recommended to AutoRetryConfig(),
        R.string.polish_fast to AutoRetryConfig(maxRetries = 2, initialDelayMs = 500, maxDelayMs = 5000),
        R.string.polish_patient to AutoRetryConfig(maxRetries = 5, initialDelayMs = 2000, maxDelayMs = 60000),
    )
    PolicyScreen(
        title = stringResource(R.string.auto_retry_sheet_title),
        subtitle = stringResource(R.string.polish_retry_intro),
        onClose = close,
        footer = {
            PolicyFooter(dirty, onReset = { reset = true }, onSave = {
                onUpdateSettings(settings.copy(networkSetting = network.copy(enableAutoRetry = enabled, autoRetry = config.clamped())))
                onDismissRequest()
            })
        },
    ) {
        SecondaryTabRow(selectedTabIndex = tab) {
            listOf(R.string.polish_basic, R.string.polish_rules).forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(title)) })
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                PolicyCard(stringResource(R.string.polish_retry_overview)) {
                    PolicyToggle(stringResource(R.string.setting_page_preferences_network_auto_retry), stringResource(R.string.auto_retry_master_switch_desc), enabled, { enabled = it })
                    val delays = (0 until config.maxRetries).map { RetryPolicy.backoffDelay(it, config.copy(jitter = false)) }
                    Text(stringResource(R.string.polish_retry_attempts, if (enabled) config.maxRetries + 1 else 1), style = MaterialTheme.typography.titleLarge)
                    if (enabled && delays.isNotEmpty()) {
                        Text(delays.joinToString(" → ") { "${it / 1000.0}s" }, color = MaterialTheme.colorScheme.primary)
                        PolicyHint(stringResource(R.string.polish_retry_total, "${delays.sum() / 1000.0}"))
                        if (config.jitter) PolicyHint(stringResource(R.string.polish_jitter_preview))
                    } else PolicyHint(stringResource(R.string.polish_retry_off))
                }
            }
            if (tab == 0) {
                item {
                    PolicyCard(stringResource(R.string.polish_quick_setup), stringResource(R.string.polish_preset_desc)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            presets.forEach { (label, value) ->
                                FilterChip(selected = config == value, onClick = { change(value) }, label = { Text(stringResource(label)) })
                            }
                        }
                        if (presets.none { it.second == config }) PolicyHint(stringResource(R.string.polish_custom))
                    }
                }
                item {
                    PolicyCard(stringResource(R.string.polish_retry_timing)) {
                        PolicySlider(stringResource(R.string.auto_retry_max_retries), config.maxRetries.toFloat(), 0f..10f, 9, "${config.maxRetries}") { change(config.copy(maxRetries = it.roundToInt())) }
                        PolicySlider(stringResource(R.string.auto_retry_initial_delay), config.initialDelayMs.toFloat(), 0f..10000f, 39, "${config.initialDelayMs / 1000.0}s", exactScale = 1000f, exactUnit = "s") { change(config.copy(initialDelayMs = (it / 250).roundToInt() * 250L)) }
                        PolicySlider(stringResource(R.string.auto_retry_multiplier), config.multiplier.toFloat(), 1f..5f, 7, "${config.multiplier}×") { change(config.copy(multiplier = (it * 2).roundToInt() / 2.0)) }
                        PolicySlider(stringResource(R.string.auto_retry_max_delay), config.maxDelayMs.toFloat(), 0f..120000f, 119, "${config.maxDelayMs / 1000}s", exactScale = 1000f, exactUnit = "s") { change(config.copy(maxDelayMs = (it / 1000).roundToInt() * 1000L)) }
                        PolicyToggle(stringResource(R.string.auto_retry_jitter), stringResource(R.string.auto_retry_jitter_desc), config.jitter, { change(config.copy(jitter = it)) })
                    }
                }
                item {
                    PolicyCard(stringResource(R.string.polish_safety)) {
                        PolicyToggle(stringResource(R.string.auto_retry_on_network_error), stringResource(R.string.auto_retry_on_network_error_desc), config.retryOnNetworkError, { change(config.copy(retryOnNetworkError = it)) })
                        PolicyToggle(stringResource(R.string.auto_retry_partial), stringResource(R.string.auto_retry_partial_desc), config.retryAfterPartialResponse, {
                            if (it) partialConfirm = true else change(config.copy(retryAfterPartialResponse = false))
                        })
                        PolicyHint(stringResource(R.string.polish_retry_key_separate))
                    }
                }
            } else {
                item {
                    PolicyCard(stringResource(R.string.auto_retry_status_codes), stringResource(R.string.polish_status_priority)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (AutoRetryConfig.DEFAULT_RETRY_STATUS_CODES + config.retryStatusCodes).sorted().forEach { code ->
                                FilterChip(selected = code in config.retryStatusCodes, onClick = {
                                    change(config.copy(retryStatusCodes = if (code in config.retryStatusCodes) config.retryStatusCodes - code else config.retryStatusCodes + code))
                                }, label = { Text("$code") })
                            }
                        }
                        RuleInput(numeric = true) { value -> change(config.copy(retryStatusCodes = config.retryStatusCodes + value.toInt())) }
                    }
                }
                item {
                    KeywordCard(stringResource(R.string.auto_retry_keywords), stringResource(R.string.polish_retry_keywords_desc), config.retryKeywords) {
                        change(config.copy(retryKeywords = it))
                    }
                }
                item {
                    KeywordCard(stringResource(R.string.auto_retry_stop_keywords), stringResource(R.string.auto_retry_stop_keywords_desc), config.stopKeywords) {
                        change(config.copy(stopKeywords = it))
                    }
                }
            }
        }
    }
    if (discard) PolicyConfirm(stringResource(R.string.polish_discard), stringResource(R.string.polish_discard_desc), { discard = false }, onDismissRequest)
    if (reset) PolicyConfirm(stringResource(R.string.auto_retry_reset_all), stringResource(R.string.polish_reset_desc), { reset = false }, { enabled = true; change(AutoRetryConfig()); reset = false })
    if (partialConfirm) PolicyConfirm(stringResource(R.string.auto_retry_partial), stringResource(R.string.auto_retry_partial_desc), { partialConfirm = false }, { change(config.copy(retryAfterPartialResponse = true)); partialConfirm = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordCard(title: String, description: String, words: List<String>, onChange: (List<String>) -> Unit) {
    PolicyCard(title, description) {
        if (words.isEmpty()) PolicyHint(stringResource(R.string.polish_no_rules))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            words.forEach { word ->
                InputChip(selected = false, onClick = { onChange(words - word) }, label = { Text(word) }, trailingIcon = { Text("×") })
            }
        }
        PolicyHint(stringResource(R.string.polish_tap_remove))
        RuleInput(numeric = false) { onChange((words + it).distinct()) }
    }
}

@Composable
private fun RuleInput(numeric: Boolean, onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val invalid = numeric && text.isNotBlank() && (text.toIntOrNull() ?: -1) !in 400..599
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.weight(1f),
            label = { Text(stringResource(if (numeric) R.string.auto_retry_status_code_hint else R.string.auto_retry_keyword_hint)) },
            isError = invalid, supportingText = { if (invalid) Text(stringResource(R.string.polish_status_invalid)) },
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text))
        TextButton(enabled = text.isNotBlank() && !invalid, onClick = { onAdd(text.trim()); text = "" }) { Text(stringResource(R.string.auto_retry_add)) }
    }
}
