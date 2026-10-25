# 轮 6 步骤 6c/7：自动重试页 ParamCell 样式 + 数值编辑改底部弹层
set -euo pipefail
perl - app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingAutoRetryPage.kt <<'PERL'
my $F = $ARGV[0];
open(my $in, '<', $F) or die; my @L = <$in>; close $in;
my $n = 0;
my $s = join('', @L);
sub rep { my ($o,$nw)=@_; die "not found: ".substr($o,0,80) unless index($s,$o)>=0; $s =~ s/\Q$o\E/$nw/; $n++; }
rep("        shape = MaterialTheme.shapes.large,\n", "        // 和 Dialog 里其它小格一致：大圆角 + 最低一档容器色，深浅主题都留得住边界\n        shape = MaterialTheme.shapes.extraLarge,\n");
rep("        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {\n",
    "        Column(\n            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),\n            verticalArrangement = Arrangement.spacedBy(2.dp),\n        ) {\n");
rep("                    style = MaterialTheme.typography.titleLarge,\n",
    "                    // 四个格子并排时 titleLarge 过重，比分组标题小一档刚好\n                    style = MaterialTheme.typography.titleMediumEmphasized,\n                    maxLines = 1,\n");
rep("                Text(\n                    text = \" \$unit\",\n                    style = MaterialTheme.typography.labelMedium,\n                    color = MaterialTheme.colorScheme.onSurfaceVariant,\n                    modifier = Modifier.padding(bottom = 2.dp),\n                )\n",
    "                Text(\n                    text = unit,\n                    style = MaterialTheme.typography.labelSmall,\n                    color = MaterialTheme.colorScheme.onSurfaceVariant,\n                    modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),\n                )\n");
@L = split /(?<=\n)/, $s;

my $ad = -1;
for my $i (0..$#L) { if ($L[$i] =~ /^        AlertDialog\($/) { $ad = $i; last } }
die "AlertDialog not found\n" if $ad < 0;
my $adend = -1;
for my $i ($ad..$#L) { if ($L[$i] =~ /^        \)\s*$/) { $adend = $i; last } }
die "AlertDialog end not found\n" if $adend < 0;
my @sheet = (
"        val sheetState = rememberBottomSheetState(\n",
"            initialValue = SheetValue.Hidden,\n",
"            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),\n",
"        )\n",
"        val canCommit = draft.isNotBlank() && draft != value\n",
"        fun commit() {\n",
"            if (draft.isNotBlank()) onCommit(draft)\n",
"            editing = false\n",
"        }\n",
"        // 数值编辑放底部弹层：大输入框 + 大按钮，比窄的 AlertDialog 好按，键盘也不遮\n",
"        ModalBottomSheet(\n",
"            onDismissRequest = { editing = false },\n",
"            sheetState = sheetState,\n",
"        ) {\n",
"            Column(\n",
"                modifier = Modifier\n",
"                    .fillMaxWidth()\n",
"                    .imePadding()\n",
"                    .padding(horizontal = 16.dp)\n",
"                    .padding(bottom = 28.dp),\n",
"                verticalArrangement = Arrangement.spacedBy(12.dp),\n",
"            ) {\n",
"                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {\n",
"                    Text(\n",
"                        text = label,\n",
"                        style = MaterialTheme.typography.titleMediumEmphasized,\n",
"                    )\n",
"                    Text(\n",
"                        text = rangeHint,\n",
"                        style = MaterialTheme.typography.bodySmall,\n",
"                        color = MaterialTheme.colorScheme.onSurfaceVariant,\n",
"                    )\n",
"                }\n",
"                OutlinedTextField(\n",
"                    value = draft,\n",
"                    onValueChange = { next ->\n",
"                        draft = if (decimal) {\n",
"                            next.filter { it.isDigit() || it == '.' }\n",
"                        } else {\n",
"                            next.filter { it.isDigit() }\n",
"                        }\n",
"                    },\n",
"                    modifier = Modifier.fillMaxWidth(),\n",
"                    textStyle = MaterialTheme.typography.headlineSmall,\n",
"                    singleLine = true,\n",
"                    suffix = { Text(unit, style = MaterialTheme.typography.bodyLarge) },\n",
"                    keyboardOptions = KeyboardOptions(\n",
"                        keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,\n",
"                    ),\n",
"                )\n",
"                FilledTonalButton(\n",
"                    onClick = { commit() },\n",
"                    modifier = Modifier.fillMaxWidth(),\n",
"                    enabled = canCommit,\n",
"                ) {\n",
"                    Text(stringResource(R.string.confirm))\n",
"                }\n",
"            }\n",
"        }\n",
);
splice @L, $ad, $adend - $ad + 1, @sheet;
$n++;
open(my $o, '>', $F) or die; print $o @L; close $o;
print "autoretry c edits=$n, lines=", scalar(@L), "\n";
PERL
