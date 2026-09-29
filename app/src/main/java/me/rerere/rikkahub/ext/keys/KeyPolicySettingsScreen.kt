package me.rerere.rikkahub.ext.keys

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.util.*
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ext.ui.PolicyConfirm

@Composable
internal fun keyRuleSummary(rule: KeyFailureRule): String = when (rule.action) {
    KeyFailureAction.IGNORE -> stringResource(R.string.polish_ignore)
    KeyFailureAction.SUSPEND -> if (rule.manualRecoveryOnly) stringResource(R.string.key3_suspend_manual)
        else stringResource(R.string.key3_suspend_time, keyDuration(rule.suspendMinutes * 60L))
    KeyFailureAction.COOLDOWN -> stringResource(R.string.key3_cooldown_time, keyDuration(rule.cooldownSeconds.toLong()))
}

@Composable
private fun keyDuration(seconds: Long): String = when {
    seconds % 3600L == 0L -> stringResource(R.string.key3_hours, (seconds / 3600).toInt())
    seconds % 60L == 0L -> stringResource(R.string.key3_minutes, (seconds / 60).toInt())
    else -> stringResource(R.string.key3_seconds, seconds.toInt())
}

@Composable
fun KeyPolicySettingsScreen(visible: Boolean, onDismissRequest: () -> Unit, settings: Settings, onUpdateSettings: (Settings) -> Unit) {
    if (!visible) return
    val original = settings.networkSetting.keyManagement.clamped()
    var serialized by rememberSaveable { mutableStateOf(Json.encodeToString(original)) }
    val config = remember(serialized) { Json.decodeFromString<KeyManagementPolicy>(serialized) }
    fun change(value: KeyManagementPolicy) { serialized = Json.encodeToString(value.clamped()) }
    val dirty = original != config
    var confirm by remember { mutableStateOf("") }
    var editing by rememberSaveable { mutableIntStateOf(-1) }
    var customEdit by remember { mutableStateOf<CustomKeyRule?>(null) }
    var deleting by remember { mutableStateOf<CustomKeyRule?>(null) }
    var customExpanded by rememberSaveable { mutableStateOf(false) }
    var showSwitch by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val titles = listOf(R.string.polish_invalid_rule, R.string.polish_quota_rule, R.string.polish_rate_rule)
    CompactKeySheet(stringResource(R.string.polish_key_policy), stringResource(R.string.key3_policy_subtitle),
        onClose = { if (dirty) confirm = "discard" else onDismissRequest() }, footer = {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(HugeIcons.MoreVertical, stringResource(R.string.key3_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.auto_retry_reset_all)) }, onClick = { confirm = "reset"; menu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.polish_clear_health)) }, onClick = { confirm = "clear"; menu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.key3_help)) }, onClick = { help = true; menu = false })
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { if (dirty) confirm = "discard" else onDismissRequest() }) { Text(stringResource(R.string.cancel)) }
                Button(enabled = dirty, onClick = {
                    onUpdateSettings(settings.copy(networkSetting = settings.networkSetting.copy(keyManagement = config)))
                    onDismissRequest()
                }) { Text(stringResource(if (dirty) R.string.polish_save_changes else R.string.polish_saved)) }
            }
        }) {
        LazyColumn {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.polish_policy_enabled), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = config.enabled, onCheckedChange = { change(config.copy(enabled = it)) })
                }
                if (!config.enabled) Text(stringResource(R.string.key3_policy_off), Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
            }
            items(3) { index ->
                CompactSettingRow(stringResource(titles[index]), keyRuleSummary(config.ruleFor(index))) { editing = index }
            }
            item {
                HorizontalDivider()
                CompactSettingRow(stringResource(R.string.polish_switch_policy),
                    if (config.autoSwitch) stringResource(R.string.key3_switch_summary, config.maxSwitches, config.switchDelayMs) else stringResource(R.string.key3_off)) { showSwitch = true }
                CompactSettingRow(stringResource(R.string.key3_custom_rules), stringResource(R.string.key3_custom_count, config.customRules.size)) { customExpanded = !customExpanded }
            }
            if (customExpanded) {
                item {
                    Text(stringResource(R.string.key3_first_match), Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                itemsIndexed(config.customRules, key = { _, rule -> rule.id }) { index, rule ->
                    var more by remember(rule.id) { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            CompactSettingRow(rule.name, if (rule.enabled) keyRuleSummary(rule.behavior) else stringResource(R.string.key3_off)) { customEdit = rule }
                        }
                        Box {
                            IconButton(onClick = { more = true }) { Icon(HugeIcons.MoreVertical, stringResource(R.string.key3_more)) }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.key3_move_up)) }, enabled = index > 0,
                                    onClick = { change(config.copy(customRules = config.customRules.toMutableList().apply { add(index - 1, removeAt(index)) })); more = false })
                                DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, onClick = { deleting = rule; more = false })
                            }
                        }
                    }
                }
                item {
                    TextButton(onClick = { customEdit = CustomKeyRule() }, enabled = config.customRules.size < 30, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.key3_add_rule))
                    }
                }
            }
        }
    }
    if (editing in 0..2) key(editing) {
        KeyRuleEditor(title = stringResource(titles[editing]), initial = config.ruleFor(editing),
            description = stringResource(listOf(R.string.polish_invalid_rule_desc, R.string.polish_quota_rule_desc, R.string.polish_rate_rule_desc)[editing]),
            onDismiss = { editing = -1 }, onSave = { rule, _ ->
                change(when (editing) { 0 -> config.copy(invalidRule = rule); 1 -> config.copy(quotaRule = rule); else -> config.copy(rateLimitRule = rule) })
                editing = -1
            })
    }
    customEdit?.let { custom -> key(custom.id) {
        KeyRuleEditor(title = stringResource(R.string.key3_custom_rule), initial = custom.behavior, custom = custom,
            description = stringResource(R.string.key3_conditions_desc), onDismiss = { customEdit = null }, onSave = { _, updated ->
                if (updated != null) change(config.copy(customRules = if (config.customRules.any { it.id == updated.id })
                    config.customRules.map { if (it.id == updated.id) updated else it } else config.customRules + updated))
                customEdit = null
            })
    } }
    deleting?.let { rule -> PolicyConfirm(stringResource(R.string.common_delete), rule.name, { deleting = null }, {
        change(config.copy(customRules = config.customRules.filterNot { it.id == rule.id })); deleting = null
    }) }
    if (showSwitch) KeySwitchEditor(config, { showSwitch = false }) { change(it); showSwitch = false }
    if (confirm.isNotEmpty()) PolicyConfirm(
        stringResource(when (confirm) { "discard" -> R.string.polish_discard; "clear" -> R.string.polish_clear_health; else -> R.string.auto_retry_reset_all }),
        stringResource(when (confirm) { "discard" -> R.string.polish_discard_desc; "clear" -> R.string.polish_clear_health_desc; else -> R.string.polish_reset_desc }),
        { confirm = "" }, {
            when (confirm) { "discard" -> onDismissRequest(); "clear" -> KeyRotationPolicy.clearAllHealth(); "reset" -> change(KeyManagementPolicy()) }
            confirm = ""
        })
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(stringResource(R.string.key3_help)) },
        text = { Text(stringResource(R.string.key3_help_policy)) },
        confirmButton = { TextButton(onClick = { help = false }) { Text(stringResource(R.string.common_confirm)) } })
}

