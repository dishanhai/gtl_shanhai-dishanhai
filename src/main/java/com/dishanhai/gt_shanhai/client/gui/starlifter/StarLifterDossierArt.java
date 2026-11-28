package com.dishanhai.gt_shanhai.client.gui.starlifter;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterGeometry;
import com.dishanhai.sh_better_ui.client.gui.display.BuiltInSvgIcons;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.parser.resources.ResourcePolicy;
import com.github.weisj.jsvg.view.ViewBox;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4f;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.io.InputStream;

import static com.dishanhai.gt_shanhai.client.gui.starlifter.StarLifterDossier.*;

final class StarLifterDossierArt extends SimplePreparableReloadListener<NativeImage> {
    private static final ResourceLocation SOURCE = new ResourceLocation("gt_shanhai", "textures/gui/star_lifter/dossier.svg");
    private static final ResourceLocation ATLAS = new ResourceLocation("gt_shanhai", "star_lifter/dossier_atlas");
    private static final int SIDE = 1536;
    private static final int CELL = SIDE / 4;
    private static final int CONTROL_TEXELS = 64;
    private static final int ATLAS_HEIGHT = SIDE + CONTROL_TEXELS;
    private static final double TAU = 2 * Math.PI;
    private static final double[] PERIODS = {19, 26, 31, 38};
    private static final float BORDER_INSET = 2;
    private boolean ready;
    private BufferBuilder batch;
    private Matrix4f matrix;
    private int vertices;

    boolean ready() {
        return ready;
    }

    @Override
    protected NativeImage prepare(ResourceManager resources, ProfilerFiller profiler) {
        try (var stream = resources.open(SOURCE)) {
            var image = rasterize(stream);
            var pixels = new NativeImage(SIDE, ATLAS_HEIGHT, false);
            int[] colors = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            for (int y = 0; y < ATLAS_HEIGHT; y++) {
                for (int x = 0; x < SIDE; x++) {
                    int c = colors[y * SIDE + x];
                    pixels.setPixelRGBA(x, y, c & 0xff00ff00 | (c & 255) << 16 | c >>> 16 & 255);
                }
            }
            return pixels;
        } catch (IOException | RuntimeException failure) {
            GTDishanhaiMod.LOGGER.warn("Failed to load the Star Lifter tooltip SVG", failure);
            return null;
        }
    }

