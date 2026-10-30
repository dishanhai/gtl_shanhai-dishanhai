package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：以實際繪圖模型驗證分頁與槽位資料對齊。 */
class GuixuArPaginationTest {
    private HostFxGlassArModel.State state(int count) throws Exception {
        var state = new HostFxGlassArModel.State();
        state.installedModules = count;
        state.moduleSlots = IntStream.range(0, count).map(i -> i + 3).toArray();
        state.moduleNames = IntStream.range(0, count).mapToObj(i -> "模組" + (i + 3)).toArray(String[]::new);
        return state;
    }

    private List<String> slots(HostFxGlassArModel.State state, long millis) throws Exception {
        state.getClass().getField("modulePageTimeMillis").setLong(state, millis);
        return HostFxGlassArModel.build(state).texts.stream()
                .filter(t -> t.zone == HostFxGlassArModel.Z_MODLIST && t.slot >= 200 && t.slot % 10 == 0)
                .map(t -> t.text).toList();
    }

    @Test void sevenRowsStayOnOnePage() throws Exception {
        var state = state(7);
        assertEquals(List.of("3", "4", "5", "6", "7", "8", "9"), slots(state, 0));
        assertEquals(slots(state, 0), slots(state, 10_000));
    }

    @Test void tenModulesRotateAtTenSecondsAndWrapAtTwenty() throws Exception {
        var state = state(10);
        assertEquals(List.of("3", "4", "5", "6", "7", "8", "9"), slots(state, 9_999));
        assertEquals(List.of("10", "11", "12"), slots(state, 10_000));
        assertEquals(slots(state, 0), slots(state, 20_000));
    }

    @Test void allFortySlotsAreReachableWithoutDuplicatingRows() throws Exception {
        var state = state(40);
        state.moduleSlots = IntStream.range(0, 40).toArray();
        var seen = new java.util.ArrayList<String>();
        for (int page = 0; page < 6; page++) seen.addAll(slots(state, page * 10_000L));
        assertEquals(IntStream.range(0, 40).mapToObj(String::valueOf).toList(), seen);
        assertEquals(slots(state, 0), slots(state, 60_000));
    }

    @Test void secondPageKeepsNamesAndLongParallelAttachedToTheirSlots() throws Exception {
        var state = state(10);
        long[] parallels = new long[40];
        parallels[10] = 9_000_000_000L;
        state.getClass().getField("modParallel").set(state, parallels);
        slots(state, 10_000);
        var texts = HostFxGlassArModel.build(state).texts;
        assertTrue(texts.stream().anyMatch(t -> t.slot == 201 && t.text.equals("模組10")));
        assertTrue(texts.stream().anyMatch(t -> t.slot == 204 && t.text.equals("无限")));
        assertTrue(texts.stream().anyMatch(t -> t.zone == HostFxGlassArModel.Z_MODLIST && t.text.contains("2/2")));
    }

    @Test void emptyListIsReportedAsEmptyInsteadOfMissingSync() throws Exception {
        var state = state(0);
        assertTrue(slots(state, 0).isEmpty());
        assertTrue(HostFxGlassArModel.build(state).texts.stream().anyMatch(t -> t.text.equals("未安装模块")));
    }

    @Test void onlyCountsAboveIntegerMaxAreShownAsInfinite() {
        assertEquals("--", HostFxGlassArModel.countOrDash(-1));
        assertEquals("0", HostFxGlassArModel.countOrDash(0));
        assertEquals("2.15G", HostFxGlassArModel.countOrDash(Integer.MAX_VALUE));
        assertEquals("无限", HostFxGlassArModel.countOrDash((long) Integer.MAX_VALUE + 1));
        assertEquals("无限", HostFxGlassArModel.countOrDash(Long.MAX_VALUE));
    }

    @Test void materialProductionDisplaysTheCombinedMultiplierWithoutAddingBonusesAgain() {
        var state = new HostFxGlassArModel.State();
        for (int multiplier : new int[]{6, 11, 600, 1100, 11000}) {
            state.productionMultiplier = multiplier;
            assertTrue(HostFxGlassArModel.build(state).texts.stream()
                    .anyMatch(t -> t.text.equals("材料增产 x" + multiplier)));
        }
    }

    @Test void pageCountdownDecreasesAndResetsWhenThePageChanges() throws Exception {
        var state = state(10);
        for (long time : new long[]{0, 999, 1_000, 9_001, 9_999, 10_000}) {
            slots(state, time);
            String header = HostFxGlassArModel.build(state).texts.stream()
                    .filter(t -> t.zone == HostFxGlassArModel.Z_MODLIST && t.slot == 6)
                    .findFirst().orElseThrow().text;
            int remaining = (int) ((10_000 - time % 10_000 + 999) / 1_000);
            assertTrue(header.contains(remaining + "s换页"), header);
            assertTrue(header.contains(time < 10_000 ? "1/2" : "2/2"), header);
        }
    }

    @Test void threadsAtIntegerMaxAreInfiniteWhileParallelAtThatValueIsNumeric() throws Exception {
        var state = state(1);
        state.modThreads = new int[40];
        state.modThreads[3] = Integer.MAX_VALUE;
        state.modParallel = new long[40];
        state.modParallel[3] = Integer.MAX_VALUE;
        state.hostThreads = Integer.MAX_VALUE;
        var texts = HostFxGlassArModel.build(state).texts;
        assertTrue(texts.stream().anyMatch(t -> t.zone == HostFxGlassArModel.Z_MODLIST
                && t.slot == 205 && t.text.equals("无限")));
        assertTrue(texts.stream().anyMatch(t -> t.zone == HostFxGlassArModel.Z_MODLIST
                && t.slot == 204 && t.text.equals("2.15G")));
        assertTrue(texts.stream().anyMatch(t -> t.zone == HostFxGlassArModel.Z_HOST
                && t.text.contains("线程 无限")));
        state.modThreads[3] = Integer.MAX_VALUE - 1;
        assertTrue(HostFxGlassArModel.build(state).texts.stream().anyMatch(t ->
                t.zone == HostFxGlassArModel.Z_MODLIST && t.slot == 205 && t.text.equals("2.15G")));
    }
}
