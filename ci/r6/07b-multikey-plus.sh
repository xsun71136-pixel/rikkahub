# 轮 6 步骤 7b：多 Key 页收尾。
# 1) 补 PaddingValues 的 plus 运算符 import（07 生成的 contentPadding = innerPadding + ... 要用）；
# 2) 调 8：把页面重新包回 Dialog —— 否则从 ProviderConfigure 的滚动 Column 内联调起时
#    里面的 LazyColumn 会拿到无限高度约束而崩溃（详见 08 脚本头部注释）。
# 3) 调 9：去掉 decorFitsSystemWindows（它会取消 Dialog 窗口的有限高度兜底）+ 给 LazyColumn 钉高度上限。
# 4) 调 10：版本号打标 3.1.1-r7 / 195，方便确认机器上装的是哪次构建。
set -euo pipefail
perl - app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/MultiKeyConfig.kt <<'PERL'
my $F = $ARGV[0];
open(my $in, '<', $F) or die; local $/; my $s = <$in>; close $in;
die "already patched\n" if index($s, "foundation.layout.plus") >= 0;
# 插在 layout 分区第一行 import 之前，保证分组整齐
my $pos = index($s, "import androidx.compose.foundation.layout.");
die "layout import anchor not found\n" if $pos < 0;
substr($s, $pos, 0) = "import androidx.compose.foundation.layout.plus\n";
open(my $o, '>', $F) or die; print $o $s; close $o;
print "plus import inserted at byte $pos\n";
PERL

bash ci/r6/08-multikey-dialog.sh
bash ci/r6/09-multikey-insets.sh
bash ci/r6/10-version.sh