    static BufferedImage rasterize(InputStream stream) throws IOException {
        var context = LoaderContext.builder().externalResourcePolicy(ResourcePolicy.DENY_ALL).build();
        var svg = new SVGLoader().load(stream, null, context);
        if (svg == null) throw new IOException(SOURCE.toString());
        var image = new BufferedImage(SIDE, ATLAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            svg.render(null, graphics, new ViewBox(0, 0, SIDE, SIDE));
            for (int i = 0; i < CONTROL_SVGS.size(); i++) {
                String icon = CONTROL_SVGS.get(i);
                // Older SH-Better-UI builds ship a general mouse SVG instead of the wheel glyph.
                if (icon.equals("mouse_wheel") && !BuiltInSvgIcons.hasSource(icon)) icon = "mouse";
                var control = BuiltInSvgIcons.render(icon, CONTROL_TEXELS);
                graphics.drawImage(control, i * CONTROL_TEXELS, SIDE, null);
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    @Override
    protected void apply(NativeImage image, ResourceManager resources, ProfilerFiller profiler) {
        var textures = Minecraft.getInstance().getTextureManager();
        if (ready) textures.release(ATLAS);
        ready = image != null;
        if (ready) {
            var texture = new DynamicTexture(image);
            texture.setFilter(true, false);
            textures.register(ATLAS, texture);
        }
    }

    void begin(GuiGraphics gui) {
        gui.flush();
        matrix = gui.pose().last().pose();
        batch = Tesselator.getInstance().getBuilder();
        batch.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);
        vertices = 0;
    }

    int end() {
        var shader = RenderSystem.getShader();
        int texture = RenderSystem.getShaderTexture(0);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorTexShader);
        RenderSystem.setShaderTexture(0, ATLAS);
        try {
            var built = batch.endOrDiscardIfEmpty();
            if (built != null) BufferUploader.drawWithShader(built);
        } finally {
            RenderSystem.setShaderTexture(0, texture);
            RenderSystem.setShader(() -> shader);
            RenderSystem.disableBlend();
            batch = null;
            matrix = null;
        }
        return vertices;
    }

    void frame(int width, int height, float alpha, double time, int quality, float project, int border, boolean contrast) {
        sprite(0, .5f, .5f, width - 1, height - 1, contrast ? 0xb5bac9 : 0xffffff, alpha);
        float right = width - BORDER_INSET, bottom = height - BORDER_INSET;
        float breath = (float) Math.sin(time * TAU / 6.7);
        float pulse = quality > 0 ? .91f + .09f * breath : .93f;
        sprite(RULE, BORDER_INSET, BORDER_INSET, right - BORDER_INSET, 1, border, alpha * .90f * pulse);
        sprite(RULE, BORDER_INSET, bottom - 1, right - BORDER_INSET, 1, border, alpha * .69f * pulse);
        rotated(RULE, BORDER_INSET, height * .5f, height - 4, 1, 0, 1, border, alpha * .67f * pulse);
        rotated(RULE, right, height * .5f, height - 4, 1, 0, 1, border, alpha * .67f * pulse);
        sprite(CORNER, 1, 1, 18, 18, GOLD, alpha * .74f);
        sprite(CORNER, width - 1, 1, -18, 18, VIOLET, alpha * .67f);
        sprite(CORNER, 1, height - 1, 18, -18, VIOLET, alpha * .63f);
        sprite(CORNER, width - 1, height - 1, -18, -18, GOLD, alpha * .70f);

        float cx = width - 45, cy = 25;
        float size = 69 * (1 + .026f * breath);
        // All high-luminance pixels stay in the reserved top-right header area.
        if (width >= 150) {
            dot(cx, cy, 64, ORANGE, alpha * (.20f + .09f * project));
            double spin = time * TAU / 35;
            rotated(ART, cx, cy, size * .61f, size * .61f, (float) Math.cos(spin),
                    (float) Math.sin(spin), 0xffffff, alpha * (.72f + .12f * project + .045f * breath));
            for (int ring = 0; ring < StarLifterGeometry.RINGS.size(); ring++) {
                drawRing(ring, cx, cy, time, quality, alpha);
            }
        }
        int dust = quality == 2 ? 8 : quality == 1 ? 4 : 0;
        for (int i = 0; i < dust; i++) {
            double age = fract(time / (7 + i * .77) + i * .618);
            float x = 13 + (width - 26) * (float) fract(i * .381 + .08) + (float) age * 5;
            float y = 39 + Math.max(0, height - 53) * (float) fract(i * .271 + .13);
            dot(x, y, 2.4f, (i & 1) == 0 ? VIOLET : BLUE, alpha * .20f * envelope(age));
        }
        if (quality > 0) {
            float distance = (float) fract(time / 7.4) * (2 * (right - 8) + 2 * (bottom - 8));
            float horizontal = right - 8, vertical = bottom - 8;
            if (distance < horizontal) {
                sprite(RULE, 4 + distance - 10, 2, 22, 1, GOLD, alpha * .55f);
            } else if ((distance -= horizontal) < vertical) {
                rotated(RULE, right, 4 + distance, 22, 1, 0, 1, GOLD, alpha * .55f);
            } else if ((distance -= vertical) < horizontal) {
                sprite(RULE, right - 2 - distance - 10, bottom - 1, 22, 1, GOLD, alpha * .55f);
            } else {
                rotated(RULE, 2, bottom - 2 - (distance - horizontal), 22, 1, 0, 1, GOLD, alpha * .55f);
            }
        }
    }

    private void drawRing(int index, float cx, float cy, double time, int quality, float alpha) {
        var ring = StarLifterGeometry.RINGS.get(index);
        float turn = (float) (time * TAU / PERIODS[index]);
        double inclination = Math.toRadians(ring.heading() + ring.axisAzimuth() * .56 + index * 9 - 13)
                + Math.sin(turn) * .24;
        float cosine = (float) Math.cos(inclination), sine = (float) Math.sin(inclination);
        float width = 34 + (float) (ring.radius() * 23);
        float height = width * (.52f + .082f * (float) Math.sin(turn + index * .84));
        rotated(RING, cx, cy, width, height, cosine, sine, index == 3 ? VIOLET : GOLD, alpha * .72f);
        int count = quality == 2 ? 3 : quality == 1 ? 1 : 0;
        double end = 3.3 + index * .48;
        for (int i = 0; i < count; i++) {
            double age = fract(time / (4.2 + .51 * index) + i * .109 + index * .217);
            double t = Math.min(age / .27, 1);
            double converge = Math.max(0, (age - .88) / .12);
            double angle = end - 3.8 * (1 - age) + .13 * Math.sin(TAU * t);
            float orbitX = width * .4297f * (float) Math.cos(angle);
            float orbitY = height * .2266f * (float) Math.sin(angle);
            float spread = .20f + .80f * (float) (t * t * (3 - 2 * t));
            float x = cx + (orbitX * cosine - orbitY * sine) * spread * (1 - .07f * (float) converge);
            float y = cy + (orbitX * sine + orbitY * cosine) * spread * (1 - .07f * (float) converge);
            dot(x, y, i == 0 ? 5 : 3.2f, i == 0 ? CORE : GOLD, alpha * .84f * envelope(age));
        }
        float x = width * .4297f * (float) Math.cos(end - .12);
        float y = height * .2266f * (float) Math.sin(end - .12);
        float node = .44f + .26f * (float) Math.sin(time * TAU / (4.2 + .51 * index) + index * 1.36);
        dot(cx + x * cosine - y * sine, cy + x * sine + y * cosine, 4.4f, GOLD, alpha * node);
    }

    void dot(float x, float y, float size, int rgb, float alpha) {
        sprite(GLOW, x - size * .5f, y - size * .5f, size, size, rgb, alpha);
    }

    void sprite(int tile, float x, float y, float w, float h, int rgb, float a) {
        rotated(tile, x + w * .5f, y + h * .5f, w, h, 1, 0, rgb, a);
    }

    private void rotated(int tile, float cx, float cy, float w, float h, float c, float s, int rgb, float a) {
        if (a <= .005f || w == 0 || h == 0) return;
        // Half-texel inset prevents neighboring SVG cells leaking through linear sampling.
        boolean control = tile >= CONTROL_TILE;
        int texels = control ? CONTROL_TEXELS : CELL;
        int atlasX = control ? (tile - CONTROL_TILE) * CONTROL_TEXELS : (tile % 4) * CELL;
        int atlasY = control ? SIDE : (tile / 4) * CELL;
        float u = (atlasX + .5f) / SIDE, v = (atlasY + .5f) / ATLAS_HEIGHT;
        float du = (texels - 1f) / SIDE, dv = (texels - 1f) / ATLAS_HEIGHT;
        float wx = w * .5f * c, wy = w * .5f * s, hx = -h * .5f * s, hy = h * .5f * c;
        int alpha = Math.round(a * 255);
        if (w * h >= 0) {
            vertex(cx - wx - hx, cy - wy - hy, u, v, rgb, alpha);
            vertex(cx - wx + hx, cy - wy + hy, u, v + dv, rgb, alpha);
            vertex(cx + wx + hx, cy + wy + hy, u + du, v + dv, rgb, alpha);
            vertex(cx + wx - hx, cy + wy - hy, u + du, v, rgb, alpha);
        } else {
            vertex(cx - wx - hx, cy - wy - hy, u, v, rgb, alpha);
            vertex(cx + wx - hx, cy + wy - hy, u + du, v, rgb, alpha);
            vertex(cx + wx + hx, cy + wy + hy, u + du, v + dv, rgb, alpha);
            vertex(cx - wx + hx, cy - wy + hy, u, v + dv, rgb, alpha);
        }
        vertices += 4;
    }

    private void vertex(float x, float y, float u, float v, int rgb, int alpha) {
        batch.vertex(matrix, x, y, 0).color(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, alpha).uv(u, v).endVertex();
    }

    private static double fract(double x) { return x - Math.floor(x); }
    private static float envelope(double t) { return (float) Math.sin(Math.PI * t); }
}
