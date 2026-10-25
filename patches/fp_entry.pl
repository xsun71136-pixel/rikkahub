#!/usr/bin/env perl
# 把 FilesPicker 的「压缩历史」从分段列表改成独立 CardGroup，
# 并拆成「主体点击 = 立即压缩 / 右侧设置 = 打开面板」两个落点。
# 每处替换都必须恰好匹配一次，否则立即失败（重复执行也会失败，这是有意的保护）。
use strict;
use warnings;

my $path = shift @ARGV or die "usage: fp_entry.pl <FilesPicker.kt>\n";
open(my $fh, '<', $path) or die "open $path: $!\n";
local $/ = undef;
my $s = <$fh>;
close $fh;

my $crlf = ($s =~ /\r\n/) ? 1 : 0;
$s =~ s/\r\n/\n/g;

my $n = 0;
sub rep {
    my ($old, $new, $tag) = @_;
    my $c = () = $s =~ /\Q$old\E/g;
    die "ANCHOR [$tag] matched $c times, expected 1\n" if $c != 1;
    $s =~ s/\Q$old\E/$new/;
    $n++;
}

# ---- 1. 补 import ----
rep(
    "import androidx.compose.animation.togetherWith\nimport androidx.compose.foundation.layout.Arrangement\n",
    "import androidx.compose.animation.togetherWith\n"
    . "import androidx.compose.foundation.clickable\n"
    . "import androidx.compose.foundation.layout.Arrangement\n",
    "import clickable",
);
rep(
    "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.graphics.vector.ImageVector\n",
    "import androidx.compose.ui.Modifier\n"
    . "import androidx.compose.ui.draw.clip\n"
    . "import androidx.compose.ui.graphics.vector.ImageVector\n",
    "import clip",
);
rep(
    "import me.rerere.rikkahub.ui.components.ui.ExtensionSelector\n",
    "import me.rerere.rikkahub.ui.components.ui.CardGroup\n"
    . "import me.rerere.rikkahub.ui.components.ui.CardGroupIcon\n"
    . "import me.rerere.rikkahub.ui.components.ui.ExtensionSelector\n",
    "import CardGroup",
);

# ---- 2. itemCount 不再统计压缩历史 ----
rep(
    "                val itemCount = 2 + listOf(showWorkspace, showWorkspace && showCwd, showMcp, showCompress).count { it }\n",
    "                // 搜索 / 工作区 / CWD / MCP / 扩展：压缩历史不在这个分段里，单独一张卡片\n"
    . "                val itemCount = 2 + listOf(showWorkspace, showWorkspace && showCwd, showMcp).count { it }\n",
    "itemCount",
);

my $old_tail = <<'OLD';
                        Text(stringResource(R.string.assistant_page_tab_extensions))
                    }

                    // Compress History Button
                    if (showCompress) {
                        SegmentedListItem(
                            onClick = { onShowCompressDialogChange(true) },
                            shapes = ListItemDefaults.segmentedShapes(index = itemIndex++, count = itemCount),
                            leadingContent = {
                                Icon(
                                    imageVector = HugeIcons.Package01,
                                    contentDescription = null,
                                )
                            },
                            trailingContent = {
                                val contextTokens = remember(conversation.messageNodes) {
                                    conversation.lastContextTokens()
                                }
                                // 和压缩对话框同一基准：自定义上下文窗口优先，其次模型窗口
                                val contextLength = settings.compressSetting.customContextLength.takeIf { it > 0 }
                                    ?: chatModel?.contextLength?.takeIf { it > 0 }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        if (conversation.messageNodes.isNotEmpty()) {
                                            Text(
                                                text = stringResource(R.string.chat_page_message_count, conversation.messageNodes.size),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (contextTokens != null && contextLength != null) {
                                            Text(
                                                text = "${contextTokens.formatNumber()} / ${contextLength.formatNumber()}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (contextTokens != null && contextLength != null) {
                                        ContextUsageRing(fraction = contextTokens.toFloat() / contextLength)
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(R.string.chat_page_compress_context))
                        }
                    }
                }
OLD

my $new_tail = <<'NEW';
                        Text(stringResource(R.string.assistant_page_tab_extensions))
                    }
                }

                // 压缩历史：独立一张卡片，不再混进上面的分段列表。
                // 点条目主体 = 按当前设置立即压缩；点右侧「设置」= 打开配置面板。
                if (showCompress) {
                    val contextTokens = remember(conversation.messageNodes) {
                        conversation.lastContextTokens()
                    }
                    val contextLength = (
                        settings.compressSetting.customContextLength.takeIf { it > 0 }
                            ?: chatModel?.contextLength?.takeIf { it > 0 }
                        )
                    CardGroup {
                        formItem(
                            headlineContent = { Text(stringResource(R.string.chat_page_compress_context)) },
                            supportingContent = {
                                Text(stringResource(R.string.chat_page_compress_now_desc))
                            },
                            leadingContent = { CardGroupIcon(HugeIcons.Package01) },
                            trailingContent = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (contextTokens != null && contextLength != null) {
                                        ContextUsageRing(
                                            fraction = contextTokens.toFloat() / contextLength
                                        )
                                    }
                                    // 设置入口：只负责打开配置面板，不会触发压缩
                                    Row(
                                        modifier = Modifier
                                            .clip(MaterialTheme.shapes.small)
                                            .clickable { onShowCompressDialogChange(true) }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.chat_page_compress_settings),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Icon(
                                            imageVector = HugeIcons.ArrowRight01,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            },
                        ) {
                            // 主体区域：整块可点，点一下直接压缩
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable {
                                        // 用当前设置直接压缩，压缩在后台执行，聊天页显示迷你进度条
                                        onCompressContext(
                                            settings.compressSetting.additionalPrompt,
                                            settings.compressSetting.keepRecentMessages,
                                            settings.compressSetting.modelId,
                                        )
                                        onDismiss()
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = if (contextTokens != null && contextLength != null) {
                                        "${contextTokens.formatNumber()} / ${contextLength.formatNumber()}"
                                    } else {
                                        stringResource(R.string.chat_page_message_count, conversation.messageNodes.size)
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = stringResource(R.string.chat_page_compress_now),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
NEW

rep($old_tail, $new_tail, "compress entry");

$s =~ s/\n/\r\n/g if $crlf;
open(my $out, '>', $path) or die "write $path: $!\n";
print $out $s;
close $out;
print "fp_entry.pl: $n replacement(s)\n";
