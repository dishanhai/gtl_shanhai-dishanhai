package com.dishanhai.gt_shanhai.client.renderer;

import com.dishanhai.gt_shanhai.api.DShanhaiStyleRegistry;
import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import java.util.List;

/**
 * 终末之环自定义 Tooltip — 圆角背景 + 彩虹光环旋转
 * 参考 ArcaneVortex FoxBladeTooltipRenderer + renderRingHalf
 */
public class HaloEndTooltipRenderer {

    private static final ResourceLocation TARGET = new ResourceLocation("dishanhai", "halo_end");
    private static final ResourceLocation BG_TEXTURE = new ResourceLocation("gt_shanhai", "textures/gui/halo_end_tooltip_bg.png");
    private static final int CORNER = 16;
    private static final int TEX_SIZE = 256;

    public static boolean shouldRender(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()).equals(TARGET);
    }

    public static void render(GuiGraphics gui, ItemStack stack, int mouseX, int mouseY,
                              List<Component> tooltipLines, Font font) {
        if (tooltipLines == null || tooltipLines.isEmpty() || font == null) return;

        // 计算尺寸
        int maxLineW = 0;
        int totalH = (tooltipLines.size() > 0) ? (tooltipLines.size() * 10) : 10;
        for (Component c : tooltipLines) {
            int w = font.width(c);
            if (w > maxLineW) maxLineW = w;
        }
        int padding = 8;
        int bgW = maxLineW + padding * 2;
        int bgH = totalH + padding * 2;

        // 位置
        int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int tx = mouseX + 12, ty = mouseY - 12;
        if (tx + bgW > screenW) tx = mouseX - bgW - 8;
        if (ty + bgH > screenH) ty = screenH - bgH - 4;
        if (ty < 4) ty = 4;

        long now = System.currentTimeMillis();
        int[] colors = DShanhaiStyleRegistry.getRGB("ultimate");

        PoseStack pose = gui.pose();
        pose.pushPose();
        pose.translate(0, 0, 800);

        // === 1. 彩虹光环（四角弧形旋转） ===
        renderAura(gui, tx, ty, tx + bgW, ty + bgH, now, colors);

        // === 2. 底图背景（9-slice，16px 边框） ===
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        blitNineSliced(gui, BG_TEXTURE, tx, ty, bgW, bgH);

        // === 3. 模糊内发光边框（叠加在底图上） ===
        RoundedRectRenderer.GradientConfig gradient = new RoundedRectRenderer.GradientConfig(colors, 1.5f, RoundedRectRenderer.GradientConfig.GradientType.BORDER_CIRCULAR);
        RoundedRectRenderer.renderRoundedRectBorder(gui, tx, ty, tx + bgW, ty + bgH,
                8f, 8f, 8f, 8f, 1.5f, gradient, now);
        RoundedRectRenderer.renderRoundedRect(gui, tx + 2, ty + 2, tx + bgW - 2, ty + bgH - 2,
                7f, 7f, 7f, 7f,
                new RoundedRectRenderer.GradientConfig(colors, 0.5f, RoundedRectRenderer.GradientConfig.GradientType.BORDER_CIRCULAR), now);

        // === 5. 文本 ===
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        int lineY = ty + padding;
        for (Component line : tooltipLines) {
            int lineW = font.width(line);
            int lineX = tx + (bgW - lineW) / 2;
            font.drawInBatch(line, lineX, lineY, 0xFFFFFFFF, false,
                    pose.last().pose(), gui.bufferSource(),
                    Font.DisplayMode.NORMAL, 0xF000F0, 0);
            lineY += font.lineHeight + 2;
        }
        pose.popPose();
        RenderSystem.disableBlend();
    }

    // 注释号更新：1=光环 2=底图 3=底栏 4=发泡边框 5=文本

    /** 9-slice 渲染 256x256 纹理，用于任意尺寸的 tooltip 背景 */
    private static void blitNineSliced(GuiGraphics gui, ResourceLocation tex, int x, int y, int w, int h) {
        int c = CORNER;
        int tc = CORNER; // texture corner size
        // 9-slice: 4 corners, 4 edges, 1 center
        // Corners
        gui.blit(tex, x,     y,     c, c,     0,      0,      tc, tc, TEX_SIZE, TEX_SIZE);
        gui.blit(tex, x+w-c, y,     c, c,     TEX_SIZE-tc, 0, tc, tc, TEX_SIZE, TEX_SIZE);
        gui.blit(tex, x,     y+h-c, c, c,     0,      TEX_SIZE-tc, tc, tc, TEX_SIZE, TEX_SIZE);
        gui.blit(tex, x+w-c, y+h-c, c, c,     TEX_SIZE-tc, TEX_SIZE-tc, tc, tc, TEX_SIZE, TEX_SIZE);
        // Top/bottom edges
        gui.blit(tex, x+c,   y,     w-c*2, c, tc, 0, TEX_SIZE-tc*2, tc, TEX_SIZE, TEX_SIZE);
        gui.blit(tex, x+c,   y+h-c, w-c*2, c, tc, TEX_SIZE-tc, TEX_SIZE-tc*2, tc, TEX_SIZE, TEX_SIZE);
        // Left/right edges
        gui.blit(tex, x,     y+c,   c, h-c*2, 0, tc, tc, TEX_SIZE-tc*2, TEX_SIZE, TEX_SIZE);
        gui.blit(tex, x+w-c, y+c,   c, h-c*2, TEX_SIZE-tc, tc, tc, TEX_SIZE-tc*2, TEX_SIZE, TEX_SIZE);
        // Center
        gui.blit(tex, x+c,   y+c,   w-c*2, h-c*2, tc, tc, TEX_SIZE-tc*2, TEX_SIZE-tc*2, TEX_SIZE, TEX_SIZE);
    }

    /** 彩虹旋转光环 — 四角弧线随颜色流动 */
    private static void renderAura(GuiGraphics gui, float x, float y, float x2, float y2,
                                    long time, int[] palette) {
        PoseStack pose = gui.pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        float extend = 6f;  // 光环超出边框的距离
        int segments = 20;  // 每角细分
        float cx = (x + x2) / 2f;
        float cy = (y + y2) / 2f;
        float hw = (x2 - x) / 2f + extend;
        float hh = (y2 - y) / 2f + extend;

        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // 四条边上的光点（沿椭圆轨迹旋转）
        float angleStep = 360f / 12;
        for (int i = 0; i < 12; i++) {
            float angle = (float)Math.toRadians(i * angleStep + (time % 6000L) / 6000f * 360);
            float px = cx + (float)Math.cos(angle) * hw;
            float py = cy + (float)Math.sin(angle) * hh;
            float innerX = cx + (float)Math.cos(angle) * (hw - extend);
            float innerY = cy + (float)Math.sin(angle) * (hh - extend);

            int idx = (i + (int)(time / 100) % palette.length) % palette.length;
            int col = palette[idx] | 0x88000000;
            int colFade = palette[idx] | 0x22000000;

            // 每个光点是一小段梯形（从外到内渐变）
            Matrix4f mat = pose.last().pose();
            buf.vertex(mat, px, py, 0).color(col).endVertex();
            buf.vertex(mat, px + 2, py + 2, 0).color(colFade).endVertex();
            buf.vertex(mat, innerX + 2, innerY + 2, 0).color(0x00000000).endVertex();
            buf.vertex(mat, innerX, innerY, 0).color(0x00000000).endVertex();
        }
        BufferUploader.drawWithShader(buf.end());
        RenderSystem.disableBlend();
    }

    private static void addRect(BufferBuilder buf, Matrix4f mat, float x1, float y1, float x2, float y2, int color) {
        buf.vertex(mat, x1, y1, 0).color(color).endVertex();
        buf.vertex(mat, x2, y1, 0).color(color).endVertex();
        buf.vertex(mat, x2, y2, 0).color(color).endVertex();
        buf.vertex(mat, x1, y2, 0).color(color).endVertex();
    }
}
