#!/usr/bin/env perl
# 极简 unified diff 应用器：只支持本仓库补丁用到的形式（-u, 无 rename）。
# 任何 hunk 的上下文/删除行与目标不匹配都会立刻退出非 0，避免静默写坏文件。
# 自动识别 CRLF/LF，并保持原文件的换行风格与结尾换行。
#
# v2 修复：diff 会把「在文件末尾追加」的 + 行排在最后一段上下文之后，
# 旧版在 old_count 消费完就退出会丢掉这些行；现在按「消费完且下一行不是 +」判断结束。
use strict;
use warnings;

my ($patch_file, $target) = @ARGV;
die "usage: apply_patch.pl <patch> <target>\n" unless defined $patch_file && defined $target;

sub slurp {
    my ($path) = @_;
    open(my $fh, '<', $path) or die "open $path: $!\n";
    local $/ = undef;
    my $text = <$fh>;
    close $fh;
    return defined $text ? $text : '';
}

my $patch_text = slurp($patch_file);
my $target_text = slurp($target);

my $crlf = ($target_text =~ /\r\n/) ? 1 : 0;
my $ends_with_nl = ($target_text =~ /\n$/) ? 1 : 0;

my @lines = split /\n/, $patch_text, -1;
$_ =~ s/\r$// for @lines;
pop @lines while @lines && $lines[-1] eq '';

my @t = split /\n/, $target_text, -1;
$_ =~ s/\r$// for @t;
pop @t while @t && $t[-1] eq '';

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
        while ($i < @lines) {
            my $h = $lines[$i];
            my $tag0 = substr($h, 0, 1);
            last if $consumed >= $old_count && $tag0 ne '+';
            $i++;
            next if $h =~ /^\\/;
            my $tag = substr($h, 0, 1);
            my $body = substr($h, 1);
            if ($tag eq ' ') {
                die "CTX MISMATCH at line " . ($ti + 1) . "\n  want: $body\n  got : " . ($t[$ti] // '<EOF>') . "\n"
                    unless ($t[$ti] // '') eq $body;
                push @out, $t[$ti]; $ti++; $consumed++;
            } elsif ($tag eq '-') {
                die "DEL MISMATCH at line " . ($ti + 1) . "\n  want: $body\n  got : " . ($t[$ti] // '<EOF>') . "\n"
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

my $nl = $crlf ? "\r\n" : "\n";
my $joined = join($nl, @out);
$joined .= $nl if $ends_with_nl;

open(my $of, '>', $target) or die "write $target: $!\n";
print $of $joined;
close $of;

print "applied $hunks hunks -> $target (" . scalar(@out) . " lines)\n";
