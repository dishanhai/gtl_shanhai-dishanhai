package com.dishanhai.gt_shanhai.client.gui.generator;

import com.dishanhai.gt_shanhai.client.gui.glass.*;
import com.dishanhai.gt_shanhai.client.gui.scaled.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.function.IntConsumer;

/** 山海：原生 Screen 的可換行下拉選單。既有 EditorWidgets/ShopDialogRows 為 package-private，
 * ShopQuestLinkMenu 依賴 FTB BaseScreen，無法用於此 Screen；復用玻璃、Spring 與共用按鈕回饋。 */
final class GeneratorDropdown {
    final int x,y,w,h;
    final GuiRect anchor;
    final List<Component> options;
    final IntConsumer choose;
    final Spring opening = new Spring(Spring.PANEL);
    final Spring scroll = new Spring(Spring.SCROLL);
    private final int[] starts,heights;
    private final int total;
    private final GlassPanelTexture panel=GlassPanelTexture.panel();
    private int target;
    private int selected;

    GeneratorDropdown(Font font,int x,int y,int w,int available,GuiRect anchor,List<Component> options,int selected,IntConsumer choose,long now) {
        this.x=x; this.y=y; this.w=w; this.anchor=anchor; this.options=List.copyOf(options); this.choose=choose;
        starts=new int[options.size()]; heights=new int[options.size()]; int used=0;
        for (int i=0;i<options.size();i++) {
            starts[i]=used; heights[i]=Math.max(22,font.split(options.get(i),Math.max(1,w-20)).size()*font.lineHeight+10); used+=heights[i];
        }
        total=used; h=Math.max(24,Math.min(available,total+8)); opening.setTarget(1,now);
        this.selected=Math.max(0,Math.min(options.size()-1,selected));
        moveSelection(0); scroll.snap(target);
    }
    GuiRect hole() {
        float left=Math.min(x,anchor.getX()), top=Math.min(y,anchor.getY());
        return new GuiRect(left,top,Math.max(x+w,anchor.getX()+anchor.getW())-left,Math.max(y+h,anchor.getY()+anchor.getH())-top);
    }
    void render(PatternGeneratorScreen screen,GuiGraphics g,int mx,int my,long now,boolean reduced) {
        if (reduced) { opening.snap(1); scroll.snap(target); }
        else { opening.update(now); scroll.setTarget(target,now); scroll.update(now); }
        GuiRenderUtil.withPanelMotion(g,x+w/2f,y+h/2f,opening.value(),()-> {
            panel.draw(g,mx,my,x,y,w,h);
            screen.scissor(g,x+2,y+2,w-4,h-4);
            try {
                for (int i=0;i<options.size();i++) {
                    int rowY=y+4+starts[i]-Math.round(scroll.value());
                    if (rowY+heights[i]<y || rowY>y+h) continue;
                    screen.drawButton(g,"dropdown."+i,x+4,rowY,w-8,heights[i]-2,options.get(i),true,mx,my);
                    if (i==selected) g.renderOutline(x+4,rowY,w-8,heights[i]-2,GlassPalette.ACCENT);
                }
            } finally { g.disableScissor(); }
        });
    }
    boolean click(double mx,double my) {
        if (mx<x || mx>=x+w || my<y || my>=y+h) return false;
        int offset=(int)my-y-4+Math.round(scroll.value());
        for (int i=0;i<options.size();i++) if (offset>=starts[i] && offset<starts[i]+heights[i]) { choose.accept(i); return true; }
        return true;
    }
    void scroll(double delta) { target=Math.max(0,Math.min(Math.max(0,total+8-h),target-(int)(delta*30))); }
    void moveSelection(int delta) {
        if (options.isEmpty()) return;
        selected=Math.max(0,Math.min(options.size()-1,selected+delta));
        if (starts[selected]<target) target=starts[selected];
        else if (starts[selected]+heights[selected]>target+h-8) target=starts[selected]+heights[selected]-h+8;
        target=Math.max(0,Math.min(Math.max(0,total+8-h),target));
    }
    void confirm() { if (!options.isEmpty()) choose.accept(selected); }
}
