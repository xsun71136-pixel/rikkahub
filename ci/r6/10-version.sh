# 轮 7 步骤 10：版本号打标，便于一眼区分新旧包（3.1.1-r7 / 195）。
# 起因：多轮反馈里很难确认机器上装的到底是哪一次构建的包，用版本号直接自证。
set -euo pipefail
perl - app/build.gradle.kts <<'PERL'
my $F = $ARGV[0];
open(my $in, '<', $F) or die "open $F: $!";
local $/; my $s = <$in>; close $in;
my $n = 0;

if (index($s, '3.1.1-r7') >= 0) {
    print "version already bumped, skip\n";
} else {
    die "versionCode anchor missing\n" unless $s =~ /(\n        versionCode = )194(\n)/;
    $s =~ s/(\n        versionCode = )194(\n)/${1}195${2}/;
    $n++;

    die "versionName anchor missing\n" unless $s =~ /(\n        versionName = ")3\.1\.1(")/;
    $s =~ s/(\n        versionName = ")3\.1\.1(")/${1}3.1.1-r7${2}/;
    $n++;
}

open(my $o, '>', $F) or die "write $F: $!";
print $o $s; close $o;
print "version bump edits=$n\n";
PERL
