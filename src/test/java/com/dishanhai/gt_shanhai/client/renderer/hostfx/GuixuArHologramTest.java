package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：直接檢查正式模型的透明分區、動畫邊界與欄內可讀性。 */
class GuixuArHologramTest {
    @Test void backingSeparatesTheSevenZonesAndLeavesTheGapsTransparent() {
        var model = HostFxGlassArModel.build(new HostFxGlassArModel.State());
        var panels = model.prims.stream().filter(p -> p.tag.equals("holo.backing")).toList();
        assertEquals(HostFxGlassArPanel.ZONES.length, panels.size());
        for (var p : panels) {
            assertEquals(HostFxGlassArModel.BLEND_ALPHA, p.blend);
            assertTrue((p.argb >>> 24) <= 80, "背景必須維持全息通透感");
            assertTrue((p.argb & 255) > ((p.argb >>> 16) & 255), "使用深藍底");
            var zone = HostFxGlassArPanel.ZONES[p.zone];
            for (int i = 0; i < p.xy.length; i += 2) {
                assertTrue(zone.contains(p.xy[i] * HostFxGlassArPanel.PIXEL,
                        p.xy[i + 1] * HostFxGlassArPanel.PIXEL, 0.001F));
            }
        }
        assertNull(model.first(HostFxGlassArModel.Z_MASK, "mask.backdrop"));
    }

    @Test void switchingOffTheMaskRemovesEveryBackingButKeepsProjectionCorners() {
        var state = new HostFxGlassArModel.State();
        state.arMask = false;
        var model = HostFxGlassArModel.build(state);
        assertTrue(model.prims.stream().noneMatch(p -> p.tag.equals("holo.backing")));
        assertTrue(model.prims.stream().anyMatch(p -> p.tag.equals("holo.corner")));
    }

    @Test void scanUsesTheAnimationClockAndStaysInTheEmptyTopEdge() {
        var state = new HostFxGlassArModel.State();
        state.animSeconds = 2;
        var first = scans(state);
        assertEquals(7, first.size());
        assertEquals(first.stream().map(p -> java.util.Arrays.toString(p.xy)).toList(),
                scans(state).stream().map(p -> java.util.Arrays.toString(p.xy)).toList());
        state.animSeconds = 3;
        var next = scans(state);
        assertFalse(java.util.Arrays.equals(first.getFirst().xy, next.getFirst().xy));
        for (var p : next) {
            var zone = HostFxGlassArPanel.ZONES[p.zone];
            for (int i = 0; i < p.xy.length; i += 2) {
                assertTrue(zone.contains(p.xy[i] * HostFxGlassArPanel.PIXEL,
                        p.xy[i + 1] * HostFxGlassArPanel.PIXEL, 0.001F));
                assertTrue(p.xy[i + 1] <= HostFxGlassArPanel.px(zone.v) + 2);
            }
        }
    }

    @Test void wideModuleNamesLeaveSpaceBeforeProgress() {
        var state = new HostFxGlassArModel.State();
        state.moduleSlots = new int[]{0};
        state.moduleNames = new String[]{"超長模組名稱".repeat(8)};
        state.textWidth = s -> s.codePointCount(0, s.length()) * 14;
        var name = HostFxGlassArModel.build(state).text(HostFxGlassArModel.Z_MODLIST, 201);
        float progress = HostFxGlassArPanel.px(HostFxGlassArPanel.ZONE_MODLIST.u
                + HostFxGlassArPanel.MOD_BAR_U);
        assertTrue(name.x + state.textWidth.applyAsInt(name.text) <= progress - 6);
        assertTrue(name.text.endsWith("…"));
    }

    @Test void narrowNamesThatFitAreNotShortenedByTheOldCharacterLimit() {
        var state = new HostFxGlassArModel.State();
        state.moduleSlots = new int[]{0};
        state.moduleNames = new String[]{"iiiiiiiiiiiiiiiiiiii"};
        state.textWidth = s -> s.chars().map(c -> c == 'i' ? 2 : 7).sum();
        var name = HostFxGlassArModel.build(state).text(HostFxGlassArModel.Z_MODLIST, 201);
        assertEquals(state.moduleNames[0], name.text);
    }

    @Test void annotationStaysWithinTheSafeWallMargin() {
        var model = HostFxGlassArModel.build(new HostFxGlassArModel.State());
        var notes = model.textsOf(HostFxGlassArModel.Z_MODLIST).stream()
                .filter(t -> t.slot == 102 || t.slot == 103).toList();
        assertFalse(notes.isEmpty());
        for (var note : notes) {
            assertTrue(note.y + HostFxGlassArPanel.INK_PX <= HostFxGlassArPanel.px(
                    HostFxGlassArPanel.WALL_H - HostFxGlassArPanel.MARGIN) + 0.001F);
        }
    }

    @Test void unknownProgressHasNoFilledHostOrModuleBar() {
        var state = new HostFxGlassArModel.State();
        state.moduleSlots = new int[]{0};
        var model = HostFxGlassArModel.build(state);
        assertEquals(0, model.count(HostFxGlassArModel.Z_HOST, "host.bar_fill"));
        assertEquals(0, model.count(HostFxGlassArModel.Z_MODLIST, "mod.bar_fill"));
    }

    private List<HostFxGlassArModel.Prim> scans(HostFxGlassArModel.State state) {
        return HostFxGlassArModel.build(state).prims.stream()
                .filter(p -> p.tag.equals("holo.scan")).toList();
    }
}
