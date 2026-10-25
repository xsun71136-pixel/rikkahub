my $F = $ARGV[0];
open(my $in, "<", $F) or die; my @L = <$in>; close $in;
my $n = 0;
sub rep {
  my ($old, $new) = @_;
  my $s = join("", @L);
  die "block not found:\n".substr($old,0,200) unless index($s, $old) >= 0;
  $s =~ s/\Q$old\E/$new/;
  @L = split /(?<=\n)/, $s;
  $n++;
}

rep("import androidx.compose.animation.core.animateFloatAsState\n", "");
rep("import androidx.compose.foundation.layout.Spacer\n", "");
rep("import androidx.compose.foundation.layout.statusBarsPadding\n", "");
rep("import androidx.compose.material3.Button\n", "");
rep("import androidx.compose.ui.draw.rotate\n", "");
rep("import me.rerere.ai.util.MultiKeyState\n", "");
rep("import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape\n", "");
rep("import androidx.compose.ui.window.DialogProperties\n", "");
rep("import me.rerere.hugeicons.stroke.ArrowLeft01\n", "");
rep("import me.rerere.hugeicons.stroke.Refresh01\n", "");

rep("import androidx.compose.ui.window.Dialog\n", "");
rep("import androidx.compose.material3.Surface\n", "");

rep("import androidx.compose.material3.FilterChip\n", "import androidx.compose.material3.FilterChip\nimport androidx.compose.material3.FilledTonalButton\n");
rep("import androidx.compose.material3.ListItem\n", "import androidx.compose.material3.LargeFlexibleTopAppBar\nimport androidx.compose.material3.ListItem\n");
rep("import androidx.compose.material3.ModalBottomSheet\n", "import androidx.compose.material3.ModalBottomSheet\nimport androidx.compose.material3.Scaffold\n");
rep("import androidx.compose.material3.Text\n", "import androidx.compose.material3.Text\nimport androidx.compose.material3.TextButton\nimport androidx.compose.material3.TopAppBarDefaults\n");
rep("import androidx.compose.ui.input.nestedscroll.nestedScroll\n", "");
rep("import androidx.compose.ui.graphics.Color\n", "import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.input.nestedscroll.nestedScroll\n");
rep("import me.rerere.rikkahub.ui.components.ui.CardGroup\n", "import me.rerere.rikkahub.ui.components.ui.CardGroup\nimport me.rerere.rikkahub.ui.components.ui.CardGroupIcon\n");
rep("import me.rerere.rikkahub.ui.components.ai.ModelField\n", "import me.rerere.rikkahub.ui.components.ai.ModelField\nimport me.rerere.rikkahub.ui.components.nav.BackButton\n");

open(my $out, ">", $F) or die; print $out @L; close $out;
print "edits=$n, lines=", scalar(@L), "\n";
