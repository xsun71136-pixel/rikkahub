package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.AutoRetrySetting
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** 词条编辑的类型：状态码、响应内容关键字、停止关键字 */
private enum class RetryListKind { CODES, KEYWORDS, STOP }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingAutoRetryPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val network = settings.networkSetting
    val config = network.autoRetry
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun update(transform: (AutoRetrySetting) -> AutoRetrySetting) {
        // 在 update 回调里取最新值，连续多次修改不会因捕获到旧的组合态而互相覆盖
        vm.updateSettings {
            it.copy(networkSetting = it.networkSetting.copy(autoRetry = transform(it.networkSetting.autoRetry)))
        }
    }

    fun updateEnabled(enabled: Boolean) {
        vm.updateSettings {
            it.copy(networkSetting = it.networkSetting.copy(enableAutoRetry = enabled))
        }
    }

    // 词条编辑弹层
    var editingList by remember { mutableStateOf<RetryListKind?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_auto_retry_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 总开关
            item {
                CardGroup {
                    switchItem(
                        checked = network.enableAutoRetry,
                        onCheckedChange = { updateEnabled(it) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    when {
                                        !network.enableAutoRetry -> R.string.setting_auto_retry_state_off
                                        config.custom -> R.string.setting_auto_retry_state_on_custom
                                        else -> R.string.setting_auto_retry_state_on_standard
                                    }
                                )
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.setting_auto_retry_page_title)) },
                    )
                }
            }

            // 重试策略
            item {
                val delays = remember(config) { expectedDelays(config) }
                CardGroup(
                    title = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.setting_auto_retry_strategy_title))
                            Text(
                                text = if (delays.isEmpty()) {
                                    stringResource(R.string.setting_auto_retry_seq_none)
                                } else {
                                    stringResource(
                                        R.string.setting_auto_retry_seq_meta,
                                        delays.size,
                                        formatDelay(delays.fold(Duration.ZERO) { acc, d -> acc + d }),
                                    )
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .weight(1f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            )
                        }
                    },
                ) {
                    formItem {
                        ConnectedChoiceButtons(
                            options = listOf(false, true),
                            selected = config.custom,
                            onSelect = { custom ->
                                update { it.copy(custom = custom) }
                            },
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) { custom, selected ->
                            ConnectedChoiceLabel(
                                text = stringResource(
                                    if (custom) {
                                        R.string.setting_auto_retry_preset_custom
                                    } else {
                                        R.string.setting_auto_retry_preset_standard
                                    }
                                ),
                                selected = selected,
                            )
                        }
                    }
                    if (config.custom) {
                        formItem(
                            headlineContent = { Text(stringResource(R.string.setting_auto_retry_max_retries)) },
                            trailingContent = { Text(stringResource(R.string.setting_auto_retry_unit_times)) },
                        ) {
                            RetryNumberField(
                                value = config.maxRetries,
                                onCommit = { v -> update { it.copy(maxRetries = v.coerceIn(0, 10)) } },
                                rangeHint = "0-10",
                            )
                        }
                        formItem(
                            headlineContent = { Text(stringResource(R.string.setting_auto_retry_first_delay)) },
                            trailingContent = { Text("ms") },
                        ) {
                            RetryNumberField(
                                value = config.initialDelayMs,
                                onCommit = { v -> update { it.copy(initialDelayMs = v.coerceIn(0, 300_000)) } },
                            )
                        }
                        formItem(
                            headlineContent = { Text(stringResource(R.string.setting_auto_retry_backoff_factor)) },
                            trailingContent = { Text("×") },
                        ) {
                            RetryDecimalField(
                                value = config.backoffFactor,
                                onCommit = { v -> update { it.copy(backoffFactor = v.coerceIn(1f, 10f)) } },
                            )
                        }
                        formItem(
                            headlineContent = { Text(stringResource(R.string.setting_auto_retry_max_delay)) },
                            trailingContent = { Text("ms") },
                        ) {
                            RetryNumberField(
                                value = config.maxDelayMs,
                                onCommit = { v -> update { it.copy(maxDelayMs = v.coerceIn(1000, 600_000)) } },
                            )
                        }
                        switchItem(
                            checked = config.jitter,
                            onCheckedChange = { jitter -> update { it.copy(jitter = jitter) } },
                            supportingContent = { Text(stringResource(R.string.setting_auto_retry_jitter_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_auto_retry_jitter)) },
                        )
                    }
                    // 预期节奏：把每次重试前的等待按序列展示出来
                    if (delays.isNotEmpty()) {
                        formItem(
                            headlineContent = { Text(stringResource(R.string.setting_auto_retry_expected)) },
                            trailingContent = {
                                Text(
                                    text = stringResource(
                                        if (config.custom && config.jitter) {
                                            R.string.setting_auto_retry_jitter_on
                                        } else {
                                            R.string.setting_auto_retry_fixed_interval
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                delays.forEachIndexed { index, delay ->
                                    if (index > 0) {
                                        Text(
                                            text = "→",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    ) {
                                        Text(
                                            text = formatDelay(delay),
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 触发条件：满足其一即重试
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_auto_retry_conditions_title)) },
                ) {
                    item(
                        onClick = { editingList = RetryListKind.CODES },
                        supportingContent = { Text(config.retryOnCodes.joinToString("  ")) },
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                CardGroupValue(config.retryOnCodes.size.toString())
                                Icon(HugeIcons.ArrowRight01, contentDescription = null)
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.setting_auto_retry_codes)) },
                    )
                    item(
                        onClick = { editingList = RetryListKind.KEYWORDS },
                        supportingContent = { Text(config.retryOnKeywords.joinToString("、")) },
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                CardGroupValue(config.retryOnKeywords.size.toString())
                                Icon(HugeIcons.ArrowRight01, contentDescription = null)
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.setting_auto_retry_keywords)) },
                    )
                    switchItem(
                        checked = config.retryOnNetworkError,
                        onCheckedChange = { on -> update { it.copy(retryOnNetworkError = on) } },
                        supportingContent = { Text(stringResource(R.string.setting_auto_retry_network_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_auto_retry_network)) },
                    )
                }
            }

            // 停止条件：命中即停止重试
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_auto_retry_stop_title)) },
                ) {
                    item(
                        onClick = { editingList = RetryListKind.STOP },
                        supportingContent = { Text(config.stopKeywords.joinToString("、")) },
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                CardGroupValue(config.stopKeywords.size.toString())
                                Icon(HugeIcons.ArrowRight01, contentDescription = null)
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.setting_auto_retry_stop)) },
                    )
                }
            }
        }
    }

    // 词条编辑弹层
    editingList?.let { kind ->
        RetryListEditorSheet(
            kind = kind,
            config = config,
            onUpdate = { update(it) },
            onDismiss = { editingList = null },
        )
    }
}

