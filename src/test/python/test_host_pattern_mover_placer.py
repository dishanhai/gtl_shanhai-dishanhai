"""Compile the real placer offline; stub only its Minecraft-dependent circuit API."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
PACKAGE = 'com.dishanhai.gt_shanhai.common.patternmover'

class PlacerRegression(unittest.TestCase):
    def test_callbacks_and_stale_plan(self):
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
            for name in ['HostPatternMoverPlacer.java', 'HostPatternMoverLog.java']:
                shutil.copyfile(source / name, folder / name)
            (folder / 'HostPatternMoverCircuit.java').write_text('package ' + PACKAGE + '; public final class HostPatternMoverCircuit { public static final int NONE=0; public static int normalize(int n) { return n>=1 && n<=32 ? n : 0; }}')
            (folder / 'Probe.java').write_text('package ' + PACKAGE + ';' + r'''
import java.util.*;
public class Probe {
    record U(String dedupKey) implements HostPatternMoverPlacer.Unit {
        public String type() { return "type"; }
        public boolean wildcard() { return false; }
    }
    static class T implements HostPatternMoverPlacer.Target {
        String[] keys; boolean[] valid; int writes;
        Runnable afterWrite = () -> {}, duringValidation = () -> {};
        T(int n) { keys = new String[n]; valid = new boolean[n]; Arrays.fill(valid, true); }
        public String label() { return "test"; }
        public int size() { return keys.length; }
        public String keyAt(int s) { return keys[s]; }
        public boolean supportsType(String t) { return true; }
        public boolean zoned() { return false; }
        public int normalSlots() { return size(); }
        public int wildcardSlots() { return 0; }
        public boolean validAt(int s, HostPatternMoverPlacer.Unit u) { duringValidation.run(); return valid[s]; }
        public void write(int s, HostPatternMoverPlacer.Unit u) { keys[s] = u.dedupKey(); writes++; afterWrite.run(); }
    }
    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    public static void main(String[] args) {
        var units = List.of(new U("a"), new U("b"));
        T occupied = new T(2);
        var plan = HostPatternMoverPlacer.plan(units, List.of(occupied));
        occupied.afterWrite = () -> occupied.keys[1] = "unrelated";
        var r = HostPatternMoverPlacer.apply(plan, units, List.of(occupied));
        check(occupied.writes == 1 && occupied.keys[1].equals("unrelated"), "callback occupancy protected");
        check(r.unplacedIndices().equals(List.of(1)) && r.skippedNoSlot() == 1, "unplaced entry retained");
        T fallback = new T(3);
        plan = HostPatternMoverPlacer.plan(units, List.of(fallback));
        fallback.afterWrite = () -> fallback.keys[1] = "unrelated";
        r = HostPatternMoverPlacer.apply(plan, units, List.of(fallback));
        check(fallback.keys[1].equals("unrelated") && fallback.keys[2].equals("b") && r.added() == 2, "fallback to free slot");
        T invalid = new T(2);
        plan = HostPatternMoverPlacer.plan(units, List.of(invalid));
        invalid.afterWrite = () -> invalid.valid[1] = false;
        r = HostPatternMoverPlacer.apply(plan, units, List.of(invalid));
        check(invalid.writes == 1 && r.unplacedIndices().equals(List.of(1)), "validAt refreshed");
        var single = List.of(new U("a"));
        T same = new T(1); same.keys[0] = "a";
        plan = HostPatternMoverPlacer.plan(single, List.of(same));
        r = HostPatternMoverPlacer.apply(plan, single, List.of(same));
        check(same.writes == 1 && r.replaced() == 1, "same key replace allowed");
        T changed = new T(1); changed.keys[0] = "a";
        plan = HostPatternMoverPlacer.plan(single, List.of(changed)); changed.keys[0] = "other";
        r = HostPatternMoverPlacer.apply(plan, single, List.of(changed));
        check(changed.writes == 0 && changed.keys[0].equals("other"), "stale replace denied");
        T validation = new T(1);
        plan = HostPatternMoverPlacer.plan(single, List.of(validation));
        validation.duringValidation = () -> validation.keys[0] = "other";
        r = HostPatternMoverPlacer.apply(plan, single, List.of(validation));
        check(validation.writes == 0 && r.unplacedIndices().equals(List.of(0)), "validation callback occupancy protected");
        T empty = new T(1); empty.keys[0] = "a";
        plan = HostPatternMoverPlacer.plan(single, List.of(empty)); empty.keys[0] = null;
        r = HostPatternMoverPlacer.apply(plan, single, List.of(empty));
        check(r.added() == 1 && r.replaced() == 0, "statistics reflect live state");
        System.out.println("PASS: 7 placer callback/stale-state scenarios");
    }
}
''')
            subprocess.run(compiler + language + ['-d', tmp, *map(str, folder.glob('*.java'))], check=True)
            subprocess.run([java, '-cp', tmp, PACKAGE + '.Probe'], check=True)

if __name__ == '__main__':
    unittest.main()
