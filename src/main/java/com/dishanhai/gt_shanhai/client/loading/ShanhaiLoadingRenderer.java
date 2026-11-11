package com.dishanhai.gt_shanhai.client.loading;

import com.dishanhai.sh_better_ui.client.glass.GlassPalette;
import com.dishanhai.sh_better_ui.client.glass.GlassBlur;
import com.dishanhai.sh_better_ui.client.glass.GuiRect;
import com.dishanhai.sh_better_ui.client.blur.GaussBlur;
import com.dishanhai.gt_shanhai.client.gui.shop.ShopTheme;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import java.util.ArrayList;
import java.util.List;
import static com.dishanhai.gt_shanhai.client.loading.ShanhaiLoadingText.text;

/** 山海：商店透明玻璃主題；全程使用原生 GUI 座標，文字不跟隨整頁縮放。 */
public final class ShanhaiLoadingRenderer {
    private static int pageOffset;
    private static boolean startupBlurAttempted, worldBlurAttempted;

    private ShanhaiLoadingRenderer() {}
    public static void scroll(int direction) { pageOffset += direction; }
    public static void resetPage() { pageOffset = 0; releaseBackdrop(); }
    public static void releaseBackdrop() { ShanhaiLoadingBackdrop.release(); }

    public static void render(GuiGraphics graphics, boolean startup, ShanhaiLoadingState.Frame frame,
                              float opacity, boolean reduceMotion, boolean redrawMenu) {
        Minecraft mc = Minecraft.getInstance();
        graphics.flush();
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting previousSorting = RenderSystem.getVertexSorting();
        var modelView = RenderSystem.getModelViewStack();
        boolean depth = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        boolean blend = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND);
        float[] previousColor = RenderSystem.getShaderColor().clone();
        modelView.pushPose();
        modelView.setIdentity();
        modelView.translate(0, 0, -11000);
        RenderSystem.applyModelViewMatrix();
        try {
            int width = mc.getWindow().getGuiScaledWidth(), height = mc.getWindow().getGuiScaledHeight();
            if (width < 1 || height < 1) return;
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, width, height, 0, 1000, 21000), VertexSorting.ORTHOGRAPHIC_Z);
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            if (redrawMenu && mc.screen != null) {
                mc.screen.renderWithTooltip(graphics, 0, 0, 0);
                graphics.flush();
            }
            var panel = ShanhaiLoadingLayout.glassPanel(width, height);
            if (!ShanhaiLoadingBackdrop.draw(width, height, startup, opacity)) {
                drawBackground(width, height, 0, opacity, true);
                warmGlass(startup);
                RenderSystem.setShaderColor(1, 1, 1, opacity);
                glassRoot(graphics, panel, opacity);
                graphics.flush();
                // 淡出首幀不快取；只保存無文字的完整底圖，進度與分頁每幀另畫。
                if (opacity >= .999f) ShanhaiLoadingBackdrop.capture(width, height, startup);
            }
            RenderSystem.setShaderColor(1, 1, 1, opacity);
            if (panel.railWidth() > 0) {
                int railX = panel.x() + panel.padding();
                ShopTheme.GLASS.box(graphics, railX, panel.contentTop() - 5, panel.railWidth(), 50,
                        startup ? GlassPalette.ACCENT : GlassPalette.BUTTON_EDGE,
                        startup ? ShopTheme.GLASS.selectBg : ShopTheme.GLASS.panelInner);
                ShopTheme.GLASS.box(graphics, railX, panel.contentTop() + 53, panel.railWidth(), 50,
                        startup ? GlassPalette.BUTTON_EDGE : GlassPalette.ACCENT,
                        startup ? ShopTheme.GLASS.panelInner : ShopTheme.GLASS.selectBg);
            }
            graphics.flush();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            ShanhaiLoadingFont atlas = startup ? ShanhaiLoadingFont.get() : null;
            List<TextRun> text = new ArrayList<>();
            add(text, text("title"), panel.x() + panel.padding(), panel.y() + 8, 18, GlassPalette.TEXT);
            add(text, text(startup ? "startup.subtitle" : "world.subtitle"),
                    panel.x() + panel.padding(), panel.y() + 31, 9, GlassPalette.TEXT_MUTED);
            String badge = text(startup ? "startup.badge" : "world.badge");
            add(text, badge, panel.x() + panel.width() - panel.padding() - width(badge, 9, atlas),
                    panel.y() + 15, 9, GlassPalette.ACCENT);
            if (panel.railWidth() > 0) {
                int railX = panel.x() + panel.padding() + 10;
                add(text, text("startup.rail"), railX, panel.contentTop() + 5, 9, GlassPalette.TEXT);
                add(text, text(startup ? "working" : "done"), railX, panel.contentTop() + 23, 9,
                        startup ? GlassPalette.ACCENT : GlassPalette.OK);
                add(text, text("world.rail"), railX, panel.contentTop() + 63, 9, GlassPalette.TEXT);
                add(text, text(startup ? "world.waiting" : "working"), railX, panel.contentTop() + 81, 9,
                        startup ? GlassPalette.TEXT_MUTED : GlassPalette.ACCENT);
            }
            List<DetailLine> details = detailLines(frame.tasks(), panel.contentWidth() - 16, atlas);
            int pages = Math.max(1, (details.size() + panel.rowCapacity() - 1) / panel.rowCapacity());
            int page = Math.floorMod(pageOffset + (int) (frame.elapsedMs() / 6000), pages);
            add(text, text(startup ? "startup.tasks" : "world.tasks"), panel.contentX() + 4, panel.contentTop() + 2, 9, GlassPalette.TEXT_HEAD);
            String pageLabel = (page + 1) + " / " + pages;
            add(text, pageLabel, panel.contentX() + panel.contentWidth() - width(pageLabel, 9, atlas) - 4,
                    panel.contentTop() + 2, 9, GlassPalette.TEXT_MUTED);
            int first = page * panel.rowCapacity();
            for (int i = first; i < Math.min(details.size(), first + panel.rowCapacity()); i++) {
                DetailLine line = details.get(i);
                add(text, line.text(), panel.contentX() + 4,
                        panel.contentTop() + 20 + (i - first) * panel.lineHeight(), 9,
                        line.heading() ? GlassPalette.TEXT : GlassPalette.TEXT_MUTED);
            }
            int barLeft = panel.x() + panel.padding(), barRight = panel.x() + panel.width() - panel.padding();
            int barY = panel.footerY() - 7;
            graphics.fill(barLeft, barY, barRight, barY + 2, alpha(ShopTheme.GLASS.barBg, opacity));
            float fraction = frame.tasks().isEmpty() ? -1 : frame.tasks().get(frame.tasks().size() - 1).fraction();
            if (fraction >= 0) graphics.fill(barLeft, barY, barLeft + Math.round((barRight - barLeft) * fraction),
                    barY + 2, alpha(GlassPalette.ACCENT, opacity));
            else {
                int span = Math.min(64, barRight - barLeft);
                int start = reduceMotion ? 0 : Math.round((float) ((Math.sin(frame.elapsedMs() / 1100d) + 1) * .5) * (barRight - barLeft - span));
                graphics.fill(barLeft + start, barY, barLeft + start + span, barY + 2, alpha(GlassPalette.ACCENT, opacity));
            }
            add(text, String.format(java.util.Locale.ROOT, "%.1f s", frame.elapsedMs() / 1000d), barLeft, panel.footerY(), 9, GlassPalette.TEXT_HEAD);
            String footer = text(pages > 1 ? "pages.hint" : "footer");
            add(text, footer, barRight - width(footer, 9, atlas), panel.footerY(), 9, GlassPalette.TEXT_MUTED);
            graphics.flush();
            drawText(graphics, text, atlas, opacity);
        } finally {
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
            RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
        }
    }

    private static void glassRoot(GuiGraphics graphics, ShanhaiLoadingLayout.GlassPanel panel, float opacity) {
        int ix = panel.contentX() - 6, iy = panel.contentTop() - 5;
        int iw = panel.contentWidth() + 12, ih = panel.contentBottom() - panel.contentTop() + 8;
        if (opacity >= .999f) {
            ShopTheme.GLASS.rootPanel(graphics, panel.x(), panel.y(), panel.width(), panel.height(), ix, iy, iw, ih);
            return;
        }
        // 後處理不吃 shaderColor，模糊強度也須同步淡出，避免結尾突然清晰。
        ShopTheme.GLASS.rootShadow(graphics, panel.x(), panel.y(), panel.width(), panel.height());
        GlassBlur.backdrop(graphics, new GuiRect(panel.x(), panel.y(), panel.width(), panel.height()), opacity);
        ShopTheme.GLASS.box(graphics, panel.x(), panel.y(), panel.width(), panel.height(), GlassPalette.ROOT_EDGE, ShopTheme.GLASS.panelBg);
        ShopTheme.GLASS.box(graphics, ix, iy, iw, ih, GlassPalette.DISPLAY_EDGE, ShopTheme.GLASS.panelInner);
    }

    /** 報告畫面亦使用同一正式背景。 */
    static void drawBackground(int width, int height, long elapsed, float opacity, boolean reduceMotion) {
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1, 1, 1, 1);
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        for (var quad : ShanhaiLoadingScene.frame(width, height, elapsed, opacity, reduceMotion)) {
            buffer.vertex(quad.x1(), quad.y1(), 0).color(quad.bottomColor()).endVertex();
            buffer.vertex(quad.x2(), quad.y2(), 0).color(quad.bottomColor()).endVertex();
            buffer.vertex(quad.x3(), quad.y3(), 0).color(quad.topColor()).endVertex();
            buffer.vertex(quad.x4(), quad.y4(), 0).color(quad.topColor()).endVertex();
        }
        BufferUploader.drawWithShader(buffer.end());
    }

    static void warmGlass(boolean startup) {
        if (GaussBlur.isReady()) return;
        if (startup ? startupBlurAttempted : worldBlurAttempted) return;
        if (startup) startupBlurAttempted = true; else worldBlurAttempted = true;
        GaussBlur.warmUp();
    }

    private static void drawText(GuiGraphics graphics, List<TextRun> text, ShanhaiLoadingFont atlas, float opacity) {
        if (atlas != null) {
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, atlas.texture);
            BufferBuilder buffer = Tesselator.getInstance().getBuilder();
            buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (var run : text) atlas.append(buffer, run.text(), run.x(), run.y(), run.size(), alpha(run.color(), opacity));
            BufferUploader.drawWithShader(buffer.end());
            return;
        }
        for (var run : text) {
            graphics.pose().pushPose();
            graphics.pose().translate(run.x(), run.y(), 0);
            int scale = run.size() >= 18 ? 2 : 1;
            graphics.pose().scale(scale, scale, 1);
            graphics.drawString(Minecraft.getInstance().font, run.text(), 0, 0, alpha(run.color(), opacity), false);
            graphics.pose().popPose();
        }
        graphics.flush();
    }

    private static List<DetailLine> detailLines(List<ShanhaiLoadingState.Task> tasks, int maxWidth, ShanhaiLoadingFont atlas) {
        List<DetailLine> result = new ArrayList<>();
        for (var task : tasks) {
            String counts = task.done() ? "  " + text("done") + (task.total() >= 0 ? " " + task.current() : "") : task.total() > 0
                    ? "  " + task.current() + " / " + task.total() + "  " + (int) (task.fraction() * 100) + "%" : "  ...";
            appendLines(result, task.label() + counts, maxWidth, true, atlas);
            if (!task.detail().isBlank()) appendLines(result, task.detail(), maxWidth, false, atlas);
        }
        if (result.isEmpty()) result.add(new DetailLine(text("preparing"), true));
        return result;
    }

    private static void appendLines(List<DetailLine> result, String value, int maxWidth, boolean heading, ShanhaiLoadingFont atlas) {
        for (String line : ShanhaiLoadingLayout.wrap(ShopTheme.GLASS.label(value), maxWidth, s -> width(s, 9, atlas)))
            result.add(new DetailLine(line, heading));
    }

    private static int width(String value, int size, ShanhaiLoadingFont atlas) {
        return atlas == null ? Minecraft.getInstance().font.width(value) * (size >= 18 ? 2 : 1) : (int) Math.ceil(atlas.width(value, size));
    }
    private static void add(List<TextRun> text, String value, int x, int y, int size, int color) { text.add(new TextRun(value, x, y, size, color)); }
    private static int alpha(int color, float opacity) { return ((int) ((color >>> 24) * Math.max(0, Math.min(1, opacity))) << 24) | (color & 0xffffff); }
    private record TextRun(String text, int x, int y, int size, int color) {}
    private record DetailLine(String text, boolean heading) {}
}