/** 预期节奏：标准策略固定 1s/2s/4s，自定义按参数计算（不含抖动随机量） */
private fun expectedDelays(config: AutoRetrySetting): List<Duration> {
    if (!config.custom) return listOf(1000L, 2000L, 4000L).map { it.milliseconds }
    if (config.maxRetries <= 0) return emptyList()
    val out = ArrayList<Duration>(config.maxRetries)
    var current = config.initialDelayMs
    repeat(config.maxRetries) {
        out += minOf(current, config.maxDelayMs).milliseconds
        current = (current * config.backoffFactor).toLong()
    }
    return out
}

/** 与预览一致：>= 1s 显示秒（最多一位小数），否则毫秒 */
private fun formatDelay(delay: Duration): String {
    val ms = delay.inWholeMilliseconds
    return if (ms >= 1000) {
        val s = (ms / 100.0).roundToInt() / 10.0
        s.toString().removeSuffix(".0") + "s"
    } else {
        "${ms}ms"
    }
}

/** 整数输入：本地草稿，合法时才写入设置 */
@Composable
private fun RetryNumberField(
    value: Long,
    onCommit: (Long) -> Unit,
) {
    var draft by rememberSaveable(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = draft,
        onValueChange = { next ->
            draft = next.filter { it.isDigit() }
            draft.toLongOrNull()?.let(onCommit)
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun RetryNumberField(
    value: Int,
    onCommit: (Int) -> Unit,
    rangeHint: String,
) {
    var draft by rememberSaveable(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = draft,
        onValueChange = { next ->
            draft = next.filter { it.isDigit() }
            draft.toIntOrNull()?.let(onCommit)
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = { Text(rangeHint) },
    )
}

/** 小数输入：退避倍率用 */
@Composable
private fun RetryDecimalField(
    value: Float,
    onCommit: (Float) -> Unit,
) {
    var draft by rememberSaveable(value) { mutableStateOf(value.toString().removeSuffix(".0")) }
    OutlinedTextField(
        value = draft,
        onValueChange = { next ->
            if (next.all { it.isDigit() || it == '.' }) {
                draft = next
                next.toFloatOrNull()?.let(onCommit)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

/** 词条编辑弹层：chips 点击删除，底部输入框添加（支持一次多个），可恢复默认 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RetryListEditorSheet(
    kind: RetryListKind,
    config: AutoRetrySetting,
    onUpdate: ((AutoRetrySetting) -> AutoRetrySetting) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val items = when (kind) {
        RetryListKind.CODES -> config.retryOnCodes.map { it.toString() }
        RetryListKind.KEYWORDS -> config.retryOnKeywords
        RetryListKind.STOP -> config.stopKeywords
    }
    val defaults = when (kind) {
        RetryListKind.CODES -> AutoRetrySetting.DEFAULT_RETRY_CODES.map { it.toString() }
        RetryListKind.KEYWORDS -> AutoRetrySetting.DEFAULT_RETRY_KEYWORDS
        RetryListKind.STOP -> AutoRetrySetting.DEFAULT_STOP_KEYWORDS
    }
    val title = when (kind) {
        RetryListKind.CODES -> stringResource(R.string.setting_auto_retry_codes)
        RetryListKind.KEYWORDS -> stringResource(R.string.setting_auto_retry_keywords)
        RetryListKind.STOP -> stringResource(R.string.setting_auto_retry_stop)
    }
    val placeholder = when (kind) {
        RetryListKind.CODES -> stringResource(R.string.setting_auto_retry_add_codes_hint)
        else -> stringResource(R.string.setting_auto_retry_add_keywords_hint)
    }

    fun write(items: List<String>) {
        onUpdate {
            when (kind) {
                RetryListKind.CODES ->
                    it.copy(retryOnCodes = items.mapNotNull { code -> code.toIntOrNull() })

                RetryListKind.KEYWORDS -> it.copy(retryOnKeywords = items)
                RetryListKind.STOP -> it.copy(stopKeywords = items)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    modifier = Modifier.weight(1f),
                )
                CardGroupValue(items.size.toString())
                TextButton(onClick = { write(defaults) }) {
                    Text(stringResource(R.string.setting_model_page_reset_to_default))
                }
            }

            // 添加框紧贴标题下方：始终可见，不会被键盘或屏底挡住
            var input by rememberSaveable { mutableStateOf("") }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                )
                FilledTonalButton(
                    onClick = {
                        // 状态码按空白/逗号分隔可一次加多个；关键字按逗号分隔（关键字本身可能带空格）
                        val parts = when (kind) {
                            RetryListKind.CODES -> input.split(Regex("[\\s,]+"))
                            else -> input.split(',', '，')
                        }.map { it.trim() }.filter { it.isNotBlank() }
                        val merged = (items + parts).distinct()
                        write(merged)
                        input = ""
                    },
                ) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items.forEach { item ->
                    InputChip(
                        selected = false,
                        onClick = { write(items - item) },
                        label = { Text(item) },
                        trailingIcon = {
                            Icon(
                                HugeIcons.Cancel01,
                                contentDescription = null,
                                modifier = Modifier.padding(2.dp),
                            )
                        },
                    )
                }
            }

            FilledTonalButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.confirm))
            }
        }
    }
}
