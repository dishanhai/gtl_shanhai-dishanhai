package com.dishanhai.gt_shanhai.client.renderer.preview;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.renderer.blackhole.BlackHoleIris;
import com.dishanhai.gt_shanhai.client.renderer.blackhole.BlackHoleLensRenderer;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterGeometry;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterInstallation;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterWorld;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** 山海摘星者世界预览。内环绕水平轴翻转，外环沿 XZ 面自转。 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT)
public final class StarLifterRenderer {
    private static final RenderType SURFACE = RenderType.entitySolid(InventoryMenu.BLOCK_ATLAS);

    private record Preview(ClientLevel level, StarLifterInstallation installation) {}
    private record Effects(Matrix4f pose, Quaternionf heading, Vec3 eye, double ticks) {}
    private record DeferredEffects(ClientLevel level, List<Effects> instances, Matrix4f modelView, Matrix4f projection,
                                   VertexSorting sorting, Vec3 cameraUp) {}
    private record Buffers(VertexBuffer star, VertexBuffer plasma, VertexBuffer[] rings) implements AutoCloseable {
        @Override
        public void close() {
            star.close();
            plasma.close();
            for (VertexBuffer ring : rings) ring.close();
        }
    }

    private static Preview preview;
    private static Buffers meshes;
    private static DeferredEffects deferred;

