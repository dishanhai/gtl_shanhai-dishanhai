package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.renderer.blackhole.BlackHoleTuning;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuHostGeometry;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreMachine;
import com.dishanhai.gt_shanhai.network.GuixuAnnihilationPacket;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

import static com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuStarRenderer.ambientGlint;
import static com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuStarRenderer.ambientVertex;

/** The final black hole frame belongs to the world, even after the controller and its chunks disappear. */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT)
public final class GuixuAnnihilationFx {
    static final int DURATION_TICKS = 84;
    private static final int RUPTURE_TICK = 8;
    private static final int WAVE_SEGMENTS = 96;
    private static final int FILAMENTS = 64;
    private static final float BLAST_RADIUS = GuixuHostGeometry.RING_HALF * 3.2F;
    private static final double[] WAVE_COS = new double[WAVE_SEGMENTS + 1];
    private static final double[] WAVE_SIN = new double[WAVE_SEGMENTS + 1];
    private static final Map<BlockPos, Burst> BURSTS = new HashMap<>();

    static {
        for (int i = 0; i <= WAVE_SEGMENTS; i++) {
            double angle = Math.TAU * i / WAVE_SEGMENTS;
            WAVE_COS[i] = Math.cos(angle);
            WAVE_SIN[i] = Math.sin(angle);
        }
    }

    private GuixuAnnihilationFx() {}

