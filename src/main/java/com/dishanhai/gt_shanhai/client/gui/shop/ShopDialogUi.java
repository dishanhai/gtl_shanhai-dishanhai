package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.ButtonFeedback;
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiMotion;
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.Spring;
import com.dishanhai.gt_shanhai.client.gui.scaled.SpringTable;
import com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import org.lwjgl.glfw.GLFW;

/**
 * 商店弹窗（自动合成确认、自选奖励）共用的交互件（山海署名，客户端）：按钮走和 {@link ShopScreen} 同一套
 * {@link ButtonFeedback}——hover 渐变、按下压感与回弹、点击声，不可用的按钮点了轻抖并低响；
 * 列表行的 hover 渐变；列表右缘可拖动的滚动条。
 *
 * <p>弹窗每帧调 {@link #frame}，松开调 {@link #release}，关闭/重建调 {@link #cancel}。</p>
 */
final class ShopDialogUi {

    static final int BAR_W = 3;
    private static final int BAR_GRAB = 2;
    private static final int TRACK = 0x40FFFFFF;
    private static final long ROW_KEY_BASE = 0x5D1A_0000_0000_0000L;

    private final SpringTable<Long> hover = new SpringTable<>(Spring.HOVER);
    private final ButtonFeedback feedback = new ButtonFeedback(hover);
    private final int border;
    private final int borderHover;
    private final int fill;
    private final int fillHover;
    private boolean reduceMotion;
    private boolean dragging;
    private boolean enterHeld;

    ShopDialogUi(int border, int borderHover, int fill, int fillHover) {
        this.border = border;
        this.borderHover = borderHover;
        this.fill = fill;
        this.fillHover = fillHover;
    }

    static long key(int x, int y, int w, int h) {
        return ((long) x * 73856093L) ^ ((long) y * 19349663L) ^ ((long) ((w << 12) ^ h) * 83492791L);
    }

    static long rowKey(int index) {
        return ROW_KEY_BASE | (index & 0xFFFFFFFFL);
    }

    /** 每帧：松开落在别处（没收到 mouseReleased）时兜底松开，游标进出按住的按钮，推进闪色与轻抖。 */
    void frame(int mx, int my) {
        long now = System.currentTimeMillis();
        reduceMotion = ClientShopUiSettings.reduceMotion();
        MouseHandler mouseHandler = Minecraft.getInstance().mouseHandler;
        if (!mouseHandler.isLeftPressed() && !mouseHandler.isRightPressed()) {
            if (feedback.holding()) feedback.release(now);
            dragging = false;
        }
        feedback.pointer(mx, my, now);
        feedback.tick(now);
    }

    /** 点到按钮就按下：可用的压下并出声，不可用的轻抖并低响。两种都算命中，调用方再看可不可用。 */
    boolean hit(double mx, double my, int x, int y, int w, int h, boolean enabled) {
        if (!GuiRenderUtil.isHovering(mx, my, x, y, w, h)) return false;
        press(x, y, w, h, enabled);
        return true;
    }

    /** 键盘触发（Enter）走同一个按下：没有鼠标按住，下一帧兜底就松开，正好按一下再弹回。 */
    void press(int x, int y, int w, int h, boolean enabled) {
        long now = System.currentTimeMillis();
        if (feedback.press(key(x, y, w, h), x, y, w, h, enabled, now)) ShopUiSounds.click();
        else ShopUiSounds.limit();
    }

    /** 动作被拒（比如上一笔还在等回执）：按钮轻抖，提示忙。 */
    void busy(int x, int y, int w, int h) {
        feedback.nudge(key(x, y, w, h), System.currentTimeMillis());
        ShopUiSounds.busy();
    }

    /** 动作没通过检查（比如保存时有栏位没填对）：按钮轻抖，出失败音。 */
    void reject(int x, int y, int w, int h) {
        feedback.nudge(key(x, y, w, h), System.currentTimeMillis());
        ShopUiSounds.fail();
    }

    /** 列表行选中：只出点击声，高亮由 {@link #rowHover} 渐变。 */
    void select() {
        ShopUiSounds.click();
    }

    float rowHover(int index, boolean hovered) {
        return feedback.hover(rowKey(index), hovered, System.currentTimeMillis());
    }

    // ============ Enter ============

    /** Enter 此刻是否按着（物理键状态）。 */
    static boolean enterDown() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_ENTER)
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_KP_ENTER);
    }

    /** 打开/重建时调：Enter 若还按着（在上一个界面按 Enter 打开了本框），松开前的系统重复都不算确认。 */
    void syncEnterHeld() {
        enterHeld = enterDown();
    }

    /** 这次 Enter 是不是新按下的；按住时的系统重复只算第一次。 */
    boolean takeEnter() {
        if (enterHeld) return false;
        enterHeld = true;
        return true;
    }

    void enterReleased(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) enterHeld = false;
    }

    void release() {
        feedback.release(System.currentTimeMillis());
        dragging = false;
    }

    void cancel() {
        feedback.cancel(System.currentTimeMillis());
        dragging = false;
    }

    void draw(GuiGraphics g, Font font, int x, int y, int w, int h, String label, int mx, int my, boolean enabled) {
        long now = System.currentTimeMillis();
        long k = key(x, y, w, h);
        float hv = feedback.hover(k, enabled && GuiRenderUtil.isHovering(mx, my, x, y, w, h), now);
        ButtonFeedback.Visual v = feedback.visual(k, k, enabled, now, reduceMotion);
        int b = ButtonFeedback.tint(GuiMotion.lerpColor(border, borderHover, hv), v);
        int f = ButtonFeedback.shade(GuiMotion.lerpColor(fill, fillHover, hv), v);
        GuiRenderUtil.withButtonMotion(g, x, y, w, h, v.scale(), v.shakeX(), () -> {
            g.fill(x, y, x + w, y + h, b);
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, f);
            g.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, 0xFFFFFFFF);
        });
    }

    // ============ 滚动条 ============

    /** 列表右缘的滚动条（x 是轨道左缘）：没溢出不画；游标在上面或正在拖时把手变亮。 */
    void drawScrollbar(GuiGraphics g, int x, int y, int h, int total, int visible, int first, int maxFirst,
                       int mx, int my) {
        if (maxFirst <= 0) return;
        int[] t = ShopDialogRows.thumb(total, visible, first, maxFirst, h);
        boolean hot = dragging || GuiRenderUtil.isHovering(mx, my, x - BAR_GRAB, y, BAR_W + BAR_GRAB * 2, h);
        g.fill(x, y, x + BAR_W, y + h, TRACK);
        g.fill(x, y + t[0], x + BAR_W, y + t[0] + t[1], hot ? borderHover : border);
    }

    /** 按在轨道上（左右各放宽 2px 好按）就开始拖，并立刻跳到那里；返回新的起始行，没按到返回 -1。 */
    int beginDrag(double mx, double my, int x, int y, int h, int total, int visible, int maxFirst) {
        if (maxFirst <= 0 || !GuiRenderUtil.isHovering(mx, my, x - BAR_GRAB, y, BAR_W + BAR_GRAB * 2, h)) return -1;
        dragging = true;
        return drag(my, y, h, total, visible, maxFirst);
    }

    /** 拖动中返回新的起始行；没在拖返回 -1。 */
    int drag(double my, int y, int h, int total, int visible, int maxFirst) {
        if (!dragging) return -1;
        int thumbH = ShopDialogRows.thumb(total, visible, 0, maxFirst, h)[1];
        return ShopDialogRows.firstAt(my - y, thumbH, h, maxFirst);
    }
}
