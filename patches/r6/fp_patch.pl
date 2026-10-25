my $F = $ARGV[0];
open(my $in, "<", $F) or die; local $/; my $s = <$in>; close $in;
my $n = 0;
sub rep { my ($old, $new) = @_; die "not found:\n".substr($old,0,200)."\n" unless index($s, $old) >= 0; $s =~ s/\Q$old\E/$new/; $n++; }

rep("import androidx.compose.animation.togetherWith\n", "import androidx.compose.animation.togetherWith\nimport androidx.compose.foundation.background\nimport androidx.compose.foundation.clickable\n");
rep("import androidx.compose.foundation.layout.Arrangement\n", "import androidx.compose.foundation.layout.Arrangement\nimport androidx.compose.foundation.layout.Box\n");
rep("import androidx.compose.foundation.layout.size\n", "import androidx.compose.foundation.layout.size\nimport androidx.compose.foundation.shape.CircleShape\n");
rep("import androidx.compose.material3.SegmentedListItem\n", "import androidx.compose.material3.SegmentedListItem\nimport androidx.compose.material3.Surface\n");
rep("import androidx.compose.ui.Modifier\n", "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.draw.clip\n");
rep("import androidx.compose.ui.unit.dp\n", "import androidx.compose.ui.unit.Dp\nimport androidx.compose.ui.unit.dp\n");

my @lines = split /\n/, $s, -1;
my ($cs, $ce);
for my $i (0..$#lines) {
  if (!defined $cs && $lines[$i] =~ m{^\s*// Compress History Button$}) { $cs = $i }
  if (!$ce && defined $cs && $lines[$i] =~ m{^\s*Text\(stringResource\(R\.string\.chat_page_compress_context\)\)$}) { $ce = $i + 2 }
}
die "compress block not found\n" unless defined $cs && defined $ce;
die "block end suspicious\n" unless $lines[$ce] =~ /^\s*$/ && $lines[$ce-1] =~ /^\s*\}$/;
my @new = (
'                    // 压缩历史：独立小卡片，形态和上面附件按钮同族，不再是一根列表长条',
'                    if (showCompress) {',
'                        CompressEntryCard(',
'                            conversation = conversation,',
'                            settings = settings,',
'                            chatModel = chatModel,',
'                            onCompress = {',
'                                onCompressContext(',
'                                    settings.compressSetting.additionalPrompt,',
'                                    settings.compressSetting.keepRecentMessages,',
'                                    settings.compressSetting.modelId,',
'                                )',
'                                onDismiss()',
'                            },',
'                            onOpenSettings = { onShowCompressDialogChange(true) },',
'                        )',
'                    }',
);
@lines = (@lines[0..$cs-1], @new, @lines[$ce+1..$#lines]);
$s = join("\n", @lines);
$n++;

rep("private fun ContextUsageRing(fraction: Float) {\n", "private fun ContextUsageRing(\n    fraction: Float,\n    size: Dp = 24.dp,\n) {\n");
rep("        modifier = Modifier.size(24.dp),\n        color = color,\n        strokeWidth = 3.dp,\n", "        modifier = Modifier.size(size),\n        color = color,\n        // 环缩小时线宽按比例收，不然会显得整圈都是粗线\n        strokeWidth = if (size <= 20.dp) 2.5.dp else 3.dp,\n");

open(my $out, ">", $F) or die; print $out $s; close $out;
print "edits=$n\n";
