package com.dishanhai.gt_shanhai.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Style;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GuideMeFcsSupportTest {

    @Test
    void normalizedInlineNamesKeepTheirStylesWithoutVisibleControlTokens() {
        Component raw = Component.literal("前缀 ").withStyle(ChatFormatting.BOLD)
                .append(Component.literal("&^$magic-世线因果板").withStyle(ChatFormatting.ITALIC))
                .append(Component.literal(" 后缀"));
        Component clean = TextFormatParser.normalizeFcsComponent(raw);
        assertEquals("前缀 世线因果板 后缀", clean.getString());
        assertEquals("前缀 &^$magic-世线因果板 后缀", raw.getString());
        assertTrue(clean.getStyle().isBold());
        var sequence = clean.getVisualOrderText();
        StringBuilder visible = new StringBuilder();
        sequence.accept((index, style, codepoint) -> {
            visible.appendCodePoint(codepoint);
            if (codepoint == '世') {
                assertEquals("fcs:magic|^", style.getInsertion());
                assertTrue(style.isItalic());
                assertTrue(style.isBold());
            }
            if (codepoint == '前' || codepoint == '后') assertNull(style.getInsertion());
            return true;
        });
        assertEquals("前缀 世线因果板 后缀", visible.toString());
    }

    @Test
    void normalizedRewardNamesSupportAllShownPalettesAndLeavePlainTextAlone() {
        for (String theme : new String[]{"gray", "green", "water", "golden", "magic", "aurora", "crimson", "electric", "neon", "ultimateRainbow"}) {
            Component clean = TextFormatParser.normalizeFcsComponent(Component.literal("×64 &$" + theme + "-世线板"));
            assertEquals("×64 世线板", clean.getString());
            clean.getVisualOrderText().accept((index, style, codepoint) -> {
                if (codepoint == '世') assertEquals("fcs:" + theme, style.getInsertion());
                if (codepoint == '×') assertNull(style.getInsertion());
                return true;
            });
        }
        Component plain = Component.literal("R&D - 普通名称 😀");
        assertTrue(plain == TextFormatParser.normalizeFcsComponent(plain));
    }

    @Test
    void widthClippingCountsVisibleCharactersAndRetainsThePalette() {
        Component clean = TextFormatParser.normalizeFcsComponent(Component.literal("&$ultimateRainbow-世线创世板"));
        StringSplitter splitter = new StringSplitter((codepoint, style) -> 1f);
        var clipped = splitter.headByWidth(clean, 3, Style.EMPTY);
        assertEquals("世线创", clipped.getString());
        clipped.visit((style, text) -> {
            if (!text.isEmpty()) assertEquals("fcs:ultimateRainbow", style.getInsertion());
            return java.util.Optional.empty();
        }, Style.EMPTY);
        assertEquals("", splitter.headByWidth(clean, 0, Style.EMPTY).getString());
    }

    @Test
    void translatedItemNamesAlsoNormalizeWithoutChangingTheSource() {
        Component original = Component.translatableWithFallback("test.shanhai.missing", "&$water-世线织络板");
        Component clean = TextFormatParser.normalizeFcsComponent(original);
        assertEquals("世线织络板", clean.getString());
        assertEquals("&$water-世线织络板", original.getString());
        clean.getVisualOrderText().accept((index, style, codepoint) -> {
            assertEquals("fcs:water", style.getInsertion());
            return true;
        });
    }

    @BeforeEach
    void setUp() {
        TextFormatParser.FCS_PREFIX_MAP.clear();
    }

    @Test
    void stripsFcsPrefixAndRemembersRawMapping() {
        var result = GuideMeFcsSupport.prepareLayoutText("&$golden-暗星物质模块");

        assertTrue(result.handled());
        assertEquals("暗星物质模块", result.layoutText());
        assertEquals("&$golden-暗星物质模块", TextFormatParser.restoreRawFcs("暗星物质模块"));
    }

    @Test
    void keepsVisiblePrefixBeforeFcsName() {
        var result = GuideMeFcsSupport.prepareLayoutText("64 &$magic-虚像物质模块");

        assertTrue(result.handled());
        assertEquals("64 虚像物质模块", result.layoutText());
        assertEquals("64 &$magic-虚像物质模块", TextFormatParser.restoreRawFcs("64 虚像物质模块"));
    }

    @Test
    void leavesPlainTextUntouched() {
        var result = GuideMeFcsSupport.prepareLayoutText("物质重组模块");

        assertFalse(result.handled());
        assertEquals("物质重组模块", result.layoutText());
        assertNull(TextFormatParser.restoreRawFcs("物质重组模块"));
    }

    @Test
    void ignoresAmpersandThatIsNotFcsPrefix() {
        var result = GuideMeFcsSupport.prepareLayoutText("R&D 模块");

        assertFalse(result.handled());
        assertEquals("R&D 模块", result.layoutText());
        assertNull(TextFormatParser.restoreRawFcs("R&D 模块"));
    }
}