@Composable
private fun KeyRuleEditor(title: String, description: String, initial: KeyFailureRule, custom: CustomKeyRule? = null,
    onDismiss: () -> Unit, onSave: (KeyFailureRule, CustomKeyRule?) -> Unit) {
    var action by rememberSaveable { mutableStateOf(initial.action) }
    var manual by rememberSaveable { mutableStateOf(initial.manualRecoveryOnly) }
    var minutes by rememberSaveable { mutableStateOf(initial.suspendMinutes.toString()) }
    var seconds by rememberSaveable { mutableStateOf(initial.cooldownSeconds.toString()) }
    var maxSeconds by rememberSaveable { mutableStateOf(initial.maxCooldownSeconds.toString()) }
    var multiplier by rememberSaveable { mutableStateOf(initial.multiplier.toString()) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf(custom?.name.orEmpty()) }
    var enabled by rememberSaveable { mutableStateOf(custom?.enabled ?: true) }
    var codes by rememberSaveable { mutableStateOf(custom?.statusCodes?.sorted()?.joinToString(", ").orEmpty()) }
    var words by rememberSaveable { mutableStateOf(custom?.keywords?.joinToString("\n").orEmpty()) }
    val tokens = codes.split(Regex("[\\s,，]+" )).filter { it.isNotBlank() }
    val parsedCodes = tokens.mapNotNull { it.toIntOrNull() }.toSet()
    val keywords = words.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val codesValid = tokens.all { (it.toIntOrNull() ?: -1) in 400..499 }
    val conditionsValid = custom == null || (name.isNotBlank() && name.length <= 80 && codesValid &&
        (parsedCodes.isNotEmpty() || keywords.isNotEmpty()) && keywords.size <= 30 && keywords.all { it.length <= 120 })
    val cooldownValid = (seconds.toIntOrNull() ?: 0) in 1..86400 && (maxSeconds.toIntOrNull() ?: 0) in 1..604800 &&
        (maxSeconds.toIntOrNull() ?: 0) >= (seconds.toIntOrNull() ?: 0) &&
        multiplier.toDoubleOrNull()?.let { it.isFinite() && it in 1.0..5.0 } == true
    val behaviorValid = when (action) { KeyFailureAction.IGNORE -> true; KeyFailureAction.SUSPEND -> manual || (minutes.toIntOrNull() ?: 0) in 1..43200; KeyFailureAction.COOLDOWN -> cooldownValid }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.65f
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(description, style = MaterialTheme.typography.bodySmall)
            if (custom != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.key3_rule_enabled), Modifier.weight(1f)); Switch(enabled, { enabled = it })
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.key3_rule_name)) }, isError = name.isBlank() || name.length > 80,
                    supportingText = { Text(stringResource(R.string.key3_name_hint)) })
                OutlinedTextField(value = codes, onValueChange = { codes = it }, label = { Text(stringResource(R.string.key3_codes)) },
                    isError = !codesValid, supportingText = { if (!codesValid) Text(stringResource(R.string.key3_codes_error)) })
                OutlinedTextField(value = words, onValueChange = { words = it }, minLines = 2, maxLines = 4,
                    label = { Text(stringResource(R.string.key3_words)) }, isError = keywords.size > 30 || keywords.any { it.length > 120 })
                if (parsedCodes.isEmpty() && keywords.isEmpty()) Text(stringResource(R.string.key3_condition_required), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.key3_action) + " · " + stringResource(actionLabel(action)))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    KeyFailureAction.entries.forEach { value -> DropdownMenuItem(text = { Text(stringResource(actionLabel(value))) }, onClick = { action = value; expanded = false }) }
                }
            }
            when (action) {
                KeyFailureAction.IGNORE -> Text(stringResource(R.string.polish_ignore_warning), style = MaterialTheme.typography.bodySmall)
                KeyFailureAction.SUSPEND -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.polish_manual_recovery), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(manual, { manual = it })
                    }
                    if (!manual) {
                        KeyNumberField(stringResource(R.string.key3_suspend_minutes), minutes, { minutes = it }, (minutes.toIntOrNull() ?: 0) in 1..43200, "1–43200 min")
                        Row {
                            listOf(60, 1440, 10080).forEach { value -> TextButton(onClick = { minutes = value.toString() }) { Text(keyDuration(value * 60L)) } }
                        }
                    }
                }
                KeyFailureAction.COOLDOWN -> {
                    KeyNumberField(stringResource(R.string.key3_cooldown_seconds), seconds, { value ->
                        seconds = value
                        val base = value.toIntOrNull()
                        if (base != null && base in 1..86400 && (maxSeconds.toIntOrNull() ?: 0) < base) maxSeconds = base.toString()
                    }, (seconds.toIntOrNull() ?: 0) in 1..86400, "1–86400 s")
                    Row {
                        listOf(60, 300, 1800).forEach { value -> TextButton(onClick = {
                            seconds = value.toString()
                            if ((maxSeconds.toIntOrNull() ?: 0) < value) maxSeconds = value.toString()
                        }) { Text(keyDuration(value.toLong())) } }
                    }
                    TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.key3_advanced_cooldown)) }
                    if (advanced || !cooldownValid) {
                        KeyNumberField(stringResource(R.string.polish_cooldown_max), maxSeconds, { maxSeconds = it }, (maxSeconds.toIntOrNull() ?: 0) in (seconds.toIntOrNull() ?: 1)..604800, "≤ 604800 s")
                        KeyNumberField(stringResource(R.string.polish_cooldown_multiplier), multiplier, { multiplier = it }, multiplier.toDoubleOrNull()?.let { it.isFinite() && it in 1.0..5.0 } == true, "1–5 ×")
                    }
                    Text(stringResource(R.string.key3_cooldown_explain), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(stringResource(R.string.key3_apply_draft), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = {
        TextButton(enabled = conditionsValid && behaviorValid, onClick = {
            val rule = KeyFailureRule(action, minutes.toIntOrNull() ?: initial.suspendMinutes, manual,
                seconds.toIntOrNull() ?: initial.cooldownSeconds, maxSeconds.toIntOrNull() ?: initial.maxCooldownSeconds,
                multiplier.toDoubleOrNull() ?: initial.multiplier).clamped()
            onSave(rule, custom?.copy(name = name.trim(), enabled = enabled, statusCodes = parsedCodes, keywords = keywords, behavior = rule))
        }) { Text(stringResource(R.string.common_confirm)) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

private fun actionLabel(action: KeyFailureAction): Int = when (action) {
    KeyFailureAction.IGNORE -> R.string.polish_ignore
    KeyFailureAction.COOLDOWN -> R.string.polish_cooldown
    KeyFailureAction.SUSPEND -> R.string.polish_suspend
}

@Composable
private fun KeyNumberField(title: String, value: String, onChange: (String) -> Unit, valid: Boolean, range: String) {
    OutlinedTextField(value = value, onValueChange = onChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), label = { Text(title) },
        isError = !valid, supportingText = { Text(range) })
}

@Composable
private fun KeySwitchEditor(initial: KeyManagementPolicy, onDismiss: () -> Unit, onSave: (KeyManagementPolicy) -> Unit) {
    var enabled by rememberSaveable { mutableStateOf(initial.autoSwitch) }
    var count by rememberSaveable { mutableStateOf(initial.maxSwitches.toString()) }
    var delay by rememberSaveable { mutableStateOf(initial.switchDelayMs.toString()) }
    val valid = (count.toIntOrNull() ?: -1) in 0..20 && (delay.toLongOrNull() ?: -1L) in 0L..10000L
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.polish_switch_policy)) }, text = {
        Column(Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.6f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.polish_auto_switch), Modifier.weight(1f)); Switch(enabled, { enabled = it }) }
            if (enabled) {
                KeyNumberField(stringResource(R.string.polish_switch_budget), count, { count = it }, (count.toIntOrNull() ?: -1) in 0..20, "0–20")
                KeyNumberField(stringResource(R.string.polish_switch_delay), delay, { delay = it }, (delay.toLongOrNull() ?: -1) in 0L..10000L, "0–10000 ms")
            }
            Text(stringResource(R.string.polish_switch_desc), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.polish_switch_partial), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = !enabled || valid, onClick = { onSave(initial.copy(autoSwitch = enabled,
        maxSwitches = count.toIntOrNull() ?: initial.maxSwitches, switchDelayMs = delay.toLongOrNull() ?: initial.switchDelayMs).clamped()) }) { Text(stringResource(R.string.common_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
