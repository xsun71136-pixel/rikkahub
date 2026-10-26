#!/usr/bin/perl
# 轮 8 · 17/17：版本号打标 195/3.1.1-r7 -> 196/3.1.1-r8
#
# 打标是为了让用户能自证装的是哪个包：设置 → 关于，或系统「应用信息」里一眼看到 3.1.1-r8。
# 前几轮反复出现「本地/云端不一致、装没装上」的沟通成本，靠版本号一次消掉。
use strict; use warnings;

my $F = 'app/build.gradle.kts';
open(my $in, '<', $F) or die "open $F: $!";
my $s = do { local $/; <$in> }; close $in;

if (index($s, '3.1.1-r8') >= 0) {
    print "version already bumped, skip\n";
    exit 0;
}

my $n = 0;
$n++ if $s =~ s/(\n        versionCode = )195(\n)/${1}196${2}/;
$n++ if $s =~ s/(\n        versionName = ")3\.1\.1-r7(")/${1}3.1.1-r8${2}/;
die "version bump edits=$n, expected 2\n" unless $n == 2;

open(my $o, '>', $F) or die "write $F: $!"; print $o $s; close $o;
print "version bump edits=$n\n";
