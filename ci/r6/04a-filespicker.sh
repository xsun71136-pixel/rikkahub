# 轮 6 步骤 4a/7：FilesPicker 压缩历史换成小卡片 + ContextUsageRing 支持尺寸
set -euo pipefail
perl - app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt <<'PERL'
my $F = $ARGV[0];
open(my $in, '<', $F) or die; local $/; my $s = <$in>; close $in;
my $n = 0;
sub rep { my ($o,$nw)=@_; die "not found: ".substr($o,0,80) unless index($s,$o)>=0; $s =~ s/\Q$o\E/$nw/; $n++; }

rep("import androidx.compose.animation.togetherWith\n", "import androidx.compose.animation.togetherWith\nimport androidx.compose.foundation.background\nimport androidx.compose.foundation.clickable\n");
rep("import androidx.compose.foundation.layout.Arrangement\n", "import androidx.compose.foundation.layout.Arrangement\nimport androidx.compose.foundation.layout.Box\n");
rep("import androidx.compose.foundation.layout.size\n", "import androidx.compose.foundation.layout.size\nimport androidx.compose.foundation.shape.CircleShape\n");
rep("import androidx.compose.material3.SegmentedListItem\n", "import androidx.compose.material3.SegmentedListItem\nimport androidx.compose.material3.Surface\n");
rep("import androidx.compose.ui.Modifier\n", "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.draw.clip\n");
rep("import androidx.compose.ui.unit.dp\n", "import androidx.compose.ui.unit.Dp\nimport androidx.compose.ui.unit.dp\n");

my @L = split /\n/, $s, -1;
my $cs = -1;
for my $i (0..$#L) { if ($L[$i] =~ /^\s*\/\/ Compress History Button\s*$/) { $cs = $i; last } }
die "compress comment not found\n" if $cs < 0;
my $ifline = -1;
for my $i ($cs..$#L) { if ($L[$i] =~ /^\s*if \(showCompress\) \{\s*$/) { $ifline = $i; last } }
die "if(showCompress) not found\n" if $ifline < 0;
my $ce = -1;
for my $i ($ifline+1..$#L) { if ($L[$i] =~ /^ {20}\}\s*$/) { $ce = $i; last } }
die "compress block end not found\n" if $ce < 0;

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
@L = (@L[0..$cs-1], @new, @L[$ce+1..$#L]);
my $t = join("\n", @L);
$n++;

$s = $t;
rep("private fun ContextUsageRing(fraction: Float) {\n", "private fun ContextUsageRing(\n    fraction: Float,\n    size: Dp = 24.dp,\n) {\n");
rep("        modifier = Modifier.size(24.dp),\n        color = color,\n        strokeWidth = 3.dp,\n", "        modifier = Modifier.size(size),\n        color = color,\n        // 环缩小时线宽按比例收，不然会显得整圈都是粗线\n        strokeWidth = if (size <= 20.dp) 2.5.dp else 3.dp,\n");

open(my $o, '>', $F) or die; print $o $s; close $o;
print "filespicker edits=$n\n";
PERL
