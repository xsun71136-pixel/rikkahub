package me.rerere.rikkahub.ext.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderApiKey
import me.rerere.ai.provider.ProviderKeyStrategy
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.copyWithApiKeyConfig
import me.rerere.ai.provider.getProviderApiKeys
import me.rerere.ai.provider.getProviderKeyStrategy
import me.rerere.ai.provider.maskProviderApiKey
import me.rerere.ai.provider.normalizedProviderApiKeys
import me.rerere.ai.provider.splitProviderApiKeys
import me.rerere.ai.provider.syncEnabledApiKeysToLegacyField
import me.rerere.ai.provider.withSingleApiKeyForRequest
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.readClipboardText
import org.koin.compose.koinInject

/**
 * Key 管理器底部弹窗（自定义插件层，功能参考 FLIT-feature ProviderKeyManager，
 * 视觉沿用本项目 Material3 组件，见 docs/custom/03-multi-key.md）。
 *
 * 能力：策略切换（随机/轮询）、添加/粘贴导入、别名、启用/禁用、删除、单 Key 连通测试。
 * 所有变更通过 [onProviderChange] 写回 provider（并同步启用 Key 到旧 apiKey 字段）。
 */
@Composable
fun ProviderKeyManagerSheet(
    provider: ProviderSetting,
    onDismissRequest: () -> Unit,
    onProviderChange: (ProviderSetting) -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val keys = provider.getProviderApiKeys().normalizedProviderApiKeys()
    val activeCount = keys.count { it.enabled }
    val testModel = remember(provider.models) {
        provider.models.firstOrNull { it.type == ModelType.CHAT }
    }

    var editingKey by remember { mutableStateOf<ProviderApiKey?>(null) }
    var importText by remember { mutableStateOf<String?>(null) }
    var deletingKey by remember { mutableStateOf<ProviderApiKey?>(null) }

    fun updateKeys(updatedKeys: List<ProviderApiKey>) {
        onProviderChange(
            provider.copyWithApiKeyConfig(
                multiKeyEnabled = true,
                apiKeys = updatedKeys.normalizedProviderApiKeys(),
            ).syncEnabledApiKeysToLegacyField()
        )
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
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.setting_provider_page_multi_key_manager),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(
                        R.string.setting_provider_page_multi_key_summary,
                        activeCount,
                        keys.size,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- 轮换策略 ----
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    ProviderKeyStrategy.RANDOM to stringResource(R.string.setting_provider_page_multi_key_strategy_random),
                    ProviderKeyStrategy.ROUND_ROBIN to stringResource(R.string.setting_provider_page_multi_key_strategy_round_robin),
                )
                options.forEachIndexed { index, (strategy, label) ->
                    SegmentedButton(
                        selected = provider.getProviderKeyStrategy() == strategy,
                        onClick = {
                            onProviderChange(provider.copyWithApiKeyConfig(keyStrategy = strategy))
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        label = { Text(label) },
                    )
                }
            }

            // ---- 添加 / 粘贴导入 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = { editingKey = ProviderApiKey() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                    Text(
                        text = stringResource(R.string.setting_provider_page_multi_key_add),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                OutlinedButton(
                    onClick = { importText = context.readClipboardText() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(HugeIcons.Clipboard, contentDescription = null)
                    Text(
                        text = stringResource(R.string.setting_provider_page_multi_key_import),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            if (testModel == null) {
                Text(
                    text = stringResource(R.string.setting_provider_page_multi_key_test_needs_model),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- Key 列表 ----
            if (keys.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_multi_key_empty),
                        modifier = Modifier.padding(18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(
                        items = keys,
                        key = { _, key -> key.id.toString() },
                    ) { index, key ->
                        ProviderApiKeyCard(
                            index = index,
                            apiKey = key,
                            provider = provider,
                            testModel = testModel,
                            onToggle = { enabled ->
                                updateKeys(keys.map {
                                    if (it.id == key.id) it.copy(enabled = enabled) else it
                                })
                            },
                            onEdit = { editingKey = key },
                            onDelete = { deletingKey = key },
                        )
                    }
                }
            }
        }
    }

    // ---- 编辑/添加对话框 ----
    editingKey?.let { initial ->
        ProviderApiKeyEditDialog(
            initial = initial,
            onDismissRequest = { editingKey = null },
            onConfirm = { edited ->
                val updated = if (keys.any { it.id == edited.id }) {
                    keys.map { if (it.id == edited.id) edited else it }
                } else {
                    keys + edited
                }
                updateKeys(updated)
                editingKey = null
            },
        )
    }

    // ---- 粘贴导入对话框 ----
    importText?.let { initial ->
        ProviderApiKeyImportDialog(
            initialText = initial,
            onDismissRequest = { importText = null },
            onImport = { raw ->
                val existingValues = keys.map { it.value }.toSet()
                val imported = splitProviderApiKeys(raw)
                    .filterNot { it in existingValues }
                    .map { value -> ProviderApiKey(value = value) }
                if (imported.isEmpty()) {
                    toaster.show(
                        message = context.getString(R.string.setting_provider_page_multi_key_import_empty),
                        type = ToastType.Warning,
                    )
                } else {
                    updateKeys(keys + imported)
                    toaster.show(
                        message = context.getString(
                            R.string.setting_provider_page_multi_key_imported,
                            imported.size,
                        ),
                        type = ToastType.Success,
                    )
                    importText = null
                }
            },
        )
    }

    // ---- 删除确认 ----
    deletingKey?.let { key ->
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.setting_provider_page_multi_key_delete_title),
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                updateKeys(keys.filterNot { it.id == key.id })
                deletingKey = null
            },
            onDismiss = { deletingKey = null },
        ) {
            Text(
                text = stringResource(
                    R.string.setting_provider_page_multi_key_delete_desc,
                    key.alias.ifBlank { maskProviderApiKey(key.value) },
                )
            )
        }
    }
}

