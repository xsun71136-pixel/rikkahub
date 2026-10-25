my $F = $ARGV[0];
open(my $in, "<", $F) or die; local $/; my $s = <$in>; close $in;
my $n = 0;
sub rep { my ($old, $new) = @_; die "not found:\n$old" unless index($s, $old) >= 0; $s =~ s/\Q$old\E/$new/; $n++; }

rep(
"enum class ChatErrorSolution {\n    CheckFastModelSettings,\n}\n",
"enum class ChatErrorSolution {\n    CheckFastModelSettings,\n}\n\n/**\n * 压缩结束事件：聊天页据此弹 toast。\n * [auto] 区分自动压缩（到阈值自己压）和手动压缩（点入口/对话框），提示文案不同。\n */\nsealed interface CompressEvent {\n    val conversationId: Uuid\n    val auto: Boolean\n\n    data class Success(\n        override val conversationId: Uuid,\n        override val auto: Boolean,\n    ) : CompressEvent\n\n    data class Failure(\n        override val conversationId: Uuid,\n        override val auto: Boolean,\n        val throwable: Throwable,\n    ) : CompressEvent\n}\n"
);

rep(
"    fun isCompressing(conversationId: Uuid): Flow<Boolean> =\n        compressingIds.map { conversationId in it }.distinctUntilChanged()\n",
"    fun isCompressing(conversationId: Uuid): Flow<Boolean> =\n        compressingIds.map { conversationId in it }.distinctUntilChanged()\n\n    // 压缩结束事件（成功/失败都发），聊天页收集后弹 toast；只给在线订阅者；replay=0\n    private val _compressEvents = MutableSharedFlow<CompressEvent>(extraBufferCapacity = 8)\n    val compressEvents: SharedFlow<CompressEvent> = _compressEvents.asSharedFlow()\n"
);

open(my $out, ">", $F) or die; print $out $s; close $out;
print "edits=$n (part1)\n";
