"""Pin the packaged GTLAdditions input predicate targeted by the #61 compatibility fix.

This verifies the real dependency bytecode and the guarded wiring. Complete
multiblock formation still needs a Forge instance.
"""

from pathlib import Path
import re
import shutil
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[3]
JAVA_ROOT = ROOT / "src/main/java/com/dishanhai/gt_shanhai"


class DraconicCollapseStellarInput(unittest.TestCase):
    def test_packaged_custom_controller_distinguishes_input_from_output(self):
        jar = ROOT / "libs/gtladditions-3.3.1Custom.jar"
        self.assertTrue(jar.is_file(), f"Missing packaged GTLAdditions build: {jar}")
        javap = shutil.which("javap")
        java = shutil.which("java")
        if not javap and not java:
            self.skipTest("Java with javap is required for packaged bytecode checks")
        tool = [javap] if javap else [java, "-m", "jdk.jdeps/com.sun.tools.javap.Main"]
        probe = subprocess.run(tool + ["-version"], capture_output=True, text=True)
        if probe.returncode and not javap:
            self.skipTest("javap is not available in the installed Java runtime")
        code = subprocess.check_output(tool + [
            "-classpath", str(jar), "-c", "-p",
            "com.gtladd.gtladditions.common.machine.multiblock.MultiBlockMachine",
        ], text=True, encoding="utf-8")
        factories = [body for body in re.split(r"\n  (?=\w)", code)
                     if "MultiBlockStructure.getDRACONIC_COLLAPSE_CORE_STRUCTURE:" in body]
        self.assertEqual(len(factories), 1)
        slots = dict(re.findall(
            r"// String ([A-Z])\s*\n(.*?)(?=// Method [^\n]*FactoryBlockPattern.where:)",
            factories[0], flags=re.S,
        ))
        input_rule, output_rule = slots["L"], slots["O"]
        for signature in ("GTMachines.ITEM_IMPORT_BUS", "CustomMachines.HUGE_ITEM_IMPORT_BUS",
                          "GTLAddMachines.getME_SUPER_PATTERN_BUFFER:",
                          "GTLAddMachines.getME_SUPER_PATTERN_BUFFER_PROXY:"):
            self.assertIn(signature, input_rule)
            self.assertNotIn(signature, output_rule)
        self.assertIn("PartAbility.EXPORT_ITEMS", output_rule)
        self.assertNotIn("PartAbility.EXPORT_ITEMS", input_rule)

    def test_wiring_scopes_append_to_the_stellar_block_on_the_custom_controller(self):
        compat = (JAVA_ROOT / "integration/gtladditions/DraconicCollapseInputCompat.java").read_text(
            encoding="utf-8")
        entry = (JAVA_ROOT / "GTDishanhaiMod.java").read_text(encoding="utf-8")
        self.assertIn('"3.3.1Custom".equals(', compat)
        self.assertIn("MultiBlockMachine.INSTANCE.getDRACONIC_COLLAPSE_CORE()", compat)
        self.assertIn("DShanhaiMachines.RECIPE_TYPE_PATTERN_BUFFER;", compat)
        self.assertNotIn("RECIPE_TYPE_PATTERN_BUFFER_PROXY", compat)
        self.assertIn("patchPatternPredicates(", compat)
        self.assertNotIn("replacePatternPredicates(", compat)
        self.assertRegex(entry, r"registerPartAbilities\(\);\s*event\.enqueueWork\([\w.]+DraconicCollapseInputCompat::install\)")
