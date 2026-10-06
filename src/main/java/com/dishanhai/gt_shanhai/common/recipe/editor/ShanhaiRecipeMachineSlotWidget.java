package com.dishanhai.gt_shanhai.common.recipe.editor;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.ingredient.IGhostIngredientTarget;
import com.lowdragmc.lowdraglib.gui.ingredient.Target;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Non-consuming GTL-EH style machine ghost slot. */
public final class ShanhaiRecipeMachineSlotWidget extends Widget
        implements IGhostIngredientTarget {

    private static final int ACT_SET = 1;
    private static final int ACT_CLEAR = 2;
    private final Supplier<ItemStack> value;
    private final Consumer<ItemStack> changed;

    public ShanhaiRecipeMachineSlotWidget(int x, int y,
                                          Supplier<ItemStack> value,
                                          Consumer<ItemStack> changed) {
        super(x, y, 22, 22);
        this.value = value;
        this.changed = changed;
        setBackground(GuiTextures.SLOT);
    }

    @Override
    public List<Target> getPhantomTargets(Object ingredient) {
        ItemStack stack = normalize(ingredient);
        if (stack.isEmpty()) return List.of();
        return List.of(new Target() {
            @Override
            public Rect2i getArea() {
                return new Rect2i(getPositionX(), getPositionY(), 22, 22);
            }

            @Override
            public void accept(Object accepted) {
                ItemStack resolved = normalize(accepted);
                if (!resolved.isEmpty()) {
                    ItemStack copy = resolved.copyWithCount(1);
                    changed.accept(copy);
                    writeClientAction(ACT_SET, buffer -> buffer.writeItem(copy));
                }
            }
        });
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
        ItemStack stack = value.get();
        if (stack != null && !stack.isEmpty()) {
            new ItemStackTexture(stack).draw(graphics, mouseX, mouseY,
                    getPositionX() + 3, getPositionY() + 3, 16, 16);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        ItemStack stack = value.get();
        setHoverTooltips(stack == null || stack.isEmpty()
                ? List.of(Component.literal("把 GT 机器从 JEI 拖入这里，自动映射配方类型"))
                : List.of(stack.getHoverName(),
                        Component.literal("已映射 " + ShanhaiRecipeMachineMapping.recipeTypes(stack).size()
                                + " 个配方类型"),
                        Component.literal("右键清除机器映射")));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isMouseOverElement(mouseX, mouseY)) return false;
        if (button == 1) {
            changed.accept(ItemStack.EMPTY);
            writeClientAction(ACT_CLEAR, buffer -> {});
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void handleClientAction(int id, FriendlyByteBuf buffer) {
        if (id == ACT_CLEAR) {
            changed.accept(ItemStack.EMPTY);
        } else if (id == ACT_SET) {
            changed.accept(buffer.readItem());
        }
    }

    private static ItemStack normalize(Object ingredient) {
        if (ingredient instanceof ITypedIngredient<?> typed) {
            try {
                ItemStack item = typed.getItemStack().orElse(ItemStack.EMPTY);
                if (!item.isEmpty()) return item;
            } catch (Throwable ignored) {
                // Try the raw ingredient below.
            }
            ingredient = typed.getIngredient();
        }
        if (ingredient instanceof ItemStack stack) return stack;
        if (ingredient instanceof Ingredient item) {
            ItemStack[] stacks = item.getItems();
            return stacks.length == 0 ? ItemStack.EMPTY : stacks[0];
        }
        if (ingredient instanceof GenericStack generic && generic.what() instanceof AEItemKey key) {
            return key.toStack(1);
        }
        return ItemStack.EMPTY;
    }
}
