# 轮 6 修复 8/8：把多 Key 页重新包回 Dialog 子窗口。
#
# 为什么：MultiKeyDialog 是在 ProviderConfigure()（一个 Column + verticalScroll）里内联调起的，
# 07 把它改成了 Scaffold + LazyColumn 直接铺开，于是 LazyColumn 拿到的高度约束是 Infinity，
# 打开「管理多密钥」就抛：
#   java.lang.IllegalStateException: Vertically scrollable component was measured with an
#   infinity maximum height constraints, which is disallowed.
# 修法：外层恢复 Dialog（子窗口自带有限尺寸约束），usePlatformDefaultWidth=false +
# decorFitsSystemWindows=false 保持全屏观感（和扫码、图片预览这些全屏弹层同一套写法）；
# 返回键改用 FilledTonalIconButton(onClick = onDismiss)，与 BackButton 同一外观，
# 但不能用 BackButton —— 它会 popBackStack，把底下的服务商详情页一起退掉。
set -euo pipefail
perl - app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/MultiKeyConfig.kt <<'PERL'
my $F = $ARGV[0];
open(my $in, '<', $F) or die; local $/; my $s = <$in>; close $in;
die "already wrapped\n" if index($s, "usePlatformDefaultWidth") >= 0;
my $n = 0;
sub rep { my ($o,$nw)=@_; die "not found: ".substr($o,0,90)."\n" unless index($s,$o)>=0; $s =~ s/\Q$o\E/$nw/; $n++; }

# 1) 返回键：Dialog 里不能用 BackButton（它会 popBackStack）
rep("                navigationIcon = { BackButton() },\n",
"                navigationIcon = {\n" .
"                    FilledTonalIconButton(\n" .
"                        onClick = onDismiss,\n" .
"                        shapes = IconButtonDefaults.shapes(),\n" .
"                        colors = IconButtonDefaults.filledTonalIconButtonColors(\n" .
"                            containerColor = CustomColors.listItemColors.containerColor,\n" .
"                        ),\n" .
"                    ) {\n" .
"                        Icon(\n" .
"                            imageVector = HugeIcons.ArrowLeft01,\n" .
"                            contentDescription = stringResource(R.string.back),\n" .
"                        )\n" .
"                    }\n" .
"                },\n");

# 2) 注释写明为什么必须套 Dialog
rep("    // 全屏页而不是对话框：和「文件管理」「MCP」这些设置页同一骨架，密钥多了才放得下\n",
"    // 和「文件管理」「MCP」这些设置页同一骨架：LargeFlexibleTopAppBar + CardGroup。\n" .
"    // 外面必须包一层 Dialog —— 本组件由 ProviderConfigure 的 verticalScroll Column 内联调起，\n" .
"    // 直接铺 Scaffold 会拿到无限高度约束，里面的 LazyColumn 会抛 IllegalStateException。\n" .
"    // Dialog 子窗口自带有限尺寸，全屏观感由 usePlatformDefaultWidth=false 保证。\n");

# 3) 把 Scaffold ... } 整块缩进 8 格，前后套 Dialog + Surface
my $a = index($s, "    Scaffold(\n");
die "Scaffold not found\n" if $a < 0;
my $tb = index($s, "\n    }\n\n    if (showTest) {\n", $a);
die "scaffold tail not found\n" if $tb < 0;
my $b = $tb + length("\n    }");
my $body = substr($s, $a, $b - $a);
$body = substr($body, 0, length($body) - length("\n    }"));
$body =~ s/^(.+)$/        $1/mg;

my $head =
"    Dialog(\n" .
"        onDismissRequest = onDismiss,\n" .
"        properties = DialogProperties(\n" .
"            usePlatformDefaultWidth = false,\n" .
"            decorFitsSystemWindows = false,\n" .
"        ),\n" .
"    ) {\n" .
"        Surface(\n" .
"            modifier = Modifier.fillMaxSize(),\n" .
"            color = CustomColors.topBarColors.containerColor,\n" .
"        ) {\n";
$s = substr($s, 0, $a) . $head . $body . "\n            }\n        }\n    }" . substr($s, $b);
$n++;

# 4) imports
rep("import me.rerere.rikkahub.ui.components.nav.BackButton\n", "");
rep("import androidx.compose.material3.FilledTonalButton\n",
    "import androidx.compose.material3.FilledTonalButton\nimport androidx.compose.material3.FilledTonalIconButton\n");
rep("import androidx.compose.material3.IconButton\n",
    "import androidx.compose.material3.IconButton\nimport androidx.compose.material3.IconButtonDefaults\n");
rep("import androidx.compose.material3.Scaffold\n",
    "import androidx.compose.material3.Scaffold\nimport androidx.compose.material3.Surface\n");
rep("import androidx.compose.ui.unit.dp\n",
    "import androidx.compose.ui.unit.dp\nimport androidx.compose.ui.window.Dialog\nimport androidx.compose.ui.window.DialogProperties\n");
rep("import me.rerere.hugeicons.stroke.Add01\n",
    "import me.rerere.hugeicons.stroke.Add01\nimport me.rerere.hugeicons.stroke.ArrowLeft01\n");

open(my $o, '>', $F) or die; print $o $s; close $o;
print "multikey dialog wrap edits=$n\n";
PERL
