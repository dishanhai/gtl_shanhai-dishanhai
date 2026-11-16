"""Compile the actual type-edit methods against small inventory/API boundary stubs."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / 'src/main/java/com/dishanhai/gt_shanhai/common/machine/part/RecipeTypePatternBufferPartMachine.java'


def method(text, name):
    start = text.index('    private void ' + name + '(')
    opening = text.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        if text[end] == '{':
            depth += 1
        elif text[end] == '}':
            depth -= 1
        end += 1
    return text[start:end]


class WildcardTypeCircuitPreservation(unittest.TestCase):
    def test_real_type_edits_preserve_explicit_and_default_circuits(self):
        java = shutil.which('java')
        if not java:
            self.skipTest('Java 17+ required')
        javac = shutil.which('javac')
        compiler = [javac] if javac else [java, 'com.sun.tools.javac.Main']
        if subprocess.run(compiler + ['-version'], capture_output=True).returncode:
            self.skipTest('JDK compiler required')
        actual = SOURCE.read_text(encoding='utf-8')
        methods = '\n'.join(method(actual, name) for name in
                            ['assignWildcardRecipeType', 'clearWildcardRecipeType', 'assignWildcardRecipeTypeId'])
        harness = r'''
public class TypeCircuitProbe {
    static class ItemStack {
        final boolean empty; final String type;
        ItemStack(boolean empty, String type) { this.empty = empty; this.type = type; }
        boolean isEmpty() { return empty; }
    }
    static class GTRecipeType {
        final String registryName;
        GTRecipeType(String name) { registryName = name; }
    }
    static class WildcardPatternRecipeTypeBinding {
        static ItemStack assign(ItemStack s, String type) { return new ItemStack(s.empty, type); }
        static ItemStack clear(ItemStack s) { return assign(s, ""); }
    }
    final int wildcardSlotCount = 1;
    int circuit, writes, rawWrites;
    ItemStack item = new ItemStack(false, "old");
    final Inventory wildcardPatternInventory = new Inventory();
    class Inventory {
        ItemStack getStackInSlot(int slot) {
            if (slot != 0) throw new AssertionError("invalid slot read");
            return item;
        }
        void setStackInSlot(int slot, ItemStack stack) {
            rawWrites++; item = stack; circuit = 0;
        }
    }
    int gtShanhai$getWildcardMotherCircuit(int slot) {
        if (slot != 0) throw new AssertionError("invalid circuit read");
        return circuit;
    }
    boolean gtShanhai$setWildcardMotherPattern(int slot, ItemStack stack, int value) {
        if (slot != 0) throw new AssertionError("invalid atomic write");
        writes++; item = stack; circuit = value; return true;
    }
__METHODS__
    static void check(boolean ok, String text) { if (!ok) throw new AssertionError(text); }
    static TypeCircuitProbe machine(int circuit) {
        var m = new TypeCircuitProbe(); m.circuit = circuit; return m;
    }
    public static void main(String[] args) {
        for (int value : new int[]{0, 5, 32}) {
            var assign = machine(value);
            assign.assignWildcardRecipeType(0, new GTRecipeType("new"));
            check(assign.item.type.equals("new") && assign.circuit == value, "assign preserves " + value);
            check(assign.writes == 1 && assign.rawWrites == 0, "assign uses one atomic write");
            var clear = machine(value);
            clear.clearWildcardRecipeType(0);
            check(clear.item.type.isEmpty() && clear.circuit == value, "clear preserves " + value);
            check(clear.writes == 1 && clear.rawWrites == 0, "clear uses one atomic write");
            var byId = machine(value);
            byId.assignWildcardRecipeTypeId(0, "new:id");
            check(byId.item.type.equals("new:id") && byId.circuit == value, "assign by id preserves " + value);
            check(byId.writes == 1 && byId.rawWrites == 0, "assign by id uses one atomic write");
            var invalid = machine(value);
            for (int slot : new int[]{-1, 1, Integer.MAX_VALUE}) {
                invalid.assignWildcardRecipeType(slot, new GTRecipeType("new"));
                invalid.clearWildcardRecipeType(slot);
                invalid.assignWildcardRecipeTypeId(slot, "new:id");
            }
            invalid.assignWildcardRecipeTypeId(0, null);
            invalid.assignWildcardRecipeTypeId(0, "");
            invalid.assignWildcardRecipeType(0, null);
            invalid.assignWildcardRecipeType(0, new GTRecipeType(null));
            check(invalid.writes == 0 && invalid.rawWrites == 0 && invalid.circuit == value,
                    "invalid inputs never write");
            invalid.item = new ItemStack(true, "");
            invalid.assignWildcardRecipeType(0, new GTRecipeType("new"));
            invalid.clearWildcardRecipeType(0);
            invalid.assignWildcardRecipeTypeId(0, "new:id");
            check(invalid.writes == 0 && invalid.rawWrites == 0 && invalid.circuit == value,
                    "empty mother never writes");
        }
        System.out.println("PASS: all three real type setters preserve 0/5/32; invalid slot/type/empty do not write");
    }
}
'''.replace('__METHODS__', methods)
        with tempfile.TemporaryDirectory() as tmp:
            probe = Path(tmp) / 'TypeCircuitProbe.java'
            probe.write_text(harness, encoding='utf-8')
            compiled = subprocess.run(compiler + ['-source', '17', '-target', '17', '-encoding', 'UTF-8',
                                                   '-d', tmp, str(probe)], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run([java, '-cp', tmp, 'TypeCircuitProbe'], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('PASS:', result.stdout)


if __name__ == '__main__':
    unittest.main()
