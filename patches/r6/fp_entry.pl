my ($F, $tpl) = @ARGV;
open(my $in, "<", $F) or die; local $/; my $s = <$in>; close $in;
open(my $t, "<", $tpl) or die; local $/; my $add = <$t>; close $t;
die "already patched\n" if index($s, "private fun CompressEntryCard") >= 0;
$s =~ s/\s*\z/\n/;
$s .= $add;
open(my $o, ">", $F) or die; print $o $s; close $o;
print "appended entry card\n";
