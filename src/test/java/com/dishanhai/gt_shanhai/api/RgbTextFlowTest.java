package com.dishanhai.gt_shanhai.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RgbTextFlowTest {

    @BeforeEach
    void setUp() {
        DShanhaiTextUtil.setSpeed(80);
        DShanhaiTextUtil.setDirection(1);
        DShanhaiTextUtil.lockTimeAt(0);
    }

    @AfterEach
    void tearDown() {
        DShanhaiTextUtil.unlockTime();
        DShanhaiTextUtil.setSpeed(80);
        DShanhaiTextUtil.setDirection(1);
    }

    @Test
    void allStyleWalksHeadlinePresetsInOrder() {
        int[][] presets = DShanhaiTextUtil.flowPresets();
        assertTrue(presets.length >= 30, "标题和正文色板都应被统合进全元素");
        assertEquals(0xFF4444, presets[0][0]);
        assertEquals(0xFF3333, presets[1][0]);

        int[] all = DShanhaiStyleRegistry.getRGB("全");
        assertSame(DShanhaiTextUtil.PALETTE_ALL, all);
        assertSame(all, DShanhaiStyleRegistry.getRGB("all"));
        assertSame(all, DShanhaiStyleRegistry.getRGB("quan"));

        assertEquals(presets[0][0], DShanhaiTextUtil.getDynamicColor(all, 0, 4, false));
        DShanhaiTextUtil.lockTimeAt(DShanhaiTextUtil.ALL_PRESET_MS);
        assertEquals(presets[1][0], DShanhaiTextUtil.getDynamicColor(all, 0, 4, false));
    }

    @Test
    void allPresetBoundaryDoesNotJump() {
        int[] all = DShanhaiTextUtil.PALETTE_ALL;
        DShanhaiTextUtil.lockTimeAt(DShanhaiTextUtil.ALL_PRESET_MS - 1);
        int before = DShanhaiTextUtil.getDynamicColor(all, 0, 4, false);
        DShanhaiTextUtil.lockTimeAt(DShanhaiTextUtil.ALL_PRESET_MS);
        int after = DShanhaiTextUtil.getDynamicColor(all, 0, 4, false);
        assertTrue(channelDelta(before, after) <= 48, "全元素换段应已淡入下一段，实际差 " + channelDelta(before, after));
    }

    @Test
    void plainPaletteInterpolatesInsteadOfStepping() {
        int[] fire = DShanhaiStyleRegistry.getRGB("fire");
        assertEquals(0x992200, DShanhaiTextUtil.getDynamicColor(fire, 0, 4, false));
        DShanhaiTextUtil.lockTimeAt(40);
        int mid = DShanhaiTextUtil.getDynamicColor(fire, 0, 4, false);
        assertNotEquals(fire[0], mid);
        assertNotEquals(fire[1], mid);
        assertTrue(between(fire[0], fire[1], mid), "中点应落在相邻停靠点之间");
    }

    @Test
    void randomUsesTheSamePresetPool() {
        int[][] presets = DShanhaiTextUtil.flowPresets();
        int[] random = DShanhaiStyleRegistry.getRGB("随机");
        assertSame(DShanhaiTextUtil.PALETTE_RANDOM, random);
        assertSame(random, DShanhaiStyleRegistry.getRGB("random"));

        int color = DShanhaiTextUtil.getDynamicColor(random, 0, 4, false);
        int picked = presets[DShanhaiTextUtil.pickPreset(0, presets.length)][0];
        assertEquals(picked, color);

        DShanhaiTextUtil.lockTimeAt(1007);
        int before = DShanhaiTextUtil.getDynamicColor(random, 0, 4, false);
        DShanhaiTextUtil.lockTimeAt(1008);
        int after = DShanhaiTextUtil.getDynamicColor(random, 0, 4, false);
        assertTrue(channelDelta(before, after) <= 24, "随机切换起点不应跳色");
    }

    @Test
    void fullRandomIsPerCharacterAndContinuous() {
        int[] full = DShanhaiStyleRegistry.getRGB("全随机");
        assertSame(DShanhaiTextUtil.PALETTE_FULL_RANDOM, full);
        assertSame(full, DShanhaiStyleRegistry.getRGB("完全随机"));
        assertSame(full, DShanhaiStyleRegistry.getRGB("fullrandom"));

        int first = DShanhaiTextUtil.getDynamicColor(full, 0, 4, false);
        int second = DShanhaiTextUtil.getDynamicColor(full, 1, 4, false);
        assertNotEquals(first, second);
        assertTrue(bright(first) >= 200);
        assertTrue(bright(second) >= 200);

        DShanhaiTextUtil.lockTimeAt(40);
        int later = DShanhaiTextUtil.getDynamicColor(full, 0, 4, false);
        assertTrue(channelDelta(first, later) <= 40, "完全随机应在关键帧之间过渡");
        assertEquals(later, DShanhaiTextUtil.getDynamicColor(full, 0, 4, false));
    }

    @Test
    void chineseAliasesShareTheEnglishPalette() {
        assertSame(DShanhaiStyleRegistry.getRGB("fire"), DShanhaiStyleRegistry.getRGB("火"));
        assertSame(DShanhaiStyleRegistry.getRGB("fire"), DShanhaiStyleRegistry.getRGB("火焰"));
        assertSame(DShanhaiStyleRegistry.getRGB("golden"), DShanhaiStyleRegistry.getRGB("金色"));
        assertSame(DShanhaiStyleRegistry.getRGB("ultimate"), DShanhaiStyleRegistry.getRGB("终极彩虹"));
        assertSame(DShanhaiStyleRegistry.getRGB("body_silver"), DShanhaiStyleRegistry.getRGB("正文银"));
        assertSame(DShanhaiStyleRegistry.getRGB("body_silver"), DShanhaiStyleRegistry.getRGB("银"));
        assertTrue(DShanhaiStyleRegistry.isBody("正文银"));
        assertTrue(DShanhaiStyleRegistry.isObfuscatedStyle("混淆"));
        assertTrue(DShanhaiStyleRegistry.isObfuscatedStyle("混淆彩虹"));

        TextFormatParser.ParseResult fire = TextFormatParser.parseFormatting("&$火焰-测试");
        assertEquals("火焰", fire.flags.gradientTheme);
        assertEquals("测试", fire.cleanText);
        assertSame(DShanhaiStyleRegistry.getRGB("fire"), fire.flags.palette);

        TextFormatParser.ParseResult ultimate = TextFormatParser.parseFormatting("&$终极彩虹字");
        assertEquals("终极彩虹", ultimate.flags.gradientTheme);
        assertEquals("字", ultimate.cleanText);

        TextFormatParser.ParseResult electric = TextFormatParser.parseFormatting("&$电光测试");
        assertEquals("电光", electric.flags.gradientTheme);
        assertEquals("测试", electric.cleanText);

        TextFormatParser.ParseResult body = TextFormatParser.parseFormatting("&$正文金色正文");
        assertEquals("正文金色", body.flags.gradientTheme);
        assertEquals("正文", body.cleanText);
        assertTrue(body.flags.palette == DShanhaiStyleRegistry.getRGB("body_golden"));
    }

    @Test
    void fcsPrefixPicksTheLongestMetaName() {
        TextFormatParser.ParseResult all = TextFormatParser.parseFormatting("&$全-山海");
        assertEquals("山海", all.cleanText);
        assertEquals("全", all.flags.gradientTheme);
        assertSame(DShanhaiTextUtil.PALETTE_ALL, all.flags.palette);

        TextFormatParser.ParseResult partial = TextFormatParser.parseFormatting("&$全随机-山海");
        assertEquals("全随机", partial.flags.gradientTheme);
        assertSame(DShanhaiTextUtil.PALETTE_FULL_RANDOM, partial.flags.palette);

        TextFormatParser.ParseResult complete = TextFormatParser.parseFormatting("&$完全随机山海");
        assertEquals("完全随机", complete.flags.gradientTheme);
        assertEquals("山海", complete.cleanText);
        assertSame(DShanhaiTextUtil.PALETTE_FULL_RANDOM, complete.flags.palette);
    }

    private static int channelDelta(int a, int b) {
        int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
        int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
        int db = Math.abs((a & 0xFF) - (b & 0xFF));
        return Math.max(dr, Math.max(dg, db));
    }

    private static int bright(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return Math.max(r, Math.max(g, b));
    }

    private static boolean between(int a, int b, int mid) {
        return inside((a >> 16) & 0xFF, (b >> 16) & 0xFF, (mid >> 16) & 0xFF)
                && inside((a >> 8) & 0xFF, (b >> 8) & 0xFF, (mid >> 8) & 0xFF)
                && inside(a & 0xFF, b & 0xFF, mid & 0xFF);
    }

    private static boolean inside(int a, int b, int mid) {
        int lo = Math.min(a, b) - 1;
        int hi = Math.max(a, b) + 1;
        return mid >= lo && mid <= hi;
    }
}
