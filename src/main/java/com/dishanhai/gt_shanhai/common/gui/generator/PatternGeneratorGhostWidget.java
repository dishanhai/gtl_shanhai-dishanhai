package com.dishanhai.gt_shanhai.common.gui.generator;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.gui.ingredient.IGhostIngredientTarget;
import com.lowdragmc.lowdraglib.gui.ingredient.IIngredientSlot;
import com.lowdragmc.lowdraglib.gui.ingredient.Target;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.widget.PhantomFluidWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import mezz.jei.api.ingredients.ITypedIngredient;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** 一个不消耗物品的格子，物品和流体都能放，包括 JEI 拖入。 */
public final class PatternGeneratorGhostWidget extends Widget implements IGhostIngredientTarget, IIngredientSlot {
    private final Supplier<GenericStack> value;
    private final Predicate<GenericStack> valid;
    private final Consumer<GenericStack> changed;
    private final String hint;
    private boolean clicked;
    private Runnable selected = () -> {};
    private BooleanSupplier highlighted = () -> false;
    private Supplier<Component> detail = () -> Component.empty();

    public PatternGeneratorGhostWidget(int x, int y, Supplier<GenericStack> value,
                                       Predicate<GenericStack> valid, Consumer<GenericStack> changed, String hint) {
        super(x, y, 18, 18);
        this.value = value;
        this.valid = valid;
        this.changed = changed;
        this.hint = hint;
        setBackground(GuiTextures.SLOT);
    }

    public PatternGeneratorGhostWidget selection(Runnable selected, BooleanSupplier highlighted, Supplier<Component> detail) {
        this.selected = selected;
        this.highlighted = highlighted;
        this.detail = detail;
        return this;
    }

    public static GenericStack identify(Object ingredient) {
        if (LDLib.isJeiLoaded() && ingredient instanceof ITypedIngredient<?> typed) ingredient = typed.getIngredient();
        Object normalized = PhantomFluidWidget.checkJEIIngredient(ingredient);
        if (normalized instanceof GenericStack stack) return supported(stack);
        if (normalized instanceof FluidStack fluid && !fluid.isEmpty()) {
            return new GenericStack(fluid.hasTag() ? AEFluidKey.of(fluid.getFluid(), fluid.getTag())
                    : AEFluidKey.of(fluid.getFluid()), 1);
        }
        if (normalized instanceof Ingredient itemIngredient) {
            ItemStack[] choices = itemIngredient.getItems();
            normalized = choices.length == 0 ? ItemStack.EMPTY : choices[0];
        }
        if (normalized instanceof ItemStack item && !item.isEmpty()) {
            GenericStack wrapped = GenericStack.unwrapItemStack(item);
            if (wrapped != null) return supported(wrapped);
            FluidStack fluid = PhantomFluidWidget.drainFrom(item.copy());
            if (!fluid.isEmpty()) return identify(fluid);
            return new GenericStack(AEItemKey.of(item), 1);
        }
        return null;
    }

    private static GenericStack supported(GenericStack stack) {
        if (stack == null) return null;
        return stack.what() instanceof AEItemKey || stack.what() instanceof AEFluidKey
                ? new GenericStack(stack.what(), 1) : null;
    }

    public static ItemStack display(GenericStack stack) {
        if (stack == null) return ItemStack.EMPTY;
        return stack.what() instanceof AEItemKey item ? item.toStack(1) : GenericStack.wrapInItemStack(stack);
    }

    @Override
    public List<Target> getPhantomTargets(Object ingredient) {
        GenericStack stack = identify(ingredient);
        if (!isVisible() || !isActive() || stack == null || !valid.test(stack)) return List.of();
        return List.of(new Target() {
            @Override
            public Rect2i getArea() {
                return new Rect2i(getPosition().x, getPosition().y, getSize().width, getSize().height);
            }

            @Override
            public void accept(Object accepted) {
                submit(identify(accepted));
            }
        });
    }

    private void submit(GenericStack stack) {
        if (stack != null && !valid.test(stack)) return;
        selected.run();
        writeClientAction(0, buffer -> buffer.writeNbt(stack == null ? null : GenericStack.writeTag(stack)));
    }

    @Override
    public void handleClientAction(int id, FriendlyByteBuf buffer) {
        if (id != 0) {
            super.handleClientAction(id, buffer);
            return;
        }
        var tag = buffer.readNbt();
        GenericStack stack = tag == null ? null : supported(GenericStack.readTag(tag));
        if (stack == null || valid.test(stack)) changed.accept(stack);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        clicked = false;
        if (!isMouseOverElement(x, y) || !isActive() || !isVisible()) return false;
        selected.run();
        if (button == 1) {
            submit(null);
            clicked = true;
            return true;
        }
        GenericStack carried = identify(gui.entityPlayer.containerMenu.getCarried());
        if (button == 0 && carried != null && valid.test(carried)) {
            submit(carried);
            clicked = true;
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (clicked) {
            clicked = false;
            return true;
        }
        if (button == 0 && isActive() && isVisible() && isMouseOverElement(x, y)) {
            GenericStack carried = identify(gui.entityPlayer.containerMenu.getCarried());
            if (carried != null && valid.test(carried)) {
                submit(carried);
                return true;
            }
        }
        return super.mouseReleased(x, y, button);
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
        ItemStack icon = display(value.get());
        if (!icon.isEmpty()) {
            new ItemStackTexture(icon).draw(graphics, mouseX, mouseY, getPosition().x + 1, getPosition().y + 1, 16, 16);
        }
        if (highlighted.getAsBoolean()) {
            new ColorBorderTexture(1, 0xffd4a342).draw(graphics, mouseX, mouseY, getPosition().x, getPosition().y, 18, 18);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        GenericStack stack = value.get();
        setHoverTooltips(stack == null
                ? List.of(Component.translatable(hint))
                : List.of(stack.what().getDisplayName(), Component.literal(stack.what().getId().toString()),
                detail.get(), Component.translatable(hint)));
    }

    @Override
    public Object getXEIIngredientOverMouse(double x, double y) {
        if (!isMouseOverElement(x, y) || value.get() == null) return null;
        return value.get().what() instanceof AEFluidKey fluid ? fluid.toStack(1000) : display(value.get());
    }
}
