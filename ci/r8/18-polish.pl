#!/usr/bin/perl
# 轮 8 · 18/18：收尾修正与打磨（链条最后一段，专门收拾前面几段留下的小问题）
#
# 0) 【编译错误修正】14-autoretry.pl 把「抖动」从 switchItem 改成 FilterChip 时，
#    照抄了开关的回调写法 onClick = { jitter -> ... }。但 FilterChip.onClick 是 () -> Unit，
#    不像 Switch.onCheckedChange 会回传新值，于是 compileGithubReleaseKotlin 报：
#      SettingAutoRetryPage.kt:231:47 Argument type mismatch:
#        actual type is '(??? (Unknown type for value parameter jitter)) -> Unit',
#        but '() -> Unit' was expected.
#    改成 onClick = { update { it.copy(jitter = !it.jitter) } }。
#    用 it.jitter 而不是 config.jitter：update 的回调拿到的是最新配置，
#    连点两下不会因为捕获到旧的组合态而互相覆盖（本页其它 update 调用也是这个规矩）。
#
# 1) 自动重试组标题行里的总开关 Switch 会贴到卡片右边缘。
#    CardGroup 的 title 是 Box(Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp))，
#    没有 end padding；组标题以前只放过 Text，无所谓，现在多了个 Switch 就露出来了。补 8dp。
#
# 2) ZIP 默认名原来用 String.format(Locale.getDefault(), template, arg)（伴生对象带 locale 的重载）。
#    仓库里一处先例都没有，而本轮不能在本地编译，没必要在这种地方赌；
#    换成接收者形式 template.format(arg) —— 仓库里 "%02x".format(it) 这类写法有 4 处先例。
#    %1$s 本身与 locale 无关，语义完全一致。
use strict; use warnings;

my $n = 0;
sub patch {
    my ($F, $old, $new, $tag) = @_;
    open(my $in, '<', $F) or die "open $F: $!";
    my $s = do { local $/; <$in> }; close $in;
    my $p = index($s, $old);
    die "anchor not found: $tag\n" if $p < 0;
    die "anchor not unique: $tag\n" if index($s, $old, $p + 1) >= 0;
    substr($s, $p, length($old)) = $new;
    open(my $o, '>', $F) or die "write $F: $!"; print $o $s; close $o;
    $n++;
}

my $AR  = 'app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingAutoRetryPage.kt';
my $WDP = 'app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceDetailPage.kt';

# ---- 0) FilterChip.onClick 是 () -> Unit，不吃参数 ----
patch($AR, <<'OLD', <<'NEW', 'filterchip onclick arity');
                                FilterChip(
                                    selected = config.jitter,
                                    onClick = { jitter -> update { it.copy(jitter = jitter) } },
OLD
                                FilterChip(
                                    selected = config.jitter,
                                    // FilterChip.onClick 是 () -> Unit，不像 Switch.onCheckedChange 会回传新值，
                                    // 所以自己取反；用 it.jitter 而不是 config.jitter，连点两下不会被旧的组合态覆盖
                                    onClick = { update { it.copy(jitter = !it.jitter) } },
NEW

# ---- 1) 组标题行给 Switch 留出右边距 ----
patch($AR, <<'OLD', <<'NEW', 'title row end padding');
                    title = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.setting_auto_retry_strategy_title),
OLD
                    title = {
                        Row(
                            // CardGroup 的 title 只有 start/top/bottom padding，没有 end；
                            // 标题行右侧现在放了总开关 Switch，不补边距它会贴到卡片右边缘
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.setting_auto_retry_strategy_title),
NEW

# ---- 2) ZIP 默认名改用仓库里有先例的接收者形式 ----
patch($WDP, <<'OLD', <<'NEW', 'zip name format');
        var zipName by remember(targets, zipNameTemplate) {
            mutableStateOf(
                String.format(
                    Locale.getDefault(),
                    zipNameTemplate,
                    SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date()),
                )
            )
        }
OLD
        val zipStamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
        var zipName by remember(targets, zipNameTemplate, zipStamp) {
            mutableStateOf(zipNameTemplate.format(zipStamp))
        }
NEW

print "polish edits=$n\n";
