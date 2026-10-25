# 轮 6 步骤 7b/7：补 PaddingValues 的 plus 运算符 import
set -euo pipefail
perl - app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/MultiKeyConfig.kt <<'PERL'
my $F = $ARGV[0];
open(my $in, '<', $F) or die; local $/; my $s = <$in>; close $in;
die "already patched\n" if index($s, "import androidx.compose.foundation.layout.plus\n") >= 0;
my $anchor = "import androidx.compose.foundation.layout.padding\n";
die "anchor not found\n" if index($s, $anchor) < 0;
$s =~ s/\Q$anchor\E/$anchorimport androidx.compose.foundation.layout.plus\n/;
open(my $o, '>', $F) or die; print $o $s; close $o;
print "multikey plus import added\n";
PERL
