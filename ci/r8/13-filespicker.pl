#!/usr/bin/perl
# 轮 8 · 13/17：压缩历史入口卡片 —— 右侧收窄，把宽度还给中间的标题/说明
#
# 原来一行里塞了「40dp 圆图标 | 弹性文本 | 12.3K/128K + 用量环 | 设置 ›」，
# 右侧那块吃掉近 140dp，中间列被压得只能出省略号。
# 改法：右侧数字从 "12.3K/128K"（约 78dp）缩成 "38%"（约 34dp），K 数挪到说明行；
#       信息一条没丢，中间弹性列多出约 44dp。
use strict; use warnings;

my $F = 'app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt';
open(my $in, '<', $F) or die "open $F: $!";
my $s = do { local $/; <$in> }; close $in;
my $n = 0;

sub rep {
    my ($old, $new, $tag) = @_;
    my $p = index($s, $old);
    die "anchor not found: $tag\n" if $p < 0;
    die "anchor not unique: $tag\n" if index($s, $old, $p + 1) >= 0;
    substr($s, $p, length($old)) = $new;
    $n++;
}

# ---- 1) 卡片内边距 / 列间距 / 图标尺寸收一档 ----
rep(<<'OLD', <<'NEW', 'card row metrics');
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
OLD
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
NEW

# ---- 2) 说明行：K 数从右侧挪过来，信息不丢 ----
rep(<<'OLD', <<'NEW', 'supporting text');
                Text(
                    text = if (conversation.messageNodes.isNotEmpty()) {
                        stringResource(
                            R.string.chat_page_compress_entry_desc,
                            conversation.messageNodes.size,
                        )
                    } else {
                        stringResource(R.string.chat_page_compress_now_desc)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
OLD
                Text(
                    // K 数从右侧挪到这一行：右侧只留「环 + 百分比」，中间标题列才不会被挤成省略号
                    text = when {
                        contextTokens != null && contextLength != null &&
                            conversation.messageNodes.isNotEmpty() ->
                            stringResource(
                                R.string.chat_page_compress_entry_usage,
                                contextTokens.formatNumber(),
                                contextLength.formatNumber(),
                                conversation.messageNodes.size,
                            )

                        conversation.messageNodes.isNotEmpty() -> stringResource(
                            R.string.chat_page_compress_entry_desc,
                            conversation.messageNodes.size,
                        )

                        else -> stringResource(R.string.chat_page_compress_now_desc)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
NEW

# ---- 3) 右侧用量块：K 数 -> 百分比；「设置 ›」内边距收紧 ----
rep(<<'OLD', <<'NEW', 'usage block');
            // 用量：K 数 + 环（接近上限变色）
            if (contextTokens != null && contextLength != null && fraction != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "${contextTokens.formatNumber()}/${contextLength.formatNumber()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ContextUsageRing(fraction = fraction, size = 20.dp)
                }
            }
            // 设置入口：自己吃掉点击，不触发压缩
            Row(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.large)
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
OLD
            // 用量：只留「环 + 百分比」。原来这里塞着「12.3K/128K」，约 78dp 宽，
            // 把中间的标题/说明挤成省略号；换成百分比后右侧约 34dp，省下的宽度全给中间弹性列。
            if (fraction != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    ContextUsageRing(fraction = fraction, size = 20.dp)
                    Text(
                        text = stringResource(
                            R.string.chat_page_compress_percent,
                            (fraction * 100).roundToInt(),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            // 设置入口：自己吃掉点击，不触发压缩
            Row(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.large)
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
NEW

# ---- 4) import ----
rep(<<'OLD', <<'NEW', 'roundToInt import');
import kotlin.uuid.Uuid
OLD
import kotlin.math.roundToInt
import kotlin.uuid.Uuid
NEW

open(my $o, '>', $F) or die "write $F: $!"; print $o $s; close $o;
my $lines = () = $s =~ /\n/g;
print "filespicker compress entry edits=$n, lines=$lines\n";
