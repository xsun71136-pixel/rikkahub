#!/usr/bin/perl
# 轮 8 · 14/17：自动重试页去滑动
#
# 改前高度账（自定义档全开）≈ 1004dp；本机可用高度 ≈ 733dp，超出约 270dp，必须滑。
# 四处压缩：
#   1. 总开关并进「重试策略」的组标题行 —— 108dp 变 48dp，省 60dp。
#      置灰改成逐项挂在 item 上而不是整组，否则标题行里的开关会被一起压暗到看不清。
#   2. ParamCell 三行（标签/数值/范围提示）改单行（标签左、数值右）—— 每格 80dp 变 42dp，
#      2×2 网格 168dp 变 90dp，省 72dp。范围提示挪进编辑弹层，那里本来就显示了一遍。
#   3. 「概要」和「预期节奏」合成一行文本，删掉整块 FlowRow + SuggestionChip 预览；
#      抖动从独立 switchItem 变成标题行右侧的 FilterChip —— 合计省约 110dp。
#   4. 触发条件 + 停止条件合并成一组，省掉一个组标题和一份组间距 —— 省 46dp。
# 加上组间距 16->12、contentPadding 8->6（省 20dp），自定义档落到约 682dp，标准档约 470dp，都不用滑。
use strict; use warnings;

my $F = 'app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingAutoRetryPage.kt';
open(my $in, '<', $F) or die "open $F: $!";
my @L = <$in>; close $in;

sub cut {
    my ($from, $to, $new, $reFrom, $reTo) = @_;
    die "line $from mismatch: $L[$from-1]" unless $L[$from-1] =~ /$reFrom/;
    die "line $to mismatch: $L[$to-1]"     unless $L[$to-1]   =~ /$reTo/;
    splice(@L, $from - 1, $to - $from + 1, $new);
}

# ============ B) 412..462 ParamCell：三行改单行（编辑弹层原样保留） ============
my $cell = <<'KT';
/**
 * 参数小格：单行「标签 — 当前值 单位」，点击在弹层里编辑（确认才写入）。
 * 原来是三行（标签 / 数值 / 范围提示），每格约 80dp、2×2 就要 168dp；
 * 收成单行后每格约 42dp、整块 90dp。范围提示挪进编辑弹层——那里本来就显示了一遍。
 */
