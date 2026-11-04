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
        assertFalse(texts.stream().anyMatch(t -> t.slot == 207), "運行狀態由進度表示，不影響槽號排序");
        assertEquals(withoutLabel, texts.stream().filter(t -> t.slot == 206).findFirst().orElseThrow().text);
    }

    @Test void shuffledSlotsSortByIdAndKeepTheirNamesAcrossAllFortySlots() {
        var state = new HostFxGlassArModel.State();
        int[] reversed = IntStream.range(0, 40).map(i -> 39 - i).toArray();
        state.moduleSlots = reversed;
        state.moduleNames = Arrays.stream(reversed).mapToObj(i -> "模組" + i).toArray(String[]::new);
        var seen = new java.util.ArrayList<String>();
        for (int page = 0; page < 6; page++) {
            state.modulePageTimeMillis = page * 10_000L;
            var texts = HostFxGlassArModel.build(state).texts;
            seen.addAll(texts.stream().filter(t -> t.zone == HostFxGlassArModel.Z_MODLIST
                    && t.slot >= 200 && t.slot % 10 == 0).map(t -> t.text).toList());
            int first = page * 7;
            assertTrue(texts.stream().anyMatch(t -> t.slot == 201 && t.text.equals("模組" + first)));
        }
        assertEquals(IntStream.range(0, 40).mapToObj(String::valueOf).toList(), seen);
        assertArrayEquals(IntStream.range(0, 40).map(i -> 39 - i).toArray(), state.moduleSlots,
                "排序不得改動原始槽號與名稱陣列的對應");
    }

    @Test void missingSlotTwoPlacesSlotThreeOnTheSecondRowWithItsOwnData() {
        var state = new HostFxGlassArModel.State();
        state.moduleSlots = new int[]{3, 1};
        state.moduleNames = new String[]{"模組三", "模組一"};
        state.modProgressPct = new byte[]{-1, 25, -1, 75};
        var model = HostFxGlassArModel.build(state);
        assertEquals("1", model.text(HostFxGlassArModel.Z_MODLIST, 200).text);
        assertEquals("模組一", model.text(HostFxGlassArModel.Z_MODLIST, 201).text);
        assertEquals("25%", model.text(HostFxGlassArModel.Z_MODLIST, 202).text);
        assertEquals("3", model.text(HostFxGlassArModel.Z_MODLIST, 210).text);
        assertEquals("模組三", model.text(HostFxGlassArModel.Z_MODLIST, 211).text);
        assertEquals("75%", model.text(HostFxGlassArModel.Z_MODLIST, 212).text);
        assertEquals(HostFxGlassArPanel.px(HostFxGlassArPanel.MODLIST_ROW_DV),
                model.text(HostFxGlassArModel.Z_MODLIST, 210).y - model.text(HostFxGlassArModel.Z_MODLIST, 200).y,
                0.0001F);
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

    @Test void emptyAndUnknownSlotListsDoNotInventRows() {
        var state = new HostFxGlassArModel.State();
        for (int[] slots : new int[][]{null, new int[0]}) {
            state.moduleSlots = slots;
            assertTrue(HostFxGlassArModel.build(state).texts.stream()
                    .noneMatch(t -> t.zone == HostFxGlassArModel.Z_MODLIST && t.slot >= 200));
        }
    }

    @Test void workingStoppedAndUnknownReasonsNeverChangeSlotOrder() {
        var state = new HostFxGlassArModel.State();
        state.moduleSlots = new int[]{3, 1, 2};
        for (byte[] reasons : new byte[][]{{0, 0, 1, 1}, {0, 2, 1, 0}, {-1, -1, -1, -1}}) {
            state.modReason = reasons;
            assertEquals(java.util.List.of("1", "2", "3"), HostFxGlassArModel.build(state).texts.stream()
                    .filter(t -> t.zone == HostFxGlassArModel.Z_MODLIST && t.slot >= 200 && t.slot % 10 == 0)
                    .map(t -> t.text).toList());
        }
    }
}
