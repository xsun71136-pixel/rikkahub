# 轮 6 步骤 2b/7：launchCompress 加 auto 参数 + 发事件
set -euo pipefail
perl - app/src/main/java/me/rerere/rikkahub/service/ChatService.kt <<'PERL'
my $F = $ARGV[0];
open(my $in, '<', $F) or die; local $/; my $s = <$in>; close $in;
my $n = 0;
sub rep { my ($o,$nw)=@_; die "not found: ".substr($o,0,80) unless index($s,$o)>=0; $s =~ s/\Q$o\E/$nw/; $n++; }
rep("    fun launchCompress(\n        conversationId: Uuid,\n        additionalPrompt: String,\n        keepRecentMessages: Int = 0,\n        modelId: Uuid? = null,\n        onFailure: (Throwable) -> Unit,\n    ): Job {\n        compressionJobs.remove(conversationId)?.cancel()\n        val job = appScope.launch {\n            compressConversation(conversationId, additionalPrompt, keepRecentMessages, modelId)\n                .onFailure { if (it !is CancellationException) onFailure(it) }\n        }\n",
    "    fun launchCompress(\n        conversationId: Uuid,\n        additionalPrompt: String,\n        keepRecentMessages: Int = 0,\n        modelId: Uuid? = null,\n        // 自动压缩（到阈值触发）的提示文案与手动压缩不同\n        auto: Boolean = false,\n        onFailure: (Throwable) -> Unit,\n    ): Job {\n        compressionJobs.remove(conversationId)?.cancel()\n        val job = appScope.launch {\n            compressConversation(conversationId, additionalPrompt, keepRecentMessages, modelId)\n                .onSuccess { _compressEvents.tryEmit(CompressEvent.Success(conversationId, auto)) }\n                .onFailure {\n                    if (it !is CancellationException) {\n                        _compressEvents.tryEmit(CompressEvent.Failure(conversationId, auto, it))\n                        onFailure(it)\n                    }\n                }\n        }\n");
rep("            keepRecentMessages = config.keepRecentMessages,\n            modelId = config.modelId,\n        ) {\n",
    "            keepRecentMessages = config.keepRecentMessages,\n            modelId = config.modelId,\n            auto = true,\n        ) {\n");
open(my $o, '>', $F) or die; print $o $s; close $o;
print "service part2 edits=$n\n";
PERL
