package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import static com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuStarRenderer.ambientVertex;

/** 实体球面写入深度，日冕、大气和星环在后续加性批次绘制。 */
final class GuixuCelestialRenderer {
    private static final ResourceLocation TEXTURE =
            new ResourceLocation(GTDishanhaiMod.MOD_ID, "textures/guixu/celestial_surfaces.png");
    private static final int SURFACE_COLUMNS = 4;
    private static final int SURFACE_ROWS = 5;
    private static final int SURFACE_TILE_WIDTH = 640;
    private static final int SURFACE_TILE_HEIGHT = 320;
    private static final Sphere DETAIL = new Sphere(18, 36);
    private static final Sphere SIMPLE = new Sphere(12, 24);
    private static final Sphere DISTANT = new Sphere(8, 16);
    private static final Sphere MOON = new Sphere(6, 12);
    private static final Sphere[] ASTEROID_MESHES = {
            new Sphere(4, 7, .24F, .45),
            new Sphere(4, 8, .31F, 1.93),
            new Sphere(5, 7, .22F, 3.29)
    };
    private static final float PULSAR_MAGNETIC_TILT = .47F;
    private static final DiskBand[] ACCRETION_BANDS = {
            new DiskBand(1.18F, .035F, 0, .88F, .93F, 1F),
            new DiskBand(1.32F, .075F, .62F, .92F, .95F, 1F),
            new DiskBand(1.57F, .11F, .92F, 1F, .96F, .91F),
            new DiskBand(1.86F, .18F, .60F, 1F, .78F, .56F),
            new DiskBand(2.43F, .24F, .29F, 1F, .59F, .35F),
            new DiskBand(3.09F, .17F, .092F, .91F, .40F, .23F),
            new DiskBand(3.32F, .08F, 0, .76F, .27F, .15F)
    };
    private static final LimbStyle WARM_LIMB = new LimbStyle(1F, .61F, .22F, .15F, 1.11F);
    private static final LimbStyle COOL_LIMB = new LimbStyle(.36F, .72F, 1F, .15F, 1.11F);
    private static final LimbStyle NEUTRON_LIMB = new LimbStyle(.25F, .73F, 1F, .75F, 1.29F);
    private static final LimbStyle PULSAR_LIMB = new LimbStyle(.41F, .89F, 1F, .59F, 1.27F);
    private static final LimbStyle HOLE_LIMB = new LimbStyle(1F, .64F, .27F, .88F, 1.19F);
    private static final LimbStyle WHITE_HOLE_LIMB = new LimbStyle(.66F, .88F, 1F, .59F, 1.23F);
    private static final LimbStyle SUPERGIANT_LIMB = new LimbStyle(1F, .29F, .10F, .22F, 1.24F);
    private static final CoronaBand[] STELLAR_CORONA = {
            new CoronaBand(-.035F, 0, .12F),
            new CoronaBand(.012F, .36F, .18F),
            new CoronaBand(.065F, .22F, .38F),
            new CoronaBand(.14F, .105F, .65F),
            new CoronaBand(.235F, .032F, .90F),
            new CoronaBand(.345F, 0, 1F)
    };
    private static RenderType bodyLayer;
    private static RenderType animatedBodyLayer;
    private static RenderType horizonDepthLayer;

    private final Matrix4f matrix, clip;
    private final Vector3f camera, center = new Vector3f(), view = new Vector3f(), pull = new Vector3f();
    private final Vector3f light = new Vector3f(), normal = new Vector3f(), point = new Vector3f();
    private final Vector3f right = new Vector3f(), up = new Vector3f();
    private final Vector3f coronaFrom = new Vector3f(), coronaTo = new Vector3f();
    private final Vector3f pole = new Vector3f();
    private final Vector3d planetCenter = new Vector3d(), minorCenter = new Vector3d();
    private float limbOffset, limbRadius, compression, jetBend;
    private final Quaternionf axis = new Quaternionf(), rotation = new Quaternionf();
    private final Sphere sphere;
    private final float projectionScale;
    private final double seconds;
    private final float horizon;

    GuixuCelestialRenderer(Matrix4f matrix, float[] camera, double seconds, float horizon, boolean simple) {
        this.matrix = matrix;
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        this.clip = new Matrix4f(projection).mul(matrix);
        this.projectionScale = Math.max(Math.abs(projection.m00()), Math.abs(projection.m11()));
        this.camera = new Vector3f(camera[0], camera[1], camera[2]);
        this.sphere = simple ? SIMPLE : DETAIL;
        this.seconds = seconds;
        this.horizon = horizon;
    }

    void drawHorizonDepth(MultiBufferSource.BufferSource buffers) {
        if (camera.lengthSquared() <= horizon * horizon
                || !HostFxVisibility.isVisible(clip, 0, 0, 0, horizon)) return;
        RenderType layer = horizonDepthLayer();
        VertexConsumer vertices = buffers.getBuffer(layer);
        // 屏幕空间透镜只写颜色，这个视界球为随后绘制的天体补上遮挡深度。
        for (int index : DETAIL.indices) {
            var vertex = DETAIL.points[index];
            ambientVertex(vertices, matrix, horizon * vertex.x(), horizon * vertex.y(), horizon * vertex.z(),
                    0, 0, 0, 1);
        }
        buffers.endBatch(layer);
    }

    void drawBodies(MultiBufferSource.BufferSource buffers, GuixuStellarEnd.Star[] stars) {
        boolean animate = GuixuCelestialShader.prepare(seconds, PULSAR_MAGNETIC_TILT);
        RenderType layer = bodyLayer(animate);
        VertexConsumer vertices = buffers.getBuffer(layer);
        // 渲染线程按天体顺序复用变换后的顶点，避免给相邻四边形重复做光照和潮汐计算。
        for (var star : stars) {
            if (star == null || star.solidRadius() <= GuixuStellarEnd.MIN_SOLID_RADIUS) continue;
            if (place(star)) drawSphere(vertices, star, animate);
            drawMoons(vertices, star, animate);
            drawTidalFragments(vertices, star, animate);
        }
        drawAsteroids(vertices, animate);
        buffers.endBatch(layer);
    }

