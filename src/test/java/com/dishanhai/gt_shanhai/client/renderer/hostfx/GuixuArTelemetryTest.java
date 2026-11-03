package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：真實狀態協定、常駐讀數及可達的模型頁面。 */
class GuixuArTelemetryTest {
    private Class<?> statusType() {
        return assertDoesNotThrow(() -> Class.forName(
                "com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuArStatus"));
    }

    private Object decode(long[] wire) throws Exception {
        return statusType().getMethod("fromWire", long[].class).invoke(null, (Object) wire);
    }

    private long[] wire() {
        // version, operation, active, enabled, lock, field, stability, fluid, cost,
        // catalysis, engine link, echo link, local, remote, echo count
        return new long[]{1, 1, 5, 1, 0, 1, Float.floatToIntBits(86), 80_000, 8, 12, 2, 1, 24, 16, 0};
    }

    private HostFxGlassArModel.State state(long[] wire) throws Exception {
        var state = new HostFxGlassArModel.State();
        state.getClass().getField("status").set(state, decode(wire));
        return state;
    }

    private boolean contains(HostFxGlassArModel.State state, String text) {
        return HostFxGlassArModel.build(state).texts.stream().anyMatch(t -> t.text.contains(text));
    }

    @Test void statusPayloadRoundTripsWithoutLosingNegativeStabilityOrLongFluidRate() throws Exception {
        var wire = wire();
        wire[5] = 2;
        wire[6] = Float.floatToIntBits(-450);
        wire[7] = 10_000_000_000_000L;
        var status = decode(wire);
        assertArrayEquals(wire, (long[]) statusType().getMethod("toWire").invoke(status));
    }

    @Test void missingAndUnsupportedPayloadsDoNotInventAnIdleHostOrAbsentField() throws Exception {
        for (long[] wire : new long[][]{null, new long[0], new long[]{99},
                new long[]{1, 999, 0, 1, 0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 0}}) {
            var state = state(wire);
            assertTrue(contains(state, "归墟 --"));
            assertTrue(contains(state, "遏制场 --"));
            assertFalse(contains(state, "归墟 待机"));
        }
    }

    @Test void collapseLockTakesPrecedenceOverWorkingAndClosedField() throws Exception {
        var wire = wire();
        wire[4] = 42;
        wire[5] = 0;
        var state = state(wire);
        assertTrue(contains(state, "坍缩锁定 42s"));
        wire[4] = 0;
        wire[3] = 0;
        assertTrue(contains(state(wire), "归墟 已停机"));
    }

    @Test void knownAbsentAndUnstableFieldsHaveDifferentReadouts() throws Exception {
        var wire = wire();
        wire[5] = -1;
        assertTrue(contains(state(wire), "遏制场 未安装"));
        wire[5] = 2;
        wire[6] = Float.floatToIntBits(-450);
        var unstable = state(wire);
        assertTrue(contains(unstable, "遏制场 失稳"));
        assertTrue(contains(unstable, "吞噬 50%"));
        assertTrue(contains(unstable, "距坍缩 450s"));
    }

    @Test void allFourModelPagesAreReachableWhileTheModuleTableKeepsItsOwnPagination() {
        var state = new HostFxGlassArModel.State();
        state.moduleSlots = IntStream.range(0, 41).toArray();
        state.moduleNames = IntStream.range(0, 41).mapToObj(i -> "模块" + i).toArray(String[]::new);
        String[] tags = {"view.overview", "view.cutaway", "view.decompressor", "view.topology"};
        for (int page = 0; page < 4; page++) {
            state.modulePageTimeMillis = page * 10_000L;
            var model = HostFxGlassArModel.build(state);
            String tag = tags[page];
            assertTrue(model.prims.stream().anyMatch(p -> p.tag.startsWith(tag)), tag);
            assertEquals(7, model.texts.stream().filter(t -> t.zone == HostFxGlassArModel.Z_MODLIST
                    && t.slot >= 200 && t.slot % 10 == 0).count());
        }
        state.modulePageTimeMillis = 50_000L;
        assertEquals(java.util.List.of("35", "36", "37", "38", "39", "40"),
                HostFxGlassArModel.build(state).texts.stream().filter(t -> t.zone == HostFxGlassArModel.Z_MODLIST
                        && t.slot >= 200 && t.slot % 10 == 0).map(t -> t.text).toList());
    }

