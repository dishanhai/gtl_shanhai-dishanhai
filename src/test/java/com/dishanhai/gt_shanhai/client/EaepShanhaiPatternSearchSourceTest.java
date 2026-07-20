package com.dishanhai.gt_shanhai.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EaepShanhaiPatternSearchSourceTest {

    private static final Path ROOT = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai");

    @Test
    void eaepAndExtendedAeFeaturesReachTheShanhaiTerminalThroughTheirPublicScreenLayer() throws Exception {
        String menu = Files.readString(ROOT.resolve(
                "common/item/terminal/ShanhaiPatternManagementMenu.java"));
        String screen = Files.readString(ROOT.resolve(
                "client/gui/terminal/ShanhaiPatternManagementScreen.java"));
        String style = Files.readString(Path.of("src", "main", "resources", "assets", "ae2", "screens",
                "shanhai_wireless_pattern_management.json"));

        assertTrue(menu.contains("extends ContainerExPatternTerminal"));
        assertTrue(screen.contains("extends GuiExPatternTerminal<ShanhaiPatternManagementMenu>"));
        assertTrue(style.contains("\"ex_pattern_access_terminal.json\""));
        assertTrue(style.contains("\"wtlib/universal_terminal_no_viewcells.json\""));
    }
}
