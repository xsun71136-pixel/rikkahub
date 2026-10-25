my ($F, $tpl) = @ARGV;
open(my $in, "<", $F) or die; my @l = <$in>; close $in;
open(my $t, "<", $tpl) or die; my @t = <$t>; close $t;
my $start;
for my $i (0..$#l) { if ($l[$i] =~ m{^/\*\* 后台压缩的迷你进度胶囊}) { $start = $i; last } }
die "marker not found\n" unless defined $start;
die "must be near EOF\n" unless $start > $#l - 60;
my @out = (@l[0..$start-1], @t);
open(my $o, ">", $F) or die; print $o @out; close $o;
print "replaced from line ", $start+1, " -> total ", scalar(@out), "\n";
