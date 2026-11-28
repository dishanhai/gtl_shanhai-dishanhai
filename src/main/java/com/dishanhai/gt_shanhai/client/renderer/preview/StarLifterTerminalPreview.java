package com.dishanhai.gt_shanhai.client.renderer.preview;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterGeometry;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterGeometry.Point;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterStructure;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterVisualState;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterInnerLatchBlock;
import com.dishanhai.gt_shanhai.config.ShanhaiUiClientConfig.StarLifterMotion;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.Locale;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL13.GL_MULTISAMPLE;
import static org.lwjgl.opengl.GL33.glBindSampler;

/** Reuses the world meshes and effects in an isolated render target. */
public final class StarLifterTerminalPreview {
    private static final Storage INLINE = new Storage(), FULLSCREEN = new Storage();
    private static final float[] CLEAR_COLOR = {0, 0, 0, 0}, CLEAR_DEPTH = {1};
    private static ShaderInstance composite, observation;
    private static Storage presented;
    private static int activeWidth, activeHeight, textureLimit, sampleLimit;
    private static VertexBuffer fixed;
    private static View rendered;
    private static float renderedAspect;
    private static int hovered = -1;
    private static final Matrix4f CAMERA = new Matrix4f();
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static final Matrix4f PERSPECTIVE = new Matrix4f();
    private static final Matrix4f PROJECT_FAULT = new Matrix4f();
    private static final Matrix4f INVERSE = new Matrix4f();

    public record View(float yaw, float pitch, float zoom, float panX, float panY, float heading,
                       double ticks, int ring, boolean isolate, float stellar, float transport, StarLifterMotion quality,
                       StarLifterVisualState visual, float ignition, float reserveFraction,
                       float fitSpan, float light, float bootExposure, float bootScan, float fieldOfView,
                       float selection, float selectionSweep, float engineering, float projectionMix) {
        boolean starOnly() { return stellar >= .995f; }
        boolean perspective() { return projectionMix > 0 && fieldOfView > 0; }
    }

    private StarLifterTerminalPreview() {}

    private static final class Storage {
        final StarLifterPreviewSamples samples = new StarLifterPreviewSamples();
        TextureTarget target;
        int requestedSamples;

        boolean fits(int width, int height, int count) {
            return target != null && target.width >= width && target.height >= height && requestedSamples == count;
        }

        void reserve(int width, int height, int count) {
            if (target != null) {
                width = Math.max(width, target.width);
                height = Math.max(height, target.height);
                target.destroyBuffers();
            }
            samples.resize(width, height, count);
            target = new TextureTarget(width, height, !samples.multisampled(), Minecraft.ON_OSX);
            target.setFilterMode(GL_LINEAR);
            requestedSamples = count;
        }

        void close() {
            if (target == null) return;
            target.destroyBuffers();
            samples.close();
            target = null;
        }
    }

    private static final class Envelope {
        static final float RADIUS = measure();

        private static float measure() {
            var outer = StarLifterGeometry.RINGS.get(StarLifterGeometry.OUTER_RING);
            double[] max = {outer.outerLimit() * outer.outerLimit() + outer.height() * outer.height()};
            StarLifterStructure.forEachFixedPreviewBlock((x, y, z, symbol) -> {
                double nx = (Math.abs(x) + .5) / StarLifterGeometry.DEFAULT_RADIUS;
                double ny = (Math.abs(y) + .5) / StarLifterGeometry.DEFAULT_RADIUS;
                double nz = (Math.abs(z) + .5) / StarLifterGeometry.DEFAULT_RADIUS;
                max[0] = Math.max(max[0], nx * nx + ny * ny + nz * nz);
            });
            return (float) Math.sqrt(max[0]);
        }
    }

    public static float fitSpan(float aspect, float stellar) {
        return Mth.lerp(stellar, Envelope.RADIUS / .89f, .65f) / Math.min(1, aspect);
    }

