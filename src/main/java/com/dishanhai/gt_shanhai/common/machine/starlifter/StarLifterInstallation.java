package com.dishanhai.gt_shanhai.common.machine.starlifter;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import it.unimi.dsi.fastutil.longs.LongConsumer;

public final class StarLifterInstallation {
    public static final float DEFAULT_RADIUS = StarLifterGeometry.DEFAULT_RADIUS;
    public static final float MIN_RADIUS = 12.0f;
    public static final float MAX_RADIUS = 128.0f;

    private final String name;
    private final Vec3 center;
    private final float radius;
    private final float heading;
    private final long startTick;
    private final AABB bounds;
    private final BlockPos centerBlock;
    private StarLifterCollision.Frame collision;
    private volatile StarLifterVisualState visual;

    public StarLifterInstallation(String name, Vec3 center, float radius, float heading, long startTick) {
        if (name.isEmpty() || name.length() > 64 || !Double.isFinite(center.x) || !Double.isFinite(center.y)
                || !Double.isFinite(center.z) || !Float.isFinite(radius) || radius < MIN_RADIUS || radius > MAX_RADIUS
                || !Float.isFinite(heading) || heading < -180 || heading > 180) {
            throw new IllegalArgumentException("Invalid Star Lifter installation");
        }
        this.name = name;
        this.center = center;
        this.centerBlock = BlockPos.containing(center);
        this.radius = radius;
        this.heading = heading;
        this.startTick = startTick;
        this.visual = isController() ? StarLifterVisualState.IDLE
                : new StarLifterVisualState(StarLifterVisualState.Mode.RUNNING, startTick, false);
        double extent = radius * (StarLifterGeometry.OUTER_RADIUS + .04);
        this.bounds = new AABB(center.subtract(extent, extent, extent), center.add(extent, extent, extent));
    }

    public String name() { return name; }
    public Vec3 center() { return center; }
    public float radius() { return radius; }
    public float heading() { return heading; }
    public long startTick() { return startTick; }
    public AABB bounds() { return bounds; }
    public StarLifterVisualState visual() { return visual; }

    public void setVisual(StarLifterVisualState visual) { this.visual = visual; }

    public boolean isController() { return name.startsWith("controller:"); }

    BlockPos centerBlock() { return centerBlock; }

    public void forEachShellSection(LongConsumer visitor) {
        StarLifterStructure.forEachShellSection(this, visitor);
    }

    StarLifterCollision.Frame collision(long gameTime) {
        if (collision == null || collision.gameTime() != gameTime) {
            collision = new StarLifterCollision.Frame(this, gameTime);
        }
        return collision;
    }

    CompoundTag save() {
        var tag = new CompoundTag();
        tag.putString("Name", name);
        tag.putDouble("X", center.x);
        tag.putDouble("Y", center.y);
        tag.putDouble("Z", center.z);
        tag.putFloat("Radius", radius);
        tag.putFloat("Heading", heading);
        tag.putLong("StartTick", startTick);
        return tag;
    }

    static StarLifterInstallation load(CompoundTag tag) {
        return new StarLifterInstallation(tag.getString("Name"),
                new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z")),
                tag.getFloat("Radius"), tag.getFloat("Heading"), tag.getLong("StartTick"));
    }
}
