package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuHostGeometry;
import com.dishanhai.gt_shanhai.network.GuixuAnnihilationPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GuixuAnnihilationFxTest {
    @Test
    void coreCollapsesBeforeTheFlashAndTheJetsLaunch() {
        var first = GuixuAnnihilationFx.sample(0, 6);
        var collapse = GuixuAnnihilationFx.sample(5, 6);
        var rupture = GuixuAnnihilationFx.sample(8, 6);
        assertEquals(1F, first.coreScale());
        assertTrue(collapse.coreScale() > 0F && collapse.coreScale() < first.coreScale());
        assertEquals(0F, collapse.primary().radius());
        assertEquals(0F, collapse.jetOpacity());
        assertEquals(0F, rupture.coreScale());
        assertEquals(1F, rupture.flash());
        assertEquals(1F, rupture.jetOpacity());
        assertTrue(rupture.primary().opacity() > 0F);
    }

    @Test
    void threeStaggeredShockFrontsPassTheOuterRingThenExpire() {
        var primary = GuixuAnnihilationFx.sample(15, 6);
        assertTrue(primary.primary().radius() > 0F);
        assertEquals(0F, primary.secondary().radius());
        var secondary = GuixuAnnihilationFx.sample(24, 6);
        assertTrue(secondary.secondary().radius() > 0F);
        assertEquals(0F, secondary.tertiary().radius());

        var trailing = GuixuAnnihilationFx.sample(50, 6);
        assertTrue(trailing.primary().radius() > GuixuHostGeometry.RING_HALF * 2.5F);
        assertTrue(trailing.primary().radius() > trailing.secondary().radius());
        assertTrue(trailing.secondary().radius() > trailing.tertiary().radius());
        assertTrue(trailing.tertiary().radius() > GuixuHostGeometry.RING_HALF);
        assertTrue(trailing.tertiary().opacity() > 0F);
        assertTrue(trailing.primary().opacity() < primary.primary().opacity());

        var ended = GuixuAnnihilationFx.sample(GuixuAnnihilationFx.DURATION_TICKS, 6);
        assertEquals(0F, ended.jetOpacity());
        assertEquals(0F, ended.primary().opacity());
        assertEquals(0F, ended.secondary().opacity());
        assertEquals(0F, ended.tertiary().opacity());
    }

    @Test
    void snapshotCarriesTheBlackHoleStateWithoutResolvingTheController() {
        for (boolean preview : new boolean[]{false, true}) {
            var burst = new GuixuAnnihilationPacket(new BlockPos(-40, 90, 210), new BlockPos(-112, 79, 210),
                    8.6F, 71, 120_700L, preview);
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                burst.encode(buffer);
                assertEquals(burst, new GuixuAnnihilationPacket(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }
}
