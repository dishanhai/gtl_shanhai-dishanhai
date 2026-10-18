package com.shanhai.common.reality;

import com.lowdragmc.lowdraglib.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 「自己新增物品」面板用的几个小控件（格子 / 文字按钮 / 预览框）。
 *
 * <h2>为什么自己写，而不是用 LDLib 的 ButtonWidget</h2>
 * <ol>
 *   <li>按钮文字：LDLib 的 {@code ButtonWidget} <b>没有 setText</b>，既有写法是"按钮铺满 + 上面盖一个
 *       {@code LabelWidget}"（本工程既有留档：{@code ParallelOverrideConfigurator:278-282}）。
 *       一屏 8 个按钮 ⇒ 多 8 个 Label 控件，且文字要动态变（稀有度按钮）⇒ 自己画更省。</li>
 *   <li>格子：每次渲染都要现读表（图标可能刚被改）⇒ 用 {@link Supplier} 取 {@link ItemStack}，
 *       而不是建控件时快照一份。</li>
 *   <li>点一下就要能立刻做事（客户端本地），不需要服务端参与 ⇒ 直接覆写 {@code mouseClicked}。</li>
 * </ol>
 *
 * <h2>🔴 这些控件全都只在【客户端】被绘制/点击</h2>
 * {@code drawInBackground} / {@code mouseClicked} 只由客户端界面调用；服务端那份控件树
 * 只用于容器对齐（与配方编辑器同一套路）。因此这里出现 {@link Minecraft} / {@link GuiGraphics}
 * 不会让专用服务端出问题（JVM 惰性解析，方法体在服务端永远不执行）。
 */
final class ShanhaiCreativeWidgets {

    private ShanhaiCreativeWidgets() {}

    /** 通用"物品格"：图标 + 选中高亮 + tooltip + 点击。 */
    static final class IconCell extends Widget {

        static final int SIZE = 18;

        private static final int FRAME_IDLE = 0xFF6E6E6E;
        private static final int FRAME_SEL = 0xFFFFD54A;
        private static final int BG = 0xFF2B2B2B;

        private Supplier<ItemStack> stackSupplier = () -> ItemStack.EMPTY;
        private Supplier<List<Component>> tooltipSupplier;
        private Runnable onClick;
        private boolean selected;
        private boolean dim;

        IconCell(int x, int y) {
            super(x, y, SIZE, SIZE);
        }

        IconCell stack(Supplier<ItemStack> supplier) {
            this.stackSupplier = supplier;
            return this;
        }

        IconCell tooltip(Supplier<List<Component>> supplier) {
            this.tooltipSupplier = supplier;
            return this;
        }

        IconCell onClick(Runnable action) {
            this.onClick = action;
            return this;
        }

        IconCell selected(boolean value) {
            this.selected = value;
            return this;
        }

        /** 变暗（用来表示"这一格还没定义"）。 */
        IconCell dim(boolean value) {
            this.dim = value;
            return this;
        }

        @Override
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            final int x = getPositionX();
            final int y = getPositionY();
            final boolean hover = isMouseOverElement(mouseX, mouseY);
            graphics.fill(x, y, x + SIZE, y + SIZE, selected ? FRAME_SEL : (hover ? 0xFFBFBFBF : FRAME_IDLE));
            graphics.fill(x + 1, y + 1, x + SIZE - 1, y + SIZE - 1, BG);
            final ItemStack stack = stackSupplier == null ? ItemStack.EMPTY : stackSupplier.get();
            if (stack != null && !stack.isEmpty()) {
                // 与既有 IO 格同一写法（先置白 shader、抬 z）——见 ShanhaiQuerySlotWidget 的注释
                DrawerHelper.drawItemStack(graphics, stack, x + 1, y + 1, -1, null);
            } else {
                graphics.fill(x + 5, y + 5, x + SIZE - 5, y + SIZE - 5, 0xFF4A4A4A);
            }
            if (dim) {
                graphics.fill(x + 1, y + 1, x + SIZE - 1, y + SIZE - 1, 0x80202020);
            }
        }