    void drawAuras(VertexConsumer vertices, GuixuStellarEnd.Star[] stars, int segments) {
        for (var star : stars) {
            if (star == null || star.kind() == GuixuStellarEnd.BodyKind.BLACK_DWARF || !place(star)) continue;
            if (star.kind() == GuixuStellarEnd.BodyKind.QUASAR) {
                var ignition = GuixuStellarEnd.quasarIgnition(seconds);
                new GuixuQuasarRenderer(matrix, center, view, axis, seconds, star, ignition).draw(vertices, segments + 16);
                if (ignition.jetReach() > .001F) drawJets(vertices, star, star.kind().jetLength(), segments / 2, ignition);
                continue;
            }
            if (star.kind().hasPlanetaryRings()) drawRings(vertices, star, segments);
            if (star.kind().hasAccretionDisk()) drawAccretionDisk(vertices, star, segments);
            float jetLength = star.kind().jetLength();
            if (jetLength > 0) drawJets(vertices, star, jetLength, segments / 4, null);
            float distance = view.length(), radius = star.solidRadius();
            if (distance <= radius * (1 + star.tidalStress())) continue;
            limbOffset = GuixuStellarEnd.limbOffset(radius, distance);
            limbRadius = GuixuStellarEnd.limbRadius(radius, distance);
            view.div(distance);
            right.set(view.z, 0, -view.x);
            if (right.lengthSquared() < 1e-8F) right.set(1, 0, 0);
            right.normalize();
            up.set(view).cross(right);
            var kind = star.kind();
            boolean distant = switch (kind) {
                case WHITE_HOLE, THORNE_ZYTKOW, ANTISTAR, BLACK_DWARF -> sphereFor(star) == DISTANT;
                default -> false;
            };
            int limbSegments = distant ? segments / 2 : segments;
            float shift = kind == GuixuStellarEnd.BodyKind.WHITE_HOLE
                    ? GuixuStellarEnd.whiteHoleShift(center.length(), horizon) : 1F;
            float visibility = switch (kind) {
                case WHITE_HOLE -> shift * shift;
                case THORNE_ZYTKOW -> 1F - .87F * GuixuStellarEnd.tzoCoreExposure(star);
                default -> 1F;
            };
            if (star.brightness() * visibility > .001F) drawLimb(vertices, star, limbSegments, visibility);
            if (star.kind() == GuixuStellarEnd.BodyKind.WHITE_HOLE) {
                drawWhiteHoleEjecta(vertices, star, distance, distant ? 16 : Math.max(24, segments / 2), shift);
            }
            if (star.kind() == GuixuStellarEnd.BodyKind.ANTISTAR) {
                drawAnnihilation(vertices, star, limbSegments / 4);
            }
        }
    }

    private boolean place(GuixuStellarEnd.Star star) {
        if (star == null || star.solidRadius() <= GuixuStellarEnd.MIN_SOLID_RADIUS) return false;
        var location = GuixuStellarEnd.bodyPosition(star, horizon);
        center.set((float) location.x(), (float) location.y(), (float) location.z());
        float extent = star.solidRadius() * star.kind().effectExtent();
        if (!HostFxVisibility.isVisible(clip, center.x, center.y, center.z, extent)) return false;
        pull.set(center).negate().normalize();
        view.set(camera).sub(center);
        compression = star.transverseCompression();
        axis.rotationY((float) star.phase()).rotateZ(GuixuPlanetSystems.planetTilt(star));
        return true;
    }

    private void drawSphere(VertexConsumer vertices, GuixuStellarEnd.Star star, boolean animate) {
        Sphere sphere = sphereFor(star);
        rotation.set(axis).rotateY(star.kind().surfaceSpin(seconds, star.phase()));
        setDirectLight();
        boolean luminous = star.kind().luminousSurface();
        boolean horizonMask = star.kind().hasAccretionDisk() || star.kind() == GuixuStellarEnd.BodyKind.WHITE_HOLE;
        boolean quasar = star.kind() == GuixuStellarEnd.BodyKind.QUASAR;
        boolean dwarf = star.kind() == GuixuStellarEnd.BodyKind.WHITE_DWARF;
        boolean coldDwarf = star.kind() == GuixuStellarEnd.BodyKind.BLACK_DWARF;
        boolean supergiant = star.kind() == GuixuStellarEnd.BodyKind.THORNE_ZYTKOW;
        boolean neutron = star.kind() == GuixuStellarEnd.BodyKind.NEUTRON_STAR;
        boolean clouds = star.kind().hasPlanetaryRings();
        boolean tidalMaterial = clouds || neutron || dwarf
                || star.kind() == GuixuStellarEnd.BodyKind.STAR || star.kind() == GuixuStellarEnd.BodyKind.ROCKY;
        float disruption = star.disruption();
        float core = supergiant ? GuixuStellarEnd.tzoCoreExposure(star) : 0F;
        float dwarfTidalLight = dwarf ? GuixuStellarEnd.whiteDwarfTidalLight(star) : 0F;
        float radius = star.solidRadius();
        float viewLengthSquared = view.lengthSquared();
        float limbDarkening = supergiant ? .63F : dwarf ? .52F : .43F;
        for (int i = 0; i < sphere.points.length; i++) {
            var vertex = sphere.points[i];
            normal.set(vertex.x(), vertex.y(), vertex.z()).rotate(rotation);
            float facingHole = normal.dot(pull);
            float stretch = quasar ? 0F : Math.max(0F, facingHole);
            float bulge = 1 - compression * (1 - facingHole * facingHole);
            point.set(center).fma(radius * bulge, normal)
                    .fma(radius * star.tidalStress() * stretch * stretch, pull);
            float irradiance = Math.max(0, normal.dot(light));
            float lit = .08F + .92F * irradiance;
            float hot = star.tidalStress() * stretch * stretch * .58F;
            if (luminous) {
                // 用视线与光球法线的夹角计算边缘昏暗。
                float projected = normal.dot(view);
                float surfaceDistanceSquared = viewLengthSquared + radius * (radius - 2F * projected);
                float distanceToSurface = (float) Math.sqrt(Math.max(1e-6F, surfaceDistanceSquared));
                float mu = Math.clamp((projected - radius) / distanceToSurface, 0F, 1F);
                lit = 1F - (limbDarkening - .24F * core) * (1F - mu);
                hot = dwarf ? dwarfTidalLight * stretch * stretch * .78F : hot * (1F - core);
            }
            if (neutron) hot += disruption * stretch * .42F;
            if (clouds) hot += disruption * stretch * .23F;
            if (coldDwarf) {
                // 冷却余热主要在红外，演出中只画归墟照亮的固态表面。
                lit = .035F + .965F * irradiance;
                hot = 0;
            }
            if (horizonMask) {
                // 视界仅写入黑色和深度，盘面与外流由后续绘制。
                lit = 0;
                hot = 0;
            }
            int p = i * 7;
            sphere.transformed[p] = point.x;
            sphere.transformed[p + 1] = point.y;
            sphere.transformed[p + 2] = point.z;
            sphere.transformed[p + 3] = Math.min(1, lit + (star.warm() || dwarf ? hot : hot * .30F));
            sphere.transformed[p + 4] = Math.min(1, lit + hot * .61F);
            sphere.transformed[p + 5] = Math.min(1, lit + (star.warm() ? hot * .22F : hot));
            // TZO 从受力面开始露核。其他材质用同一通道传递局部潮汐强度。
            float exposure = supergiant ? core + core * (1 - core) * facingHole
                    : tidalMaterial ? disruption * (.20F + .80F * stretch) : 0F;
            sphere.transformed[p + 6] = animate ? 1F - .85F * exposure : 1F;
        }
        int tile = star.kind().surfaceTile(star.warm());
        if (!animate && core >= .5F) tile = GuixuStellarEnd.BodyKind.NEUTRON_STAR.surfaceTile(false);
        for (int index : sphere.indices) {
            var vertex = sphere.points[index];
            bodyVertex(vertices, sphere.transformed, index * 7, tile, animate, vertex);
        }
    }

