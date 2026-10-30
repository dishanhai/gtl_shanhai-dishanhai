package com.dishanhai.gt_shanhai.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        DShanhaiTextUtil.lockTimeAt(presetBoundary(0, 1, DShanhaiTextUtil.ALL_PRESET_MS));
        assertTrue(channelDelta(presets[1][0],
                DShanhaiTextUtil.getDynamicColor(all, 0, 4, false)) <= 2);
    }

    @Test
    void allPresetBoundaryDoesNotJump() {
        int[] all = DShanhaiTextUtil.PALETTE_ALL;
        long boundary = presetBoundary(0, 1, DShanhaiTextUtil.ALL_PRESET_MS);
        DShanhaiTextUtil.lockTimeAt(boundary - 1);
        int before = DShanhaiTextUtil.getDynamicColor(all, 0, 4, false);
        DShanhaiTextUtil.lockTimeAt(boundary);
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

        long firstWindow = presetBoundary(0, 1, DShanhaiTextUtil.RANDOM_WINDOW_MS);
        long fadeStart = Math.round(firstWindow * 0.72);
        DShanhaiTextUtil.lockTimeAt(fadeStart - 1);
        int before = DShanhaiTextUtil.getDynamicColor(random, 0, 4, false);
        DShanhaiTextUtil.lockTimeAt(fadeStart);
        int after = DShanhaiTextUtil.getDynamicColor(random, 0, 4, false);
        assertTrue(channelDelta(before, after) <= 24, "随机切换起点不应跳色");
    }

    @Test
    void randomNeverRepeatsAPresetWithinOneCycle() {
        int count = DShanhaiTextUtil.flowPresets().length;
        for (int cycle = 0; cycle < 100; cycle++) {
            java.util.Set<Integer> used = new java.util.HashSet<>();
            for (int index = 0; index < count; index++) {
                int picked = DShanhaiTextUtil.pickPreset((long) cycle * count + index, count);
                assertTrue(used.add(picked), "同一輪重複抽取色板：" + picked + "，輪次 " + cycle);
            }
            assertEquals(count, used.size());
        }
    }

    @Test
    void randomRenderingFollowsTheBagEvenAfterLargeTimeJumps() {
        int[][] presets = DShanhaiTextUtil.flowPresets();
        int count = presets.length;
        java.util.Set<String> orders = new java.util.HashSet<>();
        for (long cycle : new long[]{0, 1, 2, 30_000_000, 0}) {
            int[] order = new int[count];
            for (int index = 0; index < count; index++) {
                order[index] = DShanhaiTextUtil.pickPreset(cycle * count + index, count);
                long time = presetBoundary(cycle, index, DShanhaiTextUtil.RANDOM_WINDOW_MS) + 1;
                DShanhaiTextUtil.lockTimeAt(time);
                for (int direction : new int[]{1, -1}) {
                    DShanhaiTextUtil.setDirection(direction);
                    for (int character : new int[]{0, 3}) {
                        int expected = DShanhaiTextUtil.getDynamicColor(presets[order[index]], character, 4, false);
                        assertEquals(expected, DShanhaiTextUtil.getDynamicColor(
                                DShanhaiTextUtil.PALETTE_RANDOM, character, 4, false));
                    }
                }
            }
            orders.add(java.util.Arrays.toString(order));
        }
        assertTrue(orders.size() > 1, "新一輪必須重新洗牌");
    }

    @Test
    void randomDoesNotRepeatAcrossCycleBoundaries() {
        for (int count : new int[]{1, 2, 3, DShanhaiTextUtil.flowPresets().length}) {
            for (int cycle = 1; cycle < 100; cycle++) {
                long slot = (long) cycle * count;
                int previous = DShanhaiTextUtil.pickPreset(slot - 1, count);
                int next = DShanhaiTextUtil.pickPreset(slot, count);
                if (count == 1) assertEquals(0, next);
                else assertNotEquals(previous, next, "輪次交界不得連續使用同一色板");
            }
        }
    }

    @Test
    void cycleDurationChangesWithoutSkippingOrReorderingPresets() {
        int count = DShanhaiTextUtil.flowPresets().length;
        java.util.Set<Long> durations = new java.util.HashSet<>();
        int[] all = DShanhaiTextUtil.PALETTE_ALL;
        int[][] presets = DShanhaiTextUtil.flowPresets();
        for (int cycle = 0; cycle < 20; cycle++) {
            double start = DShanhaiTextUtil.presetCycleStart(cycle, count);
            double end = DShanhaiTextUtil.presetCycleStart(cycle + 1, count);
            double duration = end - start;
            assertTrue(duration >= count * 0.75 && duration <= count * 1.25);
            durations.add(Math.round(duration * 1000));
            for (int index = 0; index < count; index++) {
                long time = presetBoundary(cycle, index, DShanhaiTextUtil.ALL_PRESET_MS);
                DShanhaiTextUtil.lockTimeAt(time);
                assertTrue(channelDelta(presets[index][0],
                        DShanhaiTextUtil.getDynamicColor(all, 0, 4, false)) <= 4,
                        "全色板在隨機輪長下仍須完整依序遍歷");
            }
        }
        assertTrue(durations.size() > 1, "各輪的循環時長必須隨機變化");
    }

    @Test
    void metaTransitionsStayContinuousIncludingCycleBoundariesAndBodySpeed() {
        int count = DShanhaiTextUtil.flowPresets().length;
        for (int speed : new int[]{40, 80, 160}) {
            DShanhaiTextUtil.setSpeed(speed);
            for (boolean body : new boolean[]{false, true}) {
                for (int[] palette : new int[][]{DShanhaiTextUtil.PALETTE_ALL, DShanhaiTextUtil.PALETTE_RANDOM}) {
                    boolean all = palette == DShanhaiTextUtil.PALETTE_ALL;
                    long window = (all ? DShanhaiTextUtil.ALL_PRESET_MS * (body ? 2 : 1)
                            : DShanhaiTextUtil.RANDOM_WINDOW_MS + (body ? 800 : 0)) * speed / 80;
                    for (int cycle = 0; cycle < 3; cycle++) {
                        for (int index = 1; index <= count; index++) {
                            long time = presetBoundary(cycle, index, window);
                            for (int character : new int[]{0, 3}) {
                                DShanhaiTextUtil.lockTimeAt(time - 1);
                                int before = DShanhaiTextUtil.getDynamicColor(palette, character, 4, body);
                                DShanhaiTextUtil.lockTimeAt(time);
                                int after = DShanhaiTextUtil.getDynamicColor(palette, character, 4, body);
                                assertTrue(channelDelta(before, after) <= 48,
                                        "切換、跨輪及正文速度下淡化必須連續");
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void shanhaiPalettesHaveAliasesAndJoinBothMetaPalettePools() {
        String[][] names = {
                {"shanhai_dawn", "山海朝霞"}, {"shanhai_mist", "山海青岚"},
                {"shanhai_galaxy", "山海星河"}, {"shanhai_prism", "山海琉彩"}
        };
        java.util.Set<int[]> identities = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        int[][] presets = DShanhaiTextUtil.flowPresets();
        for (String[] name : names) {
            var definition = DShanhaiStyleRegistry.get(name[0]);
            assertTrue(definition != null, "必須真正註冊新色板：" + name[0]);
            assertSame(definition.rgb(), DShanhaiStyleRegistry.getRGB(name[1]));
            assertTrue(identities.add(definition.rgb()), "各新色板須有獨立顏色");
            assertTrue(java.util.Arrays.stream(presets).anyMatch(preset -> preset == definition.rgb()),
                    "新色板須加入全／隨機共用池");
            var parsed = TextFormatParser.parseFormatting("&$" + name[1] + "-測試");
            assertEquals("測試", parsed.cleanText);
            assertSame(definition.rgb(), parsed.flags.palette);
            DShanhaiTextUtil.lockTimeAt(0);
            int first = DShanhaiTextUtil.getDynamicColor(definition.rgb(), 0, 4, false);
            DShanhaiTextUtil.lockTimeAt(40);
            int later = DShanhaiTextUtil.getDynamicColor(definition.rgb(), 0, 4, false);
            assertNotEquals(first, later, "新色板須隨時間流動：" + name[0]);
        }
    }

    @Test
    void randomAloneReceivesAutomaticCharacterEffects() {
        for (int index = 0; index < DShanhaiTextUtil.AllTextEffect.values().length; index++) {
            long start = presetBoundary(0, index, DShanhaiTextUtil.RANDOM_WINDOW_MS);
            long end = presetBoundary(0, index + 1, DShanhaiTextUtil.RANDOM_WINDOW_MS);
            DShanhaiTextUtil.lockTimeAt(Math.round(start + (end - start) * 0.4));
            assertNull(DShanhaiTextUtil.allTextFrame(DShanhaiTextUtil.PALETTE_ALL, false));
            var frame = DShanhaiTextUtil.allTextFrame(DShanhaiTextUtil.PALETTE_RANDOM, false);
            assertEquals(DShanhaiTextUtil.allTextEffect(index), frame.effect());
            assertEquals(1.0f, frame.strength(), 0.001f);
        }
        assertNull(DShanhaiTextUtil.allTextFrame(DShanhaiTextUtil.PALETTE_FULL_RANDOM, false));
        assertEquals("fcs:random", ShanhaiTextAPI.random("山海").getStyle().getInsertion());
        assertEquals("fcs:随机|~", TextFormatParser.parseClean("&~$随机-山海").getStyle().getInsertion());
    }

    @Test
    void randomCanActuallyJumpItsAnimationPhase() {
        int count = DShanhaiTextUtil.flowPresets().length;
        int biggestJump = 0;
        for (int cycle = 0; cycle < 20; cycle++) {
            for (int index = 0; index < count; index++) {
                long start = presetBoundary(cycle, index, DShanhaiTextUtil.RANDOM_WINDOW_MS);
                long end = presetBoundary(cycle, index + 1, DShanhaiTextUtil.RANDOM_WINDOW_MS);
                for (int step = 1; step < 6; step++) {
                    long time = Math.round(start + (end - start) * step / 8.0);
                    DShanhaiTextUtil.lockTimeAt(time - 1);
                    int before = DShanhaiTextUtil.getDynamicColor(DShanhaiTextUtil.PALETTE_RANDOM, 0, 4, false);
                    DShanhaiTextUtil.lockTimeAt(time + 1);
                    int after = DShanhaiTextUtil.getDynamicColor(DShanhaiTextUtil.PALETTE_RANDOM, 0, 4, false);
                    biggestJump = Math.max(biggestJump, channelDelta(before, after));
                }
            }
        }
        assertTrue(biggestJump > 80, "隨機模式須實際抽到大幅跳時效果");
    }

    @Test
    void allEffectBagIncludesEveryAnimationOnce() {
        int count = DShanhaiTextUtil.AllTextEffect.values().length;
        java.util.Set<DShanhaiTextUtil.AllTextEffect> used = java.util.EnumSet.noneOf(DShanhaiTextUtil.AllTextEffect.class);
        for (int index = 0; index < count; index++) {
            assertTrue(used.add(DShanhaiTextUtil.allTextEffect(index)), "效果池一輪內不得重複");
        }
        assertEquals(count, used.size());
        assertTrue(used.contains(DShanhaiTextUtil.AllTextEffect.REVERSE));
        assertTrue(used.contains(DShanhaiTextUtil.AllTextEffect.TIME_JUMP));
    }

    @Test
    void randomFramesShareEffectsAndKeepTheirTimeLocal() {
        int effectCount = DShanhaiTextUtil.AllTextEffect.values().length;
        int[] fire = DShanhaiStyleRegistry.getRGB("fire");
        for (int index = 0; index < effectCount; index++) {
            long start = presetBoundary(0, index, DShanhaiTextUtil.RANDOM_WINDOW_MS);
            long end = presetBoundary(0, index + 1, DShanhaiTextUtil.RANDOM_WINDOW_MS);
            long time = Math.round(start + (end - start) * 0.4);
            DShanhaiTextUtil.lockTimeAt(time);
            int original = DShanhaiTextUtil.getDynamicColor(fire, 3, 4, false);
            var frame = DShanhaiTextUtil.allTextFrame(DShanhaiTextUtil.PALETTE_RANDOM, false);
            assertEquals(DShanhaiTextUtil.allTextEffect(index), frame.effect());
            assertEquals(1.0f, frame.strength(), 0.001f);
            assertEquals(frame, DShanhaiTextUtil.allTextFrame(DShanhaiTextUtil.PALETTE_RANDOM, false));
            switch (frame.effect()) {
                case REVERSE -> assertEquals(-time, frame.time());
                case TIME_JUMP -> assertTrue(frame.time() >= time + 90_000);
                default -> assertEquals(time, frame.time());
            }
            assertEquals(original, DShanhaiTextUtil.getDynamicColor(fire, 3, 4, false),
                    "隨機效果不得修改其他色板的時間或方向");
        }
        assertNull(DShanhaiTextUtil.allTextFrame(DShanhaiTextUtil.PALETTE_ALL, false));
        assertNull(DShanhaiTextUtil.allTextFrame(fire, false));
    }

    @Test
    void reverseAndTimeJumpChangeTheActualPalettePhase() {
        int[] palette = {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF};
        DShanhaiTextUtil.lockTimeAt(120);
        int forward = DShanhaiTextUtil.sampleRandomEffect(palette, 0, 4, false, 120, 0.4, 40,
                DShanhaiTextUtil.AllTextEffect.FLOW, 1);
        int reverse = DShanhaiTextUtil.sampleRandomEffect(palette, 0, 4, false, 120, 0.4, 40,
                DShanhaiTextUtil.AllTextEffect.REVERSE, 1);
        int jumped = DShanhaiTextUtil.sampleRandomEffect(palette, 0, 4, false, 120, 0.4, 40,
                DShanhaiTextUtil.AllTextEffect.TIME_JUMP, 1);
        int earlier = DShanhaiTextUtil.sampleRandomEffect(palette, 0, 4, false, 40, 0.4, 0,
                DShanhaiTextUtil.AllTextEffect.FLOW, 1);
        int reversedNext = DShanhaiTextUtil.sampleRandomEffect(palette, 0, 4, false, 121, 0.4, 41,
                DShanhaiTextUtil.AllTextEffect.REVERSE, 1);
        int earlierNext = DShanhaiTextUtil.sampleRandomEffect(palette, 0, 4, false, 39, 0.4, 0,
                DShanhaiTextUtil.AllTextEffect.FLOW, 1);
        assertNotEquals(forward, reverse, "反向須改變實際色板相位");
        assertNotEquals(forward, jumped, "跳時須改變實際色板相位");
        assertEquals(earlier, reverse);
        assertEquals(earlierNext, reversedNext, "反向時時間前進，色板應向相反方向流動");
    }

    @Test
    void allEffectsFadeOutAndPreserveFcsMarkers() {
        for (var effect : DShanhaiTextUtil.AllTextEffect.values()) {
            var frame = new DShanhaiTextUtil.AllTextFrame(effect, 12345, 0);
            assertNull(TextFormatParser.calcAllEffectOffset(frame, 3));
            assertEquals(0x224466, TextFormatParser.applyAllEffectColor(0x224466, frame, 3, 4));
        }
        var frame = new DShanhaiTextUtil.AllTextFrame(DShanhaiTextUtil.AllTextEffect.WAVE_Y, 12345, 1);
        assertEquals(TextFormatParser.calcWaveY(3, 12345), TextFormatParser.calcAllEffectOffset(frame, 3)[1]);
        var breathe = new DShanhaiTextUtil.AllTextFrame(DShanhaiTextUtil.AllTextEffect.BREATHE, 12345, 1);
        assertEquals(TextFormatParser.applyBreatheColor(0x224466, 3, 12345),
                TextFormatParser.applyAllEffectColor(0x224466, breathe, 3, 4));

        var api = ShanhaiTextAPI.all("山海");
        assertEquals("山海", api.getString());
        assertEquals("fcs:all", api.getStyle().getInsertion());
        var parsed = TextFormatParser.parseClean("&~$全-山海");
        assertEquals("山海", parsed.getString());
        assertEquals("fcs:全|~", parsed.getStyle().getInsertion());
    }

    @Test
    void nestedAllMarkersAreDetectedWithoutChangingPlainOrUniformRuns() {
        var plain = net.minecraft.network.chat.Style.EMPTY;
        var all = ShanhaiTextAPI.all("山海").getStyle();
        var scan = TextFormatParser.createFcsComponent("^", "掃光", "all").getStyle();
        assertTrue(TextFormatParser.hasMixedFcsInsertions(java.util.List.of(plain, all, all, plain)));
        assertTrue(TextFormatParser.hasMixedFcsInsertions(java.util.List.of(all, scan)));
        assertFalse(TextFormatParser.hasMixedFcsInsertions(java.util.List.of(all, all)));
        assertFalse(TextFormatParser.hasMixedFcsInsertions(java.util.List.of(plain, plain.withColor(0x123456))));
        assertFalse(TextFormatParser.hasMixedFcsInsertions(java.util.List.of()));
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

    private static long presetBoundary(long cycle, int index, long window) {
        int count = DShanhaiTextUtil.flowPresets().length;
        double start = DShanhaiTextUtil.presetCycleStart(cycle, count);
        double end = DShanhaiTextUtil.presetCycleStart(cycle + 1, count);
        return Math.round((start + (end - start) * index / count) * window);
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
