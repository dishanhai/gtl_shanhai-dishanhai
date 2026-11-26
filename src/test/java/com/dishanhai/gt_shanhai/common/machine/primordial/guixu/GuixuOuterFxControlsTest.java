package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuixuOuterFxControlsTest {
    private Host host() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Host) ((sun.misc.Unsafe) field.get(null)).allocateInstance(Host.class);
    }

    @Test void screenEffectsAreRegisteredForSaveAndClientSync() throws Exception {
        var fields = host().getFieldHolder().getFields();
        for (String name : new String[]{"hostOuterStarsEnabled", "hostNebulaEnabled", "hostLegacyBlackHole", "hostWhiteHole"}) {
            var key = java.util.Arrays.stream(fields).filter(candidate -> candidate.getName().equals(name))
                    .findFirst().orElseThrow(() -> new AssertionError("Missing managed field: " + name));
            assertTrue(key.isPersist(), name);
            assertTrue(key.isDestSync(), name);
        }
    }

    @Test void cyclesFromOldSaveFlagsThroughWhiteHoleAndBackToKerr() throws Exception {
        Host host = host();
        host.cycleHostHoleStyle();
        assertTrue(host.isHostLegacyBlackHole());
        assertFalse(host.isHostWhiteHole());
        host.cycleHostHoleStyle();
        assertFalse(host.isHostLegacyBlackHole());
        assertTrue(host.isHostWhiteHole());
        host.cycleHostHoleStyle();
        assertFalse(host.isHostLegacyBlackHole());
        assertFalse(host.isHostWhiteHole());
        assertEquals(3, host.dirty);
        assertEquals(3, host.sync);
    }

    @Test void blackHoleStyleDefaultsToKerrAndSavesBothTransitions() throws Exception {
        Host host = host();
        assertFalse(host.isHostLegacyBlackHole());
        host.setHostLegacyBlackHole(true);
        host.setHostLegacyBlackHole(true);
        assertTrue(host.isHostLegacyBlackHole());
        assertEquals(1, host.dirty);
        assertEquals(1, host.sync);
        host.setHostLegacyBlackHole(false);
        assertFalse(host.isHostLegacyBlackHole());
        assertEquals(2, host.dirty);
        assertEquals(2, host.sync);
    }

    @Test void eachToggleSavesAndSyncsOnlyWhenItsValueChanges() throws Exception {
        for (String feature : new String[]{"OuterStars", "Nebula"}) {
            Host host = host();
            var setter = Host.class.getMethod("setHost" + feature + "Enabled", boolean.class);
            var getter = Host.class.getMethod("isHost" + feature + "Enabled");
            setter.invoke(host, true);
            assertEquals(true, getter.invoke(host));
            assertEquals(1, host.dirty);
            assertEquals(1, host.sync);
            setter.invoke(host, true);
            assertEquals(1, host.dirty);
            assertEquals(1, host.sync);
            setter.invoke(host, false);
            assertEquals(false, getter.invoke(host));
            assertEquals(2, host.dirty);
            assertEquals(2, host.sync);
        }
    }

    @Test void renderPanelContainsTheControlsAndHasRoomForTheirLastRow() throws Exception {
        String layout = String.join("\n", HostFxConfigurators.layoutTable());
        assertTrue(layout.contains("外层星辰"));
        assertTrue(layout.contains("外层星云"));
        assertTrue(layout.contains("黑洞渲染"));
        var height = HostFxConfigurators.class.getDeclaredField("PANEL_H");
        height.setAccessible(true);
        assertTrue(height.getInt(null) >= 145 + 16 + 5);
    }

    private static class Host extends GuixuVisionCoreMachine {
        int dirty, sync;
        Host() { super(null); }
        @Override public boolean isRemote() { return false; }
        @Override public void markDirty() { dirty++; }
        @Override public void notifyBlockUpdate() { sync++; }
    }
}