    private void setDirectLight() {
        // 行星表面、大气和星环共享同一场景光源。
        light.set(pull).mul(.76F).add(-.17F, .37F, .10F).normalize();
    }

    private void bodyVertex(VertexConsumer vertices, float[] transformed, int p, int tile, boolean animate,
                            SpherePoint vertex) {
        // 图块编号放在纬度的整数部分，留出一格以便在球面极点插值。
        float u = animate ? vertex.u()
                : atlasUv(vertex.u(), tile % SURFACE_COLUMNS, SURFACE_TILE_WIDTH, SURFACE_COLUMNS);
        float v = animate ? 2F * tile + vertex.v()
                : atlasUv(vertex.v(), tile / SURFACE_COLUMNS, SURFACE_TILE_HEIGHT, SURFACE_ROWS);
        vertices.vertex(matrix, transformed[p], transformed[p + 1], transformed[p + 2])
                .uv(u, v).color(transformed[p + 3], transformed[p + 4], transformed[p + 5], transformed[p + 6]).endVertex();
    }

    private static float atlasUv(float uv, int tile, int tileSize, int tileCount) {
        // 采样范围止于首尾像素的中心，避免双线性过滤混入相邻材质。
        return (tile * tileSize + .5F + (tileSize - 1) * uv) / (tileCount * tileSize);
    }

    private void drawMoons(VertexConsumer vertices, GuixuStellarEnd.Star star, boolean animate) {
        var moons = GuixuPlanetSystems.moons(star.kind());
        if (moons.isEmpty()) return;
        GuixuPlanetSystems.planetCenter(star, horizon, planetCenter);
        for (var moon : moons) {
            float radius = moon.sample(star, seconds, horizon, planetCenter, minorCenter);
            if (radius <= .025F) continue;
            rotation.rotationY((float) star.phase()).rotateZ(GuixuPlanetSystems.planetTilt(star))
                    .rotateX((float) moon.inclination()).rotateY(-(float) moon.angle(seconds, star.phase()));
            drawMinorBody(vertices, MOON, radius, moon.tile(), animate);
        }
    }

    private void drawAsteroids(VertexConsumer vertices, boolean animate) {
        if (seconds >= GuixuStellarEnd.QUASAR_ENTRY_SECONDS) return;
        var rocks = GuixuPlanetSystems.asteroids();
        int step = sphere == SIMPLE ? 2 : 1;
        for (int i = 0; i < rocks.size(); i += step) {
            var rock = rocks.get(i);
            float radius = rock.sample(seconds, horizon, minorCenter);
            if (radius <= .025F) continue;
            rotation.rotationY((float) (rock.phase() + seconds * (.37 + .075 * (i % 7))))
                    .rotateX((float) (.8 * rock.phase() - .23 * seconds));
            drawMinorBody(vertices, ASTEROID_MESHES[i % ASTEROID_MESHES.length], radius,
                    GuixuPlanetSystems.ASTEROID_TILE, animate);
        }
    }

    private void drawTidalFragments(VertexConsumer vertices, GuixuStellarEnd.Star star, boolean animate) {
        boolean ice = star.kind() == GuixuStellarEnd.BodyKind.RINGED_GIANT;
        if (!ice && star.kind() != GuixuStellarEnd.BodyKind.ROCKY) return;
        int step = sphere == SIMPLE ? 2 : 1;
        int tile = ice ? GuixuPlanetSystems.ICE_TILE : GuixuPlanetSystems.ROCK_TILE;
        for (int i = 0; i < 8; i += step) {
            double release = ice ? -20 + 1.42 * i : -12 + .87 * i;
            double travel = GuixuStellarEnd.ease(release, -.7, star.captureAge());
            if (travel <= 0 || travel >= 1) continue;
            double progress = star.body() + (1 - star.body()) * travel;
            GuixuStellarEnd.streamPosition(star, progress, horizon, minorCenter);
            double phase = star.phase() + 2.399963 * i;
            double spread = (ice ? 2.1 : 1.25) * Math.sin(travel * Math.PI);
            double lateral = spread * Math.cos(phase) / Math.hypot(minorCenter.x, minorCenter.z);
            minorCenter.add(-minorCenter.z * lateral, spread * Math.sin(phase), minorCenter.x * lateral);
            float size = (ice ? .27F : .53F) * (.62F + .38F * (float) Math.sin(phase + .7))
                    * (float) (GuixuStellarEnd.ease(0, .09, travel) * (1 - GuixuStellarEnd.ease(.58, 1, travel)));
            float radius = Math.min(size, ((float) minorCenter.length() - horizon) / 1.35F);
            if (radius <= .025F) continue;
            rotation.rotationY((float) (phase + seconds * (1.4 + .11 * i))).rotateX((float) (phase - .92 * seconds));
            drawMinorBody(vertices, ASTEROID_MESHES[i % ASTEROID_MESHES.length], radius, tile, animate);
        }
    }

    private void drawMinorBody(VertexConsumer vertices, Sphere mesh, float radius, int tile, boolean animate) {
        center.set((float) minorCenter.x, (float) minorCenter.y, (float) minorCenter.z);
        if (!HostFxVisibility.isVisible(clip, center.x, center.y, center.z, radius * 1.35F)) return;
        light.set(center).normalize(-.76F).add(-.17F, .37F, .10F).normalize();
        for (int i = 0; i < mesh.points.length; i++) {
            var vertex = mesh.points[i];
            normal.set(vertex.x(), vertex.y(), vertex.z()).rotate(rotation);
            point.set(center).fma(radius, normal);
            float lit = .27F + .73F * Math.max(0, normal.normalize().dot(light));
            int p = i * 7;
            mesh.transformed[p] = point.x;
            mesh.transformed[p + 1] = point.y;
            mesh.transformed[p + 2] = point.z;
            mesh.transformed[p + 3] = lit;
            mesh.transformed[p + 4] = lit;
            mesh.transformed[p + 5] = lit;
            mesh.transformed[p + 6] = 1F;
        }
        for (int index : mesh.indices) {
            bodyVertex(vertices, mesh.transformed, index * 7, tile, animate, mesh.points[index]);
        }
    }

