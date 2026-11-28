package com.dishanhai.gt_shanhai.client.renderer.preview;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.dishanhai.gt_shanhai.config.ShanhaiUiClientConfig.StarLifterVisual;
import com.dishanhai.gt_shanhai.config.ShanhaiUiClientConfig.StarLifterMotion;
import com.dishanhai.sh_better_ui.client.gui.scaled.UiPreferences;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;

import java.io.IOException;

import static com.dishanhai.sh_better_ui.client.api.ShBetterUiApi.motion;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL14.*;

/** Shared procedural sky for the Star Lifter terminal and recipe cards. */
public final class StarLifterTerminalSky {
    private static final IGuiTexture RECIPE_BACKGROUND = (graphics, mouseX, mouseY, x, y, width, height) ->
            draw(graphics, Math.round(x), Math.round(y), width, height);
    private static ShaderInstance shader;
    private static long previousFrame;
    private static double elapsed;

    private StarLifterTerminalSky() {}

    public static void installRecipeBackground() {
        DShanhaiRecipeTypes.STAR_LIFTING.getRecipeUI().setUiBuilder(
                (recipe, card) -> card.setBackground(RECIPE_BACKGROUND));
        DShanhaiRecipeTypes.STELLAR_DECONSTRUCTION.getRecipeUI().setUiBuilder(
                (recipe, card) -> card.setBackground(RECIPE_BACKGROUND));
    }

    static void register(RegisterShadersEvent event) {
        shader = null;
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    new ResourceLocation(GTDishanhaiMod.MOD_ID, "star_lifter_terminal_sky"),
                    DefaultVertexFormat.POSITION_TEX), ready -> shader = ready);
        } catch (IOException | RuntimeException failure) {
            GTDishanhaiMod.LOGGER.warn("摘星者终端星空着色器加载失败", failure);
        }
    }

    public static void draw(GuiGraphics graphics, int x, int y, int width, int height) {
        draw(graphics, x, y, width, height, 1);
    }

    public static void draw(GuiGraphics graphics, int x, int y, int width, int height, float reveal) {
        draw(graphics, x, y, width, height, reveal, 1, 0, 0);
    }

    public static void draw(GuiGraphics graphics, int x, int y, int width, int height, float reveal, float opacity,
                            float parallaxX, float parallaxY) {
        if (width <= 0 || height <= 0 || opacity <= 0) return;
        var active = shader;
        if (active == null) {
            graphics.fill(x, y, x + width, y + height, Math.round(opacity * 255) << 24 | 0x080d20);
            return;
        }
        long now = motion().nowMs();
        var quality = StarLifterMotion.current();
        if (previousFrame != 0 && now - previousFrame < 120 && StarLifterVisual.TERMINAL_MOTION.enabled()
                && !UiPreferences.configured().reducedMotion() && quality != StarLifterMotion.REDUCED
                && quality != StarLifterMotion.MINIMAL) {
            elapsed += (now - previousFrame) * (quality == StarLifterMotion.FULL ? .001 : .00042);
        }
        previousFrame = now;
        graphics.flush();
        var preceding = RenderSystem.getShader();
        var blend = opacity < 1 ? new BlendState(glIsEnabled(GL_BLEND), glGetInteger(GL_BLEND_SRC_RGB),
                glGetInteger(GL_BLEND_DST_RGB), glGetInteger(GL_BLEND_SRC_ALPHA), glGetInteger(GL_BLEND_DST_ALPHA)) : null;
        try (var profile = StarLifterProfile.begin("StarLifterUI.Background", true)) {
            if (blend != null) {
                // Native widget batches can change GL blending without changing the last applied shader blend mode.
                RenderSystem.enableBlend();
                RenderSystem.blendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            }
            active.safeGetUniform("SkyTime").set((float) elapsed);
            active.safeGetUniform("SkyAspect").set((float) width / height);
            active.safeGetUniform("SkyReveal").set(reveal);
            active.safeGetUniform("SkyOpacity").set(opacity);
            boolean still = quality == StarLifterMotion.REDUCED || quality == StarLifterMotion.MINIMAL
                    || !StarLifterVisual.TERMINAL_MOTION.enabled() || UiPreferences.configured().reducedMotion();
            active.safeGetUniform("SkyParallax").set(still ? 0 : parallaxX, still ? 0 : parallaxY);
            RenderSystem.setShader(() -> active);
            var matrix = graphics.pose().last().pose();
            var quad = Tesselator.getInstance().getBuilder();
            quad.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            quad.vertex(matrix, x, y + height, 0).uv(0, 1).endVertex();
            quad.vertex(matrix, x + width, y + height, 0).uv(1, 1).endVertex();
            quad.vertex(matrix, x + width, y, 0).uv(1, 0).endVertex();
            quad.vertex(matrix, x, y, 0).uv(0, 0).endVertex();
            BufferUploader.drawWithShader(quad.end());
        } finally {
            RenderSystem.setShader(() -> preceding);
            if (blend != null) blend.restore();
        }
    }

    private record BlendState(boolean enabled, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        void restore() {
            RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            if (enabled) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
        }
    }
}
