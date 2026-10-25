# 轮 6 修复 8/8：把多 Key 页重新包回 Dialog 子窗口（并整理 import）。
#
# 崩溃原因：MultiKeyDialog 由 ProviderConfigure()（Column + verticalScroll）内联调起，
# 07 把它改成 Scaffold + LazyColumn 直接铺开后，LazyColumn 拿到的高度约束是 Infinity：
#   java.lang.IllegalStateException: Vertically scrollable component was measured with an
#   infinity maximum height constraints, which is disallowed.
# 修法：外层恢复 Dialog（子窗口自带有限尺寸约束）；usePlatformDefaultWidth=false +
# decorFitsSystemWindows=false 保持全屏观感，状态栏仍交给里面的 Scaffold 处理。
# 返回键改成 FilledTonalIconButton(onClick = onDismiss)，外观和 BackButton 一致，但不能用
# BackButton —— 它会 popBackStack，把底下的服务商详情页一起退掉。
#
# 块尾只用一条结构锚点：多 Key 页里紧跟 Scaffold 的 `if (showTest) {`。
# （不能用大括号配平 —— topBar = { ... }, 结束时就回到 0 了，会截断在顶栏处。）
set -euo pipefail
perl - app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/MultiKeyConfig.kt <<'PERL'
my $F = $ARGV[0];
open(my $in, '<', $F) or die; local $/; my $s = <$in>; close $in;
my $n = 0;

# ---------- 1) 把 Scaffold 整块包进 Dialog > Surface ----------
if (index($s, "properties = DialogProperties(\n") >= 0) {
    print "already wrapped, skip\n";
} else {
    my $fn = index($s, "fun MultiKeyDialog(");
    die "MultiKeyDialog not found\n" if $fn < 0;
    my $tail = substr($s, $fn);
    $tail =~ /^[ \t]*Scaffold\($/m or die "Scaffold( line not found\n";
    my $a = $fn + $-[0];
    $tail =~ /^[ \t]*if \(showTest\) \{$/m or die "if (showTest) not found\n";
    my $ifpos = $fn + $-[0];

    my $pre = substr($s, 0, $a);
    my $blk = substr($s, $a, $ifpos - $a);
    $blk =~ s/\s+\z//;
    $blk =~ s/^(.+)$/        $1/mg;

    # 去掉紧贴其上的旧注释（内容已不成立）
    1 while $pre =~ s/^[ \t]*\/\/[^\n]*\n\z//m;

    my $note =
"    // 和「文件管理」「MCP」这些设置页同一骨架：LargeFlexibleTopAppBar + CardGroup。\n" .
"    // 外面必须包一层 Dialog —— 本组件由 ProviderConfigure 的 verticalScroll Column 内联调起，\n" .
"    // 直接铺 Scaffold 会拿到无限高度约束，里面的 LazyColumn 会抛 IllegalStateException。\n" .
"    // Dialog 子窗口自带有限尺寸，全屏观感由 usePlatformDefaultWidth=false 保证。\n";
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

    $s = $pre . $note . $head . $blk . "\n        }\n    }\n\n" . substr($s, $ifpos);
    $n++;
}

# ---------- 2) 返回键：BackButton → FilledTonalIconButton(onClick = onDismiss) ----------
if ($s =~ /^([ \t]*)navigationIcon = \{ BackButton\(\) \},$/m) {
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
}

# ---------- 3) import：去掉不再用的 BackButton、补齐缺失项、整段按字典序重排 ----------
my @lines = split /(?<=\n)/, $s;
my ($fst, $lst) = (-1, -1);
my @imp; my %seen;
for my $i (0..$#lines) {
    next unless $lines[$i] =~ /^import /;
    $fst = $i if $fst < 0;
    $lst = $i;
    next if $seen{$lines[$i]}++;
    push @imp, $lines[$i];
}
die "no import section\n" if $fst < 0;

@imp = grep { $_ ne "import me.rerere.rikkahub.ui.components.nav.BackButton\n" } @imp
    if $s !~ /BackButton\(/;

for my $w (
  "import androidx.compose.material3.FilledTonalIconButton\n",
  "import androidx.compose.material3.IconButtonDefaults\n",
  "import androidx.compose.material3.Surface\n",
  "import androidx.compose.ui.window.Dialog\n",
  "import androidx.compose.ui.window.DialogProperties\n",
  "import me.rerere.hugeicons.stroke.ArrowLeft01\n",
) {
    push @imp, $w unless grep { $_ eq $w } @imp;
}

my @sorted = sort { lc($a) cmp lc($b) } @imp;
my @out;
for my $i (0..$#lines) {
    if ($i == $fst) { push @out, @sorted; next; }
    next if $i > $fst && $i <= $lst;
    push @out, $lines[$i];
}
$s = join('', @out);
$n++;

open(my $o, '>', $F) or die; print $o $s; close $o;
print "multikey dialog wrap edits=$n\n";
PERL
