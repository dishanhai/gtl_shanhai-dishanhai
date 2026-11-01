package com.shanhai.common.reality;

import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.text.ShanhaiTextPalette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 「提示」多行编辑器下方的<b>特效/颜色选择器按钮带</b>（两排常驻按钮 ＋ 两行提示）。
 *
 * <h2>它做什么</h2>
 * 点一个按钮 ⇒ 把对应的码（{@code §f} / {@code §l} / {@code &$neon-} / {@code &r}）<b>插到当前行的光标处</b>
 * （见 {@link ShanhaiTokenField#insertAtCursor}）。
 *
 * <h2>🔴 两套码不能混用（这不是我们的怪癖，是解析器写死的规则）</h2>
 * {@code ShanhaiTextParser#parseUncached} 里：一行里只要出现 {@code §} 又出现 {@code &$}，
 * 就 {@code warnUnsupported("mixed_section_sign")} ＋ {@code return NOT_OURS} ⇒ <b>整行交回原版渲染</b>，
 * 于是 {@code &$neon-} 会<b>原样显示在提示里</b>。所以这里必须先分组、再把这句话摆在玩家眼前。
 *
 * <h2>🔴 为什么插的是 {@code §f} 而不是 {@code &f}</h2>
 * FTB 任务那边插的是 {@code &} 码，因为它自己那条文本链会把 {@code &} 当 legacy 码；
 * <b>我们这条链只认 {@code &$…-}</b>（{@code parseUncached} 要求 {@code &} 后面必须有 {@code $}）
 * ⇒ 照抄 FTB 插 {@code &f} 会原样显示两个字符。原版 16 色/6 格式一律走 {@code §}（原版渲染懂它）。
 *
 * <h2>布局（面板 340×300，全部落在 240..274 ＋ 6/18/27 三行小字里）</h2>
 * <ul>
 *   <li>第一排 y=240：16 个色块（13×16）＋ 6 个格式码（13×16），x=46 起；</li>
 *   <li>第二排 y=258：16 个山海样式（16×16）＋ {@code &r}（16×16），x=46 起；</li>
 *   <li>左侧 6..42 是组标题「原版格式」/「山海样式」；</li>
 *   <li>10 个行框（y=36..214，宽 250）<b>一个像素都没动</b>；「加/删/翻页」仍在 y=222；</li>
 *   <li>「接受/取消」按任务书下移到 y=278（278..296）。</li>
 * </ul>
 * 坐标的<b>唯一真源</b>在 {@link ShanhaiTokenLibrary#CHIPS}（探针直接读它做重叠断言）。
 */
final class ShanhaiTokenPicker {

    /** 编辑器提供给选择器的两件事（查询"插到哪个框"）。 */
    interface FieldSource {
        /** 当前焦点框；没有焦点 ⇒ 最后点过的那个；都没有 ⇒ {@code null}。 */
        ShanhaiTokenField target();

        /** 这个框是屏幕上第几行（1 基）；认不出来给 0（只用于提示文案）。 */
        int rowOf(ShanhaiTokenField field);
    }

    // ---- 文案：走 lang，取不到就用内置中文（绝不把 key 原样显示给玩家） ----
    private static final String KEY_HINT = "shanhai.gui.lore_editor.family_hint";
    private static final String ZH_HINT = "§8同一行只能选一套：§7原版色码§8 或 §7&$样式码§8，不能混用";
    private static final String KEY_IDLE = "shanhai.gui.lore_editor.idle";
    private static final String ZH_IDLE = "§8上排＝原版格式（色码/格式码）· 下排＝山海样式（&$…-）· 点一下插到光标处";
    private static final String KEY_OK = "shanhai.gui.lore_editor.inserted";
    private static final String ZH_OK = "已插入 %s · 第 %s 行 · 光标 %s/%s";
    private static final String KEY_LONG = "shanhai.gui.lore_editor.too_long";
    private static final String ZH_LONG = "已到每行 %s 字上限（现 %s 字），未插入";
    private static final String KEY_NO_TARGET = "shanhai.gui.lore_editor.no_target";
    private static final String ZH_NO_TARGET = "先点一下要编辑的那一行，再点这里的按钮";

    private final FieldSource source;
    private final int maxChars;
    private final LabelWidget status;
    private final String idleText;

    /**
     * @param host     挂到哪个控件组（就是那个多行编辑器本身）
     * @param source   插到哪个框
     * @param maxChars 每行上限（{@code ShanhaiRealityItemTable.MAX_LORE_CHARS}）
     */
    ShanhaiTokenPicker(WidgetGroup host, FieldSource source, int maxChars) {
        this.source = source;
        this.maxChars = maxChars;
        this.idleText = tr(KEY_IDLE, ZH_IDLE);

        // ---- 组标题（左边 6..42 那一条）----
        host.addWidget(new LabelWidget(ShanhaiTokenLibrary.GROUP_A_LABEL_X,
                ShanhaiTokenLibrary.GROUP_A_LABEL_Y, "§7原版格式"));
        host.addWidget(new LabelWidget(ShanhaiTokenLibrary.GROUP_B_LABEL_X,
                ShanhaiTokenLibrary.GROUP_B_LABEL_Y, "§7山海样式"));

        // ---- 39 个按钮（坐标全部来自 ShanhaiTokenLibrary.CHIPS）----
        for (ShanhaiTokenLibrary.Chip chip : ShanhaiTokenLibrary.CHIPS) {
            host.addWidget(new ChipWidget(chip, paletteOf(chip), tooltipOf(chip), () -> insert(chip)));
        }

        // ---- 两行小字：y=18 混用警告（常驻），y=27 状态（初始=用法）----
        host.addWidget(new LabelWidget(6, 18, tr(KEY_HINT, ZH_HINT)));
        this.status = new LabelWidget(6, 27, this.idleText);
        host.addWidget(this.status);
    }

    // ------------------------------------------------------------------ 点击 → 插入

    private void insert(ShanhaiTokenLibrary.Chip chip) {
        if (chip == null) {
            return;
        }
        final ShanhaiTokenField field = this.source == null ? null : this.source.target();
        if (field == null) {
            status("§c" + tr(KEY_NO_TARGET, ZH_NO_TARGET));
            return;
        }
        final ShanhaiTokenLibrary.Insert result = field.insertAtCursor(chip.token(), this.maxChars);
        final int row = this.source.rowOf(field);
        switch (result.status()) {
            case OK -> status("§a" + tr(KEY_OK, ZH_OK, ShanhaiTokenLibrary.describe(chip),
                    String.valueOf(row), String.valueOf(result.cursor()),
                    String.valueOf(result.length())));
            case TOO_LONG -> status("§c" + tr(KEY_LONG, ZH_LONG,
                    String.valueOf(this.maxChars), String.valueOf(result.length())));
            case EMPTY_TOKEN -> status("§c" + ShanhaiTokenLibrary.describe(chip));
        }
        // 日志：实机验收时能直接 grep（§ 在日志里看不见，用 visible() 换成 #）
        ShanhaiMod.LOGGER.info("{} lore_token_insert token={} row={} status={} cursor={} length={}",
                ShanhaiRealityItemTable.PREFIX, ShanhaiTokenLibrary.visible(chip.token()), row,
                result.status(), result.cursor(), result.length());
    }

    private void status(String text) {
        if (this.status != null) {
            this.status.setText(text);
        }
    }

    /** 复位成"用法"那一行（编辑器打开时调一次）。 */
    void resetStatus() {
        status(this.idleText);
    }

    // ------------------------------------------------------------------ 按钮本身

    /** 色板预览（只有 山海样式 有；拿不到就返回 null ⇒ 只画字）。 */
    private static int[] paletteOf(ShanhaiTokenLibrary.Chip chip) {
        if (chip.group() != ShanhaiTokenLibrary.Group.STYLE) {
            return null;
        }
        final String token = chip.token();           // &$neon-
        if (!token.startsWith("&$") || !token.endsWith("-")) {
            return null;
        }
        final ShanhaiTextPalette.Style style =
                ShanhaiTextPalette.get(token.substring(2, token.length() - 1));
        return style == null ? null : style.rgb();
    }

    private static Component tooltipOf(ShanhaiTokenLibrary.Chip chip) {
        final StringBuilder sb = new StringBuilder("§7");
        switch (chip.group()) {
            case VANILLA_COLOR -> sb.append("原版颜色 ").append(chip.zh());
            case VANILLA_FORMAT -> sb.append("原版格式 ").append(chip.zh());
            case STYLE -> sb.append("山海样式 ").append(chip.zh());
            case RESTORE -> sb.append(chip.zh());
        }
        sb.append('\n').append("§8点一下插到「最后点过的那个框」的光标处")
                .append('\n').append("§8（与另一套码不能写在同一行）");
        return Component.literal(sb.toString());
    }

    /**
     * 一个按钮：边框 ＋ 底色 ＋（色块 / 色板三段 / 两个字）＋ hover tooltip。
     *
     * <p>🔴 只在客户端被绘制 ⇒ 这里出现 {@link Minecraft} / {@link GuiGraphics} 与既有
     * {@code ShanhaiCreativeWidgets} 的写法一致（服务端那份控件树只对齐容器，方法体永不执行）。
     */
    static final class ChipWidget extends Widget {

        private static final int EDGE = 0xFF8A8A8A;
        private static final int EDGE_HOVER = 0xFFFFE082;
        private static final int BG = 0xFF3A3A3A;
        private static final int BG_HOVER = 0xFF4A4A4A;
        private static final int TEXT = 0xFFF0F0F0;

        private final ShanhaiTokenLibrary.Chip chip;
        private final int[] palette;
        private final Component tooltip;
        private final Runnable onClick;

        ChipWidget(ShanhaiTokenLibrary.Chip chip, int[] palette, Component tooltip, Runnable onClick) {
            super(chip.x(), chip.y(), chip.w(), chip.h());
            this.chip = chip;
            this.palette = palette;
            this.tooltip = tooltip;
            this.onClick = onClick;
        }

        @Override
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            final int x = getPositionX();
            final int y = getPositionY();
            final int w = getSizeWidth();
            final int h = getSizeHeight();
            final boolean hover = isMouseOverElement(mouseX, mouseY);
            graphics.fill(x, y, x + w, y + h, hover ? EDGE_HOVER : EDGE);
            graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, hover ? BG_HOVER : BG);

            final int ix = x + 1;
            final int iy = y + 1;
            final int iw = w - 2;
            final int ih = h - 2;
            if (iw <= 0 || ih <= 0) {
                return;
            }
            if (chip.group() == ShanhaiTokenLibrary.Group.VANILLA_COLOR) {
                // 16 色：整块填真色 ⇒ 玩家"一眼看出是什么颜色"
                graphics.fill(ix, iy, ix + iw, iy + ih, chip.rgb());
            } else if (this.palette != null && this.palette.length > 0) {
                // 山海样式：三段竖条暗示这套色板（首 / 中 / 尾）
                final int bw = Math.max(1, iw / 3);
                graphics.fill(ix, iy, ix + bw, iy + ih, 0xFF000000 | this.palette[0]);
                graphics.fill(ix + bw, iy, ix + iw - bw, iy + ih,
                        0xFF000000 | this.palette[this.palette.length / 2]);
                graphics.fill(ix + iw - bw, iy, ix + iw, iy + ih,
                        0xFF000000 | this.palette[this.palette.length - 1]);
            }

            final String label = chip.label();
            if (label != null && !label.isEmpty()) {
                final Font font = Minecraft.getInstance().font;
                final int textWidth = font.width(label);
                final int tx = x + Math.max(1, (w - textWidth) / 2);
                final int ty = y + (h - 8) / 2;
                if (this.palette != null) {
                    // 色板底上写字：垫一条半透明暗带保证可读
                    graphics.fill(ix, iy, ix + iw, iy + ih, 0x77000000);
                }
                graphics.drawString(font, label, tx, ty, TEXT, false);
            }
        }

        @Override
        public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            // ⚠️ 先清再填（比"只在 hover 时加"更保险：否则鼠标离开后 tooltipTexts 里那一条会残留）
            tooltipTexts.clear();
            if (this.tooltip != null && isMouseOverElement(mouseX, mouseY)) {
                tooltipTexts.add(this.tooltip);
            }
            super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && isMouseOverElement(mouseX, mouseY) && this.onClick != null) {
                playButtonClickSound();
                this.onClick.run();
                return true;
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ lang

    /**
     * 取一条 lang；<b>取不到就用内置中文</b>（fallback 见调用处的 {@code ZH_*}）。
     *
     * <p>为什么不用 {@code Component.translatable(...).getString()}：那样在只有 {@code zh_cn.json}、
     * 而客户端是英文的环境里会把 <b>raw key</b> 直接画到屏幕上。这里用
     * {@code Language#getOrDefault(key, fallback)} ⇒ 任何一种情况下玩家看到的都是人话。
     */
    private static String tr(String key, String zhFallback, Object... args) {
        String template = zhFallback;
        try {
            final String fromLang = net.minecraft.locale.Language.getInstance().getOrDefault(key, "");
            if (fromLang != null && !fromLang.isEmpty()) {
                template = fromLang;
            }
        } catch (Throwable ignored) {
            // 语言文件不可用 ⇒ 用内置中文（这条路径不会让界面崩）
        }
        if (args == null || args.length == 0) {
            return template;
        }
        try {
            return String.format(template, args);
        } catch (RuntimeException ignored) {
            return template;
        }
    }
}
