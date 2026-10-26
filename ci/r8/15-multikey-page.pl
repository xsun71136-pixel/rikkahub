#!/usr/bin/perl
# 轮 8 · 15/17：多 Key 页 —— 全屏 Scaffold 改成半屏卡片，底部操作条回到 Column 骨架
#
# 【这一条就是「批量测试密钥不见了」的根因】
# 轮 6 的 ci/r6/07-multikey.sh 把骨架从
#     Dialog { Surface { Column { 顶栏; LazyColumn(weight 1f); Button(测试连接) } } }
# 换成
#     Dialog { Surface { Scaffold(topBar) { LazyColumn(fillMaxSize) } } }
# 删掉了外层 Column，尾部又把 3 个收口花括号删成 1 个。括号数量对得上、能编译，
# 但底部那颗 Button 从此变成 Scaffold body 槽里和 fillMaxSize 的 LazyColumn **平级**的孩子。
# Scaffold 的 body 槽会把多个孩子叠放在同一起点（左上），不是纵向排列 ——
# 于是「测试连接」被压到列表左上角，和「使用方式」卡片重叠，看着就像功能被删了。
#
# 修法不是再去猜谁的兄弟，而是回到 Column 骨架：顶栏 / 可滚内容 / 底部操作条，
# 中间 LazyColumn 用 weight(1f, fill = false) —— 既是正确的底栏语义，
# 又保证 LazyColumn 永远拿到有限高度（轮 6/7 那个 Infinity 崩溃的同源问题）。
#
# keysCollapsed / rulesCollapsed 默认都是 true（ai/.../ProviderSetting.kt:68-69），
# 所以默认打开就是一张约半屏卡片：顶栏 56 + 策略 ~110 + 两个折叠头 ~112 + 操作条 64 ≈ 342dp。
use strict; use warnings;

my $F = 'app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/MultiKeyConfig.kt';
open(my $in, '<', $F) or die "open $F: $!";
my @L = <$in>; close $in;

