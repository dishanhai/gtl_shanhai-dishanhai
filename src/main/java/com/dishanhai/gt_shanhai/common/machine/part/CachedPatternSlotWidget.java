package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.items.misc.WrappedGenericStack;

import com.extendedae_plus.content.ClientPatternHighlightStore;
import com.extendedae_plus.util.GuiUtil;
import com.extendedae_plus.util.NumberFormatUtil;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import com.lowdragmc.lowdraglib.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib.side.item.IItemTransfer;
import com.lowdragmc.lowdraglib.utils.Position;
import com.lowdragmc.lowdraglib.utils.Size;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.gtlcore.gtlcore.integration.ae2.widget.AEPatternViewExtendSlotWidget;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 星律样板槽。覆写完整绘制路径，避免第三方基类 Mixin 每帧重复解码样板。
 */
public final class CachedPatternSlotWidget extends AEPatternViewExtendSlotWidget {

    private static final int WARNING_BORDER_WIDTH = 2;
    private static final int WARNING_BORDER_COLOR = 0xFFFF3030;

    private ItemStack cachedPatternStack;
    private CompoundTag cachedPatternTag;
    private ItemStack cachedDisplayStack;
    private String cachedPatternOutputText = "";
    private AEKey cachedPatternOutputKey;
    private final BooleanSupplier warningSupplier;
    private final Supplier<Level> levelSupplier;
    private boolean focused;

    public CachedPatternSlotWidget(IItemTransfer inventory, int slotIndex, int xPosition, int yPosition) {
        this(inventory, slotIndex, xPosition, yPosition, () -> false, () -> null);
    }

    public CachedPatternSlotWidget(IItemTransfer inventory, int slotIndex, int xPosition, int yPosition,
            BooleanSupplier warningSupplier) {
        this(inventory, slotIndex, xPosition, yPosition, warningSupplier, () -> null);
    }

    public CachedPatternSlotWidget(IItemTransfer inventory, int slotIndex, int xPosition, int yPosition,
            Supplier<Level> levelSupplier) {
        this(inventory, slotIndex, xPosition, yPosition, () -> false, levelSupplier);
    }

    public CachedPatternSlotWidget(IItemTransfer inventory, int slotIndex, int xPosition, int yPosition,
            BooleanSupplier warningSupplier, Supplier<Level> levelSupplier) {
        super(inventory, slotIndex, xPosition, yPosition);
        this.warningSupplier = warningSupplier == null ? () -> false : warningSupplier;
        this.levelSupplier = levelSupplier == null ? () -> null : levelSupplier;
    }

    public void invalidatePatternCache() {
        cachedPatternStack = null;
        cachedPatternTag = null;
        cachedDisplayStack = null;
        cachedPatternOutputText = "";
        cachedPatternOutputKey = null;
    }

    public void setFocused(boolean focused) { this.focused = focused; }

    @Override
    public ItemStack getRealStack(ItemStack stack) {
        refreshPatternCache(stack);
        return cachedDisplayStack;
    }

    @Override
    protected void drawBackgroundTexture(@NotNull GuiGraphics graphics, int mouseX, int mouseY) {
        Position pos = getPosition();
        Size size = getSize();
        if (getHandler() != null && getHandler().hasItem()) {
            if (occupiedTexture != null) {
                occupiedTexture.draw(graphics, mouseX, mouseY, pos.x, pos.y, size.width, size.height);
            }
        } else if (backgroundTexture != null) {
            backgroundTexture.draw(graphics, mouseX, mouseY, pos.x, pos.y, size.width, size.height);
        }
        if (hoverTexture != null && isMouseOverElement(mouseX, mouseY)) {
            hoverTexture.draw(graphics, mouseX, mouseY, pos.x, pos.y, size.width, size.height);
        }
    }

