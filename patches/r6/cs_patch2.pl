my $F = $ARGV[0];
open(my $in, "<", $F) or die; local $/; my $s = <$in>; close $in;
my $n = 0;
sub rep { my ($old, $new) = @_; die "not found:\n$old" unless index($s, $old) >= 0; $s =~ s/\Q$old\E/$new/; $n++; }

rep(
"    fun launchCompress(\n        conversationId: Uuid,\n        additionalPrompt: String,\n        keepRecentMessages: Int = 0,\n        modelId: Uuid? = null,\n        onFailure: (Throwable) -> Unit,\n    ): Job {\n        compressionJobs.remove(conversationId)?.cancel()\n        val job = appScope.launch {\n            compressConversation(conversationId, additionalPrompt, keepRecentMessages, modelId)\n                .onFailure { if (it !is CancellationException) onFailure(it) }\n        }\n",
"    fun launchCompress(\n        conversationId: Uuid,\n        additionalPrompt: String,\n        keepRecentMessages: Int = 0,\n        modelId: Uuid? = null,\n        // 自动压缩（到阈值触发）的提示文案与手动压缩不同\n        auto: Boolean = false,\n        onFailure: (Throwable) -> Unit,\n    ): Job {\n        compressionJobs.remove(conversationId)?.cancel()\n        val job = appScope.launch {\n            compressConversation(conversationId, additionalPrompt, keepRecentMessages, modelId)\n                .onSuccess { _compressEvents.tryEmit(CompressEvent.Success(conversationId, auto)) }\n                .onFailure {\n                    if (it !is CancellationException) {\n                        _compressEvents.tryEmit(CompressEvent.Failure(conversationId, auto, it))\n                        onFailure(it)\n                    }\n                }\n        }\n"
);

rep(
"            keepRecentMessages = config.keepRecentMessages,\n            modelId = config.modelId,\n        ) {\n",
"            keepRecentMessages = config.keepRecentMessages,\n            modelId = config.modelId,\n            auto = true,\n        ) {\n"
);

open(my $out, ">", $F) or die; print $out $s; close $out;
print "edits=$n (part2)\n";
