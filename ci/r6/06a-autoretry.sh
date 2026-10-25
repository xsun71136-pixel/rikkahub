# 轮 6 步骤 6a/7：自动重试页 策略组重构（标题/档位包装/FlowRow）
set -euo pipefail
perl - app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingAutoRetryPage.kt <<'PERL'
my $F = $ARGV[0];
open(my $in, '<', $F) or die; my @L = <$in>; close $in;
my $n = 0;

my $cg = -1;
for my $i (0..$#L) { if ($L[$i] =~ /^                CardGroup\($/) { $cg = $i; last } }
die "strategy CardGroup not found\n" if $cg < 0;
my $cgend = -1;
for my $i ($cg..$#L) { if ($L[$i] =~ /^                \) \{$/) { $cgend = $i; last } }
die "strategy CardGroup end not found\n" if $cgend < 0;
my $sz = $cgend - $cg + 1;
die "unexpected title size $sz\n" unless $sz > 20 && $sz < 40;
splice @L, $cg, $sz,
  "                CardGroup(\n",
  "                    modifier = Modifier.alpha(configAlpha),\n",
  "                    title = { Text(stringResource(R.string.setting_auto_retry_strategy_title)) },\n",
  "                ) {\n";
$n++;

my $fi = -1;
for my $i (0..$#L) { if ($L[$i] =~ /^                    formItem \{$/) { $fi = $i; last } }
die "formItem not found\n" if $fi < 0;
my $ccb = -1;
for my $i ($fi..$#L) { if ($L[$i] =~ /^                        ConnectedChoiceButtons\($/) { $ccb = $i; last } }
die "ConnectedChoiceButtons not found\n" if $ccb < 0;
my $ccbend = -1;
for my $i ($ccb..$#L) { if ($L[$i] =~ /^                        \}\s*$/) { $ccbend = $i; last } }
die "choice body end not found\n" if $ccbend < 0;
splice @L, $fi + 1, 0, "                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {\n";
$ccb += 1; $ccbend += 1;
for my $i ($ccb..$ccbend) { $L[$i] = "    " . $L[$i] if $L[$i] =~ /\S/ }
splice @L, $ccbend + 1, 0,
  "                            // 概要放在档位下方：一眼知道「几次、一共等多久」，不用去数节奏格\n",
  "                            Text(\n",
  "                                text = if (delays.isEmpty()) {\n",
  "                                    stringResource(R.string.setting_auto_retry_seq_none)\n",
  "                                } else {\n",
  "                                    stringResource(\n",
  "                                        R.string.setting_auto_retry_seq_meta,\n",
  "                                        delays.size,\n",
  "                                        formatDelay(delays.fold(Duration.ZERO) { acc, d -> acc + d }),\n",
  "                                    )\n",
  "                                },\n",
  "                                style = MaterialTheme.typography.bodySmall,\n",
  "                                color = MaterialTheme.colorScheme.onSurfaceVariant,\n",
  "                            )\n",
  "                        }\n";
$n++;

open(my $o, '>', $F) or die; print $o @L; close $o;
print "autoretry a edits=$n, lines=", scalar(@L), "\n";
PERL
