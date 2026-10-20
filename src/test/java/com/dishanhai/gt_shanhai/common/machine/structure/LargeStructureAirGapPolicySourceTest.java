package com.dishanhai.gt_shanhai.common.machine.structure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * 大型结构空白格策略守卫。
 *
 * 结构数据已从 Java String[] 迁移到 assets 下的 .mb 文件，空白格语义由
 * GTCEu FactoryBlockPattern 构造函数保证（构造时自动注册 ' ' -> Predicates.any()），
 * 因此这里改为校验迁移后的等价不变量：结构类走 DShanhaiMBParser、.mb 文件存在且空白格未被改成强制空气。
 */
class LargeStructureAirGapPolicySourceTest {

    private static final Path STRUCTURE_DIR = Path.of("src", "main", "java", "com",
            "dishanhai", "gt_shanhai", "common", "machine", "structure");
    private static final Path WORLDLINE_DIR = Path.of("src", "main", "java", "com",
            "dishanhai", "gt_shanhai", "common", "machine", "worldline_cracking");
    private static final Path MB_DIR = Path.of("src", "main", "resources", "assets",
            "gt_shanhai", "multiblock");

    @Test
    void largeStructureBlankSlotsAcceptAnyBlockInsteadOfOnlyAir() throws IOException {
        assertBlankSlotAcceptsAnyBlock(
                STRUCTURE_DIR.resolve("STRStructure.java"), MB_DIR.resolve("str_structure.mb"));
        assertBlankSlotAcceptsAnyBlock(
                WORLDLINE_DIR.resolve("WorldlineCrackingHubStructure.java"),
                MB_DIR.resolve("worldline_cracking_hub.mb"));
    }

    private static void assertBlankSlotAcceptsAnyBlock(Path structure, Path mbFile) throws IOException {
        String source = Files.readString(structure);
        String file = structure.toString();

        assertTrue(source.contains("DShanhaiMBParser.parse"),
                file + " must load its structure data via DShanhaiMBParser");
        assertTrue(Files.isRegularFile(mbFile), mbFile + " must exist as the structure data source");
        assertFalse(source.contains(".where(' ', Predicates.blocks(Blocks.AIR))"),
                file + " must not force blank structure slots to be air");
        assertFalse(source.contains(".where(' ', Predicates.blocks("),
                file + " must not pin blank structure slots to a concrete block");
    }
}
