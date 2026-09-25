package me.rerere.rikkahub.ext.retry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.context.LocalToaster
import com.dokar.sonner.ToastType

/** 状态码快捷开关预设（含常见 CDN/中转站错误码段），点选即加入/移出重试集合。 */
private val PRESET_STATUS_CODES =
    setOf(408, 425, 429, 500, 502, 503, 504, 520, 521, 522, 524, 529)

/**
 * 高级自动重试配置底部弹窗（自定义插件层，交互参考 kelivo auto_retry_page，
 * 视觉风格沿用本项目 Material3 组件，见 docs/custom/02-auto-retry.md）。
 *
 * 入口：设置 → 偏好设置 → 网络 → 长按"自动重试"行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoRetrySettingsSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    settings: Settings,
    onUpdateSettings: (Settings) -> Unit,
) {
    if (!visible) return

    val context = LocalContext.current
    val toaster = LocalToaster.current
    val network = settings.networkSetting
    // 本地草稿，点击"保存"统一写入 DataStore，避免拖动滑条时高频写盘
    var enableAutoRetry by remember { mutableStateOf(network.enableAutoRetry) }
    var config by remember { mutableStateOf(network.autoRetry.clamped()) }

    fun persist() {
        onUpdateSettings(
            settings.copy(
                networkSetting = network.copy(
                    enableAutoRetry = enableAutoRetry,
                    autoRetry = config.clamped(),
                )
            )
        )
        toaster.show(message = context.getString(R.string.auto_retry_saved), type = ToastType.Success)
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.auto_retry_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.auto_retry_sheet_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(top = 4.dp))

            // ---- 总开关 ----
            SwitchRow(
                title = stringResource(R.string.setting_page_preferences_network_auto_retry),
                subtitle = stringResource(R.string.auto_retry_master_switch_desc),
                checked = enableAutoRetry,
                onCheckedChange = { enableAutoRetry = it },
            )

            // ---- 数值配置 ----
            SliderRow(
                label = stringResource(R.string.auto_retry_max_retries),
                value = config.maxRetries.toFloat(),
                valueRange = AutoRetryConfig.MIN_MAX_RETRIES.toFloat()..AutoRetryConfig.MAX_MAX_RETRIES.toFloat(),
                steps = AutoRetryConfig.MAX_MAX_RETRIES - 1,
                displayValue = "${config.maxRetries}",
                onValueChange = { config = config.copy(maxRetries = it.toInt()) },
            )
            SliderRow(
                label = stringResource(R.string.auto_retry_initial_delay),
                value = config.initialDelayMs.toFloat(),
                valueRange = 0f..10_000f,
                steps = 39,
                displayValue = "${config.initialDelayMs} ms",
                onValueChange = { config = config.copy(initialDelayMs = (it / 250).toInt() * 250L) },
            )
            SliderRow(
                label = stringResource(R.string.auto_retry_multiplier),
                value = config.multiplier.toFloat(),
                valueRange = AutoRetryConfig.MIN_MULTIPLIER.toFloat()..AutoRetryConfig.MAX_MULTIPLIER.toFloat(),
                steps = 7,
                displayValue = String.format("%.1fx", config.multiplier),
                onValueChange = {
                    config = config.copy(multiplier = Math.round(it * 10.0) / 10.0)
                },
            )
            SliderRow(
                label = stringResource(R.string.auto_retry_max_delay),
                value = config.maxDelayMs.toFloat(),
                valueRange = 1_000f..120_000f,
                steps = 118,
                displayValue = "${config.maxDelayMs} ms",
                onValueChange = { config = config.copy(maxDelayMs = (it / 1000).toInt() * 1000L) },
            )

            SwitchRow(
                title = stringResource(R.string.auto_retry_jitter),
                subtitle = stringResource(R.string.auto_retry_jitter_desc),
                checked = config.jitter,
                onCheckedChange = { config = config.copy(jitter = it) },
            )
            SwitchRow(
                title = stringResource(R.string.auto_retry_on_network_error),
                subtitle = stringResource(R.string.auto_retry_on_network_error_desc),
                checked = config.retryOnNetworkError,
                onCheckedChange = { config = config.copy(retryOnNetworkError = it) },
            )

            HorizontalDivider(Modifier.padding(top = 4.dp))

            // ---- 状态码 / 重试关键词 / 停止关键词：折叠收纳，只占一行 ----
            var codesExpanded by remember { mutableStateOf(false) }
            var retryKwExpanded by remember { mutableStateOf(false) }
            var stopKwExpanded by remember { mutableStateOf(false) }

            CollapsibleSection(
                title = stringResource(R.string.auto_retry_status_codes),
                count = config.retryStatusCodes.size,
                expanded = codesExpanded,
                onToggle = { codesExpanded = !codesExpanded },
                onReset = {
                    config = config.copy(
                        retryStatusCodes = AutoRetryConfig.DEFAULT_RETRY_STATUS_CODES
                    )
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 常用状态码快捷开关，点选即加入/移出
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PRESET_STATUS_CODES.forEach { code ->
                            FilterChip(
                                selected = code in config.retryStatusCodes,
                                onClick = {
                                    config = config.copy(
                                        retryStatusCodes = if (code in config.retryStatusCodes) {
                                            config.retryStatusCodes - code
                                        } else {
                                            config.retryStatusCodes + code
                                        }
                                    )
                                },
                                label = { Text("$code") },
                            )
                        }
                    }
                    // 预设之外的自定义状态码
                    val customCodes = config.retryStatusCodes
                        .filter { it !in PRESET_STATUS_CODES }
                        .sorted()
                        .map { it.toString() }
                    ChipEditor(
                        items = customCodes,
                        hint = stringResource(R.string.auto_retry_status_code_hint),
                        numeric = true,
                        onItemsChange = { list ->
                            config = config.copy(
                                retryStatusCodes = (
                                        config.retryStatusCodes.filter { it in PRESET_STATUS_CODES } +
                                                list.mapNotNull { it.toIntOrNull() }
                                        ).toSet()
                            )
                        },
                    )
                }
            }

            CollapsibleSection(
                title = stringResource(R.string.auto_retry_keywords),
                count = config.retryKeywords.size,
                expanded = retryKwExpanded,
                onToggle = { retryKwExpanded = !retryKwExpanded },
                onReset = {
                    config = config.copy(retryKeywords = AutoRetryConfig.DEFAULT_RETRY_KEYWORDS)
                },
            ) {
                ChipEditor(
                    items = config.retryKeywords,
                    hint = stringResource(R.string.auto_retry_keyword_hint),
                    numeric = false,
                    onItemsChange = { config = config.copy(retryKeywords = it) },
                )
            }

            CollapsibleSection(
                title = stringResource(R.string.auto_retry_stop_keywords),
                count = config.stopKeywords.size,
                expanded = stopKwExpanded,
                onToggle = { stopKwExpanded = !stopKwExpanded },
                onReset = {
                    config = config.copy(stopKeywords = AutoRetryConfig.DEFAULT_STOP_KEYWORDS)
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.auto_retry_stop_keywords_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ChipEditor(
                        items = config.stopKeywords,
                        hint = stringResource(R.string.auto_retry_keyword_hint),
                        numeric = false,
                        onItemsChange = { config = config.copy(stopKeywords = it) },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = {
                        enableAutoRetry = true
                        config = AutoRetryConfig()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(HugeIcons.Refresh01, null, Modifier.padding(end = 6.dp))
                    Text(stringResource(R.string.auto_retry_reset_all))
                }
                Button(onClick = { persist(); onDismissRequest() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.auto_retry_save))
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    displayValue: String,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                displayValue,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

/** 折叠区块：收起时只占一行（标题 + 条目数 + 重置 + 箭头）。 */
@Composable
private fun CollapsibleSection(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.auto_retry_items_count, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onReset, modifier = Modifier.size(32.dp)) {
                Icon(
                    HugeIcons.Refresh01,
                    stringResource(R.string.auto_retry_reset_defaults),
                    modifier = Modifier.size(16.dp),
                )
            }
            Icon(
                if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                contentDescription = stringResource(
                    if (expanded) R.string.auto_retry_collapse else R.string.auto_retry_expand
                ),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipEditor(
    items: List<String>,
    hint: String,
    numeric: Boolean,
    onItemsChange: (List<String>) -> Unit,
) {
    var input by remember { mutableStateOf("") }

    fun commitInput() {
        val value = input.trim()
        if (value.isEmpty()) return
        if (numeric && value.toIntOrNull() == null) return
        if (value in items) {
            input = ""
            return
        }
        onItemsChange(items + value)
        input = ""
    }

    if (items.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { item ->
                InputChip(
                    selected = false,
                    onClick = { onItemsChange(items - item) },
                    label = { Text(item) },
                    trailingIcon = {
                        Icon(HugeIcons.Cancel01, null, Modifier.height(16.dp))
                    },
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text(hint, style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = if (numeric) {
                KeyboardOptions(keyboardType = KeyboardType.Number)
            } else {
                KeyboardOptions.Default
            },
        )
        IconButton(onClick = { commitInput() }) {
            Icon(HugeIcons.Add01, stringResource(R.string.auto_retry_add))
        }
    }
}
