package me.rerere.rikkahub.ext.keys

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.activeApiKeyValuesForRequest
import me.rerere.ai.provider.enableMultiKeyFromCurrentValue
import me.rerere.ai.provider.copyWithApiKeyConfig
import me.rerere.ai.provider.getProviderApiKeys
import me.rerere.ai.provider.isMultiKeyEnabled
import me.rerere.ai.provider.normalizedProviderApiKeys
import me.rerere.ai.util.KeyRotationPolicy
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Link01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.Switch

/**
 * 提供商配置页的"多 Key 模式"区块（自定义插件层，功能参考 FLIT-feature，
 * 视觉沿用本项目组件，见 docs/custom/03-multi-key.md）。
 *
 * 放置于单 Key 输入框下方：
 * - 开关开启时自动从当前 apiKey 字符串拆分导入 Key 列表；
 * - 开启后显示"管理 Key"入口，打开 [ProviderKeyManagerSheet]。
 */
@Composable
fun ProviderMultiKeySection(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
) {
    var showManager by remember { mutableStateOf(false) }
    // [自定义修改] 可用计数 = 启用且未被自动停用（无效/无额度/冷却中）的 Key
    val health by KeyRotationPolicy.healthFlow.collectAsState()
    val now = System.currentTimeMillis()
    val records = health[provider.id.toString()].orEmpty()
    val activeCount = provider.activeApiKeyValuesForRequest().count { value ->
        (records[value]?.until ?: 0L) <= now
    }
    val totalCount = provider.getProviderApiKeys().normalizedProviderApiKeys().size

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.setting_provider_page_multi_key_mode))
            Text(
                text = stringResource(R.string.setting_provider_page_multi_key_mode_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = provider.isMultiKeyEnabled(),
            onCheckedChange = { enabled ->
                onEdit(
                    if (enabled) {
                        provider.enableMultiKeyFromCurrentValue()
                    } else {
                        provider.copyWithApiKeyConfig(multiKeyEnabled = false)
                    }
                )
            },
        )
    }

    AnimatedVisibility(visible = provider.isMultiKeyEnabled()) {
        FilledTonalButton(
            onClick = { showManager = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(HugeIcons.Link01, contentDescription = null)
            Text(
                text = stringResource(
                    R.string.setting_provider_page_multi_key_manager_with_count,
                    activeCount,
                    totalCount,
                ),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    if (showManager) {
        ProviderKeyManagerSheet(
            provider = provider,
            onDismissRequest = { showManager = false },
            onProviderChange = onEdit,
        )
    }
}
