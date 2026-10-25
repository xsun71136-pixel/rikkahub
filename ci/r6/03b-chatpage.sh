# 轮 6 步骤 3b/7：ChatPage 迷你胶囊：居中自适应宽度
set -euo pipefail
perl - app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt <<'PERL'
my $F = $ARGV[0];
open(my $in, '<', $F) or die; my @l = <$in>; close $in;
my $start;
for my $i (0..$#l) { if ($l[$i] =~ m{^/\*\* 后台压缩的迷你进度胶囊}) { $start = $i; last } }
die "marker not found\n" unless defined $start;
die "must be near EOF\n" unless $start > $#l - 60;
my @new = split /^/, <<'KT';
/** 后台压缩的迷你进度胶囊：一行高、宽度随内容居中，只占输入框上方一点点，可取消 */
@Composable
private fun CompressMiniBanner(
    visible: Boolean,
    onCancel: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(initialScale = 0.92f) + fadeIn(),
        exit = scaleOut(targetScale = 0.92f) + fadeOut(),
    ) {
        // 已耗时秒数：显示期间每秒刷新
        var elapsedSeconds by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            elapsedSeconds = 0
            while (true) {
                delay(1_000)
                elapsedSeconds++
            }
        }
        // 居中而不是铺满：压缩是次要状态，不该抢输入框的注意力
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(11.dp),
                        strokeWidth = 1.5.dp,
                    )
                    Text(
                        text = stringResource(R.string.chat_page_compressing_banner) + " · ${elapsedSeconds}s",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.chat_page_compress_cancel),
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
    }
}
KT
my @out = (@l[0..$start-1], @new);
open(my $o, '>', $F) or die; print $o @out; close $o;
print "chatpage mini replaced -> ", scalar(@out), " lines\n";
PERL
