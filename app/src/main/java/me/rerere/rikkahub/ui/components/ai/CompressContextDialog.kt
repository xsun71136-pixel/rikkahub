package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.CompressSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.lastContextTokens
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.theme.extendColors
import kotlin.uuid.Uuid

/** 45.2K 风格的 token 数缩写（与统计页一致） */
private fun formatTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000 -> "%.2fM".format(tokens / 1_000_000.0)
    tokens >= 1_000 -> "%.1fK".format(tokens / 1_000.0)
    else -> tokens.toString()
}

/**
 * 压缩对话历史对话框：当前占用一览、压缩模型（按供应商分组）、自动压缩与比例（% + K 数预览）、
 * 保留最近的消息数、附加说明。所有设置改动即时写入 [CompressSetting]，下次打开自动沿用；确认才执行压缩。
 */
@Composable
fun CompressContextDialog(
    settings: Settings,
    conversation: Conversation,
    chatModel: Model?,
    onUpdateSetting: (CompressSetting) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (additionalPrompt: String, keepRecentMessages: Int, modelId: Uuid?) -> Job,
) {
    val config = settings.compressSetting
    // 存过的模型可能已被删掉，这时回落到跟随对话模型
    val compressModel = remember(config.modelId, settings.providers) {
        config.modelId?.let { settings.findModelById(it) }
    }
    var currentJob by remember { mutableStateOf<Job?>(null) }
    val isLoading = currentJob?.isActive == true
    var showModelSheet by remember { mutableStateOf(false) }

    // 当前占用：最后一条助手消息最后一次请求的上下文用量 / 对话模型的上下文窗口
    val contextTokens = remember(conversation.messageNodes) { conversation.lastContextTokens() }
    val contextLength = chatModel?.contextLength?.takeIf { it > 0 }
    val currentPercent = if (contextTokens != null && contextLength != null) {
        contextTokens * 100 / contextLength
    } else {
        null
    }
    // 阈值对应的 token 数，预览「多少 K 触发」
    val thresholdTokens = contextLength?.let { it * config.thresholdPercent / 100L }

    // Monitor job completion
    LaunchedEffect(currentJob) {
        currentJob?.join()
        if (currentJob?.isCompleted == true && currentJob?.isCancelled == false) {
            onDismiss()
        }
        currentJob = null
    }

    AlertDialog(
        onDismissRequest = {
            if (!isLoading) {
                onDismiss()
            }
        },
        title = {
            Text(stringResource(R.string.chat_page_compress_context_title))
        },
        text = {
            if (isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ContainedLoadingIndicator(
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = stringResource(R.string.chat_page_compressing),
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .imePadding()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = stringResource(R.string.chat_page_compress_context_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 当前占用：K 数 + 百分比 + 进度条（红线是自动压缩触发位置）
                    if (contextTokens != null && contextLength != null && currentPercent != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = formatTokenCount(contextTokens.toLong()),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = " / ${formatTokenCount(contextLength.toLong())} · $currentPercent%",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 2.dp),
                                )
                            }
                            UsageBar(
                                fraction = currentPercent / 100f,
                                thresholdFraction = if (config.autoCompress) config.thresholdPercent / 100f else null,
                            )
                        }
                    }

                    // 压缩模型（按供应商分组选择）
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.chat_page_compress_model),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                        ModelField(
                            model = compressModel,
                            onClick = { showModelSheet = true },
                            placeholder = stringResource(R.string.chat_page_compress_model_follow),
                        )
                    }

                    // 自动压缩
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.chat_page_compress_auto),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = stringResource(R.string.chat_page_compress_auto_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = config.autoCompress,
                            onCheckedChange = { onUpdateSetting(config.copy(autoCompress = it)) },
                        )
                    }

                    // 压缩比例：滑杆 + 百分比/K 数双预览
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.chat_page_compress_threshold),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (thresholdTokens != null) {
                                    stringResource(
                                        R.string.chat_page_compress_threshold_value,
                                        config.thresholdPercent,
                                        formatTokenCount(thresholdTokens),
                                    )
                                } else {
                                    "${config.thresholdPercent}%"
                                },
                                style = MaterialTheme.typography.titleMediumEmphasized,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Slider(
                            value = config.thresholdPercent.toFloat(),
                            onValueChange = { value ->
                                onUpdateSetting(config.copy(thresholdPercent = value.toInt().coerceIn(50, 95)))
                            },
                            valueRange = 50f..95f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = when {
                                currentPercent == null ->
                                    stringResource(R.string.chat_page_compress_usage_unknown)

                                config.autoCompress -> stringResource(
                                    R.string.chat_page_compress_usage_auto,
                                    currentPercent,
                                    config.thresholdPercent,
                                )

                                else -> stringResource(
                                    R.string.chat_page_compress_usage_manual,
                                    currentPercent,
                                    config.thresholdPercent,
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // 保留最近的消息数：滑杆即调即见
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.chat_page_compress_keep_recent),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stringResource(
                                    R.string.chat_page_compress_keep_value,
                                    config.keepRecentMessages,
                                ),
                                style = MaterialTheme.typography.titleMediumEmphasized,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Slider(
                            value = config.keepRecentMessages.toFloat(),
                            onValueChange = { value ->
                                onUpdateSetting(config.copy(keepRecentMessages = value.toInt().coerceIn(0, 20)))
                            },
                            valueRange = 0f..20f,
                            steps = 19,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(R.string.chat_page_compress_keep_recent_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // 附加说明（可选）
                    OutlinedTextField(
                        value = config.additionalPrompt,
                        onValueChange = { onUpdateSetting(config.copy(additionalPrompt = it)) },
                        label = { Text(stringResource(R.string.chat_page_compress_additional_prompt)) },
                        placeholder = { Text(stringResource(R.string.chat_page_compress_additional_prompt_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                    )

                    // 设置会记住
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.chat_page_compress_settings_remembered),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (isLoading) {
                TextButton(onClick = {
                    currentJob?.cancel()
                    currentJob = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                TextButton(onClick = {
                    currentJob = onConfirm(config.additionalPrompt, config.keepRecentMessages, compressModel?.id)
                }) {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
        dismissButton = {
            if (!isLoading) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )

    if (showModelSheet) {
        CompressModelSheet(
            settings = settings,
            selected = compressModel,
            onSelect = { model ->
                onUpdateSetting(config.copy(modelId = model?.id))
                showModelSheet = false
            },
            onDismiss = { showModelSheet = false },
        )
    }
}

/** 上下文用量条：填充是当前占用，竖线是自动压缩触发位置 */
@Composable
private fun UsageBar(
    fraction: Float,
    thresholdFraction: Float?,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(MaterialTheme.colorScheme.primary),
        )
        if (thresholdFraction != null) {
            Box(
                modifier = Modifier
                    .offset(x = maxWidth * thresholdFraction.coerceIn(0f, 1f) - 1.dp)
                    .size(width = 2.dp, height = 8.dp)
                    .background(MaterialTheme.extendColors.red6, CircleShape),
            )
        }
    }
}

/** 压缩模型选择：第一项固定为「跟随对话模型」，之后按供应商分组列出已配置的对话模型 */
@Composable
private fun CompressModelSheet(
    settings: Settings,
    selected: Model?,
    onSelect: (Model?) -> Unit,
    onDismiss: () -> Unit,
) {
    // 供应商 → 其下的对话模型，顺序与设置页一致；没有对话模型的供应商不显示
    val grouped = remember(settings.providers) {
        settings.providers
            .filter { it.enabled }
            .mapNotNull { provider ->
                val models = provider.models.filter { it.type == ModelType.CHAT }
                if (models.isEmpty()) null else provider to models
            }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_page_compress_model),
                style = MaterialTheme.typography.titleMediumEmphasized,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.chat_page_compress_model_follow)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onSelect(null) },
                leadingContent = {
                    Icon(
                        imageVector = HugeIcons.Brain02,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingContent = {
                    if (selected == null) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
            grouped.forEach { (provider, models) ->
                Text(
                    text = providerName(provider),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                models.forEach { model ->
                    ListItem(
                        headlineContent = { Text(model.displayName) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onSelect(model) },
                        leadingContent = {
                            AutoAIIcon(
                                name = model.modelId,
                                modifier = Modifier.size(24.dp),
                                color = Color.Transparent,
                            )
                        },
                        trailingContent = {
                            if (selected?.id == model.id) {
                                Icon(
                                    imageVector = HugeIcons.Tick01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 供应商显示名：用户填的名字优先，否则用类型名 */
private fun providerName(provider: ProviderSetting): String = provider.name.ifBlank {
    when (provider) {
        is ProviderSetting.OpenAI -> "OpenAI"
        is ProviderSetting.Google -> "Google"
        is ProviderSetting.Claude -> "Claude"
        else -> "Provider"
    }
}
