package me.rerere.rikkahub.ext.keys

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.util.KeyFailureAction
import me.rerere.ai.util.KeyManagementPolicy
import me.rerere.ai.util.KeyRotationPolicy
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ext.ui.*
import kotlin.math.roundToInt

@Composable
fun KeyPolicySettingsScreen(visible: Boolean, onDismissRequest: () -> Unit, settings: Settings, onUpdateSettings: (Settings) -> Unit) {
    if (!visible) return
    val original = settings.networkSetting.keyManagement.clamped()
    var serialized by rememberSaveable { mutableStateOf(Json.encodeToString(original)) }
    val config = remember(serialized) { Json.decodeFromString<KeyManagementPolicy>(serialized) }
    fun change(value: KeyManagementPolicy) { serialized = Json.encodeToString(value.clamped()) }
    val dirty = original != config
    var confirm by remember { mutableStateOf("") }
    val health by KeyRotationPolicy.healthFlow.collectAsState()
    PolicyScreen(title = stringResource(R.string.polish_key_policy), subtitle = stringResource(R.string.polish_policy_scope),
        onClose = { if (dirty) confirm = "discard" else onDismissRequest() },
        footer = { PolicyFooter(dirty, onReset = { confirm = "reset" }, onSave = {
            onUpdateSettings(settings.copy(networkSetting = settings.networkSetting.copy(keyManagement = config)))
            onDismissRequest()
        }) },
    ) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                PolicyCard(stringResource(R.string.polish_auto_protection)) {
                    PolicyToggle(stringResource(R.string.polish_policy_enabled), stringResource(R.string.polish_policy_disabled_desc), config.enabled, { change(config.copy(enabled = it)) })
                    PolicyHint(stringResource(R.string.polish_policy_future))
                }
            }
            item {
                PolicyCard(stringResource(R.string.polish_error_actions), stringResource(R.string.polish_policy_neutral)) {
                    ActionRow(stringResource(R.string.polish_invalid_rule), stringResource(R.string.polish_invalid_rule_desc), config.invalidAction) { change(config.copy(invalidAction = it)) }
                    HorizontalDivider()
                    ActionRow(stringResource(R.string.polish_quota_rule), stringResource(R.string.polish_quota_rule_desc), config.quotaAction) { change(config.copy(quotaAction = it)) }
                    HorizontalDivider()
                    ActionRow(stringResource(R.string.polish_rate_rule), stringResource(R.string.polish_rate_rule_desc), config.rateLimitAction) { change(config.copy(rateLimitAction = it)) }
                    if (listOf(config.invalidAction, config.quotaAction, config.rateLimitAction).contains(KeyFailureAction.IGNORE)) {
                        PolicyHint(stringResource(R.string.polish_ignore_warning), warning = true)
                    }
                }
            }
            item {
                PolicyCard(stringResource(R.string.polish_suspend_recovery)) {
                    PolicyToggle(stringResource(R.string.polish_manual_recovery), stringResource(R.string.polish_manual_recovery_desc), config.manualRecoveryOnly, { change(config.copy(manualRecoveryOnly = it)) })
                    if (!config.manualRecoveryOnly) PolicySlider(stringResource(R.string.polish_suspend_duration), config.suspendHours.toFloat(), 1f..720f, 718, "${config.suspendHours} h", exactUnit = "h") {
                        change(config.copy(suspendHours = it.roundToInt()))
                    }
                    PolicyHint(stringResource(R.string.polish_manual_disabled_note))
                }
            }
            item {
                PolicyCard(stringResource(R.string.polish_cooldown_timing)) {
                    PolicySlider(stringResource(R.string.polish_cooldown_base), config.cooldownSeconds.toFloat(), 1f..3600f, 0, "${config.cooldownSeconds}s", exactUnit = "s") { change(config.copy(cooldownSeconds = it.roundToInt())) }
                    PolicySlider(stringResource(R.string.polish_cooldown_max), config.maxCooldownSeconds.toFloat(), 1f..86400f, 0, "${config.maxCooldownSeconds}s", exactUnit = "s") { change(config.copy(maxCooldownSeconds = it.roundToInt())) }
                    PolicySlider(stringResource(R.string.polish_cooldown_multiplier), config.cooldownMultiplier.toFloat(), 1f..5f, 7, "${config.cooldownMultiplier}×") { change(config.copy(cooldownMultiplier = (it * 2).roundToInt() / 2.0)) }
                    Text((1..4).joinToString(" → ") { "${config.cooldownDurationMs(it) / 1000}s" }, color = MaterialTheme.colorScheme.primary)
                    PolicyHint(stringResource(R.string.polish_cooldown_note))
                }
            }
            item {
                PolicyCard(stringResource(R.string.polish_switch_policy)) {
                    PolicyToggle(stringResource(R.string.polish_auto_switch), stringResource(R.string.polish_switch_desc), config.autoSwitch, { change(config.copy(autoSwitch = it)) })
                    PolicySlider(stringResource(R.string.polish_switch_budget), config.maxSwitches.toFloat(), 0f..20f, 19, "${config.maxSwitches}") { change(config.copy(maxSwitches = it.roundToInt())) }
                    PolicySlider(stringResource(R.string.polish_switch_delay), config.switchDelayMs.toFloat(), 0f..10000f, 99, "${config.switchDelayMs} ms", exactUnit = "ms") { change(config.copy(switchDelayMs = (it / 100).roundToInt() * 100L)) }
                    PolicyHint(stringResource(R.string.polish_switch_partial))
                }
            }
            item {
                PolicyCard(stringResource(R.string.polish_health_records), stringResource(R.string.polish_health_records_desc)) {
                    OutlinedButton(onClick = { confirm = "clear" }, enabled = health.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.polish_clear_health))
                    }
                }
            }
        }
    }
    if (confirm.isNotEmpty()) {
        val title = when (confirm) { "discard" -> R.string.polish_discard; "clear" -> R.string.polish_clear_health; else -> R.string.auto_retry_reset_all }
        val message = when (confirm) { "discard" -> R.string.polish_discard_desc; "clear" -> R.string.polish_clear_health_desc; else -> R.string.polish_reset_desc }
        PolicyConfirm(stringResource(title), stringResource(message), { confirm = "" }, {
            when (confirm) {
                "discard" -> onDismissRequest()
                "clear" -> KeyRotationPolicy.clearAllHealth()
                "reset" -> change(KeyManagementPolicy())
            }
            confirm = ""
        })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(title: String, description: String, action: KeyFailureAction, onChange: (KeyFailureAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        PolicyHint(description)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyFailureAction.entries.forEach { value ->
                val label = when (value) { KeyFailureAction.IGNORE -> R.string.polish_ignore; KeyFailureAction.COOLDOWN -> R.string.polish_cooldown; KeyFailureAction.SUSPEND -> R.string.polish_suspend }
                FilterChip(selected = value == action, onClick = { onChange(value) }, label = { Text(stringResource(label)) })
            }
        }
    }
}
