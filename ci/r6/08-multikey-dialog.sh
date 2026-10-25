# 轮 6 修复 8/8：把多 Key 页重新包回 Dialog 子窗口（并补齐 import）。
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
# 实现不依赖 07 的具体版本：
#   - 从 MultiKeyDialog 里第一个 `Scaffold(` 起按大括号配平找块尾，不数固定行数、不比注释文案；
#   - import 只做「缺则插、多余则删」，保留原有排列顺序。
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

    # 大括号配平找块尾
    my $rest = substr($s, $a);
    my @L = split /(?<=\n)/, $rest;
    my $d = 0; my $end = -1; my $len = 0;
    for my $i (0..$#L) {
        $len += length($L[$i]);
        $d += ($L[$i] =~ tr/{//);
        $d -= ($L[$i] =~ tr/}//);
        if ($i > 0 && $d == 0) { $end = $len; last; }
    }
    die "Scaffold block end not found\n" if $end < 0;

    my $pre = substr($s, 0, $a);
    my $blk = substr($s, $a, $end);
    $blk =~ s/\n\z//;
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

    $s = $pre . $note . $head . $blk . "\n        }\n    }\n" . substr($s, $a + $end);
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

# ---------- 3) import：删多余 / 补缺失（保持原有顺序） ----------
my @lines = split /(?<=\n)/, $s;

# 3a) BackButton 已不再使用则删掉它的 import
if ($s !~ /BackButton\(/) {
    for my $i (0..$#lines) {
        if ($lines[$i] eq "import me.rerere.rikkahub.ui.components.nav.BackButton\n") {
            $lines[$i] = "";
            $n++;
        }
    }
}

# 3b) 缺失的 import 插到「指定的邻近 import」之后；找不到就退回最后一个 import
my @WANT = (
  ["import androidx.compose.material3.FilledTonalIconButton\n", "import androidx.compose.material3.FilledTonalButton\n"],
  ["import androidx.compose.material3.IconButtonDefaults\n",     "import androidx.compose.material3.IconButton\n"],
  ["import androidx.compose.material3.Surface\n",               "import androidx.compose.material3.Scaffold\n"],
  ["import androidx.compose.ui.window.Dialog\n",                "import androidx.compose.ui.unit.dp\n"],
  ["import androidx.compose.ui.window.DialogProperties\n",      "import androidx.compose.ui.window.Dialog\n"],
  ["import me.rerere.hugeicons.stroke.ArrowLeft01\n",           "import me.rerere.hugeicons.stroke.Add01\n"],
);
for my $w (@WANT) {
    my ($line, $anchor) = @$w;
    next if grep { $_ eq $line } @lines;
    my $at = -1;
    for my $i (0..$#lines) { if ($lines[$i] eq $anchor) { $at = $i; last } }
    if ($at < 0) {
        for my $i (0..$#lines) { $at = $i if $lines[$i] =~ /^import / }
        die "no import section\n" if $at < 0;
    }
    splice @lines, $at + 1, 0, $line;
    $n++;
}

$s = join('', @lines);

open(my $o, '>', $F) or die; print $o $s; close $o;
print "multikey dialog wrap edits=$n\n";
PERL
