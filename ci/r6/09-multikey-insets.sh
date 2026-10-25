# 轮 7 步骤 9：多 Key 全屏弹层去掉 decorFitsSystemWindows，并给 LazyColumn 钉高度上限。
# 根因：Dialog 窗口高度是 WRAP_CONTENT；置 decorFitsSystemWindows=false 后不再做有限高度兜底，
# 部分 ROM 会把 UNSPECIFIED 直接传下去，LazyColumn 拿到 Infinity -> IllegalStateException。
# 这一步只是保险（真正的修法是 08 的 Dialog 包装），两层任一生效都不会崩。
set -euo pipefail
perl - app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/MultiKeyConfig.kt <<'PERL'
my $F = $ARGV[0];
open(my $in, '<', $F) or die "open $F: $!";
local $/; my $s = <$in>; close $in;
my $n = 0;

# 幂等：已经加过高度上限就直接跳过
if (index($s, "heightIn(max = LocalConfiguration") >= 0) {
    print "already capped, skip\n";
} else {
    # --- 1) DialogProperties 四行块 -> 单行（去掉 decorFitsSystemWindows） ---
    my $old_props =
"        properties = DialogProperties(\n" .
"            usePlatformDefaultWidth = false,\n" .
"            decorFitsSystemWindows = false,\n" .
"        ),\n";
    my $new_props = "        properties = DialogProperties(usePlatformDefaultWidth = false),\n";
    if (index($s, $old_props) >= 0) {
        $s =~ s/\Q$old_props\E/$new_props/;
        $n++;
    } elsif (index($s, "decorFitsSystemWindows") >= 0) {
        die "DialogProperties block not recognised\n";
    } else {
        print "props already clean\n";
    }

    # --- 2) 给多 Key 页的 LazyColumn 加高度上限 ---
    my $fn = index($s, "fun MultiKeyDialog(");
    die "MultiKeyDialog not found\n" if $fn < 0;
    my $tail = substr($s, $fn);
    $tail =~ /^([ \t]*)LazyColumn\($/m or die "LazyColumn line not found\n";
    my $ind = $1;
    my $mod_anchor =
        $ind . "LazyColumn(\n" .
        $ind . "    modifier = Modifier\n" .
        $ind . "        .fillMaxSize()\n";
    die "LazyColumn modifier block not found\n" if index($tail, $mod_anchor) < 0;
    my $mod_new =
        $ind . "LazyColumn(\n" .
        $ind . "    modifier = Modifier\n" .
        $ind . "        .heightIn(max = LocalConfiguration.current.screenHeightDp.dp)\n" .
        $ind . "        .fillMaxSize()\n";
    substr($tail, index($tail, $mod_anchor), length($mod_anchor)) = $mod_new;
    $s = substr($s, 0, $fn) . $tail;
    $n++;
}

# --- 3) 补 import（整段字典序重排） ---
my @lines = split /(?<=\n)/, $s;
my ($fst, $lst) = (-1, -1);
my @imp; my %seen;
for my $i (0..$#lines) {
    next unless $lines[$i] =~ /^import /;
    $fst = $i if $fst < 0;
    $lst = $i;
    next if $seen{ $lines[$i] }++;
    push @imp, $lines[$i];
}
die "no import section\n" if $fst < 0;

for my $w (
  "import androidx.compose.foundation.layout.heightIn\n",
  "import androidx.compose.ui.platform.LocalConfiguration\n",
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

open(my $o, '>', $F) or die "write $F: $!";
print $o $s; close $o;
print "multikey inset cap edits=$n\n";
PERL
