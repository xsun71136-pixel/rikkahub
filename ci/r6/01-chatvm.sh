# 轮 6 步骤 1/7：ChatVM 暴露 compressEvents
set -euo pipefail
perl - app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt <<'PERL'
my $F = $ARGV[0];
open(my $in, '<', $F) or die; local $/; my $s = <$in>; close $in;
my $n = 0;
sub rep { my ($o,$nw)=@_; die "not found: $o" unless index($s,$o)>=0; $s =~ s/\Q$o\E/$nw/; $n++; }
rep("import kotlinx.coroutines.flow.SharedFlow\n", "import kotlinx.coroutines.flow.Flow\nimport kotlinx.coroutines.flow.SharedFlow\n");
rep("import kotlinx.coroutines.flow.StateFlow\n", "import kotlinx.coroutines.flow.StateFlow\nimport kotlinx.coroutines.flow.filter\n");
rep("import me.rerere.rikkahub.service.ChatService\n", "import me.rerere.rikkahub.service.ChatService\nimport me.rerere.rikkahub.service.CompressEvent\n");
rep("    val compressing: StateFlow<Boolean> =\n        chatService\n            .isCompressing(_conversationId)\n            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)\n",
    "    val compressing: StateFlow<Boolean> =\n        chatService\n            .isCompressing(_conversationId)\n            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)\n\n    // 压缩结束事件（成功/失败），聊天页据此弹 toast；只转发本会话的\n    val compressEvents: Flow<CompressEvent> =\n        chatService.compressEvents.filter { it.conversationId == _conversationId }\n");
open(my $o, '>', $F) or die; print $o $s; close $o;
print "chatvm edits=$n\n";
PERL
