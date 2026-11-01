package com.shanhai.common.reality;

import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 能<b>把一段码插到光标处</b>的文本框（「提示」多行编辑器每一行的那个框）。
 *
 * <h2>🔴 为什么必须是子类（三条都是取证结论，别绕过）</h2>
 * <ol>
 *   <li>{@code TextFieldWidget} <b>没有</b>光标 API：{@code javap -p} 全表里只有
 *       {@code setCurrentString/getCurrentString/getRawCurrentString/setMaxStringLength/…}，
 *       光标藏在 {@code protected EditBox textField} 里。本类是同一个包 ⇒ 能直接读那个字段。</li>
 *   <li>{@code EditBox#insertText(String)} <b>不能用</b>：它过 {@code SharedConstants#filterText}，
 *       <b>§(U+00A7) 会被静默吃掉</b>（插 {@code §f} 结果插进去的是 {@code f}）。</li>
 *   <li>{@code EditBox#setValue(String)} <b>不过</b> {@code filterText}（只过默认恒真的 {@code Predicate filter}）
 *       ⇒ § 存活。落地走 {@code setCurrentString}（内部就是 {@code setValue}）。</li>
 * </ol>
 * ⚠️ {@code setValue} 会把光标甩到末尾（{@code moveCursorToEnd}）⇒ 插完必须自己
 * {@code setCursorPosition(旧位置 + token.length())}。
 *
 * <h2>🔴 只在客户端有 {@code textField}</h2>
 * {@code TextFieldWidget#<init>} 在 {@code isRemote()} 为假时整段跳过 ⇒ 服务端那份
 * {@code textField == null}。本类所有碰它的地方都判空（连 {@code super.onFocusChanged} 都要判：
 * 它的方法体第一句就是 {@code textField.setFocused(...)}，空指针）。服务端那份只用于容器对齐。
 */
final class ShanhaiTokenField extends TextFieldWidget {

    /** 获得焦点 / 被点到时回调（编辑器用它记"最后点过的那个框"）。 */
    private final Consumer<ShanhaiTokenField> focusListener;

    ShanhaiTokenField(int x, int y, int width, int height,
                      Supplier<String> textSupplier, Consumer<String> textResponder,
                      Consumer<ShanhaiTokenField> focusListener) {
        super(x, y, width, height, textSupplier, textResponder);
        this.focusListener = focusListener;
    }

    // ------------------------------------------------------------------ 光标 / 取词

    /**
     * <b>现取</b>框里的真值：客户端以 {@code EditBox#getValue()} 为准，
     * 没有 {@code textField} 时回落到 {@code getCurrentString()}。
     *
     * <p>为什么不直接用 {@code getCurrentString()}：类注释 §「与文本框相关的既有教训」说了
     * {@code textResponder} <b>不一定在客户端这份被调</b>，内存里的 {@code currentString} 理论上可能落后一拍。
     */
    String liveText() {
        if (this.textField != null) {
            final String live = this.textField.getValue();
            return live == null ? "" : live;
        }
        final String s = getCurrentString();
        return s == null ? "" : s;
    }

    /** 光标位（0..长度）。服务端无 {@code textField} ⇒ 退化成"末尾"。 */
    int cursorPos() {
        if (this.textField == null) {
            return liveText().length();
        }
        return Math.max(0, Math.min(this.textField.getCursorPosition(), liveText().length()));
    }

    /** 放光标（夹到合法范围；服务端无 {@code textField} 时安全地什么都不做）。 */
    void setCursorPos(int pos) {
        if (this.textField == null) {
            return;
        }
        this.textField.setCursorPosition(Math.max(0, Math.min(pos, liveText().length())));
    }

    // ------------------------------------------------------------------ 插入

    /**
     * 把 {@code token} 插到<b>当前光标处</b>。
     *
     * <p>🔴 三步，顺序不能变：①{@code getCursorPosition} 取位 ②{@code setCurrentString(前+token+后)}
     * （= {@code EditBox#setValue}，§ 存活）③{@code setCursorPosition} 复位光标。
     *
     * @param maxLength 每行上限（{@code ShanhaiRealityItemTable.MAX_LORE_CHARS} = 512）；
     *                  超限<b>整次拒绝</b>，不静默截断
     * @return 结果（{@code ok()==false} 时调用方必须<b>出声</b>，不能假装插了）
     */
    ShanhaiTokenLibrary.Insert insertAtCursor(String token, int maxLength) {
        final String before = liveText();
        final int caret = cursorPos();
        final ShanhaiTokenLibrary.Insert result =
                ShanhaiTokenLibrary.insert(before, caret, token, maxLength);
        if (!result.ok()) {
            return result;
        }
        // ↓ 这一步等价于 EditBox#setValue（§ 不会被 filterText 吃掉）
        setCurrentString(result.value());
        setCursorPos(result.cursor());
        return result;
    }

    // ------------------------------------------------------------------ 焦点

    @Override
    public void onFocusChanged(Widget oldFocus, Widget newFocus) {
        // ⚠️ super 的方法体第一句就是 textField.setFocused(...) —— 服务端那份 textField==null，必须判空
        if (this.textField != null) {
            super.onFocusChanged(oldFocus, newFocus);
        }
        if (this.focusListener != null && isFocus()) {
            this.focusListener.accept(this);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // ⚠️ super 直接 textField.setFocused/isMouseOverElement 解引用，服务端那份会 NPE（见类注释）
        if (this.textField == null) {
            return false;
        }
        final boolean inside = isMouseOverElement(mouseX, mouseY);
        final boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (inside && this.focusListener != null) {
            this.focusListener.accept(this);
        }
        return handled;
    }
}
