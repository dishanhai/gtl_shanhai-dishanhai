package com.dishanhai.gt_shanhai.client.gui.generator;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：客戶端遷移必須保留七頁、減少動效與模糊生命週期。 */
class PatternGeneratorScreenSourceTest {
    private static final Path ROOT = Path.of("src/main/java/com/dishanhai/gt_shanhai/");

    @Test void scaledScreenOwnsMotionAndCleanup() throws Exception {
        String screen = Files.readString(ROOT.resolve("client/gui/generator/PatternGeneratorScreen.java"));
        assertTrue(screen.contains("extends ScaledScreen"));
        assertTrue(screen.contains("reduceMotion()"));
        assertTrue(screen.contains("void removed()"));
        assertTrue(screen.contains("ShopGaussBlur.clear()"));
        assertTrue(screen.contains("ShopGaussBlur.clearTopLayer()"));
        assertTrue(screen.contains("deferTopLayer"));
        assertTrue(screen.contains("void tick()"));
        assertTrue(screen.contains("GlassBlur.warmUp()"));
        String rendering = screen.substring(screen.indexOf("void renderScaledBackground"), screen.indexOf("void renderTooltips"));
        assertFalse(rendering.contains("warmUp()"));
        String init = Files.readString(ROOT.resolve("client/ClientInit.java"));
        assertTrue(init.contains("instanceof com.dishanhai.gt_shanhai.client.gui.generator.PatternGeneratorScreen"));
    }

    @Test void sevenViewsAndPickerGhostsRetainAllText() throws Exception {
        Path directory = ROOT.resolve("client/gui/generator");
        for (String name : new String[]{"Main", "Preview", "Filter", "Circuit", "Extension", "Preset", "SavePreset"}) {
            assertTrue(Files.readString(directory.resolve(name + "View.java")).contains("GeneratorView"));
        }
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                for (String forbidden : new String[]{"TextType.HIDE", "LEFT_HIDE", ".substring(", "trimText(", "plainSubstrByWidth("}) {
                    assertFalse(source.contains(forbidden), path + ": " + forbidden);
                }
            }
        }
        String ghost = Files.readString(directory.resolve("GeneratorGhostSlot.java"));
        assertTrue(ghost.contains("MultiPickerScreen"));
        assertTrue(ghost.contains("MetaMachineItem"));
        assertTrue(ghost.contains("GlassCell.draw"));
    }
}
