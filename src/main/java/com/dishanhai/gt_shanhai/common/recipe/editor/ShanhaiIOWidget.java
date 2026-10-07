package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.lowdragmc.lowdraglib.gui.ingredient.IGhostIngredientTarget;
import com.lowdragmc.lowdraglib.gui.ingredient.Target;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.mojang.blaze3d.systems.RenderSystem;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

/** One graphical item or fluid cell. Drops are recorded, never consumed. */
public class ShanhaiIOWidget extends Widget implements IGhostIngredientTarget {

    public static final int FRAME_ITEM = 0xFF8B8B8B;
    public static final int FRAME_FLUID = 0xFF3A7BD5;
    public static final int CELL = 20;

    private ShanhaiIoTable table;
    private final IntSupplier indexSupplier;
    private final Runnable changed;
    private Runnable before = () -> {};
    private BooleanSupplier selected = () -> false;
    private DoubleSupplier accent = () -> 1d;
    private Runnable onSelect = () -> {};

    public ShanhaiIOWidget(ShanhaiIoTable table, IntSupplier indexSupplier,
                           int x, int y, Runnable changed) {
        super(x, y, CELL, CELL);
        this.table = table;
        this.indexSupplier = indexSupplier;
        this.changed = changed;
    }

    public void setSelection(BooleanSupplier selected, Runnable onSelect) {
        this.selected = selected == null ? () -> false : selected;
        this.onSelect = onSelect == null ? () -> {} : onSelect;
    }

    public void setAccent(DoubleSupplier accent) {
        this.accent = accent == null ? () -> 1d : accent;
    }

    public void setTable(ShanhaiIoTable table) {
        this.table = table;
    }

    public void setBeforeChange(Runnable before) {
        this.before = before == null ? () -> {} : before;
    }

    private ShanhaiIoTable.Cell cell() {
        return table == null ? null : table.cell(indexSupplier.getAsInt());
    }

    @Override
    public List<Target> getPhantomTargets(Object ingredient) {
        int discoveredIndex = indexSupplier.getAsInt();
        ShanhaiIoTable.Cell current = cell();
        if (current == null) return List.of();
        Object value = normalize(ingredient, current.itemKind);
        if (value == null) return List.of();
        return List.of(new Target() {
            @Override
            public Rect2i getArea() {
                return new Rect2i(getPositionX(), getPositionY(), CELL, CELL);
            }

            @Override
            public void accept(Object accepted) {
                applyClientDraft(accepted, discoveredIndex, current);
            }
        });
    }

    private void applyClientDraft(Object accepted, int discoveredIndex,
                                  ShanhaiIoTable.Cell discoveredCell) {
        ShanhaiIoTable.Cell target = table == null ? null : table.cell(discoveredIndex);
        if (target == null || target != discoveredCell) return;
        Object normalized = normalize(accepted, target.itemKind);
        if (!(normalized instanceof ItemStack) && !(normalized instanceof FluidStack)) return;
        before.run();
        if (normalized instanceof ItemStack stack) target.setItem(stack, stack.getCount());
        else target.setFluid((FluidStack) normalized);
        changed.run();
    }

    private void applyPicked(Object accepted, int discoveredIndex, ShanhaiIoTable.Cell discoveredCell) {
        ShanhaiIoTable.Cell target = table == null ? null : table.cell(discoveredIndex);
        if (target == null || target != discoveredCell) return;
        if (target.itemKind) {
            ItemStack stack = accepted instanceof ItemStack item ? item : ItemStack.EMPTY;
            if (sameItem(target.item, stack)) return;
            before.run();
            target.setItem(stack, stack.getCount());
        } else {
            FluidStack stack = accepted instanceof FluidStack fluid ? fluid : FluidStack.empty();
            if (sameFluid(target.fluid, stack)) return;
            before.run();
            target.setFluid(stack);
        }
        changed.run();
    }

