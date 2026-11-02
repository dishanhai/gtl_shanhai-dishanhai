package com.dishanhai.gt_shanhai.client.loading;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/** 山海：每次載入僅生成一次玻璃底圖，補幀只畫一張 GPU 貼圖及動態文字。 */
final class ShanhaiLoadingBackdrop {
    private static TextureTarget texture;
    private static Key key;
    private ShanhaiLoadingBackdrop() {}

    static boolean draw(int width, int height, Object owner, float opacity) {
        Key next = current(width, height, owner);
        if (texture == null || !next.equals(key)) { release(); return false; }
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture.getColorTextureId());
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        int alpha = Math.round(255 * Math.max(0, Math.min(1, opacity)));
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        buffer.vertex(0, height, 0).uv(0, 0).color(255, 255, 255, alpha).endVertex();
        buffer.vertex(width, height, 0).uv(1, 0).color(255, 255, 255, alpha).endVertex();
        buffer.vertex(width, 0, 0).uv(1, 1).color(255, 255, 255, alpha).endVertex();
        buffer.vertex(0, 0, 0).uv(0, 1).color(255, 255, 255, alpha).endVertex();
        BufferUploader.drawWithShader(buffer.end());
        return true;
    }

    static void capture(int width, int height, Object owner) {
        Key next = current(width, height, owner);
        int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING), draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4]; glGetIntegerv(GL_VIEWPORT, viewport);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        try {
            glDisable(GL_SCISSOR_TEST);
            release();
            texture = new TextureTarget(next.pixelsWide(), next.pixelsHigh(), false, Minecraft.ON_OSX);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, draw);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, texture.frameBufferId);
            glBlitFramebuffer(0, 0, next.pixelsWide(), next.pixelsHigh(), 0, 0,
                    next.pixelsWide(), next.pixelsHigh(), GL_COLOR_BUFFER_BIT, GL_NEAREST);
            // 已合成的全畫面底圖必須視為不透明；局部 glow 的來源 alpha 不可再次混色。
            try (var stack = MemoryStack.stackPush()) {
                var mask = stack.malloc(4);
                glGetBooleanv(GL_COLOR_WRITEMASK, mask);
                try {
                    RenderSystem.colorMask(false, false, false, true);
                    glClearBufferfv(GL_COLOR, 0, new float[]{0, 0, 0, 1});
                } finally {
                    RenderSystem.colorMask(mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0);
                }
            }
            key = next;
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            if (scissor) glEnable(GL_SCISSOR_TEST);
        }
    }

    static void release() {
        if (texture != null) {
            int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING), draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
            try { texture.destroyBuffers(); }
            finally {
                glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
            }
        }
        texture = null; key = null;
    }

    private static Key current(int width, int height, Object owner) {
        var target = Minecraft.getInstance().getMainRenderTarget();
        return new Key(target.width, target.height, width, height, owner);
    }
    private record Key(int pixelsWide, int pixelsHigh, int guiWidth, int guiHeight, Object owner) {}
}
