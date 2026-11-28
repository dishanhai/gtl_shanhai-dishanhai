package com.dishanhai.gt_shanhai.client.renderer.preview;

import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterGeometry;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterInstallation;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

class StarLifterRingPassTest {
    private static final Vec3 CENTER = new Vec3(31_000, 90, -49_000);
    private static final long START = 200;

    @Test
    void outerServiceNodesTriggerAtTheRingEvenWhileCollectionIsIdle() {
        var installation = new StarLifterInstallation("controller:test", CENTER, 68, 90, START);
        var listener = CENTER.add(90, 1.6, 0);
        var passes = LongStream.rangeClosed(START + 1, START + 2780)
                .filter(t -> StarLifterRingPass.crossing(installation, listener, t) != null)
                .toArray();
        assertEquals(15, passes.length);
        for (long time : passes) {
            var source = StarLifterRingPass.crossing(installation, listener, time);
            assertTrue(source.distanceTo(listener) < StarLifterRingPass.RANGE);
            assertTrue(source.distanceTo(CENTER) > 75);
            assertEquals(source, StarLifterRingPass.crossing(installation, listener, time + 2780));
        }
    }

    @Test
    void tiltedRingPassesAStationaryListener() {
        var installation = new StarLifterInstallation("controller:test", CENTER, 68, 0, START);
        var ring = StarLifterGeometry.RINGS.getFirst();
        var sample = ring.orientation(70).transform(new Vector3d(ring.radius() * 68, 0, 0));
        var listener = CENTER.add(sample.x, sample.y, sample.z);
        assertTrue(LongStream.rangeClosed(START + 1, START + 1520)
                .anyMatch(t -> StarLifterRingPass.crossing(installation, listener, t) != null));
    }

    @Test
    void staysSilentAtTheCoreOrOutsideTheRingAndSpinUpDoesNotRestartForLateListeners() {
        var installation = new StarLifterInstallation("controller:test", CENTER, 68, 0, START);
        for (long time : new long[] {START, START + 100, START + 1800}) {
            assertNull(StarLifterRingPass.crossing(installation, CENTER, time));
            assertNull(StarLifterRingPass.crossing(installation, CENTER.add(105, 0, 0), time));
        }
        assertEquals(0f, StarLifterAudio.rotationSpin(-1));
        assertEquals(.5f, StarLifterAudio.rotationSpin(24));
        assertEquals(1f, StarLifterAudio.rotationSpin(480));
    }
}
