# 轮 6 修复 8/8：把多 Key 页重新包回 Dialog 子窗口。
#
# 为什么：MultiKeyDialog 是在 ProviderConfigure()（Column + verticalScroll）里内联调起的。
# 07 把它改成 Scaffold + LazyColumn 直接铺开后，LazyColumn 拿到的高度约束是 Infinity，
# 打开「管理多密钥」就抛：
#   java.lang.IllegalStateException: Vertically scrollable component was measured with an
#   infinity maximum height constraints, which is disallowed.
# 修法：外层恢复 Dialog（子窗口自带有限尺寸约束），usePlatformDefaultWidth=false 保持全屏观感。
# 返回键改成 FilledTonalIconButton(onClick = onDismiss)，外观和 BackButton 一致，但不能用
# BackButton —— 它会 popBackStack，把底下的服务商详情页一起退掉。
#
# 做法上只依赖结构性锚点（Scaffold( 行 / if (showTest) 行 / navigationIcon 行），
# 并用大括号配平校验 Scaffold 块，因此对 07 各版本生成的缩进和收尾数量都不敏感，
# 也不会依赖它的注释文案。
set -euo pipefail
perl - app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/MultiKeyConfig.kt <<'PERL'
my $F = $ARGV[0];
open(my $in, '<', $F) or die; local $/; my $s = <$in>; close $in;
die "already wrapped\n" if index($s, "usePlatformDefaultWidth") >= 0;
my $n = 0;

sub depth {
    my ($t) = @_;
    my $d = 0;
    $d += ($t =~ tr/{//);
    $d -= ($t =~ tr/}//);
    return $d;
}

# 0) 幂等补 import（缺什么补什么，插在同类 import 后面）
sub need {
    my ($line, $after) = @_;
    return 1 if index($s, $line) >= 0;
    my $p = index($s, $after);
    return 0 if $p < 0;
    substr($s, $p + length($after), 0) = $line;
    $n++;
    return 1;
}
need("import androidx.compose.ui.window.Dialog\n", "import androidx.compose.ui.unit.dp\n")
    or die "anchor missing: ui.unit.dp\n";
need("import androidx.compose.ui.window.DialogProperties\n", "import androidx.compose.ui.window.Dialog\n")
    or die "anchor missing: window.Dialog\n";
need("import androidx.compose.material3.Surface\n", "import androidx.compose.material3.Scaffold\n")
    or die "anchor missing: material3.Scaffold\n";
need("import androidx.compose.material3.FilledTonalIconButton\n", "import androidx.compose.material3.FilledTonalButton\n")
    or die "anchor missing: material3.FilledTonalButton\n";
need("import androidx.compose.material3.IconButtonDefaults\n", "import androidx.compose.material3.IconButton\n")
    or die "anchor missing: material3.IconButton\n";
need("import me.rerere.hugeicons.stroke.ArrowLeft01\n", "import me.rerere.hugeicons.stroke.Add01\n")
    or die "anchor missing: hugeicons.Add01\n";

# 1) 返回键：Dialog 里不能用 BackButton（它会 popBackStack）。缩进跟随原文
$s =~ /^([ \t]*)navigationIcon = \{ BackButton\(\) \},$/m
    or die "navigationIcon = { BackButton() } not found\n";
my $ind = $1;
my $old = "${ind}navigationIcon = { BackButton() },\n";
my $btn =
"${ind}navigationIcon = {\n" .
"${ind}    FilledTonalIconButton(\n" .
"${ind}        onClick = onDismiss,\n" .
"${ind}        shapes = IconButtonDefaults.shapes(),\n" .
"${ind}        colors = IconButtonDefaults.filledTonalIconButtonColors(\n" .
"${ind}            containerColor = CustomColors.listItemColors.containerColor,\n" .
"${ind}        ),\n" .
"${ind}    ) {\n" .
"${ind}        Icon(\n" .
"${ind}            imageVector = HugeIcons.ArrowLeft01,\n" .
"${ind}            contentDescription = stringResource(R.string.back),\n" .
"${ind}        )\n" .
"${ind}    }\n" .
"${ind}},\n";
substr($s, index($s, $old), length($old)) = $btn;
$n++;

# 2) 结构锚点：MultiKeyDialog 内的 Scaffold( 行 与其后的 if (showTest) 行
my $fn = index($s, "fun MultiKeyDialog(");
die "MultiKeyDialog not found\n" if $fn < 0;
my $tail = substr($s, $fn);
$tail =~ /^([ \t]*)Scaffold\($/m or die "Scaffold( line not found\n";
my $a = $fn + $-[0];
$tail =~ /^[ \t]*if \(showTest\) \{$/m or die "if (showTest) not found\n";
my $ifpos = $fn + $-[0];

# 3) 取出 Scaffold 这一块（含它自己的收尾 }），大括号必须自平衡
my $region = substr($s, $a, $ifpos - $a);
$region =~ s/\s+\z//;
die "Scaffold block unbalanced (depth=" . depth($region) . ")\n" if depth($region) != 0;
$region =~ s/^(.+)$/        $1/mg;

# 4) 去掉紧贴 Scaffold 上方的旧注释（07 各版本文案不一），换成说明为什么必须套 Dialog
my $pre = substr($s, 0, $a);
1 while $pre =~ s/^[ \t]*\/\/[^\n]*(?:全屏页|Dialog|CardGroup|TopAppBar)[^\n]*\n\z//m;
my $note =
"    // 和「文件管理」「MCP」这些设置页同一骨架：LargeFlexibleTopAppBar + CardGroup。\n" .
"    // 外面必须包一层 Dialog —— 本组件由 ProviderConfigure 的 verticalScroll Column 内联调起，\n" .
"    // 直接铺 Scaffold 会拿到无限高度约束，里面的 LazyColumn 会抛 IllegalStateException。\n" .
"    // Dialog 子窗口自带有限尺寸，全屏观感由 usePlatformDefaultWidth=false 保证。\n";

my $head =
"    Dialog(\n" .
"        onDismissRequest = onDismiss,\n" .
"        // 状态栏交给里面的 Scaffold 处理（和设置页一致）\n" .
"        properties = DialogProperties(\n" .
"            usePlatformDefaultWidth = false,\n" .
"            decorFitsSystemWindows = false,\n" .
"        ),\n" .
"    ) {\n" .
"        Surface(\n" .
"            modifier = Modifier.fillMaxSize(),\n" .
"            color = CustomColors.topBarColors.containerColor,\n" .
"        ) {\n";

$s = $pre . $note . $head . $region . "\n        }\n    }\n\n" . substr($s, $ifpos);
$n++;

# 5) BackButton 不再使用则删 import
if ($s =~ s{^import me\.rerere\.rikkahub\.ui\.components\.nav\.BackButton\n}{}m) {
    die "BackButton still used\n" if $s =~ /BackButton\(/;
    $n++;
}

open(my $o, '>', $F) or die; print $o $s; close $o;
print "multikey dialog wrap edits=$n\n";
PERL
