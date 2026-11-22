package com.shanhai.common.reality;

import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;

import java.util.List;
import java.util.function.Supplier;

/**
 * 「现实流体」面板/选择器用的两个小控件：<b>流体格</b>（可点）与<b>流体预览框</b>（不可点）。
 *
 * <h2>🔴 为什么流体格不能复用物品格（{@link ShanhaiCreativeWidgets.IconCell}）</h2>
 * 用户明确要求：「流体格子要画<b>流体自己的 still 贴图 ＋ tint 颜色</b>，别用物品图标顶替」。
 * 物品格只会 {@code DrawerHelper.drawItemStack(...)}，画不了"贴图 × 颜色"。
 * ⇒ 本类照 {@code ShanhaiIOWidget#drawFluidSprite}（配方编辑器里已经跑通的写法）画：
 * {@code Minecraft.getInstance().getTextureAtlas(BLOCK_ATLAS)} 那条路要求贴图在方块图集里，
 * 而这里的候选贴图有些来自没建方块形态的流体，所以改用 {@code GuiGraphics#blit(ResourceLocation,…)}
 * <b>直绑贴图</b> —— 任何 {@code assets/<ns>/textures/<path>.png} 都能画，
 * 再用 {@code RenderSystem.setShaderColor(...)} 乘上 tint。
 *
 * <h2>🔴 这些控件只在【客户端】被绘制/点击</h2>
 * 与 {@link ShanhaiCreativeWidgets} 同一条口径：服务端那份控件树只用于容器对齐，
 * {@code drawInBackground}/{@code mouseClicked} 的方法体在服务端永不执行
 * （JVM 惰性解析 ⇒ 这里出现 {@link GuiGraphics} 不会让专服出事）。
 */
final class ShanhaiFluidWidgets {

    private ShanhaiFluidWidgets() {}

    /** 格子的边长（与物品格一致：18px，含 1px 边框）。 */
    static final int SIZE = 18;

    // ------------------------------------------------------------------ 绘制

    /**
     * 把一张流体 still 贴图画进 {@code size×size} 的方框里，并乘上 tint。
     *
     * <h2>🔴 为什么先走方块图集（sprite），而不是直接 blit 文件</h2>
     * 原版水的 {@code block/water_still.png} 是 <b>16×512 的动画条</b>（32 帧叠在一张图里）。
     * 直接 {@code blit(tex,…,textureWidth=16,textureHeight=16)} 会把整条 32 帧压进 16×16 ⇒
     * 看上去像一道"竖着的抹痕"。走方块图集拿 {@code TextureAtlasSprite} 才是正确做法：
     * UV 由图集给出、动画逐帧推进、与 JEI / 配方编辑器里的流体图标<b>同一个来源</b>
     * （本工程既有写法见 {@code ShanhaiIOWidget#drawFluidSprite}）。
     * 拿不到 sprite（例如有人手改 JSON 写了一张不在图集里的图）⇒ 退化为"直接 blit"，
     * 再不行才退化为 tint 纯色块 —— <b>绝不静默画成空白</b>。
     *
     * @param still 贴图路径（{@code null} ⇒ 只画 tint 纯色块）
     * @param tint  ARGB（{@code 0xFFFFFFFF} = 原样）
     */
    static void drawFluid(GuiGraphics graphics, ResourceLocation still, int tint, int x, int y, int size) {
        final float r = ((tint >> 16) & 0xFF) / 255f;
        final float g = ((tint >> 8) & 0xFF) / 255f;
        final float b = (tint & 0xFF) / 255f;
        if (still != null) {
            // ---- ① 方块图集里的 sprite（正常路径；动画/UV 都对）----
            try {
                final net.minecraft.client.renderer.texture.TextureAtlasSprite sprite =
                        Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(still);
                if (sprite != null) {
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();
                    RenderSystem.setShaderColor(r, g, b, 1f);
                    graphics.blit(x, y, 0, size, size, sprite);
                    RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                    RenderSystem.disableBlend();
                    return;
                }
            } catch (Throwable ignored) {
                // 不是图集贴图 / 驱动异常 ⇒ 走到下面那条
            }
            // ---- ② 直绑文件（不在图集里的手改贴图）----
            try {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.setShaderColor(r, g, b, 1f);
                graphics.blit(still, x, y, 0f, 0f, size, size, 16, 16);
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                RenderSystem.disableBlend();
                return;
            } catch (Throwable ignored) {
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            }
        }
        // ---- ③ 兜底：tint 纯色块（绝不静默画空白）----
        graphics.fill(x, y, x + size, y + size, 0xFF000000 | (tint & 0xFFFFFF));
    }

