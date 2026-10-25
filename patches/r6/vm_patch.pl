my $F = $ARGV[0];
open(my $in, "<", $F) or die; local $/; my $s = <$in>; close $in;
my $n = 0;

my $imp_old = "import kotlinx.coroutines.flow.SharedFlow\n";
my $imp_new = "import kotlinx.coroutines.flow.Flow\n" . $imp_old;
die "flow import" unless index($s, $imp_old) >= 0;
$s =~ s/\Q$imp_old\E/$imp_new/; $n++;

my $f_old = "import kotlinx.coroutines.flow.StateFlow\n";
my $f_new = $f_old . "import kotlinx.coroutines.flow.filter\n";
die "filter import" unless index($s, $f_old) >= 0;
$s =~ s/\Q$f_old\E/$f_new/; $n++;

my $svc_old = "import me.rerere.rikkahub.service.ChatService\n";
my $svc_new = $svc_old . "import me.rerere.rikkahub.service.CompressEvent\n";
die "svc" unless index($s, $svc_old) >= 0;
$s =~ s/\Q$svc_old\E/$svc_new/; $n++;

my $body_old = "    val compressing: StateFlow<Boolean> =\n        chatService\n            .isCompressing(_conversationId)\n            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)\n";
my $body_new = $body_old . "\n    // 压缩结束事件（成功/失败），聊天页据此弹 toast；只转发本会话的\n    val compressEvents: Flow<CompressEvent> =\n        chatService.compressEvents.filter { it.conversationId == _conversationId }\n";
die "body" unless index($s, $body_old) >= 0;
$s =~ s/\Q$body_old\E/$body_new/; $n++;

open(my $out, ">", $F) or die; print $out $s; close $out;
print "edits=$n\n";
