package com.dishanhai.gt_shanhai.client.loading;

import com.dishanhai.sh_better_ui.client.glass.GlassPalette;
import java.util.ArrayList;
import java.util.List;

/** 山海：玻璃背後的低對比山海光景，面板直接沿用商店主題。 */
public final class ShanhaiLoadingScene {
    private ShanhaiLoadingScene() {}

    public static List<Quad> frame(float width, float height, long elapsedMs, float opacity, boolean reduceMotion) {
        List<Quad> quads = new ArrayList<>(520);
        float time = reduceMotion ? 0 : elapsedMs / 1000f;
        quads.add(new Quad(0, height, width, height, width, 0, 0, 0,
                alpha(0xff091724, opacity), alpha(0xff162c3d, opacity)));
        glow(quads, width * (.2f + .025f * (float) Math.sin(time * .12)), height * .28f,
                width * .38f, height * .58f, 0xff387c94, opacity * .34f);
        glow(quads, width * (.8f + .02f * (float) Math.cos(time * .1)), height * .65f,
                width * .35f, height * .5f, 0xff287c79, opacity * .2f);
        for (int i = 0; i < 20; i++) {
            float x = fract(i * .618034f + .13f) * width, y = fract(i * .414214f + .27f) * height;
            rect(quads, x, y, x + .65f, y + .65f, alpha(GlassPalette.TEXT_HEAD,
                    opacity * (.15f + .1f * (float) Math.sin(time * .4 + i))));
        }
        for (int layer = 0; layer < 3; layer++) {
            float lastX = 0, lastY = mountain(0, height, layer);
            for (int i = 1; i <= 32; i++) {
                float x = width * i / 32, y = mountain(i / 32f, height, layer);
                line(quads, lastX, lastY, x, y, .7f, alpha(GlassPalette.ACCENT, opacity * (.055f + layer * .025f)));
                lastX = x; lastY = y;
            }
        }
        return List.copyOf(quads);
    }

    private static void glow(List<Quad> quads, float x, float y, float radiusX, float radiusY, int color, float opacity) {
        for (int band = 0; band < 8; band++) for (int i = 0; i < 24; i++) {
            double a = i * Math.PI / 12, b = (i + 1) * Math.PI / 12;
            float inner = band / 8f, outer = (band + 1) / 8f;
            int tint = alpha(color, opacity * (1 - band / 8f) * (1 - band / 8f));
            quads.add(new Quad(x + (float) Math.cos(a) * radiusX * inner, y + (float) Math.sin(a) * radiusY * inner,
                    x + (float) Math.cos(b) * radiusX * inner, y + (float) Math.sin(b) * radiusY * inner,
                    x + (float) Math.cos(b) * radiusX * outer, y + (float) Math.sin(b) * radiusY * outer,
                    x + (float) Math.cos(a) * radiusX * outer, y + (float) Math.sin(a) * radiusY * outer, tint, tint));
        }
    }

    public record Quad(float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4,
                       int topColor, int bottomColor) {}
    private static int alpha(int color, float opacity) { return ((int) ((color >>> 24) * Math.max(0, Math.min(1, opacity))) << 24) | (color & 0xffffff); }
    private static float fract(float value) { return value - (float) Math.floor(value); }
    private static float mountain(float x, float height, int layer) {
        return height * (.77f + layer * .06f) - (float) (Math.abs(Math.sin(x * 9 + layer * 1.4)) * height * .08f + Math.sin(x * 19 + layer) * height * .025f);
    }
    private static void rect(List<Quad> quads, float x1, float y1, float x2, float y2, int color) { quads.add(new Quad(x1, y2, x2, y2, x2, y1, x1, y1, color, color)); }
    private static void line(List<Quad> quads, float x1, float y1, float x2, float y2, float thickness, int color) {
        float dx = x2 - x1, dy = y2 - y1, length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < .0001f) return;
        float nx = -dy / length * thickness / 2, ny = dx / length * thickness / 2;
        quads.add(new Quad(x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, x1 - nx, y1 - ny, color, color));
    }
}