    private static boolean sameItem(ItemStack left, ItemStack right) {
        if (left.isEmpty() && right.isEmpty()) return true;
        return ItemStack.isSameItemSameTags(left, right) && left.getCount() == right.getCount();
    }

    private static boolean sameFluid(FluidStack left, FluidStack right) {
        if (left.isEmpty() && right.isEmpty()) return true;
        if (left.isEmpty() || right.isEmpty()) return false;
        return left.getFluid() == right.getFluid()
                && left.getAmount() == right.getAmount()
                && java.util.Objects.equals(left.getTag(), right.getTag());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isMouseOverElement(mouseX, mouseY)) return false;
        if (button == 1) {
            ShanhaiIoTable.Cell current = cell();
            if (current == null) return true;
            int index = indexSupplier.getAsInt();
            if (current.itemKind) {
                ShanhaiRecipeStackPickerBridge.openItem(current.item.copy(),
                        stack -> applyPicked(stack, index, current));
            } else {
                ShanhaiRecipeStackPickerBridge.openFluid(current.fluid.copy(),
                        stack -> applyPicked(stack, index, current));
            }
            return true;
        }
        if (button == 0) {
            onSelect.run();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        ShanhaiIoTable.Cell current = cell();
        if (current == null) return;
        int x = getPositionX();
        int y = getPositionY();
        float strength = selected.getAsBoolean() ? (float) accent.getAsDouble() : 0f;
        if (strength > 0.02f) {
            int alpha = Math.min(255, Math.round(255f * strength));
            int inset = Math.round((1f - strength) * 3f);
            graphics.fill(x - 1 + inset, y - 1 + inset, x + CELL + 1 - inset, y + CELL + 1 - inset,
                    (alpha << 24) | 0x0069E8FF);
        }
        graphics.fill(x, y, x + CELL, y + CELL, current.itemKind ? FRAME_ITEM : FRAME_FLUID);
        graphics.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, 0xFF272A35);
        if (current.itemKind && !current.item.isEmpty()) {
            new ItemStackTexture(current.item).draw(graphics, mouseX, mouseY, x + 2, y + 2, 16, 16);
            graphics.renderItemDecorations(Minecraft.getInstance().font, current.item, x + 2, y + 2);
        } else if (!current.itemKind && !current.fluid.isEmpty()) {
            drawFluid(graphics, current.fluid, x + 2, y + 2);
            smallTextRight(graphics, compactCount(current.shownCount()), x + CELL - 2, y + CELL - 1);
        }
        if (notConsumed(current)) {
            drawNotConsumed(graphics, x + 2, y + 2);
        } else if (current.chance != 10000) {
            smallText(graphics, (current.chance * 100 / Math.max(1, current.maxChance)) + "%", x + 2, y + CELL - 1);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        ShanhaiIoTable.Cell current = cell();
        if (current == null || current.empty()) {
            setHoverTooltips(List.of(Component.literal("灰框物品 / 蓝框流体，JEI 拖入，右键打开选取器")));
        } else if (current.itemKind) {
            setHoverTooltips(List.of(current.item.getHoverName(),
                    Component.literal("数量 " + current.item.getCount()),
                    chanceTip(current),
                    Component.literal("右键打开选取器")));
        } else {
            setHoverTooltips(List.of(current.fluid.getDisplayName(),
                    Component.literal("数量 " + current.fluid.getAmount() + " mB"),
                    chanceTip(current),
                    Component.literal("右键打开选取器")));
        }
    }

    private boolean notConsumed(ShanhaiIoTable.Cell current) {
        if (current == null || current.chance != 0 || table == null) return false;
        int index = indexSupplier.getAsInt();
        if (index < 0) return false;
        ShanhaiIoTable.Kind kind = table.kindOf(index);
        return kind == ShanhaiIoTable.Kind.ITEM_IN || kind == ShanhaiIoTable.Kind.FLUID_IN;
    }

    private Component chanceTip(ShanhaiIoTable.Cell current) {
        if (notConsumed(current)) return Component.literal("不消耗").withStyle(ChatFormatting.RED);
        return Component.literal("概率 " + current.chance + "/" + current.maxChance);
    }

