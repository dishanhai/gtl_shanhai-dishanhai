package com.dishanhai.gt_shanhai.client.gui.shop;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraftforge.client.event.RenderTooltipEvent;


/**
 * 山海：商店系 tooltip 使用当前商店主题的边框；沿用事件提供的字型、定位器和文字／图片组件。
 * 在其他模组绘制彩色边框前接管，非商店画面不受影响。
 */
public final class ShopGlassTooltip {
    private static final int PAD = 4;

    private ShopGlassTooltip() {
    }

    /** 当前界面是商店系界面，三种主题均使用自己的 tooltip 框。 */
    static boolean applies() {
        var screen = Minecraft.getInstance().screen;
        return screen != null
                && screen.getClass().getName().startsWith("com.dishanhai.gt_shanhai.client.gui.shop.");
    }

    /** 以 HIGHEST 优先级挂在 Forge 总线上，先于外部 tooltip 边框绘制。 */
    public static void onTooltipPre(RenderTooltipEvent.Pre event) {
        if (!applies() || event.getComponents().isEmpty()) return;
        var components = event.getComponents();
        var font = event.getFont();
        int width = 0;
        int height = components.size() == 1 ? -2 : 0;
        for (ClientTooltipComponent c : components) {
            width = Math.max(width, c.getWidth(font));
            height += c.getHeight();
        }
        var pos = event.getTooltipPositioner().positionTooltip(event.getScreenWidth(), event.getScreenHeight(),
                event.getX(), event.getY(), width, height);
        int x = pos.x();
        int y = pos.y();
        ShopTheme theme = ShopTheme.current();
        GuiGraphics g = event.getGraphics();
        event.setCanceled(true);
        g.pose().pushPose();
        try {
            if (theme.glass) {
                theme.tooltipPanel(g, x - PAD, y - PAD, width + PAD * 2, height + PAD * 2);
            } else {
                theme.rootPanel(g, x - PAD, y - PAD, width + PAD * 2, height + PAD * 2,
                        x - PAD + 2, y - PAD + 2, width + PAD * 2 - 4, height + PAD * 2 - 4);
            }
            g.flush();
            g.pose().translate(0f, 0f, 400f);
            int ty = y;
            for (int i = 0; i < components.size(); i++) {
                ClientTooltipComponent c = components.get(i);
                c.renderText(font, x, ty, g.pose().last().pose(), g.bufferSource());
                ty += c.getHeight() + (i == 0 ? 2 : 0);
            }
            g.flush();
            ty = y;
            for (int i = 0; i < components.size(); i++) {
                ClientTooltipComponent c = components.get(i);
                c.renderImage(font, x, ty, g);
                ty += c.getHeight() + (i == 0 ? 2 : 0);
            }
        } finally {
            g.pose().popPose();
        }
    }
}
