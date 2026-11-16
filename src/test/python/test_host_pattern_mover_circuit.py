"""Exercise production Placer atomic circuit semantics; stub only the MC-dependent circuit API.
Production Entry identity is additionally asserted in HostPatternMoverSelfCheck C31ID."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
PACKAGE = 'com.dishanhai.gt_shanhai.common.patternmover'

class CircuitRegression(unittest.TestCase):
    def test_atomic_circuit_transfer(self):
        javac, java = shutil.which('javac'), shutil.which('java')
        if not java:
            self.skipTest('Java 17+ required on PATH')
        compiler = [javac] if javac else [java, 'com.sun.tools.javac.Main']
        # Minimal runtime images may retain jdk.compiler but omit ct.sym.
        # The fallback verifies Java 17 syntax/bytecode, not its API surface.
        language = ['--release', '17'] if javac else ['-source', '17', '-target', '17']
        if not javac:
            probe = subprocess.run(compiler + ['-version'], capture_output=True, text=True)
            if probe.returncode != 0:
                self.skipTest('JDK compiler required (javac or jdk.compiler module)')
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            source = ROOT / 'src/main/java' / PACKAGE.replace('.', '/')
            for name in ['HostPatternMoverPlacer.java', 'HostPatternMoverLog.java', 'HostPatternMoverTakeout.java']:
                shutil.copyfile(source / name, folder / name)
            (folder / 'HostPatternMoverCircuit.java').write_text('package ' + PACKAGE + '; public final class HostPatternMoverCircuit { public static final int NONE=0; public static int normalize(int n) { return n>=1 && n<=32 ? n : 0; }}')
            (folder / 'Probe.java').write_text('package ' + PACKAGE + ';' + r'''
import java.util.*;
public class Probe {
    record U(String recipe, int circuit) implements HostPatternMoverPlacer.Unit {
        public String dedupKey() { return recipe + (circuit == 0 ? "" : "|circuit=" + circuit); }
        public String type() { return "type"; }
        public boolean wildcard() { return false; }
    }
    static class T implements HostPatternMoverPlacer.Target {
        final String[] keys = new String[3];
        int writes;
        public String label() { return "fake"; }
        public int size() { return keys.length; }
        public String keyAt(int slot) { return keys[slot]; }
        public boolean supportsType(String type) { return true; }
        public boolean zoned() { return false; }
        public int normalSlots() { return size(); }
        public int wildcardSlots() { return 0; }
        public boolean validAt(int slot, HostPatternMoverPlacer.Unit unit) { return true; }
        public void write(int slot, HostPatternMoverPlacer.Unit unit) { keys[slot] = unit.dedupKey(); writes++; }
        public void applyCircuit(int slot, int value) { throw new AssertionError("must never post-write circuit"); }
    }
    static class Atomic extends T {
        final int[] circuits = new int[3];
        boolean reject, fail;
        int notifications;
        public boolean writeWithCircuit(int slot, HostPatternMoverPlacer.Unit unit) {
            if (fail) throw new IllegalStateException("atomic failure before mutation");
            if (reject) return false;
            keys[slot] = unit.dedupKey();
            circuits[slot] = unit.circuit();
            writes++;
            // Observers are notified only when both sample and configuration are present.
            check(keys[slot].equals(unit.dedupKey()) &&
                    (unit.circuit() == 0 || circuits[slot] == unit.circuit()), "atomic observation");
            notifications++;
            return true;
        }
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static HostPatternMoverPlacer.Result place(List<U> units, T target) {
        return HostPatternMoverPlacer.apply(HostPatternMoverPlacer.plan(units, List.of(target)), units, List.of(target));
    }
    public static void main(String[] args) {
        T unsupported = new T();
        var r = place(List.of(new U("recipe:a", 5)), unsupported);
        check(r.placed() == 0 && unsupported.writes == 0 && r.unplacedIndices().equals(List.of(0))
                && r.circuitSkipped() == 1 && r.skippedNoSlot() == 1, "unsupported circuit stays in item");
        Atomic rejected = new Atomic(); rejected.reject = true;
        r = place(List.of(new U("recipe:a", 5)), rejected);
        check(r.placed() == 0 && rejected.keys[0] == null && rejected.circuits[0] == 0
                && r.unplacedIndices().equals(List.of(0)) && r.circuitSkipped() == 1, "rejected write retained");
        Atomic failed = new Atomic(); failed.fail = true;
        r = place(List.of(new U("recipe:a", 5)), failed);
        check(r.placed() == 0 && failed.keys[0] == null && failed.circuits[0] == 0
                && r.unplacedIndices().equals(List.of(0)) && r.circuitFailed() == 1, "exception retained");
        Atomic success = new Atomic();
        r = place(List.of(new U("recipe:a", 5)), success);
        check(r.placed() == 1 && r.circuits() == 1 && success.circuits[0] == 5
                && success.keys[0].equals("recipe:a|circuit=5") && success.notifications == 1, "atomic success carries circuit");
        r = place(List.of(new U("recipe:a", 7)), success);
        check(r.added() == 1 && r.replaced() == 0 && success.circuits[0] == 5 && success.circuits[1] == 7
                && success.keys[0].equals("recipe:a|circuit=5") && success.keys[1].equals("recipe:a|circuit=7"), "different configurations coexist");
        r = place(List.of(new U("recipe:a", 5)), success);
        check(r.replaced() == 1 && success.circuits[1] == 7, "same circuit replaces only itself");
        T zero = new T(); zero.keys[0] = "recipe:legacy";
        r = place(List.of(new U("recipe:legacy", 0)), zero);
        check(r.replaced() == 1 && zero.writes == 1 && r.circuits() == 0
                && zero.keys[0].equals("recipe:legacy"), "zero preserves legacy key and path");
        Atomic zeroAtomic = new Atomic(); zeroAtomic.keys[0] = "recipe:legacy"; zeroAtomic.circuits[0] = 9;
        r = place(List.of(new U("recipe:legacy", 0)), zeroAtomic);
        check(r.placed() == 1 && r.circuits() == 0, "zero uses atomic path without old post-write API");
        final List<Integer> inventoryCalls = new ArrayList<>(), dropCalls = new ArrayList<>();
        var taken = HostPatternMoverTakeout.run(List.of(new U("recipe:a", 5), new U("recipe:b", 0),
                new U("recipe:c", 7), new U("recipe:d", 0)), "type", new HostPatternMoverTakeout.Handout() {
            public boolean toInventory(HostPatternMoverPlacer.Unit u, int i) { inventoryCalls.add(i); return i == 1; }
            public boolean drop(HostPatternMoverPlacer.Unit u, int i) { dropCalls.add(i); return true; }
        });
        check(inventoryCalls.equals(List.of(1, 3)) && dropCalls.equals(List.of(3)), "configured entries never reach handout");
        check(taken.keptIndices().equals(List.of(0, 2)) && taken.toInventory() == 1 && taken.dropped() == 1
                && taken.balanced() && taken.conserved(), "zero can be handed out; configured entries stay intact");
        System.out.println("PASS: 10 atomic circuit and takeout scenarios (production core, fake machine boundary)");
    }
}
''')
            subprocess.run(compiler + language + ['-d', tmp, *map(str, folder.glob('*.java'))], check=True)
            subprocess.run([java, '-cp', tmp, PACKAGE + '.Probe'], check=True)

if __name__ == '__main__':
    unittest.main()
