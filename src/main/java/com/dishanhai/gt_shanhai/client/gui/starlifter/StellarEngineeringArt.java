package com.dishanhai.gt_shanhai.client.gui.starlifter;

import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterHudTarget.Icon;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

import static com.dishanhai.gt_shanhai.client.gui.starlifter.StellarHudStyle.color;

/** GUI-space vector strokes with feathered edges. All paths share Jade's GUI vertex buffer. */
public final class StellarEngineeringArt {
    private static final int STEPS = 48;
    private static final float[] X = new float[STEPS + 1], Y = new float[STEPS + 1];
    private static final float[] HEX = {-5, -8.7f, 5, -8.7f, 10, 0, 5, 8.7f, -5, 8.7f, -10, 0, -5, -8.7f};
    private static final float[] CHAMBER = {-9, -5, 0, -10, 9, -5, 9, 5, 0, 10, -9, 5, -9, -5};
    private static final float[] PORT = {-9, -9, 9, -9, 9, 9, -9, 9, -9, -9};
    private static final float[][] OUTLINES = new float[Icon.values().length][];

    static {
        for (int i = 0; i <= STEPS; i++) {
            double angle = i * (2 * Math.PI / STEPS);
            X[i] = (float) Math.cos(angle);
            Y[i] = (float) Math.sin(angle);
        }
        for (var icon : Icon.values()) {
            OUTLINES[icon.ordinal()] = switch (icon) {
                case CORE -> ellipse(12, 5.5f, -.47f);
                case RING -> ellipse(10, 8, -.40f);
                case CHAMBER -> outline(CHAMBER);
                case PORT -> outline(PORT);
                case FIELD -> outline(HEX);
                case FLOW -> outline(new float[] {-10, -5, 10, -5, 10, 0, -10, 0, -10, 5, 10, 5});
            };
        }
    }

    private VertexConsumer vertices;
    private Matrix4f matrix;

    void bind(GuiGraphics graphics) {
        matrix = graphics.pose().last().pose();
        vertices = graphics.bufferSource().getBuffer(RenderType.gui());
    }

    public static void brand(GuiGraphics graphics, CuhiIdentity.Scale size, float x, float y, int argb) {
        var art = new StellarEngineeringArt();
        art.bind(graphics);
        art.emblem(x, y, switch (size) {
            case FULL -> 1.8f;
            case STANDARD -> 1.3f;
            case COMPACT -> .85f;
            case MICRO -> .55f;
        }, 1, argb);
    }

    void emblem(float x, float y, float scale, float reveal, int argb) {
        path(x, y, HEX, scale, reveal, argb);
        arc(x, y, 7 * scale, 3 * scale, -.52f, 0, reveal, 24, argb);
        diamond(x, y, 2.2f * scale * reveal, argb);
    }

    void morph(Icon icon, float x, float y, float fraction, int detail, int argb) {
        var brand = OUTLINES[Icon.FIELD.ordinal()];
        var device = OUTLINES[icon.ordinal()];
        int i = 0;
        for (int segment = 1; segment <= detail; segment++) {
            int end = 2 * Math.round(segment * STEPS / (float) detail);
            line(x + mix(brand[i], device[i], fraction), y + mix(brand[i + 1], device[i + 1], fraction),
                    x + mix(brand[end], device[end], fraction), y + mix(brand[end + 1], device[end + 1], fraction), argb);
            i = end;
        }
        arc(x, y, 7 + fraction * 3, 3 + fraction * 2, -.52f, 0, 1, 16, dim(argb, 1 - fraction));
        diamond(x, y, 2.2f, dim(argb, 1 - fraction));
    }

    void scan(float x, float y, float fraction, boolean verified, int argb) {
        float sweep = y - 9 + 18 * fraction;
        line(x - 11, sweep, x + 11, sweep, dim(argb, .68f));
        for (int i = 0; i < 4; i++) {
            float dx = (i % 2 == 0 ? -1 : 1) * 11;
            float dy = (i < 2 ? -1 : 1) * 9;
            diamond(x + dx, y + dy, verified && fraction >= (i + 1) * .2f ? 1.45f : .75f, dim(argb, .76f));
        }
    }

    private static float[] outline(float[] points) {
        float[] samples = new float[(STEPS + 1) * 2];
        int edges = points.length / 2 - 1;
        for (int i = 0; i <= STEPS; i++) {
            float cursor = i * edges / (float) STEPS;
            int edge = Math.min((int) cursor, edges - 1);
            int index = edge * 2;
            samples[2 * i] = mix(points[index], points[index + 2], cursor - edge);
            samples[2 * i + 1] = mix(points[index + 1], points[index + 3], cursor - edge);
        }
        return samples;
    }

    private static float[] ellipse(float rx, float ry, float angle) {
        float[] samples = new float[(STEPS + 1) * 2];
        double c = Math.cos(angle), s = Math.sin(angle);
        for (int i = 0; i <= STEPS; i++) {
            samples[2 * i] = (float) (X[i] * rx * c - Y[i] * ry * s);
            samples[2 * i + 1] = (float) (X[i] * rx * s + Y[i] * ry * c);
        }
        return samples;
    }

    private static float mix(float a, float b, float t) { return a + (b - a) * t; }

