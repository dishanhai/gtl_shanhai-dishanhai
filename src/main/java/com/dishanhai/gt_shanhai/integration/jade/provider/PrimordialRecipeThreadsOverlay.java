package com.dishanhai.gt_shanhai.integration.jade.provider;

// Author: dishanhai
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import snownee.jade.api.Accessor;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.ITooltip;
import snownee.jade.api.callback.JadeAfterRenderCallback;
import snownee.jade.api.callback.JadeTooltipCollectedCallback;
import snownee.jade.api.config.IWailaConfig;
import snownee.jade.api.ui.IElement;
import snownee.jade.overlay.OverlayRenderer;
import snownee.jade.overlay.TooltipRenderer;
import snownee.jade.overlay.WailaTickHandler;

import java.util.List;

/** 山海：將已標記的原初執行緒元素從機器詳情框抽出，繪製於 Jade 主框左側。 */
public final class PrimordialRecipeThreadsOverlay implements JadeTooltipCollectedCallback, JadeAfterRenderCallback {
    public static final PrimordialRecipeThreadsOverlay INSTANCE = new PrimordialRecipeThreadsOverlay();
    private Pending pending;
    private TooltipRenderer panelRenderer;

    private PrimordialRecipeThreadsOverlay() {}

    @Override
    public void onTooltipCollected(ITooltip tooltip, Accessor<?> accessor) {
        pending = null;
        panelRenderer = null;
        if (!(accessor instanceof BlockAccessor block)
                || !block.getServerData().contains(PrimordialRecipeThreadsProvider.getDataKey())) return;
        List<IElement> elements = extractThreadElements(tooltip);
        if (!elements.isEmpty()) pending = new Pending(tooltip, elements, block.getLevel(), block.getPosition());
    }

    @Override
    public void afterRender(ITooltip tooltip, Rect2i mainRect, GuiGraphics graphics, Accessor<?> accessor) {
        List<IElement> elements = threadElementsFor(tooltip, accessor);
        if (elements.isEmpty()) return;
        var overlay = IWailaConfig.get().getOverlay();
        if (panelRenderer == null) {
            var sideTooltip = new snownee.jade.impl.Tooltip();
            elements.forEach(sideTooltip::add);
            panelRenderer = new TooltipRenderer(sideTooltip, false);
            panelRenderer.setPaddingFromTheme(overlay.getTheme());
            panelRenderer.recalculateSize();
        }
        TooltipRenderer renderer = panelRenderer;
        Vec2 size = renderer.getSize();
        int width = (int) Math.ceil(size.x);
        int height = (int) Math.ceil(size.y);
        // afterRender 已套用主框的平移與縮放；使用正在繪製的 renderer 還原座標。
        float mainScale = WailaTickHandler.instance().tooltipRenderer.getRealScale();
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        float panelScale = fitScale(mainScale, width, height, mainRect.getX(), screenHeight);
        Vec2 position = leftPanelPosition(mainRect.getX(), mainRect.getY(), width, height, panelScale,
                Minecraft.getInstance().getWindow().getGuiScaledWidth(), screenHeight);

        var pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate((position.x - mainRect.getX()) / mainScale,
                    (position.y - mainRect.getY()) / mainScale, 0);
            pose.scale(panelScale / mainScale, panelScale / mainScale, 1);
            OverlayRenderer.drawTooltipBox(graphics, 0, 0, width, height,
                    OverlayRenderer.alpha * overlay.getAlpha(), overlay.getSquare(), renderer);
            renderer.draw(graphics);
        } finally {
            pose.popPose();
        }
    }

    List<IElement> threadElementsFor(ITooltip tooltip, Accessor<?> accessor) {
        Pending display = pending;
        return display != null && display.source == tooltip && accessor instanceof BlockAccessor block
                && display.level == block.getLevel() && display.position.equals(block.getPosition())
                ? display.elements : List.of();
    }

    static List<IElement> extractThreadElements(ITooltip tooltip) {
        var elements = List.copyOf(tooltip.get(PrimordialRecipeThreadsProvider.INSTANCE.getThreadElementUid()));
        if (!elements.isEmpty()) tooltip.remove(PrimordialRecipeThreadsProvider.INSTANCE.getThreadElementUid());
        return elements;
    }

    static float fitScale(float preferredScale, int width, int height, int mainX, int screenHeight) {
        float widthScale = Math.max(1, mainX - 6) / (float) Math.max(1, width);
        float heightScale = Math.max(1, screenHeight - 4) / (float) Math.max(1, height);
        return Math.max(0.01F, Math.min(preferredScale, Math.min(widthScale, heightScale)));
    }

    static Vec2 leftPanelPosition(int mainX, int mainY, int width, int height, float scale,
                                   int screenWidth, int screenHeight) {
        int scaledWidth = (int) Math.ceil(width * scale);
        int scaledHeight = (int) Math.ceil(height * scale);
        int x = Math.max(2, Math.min(mainX - scaledWidth - 4, screenWidth - scaledWidth - 2));
        int y = Math.max(2, Math.min(mainY, screenHeight - scaledHeight - 2));
        return new Vec2(x, y);
    }

    private record Pending(ITooltip source, List<IElement> elements, Level level, BlockPos position) {}
}
