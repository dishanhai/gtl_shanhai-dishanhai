package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.renderer.blackhole.BlackHoleLensRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuStarRenderer.ambientGlint;
import static com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuStarRenderer.ambientVertex;

/** 群星终焉共用归墟的加性顶点层，在环心坐标中绘制，无需额外着色器。 */
public final class GuixuStellarEndRenderer {
    private static final Map<BlockPos, DeferredFrame> PENDING = new LinkedHashMap<>();
    private static int pendingFrame = -1;

    private GuixuStellarEndRenderer() {}

    static void capture(PoseStack pose, Level level, BlockPos controller, Direction facing, float[] camera,
                        double seconds, float horizon, float spinSeconds, boolean simple,
                        List<GuixuStellarEnd.GravityWave> waves) {
        if (horizon <= 0 || seconds >= GuixuStellarEnd.LAST_CAPTURE_SECONDS) {
            render(pose, level, controller, facing, camera, seconds, horizon, spinSeconds, simple, waves);
            return;
        }
        if (seconds <= 0) return;
        int frame = BlackHoleLensRenderer.upcomingFrameSerial();
        if (frame != pendingFrame) PENDING.clear();
        pendingFrame = frame;
        PENDING.put(controller.immutable(), new DeferredFrame(level, facing, camera, seconds, horizon, spinSeconds, simple, waves,
                new Matrix4f(pose.last().pose()), new Matrix4f(RenderSystem.getModelViewMatrix()),
                new Matrix4f(RenderSystem.getProjectionMatrix()), RenderSystem.getVertexSorting()));
    }