    private Sphere sphereFor(GuixuStellarEnd.Star star) {
        // 暗核继续与细光子环匹配。普通天体只有缩到较小的屏幕轮廓时才换网格。
        if (star.kind().hasAccretionDisk()) return sphere;
        float radius = star.solidRadius() * (1 + star.tidalStress());
        float depth = clip.m03() * center.x + clip.m13() * center.y + clip.m23() * center.z + clip.m33() - radius;
        if (depth <= 0) return sphere;
        float projected = projectionScale * radius / depth;
        if (projected <= .020F) return DISTANT;
        return projected <= .045F ? SIMPLE : sphere;
    }

    private void drawRings(VertexConsumer vertices, GuixuStellarEnd.Star star, int segments) {
        var bands = GuixuPlanetSystems.rings(star.kind());
        right.set(1, 0, 0).rotate(axis);
        up.set(0, 0, 1).rotate(axis);
        setDirectLight();
        float pullRight = right.dot(pull), pullUp = up.dot(pull);
        float pullLength = (float) Math.hypot(pullRight, pullUp);
        // 力线正对环面时，各个方位等价，让最早的缺口跟随天体旋转轴确定。
        pullRight = pullLength > 1e-4F ? pullRight / pullLength : 1;
        pullUp = pullLength > 1e-4F ? pullUp / pullLength : 0;
        boolean icy = star.kind() == GuixuStellarEnd.BodyKind.RINGED_GIANT;
        float red = icy ? .93F : .72F, blue = icy ? .88F : .47F;
        float first = bands.getFirst().radius(), width = bands.getLast().radius() - first;
        for (int segment = 0; segment < segments; segment++) {
            double from = segment * Math.TAU / segments, to = (segment + 1) * Math.TAU / segments;
            float fromX = (float) Math.cos(from), fromY = (float) Math.sin(from);
            float toX = (float) Math.cos(to), toY = (float) Math.sin(to);
            float fromFacing = fromX * pullRight + fromY * pullUp, toFacing = toX * pullRight + toY * pullUp;
            for (int band = 1; band < bands.size(); band++) {
                var inner = bands.get(band - 1);
                var outer = bands.get(band);
                if (inner.opacity() == 0 && outer.opacity() == 0) continue;
                ringVertex(vertices, star, inner, (inner.radius() - first) / width, from, fromX, fromY, fromFacing, red, blue);
                ringVertex(vertices, star, inner, (inner.radius() - first) / width, to, toX, toY, toFacing, red, blue);
                ringVertex(vertices, star, outer, (outer.radius() - first) / width, to, toX, toY, toFacing, red, blue);
                ringVertex(vertices, star, outer, (outer.radius() - first) / width, from, fromX, fromY, fromFacing, red, blue);
            }
        }
    }

    private void ringVertex(VertexConsumer vertices, GuixuStellarEnd.Star star, GuixuPlanetSystems.RingBand band,
                            float outer, double angle, float x, float y, float facing, float red, float blue) {
        float radius = star.solidRadius() * band.radius();
        normal.set(right).mul(x).fma(y, up);
        point.set(center).fma(radius, normal)
                .fma(radius * star.tidalStress() * outer * (.25F + .40F * Math.max(0, normal.dot(pull))), pull);
        float ringStress = Math.max(star.tidalStress(), star.disruption() * .77F);
        float transmission = GuixuStellarEnd.ringTransmission(ringStress, facing, outer);
        // 内环粒子跑得更快，背光的一侧受行星本体遮光。
        double phase = angle - seconds * GuixuPlanetSystems.orbitSpeed(band.radius()) + star.phase();
        float density = .77F + .15F * (float) Math.sin(phase * 5) + .08F * (float) Math.cos(phase * 9 + outer * 12);
        float side = normal.dot(light);
        float impact = band.radius() * (float) Math.sqrt(Math.max(0, 1 - side * side));
        float shadow = side < 0 ? .20F + .80F * Math.clamp((impact - .86F) / .23F, 0F, 1F) : 1F;
        float opacity = star.brightness() * band.opacity() * density * shadow * transmission;
        ambientVertex(vertices, matrix, point.x, point.y, point.z, red, .81F, blue,
                opacity);
    }

    private void drawAccretionDisk(VertexConsumer vertices, GuixuStellarEnd.Star star, int segments) {
        right.set(1, 0, 0).rotate(axis);
        up.set(0, 0, 1).rotate(axis);
        pole.set(0, 1, 0).rotate(axis);
        float width = star.kind().diskWidth();
        float power = .78F * (1 - .86F * star.disruption());
        float light = star.brightness() * power * (1 - .28F * star.tidalStress());
        if (light <= .001F) return;
        float invDistance = 1F / Math.max(view.length(), 1e-4F);
        float viewX = view.dot(right) * invDistance, viewY = view.dot(up) * invDistance;
        for (int i = 0; i < segments; i++) {
            double from = i * Math.TAU / segments, to = (i + 1) * Math.TAU / segments;
            // 双层薄盘保留侧视时的厚度，峰值沿半径错相形成旋臂。
            for (int side = -1; side <= 1; side += 2) {
                for (int j = 1; j < ACCRETION_BANDS.length; j++) {
                    var inner = ACCRETION_BANDS[j - 1];
                    var outer = ACCRETION_BANDS[j];
                    diskVertex(vertices, star, inner, from, side, width, light, viewX, viewY);
                    diskVertex(vertices, star, inner, to, side, width, light, viewX, viewY);
                    diskVertex(vertices, star, outer, to, side, width, light, viewX, viewY);
                    diskVertex(vertices, star, outer, from, side, width, light, viewX, viewY);
                }
            }
        }
    }

    private void diskVertex(VertexConsumer vertices, GuixuStellarEnd.Star star, DiskBand band, double angle,
                            int side, float width, float light, float viewX, float viewY) {
        double radial = 1 + (band.radius() - 1) * width;
        double spiral = angle * 3 - band.radius() * 6.9 - seconds * band.angularSpeed() + star.phase();
        float ridge = (float) (.5 + .5 * Math.cos(spiral));
        float radius = (float) (star.solidRadius() * radial);
        float height = star.solidRadius() * band.height() * (.62F + .38F * ridge) * side;
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        normal.set(right).mul(cos).fma(sin, up);
        float tidal = radius * Math.max(0F, normal.dot(pull)) * star.tidalStress() * .13F;
        point.set(center).fma(radius, normal).fma(height, pole).fma(tidal, pull);
        double shift = GuixuQuasarOptics.diskShift(radial, -sin * viewX + cos * viewY);
        double outer = GuixuStellarEnd.ease(1.23, 2.85, radial);
        double density = 1 - .96 * outer * star.disruption() * (.68 + .32 * Math.max(0, normal.dot(pull)));
        float emission = GuixuQuasarOptics.beamedLight(1.6 * band.light() * light * (.47F + .53F * ridge * ridge) * density, shift);
        float red = Math.min(1F, band.red() * (float) (1.20 - .20 * shift));
        float blue = Math.min(1F, band.blue() * (float) (.64 + .36 * shift));
        ambientVertex(vertices, matrix, point.x, point.y, point.z, red, band.green(), blue, emission);
    }