    @Override
    public void drawInBackground(@Nonnull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        drawBackgroundTexture(graphics, mouseX, mouseY);
        Position pos = getPosition();
        if (slotReference != null) {
            ItemStack itemStack = getRealStack(slotReference.getItem());
            ModularUIGuiContainer modularGui = gui == null ? null : gui.getModularUIGui();
            if (itemStack.isEmpty() && modularGui != null && modularGui.getQuickCrafting()
                    && modularGui.getQuickCraftSlots().contains(slotReference)) {
                int splitSize = modularGui.getQuickCraftSlots().size();
                itemStack = gui.getModularUIContainer().getCarried();
                if (!itemStack.isEmpty() && splitSize > 1
                        && AbstractContainerMenu.canItemQuickReplace(slotReference, itemStack, true)) {
                    itemStack = itemStack.copy();
                    itemStack.setCount(AbstractContainerMenu.getQuickCraftPlaceCount(
                            modularGui.getQuickCraftSlots(), modularGui.dragSplittingLimit, itemStack));
                    int maxCount = Math.min(itemStack.getMaxStackSize(), slotReference.getMaxStackSize(itemStack));
                    if (itemStack.getCount() > maxCount) {
                        itemStack.setCount(maxCount);
                    }
                }
            }
            if (!itemStack.isEmpty()) {
                DrawerHelper.drawItemStack(graphics, itemStack, pos.x + 1, pos.y + 1, -1, null);
            }
        }

        if (warningSupplier.getAsBoolean()) {
            gtShanhai$drawWarningBorder(graphics, pos.x, pos.y, getSize().width, getSize().height);
        }
        drawOverlay(graphics, mouseX, mouseY, partialTicks);
        if (drawHoverOverlay && isMouseOverElement(mouseX, mouseY) && getHoverElement(mouseX, mouseY) == this) {
            RenderSystem.colorMask(true, true, true, false);
            DrawerHelper.drawSolidRect(graphics, pos.x + 1, pos.y + 1, 16, 16, -2130706433);
            RenderSystem.colorMask(true, true, true, true);
        }

        if (!cachedPatternOutputText.isEmpty()) {
            graphics.pose().pushPose();
            graphics.pose().translate(0.0f, 0.0f, 300.0f);
            RenderSystem.disableDepthTest();
            DrawerHelper.drawStringFixedCorner(graphics, cachedPatternOutputText,
                    pos.x + getSize().width, pos.y + getSize().height, -1, true, 0.75f);
            RenderSystem.enableDepthTest();
            graphics.pose().popPose();
        }
        if (cachedPatternOutputKey != null && ClientPatternHighlightStore.hasHighlight(cachedPatternOutputKey)) {
            GuiUtil.drawSlotRainbowHighlight(graphics, pos.x + 1, pos.y + 1);
        }
        if (focused) gtShanhai$drawFocusBorder(graphics, pos.x, pos.y, getSize().width, getSize().height);
    }

    /** 彩虹沿邊框流動；減少動態效果時保留靜態彩虹，焦點不依賴輸出 key。 */
    public static void gtShanhai$drawFocusBorder(GuiGraphics graphics, int x, int y, int width, int height) {
        float phase = com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings.reduceMotion()
                ? 0f : (System.currentTimeMillis() % 3600L) / 3600f;
        int perimeter = 2 * (width + height);
        for (int p = 0; p < perimeter; p++) {
            int color = 0xFF000000 | net.minecraft.util.Mth.hsvToRgb((phase + (float) p / perimeter) % 1f, 0.85f, 1f);
            if (p < width) graphics.fill(x + p, y, x + p + 1, y + 1, color);
            else if (p < width + height) graphics.fill(x + width - 1, y + p - width, x + width, y + p - width + 1, color);
            else if (p < 2 * width + height) graphics.fill(x + 2 * width + height - p - 1, y + height - 1,
                    x + 2 * width + height - p, y + height, color);
            else graphics.fill(x, y + perimeter - p - 1, x + 1, y + perimeter - p, color);
        }
    }

    private static void gtShanhai$drawWarningBorder(GuiGraphics graphics, int x, int y, int width, int height) {
        DrawerHelper.drawSolidRect(graphics, x, y, width, WARNING_BORDER_WIDTH, WARNING_BORDER_COLOR);
        DrawerHelper.drawSolidRect(graphics, x, y + height - WARNING_BORDER_WIDTH,
                width, WARNING_BORDER_WIDTH, WARNING_BORDER_COLOR);
        DrawerHelper.drawSolidRect(graphics, x, y, WARNING_BORDER_WIDTH, height, WARNING_BORDER_COLOR);
        DrawerHelper.drawSolidRect(graphics, x + width - WARNING_BORDER_WIDTH, y,
                WARNING_BORDER_WIDTH, height, WARNING_BORDER_COLOR);
    }

    private void refreshPatternCache(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (cachedPatternStack == stack && cachedPatternTag == tag && cachedDisplayStack != null) {
            return;
        }

        cachedPatternStack = stack;
        cachedPatternTag = tag;
        cachedDisplayStack = stack;
        cachedPatternOutputText = "";
        cachedPatternOutputKey = null;
        if (stack.isEmpty()) {
            return;
        }
        Level level = levelSupplier.get();
        if (level == null) {
            return;
        }

        IPatternDetails details = PatternDetailsHelper.decodePattern(stack, level, false);
        if (details == null || details.getOutputs().length == 0) {
            return;
        }
        GenericStack output = details.getOutputs()[0];
        cachedPatternOutputKey = output.what();
        if (cachedPatternOutputKey instanceof AEItemKey itemKey) {
            cachedDisplayStack = itemKey.toStack();
        } else {
            cachedDisplayStack = WrappedGenericStack.wrap(cachedPatternOutputKey, 0L);
        }

        long amount = output.amount();
        long amountPerUnit = cachedPatternOutputKey.getAmountPerUnit();
        if (amount > 0 && amountPerUnit > 0) {
            double units = (double) amount / amountPerUnit;
            if (units > 0.0d) {
                cachedPatternOutputText = NumberFormatUtil.formatNumberWithDecimal(units)
                        + (amountPerUnit > 1 ? "B" : "");
            }
        }
    }
}