@Composable
private fun ProviderApiKeyCard(
    index: Int,
    apiKey: ProviderApiKey,
    provider: ProviderSetting,
    testModel: me.rerere.ai.provider.Model?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val providerManager = koinInject<ProviderManager>()
    val scope = rememberCoroutineScope()
    var testState by remember(apiKey.id) { mutableStateOf<UiState<String>>(UiState.Idle) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = apiKey.alias.ifBlank {
                            stringResource(
                                R.string.setting_provider_page_multi_key_default_alias,
                                index + 1,
                            )
                        },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = maskProviderApiKey(apiKey.value),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Switch(checked = apiKey.enabled, onCheckedChange = onToggle)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                when (testState) {
                    is UiState.Loading -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )

                    is UiState.Success -> Icon(
                        HugeIcons.CheckmarkCircle02,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )

                    is UiState.Error -> Text(
                        text = (testState as UiState.Error).error.message
                            ?.lineSequence()?.firstOrNull()
                            ?.take(60)
                            ?: stringResource(R.string.setting_provider_page_multi_key_test_failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    else -> Unit
                }

                if (testModel != null) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                testState = UiState.Loading
                                testState = runCatching {
                                    val single = provider.withSingleApiKeyForRequest(apiKey.value)
                                    val impl = providerManager.getProviderByType(single)
                                    impl.generateText(
                                        providerSetting = single,
                                        messages = listOf(UIMessage.user("hello")),
                                        params = TextGenerationParams(
                                            model = testModel,
                                            customHeaders = testModel.customHeaders,
                                            customBody = testModel.customBodies,
                                        ),
                                    )
                                }.fold(
                                    onSuccess = { UiState.Success("OK") },
                                    onFailure = { UiState.Error(it) },
                                )
                            }
                        },
                    ) {
                        Icon(HugeIcons.Connect, stringResource(R.string.setting_provider_page_multi_key_test))
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(HugeIcons.Edit01, stringResource(R.string.common_edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(HugeIcons.Delete01, stringResource(R.string.common_delete))
                }
            }
        }
    }
}

@Composable
private fun ProviderApiKeyEditDialog(
    initial: ProviderApiKey,
    onDismissRequest: () -> Unit,
    onConfirm: (ProviderApiKey) -> Unit,
) {
    var value by remember { mutableStateOf(initial.value) }
    var alias by remember { mutableStateOf(initial.alias) }
    var keyVisible by remember { mutableStateOf(false) }

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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
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
                                contentDescription = null,
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
                enabled = value.isNotBlank(),
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
    onDismissRequest: () -> Unit,
    onImport: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    val parsedCount = remember(text) { splitProviderApiKeys(text).size }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.setting_provider_page_multi_key_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    text = stringResource(R.string.setting_provider_page_multi_key_import_count, parsedCount),
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
