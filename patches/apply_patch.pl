#!/usr/bin/env perl
# 极简 unified diff 应用器：只支持本仓库补丁用到的形式（-u 格式，无 rename）。
# 任何 hunk 的上下文/删除行与目标不匹配都会立刻退出非 0，避免静默写坏文件。
use strict; use warnings;

my ($patch_file, $target) = @ARGV;
die "usage: apply_patch.pl <patch> <target>\n" unless defined $patch_file && defined $target;

open(my $pf, '<', $patch_file) or die "open patch: $!\n";
my @lines = <$pf>; close $pf;
chomp @lines;

open(my $tf, '<', $target) or die "open target: $!\n";
my @t = <$tf>; close $tf;
chomp @t;

my @out;
my $ti = 0;
my $i = 0;
my $hunks = 0;

while ($i < @lines) {
    my $l = $lines[$i];
    if ($l =~ /^--- / || $l =~ /^\+\+\+ /) { $i++; next; }
    if ($l =~ /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/) {
        my $old_start = $1;
        my $old_count = defined $2 ? $2 : 1;
        $hunks++;
        my $src = $old_start - 1;
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
                die "CTX MISMATCH at line ".($ti+1)."\n  want: $body\n  got : ".($t[$ti] // '<EOF>')."\n"
                    unless ($t[$ti] // '') eq $body;
                push @out, $t[$ti]; $ti++; $consumed++;
            } elsif ($tag eq '-') {
                die "DEL MISMATCH at line ".($ti+1)."\n  want: $body\n  got : ".($t[$ti] // '<EOF>')."\n"
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
print $of join("\n", @out), "\n";
close $of;
print "applied $hunks hunks -> $target (".scalar(@out)." lines)\n";
