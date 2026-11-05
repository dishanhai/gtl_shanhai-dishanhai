package com.dishanhai.gt_shanhai.client.gui.shop;

import net.minecraft.client.Minecraft;
import com.dishanhai.sh_better_ui.client.glass.GlassTooltip;
import net.minecraftforge.client.event.RenderTooltipEvent;


/**
 * 商店玻璃主题下的 tooltip（山海署名，客户端）：原版 tooltip 是近黑底 + 紫粉渐变边，和半透明玻璃格格不入，
 * 而且别的模组会往它的颜色事件里塞自己的边框。这里在 {@link RenderTooltipEvent.Pre} 里直接接管——
 * 只在商店界面且主题为玻璃时取消原版绘制，按原版同一套布局（宽高、定位器、首行后空两像素）自己画：
 * 毛玻璃底 + 深蓝实底 + 青色细边，再画文字与图片组件。别的界面、经典主题完全不受影响。
 */
public final class ShopGlassTooltip {


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
        if (applies()) GlassTooltip.render(event);
    }
}
