package com.dishanhai.gt_shanhai.common.machine.starlifter;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix3d;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/** 按同一组环段轮廓和旋转角度，生成当前查询范围内的世界坐标碰撞体素。 */
final class StarLifterCollision {
    private static final double CELL = .125;
    private static final double TAU = 2 * Math.PI;
    private static final List<List<List<Band>>> BANDS = bands();

    private StarLifterCollision() {}

    private record Band(double sinStart, double cosStart, double sinEnd, double cosEnd, double midX, double midZ,
                        double inner, double outer, double bottom, double top) {
        Band(double start, double end, double inner, double outer, double bottom, double top) {
            this(Math.sin(start), Math.cos(start), Math.sin(end), Math.cos(end),
                    Math.cos((start + end) * .5), Math.sin((start + end) * .5),
                    inner * Math.cos((end - start) * .5), outer * Math.cos((end - start) * .5), bottom, top);
        }

        boolean near(Vector3d point, double pad) {
            double radial = midX * point.x + midZ * point.z;
            return point.y >= bottom - pad && point.y <= top + pad && radial >= inner - pad && radial <= outer + pad
                    && cosStart * point.z - sinStart * point.x >= -pad
                    && sinEnd * point.x - cosEnd * point.z >= -pad;
        }
    }

    private static List<List<List<Band>>> bands() {
        List<List<List<Band>>> rings = new ArrayList<>();
        for (int i = 0; i < StarLifterGeometry.RINGS.size(); i++) {
            int count = StarLifterGeometry.segmentCount(i);
            List<List<Band>> segments = new ArrayList<>(count);
            for (int segment = 0; segment < count; segment++) segments.add(new ArrayList<>());
            StarLifterGeometry.ring(i, new StarLifterGeometry.Mesh() {
                @Override
                public void quad(StarLifterGeometry.Surface surface, StarLifterGeometry.Point a, StarLifterGeometry.Point b,
                                 StarLifterGeometry.Point c, StarLifterGeometry.Point d) {}

                @Override
                public void band(StarLifterGeometry.Surface surface, double start, double end,
                                 double inner, double outer, double bottom, double top) {
                    int segment = (int) Math.floor(start * count / TAU + 1e-8);
                    segments.get(segment).add(new Band(start, end, inner, outer, bottom, top));
                }
            });
            rings.add(segments);
        }
        return rings;
    }

    static final class Frame {
        private final StarLifterInstallation installation;
        private final long gameTime;
        private final Matrix3d[] inverse;

        Frame(StarLifterInstallation installation, long gameTime) {
            this.installation = installation;
            this.gameTime = gameTime;
            double ticks = gameTime - installation.startTick();
            var heading = new Quaternionf().rotationY((float) Math.toRadians(installation.heading()));
            inverse = StarLifterGeometry.RINGS.stream()
                    .map(ring -> new Matrix3d().rotation(new Quaternionf(heading).mul(ring.orientation(ticks))).transpose())
                    .toArray(Matrix3d[]::new);
        }

        long gameTime() { return gameTime; }

        void append(AABB query, List<VoxelShape> result) {
            AABB clipped = query.intersect(installation.bounds());
            Vec3 center = installation.center();
            cells(result, new Vector3d(),
                    Mth.floor((clipped.minX - center.x) / CELL), Mth.floor((clipped.minY - center.y) / CELL),
                    Mth.floor((clipped.minZ - center.z) / CELL), Mth.ceil((clipped.maxX - center.x) / CELL),
                    Mth.ceil((clipped.maxY - center.y) / CELL), Mth.ceil((clipped.maxZ - center.z) / CELL));
        }

        private void cells(List<VoxelShape> result, Vector3d local, int x, int y, int z, int endX, int endY, int endZ) {
            int dx = endX - x, dy = endY - y, dz = endZ - z;
            if (dx <= 0 || dy <= 0 || dz <= 0) return;
            double scale = CELL / installation.radius();
            double rx = (x + endX) * .5 * scale;
            double ry = (y + endY) * .5 * scale;
            double rz = (z + endZ) * .5 * scale;
            double pad = .5 * scale * Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
            boolean leaf = dx == 1 && dy == 1 && dz == 1;
            if (!intersects(local, rx, ry, rz, pad, leaf)) return;
            if (leaf) {
                Vec3 center = installation.center();
                result.add(Shapes.box(center.x + x * CELL, center.y + y * CELL, center.z + z * CELL,
                        center.x + endX * CELL, center.y + endY * CELL, center.z + endZ * CELL));
            } else if (dx >= dy && dx >= dz) {
                int mid = x + dx / 2;
                cells(result, local, x, y, z, mid, endY, endZ);
                cells(result, local, mid, y, z, endX, endY, endZ);
            } else if (dy >= dz) {
                int mid = y + dy / 2;
                cells(result, local, x, y, z, endX, mid, endZ);
                cells(result, local, x, mid, z, endX, endY, endZ);
            } else {
                int mid = z + dz / 2;
                cells(result, local, x, y, z, endX, endY, mid);
                cells(result, local, x, y, mid, endX, endY, endZ);
            }
        }

        private boolean intersects(Vector3d local, double x, double y, double z, double pad, boolean leaf) {
            double star = StarLifterGeometry.STAR_RADIUS + pad;
            if (x * x + y * y + z * z <= star * star) return true;
            for (int i = 0; i < inverse.length; i++) {
                local.set(x, y, z).mul(inverse[i]);
                var ring = StarLifterGeometry.RINGS.get(i);
                if (Math.abs(local.y) > ring.height() * .583 + pad) continue;
                double radial = Math.hypot(local.x, local.z);
                if (radial < ring.innerLimit() - .001 - pad || radial > ring.outerLimit() + pad) continue;
                if (!leaf) return true;
                List<List<Band>> segments = BANDS.get(i);
                int count = segments.size();
                int segment = Math.floorMod(Mth.floor(Math.atan2(local.z, local.x) * count / TAU), count);
                for (int adjacent = -1; adjacent <= 1; adjacent++) {
                    for (Band band : segments.get(Math.floorMod(segment + adjacent, count))) {
                        if (band.near(local, pad)) return true;
                    }
                }
            }
            return false;
        }
    }
}