    public static void start(GuixuAnnihilationPacket packet) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        if (!packet.preview()) {
            if (level.hasChunkAt(packet.controller())
                    && MetaMachine.getMachine(level, packet.controller()) instanceof GuixuVisionCoreMachine host) {
                host.onClientMatrixDetonation();
            }
            HostFxHide.discardAnnihilatedHost(packet.controller());
        }
        BlackHoleTuning.clearHostCenter(level, packet.controller().asLong());
        BURSTS.put(packet.controller(), new Burst(level, packet, level.getGameTime()));
    }

    static boolean isPreviewing(ClientLevel level, BlockPos controller) {
        Burst burst = BURSTS.get(controller);
        return burst != null && burst.level() == level && burst.packet().preview()
                && level.getGameTime() - burst.started() < DURATION_TICKS;
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel level) BURSTS.values().removeIf(burst -> burst.level() == level);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || BURSTS.isEmpty()) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        double now = level.getGameTime() + event.getPartialTick();
        Vec3 camera = event.getCamera().getPosition();
        BURSTS.values().removeIf(burst -> burst.level() != level || now - burst.started() >= DURATION_TICKS);
        for (var burst : BURSTS.values()) {
            Vec3 eye = camera.subtract(Vec3.atCenterOf(burst.packet().center()));
            if (eye.lengthSqr() <= 256D * 256D) draw(event.getPoseStack(), burst.packet(), eye, now - burst.started());
        }
    }

    private static void draw(PoseStack pose, GuixuAnnihilationPacket snapshot, Vec3 eye, double elapsed) {
        float radius = snapshot.radius();
        Frame frame = sample(elapsed, radius);
        pose.pushPose();
        try {
            pose.translate(-eye.x, -eye.y, -eye.z);
            if (frame.coreScale() > 0.01F) {
                pose.pushPose();
                try {
                    float scale = frame.coreScale();
                    pose.scale(scale, scale, scale);
                    float diskTime = (float) ((snapshot.phaseTick() % 48_000 + elapsed * (2 + snapshot.spin() / 25.0)) / 20.0);
                    HostFxBlackHole.drawVisual(pose, (float) eye.x / scale, (float) eye.y / scale, (float) eye.z / scale,
                            diskTime, radius, 1F - scale, Direction.EAST);
                } finally {
                    pose.popPose();
                }
            }

            var layer = HostFxDraw.beamLayer();
            var buffers = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
            var vertices = buffers.getBuffer(layer);
            Matrix4f world = pose.last().pose();
            Vector3f view = new Vector3f((float) eye.x, (float) eye.y, (float) eye.z);
            if (view.lengthSquared() < 1.0e-4F) view.set(0, 0, 1);
            view.normalize();
            Vector3f right = new Vector3f(view.z, 0, -view.x);
            if (right.lengthSquared() < 1.0e-4F) right.set(1, 0, 0);
            right.normalize();
            Vector3f up = new Vector3f(view).cross(right);

            var diskRotation = GuixuStarRenderer.diskRotation(Direction.EAST);
            var disk = new Matrix4f(world).rotate(diskRotation);
            drawFlash(vertices, world, view, right, up, radius, frame);
            drawShockFront(vertices, disk, frame.primary(), .76F, .46F, 1F);
            drawShockFront(vertices, new Matrix4f(disk).rotateX(1.04F), frame.secondary(), .36F, .58F, 1F);
            drawShockFront(vertices, new Matrix4f(disk).rotateZ(-1.04F), frame.tertiary(), 1F, .47F, .26F);
            drawFilaments(vertices, disk, elapsed, radius, frame, snapshot.spin(), snapshot.phaseTick());
            Vector3f axis = diskRotation.transform(new Vector3f(0, 1, 0));
            drawJets(vertices, world, view, axis, right, radius, frame);
            buffers.endBatch(layer);
        } finally {
            pose.popPose();
        }
    }

    private static void drawFlash(VertexConsumer vertices, Matrix4f world, Vector3f view, Vector3f right, Vector3f up,
                                  float horizon, Frame frame) {
        float flash = frame.flash();
        if (flash <= .005F) return;
        Vector3f center = new Vector3f(view).mul(horizon * (frame.coreScale() + .08F));
        float glowRadius = horizon * (3.6F + frame.primary().radius() / BLAST_RADIUS * 3F);
        for (int i = 0; i < WAVE_SEGMENTS; i += 3) {
            int next = i + 3;
            float x0 = glowRadius * (float) WAVE_COS[i], y0 = glowRadius * (float) WAVE_SIN[i];
            float x1 = glowRadius * (float) WAVE_COS[next], y1 = glowRadius * (float) WAVE_SIN[next];
            ambientVertex(vertices, world, center.x, center.y, center.z, .81F, .48F, 1F, flash * .78F);
            ambientVertex(vertices, world, center.x + right.x * x0 + up.x * y0,
                    center.y + right.y * x0 + up.y * y0, center.z + right.z * x0 + up.z * y0, .45F, .24F, 1F, 0);
            ambientVertex(vertices, world, center.x + right.x * x1 + up.x * y1,
                    center.y + right.y * x1 + up.y * y1, center.z + right.z * x1 + up.z * y1, .45F, .24F, 1F, 0);
            ambientVertex(vertices, world, center.x, center.y, center.z, .81F, .48F, 1F, flash * .78F);
        }
        ambientGlint(vertices, world, center, right, up, horizon * 6F, horizon * .18F, .66F, .74F, 1F, flash * .72F);
        ambientGlint(vertices, world, center, right, up, horizon * .18F, horizon * 3.8F, 1F, .7F, .54F, flash * .7F);
        ambientGlint(vertices, world, center, right, up, horizon * 1.1F, horizon * 1.1F, 1F, .96F, .88F, flash);
    }

    private static void drawShockFront(VertexConsumer v, Matrix4f m, ShockFront front,
                                       float red, float green, float blue) {
        float opacity = front.opacity();
        if (opacity <= .005F) return;
        float radius = front.radius();
        float width = .38F + radius * .033F;
        for (int i = 0; i < WAVE_SEGMENTS; i++) {
            int next = i + 1;
            float wake = radius - width * 3.8F;
            float crest = radius - width * .21F;
            waveBand(v, m, i, next, wake, crest, red, green, blue, 0, opacity * .48F);
            waveBand(v, m, i, next, crest, radius, 1F, .89F, .98F, opacity * .48F, opacity * .95F);
            waveBand(v, m, i, next, radius, radius + width, red, green, blue, opacity * .85F, 0);
        }
    }

    private static void waveBand(VertexConsumer v, Matrix4f m, int from, int to, float inner, float outer,
                                 float red, float green, float blue, float inside, float outside) {
        float x0 = (float) WAVE_COS[from], z0 = (float) WAVE_SIN[from];
        float x1 = (float) WAVE_COS[to], z1 = (float) WAVE_SIN[to];
        ambientVertex(v, m, x0 * inner, 0, z0 * inner, red, green, blue, inside);
        ambientVertex(v, m, x1 * inner, 0, z1 * inner, red, green, blue, inside);
        ambientVertex(v, m, x1 * outer, 0, z1 * outer, red, green, blue, outside);
        ambientVertex(v, m, x0 * outer, 0, z0 * outer, red, green, blue, outside);
    }

    private static void drawFilaments(VertexConsumer v, Matrix4f disk, double age, float horizon, Frame frame,
                                      int spin, long phaseTick) {
        boolean collapsing = age < RUPTURE_TICK;
        float fade = collapsing ? frame.coreScale() * .75F : frame.primary().opacity();
        if (fade <= .005F) return;
        float orbit = collapsing ? horizon * (1.2F + 3F * frame.coreScale()) : frame.primary().radius();
        float twist = .025F + spin * .0007F;
        for (int i = 0; i < FILAMENTS; i++) {
            double angle = i * 2.399963 + (phaseTick % 240 + age * (collapsing ? 5 : 2)) * twist;
            float spread = (i * 17 % FILAMENTS) / (float) (FILAMENTS - 1);
            float distance = orbit * (.24F + .71F * spread);
            float tail = collapsing ? distance + horizon * (.5F + spread) : distance * (.62F + spread * .24F);
            float width = horizon * (.018F + .052F * spread);
            double shear = angle - (collapsing ? .48 : .15) * (.5 + spread);
            float x = (float) Math.cos(angle), z = (float) Math.sin(angle);
            float y = distance * ((i * 29 % FILAMENTS) / (float) (FILAMENTS - 1) - .5F) * (collapsing ? .16F : .7F);
            float tailX = (float) Math.cos(shear) * tail, tailZ = (float) Math.sin(shear) * tail;
            float green = .40F + .44F * spread;
            ambientVertex(v, disk, tailX, y * .7F, tailZ, .65F, green, 1F, 0);
            ambientVertex(v, disk, x * distance - z * width, y, z * distance + x * width, 1F, green, .86F, fade);
            ambientVertex(v, disk, x * distance + z * width, y, z * distance - x * width, 1F, green, .86F, fade);
            ambientVertex(v, disk, tailX, y * .7F, tailZ, .65F, green, 1F, 0);
        }
    }

    private static void drawJets(VertexConsumer vertices, Matrix4f world, Vector3f view, Vector3f axis,
                                 Vector3f right, float horizon, Frame frame) {
        if (frame.jetOpacity() <= .005F) return;
        Vector3f across = new Vector3f(view).cross(axis);
        if (across.lengthSquared() < 1.0e-4F) across.set(right);
        across.normalize();
        float length = horizon + frame.primary().radius() * .73F;
        for (int side = -1; side <= 1; side += 2) {
            jetRibbon(vertices, world, axis, across, side * length, horizon * 2.1F, .52F, .27F, 1F, frame.jetOpacity());
            jetRibbon(vertices, world, axis, across, side * length * .82F, horizon * .48F, .86F, .94F, 1F,
                    frame.jetOpacity());
        }
    }

    private static void jetRibbon(VertexConsumer v, Matrix4f m, Vector3f axis, Vector3f across, float length,
                                  float width, float red, float green, float blue, float opacity) {
        Vector3f shoulder = new Vector3f(axis).mul(length * .26F);
        ambientVertex(v, m, 0, 0, 0, red, green, blue, opacity);
        ambientVertex(v, m, shoulder.x + across.x * width, shoulder.y + across.y * width,
                shoulder.z + across.z * width, red, green, blue, opacity * .52F);
        ambientVertex(v, m, axis.x * length, axis.y * length, axis.z * length, red, green, blue, 0);
        ambientVertex(v, m, shoulder.x - across.x * width, shoulder.y - across.y * width,
                shoulder.z - across.z * width, red, green, blue, opacity * .52F);
    }

    static Frame sample(double age, float horizon) {
        if (age < 0 || age >= DURATION_TICKS) return Frame.EMPTY;
        float collapse = (float) Math.min(1, age / RUPTURE_TICK);
        double flashOffset = (age - RUPTURE_TICK) / (age < RUPTURE_TICK ? 1.35 : 6.0);
        float flash = (float) Math.exp(-flashOffset * flashOffset);
        float jet = age < RUPTURE_TICK ? 0 : (float) Math.exp(-(age - RUPTURE_TICK) / 13.0);
        return new Frame(1 - collapse * collapse, flash, jet,
                shockFront(age, RUPTURE_TICK, horizon, 1F),
                shockFront(age, RUPTURE_TICK + 8, horizon, .88F),
                shockFront(age, RUPTURE_TICK + 17, horizon, .76F));
    }

    private static ShockFront shockFront(double age, int start, float horizon, float reach) {
        if (age < start) return ShockFront.EMPTY;
        float remaining = (float) ((DURATION_TICKS - age) / (DURATION_TICKS - start));
        float eased = 1 - remaining * remaining * remaining;
        return new ShockFront(horizon * .9F + BLAST_RADIUS * reach * eased, remaining * remaining * reach);
    }

    record ShockFront(float radius, float opacity) {
        private static final ShockFront EMPTY = new ShockFront(0, 0);
    }

    record Frame(float coreScale, float flash, float jetOpacity, ShockFront primary, ShockFront secondary,
                 ShockFront tertiary) {
        private static final Frame EMPTY = new Frame(0, 0, 0, ShockFront.EMPTY, ShockFront.EMPTY, ShockFront.EMPTY);
    }
    private record Burst(ClientLevel level, GuixuAnnihilationPacket packet, long started) {}
}
