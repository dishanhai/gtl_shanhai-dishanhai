package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopFeedbackPrefsSourceTest {

    private static final String ROOT = "src/main/java/com/dishanhai/gt_shanhai/";

    @Test
    void clientPrefsCarryReduceMotionAndUiSoundsWithDefaults() throws Exception {
        String s = Files.readString(Path.of(ROOT + "client/shop/ClientShopUiSettings.java"));
        assertTrue(s.contains("private static boolean reduceMotion = false;"), "减少动效默认关");
        assertTrue(s.contains("private static boolean uiSounds = true;"), "商店音效默认开");
        String reload = extractBlock(s, "public static synchronized void reload()");
        assertTrue(reload.contains("reduceMotion = false;") && reload.contains("uiSounds = true;"),
                "reload 先回到默认值，旧文件没有这两个键时保持默认");
        assertTrue(s.contains("root.has(\"reduceMotion\")") && s.contains("root.has(\"uiSounds\")"));
        assertTrue(s.contains("root.addProperty(\"reduceMotion\", reduceMotion)"));
        assertTrue(s.contains("root.addProperty(\"uiSounds\", uiSounds)"));
        assertTrue(s.contains("public static synchronized void setFeedbackPrefs("));
    }

    @Test
    void shopSoundsUseVanillaEventsBehindTheToggleAndThrottle() throws Exception {
        String s = Files.readString(Path.of(ROOT + "client/gui/shop/ShopUiSounds.java"));
        assertTrue(s.contains("SimpleSoundInstance.forUI("));
        assertTrue(s.contains("ClientShopUiSettings.uiSounds()"), "受商店音效开关控制");
        assertTrue(s.contains("UiSoundThrottle"), "连发连点要节流");
        assertTrue(s.contains("SoundEvents.UI_BUTTON_CLICK"));
        assertFalse(s.contains("createVariableRangeEvent"), "只用原版音效，不注册新声音");
        assertFalse(s.contains("new ResourceLocation("), "只用原版音效，不引用新资源");
        assertFalse(s.contains("GTDishanhaiMod.MOD_ID"), "只用原版音效，不引用模组资源");
    }

    private static String extractBlock(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertTrue(start >= 0, "缺少方法声明: " + declaration);
        int openBrace = source.indexOf('{', start);
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) {
                return source.substring(openBrace, i + 1);
            }
        }
        throw new AssertionError("方法体未闭合: " + declaration);
    }
}