    // ------------------------------------------------------------------ 可点格子

    /** 通用"流体格"：still 贴图 × tint ＋ 选中高亮 ＋ 变暗 ＋ tooltip ＋ 点击。 */
    static final class FluidCell extends Widget {

        private static final int FRAME_IDLE = 0xFF6E6E6E;
        private static final int FRAME_SEL = 0xFFFFD54A;
        private static final int BG = 0xFF2B2B2B;

        private Supplier<ResourceLocation> stillSupplier = () -> ShanhaiRealityFluidTable.stillTexture(0);
        private Supplier<Integer> tintSupplier = () -> ShanhaiRealityFluidTable.DEFAULT_TINT;
        private Supplier<List<Component>> tooltipSupplier;
        private Runnable onClick;
        private boolean selected;
        private boolean dim;

        FluidCell(int x, int y) {
            super(x, y, SIZE, SIZE);
        }

        /** 这一格画哪张贴图（每次绘制现取 ⇒ 改表立刻换图）。 */
        FluidCell still(Supplier<ResourceLocation> supplier) {
            this.stillSupplier = supplier;
            return this;
        }

        /** 这一格乘什么颜色（同上，每次现取）。 */
        FluidCell tint(Supplier<Integer> supplier) {
            this.tintSupplier = supplier;
            return this;
        }

        FluidCell tooltip(Supplier<List<Component>> supplier) {
            this.tooltipSupplier = supplier;
            return this;
        }

        FluidCell onClick(Runnable action) {
            this.onClick = action;
            return this;
        }

        FluidCell selected(boolean value) {
            this.selected = value;
            return this;
        }

        /** 变暗（"这一格还没填名字"的视觉表达）。 */
        FluidCell dim(boolean value) {
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
            final ResourceLocation still = this.stillSupplier == null ? null : this.stillSupplier.get();
            final int tint = this.tintSupplier == null ? ShanhaiRealityFluidTable.DEFAULT_TINT
                    : this.tintSupplier.get();
            drawFluid(graphics, still, tint, x + 1, y + 1, SIZE - 2);
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

    // ------------------------------------------------------------------ 颜色按钮

    /** 一个纯色块按钮（预设颜色用）：画真色 ＋ hover 边框 ＋ tooltip ＋ 点击。 */
    static final class ColorButton extends Widget {

        private static final int EDGE = 0xFF8A8A8A;
        private static final int EDGE_HOVER = 0xFFFFE082;

        private final int rgb;
        private final Runnable onClick;

        ColorButton(int x, int y, int w, int h, int rgb, Runnable onClick) {
            super(x, y, w, h);
            this.rgb = rgb;
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
            graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF000000 | (this.rgb & 0xFFFFFF));
        }

        @Override
        public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            tooltipTexts.clear();
            if (isMouseOverElement(mouseX, mouseY)) {
                tooltipTexts.add(Component.literal("§f" + ShanhaiRealityFluidTable.tintHex(this.rgb)
                        + "§8 · 点一下套用"));
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

    // ------------------------------------------------------------------ 纯预览

    /** 纯预览框（不响应点击）：给"当前长什么样"用。 */
    static final class FluidSwatch extends Widget {

        private Supplier<ResourceLocation> stillSupplier = () -> ShanhaiRealityFluidTable.stillTexture(0);
        private Supplier<Integer> tintSupplier = () -> ShanhaiRealityFluidTable.DEFAULT_TINT;

        FluidSwatch(int x, int y, int size) {
            super(x, y, size, size);
        }

        FluidSwatch still(Supplier<ResourceLocation> supplier) {
            this.stillSupplier = supplier;
            return this;
        }

        FluidSwatch tint(Supplier<Integer> supplier) {
            this.tintSupplier = supplier;
            return this;
        }

        @Override
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            final int x = getPositionX();
            final int y = getPositionY();
            final int w = getSizeWidth();
            final int h = getSizeHeight();
            graphics.fill(x, y, x + w, y + h, 0xFF35D0C0);
            graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF2B2B2B);
            final ResourceLocation still = this.stillSupplier == null ? null : this.stillSupplier.get();
            final int tint = this.tintSupplier == null ? ShanhaiRealityFluidTable.DEFAULT_TINT
                    : this.tintSupplier.get();
            drawFluid(graphics, still, tint, x + 1, y + 1, Math.max(1, Math.min(w, h) - 2));
        }
    }
}
