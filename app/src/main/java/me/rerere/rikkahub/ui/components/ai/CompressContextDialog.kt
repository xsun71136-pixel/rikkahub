package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
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
import kotlin.uuid.Uuid

/**
 * 压缩对话历史对话框：压缩模型（默认跟随对话模型）、自动压缩与比例、保留最近的消息数、附加说明。
 * 所有设置改动即时写入 [CompressSetting]，下次打开自动沿用；确认才执行压缩。
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
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .imePadding()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = stringResource(R.string.chat_page_compress_context_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 压缩模型
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

                    // 压缩比例
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                text = "${config.thresholdPercent}%",
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

                    // 保留最近的消息数
                    var keepDraft by rememberSaveable(config.keepRecentMessages) {
                        mutableStateOf(config.keepRecentMessages.toString())
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = keepDraft,
                            onValueChange = { next ->
                                keepDraft = next.filter { it.isDigit() }
                                keepDraft.toIntOrNull()?.let { value ->
                                    onUpdateSetting(config.copy(keepRecentMessages = value.coerceIn(0, 500)))
                                }
                            },
                            label = { Text(stringResource(R.string.chat_page_compress_keep_recent)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                        maxLines = 4,
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

/** 压缩模型选择：第一项固定为「跟随对话模型」，之后是本机已配置的全部对话模型 */
@Composable
private fun CompressModelSheet(
    settings: Settings,
    selected: Model?,
    onSelect: (Model?) -> Unit,
    onDismiss: () -> Unit,
) {
    val models = remember(settings.providers) {
        settings.providers.filter { it.enabled }.flatMap { provider ->
            provider.models.filter { it.type == ModelType.CHAT }
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
