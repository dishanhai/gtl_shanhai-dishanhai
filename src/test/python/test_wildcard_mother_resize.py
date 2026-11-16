"""Run actual identity snapshot code with old/new inventory-size mismatch fixtures."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / 'src/main/java/com/dishanhai/gt_shanhai/common/machine/part/RecipeTypePatternBufferPartMachine.java'


def extract(text, signature):
    start = text.index(signature)
    end = text.index('{', start) + 1
    depth = 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


class MotherResizeRegression(unittest.TestCase):
    def test_real_snapshot_handles_all_resize_directions(self):
        java = shutil.which('java')
        if not java:
            self.skipTest('Java 17+ required')
        compiler = [shutil.which('javac')] if shutil.which('javac') else [java, 'com.sun.tools.javac.Main']
        if subprocess.run(compiler + ['-version'], capture_output=True).returncode:
            self.skipTest('JDK compiler required')
        text = SOURCE.read_text(encoding='utf-8')
        methods = '\n'.join(extract(text, signature) for signature in (
            'private void rememberMotherIdentities(', 'public int gtShanhai$getWildcardMotherCircuit(',
            'public boolean gtShanhai$setWildcardMotherCircuit('))
        harness = r'''
import java.util.*;
public class ResizeProbe {
    static final class ItemStack {
        static final ItemStack EMPTY = new ItemStack("");
        final String tag;
        ItemStack(String tag) { this.tag = tag; }
        boolean isEmpty() { return tag.isEmpty(); }
        ItemStack copy() { return new ItemStack(tag); }
        static boolean isSameItemSameTags(ItemStack a, ItemStack b) {
            return a != null && b != null && a.tag.equals(b.tag);
        }
    }
    static class Inventory {
        final ItemStack[] items;
        int reads;
        Inventory(int size) { items = new ItemStack[size]; Arrays.fill(items, new ItemStack("original")); }
        int getSlots() { return items.length; }
        ItemStack getStackInSlot(int slot) {
            if (slot < 0 || slot >= items.length) throw new AssertionError("out-of-bounds read " + slot);
            reads++; return items[slot];
        }
    }
    int[] wildcardMotherCircuits;
    ItemStack[] wildcardMotherIdentities;
    Inventory wildcardPatternInventory;
    int wildcardSlotCount, writes;
    ResizeProbe(int configured, int stored) {
        wildcardSlotCount = configured;
        wildcardMotherCircuits = new int[configured]; Arrays.fill(wildcardMotherCircuits, 5);
        wildcardMotherIdentities = new ItemStack[configured]; Arrays.fill(wildcardMotherIdentities, ItemStack.EMPTY);
        wildcardPatternInventory = new Inventory(stored);
    }
    boolean gtShanhai$setWildcardMotherPattern(int slot, ItemStack stack, int circuit) { writes++; return true; }
__METHODS__
    static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    public static void main(String[] args) {
        var grow = new ResizeProbe(4, 2); grow.rememberMotherIdentities(false);
        check(Arrays.equals(grow.wildcardMotherCircuits, new int[]{5,5,0,0}), "old small -> new large");
        check(grow.wildcardMotherIdentities[2].isEmpty() && grow.wildcardMotherIdentities[3].isEmpty(), "missing identities empty");
        check(grow.gtShanhai$getWildcardMotherCircuit(2) == 0, "getter rejects missing inventory slot");
        check(!grow.gtShanhai$setWildcardMotherCircuit(2, 5) && grow.writes == 0, "setter rejects missing inventory slot");
        var shrink = new ResizeProbe(2, 4); shrink.rememberMotherIdentities(false);
        check(Arrays.equals(shrink.wildcardMotherCircuits, new int[]{5,5}), "old large -> new small");
        check(shrink.wildcardPatternInventory.reads == 2, "does not walk discarded stored slots");
        var zeroInventory = new ResizeProbe(2, 0); zeroInventory.rememberMotherIdentities(false);
        check(Arrays.equals(zeroInventory.wildcardMotherCircuits, new int[]{0,0}), "zero inventory slots");
        var zeroConfig = new ResizeProbe(0, 2); zeroConfig.rememberMotherIdentities(false);
        check(zeroConfig.wildcardPatternInventory.reads == 0, "zero configured slots");
        var empty = new ResizeProbe(1, 1); empty.wildcardPatternInventory.items[0] = ItemStack.EMPTY;
        empty.rememberMotherIdentities(false); check(empty.wildcardMotherCircuits[0] == 0, "empty clears stale circuit");
        var same = new ResizeProbe(1, 1); same.rememberMotherIdentities(false);
        same.rememberMotherIdentities(true); check(same.wildcardMotherCircuits[0] == 5, "same pattern keeps circuit");
        same.wildcardPatternInventory.items[0] = new ItemStack("replacement");
        same.rememberMotherIdentities(true); check(same.wildcardMotherCircuits[0] == 0, "replacement clears circuit");
        check(!same.gtShanhai$setWildcardMotherCircuit(-1, 5)
                && !same.gtShanhai$setWildcardMotherCircuit(0, 33)
                && !same.gtShanhai$setWildcardMotherCircuit(0, -1), "invalid API values rejected");
        System.out.println("PASS: resize, empty, replacement, stable identity, actual-slot guards");
    }
}
'''.replace('__METHODS__', methods)
        # The atomic setter has additional game dependencies; verify its exact preflight guard separately.
        atomic = extract(text, 'public boolean gtShanhai$setWildcardMotherPattern(')
        guard = atomic[:atomic.index('ItemStack copy')]
        self.assertIn('motherSlot >= wildcardPatternInventory.getSlots()', guard)
        with tempfile.TemporaryDirectory() as tmp:
            probe = Path(tmp) / 'ResizeProbe.java'; probe.write_text(harness, encoding='utf-8')
            compiled = subprocess.run(compiler + ['-source', '17', '-target', '17', '-encoding', 'UTF-8',
                                                   '-d', tmp, str(probe)], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run([java, '-cp', tmp, 'ResizeProbe'], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('PASS:', result.stdout)


if __name__ == '__main__':
    unittest.main()
