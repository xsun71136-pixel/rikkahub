# 轮 6 步骤 7b/7：补 PaddingValues 的 plus 运算符 import（插在 layout 包首个 import 之前）
set -euo pipefail
perl - app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/MultiKeyConfig.kt <<'PERL'
my $F = $ARGV[0];
open(my $in, '<', $F) or die; local $/; my $s = <$in>; close $in;
die "already patched\n" if index($s, "foundation.layout.plus") >= 0;

# 找到 “import androidx.compose.foundation.layout.” 第一次出现的位置，插在它前面
my $pos = index($s, "import androidx.compose.foundation.layout.");
die "layout import anchor not found\n" if $pos < 0;
substr($s, $pos, 0) = "import androidx.compose.foundation.layout.plus\n";

open(my $o, '>', $F) or die; print $o $s; close $o;
print "plus import inserted at byte $pos\n";
PERL
