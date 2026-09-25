package me.rerere.rikkahub.ext.keys

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.delay
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
import me.rerere.ai.util.KeyHealthRecord
import me.rerere.ai.util.KeyHealthState
import me.rerere.ai.util.KeyRotationPolicy
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.context.LocalToaster
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

    // [自定义修改] Key 健康状态：停用（无效/无额度）/冷却 徽标与恢复入口
    val health by KeyRotationPolicy.healthFlow.collectAsState()
    val providerId = provider.id.toString()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    fun recordFor(key: ProviderApiKey): KeyHealthRecord? {
        val record = health[providerId]?.get(key.value) ?: return null
        return if (record.until > now) record else null
    }

    val hasHealthMarks = keys.any { recordFor(it) != null }
    LaunchedEffect(hasHealthMarks) {
        // 有冷却倒计时时每秒刷新一次剩余时间
        while (hasHealthMarks) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    var editingKey by remember { mutableStateOf<ProviderApiKey?>(null) }
    // [自定义修改] 导入对话框不再自动读取剪贴板，默认空白，由用户手动粘贴
    var showImportDialog by remember { mutableStateOf(false) }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
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
                if (hasHealthMarks) {
                    TextButton(
                        onClick = {
                            KeyRotationPolicy.clearProviderHealth(providerId)
                            toaster.show(
                                message = context.getString(
                                    R.string.setting_provider_page_multi_key_health_restored_all
                                ),
                                type = ToastType.Success,
                            )
                        },
                    ) {
                        Icon(HugeIcons.Refresh01, null, Modifier.size(16.dp))
                        Text(
                            text = stringResource(R.string.setting_provider_page_multi_key_restore_all),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }

            if (hasHealthMarks) {
                Text(
                    text = stringResource(R.string.setting_provider_page_multi_key_health_caption),
                    style = MaterialTheme.typography.bodySmall,
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
                    onClick = { showImportDialog = true },
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
                            health = recordFor(key),
                            now = now,
                            onRestoreHealth = {
                                KeyRotationPolicy.clearKeyHealth(providerId, key.value)
                                toaster.show(
                                    message = context.getString(
                                        R.string.setting_provider_page_multi_key_health_restored
                                    ),
                                    type = ToastType.Success,
                                )
                            },
                            onToggle = { enabled ->
                                // [自定义修改] 手动打开开关 = 同时恢复健康停用/冷却标记
                                if (enabled) {
                                    KeyRotationPolicy.clearKeyHealth(providerId, key.value)
                                }
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
    if (showImportDialog) {
        ProviderApiKeyImportDialog(
            initialText = "",
            onDismissRequest = { showImportDialog = false },
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
                    showImportDialog = false
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
    health: KeyHealthRecord?,
    now: Long,
    onRestoreHealth: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val providerManager = koinInject<ProviderManager>()
    val scope = rememberCoroutineScope()
    var testing by remember(apiKey.id) { mutableStateOf(false) }

    fun runTest() {
        val model = testModel ?: return
        scope.launch {
            testing = true
            // 显式测试 = 先恢复该 Key 再实测；结果回写健康状态（失败会自动重新停用/冷却）
            KeyRotationPolicy.clearKeyHealth(provider.id.toString(), apiKey.value)
            val result = runCatching {
                val single = provider.withSingleApiKeyForRequest(apiKey.value)
                val impl = providerManager.getProviderByType(single)
                impl.generateText(
                    providerSetting = single,
                    messages = listOf(UIMessage.user("hello")),
                    params = TextGenerationParams(
                        model = model,
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                    ),
                )
            }
            result.onSuccess {
                KeyRotationPolicy.reportSuccess(provider.id.toString())
                toaster.show(
                    message = context.getString(R.string.setting_provider_page_multi_key_test_success),
                    type = ToastType.Success,
                )
            }.onFailure { error ->
                KeyRotationPolicy.reportFailure(provider.id.toString(), error)
                toaster.show(
                    message = error.message?.lineSequence()?.firstOrNull()?.take(80)
                        ?: context.getString(R.string.setting_provider_page_multi_key_test_failed),
                    type = ToastType.Error,
                )
            }
            testing = false
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        // 单行布局：别名+健康徽标 / 掩码 / 开关 / 测试 / 编辑 / 删除
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (health != null) {
                        val label = when (health.state) {
                            KeyHealthState.INVALID -> stringResource(
                                R.string.setting_provider_page_multi_key_health_invalid
                            )

                            KeyHealthState.QUOTA -> stringResource(
                                R.string.setting_provider_page_multi_key_health_quota
                            )

                            KeyHealthState.COOLDOWN -> stringResource(
                                R.string.setting_provider_page_multi_key_health_cooldown,
                                formatRemaining(health.until - now),
                            )
                        }
                        Text(
                            text = " · $label",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (health.state == KeyHealthState.COOLDOWN) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            maxLines = 1,
                            // 点击徽标即恢复该 Key
                            modifier = Modifier.clickable { onRestoreHealth() },
                        )
                    }
                }
                Text(
                    text = maskProviderApiKey(apiKey.value),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // [自定义修改] 健康停用（无效/额度）时开关同步显示为关闭，避免“写着停用开关还开着”
            val suspendedByHealth =
                health != null && health.state != KeyHealthState.COOLDOWN
            Switch(
                checked = apiKey.enabled && !suspendedByHealth,
                onCheckedChange = onToggle,
            )
            CompactIconButton(
                onClick = { runTest() },
                enabled = testModel != null && !testing,
            ) {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        HugeIcons.Connect,
                        stringResource(R.string.setting_provider_page_multi_key_test),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            CompactIconButton(onClick = onEdit) {
                Icon(
                    HugeIcons.Edit01,
                    stringResource(R.string.common_edit),
                    modifier = Modifier.size(18.dp),
                )
            }
            CompactIconButton(onClick = onDelete) {
                Icon(
                    HugeIcons.Delete01,
                    stringResource(R.string.common_delete),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 38dp 紧凑图标按钮，保证一行能放下 开关+测试+编辑+删除。 */
@Composable
private fun CompactIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(38.dp),
    ) {
        content()
    }
}

/** 冷却剩余时间的紧凑格式：45s / 3m20s / 1h05m。 */
private fun formatRemaining(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return when {
        seconds >= 3600 -> "${seconds / 3600}h${(seconds % 3600) / 60}m"
        seconds >= 60 -> "${seconds / 60}m${seconds % 60}s"
        else -> "${seconds}s"
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
