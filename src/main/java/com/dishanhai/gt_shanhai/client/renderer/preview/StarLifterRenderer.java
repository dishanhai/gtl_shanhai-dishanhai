package com.dishanhai.gt_shanhai.client.renderer.preview;

import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterBootTimeline;

import com.dishanhai.gt_shanhai.config.ShanhaiUiClientConfig.StarLifterVisual;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.renderer.blackhole.BlackHoleIris;
import com.dishanhai.gt_shanhai.client.renderer.blackhole.BlackHoleLensRenderer;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterGeometry;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterInstallation;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterStructure;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL33;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** 山海摘星者世界预览。内环绕水平轴翻转，外环沿 XZ 面自转。 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT)
public final class StarLifterRenderer {
    private static final RenderType SURFACE = RenderType.entitySolid(InventoryMenu.BLOCK_ATLAS);
    private static final Quaternionf WORLD_ROTATION = new Quaternionf();
    private static final Vector3f RING_NORMAL = new Vector3f();
    private static final Matrix4f FALLBACK_POSE = new Matrix4f();
    private static final int ALL_RINGS = (1 << StarLifterGeometry.RINGS.size()) - 1;

    private record Preview(ClientLevel level, StarLifterInstallation installation) {}
    private record Effects(Matrix4f pose, Quaternionf heading, Vec3 eye, double ticks, boolean plasma, boolean clasp,
                           int ringMask, StarLifterEffects.Activity activity) {}
    private record DeferredEffects(ClientLevel level, List<Effects> instances, Matrix4f modelView, Matrix4f projection,
                                   VertexSorting sorting, Vec3 cameraUp) {}
    private record SurfaceMesh(VertexBuffer buffer, int vertices, double radius, double halfHeight) {}
    private record Buffers(SurfaceMesh star, VertexBuffer plasma, SurfaceMesh[] rings) implements AutoCloseable {
        @Override
        public void close() {
            star.buffer().close();
            plasma.close();
            for (SurfaceMesh ring : rings) ring.buffer().close();
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
        StarLifterTerminalPreview.closeFrame();
        StarLifterProfile.finish();
    }

    @SubscribeEvent
    public static void onShutdown(GameShuttingDownEvent event) {
        StarLifterProfile.finish();
        release();
    }

    @SubscribeEvent
    public static void profileFrame(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) StarLifterProfile.startFrame();
        else StarLifterProfile.endFrame();
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            finishEffects();
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        try (var profile = StarLifterProfile.begin("WorldSubmission", true)) {
            renderInstallations(event);
        }
    }

    private static void renderInstallations(RenderLevelStageEvent event) {
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
        if (relative.lengthSqr() > drawDistance * drawDistance) return;
        var heading = Axis.YP.rotationDegrees(installation.heading());
        boolean clasp = installation.isController() && worldControlVisible(event, installation, heading);
        if (!clasp && !event.getFrustum().isVisible(installation.bounds())) return;
        prepareMeshes();
        double gameTick = level.getGameTime() + (double) event.getPartialTick();
        double ticks = level.getGameTime() - installation.startTick() + (double) event.getPartialTick();
        var activity = StarLifterEffects.Activity.at(installation.visual(), gameTick);
        Vec3 eye = relative.scale(-1.0 / installation.radius());
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        try {
            pose.translate(relative.x, relative.y, relative.z);
            pose.scale(installation.radius(), installation.radius(), installation.radius());
            boolean plasma = plasmaEnabled();
            Vec3 center = installation.center();
            double extent = installation.radius() * StarLifterGeometry.STAR_RADIUS * (plasma ? 1.62 : 1.0);
            boolean starVisible = event.getFrustum().isVisible(bounds(center, extent, extent, extent));
            int rings = drawStructure(pose, event, installation, ticks, heading, starVisible && !plasma, activity);
            if (plasma && starVisible || rings != 0 && StarLifterVisual.WORLD_COLLECTORS.enabled() || clasp) {
                visible.add(new Effects(new Matrix4f(pose.last().pose()), heading, eye, ticks, plasma && starVisible,
                        clasp, rings, activity));
            }
        } finally {
            pose.popPose();
        }
    }

