package com.dishanhai.gt_shanhai.common.machine.primordial.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class PrimordialModuleStructureCasingSourceTest {

    private static final Path MODULE_ROOT = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "primordial", "module");
    private static final Path TAIXU_STRUCTURE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "misc", "TaixuSmeltingFurnaceStructure.java");

    @Test
    void moduleStructuresOnlyKeepIndustrialSteamCasingAtTheSharedEngineSlot() throws Exception {
        try (Stream<Path> files = Files.walk(MODULE_ROOT)) {
            List<Path> structures = files
                    .filter(path -> path.getFileName().toString().endsWith("Structure.java"))
                    .sorted()
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
            structures.add(TAIXU_STRUCTURE);

            assertTrue(structures.size() > 0, "未找到原初模块结构文件");
            for (Path structure : structures) {
                String source = Files.readString(structure);
                String file = structure.toString();

                assertEquals(1, count(source, "\"gtceu\", \"industrial_steam_casing\""),
                        file + " 必须且只能保留一个工业蒸汽机械方块查表，作为主机/模块共用 D 点");
                assertTrue(source.contains(".where('D', Predicates.blocks(industrialSteamCasing))"),
                        file + " 必须保留 D 点工业蒸汽机械方块；GTLAdditions 原模板中模块 D 共 21 个，与主机 E 共用");
                assertFalse(source.contains(".where('E', Predicates.blocks(industrialSteamCasing))"),
                        file + " 不得把模块 E 当成主机共用点；原模板中模块 E 不是主机 E 的共享 casing");
                assertFalse(source.contains("Predicates.blocks(bronzeCasing, industrialSteamCasing)")
                                || source.contains("Predicates.blocks(\n                bronzeCasing, industrialSteamCasing)")
                                || source.contains("Predicates.blocks(\r\n                bronzeCasing, industrialSteamCasing)"),
                        file + " 不得把工业蒸汽机械方块混入青铜外壳候选集合，预览会优先放置青铜外壳");
            }
        }
    }

    private static int count(String source, String needle) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
