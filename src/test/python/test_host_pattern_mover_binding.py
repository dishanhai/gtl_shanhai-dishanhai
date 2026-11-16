"""Binding regressions: compile the real dependency-free identity class, plus UI wiring guards.

Run: python -m unittest discover -s src/test/python -p test_host_pattern_mover_binding.py -v
The JVM test is explicitly skipped without JDK 17+; source guards still run.
"""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
PACKAGE = 'com.dishanhai.gt_shanhai.common.patternmover'
SOURCE = ROOT / 'src/main/java' / PACKAGE.replace('.', '/')


def source(name):
    return (SOURCE / (name + '.java')).read_text(encoding='utf-8')


def compact(name):
    return re.sub(r'\s+', '', source(name))


class BindingRegression(unittest.TestCase):
    def test_real_binding_identity_and_sticky_invalidation(self):
        javac, java = shutil.which('javac'), shutil.which('java')
        if not java:
            self.skipTest('Java 17+ required on PATH; real Binding not executed')
        compiler = [javac] if javac else [java, 'com.sun.tools.javac.Main']
        probe = subprocess.run(compiler + ['-version'], capture_output=True, text=True)
        if probe.returncode:
            self.skipTest('JDK compiler unavailable; real Binding not executed')
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            shutil.copyfile(SOURCE / 'HostPatternMoverBinding.java', folder / 'HostPatternMoverBinding.java')
            (folder / 'BindingProbe.java').write_text('package ' + PACKAGE + ';' + r'''
public class BindingProbe {
    static final Object player = new Object(), world = new Object();
    static final Object main = new Object(), off = new Object();
    // Value-equal objects must NOT substitute for the physical tool identity.
    record Tool(String data) {}
    static final Tool original = new Tool("same NBT");
    static HostPatternMoverBinding binding(Object hand, int slot) {
        return new HostPatternMoverBinding(player, world, hand, slot, original);
    }
    static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }
    static void reject(Object p, Object w, Object h, int s, Object tool, String name) {
        var b = binding(main, 2);
        check(!b.matches(p, w, h, s, tool), name);
        check(!b.matches(player, world, main, 2, original), name + ": invalid remains latched");
    }
    public static void main(String[] args) {
        var stable = binding(main, 2);
        for (int i = 0; i < 20; i++)
            check(stable.matches(player, world, main, 2, original), "stable identity");
        Tool other = new Tool("same NBT");
        check(original.equals(other) && original != other, "test equal-looking replacement");
        reject(player, world, main, 2, other, "replacement tool");
        reject(player, world, off, 40, original, "same tool moved to other hand");
        reject(player, world, main, 3, original, "same tool moved to another selected slot");
        reject(player, new Object(), main, 2, original, "different world");
        reject(new Object(), world, main, 2, original, "different player");
        reject(player, world, main, 2, null, "tool removed");
        var offBinding = binding(off, 40);
        check(offBinding.matches(player, world, off, 40, original), "offhand tool selected while dual wielding");
        check(!offBinding.matches(player, world, main, 2, other), "never fallback to main hand tool");
        check(!offBinding.matches(player, world, off, 40, original), "dual wield fallback permanently invalidates");
        System.out.println("PASS: stable identity, dual wield, hand/slot changes, replacement, world/player, sticky invalidation");
    }
}
''', encoding='utf-8')
            subprocess.run(compiler + ['-source', '17', '-target', '17', '-encoding', 'UTF-8', '-d', tmp,
                            str(folder / 'HostPatternMoverBinding.java'), str(folder / 'BindingProbe.java')],
                           check=True, capture_output=True, text=True)
            result = subprocess.run([java, '-cp', tmp, PACKAGE + '.BindingProbe'],
                                    check=True, capture_output=True, text=True)
            self.assertIn('PASS:', result.stdout)

    def test_ui_never_rediscover_tool_by_hand_fallback(self):
        for name in ('HostPatternMoverFactory', 'HostPatternMoverPanel',
                     'HostPatternMoverConfirmPanel', 'HostPatternMoverClearPanel'):
            with self.subTest(name=name):
                self.assertNotRegex(source(name), r'\bfindMover\s*\(')
        factory = compact('HostPatternMoverFactory')
        self.assertIn('player.getItemInHand(boundHand)', factory)
        self.assertIn('toolBinding.matches(player,player.level(),boundHand,slot,current)', factory)
        self.assertIn('player.getItemInHand(hand)!=stack', factory)
        self.assertIn('InteractionHand.MAIN_HAND?player.getInventory().selected:40', factory)
        for name in ('HostPatternMoverPanel', 'HostPatternMoverConfirmPanel'):
            self.assertIn('holder.tool(serverPlayer)', compact(name))

    def test_actual_interaction_hand_propagates_through_all_open_paths(self):
        item = compact('HostPatternMoverItem')
        self.assertIn('HostPatternMoverFactory.open(serverPlayer,stack,hand)', item)
        self.assertIn('HostPatternMoverActions.preview(serverPlayer,stack,context.getHand(),machine,pos)', item)
        actions = compact('HostPatternMoverActions')
        self.assertIn('HostPatternMoverFactory.openConfirm(player,preview,stack,hand)', actions)
        self.assertIn('HostPatternMoverFactory.openClear(player,preview,stack,hand)', actions)
        self.assertIn('openClearConfirm(serverPlayer,pos,tool,this.holder.hand())',
                      compact('HostPatternMoverConfirmPanel'))
        self.assertEqual(compact('HostPatternMoverFactory').count('holder.bind(player,stack,hand)'), 3)

    def test_invalid_tool_or_target_requires_reopening(self):
        for name in ('HostPatternMoverPanel', 'HostPatternMoverConfirmPanel'):
            with self.subTest(name=name):
                self.assertIn('搬运工具已更换或移动，请重新打开面板', source(name))
        for name in ('HostPatternMoverConfirmPanel', 'HostPatternMoverClearPanel'):
            with self.subTest(name=name):
                self.assertIn('目标已失效或无交互权限，请重新打开', source(name))


if __name__ == '__main__':
    unittest.main()
