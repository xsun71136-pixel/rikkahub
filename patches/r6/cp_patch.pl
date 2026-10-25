my $F = $ARGV[0];
open(my $in, "<", $F) or die; local $/; my $s = <$in>; close $in;
my $n = 0;
sub rep { my ($old, $new) = @_; die "not found:\n".substr($old,0,300)."\n" unless index($s, $old) >= 0; $s =~ s/\Q$old\E/$new/; $n++; }

rep("import androidx.compose.animation.expandVertically\nimport androidx.compose.animation.fadeIn\nimport androidx.compose.animation.fadeOut\nimport androidx.compose.animation.shrinkVertically\n",
    "import androidx.compose.animation.fadeIn\nimport androidx.compose.animation.fadeOut\nimport androidx.compose.animation.scaleIn\nimport androidx.compose.animation.scaleOut\n");

rep("import me.rerere.rikkahub.service.ChatError\n",
    "import me.rerere.rikkahub.service.ChatError\nimport me.rerere.rikkahub.service.CompressEvent\n");

rep("    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)\n",
"    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)\n\n    // 压缩结束提示：自动/手动都要有反馈，成功用 Success、失败用 Error\n    val compressSucc = stringResource(R.string.chat_page_compress_toast_success)\n    val compressSuccAuto = stringResource(R.string.chat_page_compress_toast_success_auto)\n    val compressFail = stringResource(R.string.chat_page_compress_toast_failure)\n    LaunchedEffect(vm) {\n        vm.compressEvents.collect { event ->\n            when (event) {\n                is CompressEvent.Success -> toaster.show(\n                    message = if (event.auto) compressSuccAuto else compressSucc,\n                    type = ToastType.Success,\n                )\n\n                is CompressEvent.Failure -> {\n                    val reason = event.throwable.message?.takeIf { it.isNotBlank() }\n                    toaster.show(\n                        message = if (reason != null) \"$compressFail：$reason\" else compressFail,\n                        type = ToastType.Error,\n                    )\n                }\n            }\n        }\n    }\n");

open(my $out, ">", $F) or die; print $out $s; close $out;
print "edits=$n\n";
