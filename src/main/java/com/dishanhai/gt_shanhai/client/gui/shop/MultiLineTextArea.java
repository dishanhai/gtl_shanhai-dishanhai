package com.dishanhai.gt_shanhai.client.gui.shop;

import net.minecraft.SharedConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Style;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 轻量多行文本编辑区（山海署名）：包一层 vanilla {@link MultilineTextField}（换行/光标/选区模型全交给它算，
 * 含内建的方向键/Home/End/Ctrl+方向跳词/退格/删除/全选/复制/粘贴/剪切，回车即插入换行），
 * 本类只负责在 {@link GuiGraphics} 里画出来 + 把鼠标/键盘事件从宿主 Screen 转发进来 + 简单的按行滚动。
 *
 * <p>{@code MultilineTextField.StringView} 是 protected 嵌套类型，跨包不可见，因此渲染用的行边界
 * 改由本类自己调 {@link Font#getSplitter()} 按与 {@link MultilineTextField} 内部完全一致的算法
 * （同 width/{@link Style#EMPTY}/不保留尾随空白）重新切一遍，保证行号与 field 的
 * {@code getLineAtCursor()}/{@code getLineCount()} 对得上；仅「选区高亮」这一处离不开 StringView，
 * 用反射取其 beginIndex/endIndex（record 访问器，只读、无副作用，稳妥）。</p>
 *
 * <p>不是 {@link net.minecraft.client.gui.components.AbstractWidget}，不接入 Screen 的控件树/焦点系统，
 * 由宿主 Screen（{@link ShopEntryEditScreen}）在自己的 universalMouseClicked/keyPressed/charTyped 里手动路由。</p>
 */
class MultiLineTextArea {

    private static final int LINE_H = 9;

    private static final Method SV_BEGIN;
    private static final Method SV_END;
    static {
        Method begin = null, end = null;
        try {
            Class<?> svClass = Class.forName("net.minecraft.client.gui.components.MultilineTextField$StringView");
            begin = svClass.getDeclaredMethod("beginIndex");
            end = svClass.getDeclaredMethod("endIndex");
            begin.setAccessible(true);
            end.setAccessible(true);
        } catch (ReflectiveOperationException ignored) {
            // 拿不到就跳过选区高亮渲染，不影响编辑本身（见 render() 里的 null 判空）
        }
        SV_BEGIN = begin;
        SV_END = end;
    }

    private final Font font;
    private final MultilineTextField field;
    private final int width;
    private int x, y, w, h;
    private int scrollLine;
    private boolean focused;

    MultiLineTextArea(Font font, int width) {
        this.font = font;
        this.width = width;
        this.field = new MultilineTextField(font, width);
        this.field.setCursorListener(this::scrollToCursor);
    }

    void setBounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    void setValue(String text) {
        field.setValue(text == null ? "" : text);
        scrollLine = 0;
    }

    String getValue() {
        return field.value();
    }

    void setFocused(boolean f) {
        this.focused = f;
    }

    private int visibleLines() {
        return Math.max(1, h / LINE_H);
    }

    /** 与 {@link MultilineTextField} 内部换行算法完全一致地重新切一遍行（见类注释）。 */
    private List<int[]> computeLines() {
        String text = field.value();
        List<int[]> lines = new ArrayList<>();
        if (text.isEmpty()) {
            lines.add(new int[]{0, 0});
        } else {
            font.getSplitter().splitLines(text, width, Style.EMPTY, false,
                    (style, start, end) -> lines.add(new int[]{start, end}));
            if (text.charAt(text.length() - 1) == '\n') {
                lines.add(new int[]{text.length(), text.length()});
            }
        }
        return lines;
    }

    /** 光标移动后若跑出可视范围，自动把滚动窗口拉回来盖住光标所在行（field 的 cursorListener 回调）。 */
    private void scrollToCursor() {
        int line = field.getLineAtCursor();
        int visible = visibleLines();
        if (line < scrollLine) {
            scrollLine = line;
        } else if (line >= scrollLine + visible) {
            scrollLine = line - visible + 1;
        }
    }

    /** 反射取选区 [begin,end)；拿不到（反射初始化失败）或无选区时返回 null。 */
    private int[] selectedRange() {
        if (SV_BEGIN == null || SV_END == null || !field.hasSelection()) return null;
        try {
            Object view = field.getSelected();
            return new int[]{(int) SV_BEGIN.invoke(view), (int) SV_END.invoke(view)};
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    void render(GuiGraphics g, int mx, int my) {
        String s = field.value();
        int cursor = field.cursor();
        boolean drawCursor = focused && (System.currentTimeMillis() / 500L) % 2L == 0L;
        int[] sel = selectedRange();
        List<int[]> lines = computeLines();
        int visible = visibleLines();

        int ty = y;
        for (int i = scrollLine; i < Math.min(lines.size(), scrollLine + visible); i++) {
            int[] view = lines.get(i);
            if (sel != null && sel[0] <= view[1] && sel[1] >= view[0]) {
                int selStart = Math.max(sel[0], view[0]);
                int selEnd = Math.min(sel[1], view[1]);
                int hx1 = x + font.width(s.substring(view[0], selStart));
                int hx2 = x + font.width(s.substring(view[0], selEnd));
                g.fill(hx1, ty, Math.max(hx1 + 1, hx2), ty + LINE_H, 0x553399FF);
            }
            if (view[1] > view[0]) {
                g.drawString(font, s.substring(view[0], view[1]), x, ty, 0xFFFFFF, false);
            }
            if (drawCursor && cursor >= view[0] && cursor <= view[1]) {
                int cx = x + font.width(s.substring(view[0], cursor));
                g.fill(cx, ty, cx + 1, ty + LINE_H, 0xFFFFFFFF);
            }
            ty += LINE_H;
        }
        // 行数提示常驻显示（非仅超出可视范围才显示）：既是超长翻页提示，也是换行/可视行数是否异常的排查依据
        g.drawString(font, "§8第 " + (scrollLine + 1) + "-" + Math.min(scrollLine + visible, lines.size())
                + " / 共 " + lines.size() + " 行（可视 " + visible + " 行）"
                + (lines.size() > visible ? " 滚轮翻页" : ""), x, y + h + 1, 0x808080, false);
    }

    boolean isHovering(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    boolean mouseClicked(double mx, double my) {
        if (!isHovering(mx, my)) return false;
        field.setSelecting(Screen.hasShiftDown());
        double localX = mx - x;
        double localY = (my - y) + (double) scrollLine * LINE_H;
        field.seekCursorToPoint(localX, localY);
        field.setSelecting(false);
        return true;
    }

    /** 拖拽扩选：与点选共用同一套「像素坐标 → 光标位置」换算，全程保持 selecting=true 直到松手。 */
    boolean mouseDragged(double mx, double my) {
        if (!focused) return false;
        field.setSelecting(true);
        double localX = Math.max(0, mx - x);
        double localY = (my - y) + (double) scrollLine * LINE_H;
        field.seekCursorToPoint(localX, Math.max(0, localY));
        return true;
    }

    boolean scroll(double delta) {
        int max = Math.max(0, computeLines().size() - visibleLines());
        int next = Math.max(0, Math.min(max, scrollLine - (int) Math.signum(delta)));
        if (next == scrollLine) return false;
        scrollLine = next;
        return true;
    }

    boolean keyPressed(int keyCode) {
        if (!focused) return false;
        return field.keyPressed(keyCode);
    }

    boolean charTyped(char c) {
        if (!focused) return false;
        if (SharedConstants.isAllowedChatCharacter(c)) {
            field.insertText(Character.toString(c));
            return true;
        }
        return false;
    }
}