    private StarLifterRenderer() {}

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("shanhai").then(Commands.literal("star_lifter")
                .then(Commands.literal("show").then(Commands.argument("center", Vec3Argument.vec3())
                        .executes(ctx -> show(ctx.getSource(), Vec3Argument.getVec3(ctx, "center"),
                                StarLifterInstallation.DEFAULT_RADIUS, 0))
                        .then(Commands.argument("radius", FloatArgumentType.floatArg(
                                        StarLifterInstallation.MIN_RADIUS, StarLifterInstallation.MAX_RADIUS))
                                .executes(ctx -> show(ctx.getSource(), Vec3Argument.getVec3(ctx, "center"),
                                        FloatArgumentType.getFloat(ctx, "radius"), 0))
                                .then(Commands.argument("heading", FloatArgumentType.floatArg(-180.0f, 180.0f))
                                        .executes(ctx -> show(ctx.getSource(), Vec3Argument.getVec3(ctx, "center"),
                                                FloatArgumentType.getFloat(ctx, "radius"),
                                                FloatArgumentType.getFloat(ctx, "heading")))))))
                .then(Commands.literal("hide").executes(ctx -> {
                    preview = null;
                    ctx.getSource().sendSuccess(() -> Component.translatable("gt_shanhai.star_lifter.preview.hidden"), false);
                    return 1;
                }))));
    }

    private static int show(CommandSourceStack source, Vec3 center, float radius, float heading) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return 0;
        preview = new Preview(level, new StarLifterInstallation("preview", center, radius, heading, level.getGameTime()));
        source.sendSuccess(() -> Component.translatable("gt_shanhai.star_lifter.preview.shown", radius, heading), false);
        return 1;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        preview = null;
        deferred = null;
        StarLifterWorld.clearClient();
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            finishEffects();
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        deferred = null;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        var installations = StarLifterWorld.installations(level);
        if (preview != null) {
            if (preview.level() != level) {
                preview = null;
            } else {
                var shown = preview.installation();
                if (installations.stream().anyMatch(installation -> installation.center().equals(shown.center()))) {
                    preview = null;
                } else {
                    installations = new ArrayList<>(installations);
                    installations.add(shown);
                }
            }
        }
        if (installations.isEmpty()) return;

        List<Effects> visible = new ArrayList<>(installations.size());
        for (StarLifterInstallation installation : installations) {
            drawInstallation(event, level, installation, visible);
        }
        if (visible.isEmpty()) return;
        Vec3 cameraUp = new Vec3(new Vector3f(0, 1, 0).rotate(event.getCamera().rotation()));
        // Oculus 合成会覆盖在主目标上提前绘制的自定义着色器颜色。
        if (BlackHoleIris.update(BlackHoleLensRenderer.upcomingFrameSerial())) {
            deferred = new DeferredEffects(level, visible, new Matrix4f(RenderSystem.getModelViewMatrix()),
                    new Matrix4f(event.getProjectionMatrix()), RenderSystem.getVertexSorting(), cameraUp);
        } else {
            drawEffects(visible, event.getProjectionMatrix(), cameraUp);
        }
    }

    private static void drawInstallation(RenderLevelStageEvent event, ClientLevel level,
                                         StarLifterInstallation installation, List<Effects> visible) {
        Vec3 relative = installation.center().subtract(event.getCamera().getPosition());
        double drawDistance = Math.max(256.0, installation.radius() * 6.0);
        if (relative.lengthSqr() > drawDistance * drawDistance || !event.getFrustum().isVisible(installation.bounds())) return;
        if (meshes == null) meshes = bake();
        double ticks = level.getGameTime() - installation.startTick() + (double) event.getPartialTick();
        Vec3 eye = relative.scale(-1.0 / installation.radius());
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        try {
            pose.translate(relative.x, relative.y, relative.z);
            pose.scale(installation.radius(), installation.radius(), installation.radius());
            var heading = Axis.YP.rotationDegrees(installation.heading());
            drawStructure(pose, event, ticks, heading, StarLifterEffects.hasPlasma());
            visible.add(new Effects(new Matrix4f(pose.last().pose()), heading, eye, ticks));
        } finally {
            pose.popPose();
        }
    }

    private static void finishEffects() {
        DeferredEffects frame = deferred;
        deferred = null;
        if (frame == null || frame.level() != Minecraft.getInstance().level) return;
        Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        RenderSystem.backupProjectionMatrix();
        try {
            modelView.last().pose().set(frame.modelView());
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(frame.projection(), frame.sorting());
            drawEffects(frame.instances(), frame.projection(), frame.cameraUp());
        } finally {
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    private static void drawEffects(List<Effects> instances, Matrix4f projection, Vec3 cameraUp) {
        for (Effects effects : instances) {
            if (StarLifterEffects.hasPlasma()) {
                StarLifterEffects.drawPlasma(meshes.plasma(), effects.pose(), projection, effects.eye(), cameraUp, effects.ticks());
            }
            StarLifterEffects.drawCollectors(effects.pose(), effects.heading(), effects.ticks());
        }
    }

    private static void drawStructure(PoseStack pose, RenderLevelStageEvent event, double ticks,
                                      Quaternionf heading, boolean lit) {
        pose.pushPose();
        SURFACE.setupRenderState();
        try {
            ShaderInstance shader = RenderSystem.getShader();
            if (shader == null) return;
            if (!lit) draw(meshes.star(), pose, event, shader);
            pose.mulPose(heading);
            for (int i = 0; i < meshes.rings().length; i++) {
                pose.pushPose();
                try {
                    pose.mulPose(StarLifterGeometry.RINGS.get(i).orientation(ticks));
                    draw(meshes.rings()[i], pose, event, shader);
                } finally {
                    pose.popPose();
                }
            }
        } finally {
            VertexBuffer.unbind();
            SURFACE.clearRenderState();
            pose.popPose();
        }
    }

    private static void draw(VertexBuffer buffer, PoseStack pose, RenderLevelStageEvent event, ShaderInstance shader) {
        buffer.bind();
        buffer.drawWithShader(pose.last().pose(), event.getProjectionMatrix(), shader);
    }

    private static Buffers bake() {
        var atlas = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS);
        var surfaces = StarLifterGeometry.Surface.values();
        var sprites = new TextureAtlasSprite[surfaces.length];
        for (var surface : surfaces) {
            var id = new ResourceLocation(GTDishanhaiMod.MOD_ID,
                    "block/star_lifter/" + surface.name().toLowerCase(Locale.ROOT));
            sprites[surface.ordinal()] = atlas.apply(id);
        }
        VertexBuffer[] rings = new VertexBuffer[StarLifterGeometry.RINGS.size()];
        VertexBuffer star = bakeMesh(StarLifterGeometry::star, sprites);
        VertexBuffer plasma = null;
        try {
            plasma = StarLifterEffects.bakeSurface();
            for (int i = 0; i < rings.length; i++) {
                int ring = i;
                rings[i] = bakeMesh(mesh -> StarLifterGeometry.ring(ring, mesh), sprites);
            }
            return new Buffers(star, plasma, rings);
        } catch (RuntimeException exception) {
            star.close();
            if (plasma != null) plasma.close();
            for (VertexBuffer ring : rings) if (ring != null) ring.close();
            throw exception;
        }
    }

    private static VertexBuffer bakeMesh(Consumer<StarLifterGeometry.Mesh> geometry,
                                         TextureAtlasSprite[] sprites) {
        var builder = new BufferBuilder(1 << 18);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        geometry.accept((surface, a, b, c, d) -> {
            TextureAtlasSprite sprite = sprites[surface.ordinal()];
            Vec3 side = new Vec3(b.x() - a.x(), b.y() - a.y(), b.z() - a.z());
            Vec3 diagonal = new Vec3(c.x() - a.x(), c.y() - a.y(), c.z() - a.z());
            Vec3 normal = side.cross(diagonal).normalize();
            int tone = surface == StarLifterGeometry.Surface.STELLAR ? 255
                    : (int) (213 + 34 * Math.max(0.0, normal.y()));
            vertex(builder, a, sprite.getU0(), sprite.getV1(), normal, tone);
            vertex(builder, b, sprite.getU1(), sprite.getV1(), normal, tone);
            vertex(builder, c, sprite.getU1(), sprite.getV0(), normal, tone);
            vertex(builder, d, sprite.getU0(), sprite.getV0(), normal, tone);
        });
        return upload(builder);
    }

    static VertexBuffer upload(BufferBuilder builder) {
        var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        try {
            buffer.upload(builder.end());
            return buffer;
        } catch (RuntimeException exception) {
            buffer.close();
            throw exception;
        } finally {
            VertexBuffer.unbind();
        }
    }

    private static void vertex(BufferBuilder builder, StarLifterGeometry.Point position,
                               float u, float v, Vec3 normal, int tone) {
        builder.vertex(position.x(), position.y(), position.z()).color(tone, tone, tone, 255)
                .uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal((float) normal.x, (float) normal.y, (float) normal.z).endVertex();
    }

    @Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Resources {
        private Resources() {}

        @SubscribeEvent
        public static void registerShader(RegisterShadersEvent event) {
            StarLifterEffects.registerShader(event);
        }

        @SubscribeEvent
        public static void reload(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) resources -> {
                if (RenderSystem.isOnRenderThread()) {
                    release();
                } else {
                    RenderSystem.recordRenderCall(StarLifterRenderer::release);
                }
            });
        }
    }

    private static void release() {
        if (meshes != null) {
            meshes.close();
            meshes = null;
        }
    }
}