    private void drawJets(VertexConsumer vertices, GuixuStellarEnd.Star star, float length, int steps,
                          GuixuStellarEnd.QuasarIgnition ignition) {
        boolean pulsar = star.kind() == GuixuStellarEnd.BodyKind.PULSAR;
        pole.set(0, 1, 0);
        // 脉冲星束沿倾斜的磁轴扫动，类星体喷流沿吸积盘法线。
        if (pulsar) pole.rotateZ(PULSAR_MAGNETIC_TILT).rotateY(star.kind().surfaceSpin(seconds, star.phase()));
        pole.rotate(axis);
        right.set(pole).cross(view);
        if (right.lengthSquared() < 1e-8F) {
            right.set(1, 0, 0).rotate(axis);
            right.fma(-right.dot(pole), pole);
        }
        right.normalize();
        float alignment = Math.clamp(pole.dot(view) / Math.max(view.length(), 1e-4F), -1F, 1F);
        float flare = ignition == null ? 0 : ignition.flare();
        float disruption = star.disruption();
        float reach = (ignition == null ? 1 : ignition.jetReach()) * (1 - .78F * disruption);
        jetBend = disruption * (pulsar ? 1.05F : .90F);
        float light = star.brightness() * (pulsar ? .49F + .48F * alignment * alignment
                : Math.min(1, reach * 7) * (.87F + .38F * flare));
        length = .78F + (length - .78F) * reach;
        var pulse = GuixuStellarEnd.jetPulse(star.kind(), seconds, star.phase());
        if (pulsar) light *= 1 - disruption * (.68F - .40F * pulse.light());
        else light *= 1 - .83F * disruption;
        float pulseLight = star.brightness() * pulse.light() * (pulsar ? .92F : .98F) * (1 - .82F * disruption);
        for (int sign = -1; sign <= 1; sign += 2) {
            double shift = pulsar ? 1 : GuixuQuasarOptics.jetShift(alignment * sign);
            // 提升喷流中可见的蓝白核心，并为退行侧保留较弱的红紫鞘层。
            if (!pulsar) drawQuasarSheath(vertices, star, length, light, flare, sign, steps, shift);
            for (int i = 0; i < steps; i++) {
                float from = i / (float) steps, to = (i + 1F) / steps;
                for (int edge = -1; edge <= 1; edge += 2) {
                    jetVertex(vertices, star, pulsar, length, light, flare, sign, edge, from, shift);
                    jetVertex(vertices, star, pulsar, length, light, flare, sign, 0, from, shift);
                    jetVertex(vertices, star, pulsar, length, light, flare, sign, 0, to, shift);
                    jetVertex(vertices, star, pulsar, length, light, flare, sign, edge, to, shift);
                }
            }
            if (pulseLight > .001F) {
                float peak = pulsar ? pulseLight : GuixuQuasarOptics.beamedLight(1.35 * pulseLight, shift);
                drawJetPulse(vertices, star.solidRadius(), length, pulsar, sign, pulse.position(), peak);
            }
        }
        if (!pulsar && view.lengthSquared() > 1e-8F) {
            float endOn = Math.max(0F, (Math.abs(alignment) - .965F) / .035F);
            if (flare <= .001F && endOn <= .001F) return;
            up.set(view).normalize().cross(right);
            float size = star.solidRadius() * (.35F + flare * .50F);
            for (int sign = -1; sign <= 1; sign += 2) {
                float nozzle = GuixuQuasarOptics.beamedLight(light * (flare + .33F * endOn),
                        GuixuQuasarOptics.jetShift(alignment * sign));
                point.set(center).fma(sign * star.solidRadius() * 1.17F, pole);
                drawJetNozzle(vertices, size * (1 + .75F * flare), size * (.52F + .48F * endOn), nozzle);
            }
        }
    }

    private void drawJetNozzle(VertexConsumer vertices, float width, float height, float light) {
        int segments = 16;
        for (int i = 0; i < segments; i++) {
            ambientVertex(vertices, matrix, point.x, point.y, point.z, .70F, .86F, 1F, light);
            for (int edge = i; edge <= i + 1; edge++) {
                double angle = edge * Math.TAU / segments;
                normal.set(point).fma(width * (float) Math.cos(angle), right).fma(height * (float) Math.sin(angle), up);
                ambientVertex(vertices, matrix, normal.x, normal.y, normal.z, .34F, .61F, 1F, 0);
            }
            ambientVertex(vertices, matrix, point.x, point.y, point.z, .70F, .86F, 1F, light);
        }
    }

    private void drawQuasarSheath(VertexConsumer vertices, GuixuStellarEnd.Star star, float length, float light,
                                 float flare, int sign, int steps, double shift) {
        float opacity = GuixuQuasarOptics.beamedLight(light * (.17 + .21 * flare), .55 + .45 * shift);
        for (int i = 0; i < steps; i++) {
            float from = i / (float) steps, to = (i + 1F) / steps;
            for (int edge = -1; edge <= 1; edge += 2) {
                sheathVertex(vertices, star.solidRadius(), length, opacity, sign, edge, from);
                sheathVertex(vertices, star.solidRadius(), length, opacity, sign, 0, from);
                sheathVertex(vertices, star.solidRadius(), length, opacity, sign, 0, to);
                sheathVertex(vertices, star.solidRadius(), length, opacity, sign, edge, to);
            }
        }
    }

    private void sheathVertex(VertexConsumer vertices, float radius, float length, float light, int sign,
                              int edge, float progress) {
        float width = jetWidth(false, progress) * (2.2F + 1.3F * progress * progress);
        jetPoint(radius, length, sign, progress, edge * width);
        ambientVertex(vertices, matrix, point.x, point.y, point.z, .46F, .29F, 1F,
                edge == 0 ? light * (1 - progress * progress) : 0);
    }

    /** 亮峰直接按时间定位，在两个档位都不会卡在喷流的分段顶点上。 */
    private void drawJetPulse(VertexConsumer vertices, float radius, float length, boolean pulsar, int sign,
                              float peak, float light) {
        float size = pulsar ? .095F : .135F;
        float tail = Math.max(0, peak - size * 1.55F);
        float tip = Math.min(1, peak + size * .72F);
        float width = jetWidth(pulsar, peak) * (pulsar ? 1.14F : .66F);
        for (int side = -1; side <= 1; side += 2) {
            pulseVertex(vertices, radius, length, sign, tail, side * width * .35F, 0);
            pulseVertex(vertices, radius, length, sign, tail, 0, 0);
            pulseVertex(vertices, radius, length, sign, peak, 0, light);
            pulseVertex(vertices, radius, length, sign, peak, side * width, 0);
            pulseVertex(vertices, radius, length, sign, peak, side * width, 0);
            pulseVertex(vertices, radius, length, sign, peak, 0, light);
            pulseVertex(vertices, radius, length, sign, tip, 0, 0);
            pulseVertex(vertices, radius, length, sign, tip, side * width * .28F, 0);
        }
    }