    static void register(RegisterShadersEvent event) {
        composite = null;
        observation = null;
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    new ResourceLocation(GTDishanhaiMod.MOD_ID, "star_lifter_terminal_composite"),
                    DefaultVertexFormat.POSITION_TEX_COLOR), shader -> composite = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    new ResourceLocation(GTDishanhaiMod.MOD_ID, "star_lifter_observation"),
                    DefaultVertexFormat.NEW_ENTITY), shader -> observation = shader);
        } catch (IOException | RuntimeException error) {
            GTDishanhaiMod.LOGGER.warn("摘星者预览合成着色器加载失败", error);
        }
    }

    public static StarLifterProfile profile(String stage) {
        return StarLifterProfile.begin("StarLifterUI." + stage, false);
    }

    public static void draw(GuiGraphics graphics, float x, float y, float w, float h, View view, Vec3 fault,
                            float mx, float my, boolean fullscreen, float reserveWidth, float reserveHeight,
                            float qualityWidth, float qualityHeight) {
        graphics.flush();
        var window = Minecraft.getInstance().getWindow();
        double densityX = (double) window.getWidth() / window.getGuiScaledWidth();
        double densityY = (double) window.getHeight() / window.getGuiScaledHeight();
        if (textureLimit == 0) {
            textureLimit = glGetInteger(GL_MAX_TEXTURE_SIZE);
            sampleLimit = glGetInteger(GL_MAX_SAMPLES);
        }
        double budgetPixels = qualityWidth * densityX * qualityHeight * densityY;
        float scale = switch (view.quality) {
            case MINIMAL -> .8f;
            case FULL -> (float) Math.clamp(Math.sqrt(8_294_400d / budgetPixels), 1, 2);
            default -> 1;
        };
        int desiredWidth = pixels(w, densityX * scale), desiredHeight = pixels(h, densityY * scale);
        int storageWidth = pixels(Math.max(w, reserveWidth), densityX * scale);
        int storageHeight = pixels(Math.max(h, reserveHeight), densityY * scale);
        int desiredSamples = scale != 1 ? 0 : Math.min(budgetPixels > 2_400_000 ? 0 : budgetPixels > 1_000_000 ? 2 : 4, sampleLimit);
        var storage = fullscreen ? FULLSCREEN : INLINE;
        boolean allocate = !storage.fits(storageWidth, storageHeight, desiredSamples);
        float aspect = w / h;
        boolean viewChanged = !view.equals(rendered) || aspect != renderedAspect;
        if (viewChanged) {
            project(view, aspect, CAMERA, PROJECTION);
            INVERSE.set(PROJECTION).mul(CAMERA).invert();
        }
        int hit;
        try (var profile = profile("HitTest")) {
            hit = pick(view, mx, my);
        }
        if (viewChanged || hit != hovered || allocate || presented != storage
                || activeWidth != desiredWidth || activeHeight != desiredHeight) {
            hovered = hit;
            try (var profile = StarLifterProfile.begin("UIPreview", true)) {
                render(view, storage, profile, desiredWidth, desiredHeight,
                        allocate ? storageWidth : 0, storageHeight, desiredSamples);
            }
            presented = storage;
            rendered = view;
            renderedAspect = aspect;
        }
        try (var profile = StarLifterProfile.begin("StarLifterUI.Composite", true)) {
            drawRetained(graphics, x, y, w, h, 1);
        }
        if (fault != null) {
            var screen = PROJECT_FAULT.set(PROJECTION).mul(CAMERA)
                    .transformProject(new Vector3f((float) fault.x, (float) fault.y, (float) fault.z));
            int px = Math.round(x + (screen.x + 1) * .5f * w);
            int py = Math.round(y + (1 - screen.y) * .5f * h);
            if (px > x + 4 && px < x + w - 4 && py > y + 4 && py < y + h - 4) {
                graphics.renderOutline(px - 3, py - 3, 7, 7, 0xffffad92);
                graphics.fill(px, py, px + 1, py + 1, 0xffffad92);
            }
        }
    }

    static void project(View view, float aspect, Matrix4f camera, Matrix4f projection) {
        float span = view.fitSpan / view.zoom;
        float distance = 4;
        projection.setOrtho(-span * aspect, span * aspect, -span, span, 2, 6);
        if (view.perspective()) {
            double tangent = Math.tan(view.fieldOfView * .5);
            double halfAngle = Math.atan(tangent * Math.min(1, aspect));
            float radius = Envelope.RADIUS;
            float perspectiveDistance = Math.max((float) StarLifterGeometry.STAR_RADIUS + .035f,
                    (float) (radius / (Math.sin(halfAngle) * .83 * view.zoom)));
            distance = Mth.lerp(view.projectionMix, distance, perspectiveDistance);
            PERSPECTIVE.setPerspective(view.fieldOfView, aspect, Math.max(.045f, distance - radius * 1.4f),
                    distance + radius * 2.4f);
            // The focal plane is at Z = -distance in view space.
            projection.m32(distance * projection.m22());
            // Keep clip-space W = 1 at the focal plane for both projections before mixing.
            PERSPECTIVE.m00(PERSPECTIVE.m00() / distance).m11(PERSPECTIVE.m11() / distance)
                    .m22(PERSPECTIVE.m22() / distance).m23(PERSPECTIVE.m23() / distance)
                    .m32(PERSPECTIVE.m32() / distance);
            projection.lerp(PERSPECTIVE, view.projectionMix);
            span = Mth.lerp(view.projectionMix, span, (float) (distance * tangent));
        }
        camera.translation(view.panX * span * aspect * 2, -view.panY * span * 2, -distance)
                .rotate(ROTATION.rotationX(view.pitch).rotateY(view.yaw));
    }

    private static int pixels(float logical, double density) {
        return Math.clamp((int) Math.round(logical * density), 1, textureLimit);
    }

    public static int pick(float x, float y) {
        return rendered == null ? -1 : pick(rendered, x, y);
    }

    private static int pick(View view, float x, float y) {
        if (view.starOnly() || x < 0 || x >= 1 || y < 0 || y >= 1) return -1;
        var origin = INVERSE.transformProject(new Vector3f(2 * x - 1, 1 - 2 * y, -1));
        var end = INVERSE.transformProject(new Vector3f(2 * x - 1, 1 - 2 * y, 1));
        return StarLifterRingPicking.pick(origin, end.sub(origin).normalize(), view);
    }

    private static void render(View view, Storage storage, StarLifterProfile profile,
                               int width, int height, int allocateWidth, int allocateHeight, int desiredSamples) {
        int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING), draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);
        int[] colorWrite = new int[4];
        glGetIntegerv(GL_COLOR_WRITEMASK, colorWrite);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST), depth = glIsEnabled(GL_DEPTH_TEST);
        int[] clip = scissor ? new int[4] : null;
        if (clip != null) glGetIntegerv(GL_SCISSOR_BOX, clip);
        boolean blend = glIsEnabled(GL_BLEND), cull = glIsEnabled(GL_CULL_FACE);
        boolean multisample = glIsEnabled(GL_MULTISAMPLE);
        boolean depthWrite = glGetBoolean(GL_DEPTH_WRITEMASK);
        int depthFunc = glGetInteger(GL_DEPTH_FUNC);
        int srcRgb = glGetInteger(GL_BLEND_SRC_RGB), dstRgb = glGetInteger(GL_BLEND_DST_RGB);
        int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        var shader = RenderSystem.getShader();
        int[] textures = {RenderSystem.getShaderTexture(0), RenderSystem.getShaderTexture(1), RenderSystem.getShaderTexture(2)};
        var shaderColor = RenderSystem.getShaderColor().clone();
        float fogStart = RenderSystem.getShaderFogStart();
        var previousProjection = RenderSystem.getProjectionMatrix();
        var previousSorting = RenderSystem.getVertexSorting();
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        try {
            if (scissor) RenderSystem.disableScissor();
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            if (allocateWidth != 0) {
                storage.reserve(allocateWidth, allocateHeight, desiredSamples);
                StarLifterProfile.framebuffer(profile);
            }
            var target = storage.target;
            var samples = storage.samples;
            activeWidth = width;
            activeHeight = height;
            if (fixed == null && !view.starOnly() && !view.isolate) fixed = bakeFixed();
            if (samples.multisampled()) {
                samples.bind();
                if (!multisample) glEnable(GL_MULTISAMPLE);
            } else {
                target.bindWrite(false);
            }
            RenderSystem.viewport(0, 0, width, height);
            if (width < target.width || height < target.height) RenderSystem.enableScissor(0, 0, width, height);
            glClearBufferfv(GL_COLOR, 0, CLEAR_COLOR);
            glClearBufferfv(GL_DEPTH, 0, CLEAR_DEPTH);
            // The shared effect batches read RenderSystem's matrices as well as their local pose.
            modelView.last().pose().identity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(PROJECTION, VertexSorting.ORTHOGRAPHIC_Z);
            RenderSystem.setShaderFogStart(Float.MAX_VALUE);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            var quality = switch (view.quality) {
                case FULL -> StarLifterStreams.Quality.NEAR;
                case STANDARD -> StarLifterStreams.Quality.MEDIUM;
                case MINIMAL, REDUCED -> StarLifterStreams.Quality.FAR;
            };
            StarLifterRenderer.drawTerminal(CAMERA, PROJECTION, view, hovered, quality, fixed, profile,
                    StarLifterEffects.Activity.sample(view.visual, view.ignition, view.reserveFraction), observation, samples);
            if (samples.multisampled()) {
                RenderSystem.colorMask(true, true, true, true);
                samples.resolve(target.frameBufferId, width, height);
            }
            if (allocateWidth != 0) StarLifterProfile.previewStorage(width, height, target.width, target.height,
                    samples.samples(), samples.mipLevels(), width * (long) height,
                    target.width * (long) target.height * (samples.multisampled() ? 4 : 8)
                            + samples.attachmentBytes(target.width, target.height));
        } finally {
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
            RenderSystem.setShaderFogStart(fogStart);
            GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
            GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            if (clip != null) RenderSystem.enableScissor(clip[0], clip[1], clip[2], clip[3]);
            else RenderSystem.disableScissor();
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            if (!multisample && glIsEnabled(GL_MULTISAMPLE)) glDisable(GL_MULTISAMPLE);
            RenderSystem.colorMask(colorWrite[0] != 0, colorWrite[1] != 0, colorWrite[2] != 0, colorWrite[3] != 0);
            RenderSystem.depthMask(depthWrite);
            RenderSystem.depthFunc(depthFunc);
            RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            RenderSystem.setShader(() -> shader);
            for (int i = 0; i < textures.length; i++) RenderSystem.setShaderTexture(i, textures[i]);
            RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
        }
    }

    private static final org.joml.Quaternionf ROTATION = new org.joml.Quaternionf();

    public static void drawRetained(GuiGraphics graphics, float x, float y, float w, float h, float opacity) {
        if (presented == null || opacity <= 0) return;
        opacity *= rendered.bootExposure;
        var target = presented.target;
        graphics.flush();
        boolean blend = glIsEnabled(GL_BLEND);
        boolean depth = glIsEnabled(GL_DEPTH_TEST);
        boolean depthWrite = glGetBoolean(GL_DEPTH_WRITEMASK);
        int srcRgb = glGetInteger(GL_BLEND_SRC_RGB), dstRgb = glGetInteger(GL_BLEND_DST_RGB);
        int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        var shader = RenderSystem.getShader();
        int texture = RenderSystem.getShaderTexture(0);
        boolean managedSampler = StarLifterPreviewSamples.hasSamplerObjects();
        int sampler = managedSampler ? glGetIntegeri(org.lwjgl.opengl.GL33.GL_SAMPLER_BINDING, 0) : 0;
        try {
            RenderSystem.setShader(() -> composite == null ? GameRenderer.getPositionTexColorShader() : composite);
            RenderSystem.setShaderTexture(0, target.getColorTextureId());
            if (sampler != 0) glBindSampler(0, 0);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            var matrix = graphics.pose().last().pose();
            var buffer = Tesselator.getInstance().getBuilder();
            float u = (float) activeWidth / target.width, v = (float) activeHeight / target.height;
            if (composite != null) composite.safeGetUniform("ActiveUV").set(u, v);
            int exposure = Math.round(opacity * 255);
            buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            buffer.vertex(matrix, x, y + h, 0).uv(0, 0).color(exposure, exposure, exposure, exposure).endVertex();
            buffer.vertex(matrix, x + w, y + h, 0).uv(u, 0).color(exposure, exposure, exposure, exposure).endVertex();
            buffer.vertex(matrix, x + w, y, 0).uv(u, v).color(exposure, exposure, exposure, exposure).endVertex();
            buffer.vertex(matrix, x, y, 0).uv(0, v).color(exposure, exposure, exposure, exposure).endVertex();
            BufferUploader.drawWithShader(buffer.end());
        } finally {
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderTexture(0, texture);
            if (sampler != 0) glBindSampler(0, sampler);
            RenderSystem.depthMask(depthWrite);
            if (depth) RenderSystem.enableDepthTest();
            RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            if (!blend) RenderSystem.disableBlend();
        }
    }

    private static VertexBuffer bakeFixed() {
        return StarLifterRenderer.uploadMesh(DefaultVertexFormat.NEW_ENTITY, StarLifterTerminalPreview::writeFixed);
    }

    private static void writeFixed(BufferBuilder builder) {
        var atlas = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS);
        var sprites = new TextureAtlasSprite[128];
        var faces = Direction.values();
        StarLifterStructure.forEachFixedPreviewBlock((x, y, z, symbol) -> {
            var sprite = sprites[symbol];
            if (sprite == null) {
                String material = switch (symbol) {
                    case 'L' -> "manifold";
                    case 'M' -> "beam";
                    case 'K' -> "extractor";
                    case '~', 'J' -> "socket";
                    default -> StarLifterGeometry.Surface.values()[Character.toUpperCase(symbol) - 'B']
                            .name().toLowerCase(Locale.ROOT);
                };
                sprite = atlas.apply(new ResourceLocation(GTDishanhaiMod.MOD_ID, "block/star_lifter/" + material));
                sprites[symbol] = sprite;
            }
            double width = symbol == 'M' ? 2 * StarLifterInnerLatchBlock.HALF_WIDTH : 1;
            for (var face : faces) {
                char neighbor = StarLifterStructure.previewSymbol(x + face.getStepX(), y + face.getStepY(),
                        z + face.getStepZ());
                if (StarLifterStructure.isFixedPreviewBlock(neighbor)
                        && (face.getAxis().isVertical() || symbol != 'M' && neighbor != 'M')) continue;
                var normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
                var right = face.getAxis().isVertical() ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0).cross(normal);
                var up = normal.cross(right);
                right = right.multiply(width, 1, width);
                up = up.multiply(width, 1, width);
                var corner = new Vec3(x, y, z).add(normal.multiply(width, 1, width)
                        .subtract(right).subtract(up).scale(.5));
                StarLifterRenderer.vertex(builder, point(corner), sprite.getU0(), sprite.getV1(), normal, 210);
                StarLifterRenderer.vertex(builder, point(corner.add(right)), sprite.getU1(), sprite.getV1(), normal, 210);
                StarLifterRenderer.vertex(builder, point(corner.add(right).add(up)), sprite.getU1(), sprite.getV0(), normal, 210);
                StarLifterRenderer.vertex(builder, point(corner.add(up)), sprite.getU0(), sprite.getV0(), normal, 210);
            }
        });
    }

    private static Point point(Vec3 p) {
        double scale = 1.0 / StarLifterGeometry.DEFAULT_RADIUS;
        return new Point(p.x * scale, p.y * scale, p.z * scale);
    }

    static void release() {
        closeFrame();
        if (fixed != null) fixed.close();
        fixed = null;
    }

    public static void closeFrame() {
        if (INLINE.target == null && FULLSCREEN.target == null) return;
        int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING), draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            INLINE.close();
            FULLSCREEN.close();
        } finally {
            GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
            GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
        }
        presented = null;
        rendered = null;
    }
}