    @Test void detailedViewGeometryStaysInsideItsZoneAndDoesNotUseThePageClockForAnimation() {
        var state = new HostFxGlassArModel.State();
        state.animSeconds = 7;
        for (int page = 0; page < 4; page++) {
            state.modulePageTimeMillis = page * 10_000L;
            var model = HostFxGlassArModel.build(state);
            var geometry = model.prims.stream().filter(p -> p.tag.startsWith("view.")).toList();
            assertFalse(geometry.isEmpty());
            for (var p : geometry) {
                var zone = HostFxGlassArPanel.ZONES[p.zone];
                for (int i = 0; i < p.xy.length; i += 2) {
                    assertTrue(Float.isFinite(p.xy[i]) && Float.isFinite(p.xy[i + 1]));
                    assertTrue(zone.contains(p.xy[i] * HostFxGlassArPanel.PIXEL,
                            p.xy[i + 1] * HostFxGlassArPanel.PIXEL, 0.02F), p.tag);
                }
            }
            var before = geometry.stream().map(p -> java.util.Arrays.toString(p.xy)).toList();
            state.modulePageTimeMillis += 1_000;
            assertEquals(before, HostFxGlassArModel.build(state).prims.stream()
                    .filter(p -> p.tag.startsWith("view.")).map(p -> java.util.Arrays.toString(p.xy)).toList());
        }
    }

    @Test void topologyOnlyLightsLinksThatTheServerMarksReady() throws Exception {
        var wire = wire();
        var state = state(wire);
        state.modulePageTimeMillis = 30_000;
        assertEquals(1, HostFxGlassArModel.build(state).prims.stream()
                .filter(p -> p.tag.equals("view.topology.link_ready")).count());
        wire[10] = wire[11] = 0;
        state = state(wire);
        state.modulePageTimeMillis = 30_000;
        assertEquals(0, HostFxGlassArModel.build(state).prims.stream()
                .filter(p -> p.tag.equals("view.topology.link_ready")).count());
    }

    @Test void statusSnapshotIsRegisteredComparedPublishedAndReadByTheClient() throws Exception {
        String host = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/guixu/GuixuVisionCoreMachine.java"));
        assertTrue(host.matches("(?s).*@DescSynced\\s+private long\\[\\] hostArStatus.*"));
        assertTrue(host.contains("\"hostArStatus\""), "ManagedFieldHolder 完整性檢查");
        assertTrue(host.contains("Arrays.equals(arStatus, hostArStatus)"));
        assertTrue(host.contains("hostArStatus = arStatus"));
        String client = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/renderer/hostfx/HostFxGlassArText.java"));
        assertTrue(client.contains("GuixuArStatus.fromWire(host.hostArStatus())"));
    }

    @Test void unavailableMountBitmapDoesNotPretendAllFortyOneSlotsAreEmpty() {
        var state = new HostFxGlassArModel.State();
        state.modulePageTimeMillis = 30_000;
        var unknown = HostFxGlassArModel.build(state);
        assertTrue(unknown.texts.stream().anyMatch(t -> t.text.contains("仓位 --")));
        assertTrue(unknown.prims.stream().anyMatch(p -> p.tag.equals("view.mount.unknown")));
        state.moduleSlots = new int[0];
        assertTrue(HostFxGlassArModel.build(state).prims.stream()
                .anyMatch(p -> p.tag.equals("view.mount.unknown")), "預設空位圖不代表已同步");
        state.installedModules = 1;
        assertTrue(HostFxGlassArModel.build(state).prims.stream()
                .anyMatch(p -> p.tag.equals("view.mount.unknown")), "安裝數與位圖不一致仍為未知");
        state.installedModules = 0;
        var empty = HostFxGlassArModel.build(state);
        assertFalse(empty.prims.stream().anyMatch(p -> p.tag.equals("view.mount.unknown")));
        assertEquals(41, empty.prims.stream().filter(p -> p.tag.equals("view.mount.empty")).count());
    }
}