    private void pulseVertex(VertexConsumer vertices, float radius, float length, int sign, float position,
                             float offset, float light) {
        jetPoint(radius, length, sign, position, offset);
        ambientVertex(vertices, matrix, point.x, point.y, point.z, 1F, .98F, 1F, light);
    }

    private void jetVertex(VertexConsumer vertices, GuixuStellarEnd.Star star, boolean pulsar, float length,
                           float light, float flare, int sign, int edge, float progress, double shift) {
        jetPoint(star.solidRadius(), length, sign, progress, edge * jetWidth(pulsar, progress));
        float wave = (float) (.5 + .5 * Math.cos(progress * 13 - seconds * 8 + star.phase()));
        float taper = pulsar ? 1 - progress : 1 - progress * progress;
        float crest = flare * Math.max(0, 1 - 8 * Math.abs(progress - .83F));
        float emission = light * (taper * (.62F + .38F * wave) + crest);
        float opacity = edge == 0 ? (pulsar ? Math.min(1F, emission) : GuixuQuasarOptics.beamedLight(3.25 * emission, shift)) : 0;
        float blueShift = (float) Math.clamp(shift, 0, 1);
        float red = pulsar ? (edge == 0 ? .85F : .18F) : .88F - .40F * blueShift;
        float green = pulsar ? (edge == 0 ? .99F : .66F) : .33F + .57F * blueShift;
        ambientVertex(vertices, matrix, point.x, point.y, point.z, red, green, 1F, opacity);
    }

    private void jetPoint(float radius, float length, int sign, float progress, float offset) {
        float along = radius * sign * (.78F + (length - .78F) * progress);
        point.set(center).fma(along, pole).fma(radius * offset, right)
                .fma(radius * jetBend * progress * progress, pull);
    }

    private static float jetWidth(boolean pulsar, float progress) {
        return pulsar ? .16F - .06F * progress
                : .12F + .20F * (float) Math.sqrt(progress) + .34F * progress * progress * progress;
    }

    private void drawLimb(VertexConsumer vertices, GuixuStellarEnd.Star star, int segments, float visibility) {
        if (star.kind() == GuixuStellarEnd.BodyKind.STAR) {
            drawStellarCorona(vertices, star, segments);
            return;
        }
        if (star.kind() == GuixuStellarEnd.BodyKind.WHITE_DWARF) {
            drawWhiteDwarfLimb(vertices, star, segments);
            return;
        }
        var style = switch (star.kind()) {
            case NEUTRON_STAR -> NEUTRON_LIMB;
            case PULSAR -> PULSAR_LIMB;
            case BLACK_HOLE -> HOLE_LIMB;
            case WHITE_HOLE -> WHITE_HOLE_LIMB;
            case THORNE_ZYTKOW -> SUPERGIANT_LIMB;
            default -> star.warm() ? WARM_LIMB : COOL_LIMB;
        };
        boolean photonRing = star.kind().hasAccretionDisk();
        boolean reflecting = star.kind() == GuixuStellarEnd.BodyKind.ROCKY || star.kind().hasPlanetaryRings();
        if (reflecting) setDirectLight();
        float lightX = reflecting ? right.dot(light) : 0F, lightY = reflecting ? up.dot(light) : 0F;
        float red = style.red(), green = style.green(), blue = style.blue();
        float edge = star.kind().luminousSurface() ? 1.045F : 1.016F;
        for (int i = 0; i < segments; i++) {
            double from = i * Math.TAU / segments, to = (i + 1) * Math.TAU / segments;
            float fromLight = visibility * limbLight(star, from, style.light());
            float toLight = visibility * limbLight(star, to, style.light());
            if (reflecting) {
                fromLight *= (float) Math.max(.06, Math.cos(from) * lightX + Math.sin(from) * lightY);
                toLight *= (float) Math.max(.06, Math.cos(to) * lightX + Math.sin(to) * lightY);
            }
            limbVertex(vertices, star, from, .955F, red, green, blue, 0);
            limbVertex(vertices, star, to, .955F, red, green, blue, 0);
            limbVertex(vertices, star, to, edge, red, green, blue, toLight);
            limbVertex(vertices, star, from, edge, red, green, blue, fromLight);
            limbVertex(vertices, star, from, edge, red, green, blue, fromLight);
            limbVertex(vertices, star, to, edge, red, green, blue, toLight);
            limbVertex(vertices, star, to, limbExtent(star, to, style.extent()), red, green, blue, 0);
            limbVertex(vertices, star, from, limbExtent(star, from, style.extent()), red, green, blue, 0);
            if (photonRing) {
                limbVertex(vertices, star, from, 1.008F, 1F, .92F, .85F, fromLight * .72F);
                limbVertex(vertices, star, to, 1.008F, 1F, .92F, .85F, toLight * .72F);
                limbVertex(vertices, star, to, 1.055F, red, green, blue, 0);
                limbVertex(vertices, star, from, 1.055F, red, green, blue, 0);
            }
        }
    }

    private void drawStellarCorona(VertexConsumer vertices, GuixuStellarEnd.Star star, int segments) {
        var tint = star.warm() ? WARM_LIMB : COOL_LIMB;
        double phase = star.phase() + seconds * .11;
        float tidalLight = .26F * star.disruption();
        coronaFrom.set(right);
        float density = coronaDensity(0, phase);
        float focus = tidalLight * Math.max(0F, right.dot(pull));
        for (int i = 1; i <= segments; i++) {
            double angle = i == segments ? 0 : i * Math.TAU / segments;
            coronaTo.set(right).mul((float) Math.cos(angle)).fma((float) Math.sin(angle), up);
            float nextDensity = coronaDensity(angle, phase);
            float nextFocus = tidalLight * Math.max(0F, coronaTo.dot(pull));
            float fromWidth = .94F + .11F * density + .16F * focus;
            float toWidth = .94F + .11F * nextDensity + .16F * nextFocus;
            float fromLight = star.brightness() * density * (1 + focus);
            float toLight = star.brightness() * nextDensity * (1 + nextFocus);
            // 贴着光球边缘绘制高温等离子体，逐层降低亮度并过渡到较淡的外缘。
            for (int ring = 1; ring < STELLAR_CORONA.length; ring++) {
                var inner = STELLAR_CORONA[ring - 1];
                var outer = STELLAR_CORONA[ring];
                coronaVertex(vertices, star, coronaFrom, inner, tint, fromWidth, fromLight);
                coronaVertex(vertices, star, coronaTo, inner, tint, toWidth, toLight);
                coronaVertex(vertices, star, coronaTo, outer, tint, toWidth, toLight);
                coronaVertex(vertices, star, coronaFrom, outer, tint, fromWidth, fromLight);
            }
            coronaFrom.set(coronaTo);
            density = nextDensity;
            focus = nextFocus;
        }
    }

