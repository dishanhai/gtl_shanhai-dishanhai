package com.dishanhai.gt_shanhai.client.holo;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ShanhaiHoloPortSourceTest {

    private static final Path JAVA_ROOT = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai");

    @Test
    void holoPortContainsRewriteClientAndCommonLayers() {
        assertTrue(Files.isRegularFile(JAVA_ROOT.resolve(Path.of("common", "holo", "ShanhaiHoloMenuBoards.java"))));
        assertTrue(Files.isRegularFile(JAVA_ROOT.resolve(Path.of("common", "holo", "ShanhaiHoloMenuInputRules.java"))));
        assertTrue(Files.isRegularFile(JAVA_ROOT.resolve(Path.of("common", "holo", "ShanhaiHoloMenuNetwork.java"))));

        String[] clientFiles = {
                "ShanhaiHoloCommandScreen.java",
                "ShanhaiHoloMenuAnim.java",
                "ShanhaiHoloMenuClient.java",
                "ShanhaiHoloMenuHitTest.java",
                "ShanhaiHoloMenuInput.java",
                "ShanhaiHoloMenuLayout.java",
                "ShanhaiHoloMenuPanel.java",
                "ShanhaiHoloMenuPending.java",
                "ShanhaiHoloMenuRenderer.java",
                "ShanhaiHoloMenuState.java",
                "ShanhaiHoloMenuTuning.java",
                "ShanhaiHoloSurround.java",
                "ShanhaiHoloSurroundRenderer.java",
                "ShanhaiHoloSurroundTuning.java",
                "ShanhaiHoloSurroundView.java"
        };
        for (String file : clientFiles) {
            assertTrue(Files.isRegularFile(JAVA_ROOT.resolve(Path.of("client", "holo", file))), file);
        }
    }

    @Test
    void holoPortUsesCurrentModAndModuleNamespace() throws IOException {
        String boards = Files.readString(
                JAVA_ROOT.resolve(Path.of("common", "holo", "ShanhaiHoloMenuBoards.java")));
        String input = Files.readString(
                JAVA_ROOT.resolve(Path.of("client", "holo", "ShanhaiHoloMenuInput.java")));
        String network = Files.readString(
                JAVA_ROOT.resolve(Path.of("common", "holo", "ShanhaiHoloMenuNetwork.java")));

        assertTrue(network.contains("GTDishanhaiMod.MOD_ID"));
        assertTrue(boards.contains("class ShanhaiHoloMenuBoards"));
        assertTrue(input.contains("dishanhai"));
        assertTrue(input.contains("create_mk"));
        assertTrue(network.contains("gt_shanhai"));
        assertTrue(!network.contains("import com.shanhai."));
        assertTrue(!input.contains("import com.shanhai."));
    }

    @Test
    void clientLifecycleUsesForgeSubscribers() throws IOException {
        String menuClient = Files.readString(
                JAVA_ROOT.resolve(Path.of("client", "holo", "ShanhaiHoloMenuClient.java")));
        String input = Files.readString(
                JAVA_ROOT.resolve(Path.of("client", "holo", "ShanhaiHoloMenuInput.java")));

        assertTrue(menuClient.contains("@Mod.EventBusSubscriber"));
        assertTrue(menuClient.contains("RenderLevelStageEvent"));
        assertTrue(input.contains("@Mod.EventBusSubscriber"));
        assertTrue(input.contains("InteractionKeyMappingTriggered"));
    }

    @Test
    void rewriteContainsSurroundPresetsAndAdvancedTuningPage() throws IOException {
        String panel = Files.readString(
                JAVA_ROOT.resolve(Path.of("client", "holo", "ShanhaiHoloMenuPanel.java")));
        String tuning = Files.readString(
                JAVA_ROOT.resolve(Path.of("client", "holo", "ShanhaiHoloSurroundTuning.java")));
        String surround = Files.readString(
                JAVA_ROOT.resolve(Path.of("client", "holo", "ShanhaiHoloSurround.java")));
        String renderer = Files.readString(
                JAVA_ROOT.resolve(Path.of("client", "holo", "ShanhaiHoloSurroundRenderer.java")));

        assertTrue(panel.contains("MODE_ADVANCED"));
        assertTrue(panel.contains("CLICK_PRESET_STRONG"));
        assertTrue(panel.contains("advPageCount"));
        assertTrue(tuning.contains("P_FIELD_FOLLOW_TAU"));
        assertTrue(tuning.contains("PRESET_COUNT = 4"));
        assertTrue(surround.contains("nudgeParam"));
        assertTrue(renderer.contains("drawEnabled"));
    }
}
