package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 星律样板总成默认槽位 10×10×10 = 1000：常量、配置默认、配置注释、指南、tooltip 必须同一套数字。 */
class RecipeTypePatternBufferDefaultSlotsSourceTest {

    private static final Path MACHINE = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "machine", "part", "RecipeTypePatternBufferPartMachine.java");
    private static final Path CONFIG = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "config", "DShanhaiConfig.java");
    private static final Path MACHINES = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "machine", "DShanhaiMachines.java");
    private static final Path GUIDE = Path.of("src", "main", "resources", "assets", "gt_shanhai", "guides",
            "gt_shanhai", "guide", "ae", "recipe_type_pattern_buffer.md");

    @Test
    void machineDefaultsMatchConfigDefaults() throws IOException {
        String machine = read(MACHINE);
        assertTrue(machine.contains("DEFAULT_PATTERNS_PER_ROW = 10;"));
        assertTrue(machine.contains("DEFAULT_ROWS_PER_PAGE = 10;"));
        assertTrue(machine.contains("DEFAULT_MAX_PAGES = 10;"));

        String block = configBlock();
        assertTrue(block.contains("defineInRange(\"patternsPerRow\", 10, 1, 16)"));
        assertTrue(block.contains("defineInRange(\"rowsPerPage\", 10, 1, 16)"));
        assertTrue(block.contains("defineInRange(\"maxPages\", 10, 1, 64)"));
    }

    @Test
    void configCommentsStateTheSameDefaults() throws IOException {
        String block = configBlock();
        assertTrue(block.contains("每行样板槽位数（默认 10）"));
        assertTrue(block.contains("每页行数（默认 10）"));
        assertTrue(block.contains("最大页数（默认 10）"));
        assertTrue(block.contains("默认 10×10×10 = 1000"));
        assertFalse(block.contains("（默认 9）") || block.contains("（默认 6）") || block.contains("（默认 3）"),
                "星律段不应残留旧默认值 9/6/3");
    }

    @Test
    void guideStatesTheSameDefaultsAndWildcardSlotCount() throws IOException {
        String guide = read(GUIDE);
        assertTrue(guide.contains("默认 10×10×10 = 1000 槽"));
        assertTrue(guide.contains("- patternsPerRow（默认 10）"));
        assertTrue(guide.contains("- rowsPerPage（默认 10）"));
        assertTrue(guide.contains("- maxPages（默认 10）"));
        assertFalse(guide.contains("9×6×3") || guide.contains("162 槽"), "指南不应残留旧的 162 槽");
        assertFalse(guide.contains("含 5 个母槽"), "通配符母槽数由 wildcardSlotsPerPage × wildcardMaxPages 决定，默认 20");
        assertTrue(guide.contains("wildcardSlotsPerPage") && guide.contains("wildcardMaxPages"));
    }

    @Test
    void tooltipDoesNotHardcodeWildcardSlotCountOrStaleApplyRule() throws IOException {
        String machines = read(MACHINES);
        assertFalse(machines.contains("20个通配符槽"), "通配符槽数来自配置，tooltip 不能写死");
        assertFalse(machines.contains("改完重新放置才生效"), "已放置的总成在下次区块加载时即采用新配置");
    }

    private static String configBlock() throws IOException {
        String config = read(CONFIG);
        int start = config.indexOf("recipeTypePatternsPerRow = builder");
        int end = config.indexOf("recipeTypeWildcardSlotsPerPage = builder", start);
        assertTrue(start >= 0 && end > start, "星律槽位配置段必须存在");
        return config.substring(start, end);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n");
    }
}
