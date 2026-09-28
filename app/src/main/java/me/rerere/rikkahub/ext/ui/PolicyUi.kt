package me.rerere.rikkahub.ext.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R

/** One bounded viewport; callers provide a scrolling body and a fixed footer. */
@Composable
fun PolicyScreen(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    footer: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxHeight().widthIn(max = 840.dp).fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onClose) { Icon(HugeIcons.Cancel01, stringResource(R.string.polish_close)) }
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).fillMaxWidth(), content = content)
                footer()
            }
        }
    }
}

@Composable
fun PolicyCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) PolicyHint(subtitle)
            content()
        }
    }
}

@Composable
fun PolicyHint(text: String, warning: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun PolicyToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            PolicyHint(subtitle)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun PolicySlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, display: String, exactScale: Float = 1f, exactUnit: String = "", onChange: (Float) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Surface(onClick = { input = (value / exactScale).toString(); editing = true }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(display, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps)
    }
    if (editing) {
        val parsed = input.toFloatOrNull()?.times(exactScale)
        val valid = parsed != null && parsed.isFinite() && parsed in range
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(title) }, text = {
            OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
                label = { Text(stringResource(R.string.polish_exact_value) + if (exactUnit.isNotEmpty()) " ($exactUnit)" else "") }, isError = !valid,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                supportingText = { Text(stringResource(R.string.polish_number_range, (range.start / exactScale).toString(), (range.endInclusive / exactScale).toString())) })
        }, confirmButton = { TextButton(enabled = valid, onClick = { parsed?.let(onChange); editing = false }) { Text(stringResource(R.string.common_confirm)) } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
fun PolicyFooter(dirty: Boolean, onReset: () -> Unit, onSave: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onReset) { Text(stringResource(R.string.auto_retry_reset_all)) }
            Button(onClick = onSave, enabled = dirty, modifier = Modifier.weight(1f)) {
                Text(stringResource(if (dirty) R.string.polish_save_changes else R.string.polish_saved))
            }
        }
    }
}

@Composable
fun PolicyConfirm(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
