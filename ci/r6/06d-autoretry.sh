# 轮 6 步骤 6d/7：自动重试页 总开关联动置灰 + imports
set -euo pipefail
perl - app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingAutoRetryPage.kt <<'PERL'
my $F = $ARGV[0];
open(my $in, '<', $F) or die; local $/; my $s = <$in>; close $in;
my $n = 0;
sub rep { my ($o,$nw)=@_; die "not found: ".substr($o,0,80) unless index($s,$o)>=0; $s =~ s/\Q$o\E/$nw/; $n++; }

rep("import androidx.compose.foundation.horizontalScroll\n", "");
rep("import androidx.compose.foundation.rememberScrollState\n", "");
rep("import androidx.compose.material3.AlertDialog\n", "");
rep("import androidx.compose.ui.Modifier\n", "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.draw.alpha\n");
rep("import androidx.compose.foundation.layout.padding\n", "import androidx.compose.foundation.layout.padding\nimport androidx.compose.foundation.layout.size\n");
rep("import androidx.compose.material3.SheetValue\n", "import androidx.compose.material3.SheetValue\nimport androidx.compose.material3.SuggestionChip\n");

rep("            // 重试策略\n            item {\n                val delays = remember(config) { expectedDelays(config) }\n",
    "            // 总开关关掉后下面的配置不可点，避免设了却不生效\n            val configEnabled = network.enableAutoRetry\n            val configAlpha = if (configEnabled) 1f else 0.38f\n\n            // 重试策略\n            item {\n                val delays = remember(config) { expectedDelays(config) }\n");

rep("onClick = { editingList = RetryListKind.CODES },", "onClick = if (configEnabled) ({ editingList = RetryListKind.CODES }) else null,");
rep("onClick = { editingList = RetryListKind.KEYWORDS },", "onClick = if (configEnabled) ({ editingList = RetryListKind.KEYWORDS }) else null,");
rep("onClick = { editingList = RetryListKind.STOP },", "onClick = if (configEnabled) ({ editingList = RetryListKind.STOP }) else null,");

rep("                        checked = config.retryOnNetworkError,\n                        onCheckedChange = { on -> update { it.copy(retryOnNetworkError = on) } },\n",
    "                        checked = config.retryOnNetworkError,\n                        onCheckedChange = { on -> update { it.copy(retryOnNetworkError = on) } },\n                        enabled = configEnabled,\n");
rep("                            checked = config.jitter,\n                            onCheckedChange = { jitter -> update { it.copy(jitter = jitter) } },\n",
    "                            checked = config.jitter,\n                            onCheckedChange = { jitter -> update { it.copy(jitter = jitter) } },\n                            enabled = configEnabled,\n");
rep("                                onSelect = { custom ->\n                                    update { it.copy(custom = custom) }\n                                },\n",
    "                                onSelect = { custom ->\n                                    if (configEnabled) update { it.copy(custom = custom) }\n                                },\n");

rep("                CardGroup(\n                    title = { Text(stringResource(R.string.setting_auto_retry_conditions_title)) },\n                ) {",
    "                CardGroup(\n                    modifier = Modifier.alpha(configAlpha),\n                    title = { Text(stringResource(R.string.setting_auto_retry_conditions_title)) },\n                ) {");
rep("                CardGroup(\n                    title = { Text(stringResource(R.string.setting_auto_retry_stop_title)) },\n                ) {",
    "                CardGroup(\n                    modifier = Modifier.alpha(configAlpha),\n                    title = { Text(stringResource(R.string.setting_auto_retry_stop_title)) },\n                ) {");

rep("// 预期节奏：把每次重试前的等待按序列展示出来\n", "// 预期节奏：把每次重试前的等待按序列展示出来，自动换行不横滑\n");

open(my $o, '>', $F) or die; print $o $s; close $o;
print "autoretry d edits=$n\n";
PERL
