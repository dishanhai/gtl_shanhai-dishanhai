package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：移植排序與原因標籤時保留完整分頁、槽位對齊與未知讀數。 */
class GuixuArIntegrateIncrementTest {
    @Test void runningUsesProgressAndKeepsTheOutputColumnUnchanged() {
        var state = new HostFxGlassArModel.State();
        state.moduleSlots = new int[]{0};
        state.moduleNames = new String[]{"模組"};
        state.modOutputs = new String[][]{{"很長的輸出物品名稱與數量"}};
        state.modReason = new byte[]{0};
        String withoutLabel = HostFxGlassArModel.build(state).texts.stream()
                .filter(t -> t.slot == 206).findFirst().orElseThrow().text;
        state.modReason = new byte[]{1};
        var texts = HostFxGlassArModel.build(state).texts;
        assertFalse(texts.stream().anyMatch(t -> t.slot == 207), "運行狀態由進度與排序表示");
        assertEquals(withoutLabel, texts.stream().filter(t -> t.slot == 206).findFirst().orElseThrow().text);
    }

    @Test void reorderedSlotsKeepTheirNamesAndAllFortyRemainReachable() throws Exception {
        var state = new HostFxGlassArModel.State();
        state.moduleSlots = IntStream.range(0, 40).toArray();
        state.moduleNames = IntStream.range(0, 40).mapToObj(i -> "模組" + i).toArray(String[]::new);
        int[] reversed = IntStream.range(0, 40).map(i -> 39 - i).toArray();
        assertDoesNotThrow(() -> state.getClass().getField("moduleOrder").set(state, reversed));
        var seen = new java.util.ArrayList<String>();
        for (int page = 0; page < 6; page++) {
            state.modulePageTimeMillis = page * 10_000L;
            var texts = HostFxGlassArModel.build(state).texts;
            seen.addAll(texts.stream().filter(t -> t.zone == HostFxGlassArModel.Z_MODLIST
                    && t.slot >= 200 && t.slot % 10 == 0).map(t -> t.text).toList());
            int first = 39 - page * 7;
            assertTrue(texts.stream().anyMatch(t -> t.slot == 201 && t.text.equals("模組" + first)));
        }
        assertEquals(Arrays.stream(reversed).mapToObj(String::valueOf).toList(), seen);
    }

    @Test void reasonTakesPrecedenceOverStalledProgressAndHostSwitchClearsHistory() throws Exception {
        Class<?> type = assertDoesNotThrow(() -> Class.forName(
                "com.dishanhai.gt_shanhai.client.renderer.hostfx.HostFxGlassArSort$Tracker"));
        var cache = new HostFxReadingCache();
        cache.selectHost(new Object());
        var field = assertDoesNotThrow(() -> HostFxReadingCache.class.getDeclaredField("moduleSort"));
        field.setAccessible(true);
        Object tracker = field.get(cache);
        var observe = type.getMethod("observe", long.class, int[].class, byte[].class, int[].class, byte[].class);
        var order = type.getMethod("order", int[].class);
        int[] slots = {0, 1};
        observe.invoke(tracker, 10L, slots, new byte[]{80, 0}, new int[]{10, 10}, new byte[]{2, 1});
        assertArrayEquals(new int[]{1, 0}, (int[]) order.invoke(tracker, (Object) slots));
        cache.selectHost(new Object());
        assertArrayEquals(slots, (int[]) order.invoke(field.get(cache), (Object) slots));
    }

    @Test void knownNoOutputAndUnknownSnapshotUseDifferentText() {
        var state = new HostFxGlassArModel.State();
        state.hostOutputTotal = 0;
        state.hostOutputs = new String[0];
        assertTrue(HostFxGlassArModel.build(state).texts.stream()
                .anyMatch(t -> t.zone == HostFxGlassArModel.Z_HOST && t.text.equals("物品① 无")));
        state.hostOutputs = null;
        assertTrue(HostFxGlassArModel.build(state).texts.stream()
                .anyMatch(t -> t.zone == HostFxGlassArModel.Z_HOST && t.text.equals("物品① 读不到")));
    }

    @Test void statusLabelUsesMeasuredWidthAndStaysWithinThePanel() throws Exception {
        var state = new HostFxGlassArModel.State();
        state.moduleSlots = new int[]{0};
        state.moduleNames = new String[]{"模組"};
        assertDoesNotThrow(() -> state.getClass().getField("modReason").set(state, new byte[]{3}));
        assertDoesNotThrow(() -> state.getClass().getField("moduleReasonWidths").set(state, new int[]{0, 0, 0, 70, 0}));
        var label = HostFxGlassArModel.build(state).texts.stream()
                .filter(t -> t.zone == HostFxGlassArModel.Z_MODLIST && t.slot == 207).findFirst().orElseThrow();
        assertEquals("输出满", label.text);
        float right = HostFxGlassArPanel.px(HostFxGlassArPanel.ZONE_MODLIST.u
                + HostFxGlassArPanel.ZONE_MODLIST.w - HostFxGlassArPanel.ZONE_PAD);
        assertEquals(right, label.x + 70, 0.001F);
    }

    @Test void missingAndDuplicateOrdersFallBackToTheCompleteOriginalList() throws Exception {
        var state = new HostFxGlassArModel.State();
        state.moduleSlots = new int[]{3, 7, 11};
        for (int[] invalid : new int[][]{{11, 3}, {11, 11, 3}}) {
            state.getClass().getField("moduleOrder").set(state, invalid);
            assertEquals(java.util.List.of("3", "7", "11"), HostFxGlassArModel.build(state).texts.stream()
                    .filter(t -> t.zone == HostFxGlassArModel.Z_MODLIST && t.slot >= 200 && t.slot % 10 == 0)
                    .map(t -> t.text).toList());
        }
    }

    @Test void sortingTracksStartAndStopEdgesWithoutInventingUnknownHistory() throws Exception {
        Class<?> type = Class.forName("com.dishanhai.gt_shanhai.client.renderer.hostfx.HostFxGlassArSort$Tracker");
        Object tracker = type.getConstructor().newInstance();
        var observe = type.getMethod("observe", long.class, int[].class, byte[].class, int[].class, byte[].class);
        var order = type.getMethod("order", int[].class);
        int[] slots = {0, 1, 2};
        observe.invoke(tracker, 10L, slots, null, null, new byte[]{1, 0, 0});
        observe.invoke(tracker, 20L, slots, null, null, new byte[]{1, 1, 0});
        assertArrayEquals(new int[]{1, 0, 2}, (int[]) order.invoke(tracker, (Object) slots));
        observe.invoke(tracker, 21L, slots, null, null, new byte[]{-1, -1, -1});
        assertArrayEquals(new int[]{1, 0, 2}, (int[]) order.invoke(tracker, (Object) slots));
        observe.invoke(tracker, 22L, slots, null, null, new byte[]{1, 2, 0});
        assertArrayEquals(new int[]{0, 1, 2}, (int[]) order.invoke(tracker, (Object) slots));
        observe.invoke(tracker, 23L, new int[]{2}, null, null, new byte[]{0, 0, 0});
        observe.invoke(tracker, 24L, slots, null, null, new byte[]{0, 0, 0});
        assertArrayEquals(slots, (int[]) order.invoke(tracker, (Object) slots));
    }
}