    /** 保留本帧世界深度，天体颜色在黑洞透镜完成取样后才写入。 */
    public static void flushAfterLens(int frame) {
        if (PENDING.isEmpty()) return;
        if (pendingFrame != frame) {
            PENDING.clear();
            return;
        }
        Level level = Minecraft.getInstance().level;
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        RenderSystem.backupProjectionMatrix();
        try {
            PoseStack pose = new PoseStack();
            for (var entry : PENDING.entrySet()) {
                DeferredFrame captured = entry.getValue();
                if (captured.level() != level) continue;
                pose.last().pose().set(captured.pose());
                modelView.last().pose().set(captured.modelView());
                RenderSystem.applyModelViewMatrix();
                RenderSystem.setProjectionMatrix(captured.projection(), captured.sorting());
                render(pose, level, entry.getKey(), captured.facing(), captured.camera(), captured.seconds(),
                        captured.horizon(), captured.spinSeconds(), captured.simple(), captured.waves());
            }
        } catch (RuntimeException e) {
            HostFxFallback.degrade(HostFxFallback.LEVEL_OFF, "群星终焉合成异常 " + e.getClass().getSimpleName());
            GTDishanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] 群星终焉合成失败", e);
        } finally {
            PENDING.clear();
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    private record DeferredFrame(Level level, Direction facing, float[] camera, double seconds, float horizon,
                                 float spinSeconds, boolean simple, List<GuixuStellarEnd.GravityWave> waves,
                                 Matrix4f pose, Matrix4f modelView,
                                 Matrix4f projection, VertexSorting sorting) {}

    private static void render(PoseStack pose, Level level, BlockPos controller, Direction facing, float[] camera,
                               double seconds, float horizon, float spinSeconds, boolean simple,
                               List<GuixuStellarEnd.GravityWave> waves) {
        if (seconds <= 0 || (horizon <= 0 && seconds <= GuixuStellarEnd.STRUCTURE_START_SECONDS)) return;
        var layer = HostFxDraw.beamLayer();
        var buffers = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        Matrix4f matrix = pose.last().pose();
        GuixuStellarEnd.Star[] stars = null;
        GuixuCelestialRenderer celestial = null;
        float flash = 0, outflow = 0;
        if (horizon > 0 && seconds < GuixuStellarEnd.LAST_CAPTURE_SECONDS) {
            stars = new GuixuStellarEnd.Star[GuixuStellarEnd.STAR_COUNT];
            for (int i = 0; i < GuixuStellarEnd.STAR_COUNT; i++) {
                if (!GuixuStellarEnd.drawsSource(i, simple)) continue;
                stars[i] = GuixuStellarEnd.star(i, seconds, 45);
                flash = Math.max(flash, GuixuStellarEnd.captureFlash(stars[i]));
                outflow = Math.max(outflow, GuixuStellarEnd.stellarOutflow(stars[i]));
            }
            celestial = new GuixuCelestialRenderer(matrix, camera, seconds, horizon, simple);
            celestial.drawHorizonDepth(buffers);
            celestial.drawBodies(buffers, stars);
        }
        VertexConsumer vertices = buffers.getBuffer(layer);
        var frame = new Draw(vertices, matrix, camera, facing);
        if (horizon > 0) frame.horizon(seconds, horizon, flash, simple ? 48 : 80);
        if (outflow > .001F) {
            GuixuStarRenderer.drawJets(vertices, matrix, camera, facing, seconds, horizon, outflow);
        }
        if (celestial != null) {
            celestial.drawAuras(vertices, stars, simple ? 32 : 56);
            for (var star : stars) {
                if (star == null) continue;
                frame.star(star, seconds, horizon, simple ? 8 : 16, !simple);
                frame.capture(star, horizon, simple ? 8 : 16);
            }
        }
        for (var wave : waves) frame.gravityWave(wave, simple ? 32 : 64);
        if (seconds > GuixuStellarEnd.STRUCTURE_START_SECONDS) {
            var charge = new GuixuStellarChargeRenderer(vertices, matrix, camera, seconds);
            charge.feeds(horizon, spinSeconds);
            charge.surface(GuixuStructureLightning.surface(level, controller, facing, spinSeconds));
        }
        buffers.endBatch(layer);
    }

    private static final class Draw {
        private final VertexConsumer vertices;
        private final Matrix4f matrix;
        private final Vector3f camera, center = new Vector3f(), view = new Vector3f();
        private final Vector3f right = new Vector3f(), up = new Vector3f();
        private final Vector3f a = new Vector3f(), b = new Vector3f(), next = new Vector3f();
        private final Vector3f widthA = new Vector3f(), widthB = new Vector3f();
        private final Vector3d streamPoint = new Vector3d();
        private final Quaternionf diskRotation, streamRotation = new Quaternionf();

        Draw(VertexConsumer vertices, Matrix4f matrix, float[] camera, Direction facing) {
            this.vertices = vertices;
            this.matrix = matrix;
            this.camera = new Vector3f(camera[0], camera[1], camera[2]);
            this.diskRotation = GuixuStarRenderer.diskRotation(facing);
        }

        private void faceCamera() {
            view.set(camera).sub(center);
            if (view.lengthSquared() < 1e-8F) view.set(0, 0, 1);
            view.normalize();
            right.set(view.z, 0, -view.x);
            if (right.lengthSquared() < 1e-8F) right.set(1, 0, 0);
            right.normalize();
            up.set(view).cross(right);
        }

        void horizon(double seconds, float radius, float flash, int segments) {
            if (camera.lengthSquared() <= radius * radius) return;
            center.zero();
            faceCamera();
            double finaleAge = Math.clamp((seconds - GuixuStellarEnd.STRUCTURE_START_SECONDS) / 1.25, 0, 1);
            float powerRelease = .53F * (float) Math.sin(finaleAge * Math.PI);
            float rebound = GuixuStellarEnd.mergerRebound(seconds);
            float light = Math.min(1, GuixuStellarEnd.horizonLight(seconds) + flash * .25F + powerRelease + rebound * .42F);
            for (int i = 0; i < segments; i++) {
                double from = Math.TAU * i / segments, to = Math.TAU * (i + 1) / segments;
                float fromLight = horizonWave(seconds, from) * light, toLight = horizonWave(seconds, to) * light;
                haloStrip(from, to, radius, 1.002F, 1.057F, 0, 0, fromLight, toLight);
                haloStrip(from, to, radius, 1.057F, 1.23F + .28F * flash + .36F * rebound, fromLight, toLight, 0, 0);
            }
        }

        private static float horizonWave(double seconds, double angle) {
            return (float) (.63 + .22 * Math.sin(angle * 3 - seconds * 1.7)
                    + .15 * Math.sin(angle * 7 + seconds * 2.2));
        }

        private void haloStrip(double from, double to, float radius, float inner, float outer,
                               float innerFrom, float innerTo, float outerFrom, float outerTo) {
            haloVertex(from, radius * inner, innerFrom);
            haloVertex(to, radius * inner, innerTo);
            haloVertex(to, radius * outer, outerTo);
            haloVertex(from, radius * outer, outerFrom);
        }

        private void haloVertex(double angle, float radius, float alpha) {
            float x = (float) Math.cos(angle) * radius, y = (float) Math.sin(angle) * radius;
            float gold = (float) (.5 + .5 * Math.sin(angle * 2));
            ambientVertex(vertices, matrix, right.x * x + up.x * y, right.y * x + up.y * y,
                    right.z * x + up.z * y, .47F + .53F * gold, .75F + .16F * gold, 1F - .37F * gold, alpha);
        }

        void capture(GuixuStellarEnd.Star star, float horizon, int segments) {
            if (star.captureAge() <= 0 || star.captureAge() >= GuixuStellarEnd.CAPTURE_PULSE_SECONDS) return;
            float light = GuixuStellarEnd.captureLight(star);
            float flash = GuixuStellarEnd.captureFlash(star);
            if (light <= .001F && flash <= .001F) return;
            if (star.kind() == GuixuStellarEnd.BodyKind.WHITE_HOLE) {
                var wake = GuixuStellarEnd.captureWake(star, light);
                drawCaptureWake(wake.tail(), wake.head() - wake.tail(), light, horizon,
                        Math.max(6, segments * 3 / 4), CaptureLook.OUTFLOW);
                return;
            }
            double azimuth = GuixuStellarEnd.captureAzimuth(star);
            if (flash > .001F) impactFlash(azimuth, horizon, flash);
            float age = (float) (star.captureAge() / GuixuStellarEnd.CAPTURE_PULSE_SECONDS);
            float crest = horizon * (1.045F + age * 1.38F);
            float width = horizon * (.043F + age * .22F);
            float height = horizon * .65F * age * (1 - age);
            double spread = .27 + age * 1.12;
            for (int i = 0; i < segments; i++) {
                double t0 = i / (double) segments, t1 = (i + 1) / (double) segments;
                double from = azimuth + (t0 * 2 - 1) * spread, to = azimuth + (t1 * 2 - 1) * spread;
                float fromLight = light * (float) Math.sin(t0 * Math.PI);
                float toLight = light * (float) Math.sin(t1 * Math.PI);
                captureVertex(star, from, crest - width, 0, 0);
                captureVertex(star, to, crest - width, 0, 0);
                captureVertex(star, to, crest, 0, toLight);
                captureVertex(star, from, crest, 0, fromLight);
                captureVertex(star, from, crest, 0, fromLight);
                captureVertex(star, to, crest, 0, toLight);
                captureVertex(star, to, crest + width * 2, 0, 0);
                captureVertex(star, from, crest + width * 2, 0, 0);
                captureVertex(star, from, crest, -height, 0);
                captureVertex(star, to, crest, -height, 0);
                captureVertex(star, to, crest, height, toLight * .62F);
                captureVertex(star, from, crest, height, fromLight * .62F);
            }
            var wake = GuixuStellarEnd.captureWake(star, light);
            drawCaptureWake(wake.tail(), wake.head() - wake.tail(), light, horizon, segments,
                    star.warm() ? CaptureLook.WARM : CaptureLook.COOL);
            ejecta(star, azimuth, horizon, age, light, segments + segments / 2);
        }

        void gravityWave(GuixuStellarEnd.GravityWave wave, int segments) {
            float forwardX = (float) Math.cos(wave.azimuth()), forwardZ = (float) Math.sin(wave.azimuth());
            for (int i = 0; i < segments; i++) {
                double from = i * Math.TAU / segments, to = (i + 1) * Math.TAU / segments;
                for (int side = -1; side <= 1; side += 2) {
                    float shoulder = side * .33F;
                    waveStrip(wave, forwardX, forwardZ, from, to, side * 1.34F, shoulder);
                    waveStrip(wave, forwardX, forwardZ, from, to, shoulder, 0);
                }
            }
        }

        private void waveStrip(GuixuStellarEnd.GravityWave wave, float forwardX, float forwardZ,
                                  double from, double to, float edge, float inner) {
            waveVertex(wave, forwardX, forwardZ, from, edge);
            waveVertex(wave, forwardX, forwardZ, to, edge);
            waveVertex(wave, forwardX, forwardZ, to, inner);
            waveVertex(wave, forwardX, forwardZ, from, inner);
        }

        private void waveVertex(GuixuStellarEnd.GravityWave wave, float forwardX, float forwardZ,
                                  double angle, float band) {
            float lobes = (float) Math.cos(2 * angle);
            float radius = wave.radius() + wave.width() * .24F * lobes;
            float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
            float x = radius * cos, cross = radius * sin;
            float z = cross * .94F;
            a.set(forwardX * x - forwardZ * z, cross * .342F, forwardZ * x + forwardX * z);
            // 环心线留在轨道平面，光带宽度朝向镜头，低视角仍能读出外扩波前。
            if (band != 0) {
                b.set(-forwardX * sin - forwardZ * cos * .94F, cos * .342F,
                        -forwardZ * sin + forwardX * cos * .94F);
                b.cross(next.set(camera).sub(a));
                if (b.lengthSquared() < 1e-8F) b.set(a);
                a.fma(band, b.normalize(wave.width()));
            }
            float scale = band == 0 ? 1F : Math.abs(band) < .5F ? .52F : 0F;
            float light = wave.light() * scale * (.78F + .22F * Math.abs(lobes));
            ambientVertex(vertices, matrix, a.x, a.y, a.z,
                    .48F + .47F * scale, .63F + .35F * scale, 1F, light);
        }

        private void drawCaptureWake(double tail, double span, float brightness, float horizon,
                                     int segments, CaptureLook look) {
            if (brightness <= .001F) return;
            float spread = (float) Math.min(1, span);
            var edge = look.edge();
            var core = look.core();
            float width = horizon * look.width();
            wakePoint(tail, span, horizon, spread, look.height(), 0, a);
            wakePoint(tail, span, horizon, spread, look.height(), 1.0 / segments, b);
            ribbonWidth(a, b, a, widthA, width * .12F);
            float light = 0;
            for (int i = 1; i <= segments; i++) {
                double fraction = i / (double) segments;
                float nextLight = GuixuStellarEnd.CaptureWake.lightAt(brightness, fraction) * .86F;
                if (i < segments) wakePoint(tail, span, horizon, spread, look.height(), (i + 1.0) / segments, next);
                else next.set(b);
                ribbonWidth(a, next, b, widthB, width * (.12F - .065F * (float) fraction));
                if (widthA.dot(widthB) < 0) widthB.negate();
                for (int side = -1; side <= 1; side += 2) {
                    ribbonVertex(a, widthA, side, edge.r(), edge.g(), edge.b(), 0);
                    ribbonVertex(a, widthA, 0, core.r(), core.g(), core.b(), light);
                    ribbonVertex(b, widthB, 0, core.r(), core.g(), core.b(), nextLight);
                    ribbonVertex(b, widthB, side, edge.r(), edge.g(), edge.b(), 0);
                }
                light = nextLight;
                a.set(b);
                b.set(next);
                widthA.set(widthB);
            }
        }

        private static void wakePoint(double tail, double span, float horizon, float spread, float height,
                                      double fraction, Vector3f point) {
            double angle = tail + span * fraction;
            float taper = spread * (1 - (float) fraction);
            float radius = horizon * (1.13F + .18F * taper);
            point.set(radius * (float) Math.cos(angle), horizon * (.14F + .10F * taper) * height,
                    radius * (float) Math.sin(angle));
        }

        private void impactFlash(double azimuth, float horizon, float flash) {
            float radius = horizon * 1.13F;
            center.set(radius * (float) Math.cos(azimuth), horizon * .14F, radius * (float) Math.sin(azimuth));
            faceCamera();
            float size = Math.max(1.3F, horizon * .48F) * (.8F + .2F * flash);
            for (int i = 0; i < 16; i++) {
                double from = i * Math.TAU / 16, to = (i + 1) * Math.TAU / 16;
                glowVertex(from, 0, flash);
                glowVertex(from, size * 1.65F, 0);
                glowVertex(to, size * 1.65F, 0);
                glowVertex(to, 0, flash);
            }
            glint(size * 2.4F, size * .18F, 1F, .92F, .77F, flash);
            glint(size * .18F, size * 1.85F, .74F, .89F, 1F, flash);
            glint(size * .50F, size * .50F, 1F, 1F, .95F, flash);
        }

        private void glowVertex(double angle, float radius, float alpha) {
            a.set(center).fma(radius * (float) Math.cos(angle), right).fma(radius * (float) Math.sin(angle), up);
            ambientVertex(vertices, matrix, a.x, a.y, a.z, 1F, .89F, .75F, alpha);
        }

        private void ejecta(GuixuStellarEnd.Star star, double azimuth, float horizon, float age, float light, int count) {
            for (int i = 0; i < count; i++) {
                double scatter = i * 2.399963 + star.phase();
                double angle = azimuth + (age * .31 + .12) * Math.cos(scatter) * 2.1;
                float radius = horizon * (1.07F + age * (1.35F + .70F * (float) Math.sin(scatter)));
                float y = horizon * (float) Math.cos(scatter * .73) * (.08F + age * 1.22F);
                float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
                center.set(radius * cos, y, radius * sin);
                faceCamera();
                // 将光屑的长轴对准远离入射点的喷射方向。
                a.set(cos, y / Math.max(horizon, 1), sin);
                a.fma(-a.dot(view), view);
                if (a.lengthSquared() > 1e-8F) {
                    right.set(a).normalize();
                    up.set(view).cross(right);
                }
                float size = (.30F + .13F * (i % 4)) * (1 - age * .48F) * Math.max(1, horizon * .21F);
                glint(size * 2.2F, size * .26F, 1F, .84F, star.warm() ? .46F : 1F, light * .81F);
            }
        }

        private void captureVertex(GuixuStellarEnd.Star star, double angle, float radius, float y, float alpha) {
            ambientVertex(vertices, matrix, radius * (float) Math.cos(angle), y, radius * (float) Math.sin(angle),
                    star.warm() ? 1F : .52F, .83F, star.warm() ? .48F : 1F, alpha);
        }

        void star(GuixuStellarEnd.Star star, double seconds, float horizon, int segments, boolean detailed) {
            float red = star.warm() ? 1F : .40F;
            float green = star.warm() ? .68F : .73F;
            float blue = star.warm() ? .34F : 1F;
            boolean solid = star.kind() != GuixuStellarEnd.BodyKind.GLINT;
            if (star.matterBrightness() > .001F) {
                StreamStyle style = StreamStyle.forStar(star);
                ribbon(star, seconds, horizon, segments, detailed, style);
                if (style.debris > 0) fragments(star, seconds, horizon,
                        Math.max(1, Math.round(segments * style.debris)), style);
            }
            float light = star.brightness();
            if (solid || light <= GuixuStellarEnd.MIN_GLINT_LIGHT) return;
            setPoint(center, GuixuStellarEnd.position(star, star.body(), horizon));
            faceCamera();
            float size = star.size() * (1 - .62F * (float) star.body());
            glint(size * 2.6F, size * .21F, red, green, blue, light * .77F);
            glint(size * .21F, size * 2.1F, red, green, blue, light * .77F);
            glint(size * .68F, size * .68F, 1F, .96F, .89F, light);
        }

        private void ribbon(GuixuStellarEnd.Star star, double seconds, float horizon, int segments,
                            boolean detailed, StreamStyle style) {
            double from = star.body(), length = star.leadingMatter() - from;
            if (length <= .001) return;
            if (star.kind().stellarPass != null) {
                // 恒星流束先转入内盘，加密采样以保留收束弧。
                segments += segments / 2;
            }
            if (star.kind().captureProfile() != GuixuStellarEnd.CaptureProfile.STANDARD && length < .25) {
                // 最后几块距离内，缩短的光带用更少采样维持同一屏幕轮廓。
                segments = Math.clamp((int) Math.ceil(4 * length * segments), 4, segments);
            }
            double step = length / segments;
            float spread = switch (star.kind()) {
                case GAS_GIANT, RINGED_GIANT -> 1 + .78F * star.disruption();
                case STAR, ANTISTAR, THORNE_ZYTKOW -> 1 + .49F * star.disruption();
                case ROCKY -> 1 - .38F * star.disruption();
                default -> 1F;
            };
            float streamWidth = (.09F + star.size() * .34F + star.kind().radius * .16F) * style.width * spread;
            boolean fromBody = star.kind() != GuixuStellarEnd.BodyKind.GLINT;
            float mouth = fromBody ? Math.min(streamWidth, star.solidRadius() * .40F) : streamWidth;
            double opening = Math.min(.11, length * .45);
            int pairs = style.strands;
            if (pairs == 2 && !detailed) pairs = 1;
            float core = Math.min(.72F, .23F * star.kind().siphonStrength());
            float red = style.red, green = style.green, blue = style.blue;
            float coreRed = red + (1 - red) * core, coreGreen = green + (1 - green) * core;
            float coreBlue = blue + (1 - blue) * core;
            placeStreamPoint(a, star, from, horizon);
            placeStreamPoint(b, star, from + step, horizon);
            ribbonWidth(a, b, a, widthA, mouth * (1 - (float) from * .52F));
            float alpha = matterLight(star, seconds, from, style) * (fromBody ? .18F : 1F);
            float offsetA = pairs > 0 ? filamentOffset(star, seconds, from, style) : 0;
            for (int i = 1; i <= segments; i++) {
                double end = from + step * i;
                if (i < segments) placeStreamPoint(next, star, end + step, horizon);
                else next.set(b);
                float spreadFromMouth = (float) GuixuStellarEnd.ease(0, opening, end - from);
                float width = mouth + (streamWidth - mouth) * spreadFromMouth;
                ribbonWidth(a, next, b, widthB, width * (1 - (float) end * .52F));
                if (widthA.dot(widthB) < 0) widthB.negate();
                float nextAlpha = matterLight(star, seconds, end, style);
                if (fromBody) nextAlpha *= .18F + .82F * spreadFromMouth;
                ribbonVertex(a, widthA, -1, red, green, blue, 0);
                ribbonVertex(a, widthA, 0, coreRed, coreGreen, coreBlue, alpha);
                ribbonVertex(b, widthB, 0, coreRed, coreGreen, coreBlue, nextAlpha);
                ribbonVertex(b, widthB, -1, red, green, blue, 0);
                ribbonVertex(a, widthA, 0, coreRed, coreGreen, coreBlue, alpha);
                ribbonVertex(a, widthA, 1, red, green, blue, 0);
                ribbonVertex(b, widthB, 1, red, green, blue, 0);
                ribbonVertex(b, widthB, 0, coreRed, coreGreen, coreBlue, nextAlpha);
                if (pairs > 0) {
                    float offsetB = filamentOffset(star, seconds, end, style);
                    for (int strand = 1; strand <= pairs; strand++) {
                        float push = strand == 1 ? 1F : 1.52F;
                        filament(push * offsetA, push * offsetB, red, green, blue, alpha, nextAlpha);
                        filament(-push * offsetA, -push * offsetB, red, green, blue, alpha, nextAlpha);
                    }
                    offsetA = offsetB;
                }
                alpha = nextAlpha;
                a.set(b);
                b.set(next);
                widthA.set(widthB);
            }
        }

        private void ribbonWidth(Vector3f before, Vector3f after, Vector3f point, Vector3f target, float size) {
            view.set(camera).sub(point);
            target.set(after).sub(before).cross(view);
            if (target.lengthSquared() < 1e-8F) target.set(after).sub(before).cross(0, 1, 0);
            if (target.lengthSquared() < 1e-8F) target.set(1, 0, 0);
            target.normalize(size);
        }

        private static float filamentOffset(GuixuStellarEnd.Star star, double seconds, double progress, StreamStyle style) {
            double path = Math.clamp((progress - star.body()) / (1 - star.body()), 0, 1);
            double winding = Math.cos(path * 14 - seconds * style.speed * .5 + star.phase());
            return (float) ((1 - path) * (.45 + 1.5 * Math.sin(path * Math.PI) + .38 * winding));
        }

        private void filament(float offsetA, float offsetB, float red, float green, float blue, float alpha, float nextAlpha) {
            ribbonVertex(a, widthA, offsetA - .17F, red, green, blue, 0);
            ribbonVertex(a, widthA, offsetA, red, green, blue, alpha * .66F);
            ribbonVertex(b, widthB, offsetB, red, green, blue, nextAlpha * .66F);
            ribbonVertex(b, widthB, offsetB - .17F, red, green, blue, 0);
            ribbonVertex(a, widthA, offsetA, red, green, blue, alpha * .66F);
            ribbonVertex(a, widthA, offsetA + .17F, red, green, blue, 0);
            ribbonVertex(b, widthB, offsetB + .17F, red, green, blue, 0);
            ribbonVertex(b, widthB, offsetB, red, green, blue, nextAlpha * .66F);
        }

        private static float matterLight(GuixuStellarEnd.Star star, double seconds, double progress, StreamStyle style) {
            double wave = .5 + .5 * Math.cos(progress * style.frequency - seconds * style.speed + star.phase());
            // 流头尚未抵达时柔化末端，到达后让光流在视界边缘接续。
            double tipFade = Math.clamp((star.leadingMatter() - progress) * 18, 0, 1);
            tipFade = Math.max(tipFade, Math.clamp((star.leadingMatter() - .97) / .03, 0, 1));
            double pulse = 1;
            if (style == StreamStyle.PULSE) {
                pulse = .23 + .77 * Math.pow(wave, 4);
            } else if (style == StreamStyle.ROCK) {
                // 块状物质沿轨道成团，逐渐代替连续的粉尘亮带。
                pulse = 1 - .86 * star.disruption() * (1 - wave * wave);
            } else if (style == StreamStyle.NEUTRON) {
                pulse = .38 + .62 * wave * wave;
            } else if (style == StreamStyle.OUTFLOW) {
                double crest = wave * wave;
                pulse = .23 + .77 * crest * crest;
            } else if (style == StreamStyle.ANNIHILATION) {
                // 用外部物质密度接近主视界时上升的近似，强化流头的湮灭信号。
                pulse = (.22 + .78 * progress * progress) * (.41 + .59 * wave * wave);
            }
            double strength = (.19 + .43 * wave * wave) * star.kind().siphonStrength();
            return star.matterBrightness() * (float) (pulse * Math.min(.96, strength) * tipFade);
        }

        private void fragments(GuixuStellarEnd.Star star, double seconds, float horizon, int count,
                               StreamStyle style) {
            double length = star.leadingMatter() - star.body();
            if (length < .04) return;
            for (int i = 0; i < count; i++) {
                double cycle = seconds * (.29 + .026 * style.speed + i * .018) + i * .618 + star.phase();
                double phase = cycle - Math.floor(cycle);
                double progress = star.body() + length * phase;
                float light = matterLight(star, seconds, progress, style) * (float) Math.sin(phase * Math.PI);
                placeStreamPoint(center, star, progress, horizon);
                faceCamera();
                placeStreamPoint(next, star, Math.min(1, progress + .02), horizon);
                next.sub(center);
                next.fma(-next.dot(view), view);
                if (next.lengthSquared() > 1e-8F) {
                    right.set(next).normalize();
                    up.set(view).cross(right);
                }
                float drift = (float) Math.sin(i * 5 + phase * 4) * star.kind().radius * .55F;
                center.fma(drift * (float) (1 - progress), up);
                float size = (.12F + (i % 3) * .10F) * (1 - .55F * (float) progress) * style.fragmentSize;
                glint(size * 2, size * .72F, style.red, style.green, style.blue, light);
                glint(size * .50F, size * .49F, 1F, .93F, .80F, light * .8F);
            }
        }

        private void ribbonVertex(Vector3f point, Vector3f width, float offset, float red, float green, float blue, float alpha) {
            ambientVertex(vertices, matrix, point.x + width.x * offset, point.y + width.y * offset,
                    point.z + width.z * offset, red, green, blue, alpha);
        }

        private void glint(float horizontal, float vertical, float red, float green, float blue, float alpha) {
            ambientGlint(vertices, matrix, center, right, up, horizontal, vertical, red, green, blue, alpha);
        }

        private void placeStreamPoint(Vector3f vector, GuixuStellarEnd.Star star, double progress, float horizon) {
            GuixuStellarEnd.streamPosition(star, progress, horizon, streamPoint);
            vector.set((float) streamPoint.x, (float) streamPoint.y, (float) streamPoint.z);
            if (star.kind().stellarPass != null) {
                // 先保持在恒星所在的轨道平面，回落到内侧后再贴合主吸积盘的倾角。
                float bend = (float) GuixuStellarEnd.ease(.46, .96, GuixuStellarEnd.streamFraction(star, progress));
                vector.rotate(streamRotation.identity().nlerp(diskRotation, bend));
            }
        }

        private static void setPoint(Vector3f vector, GuixuStellarEnd.Point point) {
            vector.set((float) point.x(), (float) point.y(), (float) point.z());
        }
    }

    private record Rgb(float r, float g, float b) {}

    private record CaptureLook(Rgb edge, Rgb core, float width, float height) {
        private static final CaptureLook WARM = new CaptureLook(new Rgb(1F, .52F, .21F),
                new Rgb(1F, .95F, .78F), 1F, 1F);
        private static final CaptureLook COOL = new CaptureLook(new Rgb(.35F, .67F, 1F),
                new Rgb(1F, .95F, 1F), 1F, 1F);
        private static final CaptureLook OUTFLOW = new CaptureLook(new Rgb(.94F, .25F, .30F),
                new Rgb(1F, .60F, .44F), .67F, .59F);
    }

    private record StreamStyle(float red, float green, float blue, float width, int strands, float frequency,
                               float speed, float debris, float fragmentSize) {
        private static final StreamStyle WARM_GLINT = new StreamStyle(1, .68F, .34F, 1, 0, 35, 5, 0, 0);
        private static final StreamStyle COOL_GLINT = new StreamStyle(.40F, .73F, 1, 1, 0, 35, 5, 0, 0);
        private static final StreamStyle WARM_FLAME = new StreamStyle(1, .50F, .18F, 1.48F, 1, 27, 8, .85F, 1.54F);
        private static final StreamStyle COOL_FLAME = new StreamStyle(.40F, .62F, 1, 1.48F, 1, 27, 8, .85F, 1.54F);
        private static final StreamStyle ROCK = new StreamStyle(.99F, .60F, .32F, .68F, 0, 38, 3, .26F, 1.3F);
        private static final StreamStyle CLOUD = new StreamStyle(.72F, .88F, 1, 1.74F, 1, 16, 4, .38F, 1.37F);
        private static final StreamStyle RINGS = new StreamStyle(.97F, .78F, .47F, .88F, 1, 21, 4.6F, .95F, .78F);
        private static final StreamStyle NEUTRON = new StreamStyle(.31F, .83F, 1, .64F, 1, 55, 12, .38F, .72F);
        private static final StreamStyle PULSE = new StreamStyle(.58F, .77F, 1, .54F, 0, 43, 7.85F, 0, 0);
        private static final StreamStyle DWARF = new StreamStyle(.67F, .86F, 1, .71F, 1, 17, 6.5F, .20F, .52F);
        private static final StreamStyle DISK = new StreamStyle(1, .46F, .23F, .96F, 1, 19, 6, .15F, .7F);
        private static final StreamStyle QUASAR = new StreamStyle(.69F, .40F, 1, 1.82F, 2, 24, 9, 1.12F, 2.02F);
        private static final StreamStyle SUPERGIANT = new StreamStyle(1, .35F, .16F, 1.6F, 1, 18, 5, .38F, 1.25F);
        private static final StreamStyle COLD_DEBRIS = new StreamStyle(.42F, .49F, .55F, .56F, 0, 33, 4, .13F, .66F);
        private static final StreamStyle OUTFLOW = new StreamStyle(.92F, .57F, .43F, .58F, 0, 22, 6, 0, 0);
        private static final StreamStyle ANNIHILATION = new StreamStyle(.72F, .32F, 1F, .94F, 0, 32, 9, .49F, .94F);

        private static StreamStyle forStar(GuixuStellarEnd.Star star) {
            return switch (star.kind()) {
                case GLINT -> star.warm() ? WARM_GLINT : COOL_GLINT;
                case STAR -> star.warm() ? WARM_FLAME : COOL_FLAME;
                case ROCKY -> ROCK;
                case GAS_GIANT -> CLOUD;
                case RINGED_GIANT -> RINGS;
                case NEUTRON_STAR -> NEUTRON;
                case PULSAR -> PULSE;
                case WHITE_DWARF -> DWARF;
                case BLACK_HOLE -> DISK;
                case QUASAR -> QUASAR;
                case WHITE_HOLE -> OUTFLOW;
                case THORNE_ZYTKOW -> SUPERGIANT;
                case ANTISTAR -> ANNIHILATION;
                case BLACK_DWARF -> COLD_DEBRIS;
            };
        }
    }
}
