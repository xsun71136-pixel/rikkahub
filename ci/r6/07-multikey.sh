# 轮 6 步骤 7/7：多 Key 页顶栏改为 Scaffold + LargeFlexibleTopAppBar（跟随本项目设置页骨架）
set -euo pipefail
perl - app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/MultiKeyConfig.kt <<'PERL'
my $F = $ARGV[0];
open(my $in, '<', $F) or die; local $/; my $s = <$in>; close $in;
my $n = 0;
sub rep { my ($o,$nw)=@_; die "not found: ".substr($o,0,90) unless index($s,$o)>=0; $s =~ s/\Q$o\E/$nw/; $n++; }

rep("    Dialog(\n        onDismissRequest = onDismiss,\n        properties = DialogProperties(usePlatformDefaultWidth = false),\n    ) {\n        Surface(\n            modifier = Modifier.fillMaxSize(),\n            color = MaterialTheme.colorScheme.surface,\n        ) {\n            Column(\n                modifier = Modifier\n                    .fillMaxSize()\n                    .statusBarsPadding()\n                    .imePadding(),\n            ) {\n                // 顶栏：返回、标题、摘要、完成\n                Row(\n                    modifier = Modifier\n                        .fillMaxWidth()\n                        .padding(horizontal = 8.dp, vertical = 4.dp),\n                    verticalAlignment = Alignment.CenterVertically,\n                ) {\n                    IconButton(onClick = onDismiss) {\n                        Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))\n                    }\n                    Column(modifier = Modifier.weight(1f)) {\n                        Text(\n                            text = stringResource(R.string.setting_provider_page_multi_key),\n                            style = MaterialTheme.typography.titleMediumEmphasized,\n                        )\n                        Text(\n                            text = stringResource(\n                                R.string.setting_provider_page_multi_key_summary,\n                                strategyLabel(config.strategy),\n                                availableCount,\n                                keys.size,\n                            ),\n                            style = MaterialTheme.typography.bodySmall,\n                            color = MaterialTheme.colorScheme.onSurfaceVariant,\n                        )\n                    }\n                    TextButton(onClick = onDismiss) {\n                        Text(stringResource(R.string.common_done))\n                    }\n                }\n\n                LazyColumn(\n                    modifier = Modifier\n                        .fillMaxWidth()\n                        .weight(1f),\n                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 10.dp),\n                    verticalArrangement = Arrangement.spacedBy(10.dp),\n                ) {\n",
    "    // 和「文件管理」「MCP」这些设置页同一骨架：LargeFlexibleTopAppBar + CardGroup 分组\n    Scaffold(\n        topBar = {\n            LargeFlexibleTopAppBar(\n                title = { Text(stringResource(R.string.setting_provider_page_multi_key)) },\n                subtitle = {\n                    Text(\n                        stringResource(\n                            R.string.setting_provider_page_multi_key_summary,\n                            strategyLabel(config.strategy),\n                            availableCount,\n                            keys.size,\n                        )\n                    )\n                },\n                navigationIcon = { BackButton() },\n                actions = {\n                    TextButton(onClick = onDismiss) {\n                        Text(stringResource(R.string.common_done))\n                    }\n                },\n                scrollBehavior = scrollBehavior,\n                colors = CustomColors.topBarColors,\n            )\n        },\n        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),\n        containerColor = CustomColors.topBarColors.containerColor,\n    ) { innerPadding ->\n        LazyColumn(\n            modifier = Modifier\n                .fillMaxSize()\n                .imePadding(),\n            contentPadding = innerPadding + PaddingValues(horizontal = 14.dp, vertical = 8.dp),\n            verticalArrangement = Arrangement.spacedBy(10.dp),\n        ) {\n");

rep("            }\n        }\n    }\n\n    if (showTest) {\n",
    "        }\n\n    if (showTest) {\n");

{
  my $pos = index($s, "fun MultiKeyDialog(");
  die "MultiKeyDialog not found\n" if $pos < 0;
  my $tail = substr($s, $pos);
  my $a = "    var showTest by remember { mutableStateOf(false) }\n";
  my $p = index($tail, $a);
  die "showTest not found\n" if $p < 0;
  substr($tail, $p + length($a), 0) = "    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()\n";
  $s = substr($s, 0, $pos) . $tail;
  $n++;
}

rep("import androidx.compose.ui.window.DialogProperties\n", "");
rep("import androidx.compose.ui.window.Dialog\n", "");
rep("import me.rerere.hugeicons.stroke.ArrowLeft01\n", "");
rep("import androidx.compose.material3.InputChip\n", "import androidx.compose.material3.InputChip\nimport androidx.compose.material3.LargeFlexibleTopAppBar\n");
rep("import androidx.compose.material3.OutlinedTextField\n", "import androidx.compose.material3.OutlinedTextField\nimport androidx.compose.material3.Scaffold\n");
rep("import androidx.compose.material3.Text\n", "import androidx.compose.material3.Text\nimport androidx.compose.material3.TextButton\nimport androidx.compose.material3.TopAppBarDefaults\n");
rep("import androidx.compose.ui.graphics.Color\n", "import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.input.nestedscroll.nestedScroll\n");
rep("import me.rerere.rikkahub.ui.components.ai.ModelField\n", "import me.rerere.rikkahub.ui.components.ai.ModelField\nimport me.rerere.rikkahub.ui.components.nav.BackButton\n");

open(my $o, '>', $F) or die; print $o $s; close $o;
print "multikey topbar edits=$n\n";
PERL
