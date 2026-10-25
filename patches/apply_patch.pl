#!/usr/bin/env perl
# 极简 unified diff 应用器：只支持本仓库补丁用到的形式（-u, 无 rename），
# 任何 hunk 上下文不匹配都会立刻报错。自动识别 CRLF/LF 并保留原换行风格。
use strict; use warnings;

my ($patch_file, $target) = @ARGV;
die "usage: apply_patch.pl <patch> <target>\n" unless $patch_file && $target;

open(my $pf, '<', $patch_file) or die "open patch: $!\n";
my @lines = <$pf>; close $pf;
chomp @lines;

open(my $tf, '<', $target) or die "open target: $!\n";
my @t = <$tf>; close $tf;
my $crlf = (@t > 0 && $t[0] =~ /\r\n$/) ? 1 : 0;
s/\r$// for @t;
chomp @t;

my @out;
my $ti = 0;          # 当前已消费的目标行号(0-based)
my $i = 0;
my $hunks = 0;

while ($i < @lines) {
    my $l = $lines[$i];
    if ($l =~ /^--- / || $l =~ /^\+\+\+ /) { $i++; next; }
    if ($l =~ /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/) {
        my $old_start = $1;
        my $old_count = defined $2 ? $2 : 1;
        $hunks++;
        my $src = $old_start - 1;      # 0-based
        while ($ti < $src) { push @out, $t[$ti]; $ti++; }
        $i++;
        my $consumed = 0;
        while ($i < @lines && $consumed < $old_count) {
            my $h = $lines[$i];
            $i++;
            if ($h =~ /^\\/) { next; }
            my $tag = substr($h, 0, 1);
            my $body = substr($h, 1);
            if ($tag eq ' ') {
                die "CTX MISMATCH at target line ".($ti+1)."\n  want: $body\n  got : ".($t[$ti] // '<EOF>')."\n"
                    unless ($t[$ti] // '') eq $body;
                push @out, $t[$ti]; $ti++; $consumed++;
            } elsif ($tag eq '-') {
                die "DEL MISMATCH at target line ".($ti+1)."\n  want: $body\n  got : ".($t[$ti] // '<EOF>')."\n"
                    unless ($t[$ti] // '') eq $body;
                $ti++; $consumed++;
            } elsif ($tag eq '+') {
                push @out, $body;
            } else {
                die "bad hunk line: '$h'\n";
            }
        }
        next;
    }
    $i++;
}

while ($ti < @t) { push @out, $t[$ti]; $ti++; }

open(my $of, '>', $target) or die "write: $!\n";
my $nl = $crlf ? "\r\n" : "\n";
print $of join($nl, @out), $nl;
close $of;
print "applied $hunks hunks -> $target (".scalar(@out)." lines)\n";
