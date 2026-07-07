package com.dishanhai.gt_shanhai.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * 圆角矩形渲染器 — 参考 ArcaneVortex RoundedRectRenderer
 * 支持渐变填充 + 渐变边框
 */
public class RoundedRectRenderer {

    private static final int SEGMENTS = 12;

    /** 渐变配置 — 参考 ArcaneVortex GradientConfig */
    public static class GradientConfig {

        public enum GradientType {
            HORIZONTAL,
            BORDER_CIRCULAR
        }

        private final int[] colors;
        private final float speed;
        private final GradientType type;

        public GradientConfig(int[] colors, float speed, GradientType type) {
            this.colors = colors;
            this.speed = speed;
            this.type = type;
        }

        public int[] getColors() { return colors; }
        public float getSpeed() { return speed; }
        public GradientType getType() { return type; }

        /** 根据进度 [0,1] + 时间获取当前插值颜色 */
        public int getColorAt(float progress, long time) {
            if (colors == null || colors.length == 0) return 0xFFFFFFFF;
            float phase = (time % 10000L) / 10000f * speed;
            float pos = (progress + phase) % 1f;
            float idx = pos * (colors.length - 1);
            int i = (int) idx;
            float frac = idx - i;
            if (i >= colors.length - 1) return colors[colors.length - 1];
            return interpolateColor(colors[i], colors[i + 1], frac);
        }

        private static int interpolateColor(int c1, int c2, float t) {
            int r = (int) (((c1 >> 16) & 0xFF) * (1 - t) + ((c2 >> 16) & 0xFF) * t);
            int g = (int) (((c1 >> 8) & 0xFF) * (1 - t) + ((c2 >> 8) & 0xFF) * t);
            int b = (int) ((c1 & 0xFF) * (1 - t) + (c2 & 0xFF) * t);
            return 0xFF000000 | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
        }
    }

    /**
     * 渲染圆角矩形填充（渐变）
     */
    public static void renderRoundedRect(GuiGraphics gui, float x, float y, float x2, float y2,
                                          float rTL, float rTR, float rBR, float rBL,
                                          GradientConfig gradient, long time) {
        float w = x2 - x;
        float h = y2 - y;
        if (w <= 0 || h <= 0) return;

        PoseStack pose = gui.pose();
        Matrix4f mat = pose.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // 中心矩形
        addQuadGradient(buf, mat, x + rTL, y, x2 - rTR, y + rBL,
                gradient.getColorAt(0.25f, time), gradient.getColorAt(0.75f, time),
                gradient.getColorAt(0.25f, time), gradient.getColorAt(0.75f, time));
        addQuadGradient(buf, mat, x + rTL, y + rBL, x2 - rTR, y2 - rBR,
                gradient.getColorAt(0.25f, time), gradient.getColorAt(0.75f, time),
                gradient.getColorAt(0.25f, time), gradient.getColorAt(0.75f, time));

        // 四条边
        // 上
        addQuadGradient(buf, mat, x, y, x2, y + rTL,
                gradient.getColorAt(0f, time), gradient.getColorAt(1f, time),
                gradient.getColorAt(0f, time), gradient.getColorAt(1f, time));
        // 下
        addQuadGradient(buf, mat, x, y2 - rBL, x2, y2,
                gradient.getColorAt(0f, time), gradient.getColorAt(1f, time),
                gradient.getColorAt(0f, time), gradient.getColorAt(1f, time));
        // 左
        addQuadGradient(buf, mat, x, y + rTL, x + rTL, y2 - rBL,
                gradient.getColorAt(0f, time), gradient.getColorAt(0f, time),
                gradient.getColorAt(0.5f, time), gradient.getColorAt(0.5f, time));
        // 右
        addQuadGradient(buf, mat, x2 - rTR, y + rTR, x2, y2 - rBR,
                gradient.getColorAt(1f, time), gradient.getColorAt(1f, time),
                gradient.getColorAt(0.5f, time), gradient.getColorAt(0.5f, time));

        // 四角
        addCornerArc(buf, mat, x + rTL, y + rTL, rTL, 180, 270, gradient, time);
        addCornerArc(buf, mat, x2 - rTR, y + rTR, rTR, 270, 360, gradient, time);
        addCornerArc(buf, mat, x2 - rBR, y2 - rBR, rBR, 0, 90, gradient, time);
        addCornerArc(buf, mat, x + rBL, y2 - rBL, rBL, 90, 180, gradient, time);

        BufferUploader.drawWithShader(buf.end());
        RenderSystem.disableBlend();
    }

    /**
     * 渲染圆角矩形渐变边框
     */
    public static void renderRoundedRectBorder(GuiGraphics gui, float x, float y, float x2, float y2,
                                                float rTL, float rTR, float rBR, float rBL,
                                                float borderWidth, GradientConfig gradient, long time) {
        // 外边框和内边框，用渐变填充
        float bw = borderWidth;
        renderRoundedRect(gui, x - bw, y - bw, x2 + bw, y2 + bw, rTL + bw, rTR + bw, rBR + bw, rBL + bw, gradient, time);
    }

    // ===== 内部工具 =====

    private static void addQuadGradient(BufferBuilder buf, Matrix4f mat,
                                         float x1, float y1, float x2, float y2,
                                         int cTL, int cTR, int cBL, int cBR) {
        buf.vertex(mat, x1, y1, 0).color(cTL).endVertex();
        buf.vertex(mat, x2, y1, 0).color(cTR).endVertex();
        buf.vertex(mat, x2, y2, 0).color(cBR).endVertex();
        buf.vertex(mat, x1, y2, 0).color(cBL).endVertex();
    }

    private static void addCornerArc(BufferBuilder buf, Matrix4f mat,
                                      float cx, float cy, float r,
                                      int startDeg, int endDeg,
                                      GradientConfig gradient, long time) {
        float step = 90f / SEGMENTS;
        float midProgress = (startDeg + endDeg) / 2f / 360f;
        int centerColor = gradient.getColorAt(midProgress, time);

        float prevX = cx + r * (float) Math.cos(Math.toRadians(startDeg));
        float prevY = cy + r * (float) Math.sin(Math.toRadians(startDeg));

        for (float deg = startDeg + step; deg <= endDeg + 0.01f; deg += step) {
            float rad = (float) Math.toRadians(Math.min(deg, endDeg));
            float curX = cx + r * (float) Math.cos(rad);
            float curY = cy + r * (float) Math.sin(rad);

            float prog = deg / 360f;
            int edgeColor = gradient.getColorAt(prog, time);

            buf.vertex(mat, cx, cy, 0).color(centerColor).endVertex();
            buf.vertex(mat, prevX, prevY, 0).color(edgeColor).endVertex();
            buf.vertex(mat, curX, curY, 0).color(edgeColor).endVertex();

            prevX = curX;
            prevY = curY;
        }
    }
}
