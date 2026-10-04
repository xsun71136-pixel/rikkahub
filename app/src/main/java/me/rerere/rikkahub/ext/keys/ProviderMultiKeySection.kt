package me.rerere.rikkahub.ext.keys

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.ai.provider.*
import me.rerere.ai.util.KeyRotationPolicy
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Link01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.Switch

/** Existing multi-key switch and manager entry, kept compact and progressively disclosed. */
@Composable
fun ProviderMultiKeySection(provider: ProviderSetting, onEdit: (ProviderSetting) -> Unit) {
    var showManager by rememberSaveable { mutableStateOf(false) }
    val health by KeyRotationPolicy.healthFlow.collectAsState()
    val policy by KeyRotationPolicy.policyFlow.collectAsState()
    var now by remember(provider.id) { mutableLongStateOf(System.currentTimeMillis()) }
    val records = health[provider.id.toString()].orEmpty()
    LaunchedEffect(records, policy) {
        now = System.currentTimeMillis()
        while (policy.enabled && records.values.any { it.until > now && it.until != Long.MAX_VALUE }) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val keys = provider.getProviderApiKeys().normalizedProviderApiKeys()
    val activeCount = provider.activeApiKeyValuesForRequest().count { value ->
        !policy.enabled || (records[value]?.until ?: 0L) <= now
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.setting_provider_page_multi_key_mode), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.setting_provider_page_multi_key_mode_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = provider.isMultiKeyEnabled(), onCheckedChange = { enabled ->
                    onEdit(if (enabled) provider.enableMultiKeyFromCurrentValue() else provider.copyWithApiKeyConfig(multiKeyEnabled = false))
                })
            }
            AnimatedVisibility(visible = provider.isMultiKeyEnabled()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.setting_provider_page_multi_key_summary, activeCount, keys.size),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (activeCount == 0 && keys.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { showManager = true }) {
                        Icon(HugeIcons.Link01, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.setting_provider_page_multi_key_manager))
                    }
                }
            }
        }
    }

    if (showManager) ProviderKeyManagerSheet(provider, { showManager = false }, onEdit)
}
