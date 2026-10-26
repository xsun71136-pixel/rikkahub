#!/usr/bin/perl
# 轮 8 · 12/17：压缩历史弹窗 —— 左右宽度重做
#
# 症结：AlertDialog 的内容宽度约等于「屏宽 - 96dp」（48 窗口边距 + 48 对话框内边距），
# 360dp 的机器上只剩 264dp 放滑杆和输入框，两边大片空白，看着就是「左右宽度不对」。
# 换成自己控制宽度的 Dialog 骨架后内容宽度是「屏宽 - 72dp」，和多 Key 半屏卡片同一套形态。
#
# 顺带修上下文窗口那一行的挤压：原来 ConnectedChoiceButtons(weight 1f) 和 96dp 的输入框挤一行，
# 选「自定义」时左边两档被压到一半宽，输入框出现/消失还会让整行跳动。改成两行、各自满宽。
#
# 高度链条：heightIn(max = 屏高*0.86) 先钉住上限 -> Column 拿到有限 maxHeight
#           -> weight(1f, fill = false) 才算得出来 -> 里面的 verticalScroll 不会遇到 Infinity。
use strict; use warnings;

my $F = 'app/src/main/java/me/rerere/rikkahub/ui/components/ai/CompressContextDialog.kt';
open(my $in, '<', $F) or die "open $F: $!";
my @L = <$in>; close $in;

# cut(from, to, new, reFrom, reTo)：1-based 闭区间，两端都要对得上锚点，否则直接 die。
# 必须自底向上调用，否则行号会被前面的 splice 冲掉。
sub cut {
    my ($from, $to, $new, $reFrom, $reTo) = @_;
    die "line $from mismatch: $L[$from-1]" unless $L[$from-1] =~ /$reFrom/;
    die "line $to mismatch: $L[$to-1]"     unless $L[$to-1]   =~ /$reTo/;
    splice(@L, $from - 1, $to - $from + 1, $new);
}

# ================= B) 359..375 收尾：内容 Column 收口 + 按钮行 + 三层收口 =================
my $tail = <<'KT';
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    // 确认后立即关闭：压缩在后台执行，聊天页输入框上方显示迷你进度条，可随时取消
                    TextButton(onClick = {
                        onConfirm(config.additionalPrompt, config.keepRecentMessages, compressModel?.id)
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.confirm))
                    }
                }
            }
        }
    }
KT
cut(359, 375, $tail, qr/^ {16}\}\n$/, qr/^ {4}\)\n$/);

# ================= C) 156..200 上下文窗口：一行挤压 -> 两行各自满宽 =================
my $ctx = <<'KT';
                        // 档位独占一行、满宽：不再和输入框挤一行，选「自定义」时按钮不会被压窄，整行也不跳
                        ConnectedChoiceButtons(
                            options = listOf(false, true),
                            selected = config.customContextLength > 0,
                            onSelect = { custom ->
                                if (custom) {
                                    val k = customKText.toIntOrNull()?.coerceIn(1, 2_000_000) ?: 128
                                    customKText = k.toString()
                                    onUpdateSetting(config.copy(customContextLength = k * 1000))
                                } else {
                                    onUpdateSetting(config.copy(customContextLength = 0))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            // 弹窗容器已经是 surfaceContainerHigh，未选中档要再深一档才分得开
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            minHeight = 40.dp,
                        ) { custom, selected ->
                            ConnectedChoiceLabel(
                                text = stringResource(
                                    if (custom) R.string.chat_page_compress_ctx_custom
                                    else R.string.chat_page_compress_ctx_follow
                                ),
                                selected = selected,
                            )
                        }
                        // K 数输入单独一行、满宽：96dp 的窄框换成整行，长数字也读得清
                        AnimatedVisibility(visible = config.customContextLength > 0) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedTextField(
                                    value = customKText,
                                    onValueChange = { raw ->
                                        val digits = raw.filter(Char::isDigit).take(7)
                                        customKText = digits
                                        // 只在是合法 K 数时写回；清空/非法时保留上次的值，档位不切走
                                        digits.toIntOrNull()?.coerceIn(1, 2_000_000)?.let { k ->
                                            onUpdateSetting(config.copy(customContextLength = k * 1000))
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodyLarge,
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.chat_page_compress_ctx_custom)) },
                                    suffix = { Text("K") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                )
                                Text(
                                    text = stringResource(R.string.chat_page_compress_ctx_custom_hint),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
KT
cut(156, 200, $ctx, qr/^ {24}Row\(\n$/, qr/^ {24}\}\n$/);

# ================= A) 101..112 弹窗外壳：AlertDialog -> 自控宽度的 Dialog =================
my $head = <<'KT';
    // AlertDialog 的内容宽度是「屏宽 - 96dp」，滑杆和输入框两边留白过大，看着就是「左右宽度不对」。
    // 换成自己控制宽度的 Dialog：窗口边距 16dp + 内容边距 20dp，内容宽度从 屏宽-96 变成 屏宽-72。
    // heightIn 先给出有限高度上限，里面的 weight(1f, fill = false) 才不会拿到 Infinity 约束。
    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp * 0.86f).dp
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(max = maxDialogHeight),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxDialogHeight),
            ) {
                Text(
                    text = stringResource(R.string.chat_page_compress_context_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                        .padding(horizontal = 20.dp),
                ) {
KT
cut(101, 112, $head, qr/^ {4}AlertDialog\(\n$/, qr/^ {12}\) \{\n$/);

# ================= imports =================
my $s = join('', @L);
my $ni = 0;
sub imp {
    my ($anchor, $add) = @_;
    my $p = index($s, $anchor);
    die "import anchor not found: $anchor" if $p < 0;
    substr($s, $p + length($anchor), 0) = $add;
    $ni++;
}
sub dropimp {
    my ($line) = @_;
    my $p = index($s, $line);
    die "import to drop not found: $line" if $p < 0;
    substr($s, $p, length($line)) = '';
    $ni++;
}
dropimp("import androidx.compose.material3.AlertDialog\n");
dropimp("import androidx.compose.foundation.layout.width\n");
imp("import androidx.compose.foundation.background\n",
    "import androidx.compose.animation.AnimatedVisibility\n");
imp("import androidx.compose.foundation.shape.CircleShape\n",
    "import androidx.compose.foundation.text.KeyboardOptions\n");
imp("import androidx.compose.foundation.layout.fillMaxWidth\n",
    "import androidx.compose.foundation.layout.heightIn\n");
imp("import androidx.compose.material3.Slider\n",
    "import androidx.compose.material3.Surface\n");
imp("import androidx.compose.ui.Modifier\n",
    "import androidx.compose.ui.platform.LocalConfiguration\n");
imp("import androidx.compose.ui.res.stringResource\n",
    "import androidx.compose.ui.text.input.KeyboardType\n");
imp("import androidx.compose.ui.unit.dp\n",
    "import androidx.compose.ui.window.Dialog\nimport androidx.compose.ui.window.DialogProperties\n");

# 不重排 import：CI 只跑 :ai:test 和 assembleGithubRelease，不跑 spotlessCheck，
# 顺序对构建没有影响；原地插入能让产物和本地验证过的目标逐字节一致，少一类风险。
my $out = $s;

open(my $o, '>', $F) or die "write $F: $!"; print $o $out; close $o;
my $lines = () = $out =~ /\n/g;
print "compress dialog: cuts=3, import edits=$ni, lines=$lines\n";
