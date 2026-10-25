my $F = $ARGV[0];
open(my $in, "<", $F) or die; local $/; my $s = <$in>; close $in;
my $m = 0;
sub rep2 { my ($o,$nw)=@_; die "not found(2):\n".substr($o,0,120) unless index($s,$o)>=0; $s =~ s/\Q$o\E/$nw/; $m++; }

rep2("/**\n * 多 Key 管理页：全屏弹层（普通对话框太窄，密钥行和选项会被挤压变形）。\n * 布局：使用方式并入密钥卡片顶部；「密钥」「暂停规则」两个区块可折叠、默认折叠，\n * 折叠状态写回 [MultiKeyConfig]，下次打开保持。\n */",
     "/**\n * 多 Key 管理页。和「文件管理」「MCP」这些设置页同一骨架：LargeFlexibleTopAppBar + CardGroup 分组。\n * 四组依次是：使用方式、密钥（含添加行）、暂停条件、测试入口。\n */");

rep2("    var showTest by remember { mutableStateOf(false) }\n",
     "    var showTest by remember { mutableStateOf(false) }\n    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()\n");

{
  my $body_start = index($s, "fun MultiKeyDialog(");
  die "MultiKeyDialog not found\n" if $body_start < 0;
  my $head = substr($s, 0, $body_start);
  my $tail = substr($s, $body_start);
  my $anchor = "    val availableCount = keys.count { key -> keyStates[key]?.isPaused() != true }\n";
  my $pos = index($tail, $anchor);
  die "anchor in dialog not found\n" if $pos < 0;
  substr($tail, $pos + length($anchor), 0) = "    val pausedCount = keys.size - availableCount\n";
  $s = $head . $tail;
  $m++;
}

rep2("// ---- 测试连接：和单 Key 的「测试连接」相同的三项检查，对每个密钥各跑一遍 ----\n\nprivate sealed interface KeyTestStatus {\n    data object Idle : KeyTestStatus\n    data object Running : KeyTestStatus\n    data class Ok(val detail: String) : KeyTestStatus\n    data class Warn(val detail: String) : KeyTestStatus\n    data class Fail(val detail: String) : KeyTestStatus\n}\n\n", "");

open(my $o2, ">", $F) or die; print $o2 $s; close $o2;
print "edits2=$m\n";
