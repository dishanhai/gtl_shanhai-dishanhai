package com.dishanhai.gt_shanhai.client.shop;

import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.common.gui.JeiTooltip;
import mezz.jei.gui.elements.GuiIconToggleButton;
import mezz.jei.gui.input.UserInput;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class ShopJeiModeButton extends GuiIconToggleButton {

    private static final IDrawable FALLBACK_ICON = new IDrawable() {
        @Override
        public int getWidth() {
            return 16;
        }

        @Override
        public int getHeight() {
            return 16;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y) {
            graphics.renderItem(new ItemStack(Items.EMERALD), x, y);
        }
    };
    private static final IDrawable FALLBACK_ON_ICON = new IDrawable() {
        @Override
        public int getWidth() {
            return 16;
        }

        @Override
        public int getHeight() {
            return 16;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y) {
            graphics.renderItem(new ItemStack(Items.CHEST), x, y);
        }
    };

    public ShopJeiModeButton() {
        super(offIcon(), onIcon());
    }

    @Override
    protected void getTooltips(JeiTooltip tooltip) {
        tooltip.add(Component.translatable("tooltip.gt_shanhai.jei.shop_mode"));
    }

    @Override
    protected boolean isIconToggledOn() {
        return ClientShopJeiMode.isEnabled();
    }

    @Override
    protected boolean onMouseClicked(UserInput input) {
        if (!input.isSimulate()) ClientShopJeiMode.toggle();
        return true;
    }

    private static IDrawable offIcon() {
        IDrawable icon = ClientShopJeiMode.buttonOffIcon();
        return icon == null ? FALLBACK_ICON : icon;
    }

    private static IDrawable onIcon() {
        IDrawable icon = ClientShopJeiMode.buttonOnIcon();
        return icon == null ? FALLBACK_ON_ICON : icon;
    }
}
