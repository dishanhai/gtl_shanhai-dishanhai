package com.dishanhai.gt_shanhai.client.gui.shop;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraftforge.client.event.RenderTooltipEvent;
import org.joml.Vector2ic;

import java.util.List;

/**
 * 商店玻璃主题下的 tooltip（山海署名，客户端）：原版 tooltip 是近黑底 + 紫粉渐变边，和半透明玻璃格格不入，
 * 而且别的模组会往它的颜色事件里塞自己的边框。这里在 {@link RenderTooltipEvent.Pre} 里直接接管——
 * 只在商店界面且主题为玻璃时取消原版绘制，按原版同一套布局（宽高、定位器、首行后空两像素）自己画：
 * 毛玻璃底 + 深蓝实底 + 青色细边，再画文字与图片组件。别的界面、经典主题完全不受影响。
 */
public final class ShopGlassTooltip {

    private static final int PAD = 4;
    private static final float TEXT_Z = 400f;

    private ShopGlassTooltip() {
    }

    /** 当前界面是商店系界面且玻璃主题开着。 */
    static boolean applies() {
        var screen = Minecraft.getInstance().screen;
        return screen != null
                && screen.getClass().getName().startsWith("com.dishanhai.gt_shanhai.client.gui.shop.")
                && ShopTheme.current().glass;
    }

    /** 以 LOWEST 优先级挂在 Forge 总线上：别的模组先改完位置/字体，我们再接管绘制。 */
    public static void onTooltipPre(RenderTooltipEvent.Pre event) {
        List<ClientTooltipComponent> components = event.getComponents();
        if (components.isEmpty() || !applies()) return;
        GuiGraphics g = event.getGraphics();
        var font = event.getFont();

        int w = 0;
        int h = components.size() == 1 ? -2 : 0;
        for (ClientTooltipComponent c : components) {
            w = Math.max(w, c.getWidth(font));
            h += c.getHeight();
        }
        Vector2ic pos = event.getTooltipPositioner()
                .positionTooltip(event.getScreenWidth(), event.getScreenHeight(), event.getX(), event.getY(), w, h);
        int x = pos.x();
        int y = pos.y();

        g.pose().pushPose();
        try {
            ShopTheme.GLASS.tooltipPanel(g, x - PAD, y - PAD, w + PAD * 2, h + PAD * 2);
            g.flush();
            g.pose().translate(0f, 0f, TEXT_Z);
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
        event.setCanceled(true);
    }
}