@Composable
private fun ParamCell(
    label: String,
    value: String,
    unit: String,
    rangeHint: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    decimal: Boolean = false,
) {
    var editing by remember { mutableStateOf(false) }
    Surface(
        onClick = { editing = true },
        // 单行小格用 large 圆角；extraLarge 在 42dp 的高度上会把两端吃成胶囊
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmallEmphasized,
                maxLines = 1,
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
KT
cut(412, 462, $cell, qr{^/\*\* 参数小格}, qr/^    \}\n$/);

# ============ A) 104..374 LazyColumn 整块：4 张卡 -> 2 张卡 ============
my $body = <<'KT';
        // 总开关关掉后下面的配置置灰且不可点，避免设了却不生效。
        // 置灰逐项挂在 item 上而不是整组：开关自己在组标题里，不能被一起压暗。
        val configEnabled = network.enableAutoRetry
        val configAlpha = if (configEnabled) 1f else 0.38f

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            // 组间距 16 -> 12：配合下面几处压缩，整页在常见机型上一屏放得下
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 重试策略：总开关做进组标题右侧，省掉单独一张卡片 ----
            item {
                val delays = remember(config) { expectedDelays(config) }
                val totalDelay = delays.fold(Duration.ZERO) { acc, d -> acc + d }
                CardGroup(
                    title = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.setting_auto_retry_strategy_title),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Switch(
                                checked = network.enableAutoRetry,
                                onCheckedChange = { updateEnabled(it) },
                            )
                        }
                    },
                ) {
                    // 档位单独一行，下面直接接参数格；「共几次、总等待多久」并进预期节奏那一行
                    formItem(modifier = Modifier.alpha(configAlpha)) {
                        ConnectedChoiceButtons(
                            options = listOf(false, true),
                            selected = config.custom,
                            onSelect = { custom ->
                                if (configEnabled) update { it.copy(custom = custom) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            minHeight = 40.dp,
                        ) { custom, selected ->
                            ConnectedChoiceLabel(
                                text = stringResource(
                                    if (custom) {
                                        R.string.setting_auto_retry_preset_custom
                                    } else {
                                        R.string.setting_auto_retry_preset_standard
                                    }
                                ),
                                selected = selected,
                            )
                        }
                    }
                    if (config.custom) {
                        // 4 个参数收成 2×2 的单行小格：标签在左、当前值在右，点开才在弹层里改
                        formItem(modifier = Modifier.alpha(configAlpha)) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ParamCell(
                                        label = stringResource(R.string.setting_auto_retry_max_retries),
                                        value = config.maxRetries.toString(),
                                        unit = stringResource(R.string.setting_auto_retry_unit_times),
                                        rangeHint = "0-10",
                                        modifier = Modifier.weight(1f),
                                        onCommit = { v ->
                                            v.toIntOrNull()?.let { n -> update { it.copy(maxRetries = n.coerceIn(0, 10)) } }
                                        },
                                    )
                                    ParamCell(
                                        label = stringResource(R.string.setting_auto_retry_first_delay),
                                        value = config.initialDelayMs.toString(),
                                        unit = "ms",
                                        rangeHint = "0-300000",
                                        modifier = Modifier.weight(1f),
                                        onCommit = { v ->
                                            v.toLongOrNull()?.let { n -> update { it.copy(initialDelayMs = n.coerceIn(0, 300_000)) } }
                                        },
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ParamCell(
                                        label = stringResource(R.string.setting_auto_retry_backoff_factor),
                                        value = config.backoffFactor.toString().removeSuffix(".0"),
                                        unit = "×",
                                        rangeHint = "1.0-10.0",
                                        decimal = true,
                                        modifier = Modifier.weight(1f),
                                        onCommit = { v ->
                                            v.toFloatOrNull()?.let { n -> update { it.copy(backoffFactor = n.coerceIn(1f, 10f)) } }
                                        },
                                    )
                                    ParamCell(
                                        label = stringResource(R.string.setting_auto_retry_max_delay),
                                        value = config.maxDelayMs.toString(),
                                        unit = "ms",
                                        rangeHint = "1000-600000",
                                        modifier = Modifier.weight(1f),
                                        onCommit = { v ->
                                            v.toLongOrNull()?.let { n -> update { it.copy(maxDelayMs = n.coerceIn(1000, 600_000)) } }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    // 预期节奏：原来是一整块 FlowRow + SuggestionChip 预览（近 110dp），
                    // 现在压成一行文本；抖动从独立 switchItem 变成标题行右侧的 FilterChip，再省一行
                    formItem(
                        modifier = Modifier.alpha(configAlpha),
                        headlineContent = { Text(stringResource(R.string.setting_auto_retry_expected)) },
                        trailingContent = {
                            if (config.custom) {
                                FilterChip(
                                    selected = config.jitter,
                                    onClick = { jitter -> update { it.copy(jitter = jitter) } },
                                    enabled = configEnabled,
                                    label = { Text(stringResource(R.string.setting_auto_retry_jitter)) },
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.setting_auto_retry_fixed_interval),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    ) {
                        Text(
                            text = if (delays.isEmpty()) {
                                stringResource(R.string.setting_auto_retry_seq_none)
                            } else {
                                stringResource(
                                    R.string.setting_auto_retry_rhythm_preview,
                                    delays.size,
                                    formatDelay(totalDelay),
                                    delays.joinToString(" → ") { formatDelay(it) },
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (config.custom && config.jitter) {
                            Text(
                                text = stringResource(R.string.setting_auto_retry_jitter_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // ---- 触发 / 停止条件：原来两张卡合成一张，省掉一个组标题和一份组间距 ----
            item {
                CardGroup(
                    modifier = Modifier.alpha(configAlpha),
                    title = { Text(stringResource(R.string.setting_auto_retry_rules_title)) },
                ) {
                    item(
                        onClick = if (configEnabled) ({ editingList = RetryListKind.CODES }) else null,
                        supportingContent = {
                            Text(
                                config.retryOnCodes.joinToString("  "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                CardGroupValue(config.retryOnCodes.size.toString())
                                Icon(HugeIcons.ArrowRight01, contentDescription = null)
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.setting_auto_retry_codes)) },
                    )
                    item(
                        onClick = if (configEnabled) ({ editingList = RetryListKind.KEYWORDS }) else null,
                        supportingContent = {
                            Text(
                                config.retryOnKeywords.joinToString("、"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                CardGroupValue(config.retryOnKeywords.size.toString())
                                Icon(HugeIcons.ArrowRight01, contentDescription = null)
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.setting_auto_retry_keywords)) },
                    )
                    switchItem(
                        checked = config.retryOnNetworkError,
                        onCheckedChange = { on -> update { it.copy(retryOnNetworkError = on) } },
                        enabled = configEnabled,
                        supportingContent = { Text(stringResource(R.string.setting_auto_retry_network_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_auto_retry_network)) },
                    )
                    // 停止条件并到同一组：条目名自己说明用途，说明行写清「命中即停」
                    item(
                        onClick = if (configEnabled) ({ editingList = RetryListKind.STOP }) else null,
                        supportingContent = {
                            Text(
                                if (config.stopKeywords.isEmpty()) {
                                    stringResource(R.string.setting_auto_retry_stop_hint)
                                } else {
                                    stringResource(
                                        R.string.setting_auto_retry_stop_preview,
                                        config.stopKeywords.joinToString("、"),
                                    )
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                CardGroupValue(config.stopKeywords.size.toString())
                                Icon(HugeIcons.ArrowRight01, contentDescription = null)
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.setting_auto_retry_stop)) },
                    )
                }
            }
        }
KT
cut(104, 374, $body, qr/^        LazyColumn\(\n$/, qr/^        \}\n$/);

# ============ imports ============
my $s = join('', @L);
my $ni = 0;
for my $pair (
    ["import androidx.compose.material3.FilledTonalButton\n",
     "import androidx.compose.material3.FilterChip\n"],
    ["import androidx.compose.material3.Surface\n",
     "import androidx.compose.material3.Switch\n"],
) {
    my ($anchor, $add) = @$pair;
    my $p = index($s, $anchor);
    die "import anchor not found: $anchor" if $p < 0;
    # 插在锚点之后：FilledTonalButton < FilterChip、Surface < Switch，字典序正好
    substr($s, $p + length($anchor), 0) = $add;
    $ni++;
}
open(my $o, '>', $F) or die "write $F: $!"; print $o $s; close $o;
my $lines = () = $s =~ /\n/g;
print "autoretry compact: cuts=2, import edits=$ni, lines=$lines\n";