    private static boolean worldControlVisible(RenderLevelStageEvent event, StarLifterInstallation installation, Quaternionf heading) {
        var anchor = StarLifterStructure.controlAnchor();
        var world = heading.transform(RING_NORMAL.set((float) anchor.x, (float) anchor.y, (float) anchor.z))
                .mul(installation.radius());
        double extent = .31 * installation.radius();
        return event.getFrustum().isVisible(bounds(installation.center().add(world.x, world.y, world.z), extent, extent, extent));
    }

    private static void finishEffects() {
        DeferredEffects frame = deferred;
        deferred = null;
        if (frame == null || frame.level() != Minecraft.getInstance().level) return;
        Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        RenderSystem.backupProjectionMatrix();
        try (var profile = StarLifterProfile.begin("Composite", true)) {
            StarLifterProfile.framebuffer(profile);
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
        boolean collectors = StarLifterVisual.WORLD_COLLECTORS.enabled();
        boolean detail = StarLifterVisual.WORLD_DETAIL.enabled();
        for (Effects effects : instances) {
            if (effects.plasma()) {
                StarLifterEffects.drawPlasma(meshes.plasma(), effects.pose(), projection, effects.eye(), cameraUp,
                        effects.heading(), effects.ticks(), collectors ? effects.activity() : effects.activity().withoutOperations());
            }
            double distanceSqr = effects.eye().lengthSqr();
            double inner = instances.size() > 4 ? 1.36 : 1.9;
            var quality = distanceSqr > 3.6 * 3.6 ? StarLifterStreams.Quality.FAR
                    : detail && distanceSqr <= inner * inner ? StarLifterStreams.Quality.NEAR : StarLifterStreams.Quality.MEDIUM;
            StarLifterEffects.drawCollectors(effects.pose(), projection, effects.heading(), effects.ticks(), effects.activity(),
                    quality, effects.ringMask(), effects.plasma(), effects.clasp(), collectors);
        }
    }

    private static boolean plasmaEnabled() {
        return StarLifterVisual.WORLD_STELLAR_EFFECT.enabled() && StarLifterEffects.hasPlasma();
    }

    static void drawTerminal(Matrix4f view, Matrix4f projection, StarLifterTerminalPreview.View scene, int hovered,
                             StarLifterStreams.Quality quality, VertexBuffer fixed, StarLifterProfile profile,
                             StarLifterEffects.Activity activity, ShaderInstance observation, StarLifterPreviewSamples samples) {
        prepareMeshes();
        boolean plasma = StarLifterEffects.hasPlasma();
        boolean starOnly = scene.starOnly(), wholeStructure = !starOnly && !scene.isolate();
        float bootMs = StarLifterBootTimeline.cueTime(scene.bootScan());
        float starLight = .06f + .94f * StarLifterBootTimeline.starLight(bootMs);
        int ring = scene.ring();
        var orientation = Axis.YP.rotationDegrees(scene.heading());
        int draws = 0, vertices = 0;
        SURFACE.setupRenderState();
        int previousSampler = -1;
        try {
            var shader = RenderSystem.getShader();
            if (!plasma) {
                RenderSystem.setShaderColor(starLight, starLight, starLight, 1);
                drawFallback(view, projection, shader, activity);
                RenderSystem.setShaderColor(1, 1, 1, 1);
                draws++;
                vertices += meshes.star().vertices();
            }
            if (!starOnly) {
                if (observation != null) {
                    shader = observation;
                    previousSampler = samples.bindAtlas(RenderSystem.getShaderTexture(0));
                    observation.safeGetUniform("ObservationLight").set(scene.light());
                    observation.safeGetUniform("Engineering").set(scene.engineering());
                }
                var structure = new Matrix4f(view).rotate(orientation);
                float fixture = StarLifterBootTimeline.ringMaterial(bootMs, 0);
                if (wholeStructure && fixture > 0) {
                    float shade = (1 - .45f * scene.stellar() - .18f * scene.transport()) * (1 - .18f * scene.selection());
                    RenderSystem.setShaderColor(shade, shade, shade, 1);
                    if (observation != null) {
                        observation.safeGetUniform("RingSweep").set(1f);
                        observation.safeGetUniform("MaterialSweep").set(fixture);
                        observation.safeGetUniform("SelectionSweep").set(1f);
                    }
                    fixed.bind();
                    fixed.drawWithShader(structure, projection, shader);
                    draws++;
                }
                for (int i = 0; i < meshes.rings().length; i++) {
                    if (scene.isolate() && i != ring) continue;
                    float sweep = StarLifterBootTimeline.ringSweep(bootMs, i);
                    if (sweep <= 0) continue;
                    if (observation != null) {
                        observation.safeGetUniform("RingSweep").set(sweep);
                        observation.safeGetUniform("MaterialSweep").set(StarLifterBootTimeline.ringMaterial(bootMs, i));
                        observation.safeGetUniform("SelectionSweep").set(i == ring && scene.selection() > .01f ? scene.selectionSweep() : 1);
                    }
                    float weight = i == ring ? scene.selection() : -scene.selection();
                    float shade = (1 - .52f * scene.stellar() - .20f * scene.transport()) * (1 + .22f * weight);
                    float highlight = i == hovered ? .20f : i == ring ? .12f * scene.selection() : 0;
                    RenderSystem.setShaderColor(shade * (1 + highlight), shade * (1 + highlight * .5f), shade, 1);
                    var mesh = meshes.rings()[i];
                    mesh.buffer().bind();
                    mesh.buffer().drawWithShader(new Matrix4f(structure)
                            .rotate(StarLifterGeometry.RINGS.get(i).orientation(scene.ticks())), projection, shader);
                    draws++;
                    vertices += mesh.vertices();
                }
            }
        } finally {
            if (previousSampler >= 0) GL33.glBindSampler(0, previousSampler);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            VertexBuffer.unbind();
            SURFACE.clearRenderState();
        }
        StarLifterProfile.draw(profile, draws, vertices, 0);
        if (plasma) {
            // Panning an orthographic view shifts the image without changing the sight direction.
            var eye = new Vec3(scene.perspective()
                    ? view.originAffine(new Vector3f()) : view.positiveZ(new Vector3f()).mul(-view.m32()));
            var up = new Vec3(view.positiveY(new Vector3f()));
            StarLifterEffects.drawPlasma(meshes.plasma(), view, projection, eye, up, orientation, scene.ticks(), activity, starLight);
        }
        // Keep the terminal's light contribution separate from the geometry coverage mask.
        RenderSystem.colorMask(true, true, true, false);
        int ringMask = starOnly ? 0 : wholeStructure ? ALL_RINGS : 1 << ring;
        boolean initialized = bootMs >= StarLifterBootTimeline.HANDOFF;
        StarLifterEffects.drawCollectors(view, projection, orientation, scene.ticks(),
                initialized ? activity : activity.withoutOperations(), quality, initialized ? ringMask : 0,
                plasma && bootMs >= 2260, wholeStructure && initialized, !starOnly && initialized);
    }

    private static int drawStructure(PoseStack pose, RenderLevelStageEvent event, StarLifterInstallation installation,
                                     double ticks, Quaternionf heading, boolean drawStar, StarLifterEffects.Activity activity) {
        int rings = 0;
        pose.pushPose();
        SURFACE.setupRenderState();
        try (var profile = StarLifterProfile.begin("DynamicRings", true)) {
            ShaderInstance shader = RenderSystem.getShader();
            if (shader == null) return 0;
            if (drawStar) {
                try (var star = StarLifterProfile.begin("StellarFallback", true)) {
                    drawFallback(pose.last().pose(), event.getProjectionMatrix(), shader, activity);
                    StarLifterProfile.draw(star, 1, meshes.star().vertices(), 0);
                }
            }
            pose.mulPose(heading);
            for (int i = 0; i < meshes.rings().length; i++) {
                var mesh = meshes.rings()[i];
                var orientation = StarLifterGeometry.RINGS.get(i).orientation(ticks);
                if (!ringVisible(event, installation, mesh, heading, orientation)) continue;
                rings |= 1 << i;
                pose.pushPose();
                try {
                    pose.mulPose(orientation);
                    draw(mesh, pose, event, shader);
                    StarLifterProfile.draw(profile, 1, mesh.vertices(), 0);
                } finally {
                    pose.popPose();
                }
            }
        } finally {
            VertexBuffer.unbind();
            SURFACE.clearRenderState();
            pose.popPose();
        }
        return rings;
    }

    private static void drawFallback(Matrix4f pose, Matrix4f projection, ShaderInstance shader,
                                     StarLifterEffects.Activity activity) {
        var color = RenderSystem.getShaderColor();
        float r = color[0], g = color[1], b = color[2], a = color[3];
        float spent = 1f - activity.reserveFraction();
        RenderSystem.setShaderColor(r * (1f - .08f * spent), g * (1f - .34f * spent), b * (1f - .06f * spent), a);
        try {
            var surface = meshes.star().buffer();
            surface.bind();
            surface.drawWithShader(FALLBACK_POSE.set(pose).scale(activity.stellarScale()), projection, shader);
        } finally {
            RenderSystem.setShaderColor(r, g, b, a);
        }
    }

    private static boolean ringVisible(RenderLevelStageEvent event, StarLifterInstallation installation, SurfaceMesh mesh,
                                       Quaternionf heading, Quaternionf orientation) {
        WORLD_ROTATION.set(heading).mul(orientation).transform(RING_NORMAL.set(0, 1, 0));
        double radius = mesh.radius() * installation.radius(), height = mesh.halfHeight() * installation.radius();
        return event.getFrustum().isVisible(bounds(installation.center(), extent(radius, height, RING_NORMAL.x),
                extent(radius, height, RING_NORMAL.y), extent(radius, height, RING_NORMAL.z)));
    }

    private static double extent(double radius, double height, float normal) {
        return radius * Math.sqrt(Math.max(0.0, 1.0 - (double) normal * normal)) + height * Math.abs(normal) + .01;
    }

    private static AABB bounds(Vec3 center, double x, double y, double z) {
        return new AABB(center.x - x, center.y - y, center.z - z, center.x + x, center.y + y, center.z + z);
    }

    private static void draw(SurfaceMesh mesh, PoseStack pose, RenderLevelStageEvent event, ShaderInstance shader) {
        mesh.buffer().bind();
        mesh.buffer().drawWithShader(pose.last().pose(), event.getProjectionMatrix(), shader);
    }

    private static void prepareMeshes() {
        if (meshes != null) return;
        try (var profile = StarLifterProfile.begin("ResourceUpdate", true)) {
            meshes = bake();
            int vertices = meshes.star().vertices();
            for (var ring : meshes.rings()) vertices += ring.vertices();
            StarLifterProfile.draw(profile, 0, 0, vertices * DefaultVertexFormat.NEW_ENTITY.getVertexSize()
                    + meshes.star().vertices() * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize());
        }
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
        SurfaceMesh[] rings = new SurfaceMesh[StarLifterGeometry.RINGS.size()];
        SurfaceMesh star = bakeMesh(StarLifterGeometry::star, sprites);
        VertexBuffer plasma = null;
        try {
            plasma = StarLifterEffects.bakeSurface();
            for (int i = 0; i < rings.length; i++) {
                int ring = i;
                rings[i] = bakeMesh(mesh -> StarLifterGeometry.ring(ring, mesh), sprites);
            }
            return new Buffers(star, plasma, rings);
        } catch (RuntimeException exception) {
            star.buffer().close();
            if (plasma != null) plasma.close();
            for (SurfaceMesh ring : rings) if (ring != null) ring.buffer().close();
            throw exception;
        }
    }

    private static SurfaceMesh bakeMesh(Consumer<StarLifterGeometry.Mesh> geometry,
                                         TextureAtlasSprite[] sprites) {
        var bounds = new MeshBounds();
        var buffer = uploadMesh(DefaultVertexFormat.NEW_ENTITY, builder -> geometry.accept((surface, a, b, c, d) -> {
            bounds.include(a);
            bounds.include(b);
            bounds.include(c);
            bounds.include(d);
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
        }));
        return new SurfaceMesh(buffer, bounds.vertices, Math.sqrt(bounds.radiusSquared), bounds.halfHeight);
    }

    private static final class MeshBounds {
        private int vertices;
        private double radiusSquared, halfHeight;

        private void include(StarLifterGeometry.Point point) {
            vertices++;
            radiusSquared = Math.max(radiusSquared, point.x() * point.x() + point.z() * point.z());
            halfHeight = Math.max(halfHeight, Math.abs(point.y()));
        }
    }

    static VertexBuffer uploadMesh(VertexFormat format, Consumer<BufferBuilder> emit) {
        return StarLifterMeshUpload.upload(format, emit);
    }

    static void vertex(BufferBuilder builder, StarLifterGeometry.Point position,
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
            StarLifterStreams.register(event);
            StarLifterTerminalPreview.register(event);
            StarLifterTerminalSky.register(event);
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
        StarLifterTerminalPreview.release();
        StarLifterStreams.release();
        StarLifterEffects.release();
        if (meshes != null) {
            meshes.close();
            meshes = null;
        }
    }
}