    void icon(Icon icon, float x, float y, float reveal, double phase, float strength, int argb, int detail) {
        switch (icon) {
            case CORE -> {
                arc(x, y, 12, 5.5f, -.47f, 0, reveal, detail, argb);
                arc(x, y, 12, 5.5f, .55f, 0, reveal, detail, argb);
                arc(x, y, 9.5f, 9.5f, 0, .15f, reveal * .68f, detail, dim(argb, .52f));
                node(x, y, 12, 5.5f, -.47f, phase, 1.4f, argb);
                if (detail > 16) node(x, y, 12, 5.5f, .55f, -phase + .25, 1.1f, argb);
                diamond(x, y, 2.6f + strength, argb);
            }
            case CHAMBER -> {
                path(x, y, CHAMBER, 1, reveal, argb);
                line(x - 9, y - 5, x, y, argb);
                line(x, y, x + 9, y - 5, argb);
                line(x, y, x, y + 10, argb);
                float scan = (float) Math.sin(phase * Math.PI * 2) * 4;
                line(x - 6, y + scan, x + 6, y + scan, dim(argb, .6f));
                diamond(x, y, 1.9f, argb);
            }
            case PORT -> {
                path(x, y, PORT, 1, reveal, argb);
                path(x, y, PORT, .5f, reveal, dim(argb, .64f));
                for (int turn = 0; turn < 4; turn++) {
                    int i = 12 * turn;
                    diamond(x + X[i] * 9, y + Y[i] * 9, 1.15f, argb);
                }
            }
            case RING -> {
                arc(x, y, 10, 8, -.40f, .03f, reveal * .94f, detail, argb);
                arc(x, y, 7, 5.8f, -.40f, .52f, reveal * .90f, detail, dim(argb, .62f));
                node(x, y, 10, 8, -.40f, phase, 1.4f, argb);
            }
            case FLOW -> {
                for (int i = -1; i <= 1; i++) {
                    float row = y + i * 5;
                    line(x - 10, row, x + (reveal * 20 - 10), row, dim(argb, i == 0 ? 1 : .56f));
                    diamond(x - 10 + (float) ((phase + (i + 1) * .24) % 1) * 20, row, 1.25f, argb);
                }
            }
            case FIELD -> {
                path(x, y, HEX, 1, reveal, argb);
                arc(x, y, 7.5f, 4, .75f, 0, reveal, detail, argb);
                arc(x, y, 7.5f, 4, -.75f, 0, reveal, detail, dim(argb, .68f));
                node(x, y, 7.5f, 4, .75f, phase, 1.25f, argb);
                diamond(x, y, 2 + strength, argb);
            }
        }
    }

    private void path(float x, float y, float[] path, float scale, float reveal, int argb) {
        float edges = reveal * (path.length / 2 - 1);
        for (int i = 0; i < edges; i++) {
            int p = i * 2;
            float fraction = Math.min(1, edges - i);
            line(x + path[p] * scale, y + path[p + 1] * scale,
                    x + (path[p] + (path[p + 2] - path[p]) * fraction) * scale,
                    y + (path[p + 1] + (path[p + 3] - path[p + 1]) * fraction) * scale, argb);
        }
    }

    private void arc(float x, float y, float rx, float ry, float angle, float offset, float sweep, int steps, int argb) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        float remaining = steps * sweep;
        int start = Math.round(offset * STEPS);
        int from = start % STEPS;
        for (int i = 1; i <= Math.ceil(remaining); i++) {
            int to = (start + Math.round(i * STEPS / (float) steps)) % STEPS;
            float a = X[from] * rx, b = Y[from] * ry;
            float t = Math.min(1, remaining - i + 1);
            float d = a + (X[to] * rx - a) * t, e = b + (Y[to] * ry - b) * t;
            line(x + a * c - b * s, y + a * s + b * c, x + d * c - e * s, y + d * s + e * c, argb);
            from = to;
        }
    }

    private void node(float x, float y, float rx, float ry, float angle, double phase, float radius, int argb) {
        float a = (float) Math.cos(phase * 2 * Math.PI) * rx;
        float b = (float) Math.sin(phase * 2 * Math.PI) * ry;
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        diamond(x + a * c - b * s, y + a * s + b * c, radius, argb);
    }

    void diamond(float x, float y, float radius, int argb) {
        quad(x - radius, y, x, y + radius, x + radius, y, x, y - radius, argb, argb);
    }

    void rect(float left, float top, float right, float bottom, int argb) {
        if (right > left && bottom > top) quad(left, top, left, bottom, right, bottom, right, top, argb, argb);
    }

    void line(float x, float y, float endX, float endY, int argb) {
        float dx = endX - x, dy = endY - y;
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length < .01) return;
        float nx = (float) (-dy / length * .85), ny = (float) (dx / length * .85);
        int transparent = argb & 0xffffff;
        quad(x - nx, y - ny, x, y, endX, endY, endX - nx, endY - ny, transparent, argb);
        quad(x, y, x + nx, y + ny, endX + nx, endY + ny, endX, endY, argb, transparent);
    }

    private static int dim(int argb, float opacity) {
        return color(argb, (argb >>> 24) / 255f * opacity);
    }

    private void quad(float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4, int a, int b) {
        vertex(x1, y1, a);
        vertex(x2, y2, b);
        vertex(x3, y3, b);
        vertex(x4, y4, a);
    }

    private void vertex(float x, float y, int argb) {
        vertices.vertex(matrix, x, y, 0).color(argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24).endVertex();
    }
}