    private static float coronaDensity(double angle, double phase) {
        return (float) (.80 + .12 * Math.cos(2 * angle + phase) + .07 * Math.sin(3 * angle - 1.63 * phase)
                + .045 * Math.cos(5 * angle + .58 * phase));
    }

    private void coronaVertex(VertexConsumer vertices, GuixuStellarEnd.Star star, Vector3f radial,
                              CoronaBand band, LimbStyle tint, float width, float light) {
        float mix = band.tint();
        limbVertex(vertices, star, radial, 1 + band.offset() * width,
                1 - (1 - tint.red()) * mix, 1 - (1 - tint.green()) * mix, 1 - (1 - tint.blue()) * mix,
                band.opacity() * light);
    }

    private void drawWhiteHoleEjecta(VertexConsumer vertices, GuixuStellarEnd.Star star, float distance, int segments,
                                    float shift) {
        double clock = seconds + star.phase() / Math.TAU * 2.3;
        float light = 1.85F * star.brightness() * shift * shift;
        float red = 1F - .35F * shift;
        float green = .49F + .41F * shift, blue = .28F + .72F * shift;
        for (int shell = 0; shell < 2; shell++) {
            double age = clock - shell * 1.15;
            age -= Math.floor(age / 2.3) * 2.3;
            float opacity = light * GuixuStellarEnd.whiteHoleShellLight(age);
            if (opacity <= .001F) continue;
            float radius = star.solidRadius() * GuixuStellarEnd.whiteHoleShellRadius(age);
            if (distance <= radius) continue;
            float offset = GuixuStellarEnd.limbOffset(radius, distance);
            float rim = GuixuStellarEnd.limbRadius(radius, distance);
            float width = star.solidRadius() * .19F * (rim / radius);
            float x = 1F, y = 0F;
            for (int i = 1; i <= segments; i++) {
                double angle = i * Math.TAU / segments;
                float nextX = (float) Math.cos(angle), nextY = (float) Math.sin(angle);
                shellVertex(vertices, offset, rim - width, x, y, red, green, blue, 0);
                shellVertex(vertices, offset, rim - width, nextX, nextY, red, green, blue, 0);
                shellVertex(vertices, offset, rim, nextX, nextY, red, green, blue, opacity);
                shellVertex(vertices, offset, rim, x, y, red, green, blue, opacity);
                shellVertex(vertices, offset, rim, x, y, red, green, blue, opacity);
                shellVertex(vertices, offset, rim, nextX, nextY, red, green, blue, opacity);
                shellVertex(vertices, offset, rim + width, nextX, nextY, red, green, blue, 0);
                shellVertex(vertices, offset, rim + width, x, y, red, green, blue, 0);
                x = nextX;
                y = nextY;
            }
        }
    }

    private void shellVertex(VertexConsumer vertices, float offset, float radius, float x, float y,
                             float red, float green, float blue, float opacity) {
        point.set(center).fma(offset, view).fma(radius * x, right).fma(radius * y, up);
        ambientVertex(vertices, matrix, point.x, point.y, point.z, red, green, blue, opacity);
    }

    private void drawAnnihilation(VertexConsumer vertices, GuixuStellarEnd.Star star, int spots) {
        // 用当前物质流亮度近似普通物质的供给，只在相接的半球标出伽马辐射假色。
        float supply = star.brightness() * Math.min(1F, 1.65F * star.matterBrightness())
                * (.14F + .86F * star.disruption());
        if (supply <= .001F) return;
        rotation.rotationTo(0, 1, 0, pull.x, pull.y, pull.z);
        float radius = star.solidRadius();
        for (int i = 0; i < spots; i++) {
            double phase = star.phase() + i * 2.399963 + seconds * .31;
            float facing = .35F + .6F * (i + .5F) / spots;
            float lateral = (float) Math.sqrt(1 - facing * facing);
            normal.set(lateral * (float) Math.cos(phase), facing, lateral * (float) Math.sin(phase)).rotate(rotation);
            float flicker = .52F + .48F * (float) Math.sin(phase * 2 + seconds * 3.3);
            float opacity = GuixuStellarEnd.antistarAnnihilation(facing, supply) * flicker;
            if (opacity <= .001F) continue;
            point.set(center).fma(radius * (1.06F + .1F * flicker), normal)
                    .fma(radius * star.tidalStress() * facing * facing, pull);
            float size = radius * (.09F + .09F * flicker);
            drawAnnihilationSpot(vertices, size, opacity);
        }
    }

    private void drawAnnihilationSpot(VertexConsumer vertices, float size, float opacity) {
        // 在加性 QUADS 批次内用六个渐隐三角形拼合单个闪斑。
        float x = size, y = 0;
        for (int edge = 0; edge < 6; edge++) {
            float nextX = .5F * x - .8660254F * y, nextY = .8660254F * x + .5F * y;
            ambientVertex(vertices, matrix, point.x, point.y, point.z, .84F, .47F, 1F, opacity);
            annihilationEdge(vertices, x, y);
            annihilationEdge(vertices, nextX, nextY);
            ambientVertex(vertices, matrix, point.x, point.y, point.z, .84F, .47F, 1F, opacity);
            x = nextX;
            y = nextY;
        }
    }

    private void annihilationEdge(VertexConsumer vertices, float x, float y) {
        normal.set(point).fma(x, right).fma(y, up);
        ambientVertex(vertices, matrix, normal.x, normal.y, normal.z, .63F, .22F, 1F, 0);
    }