sub lineof {
    my ($re, $from) = @_;
    $from //= 0;
    for my $i ($from .. $#L) { return $i if $L[$i] =~ /$re/; }
    die "anchor not found: $re\n";
}

# 从 $start 行的 { 开始做花括号深度扫描，返回闭合那一行（0-based）
sub block_end {
    my ($start) = @_;
    my ($d, $seen) = (0, 0);
    for my $i ($start .. $#L) {
        my $t = $L[$i];
        $t =~ s{//.*$}{};
        $t =~ s{"(?:[^"\\]|\\.)*"}{""}g;
        my $o = () = $t =~ /\{/g;
        my $c = () = $t =~ /\}/g;
        $d += $o - $c;
        $seen = 1 if $o;
        return $i if $seen && $d == 0;
    }
    die "block end not found from line " . ($start + 1) . "\n";
}

# ---------- 1) 先把 LazyColumn 里那两个 item 块原样取出来（逻辑不动，只改缩进） ----------
my $fnDoc = lineof(qr{^fun MultiKeyDialog\($});
my $docStart = $fnDoc - 1;
$docStart-- while $docStart > 0 && $L[$docStart] !~ m{^/\*\*\n$};
die "doc start not found\n" unless $L[$docStart] =~ m{^/\*\*\n$};
my $fnEnd = lineof(qr{^\}\n$}, $fnDoc);

my $lzOpen = lineof(qr{^                \) \{\n$}, $docStart);      # LazyColumn 内容 lambda 开口
my $lzEnd  = block_end($lzOpen);
my $itemStart = lineof(qr{// ---- 卡片组 1}, $lzOpen);
die "items region invalid\n" unless $itemStart > $lzOpen && $lzEnd - 1 >= $itemStart;

my $frag = join('', @L[$itemStart .. $lzEnd - 1]);
# 原来这些 item 比 LazyColumn 多缩进了 12 格，新骨架里只该多 4 格
my $dedents = ($frag =~ s/^        //mg);
die "de-indent count unexpected: $dedents\n" unless $dedents > 100;
my $dedented = $frag;

# 密钥折叠头 trailing 里补「清空全部」入口（危险操作，走二次确认）
my $clearAnchor = <<'ANCHOR';
                                    if (!keysCollapsed && keyStates.values.any { it.isPaused() }) {
ANCHOR
my $clearBlock = <<'BLOCK';
                                    // 清空全部：删掉的是密钥原文、不可撤销，走二次确认
                                    if (!keysCollapsed && keys.isNotEmpty()) {
                                        IconButton(onClick = { showClearConfirm = true }) {
                                            Icon(
                                                HugeIcons.Delete01,
                                                contentDescription = stringResource(R.string.multi_key_clear_all),
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
BLOCK
# 插到「全部恢复」那个 if 块之后：先定位它的闭合行
{
    my $p = index($dedented, $clearAnchor);
    die "recover-all anchor not found\n" if $p < 0;
    die "recover-all anchor not unique\n" if index($dedented, $clearAnchor, $p + 1) >= 0;
    # 从锚点往后数花括号，找到这个 if 的闭合行
    my $rest = substr($dedented, $p);
    my ($d, $off, $done) = (0, 0, -1);
    for my $line (split /\n/, $rest, -1) {
        my $t = $line;
        $t =~ s{//.*$}{};
        $t =~ s{"(?:[^"\\]|\\.)*"}{""}g;
        $d += (() = $t =~ /\{/g) - (() = $t =~ /\}/g);
        $off += length($line) + 1;
        if ($d == 0 && $off > length((split /\n/, $rest)[0]) + 1) { $done = $off; last; }
    }
    die "recover-all if-block end not found\n" if $done < 0;
    substr($dedented, $p + $done, 0) = $clearBlock;
}

# KeyRow 补复制回调
my $delAnchor = "                                            onDelete = { editKeys(keys - key) },\n";
{
    my $p = index($dedented, $delAnchor);
    die "KeyRow onDelete anchor not found\n" if $p < 0;
    die "KeyRow onDelete anchor not unique\n" if index($dedented, $delAnchor, $p + 1) >= 0;
    substr($dedented, $p, 0) = "                                            onCopy = { copyKey(key) },\n";
}

# ---------- 2) 新的 MultiKeyDialog ----------
my $head = <<'KT';
/**
 * 多 Key 管理页：半屏卡片弹层，一页装下全部功能。
 *
 * 为什么不用全屏 Scaffold —— 轮 6 把骨架从 `Column { 顶栏; LazyColumn(weight 1f); 底部按钮 }`
 * 改成 `Scaffold { LazyColumn(fillMaxSize) }` 时，底部那颗「测试连接」按钮没搬进 bottomBar 槽，
 * 变成了 body 槽里和 LazyColumn 平级的孩子。Scaffold 的 body 槽会把多个孩子**叠放在同一起点**，
 * 按钮被压到列表左上角，看起来就像「批量测试功能没了」。
 * 这里回到 Column 骨架：顶栏 / 可滚内容 / 底部操作条，中间 `weight(1f, fill = false)`，
 * 既是正确的底栏语义，也保证 LazyColumn 永远拿到有限高度约束（轮 6/7 那个 Infinity 崩溃的同源问题）。
 *
 * `keysCollapsed` / `rulesCollapsed` 默认都是 true，所以默认打开就是一张约半屏的卡片：
 * 策略 + 两个折叠区 + 底部操作条；要看细节再展开，展开部分在卡片内部滚，不会顶成全屏。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiKeyDialog(
    provider: ProviderSetting.ApiKey,
    onEdit: (ProviderSetting) -> Unit,
    onDismiss: () -> Unit,
) {
    val config = provider.multiKey
    val keys = remember(provider.apiKey) { provider.splitApiKeys() }
    val allStates by MultiKeyStateCenter.states.collectAsStateWithLifecycle()
    val keyStates = allStates[provider.id.toString()].orEmpty()
    val availableCount = keys.count { key -> keyStates[key]?.isPaused() != true }
    var showTest by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun editKeys(newKeys: List<String>) {
        val distinct = distinctKeys(newKeys)
        onEdit(provider.withApiKey(distinct.joinToString("\n")))
        MultiKeyStateCenter.pruneKeys(provider.id.toString(), distinct)
    }

    fun copyKey(key: String) {
        context.writeClipboardText(key)
        // Android 13 起系统自己会弹复制提示，低版本补一个 Toast
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.multi_key_copied, Toast.LENGTH_SHORT).show()
        }
    }

    // 外层必须是 Dialog：本组件由 ProviderConfigure 的 verticalScroll Column 内联调起，
    // 直接铺开会让里面的 LazyColumn 拿到无限高度约束。Dialog 子窗口自带有限尺寸，
    // 宽度由 usePlatformDefaultWidth = false 放开，好让卡片铺到「屏宽 - 20dp」。
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // heightIn 先把上限钉死：Column 拿到有限 maxHeight，weight(1f, fill = false) 才算得出来
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.82f).dp
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .heightIn(max = maxHeight),
            shape = MaterialTheme.shapes.extraLarge,
            color = CustomColors.topBarColors.containerColor,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight),
            ) {
                // ---- 紧凑顶栏 56dp：返回 / 标题+摘要 / 完成（不再用 LargeFlexibleTopAppBar） ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 不能用 BackButton：它会 popBackStack，把底下的服务商详情页一起退掉
                    FilledTonalIconButton(
                        onClick = onDismiss,
                        shapes = IconButtonDefaults.shapes(),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = CustomColors.listItemColors.containerColor,
                        ),
                    ) {
                        Icon(
                            imageVector = HugeIcons.ArrowLeft01,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.setting_provider_page_multi_key),
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                R.string.setting_provider_page_multi_key_summary,
                                strategyLabel(config.strategy),
                                availableCount,
                                keys.size,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_done))
                    }
                }

                // ---- 可滚内容：内容少时卡片自己变矮，内容多时在卡片内部滚 ----
                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .imePadding(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
KT

my $tail = <<'KT';
                }

                // ---- 底部固定操作条：批量测试 / 批量导入（不随内容滚动，也不会被列表盖住） ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { showTest = true },
                        modifier = Modifier.weight(1f),
                        enabled = keys.isNotEmpty(),
                    ) {
                        Icon(HugeIcons.Zap, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            stringResource(R.string.multi_key_batch_test),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    FilledTonalButton(
                        onClick = { showImport = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            stringResource(R.string.multi_key_batch_import),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }

    if (showTest) {
        MultiKeyTestDialog(
            provider = provider,
            keys = keys,
            onRemoveKeys = { failed -> editKeys(keys - failed.toSet()) },
            onDismiss = { showTest = false },
        )
    }

    if (showImport) {
        MultiKeyImportSheet(
            existing = keys,
            onImport = { incoming, replace -> editKeys(if (replace) incoming else keys + incoming) },
            onDismiss = { showImport = false },
        )
    }

    // 清空全部：删掉的是密钥原文、不可撤销，必须二次确认
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.multi_key_clear_all_title)) },
            text = { Text(stringResource(R.string.multi_key_clear_all_message, keys.size)) },
            confirmButton = {
                TextButton(onClick = {
                    editKeys(emptyList())
                    showClearConfirm = false
                }) {
                    Text(
                        stringResource(R.string.multi_key_clear_all),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
KT

splice(@L, $docStart, $fnEnd - $docStart + 1, $head . $dedented . $tail);

# ---------- 3) imports ----------
my $s = join('', @L);
my $ni = 0;
sub dropimp {
    my ($line) = @_;
    my $p = index($s, $line);
    die "import to drop not found: $line" if $p < 0;
    substr($s, $p, length($line)) = '';
    $ni++;
}
sub addimp {
    my ($anchor, $add) = @_;
    my $p = index($s, $anchor);
    die "import anchor not found: $anchor" if $p < 0;
    substr($s, $p + length($anchor), 0) = $add;
    $ni++;
}
# 全屏骨架拆掉后这些就用不上了
dropimp("import androidx.compose.material3.LargeFlexibleTopAppBar\n");
dropimp("import androidx.compose.material3.Scaffold\n");
dropimp("import androidx.compose.material3.TopAppBarDefaults\n");
dropimp("import androidx.compose.ui.input.nestedscroll.nestedScroll\n");
dropimp("import androidx.compose.foundation.layout.fillMaxSize\n");
dropimp("import androidx.compose.foundation.layout.statusBarsPadding\n");
dropimp("import androidx.compose.foundation.layout.Spacer\n");
# 半屏卡片 + 复制 + 批量导入弹层需要的
addimp("package me.rerere.rikkahub.ui.pages.setting.components\n\n",
       "import android.os.Build\nimport android.widget.Toast\n");
addimp("import androidx.compose.material3.FilledTonalIconButton\n",
       "import androidx.compose.material3.FilledTonalButton\n");
addimp("import androidx.compose.runtime.rememberCoroutineScope\n",
       "import androidx.compose.runtime.saveable.rememberSaveable\n");
addimp("import androidx.compose.ui.platform.LocalConfiguration\n",
       "import androidx.compose.ui.platform.LocalContext\n");
addimp("import me.rerere.hugeicons.stroke.Cancel01\n",
       "import me.rerere.hugeicons.stroke.Copy01\n");
addimp("import me.rerere.rikkahub.ui.theme.extendColors\n",
       "import me.rerere.rikkahub.utils.writeClipboardText\n");

open(my $o, '>', $F) or die "write $F: $!"; print $o $s; close $o;
my $lines = () = $s =~ /\n/g;
print "multikey page: dialog replaced (items reused, de-indents=$dedents), import edits=$ni, lines=$lines\n";
