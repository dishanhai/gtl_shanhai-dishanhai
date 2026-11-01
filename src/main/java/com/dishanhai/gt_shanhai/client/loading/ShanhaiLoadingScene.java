package com.dishanhai.gt_shanhai.client.loading;

import java.util.ArrayList;
import java.util.List;

/** 山海：遊戲渲染、JVM 測試與離線預覽共用的正式載入動畫幾何。 */
public final class ShanhaiLoadingScene {
    private static final int CYAN = 0xff80dfda, GOLD = 0xffd6b878;

    private ShanhaiLoadingScene() {}

    public static List<Quad> frame(float width, float height, long elapsedMs, float opacity, boolean reduceMotion) {
        List<Quad> quads = new ArrayList<>(580);
        float time = reduceMotion ? 0 : elapsedMs / 1000f;
        float centerX = width / 2, centerY = height * .29f;
        quads.add(new Quad(0, height, width, height, width, 0, 0, 0,
                alpha(0xff070e19, opacity), alpha(0xff101e2b, opacity)));
        for (int i = 0; i < 72; i++) {
            float x = fract(i * .618034f + .13f) * width, y = fract(i * .414214f + .27f) * height;
            float twinkle = .2f + .45f * (float) (.5 + .5 * Math.sin(time * .7 + i * 2.3));
            float size = i % 9 == 0 ? 1.5f : .7f;
            rect(quads, x, y, x + size, y + size, alpha(i % 4 == 0 ? GOLD : CYAN, opacity * twinkle));
        }
        for (int layer = 0; layer < 3; layer++) {
            float lastX = 0, lastY = mountain(0, height, layer);
            for (int i = 1; i <= 64; i++) {
                float x = width * i / 64, y = mountain(i / 64f, height, layer);
                line(quads, lastX, lastY, x, y, 1f, alpha(CYAN, opacity * (.055f + layer * .035f)));
                lastX = x; lastY = y;
            }
        }
        arc(quads, centerX, centerY, 60, -time * .13f, (float) (Math.PI * 2), 96, .65f, alpha(CYAN, opacity * .18f));
        arc(quads, centerX, centerY, 49, time * .18f, (float) (Math.PI * 2), 96, .8f, alpha(GOLD, opacity * .2f));
        for (int arc = 0; arc < 3; arc++) {
            arc(quads, centerX, centerY, 60, time * .6f + arc * 2.0944f, .48f, 18, 1.7f, alpha(CYAN, opacity));
            arc(quads, centerX, centerY, 49, -time * .4f + arc * 2.0944f, .25f, 10, 1.4f, alpha(GOLD, opacity * .9f));
        }
        for (int i = 0; i < 12; i++) {
            double angle = i * Math.PI / 6;
            float inner = i % 3 == 0 ? 65 : 67;
            line(quads, centerX + (float) Math.cos(angle) * inner, centerY + (float) Math.sin(angle) * inner,
                    centerX + (float) Math.cos(angle) * 70, centerY + (float) Math.sin(angle) * 70,
                    1, alpha(GOLD, opacity * .6f));
        }
        float panelWidth = Math.min(530, width - 64), left = (width - panelWidth) / 2, panelTop = height * .54f;
        rect(quads, left, panelTop, left + panelWidth, height - 48, alpha(0xff0a1522, opacity * .8f));
        line(quads, left, panelTop, left + panelWidth, panelTop, .7f, alpha(CYAN, opacity * .4f));
        return List.copyOf(quads);
    }

    public record Quad(float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4,
                       int topColor, int bottomColor) {}

    private static int alpha(int color, float opacity) { return ((int) ((color >>> 24) * Math.max(0, Math.min(1, opacity))) << 24) | (color & 0xffffff); }
    private static float fract(float value) { return value - (float) Math.floor(value); }
    private static float mountain(float x, float height, int layer) {
        return height * (.77f + layer * .045f) - (float) (Math.abs(Math.sin(x * 11 + layer * 2.1)) * 24
                + Math.sin(x * 27 + layer) * 9 + Math.sin(x * 5) * 16);
    }
    private static void rect(List<Quad> quads, float x1, float y1, float x2, float y2, int color) {
        quads.add(new Quad(x1, y2, x2, y2, x2, y1, x1, y1, color, color));
    }
    private static void line(List<Quad> quads, float x1, float y1, float x2, float y2, float thickness, int color) {
        float dx = x2 - x1, dy = y2 - y1, length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < .0001f) return;
        float nx = -dy / length * thickness / 2, ny = dx / length * thickness / 2;
        quads.add(new Quad(x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, x1 - nx, y1 - ny, color, color));
    }
    private static void arc(List<Quad> quads, float x, float y, float radius, float start, float span, int count, float thickness, int color) {
        for (int i = 0; i < count; i++) {
            double a = start + span * i / count, b = start + span * (i + 1) / count;
            line(quads, x + (float) Math.cos(a) * radius, y + (float) Math.sin(a) * radius,
                    x + (float) Math.cos(b) * radius, y + (float) Math.sin(b) * radius, thickness, color);
        }
    }
}
