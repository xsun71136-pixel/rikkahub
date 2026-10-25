# 用模板替换 ParamCell 里的 if (editing) { ... } 块
my ($F, $tpl) = @ARGV;
open(my $in, "<", $F) or die; my @L = <$in>; close $in;
open(my $t, "<", $tpl) or die; my @T = <$t>; close $t;

my ($s, $e);
for my $i (0..$#L) { if ($L[$i] =~ /^    if \(editing\) \{$/) { $s = $i; last } }
die "if(editing) not found\n" unless defined $s;
my $d = 0;
for my $i ($s..$#L) {
  $d += ($L[$i] =~ tr/{//) - ($L[$i] =~ tr/}//);
  if ($i > $s && $d == 0) { $e = $i; last }
}
die "block end not found\n" unless defined $e;
my $cnt = $e - $s + 1;
die "unexpected block size $cnt\n" unless $cnt > 20 && $cnt < 90;
splice @L, $s, $cnt, @T;
open(my $o, ">", $F) or die; print $o @L; close $o;
print "sheet replaced $cnt -> ", scalar(@T), " lines\n";
