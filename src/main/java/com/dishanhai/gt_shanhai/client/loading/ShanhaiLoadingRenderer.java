package com.dishanhai.gt_shanhai.client.loading;

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

/** 山海：固定幾何預算的雙階段星空／山脈／星環，啟動不依賴遊戲字型。 */
public final class ShanhaiLoadingRenderer {
    private static final int CYAN = 0xff80dfda;
    private static final int GOLD = 0xffd6b878;
    private static final int TEXT = 0xffdbe8ed;
    private static int pageOffset;

    private ShanhaiLoadingRenderer() {}

    public static void scroll(int direction) { pageOffset += direction; }
    public static void resetPage() { pageOffset = 0; }

    public static void render(GuiGraphics graphics, boolean startup, ShanhaiLoadingState.Frame frame,
                              float opacity, boolean reduceMotion, boolean redrawMenu) {
        Minecraft mc = Minecraft.getInstance();
        graphics.flush();
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting previousSorting = RenderSystem.getVertexSorting();
        var modelView = RenderSystem.getModelViewStack();
        boolean depth = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        boolean blend = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND);
        modelView.pushPose();
        modelView.setIdentity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1, 1, 1, 1);
        try {
            int guiWidth = mc.getWindow().getGuiScaledWidth(), guiHeight = mc.getWindow().getGuiScaledHeight();
            if (guiWidth < 1 || guiHeight < 1) return;
            if (redrawMenu && mc.screen != null) {
                RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, guiWidth, guiHeight, 0, 1000, 21000), VertexSorting.ORTHOGRAPHIC_Z);
                modelView.translate(0, 0, -11000);
                RenderSystem.applyModelViewMatrix();
                mc.screen.renderWithTooltip(graphics, 0, 0, 0);
                graphics.flush();
                modelView.setIdentity();
                RenderSystem.applyModelViewMatrix();
                RenderSystem.disableDepthTest();
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
            }
            float scale = Math.min(guiWidth / 640f, guiHeight / 360f);
            float width = guiWidth / scale, height = guiHeight / scale;
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, width, height, 0, -1000, 1000), VertexSorting.ORTHOGRAPHIC_Z);
            float time = reduceMotion ? 0 : frame.elapsedMs() / 1000f;
            float centerX = width / 2, centerY = height * .29f;
            var atlas = ShanhaiLoadingFont.get();
            BufferBuilder buffer = Tesselator.getInstance().getBuilder();
            buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            for (var quad : ShanhaiLoadingScene.frame(width, height, frame.elapsedMs(), opacity, reduceMotion)) {
                buffer.vertex(quad.x1(), quad.y1(), 0).color(quad.bottomColor()).endVertex();
                buffer.vertex(quad.x2(), quad.y2(), 0).color(quad.bottomColor()).endVertex();
                buffer.vertex(quad.x3(), quad.y3(), 0).color(quad.topColor()).endVertex();
                buffer.vertex(quad.x4(), quad.y4(), 0).color(quad.topColor()).endVertex();
            }
            float panelWidth = Math.min(530, width - 64), left = (width - panelWidth) / 2;
            float panelTop = height * .54f, panelBottom = height - 48;
            List<TextRun> labels = new ArrayList<>();
            center(labels, atlas, "山 海", centerX, centerY - 23, 27, alpha(TEXT, opacity));
            center(labels, atlas, "GT  SHANHAI", centerX, centerY + 16, 9, alpha(GOLD, opacity));
            center(labels, atlas, startup ? "01  /  模組初始化" : "02  /  進入世界", centerX, 20, 11, alpha(CYAN, opacity));
            center(labels, atlas, startup ? "INITIALIZING THE HORIZON" : "BUILDING YOUR WORLD", centerX, centerY + 75, 9, alpha(GOLD, opacity * .8f));

            List<DetailLine> details = detailLines(frame.tasks(), (int) panelWidth - 32, startup, atlas);
            int capacity = Math.max(1, (int) ((panelBottom - panelTop - 30) / 17));
            int pages = Math.max(1, (details.size() + capacity - 1) / capacity);
            int page = Math.floorMod(pageOffset + (int) (frame.elapsedMs() / 6000), pages);
            int first = page * capacity;
            for (int i = first; i < Math.min(details.size(), first + capacity); i++) {
                var detail = details.get(i);
                labels.add(new TextRun(detail.text(), left + 16, panelTop + 12 + (i - first) * 17,
                        11, alpha(detail.heading() ? TEXT : 0xff829aaa, opacity), !startup));
            }
            float barY = panelBottom + 9;
            rect(buffer, left, barY, left + panelWidth, barY + 2, alpha(0xff233b49, opacity));
            float fraction = frame.tasks().isEmpty() ? -1 : frame.tasks().get(frame.tasks().size() - 1).fraction();
            if (fraction >= 0) rect(buffer, left, barY, left + panelWidth * fraction, barY + 2, alpha(CYAN, opacity));
            else {
                float start = reduceMotion ? 0 : (float) ((Math.sin(time * 1.3) + 1) * .5) * (panelWidth - 70);
                rect(buffer, left + start, barY, left + start + 70, barY + 2, alpha(CYAN, opacity * .7f));
            }
            labels.add(new TextRun(String.format(java.util.Locale.ROOT, "%.1f s", frame.elapsedMs() / 1000d),
                    left, panelBottom + 20, 9, alpha(GOLD, opacity), false));
            String footer = pages > 1 ? "DETAILS " + (page + 1) + " / " + pages + "   AUTO / SCROLL" : "山海恒長在";
            labels.add(new TextRun(footer, left + panelWidth - atlas.width(footer, 9), panelBottom + 20,
                    9, alpha(0xff829aaa, opacity), false));
            BufferUploader.drawWithShader(buffer.end());

            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, atlas.texture);
            buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (var label : labels) if (!label.nativeFont()) atlas.append(buffer, label.text(), label.x(), label.y(), label.size(), label.color());
            BufferUploader.drawWithShader(buffer.end());
            for (var label : labels) {
                if (!label.nativeFont()) continue;
                graphics.pose().pushPose();
                graphics.pose().translate(label.x(), label.y(), 0);
                float fontScale = label.size() / 9;
                graphics.pose().scale(fontScale, fontScale, 1);
                graphics.drawString(mc.font, label.text(), 0, 0, label.color(), false);
                graphics.pose().popPose();
            }
            graphics.flush();
        } finally {
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
        }
    }

    private static List<DetailLine> detailLines(List<ShanhaiLoadingState.Task> tasks, int maxWidth,
                                               boolean startup, ShanhaiLoadingFont atlas) {
        List<DetailLine> result = new ArrayList<>();
        for (var task : tasks) {
            String counts = task.done() ? "  [DONE]" + (task.total() >= 0 ? " " + task.current() : "") : task.total() > 0
                    ? "  " + task.current() + " / " + task.total() + "  " + (int) (task.fraction() * 100) + "%" : "  ...";
            appendLines(result, task.label() + counts, maxWidth, true, startup, atlas);
            if (!task.detail().isBlank()) appendLines(result, task.detail(), maxWidth, false, startup, atlas);
        }
        if (result.isEmpty()) result.add(new DetailLine("正在準備 ...", true));
        return result;
    }

    private static void appendLines(List<DetailLine> result, String text, int maxWidth, boolean heading,
                                    boolean startup, ShanhaiLoadingFont atlas) {
        for (String line : ShanhaiLoadingLayout.wrap(text, maxWidth, s -> (int) Math.ceil(startup
                ? atlas.width(s, 11) : Minecraft.getInstance().font.width(s) * 11 / 9f))) {
            result.add(new DetailLine(line, heading));
        }
    }

    private static void center(List<TextRun> text, ShanhaiLoadingFont atlas, String value, float x, float y, float size, int color) {
        text.add(new TextRun(value, x - atlas.width(value, size) / 2, y, size, color, false));
    }

    private static int alpha(int color, float opacity) { return ((int) ((color >>> 24) * Math.max(0, Math.min(1, opacity))) << 24) | (color & 0xffffff); }
    private static void rect(BufferBuilder buffer, float x1, float y1, float x2, float y2, int color) { quad(buffer, x1, y1, x2, y2, color, color); }
    private static void quad(BufferBuilder buffer, float x1, float y1, float x2, float y2, int top, int bottom) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        buffer.vertex(x1, y2, 0).color(bottom).endVertex(); buffer.vertex(x2, y2, 0).color(bottom).endVertex();
        buffer.vertex(x2, y1, 0).color(top).endVertex(); buffer.vertex(x1, y1, 0).color(top).endVertex();
    }

    private record TextRun(String text, float x, float y, float size, int color, boolean nativeFont) {}
    private record DetailLine(String text, boolean heading) {}
}