    /** Red 不 at half size, top-right of the icon. Same scale GTCEu uses for chance marks. */
    private static void drawNotConsumed(GuiGraphics graphics, int iconX, int iconY) {
        var font = Minecraft.getInstance().font;
        String mark = "不";
        graphics.pose().pushPose();
        graphics.pose().translate(iconX + 16, iconY, 400f);
        graphics.pose().scale(0.5f, 0.5f, 1f);
        graphics.drawString(font, mark, -font.width(mark), 0, 0xFFFF0000, true);
        graphics.pose().popPose();
    }

    static Object normalize(Object ingredient, boolean itemKind) {
        if (ingredient instanceof ITypedIngredient<?> typed) {
            try {
                ItemStack stack = typed.getItemStack().orElse(ItemStack.EMPTY);
                if (!stack.isEmpty()) ingredient = stack;
                else ingredient = typed.getIngredient();
            } catch (Throwable ignored) {
                ingredient = typed.getIngredient();
            }
        }
        if (itemKind) {
            if (ingredient instanceof ItemStack stack && !stack.isEmpty()) return stack;
            if (ingredient instanceof Ingredient recipeIngredient) {
                ItemStack[] stacks = recipeIngredient.getItems();
                return stacks.length == 0 ? null : stacks[0];
            }
            return null;
        }
        if (ingredient instanceof FluidStack fluid && !fluid.isEmpty()) return fluid;
        Object converted = com.lowdragmc.lowdraglib.gui.widget.PhantomFluidWidget
                .checkJEIIngredient(ingredient);
        return converted instanceof FluidStack fluid && !fluid.isEmpty() ? fluid : null;
    }

    private static void drawFluid(GuiGraphics graphics, FluidStack stack, int x, int y) {
        ResourceLocation still = null;
        int tint = 0xFFFFFFFF;
        try {
            IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(stack.getFluid());
            still = ext.getStillTexture();
            tint = ext.getTintColor();
        } catch (Throwable ignored) {
            // Fall back to a tinted block.
        }
        if (still == null) {
            graphics.fill(x, y, x + 16, y + 16, 0xC0000000 | (tint & 0x00FFFFFF));
            return;
        }
        try {
            TextureAtlasSprite sprite = Minecraft.getInstance()
                    .getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(still);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(((tint >> 16) & 0xFF) / 255f,
                    ((tint >> 8) & 0xFF) / 255f, (tint & 0xFF) / 255f, 1f);
            graphics.blit(x, y, 0, 16, 16, sprite);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.disableBlend();
        } catch (Throwable ignored) {
            graphics.fill(x, y, x + 16, y + 16, 0xC0000000 | (tint & 0x00FFFFFF));
        }
    }

    private static void smallText(GuiGraphics graphics, String value, int x, int y) {
        if (value == null || value.isEmpty()) return;
        graphics.pose().pushPose();
        graphics.pose().translate(x, y - 5, 300);
        graphics.pose().scale(.5f, .5f, 1f);
        graphics.drawString(Minecraft.getInstance().font, value, 0, 0, 0xFFFFFFFF, true);
        graphics.pose().popPose();
    }

    private static void smallTextRight(GuiGraphics graphics, String value, int right, int bottom) {
        if (value == null || value.isEmpty()) return;
        var font = Minecraft.getInstance().font;
        graphics.pose().pushPose();
        graphics.pose().translate(right, bottom, 300);
        graphics.pose().scale(.5f, .5f, 1f);
        graphics.drawString(font, value, -font.width(value), -9, 0xFFFFFFFF, true);
        graphics.pose().popPose();
    }

    private static String compactCount(int count) {
        int value = Math.max(0, count);
        if (value < 1000) return Integer.toString(value);
        if (value < 1_000_000) return (value / 1000) + "k";
        return (value / 1_000_000) + "m";
    }
}
