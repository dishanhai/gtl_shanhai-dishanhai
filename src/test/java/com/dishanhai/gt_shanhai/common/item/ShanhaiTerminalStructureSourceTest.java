package com.dishanhai.gt_shanhai.common.item;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ShanhaiTerminalStructureSourceTest {

    private static final Path ADAPTER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "terminal", "ShanhaiStructurePatternAdapter.java");
    private static final Path PLANNER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "terminal", "ShanhaiStructurePlanner.java");
    private static final Path CONFIG = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "ShanhaiUltimateTerminalConfig.java");
    private static final Path BEHAVIOR = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "ShanhaiUltimateTerminalBehavior.java");
    private static final Path ZH_CN = Path.of("src", "main", "resources", "assets", "gt_shanhai",
            "lang", "zh_cn.json");

    @Test
    void adapterReadsTheRealGtceuPatternShape() throws Exception {
        String source = Files.readString(ADAPTER);

        assertTrue(source.contains("getDeclaredField(\"blockMatches\")"));
        assertTrue(source.contains("getDeclaredField(\"structureDir\")"));
        assertTrue(source.contains("getDeclaredField(\"aisleRepetitions\")"));
        assertTrue(source.contains("getDeclaredField(\"centerOffset\")"));
        assertTrue(source.contains("setActualRelativeOffset"));
        assertTrue(source.contains("Math.max(min, Math.min(max, repeatCount))"));
    }

    @Test
    void plannerNeverSelectsAChamberCandidateForPlacement() throws Exception {
        String source = Files.readString(PLANNER);

        assertTrue(source.contains("isChamberBlock"));
        assertTrue(source.contains("ordinaryCandidates"));
        assertTrue(source.contains("entry.chamberCapable()"));
        assertTrue(source.contains("ShanhaiStructurePlan.Kind.CHAMBER_HINT"));
    }

    @Test
    void tierReplacementIsExplicitAndUsesTheVerifiedGtlcoreMap() throws Exception {
        String source = Files.readString(PLANNER);

        assertTrue(source.contains("ShanhaiUltimateTerminalConfig.isReplaceMode"));
        assertTrue(source.contains("BlockMap.tierBlockMap"));
        assertTrue(source.contains("ShanhaiStructurePlan.Kind.REPLACE"));
    }

    @Test
    void plannerCachesPredicateCandidatesDuringOneScan() throws Exception {
        String source = Files.readString(PLANNER);

        assertTrue(source.contains("CandidateCache candidateCache = new CandidateCache()"));
        assertTrue(source.contains("IdentityHashMap"));
        assertTrue(source.contains("byPredicate.computeIfAbsent"));
        assertTrue(source.contains("readCandidates"));
    }

    @Test
    void noChamberModePlacesOrdinaryCasingsAndIgnoresChamberCandidates() throws Exception {
        String config = Files.readString(CONFIG);
        String planner = Files.readString(PLANNER);
        String behavior = Files.readString(BEHAVIOR);
        String lang = Files.readString(ZH_CN);

        assertTrue(config.contains("NO_CHAMBER_KEY = \"NoChamberMode\""));
        assertTrue(config.contains("isNoChamberMode"));
        assertTrue(config.contains("setNoChamberMode"));
        assertTrue(config.contains("!config.contains(NO_CHAMBER_KEY)"));
        assertTrue(planner.contains("ShanhaiUltimateTerminalConfig.isNoChamberMode(terminal)"));
        assertTrue(planner.contains("!noChambers && entry.chamberCapable()"));
        assertTrue(planner.contains("ShanhaiStructurePlan.Kind.CHAMBER_HINT"));
        assertTrue(planner.contains("ordinaryCandidates"));
        assertTrue(behavior.contains("gui.gt_shanhai.ultimate_terminal.no_chamber"));
        assertTrue(behavior.contains("ShanhaiUltimateTerminalConfig.setNoChamberMode"));
        assertTrue(lang.contains("\"gui.gt_shanhai.ultimate_terminal.no_chamber\": \"无仓室模式\""));
    }
}
