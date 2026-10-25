# 轮 6 步骤 6b/7：自动重试页 节奏横滑 Row → FlowRow + SuggestionChip
set -euo pipefail
perl - app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingAutoRetryPage.kt <<'PERL'
my $F = $ARGV[0];
open(my $in, '<', $F) or die; my @L = <$in>; close $in;
my $rc = -1;
for my $i (0..$#L) { if ($L[$i] =~ /\/\/ 单行横滑展示节奏/) { $rc = $i; last } }
die "rhythm comment not found\n" if $rc < 0;
my $rs = $rc + 1;
die "Row not found\n" unless $L[$rs] =~ /Row\(/;
my $re = -1;
for my $i ($rs..$#L) { if ($L[$i] =~ /^                            \}\s*$/) { $re = $i; last } }
die "rhythm end not found\n" if $re < 0;
my @new = (
"                            FlowRow(\n",
"                                horizontalArrangement = Arrangement.spacedBy(4.dp),\n",
"                                verticalArrangement = Arrangement.spacedBy(4.dp),\n",
"                            ) {\n",
"                                delays.forEachIndexed { index, delay ->\n",
"                                    if (index > 0) {\n",
"                                        Icon(\n",
"                                            imageVector = HugeIcons.ArrowRight01,\n",
"                                            contentDescription = null,\n",
"                                            modifier = Modifier\n",
"                                                .align(Alignment.CenterVertically)\n",
"                                                .size(14.dp),\n",
"                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,\n",
"                                        )\n",
"                                    }\n",
"                                    SuggestionChip(\n",
"                                        onClick = {},\n",
"                                        enabled = false,\n",
"                                        label = {\n",
"                                            Text(\n",
"                                                text = formatDelay(delay),\n",
"                                                style = MaterialTheme.typography.labelMedium,\n",
"                                            )\n",
"                                        },\n",
"                                    )\n",
"                                }\n",
"                            }\n",
);
splice @L, $rc, $re - $rc + 1, @new;
open(my $o, '>', $F) or die; print $o @L; close $o;
print "autoretry b ok, lines=", scalar(@L), "\n";
PERL
