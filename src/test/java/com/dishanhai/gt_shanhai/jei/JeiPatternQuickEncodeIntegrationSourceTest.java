package com.dishanhai.gt_shanhai.jei;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class JeiPatternQuickEncodeIntegrationSourceTest {

    private static final Path BUTTONS = source("integration", "jei", "JeiPatternQuickEncodeButtons.java");
    private static final Path PLUGIN = source("jei", "ShanhaiJEIPlugin.java");
    private static final Path PACKET = source("network", "JeiPatternQuickEncodeRequestPacket.java");
    private static final Path NETWORK = source("network", "ShanhaiNetwork.java");
    private static final Path ACCESSOR = source("mixin", "PatternEncodingTermMenuAccessor.java");
    private static final Path MIXIN_CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");
    private static final Path ZH_CN = lang("zh_cn.json");
    private static final Path EN_US = lang("en_us.json");

    @Test
    void registersTwoJeiRecipeButtonsWithExactTooltips() throws Exception {
        String buttons = Files.readString(BUTTONS);
        String plugin = Files.readString(PLUGIN);
        String zhCn = Files.readString(ZH_CN);
        String enUs = Files.readString(EN_US);

        assertTrue(plugin.contains("void registerAdvanced(IAdvancedRegistration registration)"));
        assertTrue(plugin.contains("JeiPatternQuickEncodeButtons.register(registration)"));
        assertTrue(buttons.contains("addRecipeButtonFactory(new Factory(patternIcon, false))"));
        assertTrue(buttons.contains("addRecipeButtonFactory(new Factory(patternIcon, true))"));
        assertTrue(buttons.contains("instanceof Recipe<?> recipe"));
        assertTrue(buttons.contains("state.setVisible(true)"));
        assertTrue(buttons.contains("state.setActive(true)"));
        assertTrue(buttons.contains("message.gt_shanhai.jei.quick_encode.open_terminal"));
        assertTrue(buttons.contains("displayClientMessage"));
        assertTrue(zhCn.contains("\"tooltip.gt_shanhai.jei.quick_encode_pattern\": \"快速编写为样板\""));
        assertTrue(zhCn.contains("\"tooltip.gt_shanhai.jei.quick_encode_recipe_type\""));
        assertTrue(zhCn.contains("\"message.gt_shanhai.jei.quick_encode.open_terminal\""));
        assertTrue(enUs.contains("\"tooltip.gt_shanhai.jei.quick_encode_pattern\""));
        assertTrue(enUs.contains("\"tooltip.gt_shanhai.jei.quick_encode_recipe_type\""));
        assertTrue(enUs.contains("\"message.gt_shanhai.jei.quick_encode.open_terminal\""));
    }

    @Test
    void packetIsServerBoundAndRejectsStaleMenus() throws Exception {
        String packet = Files.readString(PACKET);
        String network = Files.readString(NETWORK);

        assertTrue(packet.contains("player.containerMenu instanceof PatternEncodingTermMenu menu"));
        assertTrue(packet.contains("menu.containerId != packet.menuId"));
        assertTrue(packet.contains("!menu.stillValid(player)"));
        assertTrue(network.contains("JeiPatternQuickEncodeRequestPacket.class"));
        assertTrue(network.contains("JeiPatternQuickEncodeRequestPacket::handle"));
        assertTrue(network.indexOf("JeiPatternQuickEncodeRequestPacket.class")
                < network.indexOf("RecipeSyncPacket.init()"));
        int packetRegistration = network.indexOf("JeiPatternQuickEncodeRequestPacket.class");
        assertTrue(packetRegistration >= 0);
        assertTrue(network.substring(packetRegistration).contains("NetworkDirection.PLAY_TO_SERVER"));
    }

    @Test
    void blankPatternSlotAccessorIsAppliedAsCommonMixin() throws Exception {
        String accessor = Files.readString(ACCESSOR);
        String config = Files.readString(MIXIN_CONFIG);

        assertTrue(accessor.contains("@Mixin(value = PatternEncodingTermMenu.class, remap = false)"));
        assertTrue(accessor.contains("@Accessor(value = \"blankPatternSlot\", remap = false)"));
        assertTrue(config.contains("\"PatternEncodingTermMenuAccessor\""));
    }

    private static Path source(String... parts) {
        Path path = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai");
        for (String part : parts) path = path.resolve(part);
        return path;
    }

    private static Path lang(String file) {
        return Path.of("src", "main", "resources", "assets", "gt_shanhai", "lang", file);
    }
}
