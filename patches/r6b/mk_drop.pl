my $F = $ARGV[0];
open(my $in, "<", $F) or die; my @L = <$in>; close $in;

sub drop_fn {
  my ($start_re, $name) = @_;
  my $s;
  for my $i (0..$#L) { if ($L[$i] =~ $start_re) { $s = $i; last } }
  die "start not found: $name\n" unless defined $s;
  my $fs;
  for my $i ($s..$#L) { if ($L[$i] =~ /^private fun \Q$name\E\(/) { $fs = $i; last } }
  die "fun not found: $name\n" unless defined $fs;
  my $d = 0; my $e;
  for my $i ($fs..$#L) {
    $d += ($L[$i] =~ tr/{//) - ($L[$i] =~ tr/}//);
    if ($d == 0 && $L[$i] =~ /^\}/) { $e = $i; last }
  }
  die "end not found: $name\n" unless defined $e;
  $e++ while defined $L[$e+1] && $L[$e+1] =~ /^\s*$/;
  my $c = $e - $s + 1;
  splice @L, $s, $c;
  print "dropped $name: $c lines\n";
}

drop_fn(qr{^/\*\* 可折叠分区标题}, "FoldSectionHeader");
drop_fn(qr{^/\*\* 密钥行：状态点}, "KeyRow");

open(my $o, ">", $F) or die; print $o @L; close $o;
print "lines=", scalar(@L), "\n";
