# MultiKeyConfig：在 MultiKeyDialog 函数体内插入两行
my $F = $ARGV[0];
open(my $in, "<", $F) or die; binmode($in, ":utf8"); local $/; my $s = <$in>; close $in;
my $m = 0;

my $pos = index($s, "fun MultiKeyDialog(");
die "MultiKeyDialog not found\n" if $pos < 0;
my $tail = substr($s, $pos);

my $a1 = "    val availableCount = keys.count { key -> keyStates[key]?.isPaused() != true }\n";
my $p1 = index($tail, $a1);
die "availableCount not found\n" if $p1 < 0;
substr($tail, $p1 + length($a1), 0) = "    val pausedCount = keys.size - availableCount\n";
$m++;

my $a2 = "    var showTest by remember { mutableStateOf(false) }\n";
my $p2 = index($tail, $a2);
die "showTest not found\n" if $p2 < 0;
substr($tail, $p2 + length($a2), 0) = "    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()\n";
$m++;

$s = substr($s, 0, $pos) . $tail;
open(my $o, ">", $F) or die; binmode($o, ":utf8"); print $o $s; close $o;
print "misc edits=$m\n";
