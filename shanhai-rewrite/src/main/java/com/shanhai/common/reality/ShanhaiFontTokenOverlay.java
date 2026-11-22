package com.shanhai.common.reality;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import java.util.function.Supplier;

/**
 * 🆕 2026-11-22：<b>「名字字体」浮层</b> —— 把现成的 {@link ShanhaiTokenPicker}
 * （39 个按钮：16 色 ＋ 6 格式 ＋ 16 山海样式 ＋ {@code &amp;r}）盖到<b>名字输入框</b>上。
 *
 * <h2>用户原话与问题</h2>
 * 「物品编辑器的物品名字居然不能用原版或者山海的特殊字体，把那个功能也加上（选择特殊字体那个）」。
 * 现象已核实：名字框是普通 {@code TextFieldWidget}，{@code §}(U+00A7) <b>根本打不进去</b>
 * （原版 {@code SharedConstants#isAllowedChatCharacter} 硬排除；粘贴走 {@code filterText} 被静默吃掉）。
 * 「提示」那条线早就用 {@link ShanhaiTokenField} + {@link ShanhaiTokenPicker} 解决了，
 * 名字框只是没跟上 ⇒ 本类把那一套<b>原样</b>搬过来，不重写。
 *
 * <h2>🔴 为什么是"浮一层"而不是"塞进面板空白处"</h2>
 * 39 个按钮的坐标是写死的（{@link ShanhaiTokenLibrary#CHIPS}，y=240..274），
 * 而「新增」页在 y=240..290 已经排满了「选图标 / 取手上物品 / 保存 / 清空 / 关闭」。
 * 硬塞会挤坏既有控件（任务书明令禁止）⇒ 面板上只放一个 <b>「字体」小按钮</b>，
 * 点开这一层盖上去；坐标一个不差地复用按钮带。
 *
 * <h2>版面（340×300，与面板同尺寸）</h2>
 * <pre>
 *   y=4    标题
 *   y=18   混用警告（选择器自带）
 *   y=27   状态行（选择器自带：插了什么 / 为什么没插）
 *   y=44   预览标题 ／ y=60 名字的真实样子（带样式码渲染）
 *   y=240..274  39 个按钮（选择器自带，坐标与「提示」编辑器逐像素相同）
 *   y=278  「完成（关掉这一层）」
 * </pre>
 */
final class ShanhaiFontTokenOverlay extends WidgetGroup {

    private final ShanhaiTokenField field;
    private final int maxChars;
    private final Supplier<String> previewSource;

    private ShanhaiTokenPicker picker;
    private LabelWidget previewLabel;
    private String lastPreview = "\u0000";

    /**
     * @param field         插到哪个框（名字框）
     * @param previewSource 预览取哪段文本（一般是"这一页的编辑缓冲"）
     * @param maxChars      插入的原始 char 上限（见 {@link ShanhaiNameCaps}）
     */
    ShanhaiFontTokenOverlay(ShanhaiTokenField field, Supplier<String> previewSource, int maxChars) {
        super(0, 0, ShanhaiCreativeItemsPanel.W, ShanhaiCreativeItemsPanel.H);
        this.field = field;
        this.previewSource = previewSource;
        this.maxChars = maxChars;
        this.setBackground(GuiTextures.BACKGROUND);

        this.addWidget(new LabelWidget(6, 4,
                "§f名字字体 §7· 点下面的按钮，码插到「名字框」的光标处（与提示编辑器同一套）"));

        // 预览：这一段是"玩家最后会看到的样子"（§ 走原版，&$…- 走本工程的字形渲染器）
        this.addWidget(new LabelWidget(6, 44, "§7预览（名字真实样子）："));
        this.previewLabel = new LabelWidget(6, 60, "§8（名字是空的）");
        this.addWidget(this.previewLabel);

        this.picker = new ShanhaiTokenPicker(this, new ShanhaiTokenPicker.FieldSource() {
            @Override
            public ShanhaiTokenField target() {
                return field;
            }

            @Override
            public int rowOf(ShanhaiTokenField f) {
                return 1;      // 名字只有一行；状态行显示"第 1 行"即可
            }
        }, maxChars);

        this.addWidget(new ShanhaiCreativeWidgets.TextButton(176, 278, 152, 18,
                () -> "§f完成（关掉这一层）", this::close));

        this.setVisible(false);
        this.setActive(false);
    }

    // ------------------------------------------------------------------ 开关

    void open() {
        this.lastPreview = "\u0000";      // 强制刷一次预览
        refreshPreview();
        this.setVisible(true);
        this.setActive(true);
        com.shanhai.ShanhaiMod.LOGGER.info("{} name_font_overlay_open max_chars={}",
                ShanhaiRealityItemTable.PREFIX, this.maxChars);
    }

    void close() {
        this.setVisible(false);
        this.setActive(false);
    }

    /** 只给外层面板问"覆盖层开着没"。 */
    boolean isOpen() {
        return isVisible();
    }

    // ------------------------------------------------------------------ 预览

    private void refreshPreview() {
        if (this.previewLabel == null) {
            return;
        }
        String raw = this.field == null ? "" : this.field.liveText();
        if (raw == null) {
            raw = "";
        }
        if (raw.isEmpty() && this.previewSource != null) {
            raw = this.previewSource.get();
        }
        if (raw == null) {
            raw = "";
        }
        if (raw.equals(this.lastPreview)) {
            return;
        }
        this.lastPreview = raw;
        this.previewLabel.setText(raw.isEmpty() ? "§8（名字是空的）" : raw);
        com.shanhai.ShanhaiMod.LOGGER.info("{} name_font_preview {}", ShanhaiRealityItemTable.PREFIX,
                ShanhaiNameCaps.describe(raw));
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        refreshPreview();
    }

    // ------------------------------------------------------------------ 点击

    /**
     * 🔴 整层吃掉点击：本层铺满面板。不吞掉"落在它身上但没打中按钮"的那一下，
     * 点击会穿到底层（名字框 / 保存 / 关闭）—— 与两个选择器同一条教训。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        return isMouseOverElement(mouseX, mouseY);
    }
}
