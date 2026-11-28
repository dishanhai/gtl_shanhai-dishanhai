package com.dishanhai.gt_shanhai.common.machine.starlifter;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StarLifterCollisionTest {
    private final StarLifterInstallation installation = new StarLifterInstallation(
            "collision_test", new Vec3(91.25, 160.5, -214.25), 68, 37, 10_000);

    @Test
    void rotatingRailOccupiesOnlyTheCurrentPosition() {
        var ring = StarLifterGeometry.RINGS.get(0);
        int elapsed = ring.spatialPeriod() / 4;
        double angle = Math.PI * .53;
        double rail = ring.radius() - ring.width() * .408;
        var resting = railAt(ring, rail, angle, 0);
        var rotating = railAt(ring, rail, angle, elapsed);
        long start = installation.startTick();
        assertTrue(blocked(resting, start));
        assertFalse(blocked(rotating, start));
        assertFalse(blocked(resting, start + elapsed));
        assertTrue(blocked(rotating, start + elapsed));
    }

    @Test
    void centralStarRemainsSolidAndSpaceBetweenRingsStaysOpen() {
        long at = installation.startTick() + 117;
        assertTrue(blocked(installation.center(), at));
        assertFalse(blocked(installation.center().add(installation.radius() * .48, 0, 0), at));
    }

    @Test
    void depletedPhotosphereFreesItsOldSurfaceAndReactivationRestoresIt() {
        var point = installation.center().add(.40 * installation.radius(), 0, 0);
        long start = installation.startTick();
        assertTrue(blocked(point, start));
        var depleted = installation.visual().update(StarLifterVisualState.Mode.FAULT, start, false, 0);
        installation.setVisual(depleted);
        assertFalse(blocked(point, start + 31));
        assertTrue(blocked(installation.center(), start + 31));
        installation.setVisual(depleted.update(StarLifterVisualState.Mode.IDLE, start + 40, false,
                StarLifterVisualState.FULL_RESERVE));
        assertTrue(blocked(point, start + 71));
    }

    @Test
    void savedPlacementRetainsRotationEpoch() {
        var decoded = StarLifterInstallation.load(installation.save());
        assertEquals(installation.name(), decoded.name());
        assertEquals(installation.center(), decoded.center());
        assertEquals(installation.radius(), decoded.radius());
        assertEquals(installation.heading(), decoded.heading());
        assertEquals(installation.startTick(), decoded.startTick());
    }

    private Vec3 railAt(StarLifterGeometry.Ring ring, double radius, double angle, double ticks) {
        var orientation = new Quaternionf().rotationY((float) Math.toRadians(installation.heading()))
                .mul(ring.orientation(ticks));
        var point = orientation.transform(new Vector3f((float) (radius * Math.cos(angle)),
                (float) (ring.height() * .385), (float) (radius * Math.sin(angle))));
        return installation.center().add(new Vec3(point).scale(installation.radius()));
    }

    private boolean blocked(Vec3 target, long time) {
        var shapes = new ArrayList<VoxelShape>();
        installation.collision(time).append(new AABB(target, target).inflate(.07), shapes);
        return !shapes.isEmpty();
    }
}
