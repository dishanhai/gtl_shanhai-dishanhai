package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.lowdragmc.lowdraglib.gui.ingredient.IGhostIngredientTarget;
import com.lowdragmc.lowdraglib.gui.ingredient.Target;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.mojang.blaze3d.systems.RenderSystem;
import mezz.jei.api.ingredients.ITypedIngredient;
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
import java.util.function.IntSupplier;

/** One graphical item or fluid cell. Drops are recorded, never consumed. */
public class ShanhaiIOWidget extends Widget implements IGhostIngredientTarget {

    public static final int FRAME_ITEM = 0xFF8B8B8B;
    public static final int FRAME_FLUID = 0xFF3A7BD5;
    public static final int CELL = 20;

    private ShanhaiIoTable table;
    private final IntSupplier indexSupplier;
    private final Runnable changed;
    private BooleanSupplier selected = () -> false;
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

    public void setTable(ShanhaiIoTable table) {
        this.table = table;
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
        if (normalized instanceof ItemStack stack) {
            target.setItem(stack, stack.getCount());
        } else if (normalized instanceof FluidStack fluid) {
            target.setFluid(fluid);
        } else {
            return;
        }
        changed.run();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isMouseOverElement(mouseX, mouseY)) return false;
        if (button == 1) {
            ShanhaiIoTable.Cell current = cell();
            if (current != null) {
                current.clear();
                changed.run();
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
        if (selected.getAsBoolean()) {
            graphics.fill(x - 1, y - 1, x + CELL + 1, y + CELL + 1, 0xFF69E8FF);
        }
        graphics.fill(x, y, x + CELL, y + CELL, current.itemKind ? FRAME_ITEM : FRAME_FLUID);
        graphics.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, 0xFF272A35);
        if (current.itemKind && !current.item.isEmpty()) {
            new ItemStackTexture(current.item).draw(graphics, mouseX, mouseY, x + 2, y + 2, 16, 16);
            graphics.renderItemDecorations(Minecraft.getInstance().font, current.item, x + 2, y + 2);
        } else if (!current.itemKind && !current.fluid.isEmpty()) {
            drawFluid(graphics, current.fluid, x + 2, y + 2);
            smallText(graphics, Integer.toString(current.shownCount()), x + 18, y + 19);
        }
        if (current.chance != 10000) {
            smallText(graphics, (current.chance * 100 / Math.max(1, current.maxChance)) + "%", x + 2, y + 19);
        }
        if (current.chance == 0) {
            graphics.fill(x + 2, y + 2, x + 7, y + 7, 0xFFFFC400);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        ShanhaiIoTable.Cell current = cell();
        if (current == null || current.empty()) {
            setHoverTooltips(List.of(Component.literal("灰框物品 / 蓝框流体，JEI 拖入，右键清空")));
        } else if (current.itemKind) {
            setHoverTooltips(List.of(current.item.getHoverName(),
                    Component.literal("数量 " + current.item.getCount()),
                    Component.literal("概率 " + current.chance + "/" + current.maxChance),
                    Component.literal("右键清空")));
        } else {
            setHoverTooltips(List.of(current.fluid.getDisplayName(),
                    Component.literal("数量 " + current.fluid.getAmount() + " mB"),
                    Component.literal("概率 " + current.chance + "/" + current.maxChance),
                    Component.literal("右键清空")));
        }
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
        graphics.pose().translate(x, y - 6, 300);
        graphics.pose().scale(.5f, .5f, 1f);
        graphics.drawString(Minecraft.getInstance().font, value, 0, 0, 0xFFFFFFFF, true);
        graphics.pose().popPose();
    }
}
