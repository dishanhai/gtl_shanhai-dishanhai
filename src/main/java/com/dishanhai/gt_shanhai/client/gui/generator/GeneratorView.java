package com.dishanhai.gt_shanhai.client.gui.generator;

import net.minecraft.client.gui.GuiGraphics;

/** 山海：七頁共用穩定虛擬座標；視覺平移不改命中區。 */
interface GeneratorView {
    void layout(PatternGeneratorScreen screen);
    default void render(PatternGeneratorScreen screen, GuiGraphics g, int mx, int my, float pt) {
        screen.renderElements(g,mx,my,pt);
    }
    default boolean mouseClicked(PatternGeneratorScreen screen,double mx,double my,int button) {
        return screen.clickElements(mx,my,button);
    }
    default boolean mouseScrolled(PatternGeneratorScreen screen,double amount) { return screen.scrollContent(amount); }
    default boolean keyPressed(PatternGeneratorScreen screen,int key,int scan,int mods) { return false; }
    default int depth() { return 1; }
}