        @Override
        public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            if (tooltipSupplier != null && isMouseOverElement(mouseX, mouseY)) {
                tooltipTexts.clear();
                final List<Component> lines = tooltipSupplier.get();
                if (lines != null) {
                    tooltipTexts.addAll(lines);
                }
            }
            super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && isMouseOverElement(mouseX, mouseY) && onClick != null) {
                playButtonClickSound();
                onClick.run();
                return true;
            }
            return false;
        }
    }

    /** 文字按钮：边框 + 底色 + 一行字（字由 {@link Supplier} 每帧现取）。 */
    static final class TextButton extends Widget {

        private static final int EDGE = 0xFF8A8A8A;
        private static final int EDGE_HOVER = 0xFFFFE082;
        private static final int BG = 0xFF3A3A3A;
        private static final int BG_HOVER = 0xFF4A4A4A;

        /** 🆕 2026-10-17：页签用（默认 false ⇒ 既有按钮的渲染一个像素都不变）。 */
        private static final int EDGE_SELECTED = 0xFFFFD54A;

        private final Supplier<String> text;
        private Runnable onClick;
        private boolean selected;

        TextButton(int x, int y, int w, int h, Supplier<String> text, Runnable onClick) {
            super(x, y, w, h);
            this.text = text;
            this.onClick = onClick;
        }

        TextButton onClick(Runnable action) {
            this.onClick = action;
            return this;
        }

        /**
         * 🆕 让这个按钮显示"我是当前选中的那个"（金色边框，与物品格 IconCell 同一个高亮色）。
         * <p>🔴 默认 {@code false} ⇒ 「新增」页上那些既有按钮的输出与改动前完全一致。
         */
        TextButton selected(boolean value) {
            this.selected = value;
            return this;
        }

        @Override
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            final int x = getPositionX();
            final int y = getPositionY();
            final int w = getSizeWidth();
            final int h = getSizeHeight();
            final boolean hover = isMouseOverElement(mouseX, mouseY);
            graphics.fill(x, y, x + w, y + h, selected ? EDGE_SELECTED : (hover ? EDGE_HOVER : EDGE));
            graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, hover ? BG_HOVER : BG);
            final String s = text == null ? "" : text.get();
            if (s != null && !s.isEmpty()) {
                graphics.drawString(Minecraft.getInstance().font, s, x + 3, y + (h - 8) / 2, 0xFFF0F0F0, false);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && isMouseOverElement(mouseX, mouseY) && onClick != null) {
                playButtonClickSound();
                onClick.run();
                return true;
            }
            return false;
        }
    }

    /** 纯预览框（不响应点击）：给"当前图标长什么样"用。 */
    static final class Preview extends Widget {

        private Supplier<ItemStack> stackSupplier = () -> ItemStack.EMPTY;

        Preview(int x, int y) {
            super(x, y, 18, 18);
        }

        Preview stack(Supplier<ItemStack> supplier) {
            this.stackSupplier = supplier;
            return this;
        }

        @Override
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            final int x = getPositionX();
            final int y = getPositionY();
            graphics.fill(x, y, x + 18, y + 18, 0xFF35D0C0);
            graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF2B2B2B);
            final ItemStack stack = stackSupplier == null ? ItemStack.EMPTY : stackSupplier.get();
            if (stack != null && !stack.isEmpty()) {
                DrawerHelper.drawItemStack(graphics, stack, x + 1, y + 1, -1, null);
            } else {
                graphics.fill(x + 5, y + 5, x + 13, y + 13, 0xFF4A4A4A);
            }
        }
    }

    /**
     * 🆕 2026-10-17：<b>一行"物品结果"</b>（图标 ＋ 两行字），给「修改」「删除」页的搜索结果列表用。
     *
     * <h2>为什么不用 LabelWidget ＋ IconCell 拼</h2>
     * 本工程踩过：{@code LabelWidget} 的 supplier <b>不逐帧重取</b>（见 {@code ShanhaiCreativeLoreEditor}
     * 里那两个用 {@code setText} 的页数标签）⇒ 列表一翻页文字就不跟着变。
     * 自绘控件每帧现取 supplier，翻页/过滤天然正确，也省掉"每行两个控件"的数目。
     *
     * <h2>文字裁剪</h2>
     * 用 {@code Font#plainSubstrByWidth} 按像素宽剪 ⇒ 中文名不会顶出格子右边（那会盖到右侧编辑区）。
     */
    static final class ItemRow extends Widget {

        static final int ROW_H = 20;

        private Supplier<ItemStack> stack = () -> ItemStack.EMPTY;
        /** 主行字（中文名；拿不到就由调用方给 id）。 */
        private Supplier<String> title = () -> "";
        /** 次行字（id / 状态后缀）。 */
        private Supplier<String> sub = () -> "";
        private Runnable onClick;
        private boolean selected;
        private boolean dim;

        ItemRow(int x, int y, int w) {
            super(x, y, w, ROW_H);
        }

        ItemRow stack(Supplier<ItemStack> supplier) {
            this.stack = supplier;
            return this;
        }

        ItemRow title(Supplier<String> supplier) {
            this.title = supplier;
            return this;
        }

        ItemRow sub(Supplier<String> supplier) {
            this.sub = supplier;
            return this;
        }

        ItemRow onClick(Runnable action) {
            this.onClick = action;
            return this;
        }

        ItemRow selected(boolean value) {
            this.selected = value;
            return this;
        }

        /** 变暗（"这一格不该被点"的视觉表达，例如 128 个现实物品壳）。 */
        ItemRow dim(boolean value) {
            this.dim = value;
            return this;
        }

        @Override
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            final int x = getPositionX();
            final int y = getPositionY();
            final int w = getSizeWidth();
            final boolean hover = isMouseOverElement(mouseX, mouseY);
            graphics.fill(x, y, x + w, y + ROW_H, selected ? 0xFFFFD54A : (hover ? 0xFFBFBFBF : 0xFF6E6E6E));
            graphics.fill(x + 1, y + 1, x + w - 1, y + ROW_H - 1, 0xFF2B2B2B);
            final ItemStack st = stack == null ? ItemStack.EMPTY : stack.get();
            if (st != null && !st.isEmpty()) {
                DrawerHelper.drawItemStack(graphics, st, x + 2, y + 2, -1, null);
            } else {
                graphics.fill(x + 2, y + 2, x + 18, y + 18, 0xFF4A4A4A);
            }
            final net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
            final int textW = Math.max(8, w - 24);
            final String main = title == null ? "" : trim(font, title.get(), textW);
            final String second = sub == null ? "" : trim(font, sub.get(), textW);
            if (!main.isEmpty()) {
                graphics.drawString(font, main, x + 21, y + 2, 0xFFF0F0F0, false);
            }
            if (!second.isEmpty()) {
                graphics.drawString(font, second, x + 21, y + 11, 0xFF9A9A9A, false);
            }
            if (dim) {
                graphics.fill(x + 1, y + 1, x + w - 1, y + ROW_H - 1, 0x80202020);
            }
        }

        private static String trim(net.minecraft.client.gui.Font font, String s, int width) {
            if (s == null || s.isEmpty()) {
                return "";
            }
            return font.width(s) <= width ? s : font.plainSubstrByWidth(s, width - font.width("…")) + "…";
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && isMouseOverElement(mouseX, mouseY) && onClick != null) {
                playButtonClickSound();
                onClick.run();
                return true;
            }
            return false;
        }
    }

    /** 一行提示文字（不换行、不裁剪 —— 所以调用方必须自己保证够短）。 */
    static List<Component> line(String text) {
        final List<Component> out = new ArrayList<>(1);
        out.add(Component.literal(text));
        return out;
    }
}
