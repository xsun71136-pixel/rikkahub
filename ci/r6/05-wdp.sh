# 轮 6 步骤 5/7：工作区详情页 tab 对调（文件在前）
set -euo pipefail
perl - app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceDetailPage.kt <<'PERL'
my $F = $ARGV[0];
open(my $in, '<', $F) or die; local $/; my $s = <$in>; close $in;
my $old = "    BASIC(HugeIcons.Settings03, R.string.workspace_detail_tab_basic),\n    FILES(HugeIcons.File02, R.string.workspace_detail_tab_files);";
my $new = "    // 文件在前：进工作区绠大多数时候是看文件，基本设置用得少\n    FILES(HugeIcons.File02, R.string.workspace_detail_tab_files),\n    BASIC(HugeIcons.Settings03, R.string.workspace_detail_tab_basic);";
die "tab decl not found\n" unless index($s, $old) >= 0;
$s =~ s/\Q$old\E/$new/;
open(my $o, '>', $F) or die; print $o $s; close $o;
print "wdp ok\n";
PERL
