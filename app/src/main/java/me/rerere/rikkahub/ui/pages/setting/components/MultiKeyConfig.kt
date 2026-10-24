package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.MultiKeyConfig
import me.rerere.ai.provider.MultiKeyStrategy
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.splitApiKeys
import me.rerere.ai.provider.withApiKey
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.MultiKeyState
import me.rerere.ai.util.MultiKeyStateCenter
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ModelField
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.extendColors
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 提供商配置里的多 Key 卡片：开关 + 管理入口。
 * 关闭时只用第一个密钥（单 Key 行为）；开启后多个密钥按策略轮流使用。
 */
@Composable
fun MultiKeyCard(
    provider: ProviderSetting.ApiKey,
    onEdit: (ProviderSetting) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val keys = remember(provider.apiKey) { provider.splitApiKeys() }
    val allStates by MultiKeyStateCenter.states.collectAsStateWithLifecycle()
    val keyStates = allStates[provider.id.toString()].orEmpty()
    val availableCount = keys.count { key -> keyStates[key]?.isPaused() != true }

    CardGroup {
        switchItem(
            checked = provider.multiKey.enabled,
            onCheckedChange = { enabled ->
                onEdit(provider.withMultiKey(provider.multiKey.copy(enabled = enabled)))
            },
            supportingContent = {
                Text(
                    if (provider.multiKey.enabled) {
                        stringResource(
                            R.string.setting_provider_page_multi_key_summary,
                            strategyLabel(provider.multiKey.strategy),
                            availableCount,
                            keys.size,
                        )
                    } else {
                        stringResource(R.string.setting_provider_page_multi_key_off_desc)
                    }
                )
            },
            headlineContent = { Text(stringResource(R.string.setting_provider_page_multi_key)) },
        )
        if (provider.multiKey.enabled) {
            item(
                onClick = { showDialog = true },
                trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.multi_key_manage)) },
            )
        }
    }

    if (showDialog) {
        MultiKeyDialog(
            provider = provider,
            onEdit = onEdit,
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
internal fun strategyLabel(strategy: MultiKeyStrategy): String = when (strategy) {
    MultiKeyStrategy.ROUND_ROBIN -> stringResource(R.string.multi_key_strategy_round)
    MultiKeyStrategy.RANDOM -> stringResource(R.string.multi_key_strategy_random)
    MultiKeyStrategy.PARALLEL -> stringResource(R.string.multi_key_strategy_parallel)
}

@Composable
private fun strategyNote(strategy: MultiKeyStrategy): String = when (strategy) {
    MultiKeyStrategy.ROUND_ROBIN -> stringResource(R.string.multi_key_strategy_round_desc)
    MultiKeyStrategy.RANDOM -> stringResource(R.string.multi_key_strategy_random_desc)
    MultiKeyStrategy.PARALLEL -> stringResource(R.string.multi_key_strategy_parallel_desc)
}

internal fun maskKey(key: String): String {
    val s = key.trim()
    return if (s.length <= 10) s.take(3) + "•••" else s.take(12) + "••••" + s.takeLast(4)
}

private val pauseTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/** 多 Key 管理弹窗：密钥列表与使用方式、暂停条件、测试 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiKeyDialog(
    provider: ProviderSetting.ApiKey,
    onEdit: (ProviderSetting) -> Unit,
    onDismiss: () -> Unit,
) {
    val config = provider.multiKey
    val keys = remember(provider.apiKey) { provider.splitApiKeys() }
    val allStates by MultiKeyStateCenter.states.collectAsStateWithLifecycle()
    val keyStates = allStates[provider.id.toString()].orEmpty()
    val availableCount = keys.count { key -> keyStates[key]?.isPaused() != true }
    var adding by remember { mutableStateOf(false) }
    var showTest by remember { mutableStateOf(false) }

    fun editKeys(newKeys: List<String>) {
        val distinct = distinctKeys(newKeys)
        onEdit(provider.withApiKey(distinct.joinToString("\n")))
        MultiKeyStateCenter.pruneKeys(provider.id.toString(), distinct)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_provider_page_multi_key)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.multi_key_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ---- 密钥 ----
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.multi_key_section_keys),
                            style = MaterialTheme.typography.titleSmallEmphasized,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(
                                R.string.setting_provider_page_multi_key_summary,
                                strategyLabel(config.strategy),
                                availableCount,
                                keys.size,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // 使用方式
                    Text(
                        text = stringResource(R.string.multi_key_strategy),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ConnectedChoiceButtons(
                        options = MultiKeyStrategy.entries,
                        selected = config.strategy,
                        onSelect = { onEdit(provider.withMultiKey(config.copy(strategy = it))) },
                    ) { strategy, selected ->
                        ConnectedChoiceLabel(text = strategyLabel(strategy), selected = selected)
                    }
                    Text(
                        text = strategyNote(config.strategy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 密钥列表
                    keys.forEach { key ->
                        val state = keyStates[key]
                        KeyRow(
                            keyValue = key,
                            state = state,
                            onRecover = { MultiKeyStateCenter.recover(provider.id.toString(), key) },
                            onDelete = { editKeys(keys - key) },
                        )
                    }

                    // 操作：添加 / 恢复全部 / 测试
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { adding = !adding }) {
                            Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.multi_key_add))
                        }
                        if (keyStates.values.any { it.isPaused() }) {
                            TextButton(onClick = { MultiKeyStateCenter.recoverAll(provider.id.toString()) }) {
                                Icon(HugeIcons.Refresh01, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(stringResource(R.string.multi_key_recover_all))
                            }
                        }
                        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { showTest = true }) {
                                Text(stringResource(R.string.multi_key_test))
                            }
                        }
                    }

                    // 添加框
                    if (adding) {
                        var input by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.multi_key_add_placeholder)) },
                            minLines = 3,
                        )
                        Text(
                            text = stringResource(R.string.multi_key_add_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { adding = false }) {
                                Text(stringResource(R.string.cancel))
                            }
                            TextButton(
                                onClick = {
                                    val parts = input.split(Regex("[\\s,]+"))
                                        .map { it.trim() }
                                        .filter { it.isNotBlank() }
                                    editKeys(keys + parts)
                                    input = ""
                                    adding = false
                                },
                            ) {
                                Text(stringResource(R.string.multi_key_add))
                            }
                        }
                    }
                }

                // ---- 暂停条件 ----
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.multi_key_pause_section),
                            style = MaterialTheme.typography.titleSmallEmphasized,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(
                                R.string.multi_key_pause_meta,
                                config.pauseRules.size,
                                pauseDurationLabel(config.pauseDurationMinutes),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text(
                        text = stringResource(R.string.multi_key_pause_duration),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ConnectedChoiceButtons(
                        options = listOf(5, 30, 60, 1440, -1),
                        selected = config.pauseDurationMinutes,
                        onSelect = { onEdit(provider.withMultiKey(config.copy(pauseDurationMinutes = it))) },
                    ) { duration, selected ->
                        ConnectedChoiceLabel(
                            text = pauseDurationLabel(duration),
                            selected = selected,
                        )
                    }

                    Text(
                        text = stringResource(R.string.multi_key_rules),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        config.pauseRules.forEach { rule ->
                            InputChip(
                                selected = false,
                                onClick = {
                                    onEdit(
                                        provider.withMultiKey(
                                            config.copy(pauseRules = config.pauseRules - rule)
                                        )
                                    )
                                },
                                label = { Text(rule) },
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
                    var ruleInput by remember { mutableStateOf("") }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = ruleInput,
                            onValueChange = { ruleInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(stringResource(R.string.multi_key_add_rule_placeholder)) },
                            singleLine = true,
                        )
                        FilledTonalButton(
                            onClick = {
                                val rule = ruleInput.trim()
                                if (rule.isNotEmpty() && rule !in config.pauseRules) {
                                    onEdit(
                                        provider.withMultiKey(
                                            config.copy(pauseRules = config.pauseRules + rule)
                                        )
                                    )
                                }
                                ruleInput = ""
                            },
                        ) {
                            Icon(HugeIcons.Add01, contentDescription = null)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = {
                                onEdit(
                                    provider.withMultiKey(
                                        config.copy(pauseRules = MultiKeyConfig.DEFAULT_PAUSE_RULES)
                                    )
                                )
                            },
                        ) {
                            Text(stringResource(R.string.setting_model_page_reset_to_default))
                        }
                    }
                }

                // 全部暂停警告
                if (keys.isNotEmpty() && availableCount == 0) {
                    Text(
                        text = stringResource(R.string.multi_key_all_paused_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.extendColors.red6,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )

    if (showTest) {
        MultiKeyTestDialog(
            provider = provider,
            keys = keys,
            onDismiss = { showTest = false },
        )
    }
}

@Composable
private fun pauseDurationLabel(minutes: Int): String = when (minutes) {
    5 -> stringResource(R.string.multi_key_duration_5m)
    30 -> stringResource(R.string.multi_key_duration_30m)
    60 -> stringResource(R.string.multi_key_duration_1h)
    1440 -> stringResource(R.string.multi_key_duration_1d)
    else -> stringResource(R.string.multi_key_duration_forever)
}

@Composable
private fun KeyRow(
    keyValue: String,
    state: MultiKeyState?,
    onRecover: () -> Unit,
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
            .clip(MaterialTheme.shapes.medium)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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

// ---- 测试连接：和单 Key 的「测试连接」相同的三项检查，对每个密钥各跑一遍 ----

private sealed interface KeyTestStatus {
    data object Idle : KeyTestStatus
    data object Running : KeyTestStatus
    data class Ok(val detail: String) : KeyTestStatus
    data class Warn(val detail: String) : KeyTestStatus
    data class Fail(val detail: String) : KeyTestStatus
}

@Composable
private fun MultiKeyTestDialog(
    provider: ProviderSetting.ApiKey,
    keys: List<String>,
    onDismiss: () -> Unit,
) {
    val providerManager = koinInject<ProviderManager>()
    val scope = rememberCoroutineScope()
    val models = remember(provider) { provider.models.filter { it.type == ModelType.CHAT } }
    var model by remember(provider) { mutableStateOf(models.firstOrNull()) }
    var showModelSheet by remember { mutableStateOf(false) }
    val results = remember { mutableStateMapOf<String, KeyTestStatus>() }
    var running by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(stringResource(R.string.setting_provider_page_test_connection)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
                onClick = {
                    val testModel = model ?: return@TextButton
                    running = true
                    scope.launch {
                        try {
                            keys.forEach { key ->
                                results[key] = KeyTestStatus.Running
                                results[key] = testSingleKey(providerManager, provider, key, testModel)
                            }
                        } finally {
                            running = false
                        }
                    }
                },
                enabled = !running && model != null,
            ) {
                Text(stringResource(R.string.setting_provider_page_test))
            }
        },
    )

    if (showModelSheet) {
        ModalBottomSheet(onDismissRequest = { showModelSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    text = stringResource(R.string.multi_key_test_model),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                models.forEach { item ->
                    ListItem(
                        headlineContent = { Text(item.displayName) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable {
                                model = item
                                showModelSheet = false
                            },
                    )
                }
            }
        }
    }
}

/** 和单 Key「测试连接」相同的三项检查：非流式、流式、工具调用 */
private suspend fun testSingleKey(
    providerManager: ProviderManager,
    provider: ProviderSetting.ApiKey,
    key: String,
    model: Model,
): KeyTestStatus {
    val pinned = provider.withApiKey(key)
    val testProvider = providerManager.getProviderByType(pinned)
    return coroutineScope {
        val nonStreaming = async {
            runCatching {
                testProvider.generateText(
                    providerSetting = pinned,
                    messages = listOf(
                        UIMessage.system("You are a helpful assistant"),
                        UIMessage.user("hello"),
                    ),
                    params = TextGenerationParams(
                        model = model,
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                        reasoningLevel = ReasoningLevel.AUTO,
                    ),
                )
            }
        }
        val streaming = async {
            runCatching {
                testProvider.streamText(
                    providerSetting = pinned,
                    messages = listOf(
                        UIMessage.system("You are a helpful assistant"),
                        UIMessage.user("hello"),
                    ),
                    params = TextGenerationParams(
                        model = model,
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                        reasoningLevel = ReasoningLevel.AUTO,
                    ),
                ).collect { }
            }
        }
        val toolCall = async {
            runCatching {
                val testTool = Tool(
                    name = "get_current_time",
                    description = "Get the current date and time.",
                    execute = { emptyList() },
                )
                val result = testProvider.generateText(
                    providerSetting = pinned,
                    messages = listOf(
                        UIMessage.system("You are a helpful assistant"),
                        UIMessage.user("Use the get_current_time tool."),
                    ),
                    params = TextGenerationParams(
                        model = model,
                        tools = listOf(testTool),
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                        reasoningLevel = ReasoningLevel.AUTO,
                    ),
                )
                result.message.parts.filterIsInstance<UIMessagePart.Tool>().isNotEmpty()
            }
        }
        val nonStreamingResult = nonStreaming.await()
        val streamingResult = streaming.await()
        val toolResult = toolCall.await()
        val firstError = listOfNotNull(
            nonStreamingResult.exceptionOrNull(),
            streamingResult.exceptionOrNull(),
            toolResult.exceptionOrNull(),
        ).firstOrNull()
        when {
            nonStreamingResult.isFailure || streamingResult.isFailure || toolResult.isFailure ->
                KeyTestStatus.Fail(firstError?.message?.take(80) ?: firstError?.javaClass?.simpleName ?: "failed")

            toolResult.getOrNull() == false -> KeyTestStatus.Warn("")
            else -> KeyTestStatus.Ok("")
        }
    }
}

private fun distinctKeys(keys: List<String>): List<String> =
    keys.map { it.trim() }.filter { it.isNotBlank() }.distinct()