    private void drawWhiteDwarfLimb(VertexConsumer vertices, GuixuStellarEnd.Star star, int segments) {
        float light = star.brightness();
        float heating = GuixuStellarEnd.whiteDwarfTidalLight(star);
        float inwardX = right.dot(pull), inwardY = up.dot(pull);
        double from = 0;
        float fromHeat = heating * Math.max(0F, inwardX);
        for (int i = 1; i <= segments; i++) {
            double to = i * Math.TAU / segments;
            float toHeat = heating * Math.max(0F, inwardX * (float) Math.cos(to) + inwardY * (float) Math.sin(to));
            float fromRim = light * (.51F + .39F * fromHeat * fromHeat);
            float toRim = light * (.51F + .39F * toHeat * toHeat);
            float fromHalo = light * (.12F + .18F * fromHeat * fromHeat);
            float toHalo = light * (.12F + .18F * toHeat * toHeat);
            limbVertex(vertices, star, from, .98F, .83F, .93F, 1F, 0);
            limbVertex(vertices, star, to, .98F, .83F, .93F, 1F, 0);
            limbVertex(vertices, star, to, 1.012F, .83F, .93F, 1F, toRim);
            limbVertex(vertices, star, from, 1.012F, .83F, .93F, 1F, fromRim);
            limbVertex(vertices, star, from, 1.012F, .83F, .93F, 1F, fromRim);
            limbVertex(vertices, star, to, 1.012F, .83F, .93F, 1F, toRim);
            limbVertex(vertices, star, to, 1.075F, .31F, .59F, 1F, toHalo);
            limbVertex(vertices, star, from, 1.075F, .31F, .59F, 1F, fromHalo);
            limbVertex(vertices, star, from, 1.075F, .31F, .59F, 1F, fromHalo);
            limbVertex(vertices, star, to, 1.075F, .31F, .59F, 1F, toHalo);
            limbVertex(vertices, star, to, 1.26F + .10F * toHeat, .31F, .59F, 1F, 0);
            limbVertex(vertices, star, from, 1.26F + .10F * fromHeat, .31F, .59F, 1F, 0);
            from = to;
            fromHeat = toHeat;
        }
    }

    private float limbLight(GuixuStellarEnd.Star star, double angle, float light) {
        if (star.kind().hasPlanetaryRings()) {
            return star.brightness() * (light + .38F * star.disruption() * inwardSide(angle));
        }
        if (star.kind() == GuixuStellarEnd.BodyKind.NEUTRON_STAR) {
            float oscillation = .55F + .45F * (float) Math.cos(seconds * 13 + star.phase());
            return star.brightness() * (light + .67F * star.disruption() * inwardSide(angle) * oscillation);
        }
        return star.brightness() * (light + .24F * star.tidalStress());
    }

    private float limbExtent(GuixuStellarEnd.Star star, double angle, float extent) {
        if (star.kind().hasPlanetaryRings()) return extent + .62F * star.disruption() * inwardSide(angle);
        return extent;
    }

    private float inwardSide(double angle) {
        return Math.max(0F, (float) (right.dot(pull) * Math.cos(angle) + up.dot(pull) * Math.sin(angle)));
    }

    private void limbVertex(VertexConsumer vertices, GuixuStellarEnd.Star star, double angle, float scale,
                            float red, float green, float blue, float opacity) {
        normal.set(right).mul((float) Math.cos(angle)).fma((float) Math.sin(angle), up);
        limbVertex(vertices, star, normal, scale, red, green, blue, opacity);
    }

    private void limbVertex(VertexConsumer vertices, GuixuStellarEnd.Star star, Vector3f radial, float scale,
                            float red, float green, float blue, float opacity) {
        float radius = star.solidRadius();
        float facing = (limbRadius * radial.dot(pull) + limbOffset * view.dot(pull)) / radius;
        float stretch = Math.max(0, facing);
        float bulge = 1 - compression * (1 - facing * facing);
        point.set(center).fma(bulge * limbOffset, view).fma(bulge * scale * limbRadius, radial)
                .fma(radius * star.tidalStress() * stretch * stretch, pull);
        ambientVertex(vertices, matrix, point.x, point.y, point.z, red, green, blue, opacity);
    }

    private static RenderType bodyLayer(boolean animate) {
        if (animate) {
            if (animatedBodyLayer == null) animatedBodyLayer = createBodyLayer(true);
            return animatedBodyLayer;
        }
        if (bodyLayer == null) bodyLayer = createBodyLayer(false);
        return bodyLayer;
    }

    private static RenderType createBodyLayer(boolean animate) {
        return RenderType.create(animate ? "shanhai_guixu_celestial_animated" : "shanhai_guixu_celestial_body",
                DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 32768, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(
                                animate ? GuixuCelestialShader::shader : GameRenderer::getPositionTexColorShader))
                        .setTextureState(new RenderStateShard.TextureStateShard(TEXTURE, true, false))
                        .setDepthTestState(new RenderStateShard.DepthTestStateShard("lequal", GL11.GL_LEQUAL))
                        .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, true))
                        .createCompositeState(false));
    }

    private static RenderType horizonDepthLayer() {
        if (horizonDepthLayer == null) {
            horizonDepthLayer = RenderType.create("shanhai_guixu_stellar_horizon_depth", DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS, 32768, false, false, RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader))
                            .setDepthTestState(new RenderStateShard.DepthTestStateShard("lequal", GL11.GL_LEQUAL))
                            .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(false, true))
                            .createCompositeState(false));
        }
        return horizonDepthLayer;
    }

    private static final class Sphere {
        final SpherePoint[] points;
        final int[] indices;
        final float[] transformed;

        Sphere(int latitudes, int longitudes) {
            this(latitudes, longitudes, 0, 0);
        }

        Sphere(int latitudes, int longitudes, float roughness, double seed) {
            int stride = longitudes + 1;
            points = new SpherePoint[(latitudes + 1) * stride];
            transformed = new float[points.length * 7];
            indices = new int[latitudes * longitudes * 4];
            int next = 0;
            for (int lat = 0; lat <= latitudes; lat++) {
                double theta = Math.PI * lat / latitudes;
                for (int lon = 0; lon <= longitudes; lon++) {
                    double phi = Math.TAU * lon / longitudes;
                    int index = lat * stride + lon;
                    double x = Math.sin(theta) * Math.cos(phi), y = Math.cos(theta), z = Math.sin(theta) * Math.sin(phi);
                    float ridge = 1 + roughness * (float) (.65 * Math.sin(x * 6 + y * 3.8 + z * 4 + seed)
                            + .35 * Math.cos(x * 5 - z * 5.8 + seed));
                    points[index] = new SpherePoint((float) (x * ridge), (float) (y * ridge * (1 - .8F * roughness)),
                            (float) (z * ridge), lon / (float) longitudes, lat / (float) latitudes);
                    if (lat == latitudes || lon == longitudes) continue;
                    indices[next++] = index;
                    indices[next++] = index + 1;
                    indices[next++] = index + stride + 1;
                    indices[next++] = index + stride;
                }
            }
        }
    }

    private record SpherePoint(float x, float y, float z, float u, float v) {}

    private record DiskBand(float radius, float height, float light, float red, float green, float blue,
                            double angularSpeed) {
        private DiskBand(float radius, float height, float light, float red, float green, float blue) {
            this(radius, height, light, red, green, blue, GuixuQuasarOptics.angularSpeed(radius));
        }
    }

    private record LimbStyle(float red, float green, float blue, float light, float extent) {}

    private record CoronaBand(float offset, float opacity, float tint) {}
}
