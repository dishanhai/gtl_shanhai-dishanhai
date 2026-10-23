package com.dishanhai.gt_shanhai.client.gui.generator;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.dishanhai.gt_shanhai.client.gui.glass.GlassCell;
import com.dishanhai.gt_shanhai.client.gui.glass.GlassPalette;
import com.dishanhai.gt_shanhai.client.gui.shop.MultiPickerScreen;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.lowdragmc.lowdraglib.gui.widget.PhantomFluidWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

/** 山海：不消耗物品的選取槽，沿用原本流體容器與 AE 包裝辨識。 */
public final class GeneratorGhostSlot {
    final int x,y;
    final String key;
    private final boolean machine;
    private final Supplier<GenericStack> value;
    private final Consumer<GenericStack> changed;
    private final Runnable selected;
    private final BooleanSupplier highlighted;

    GeneratorGhostSlot(String key,int x,int y,boolean machine,Supplier<GenericStack> value,
                       Consumer<GenericStack> changed,Runnable selected,BooleanSupplier highlighted) {
        this.key=key; this.x=x; this.y=y; this.machine=machine;
        this.value=value; this.changed=changed; this.selected=selected; this.highlighted=highlighted;
    }
    boolean accepts(GenericStack stack) {
        return stack != null && (!machine || stack.what() instanceof AEItemKey item && item.getItem() instanceof MetaMachineItem);
    }
    void accept(GenericStack stack) { if (stack == null || accepts(stack)) { selected.run(); changed.accept(stack); } }
    boolean hit(double mx,double my) { return mx>=x && mx<x+20 && my>=y && my<y+20; }
    void draw(GuiGraphics g,boolean hover,float selectedAmount) {
        GlassCell.draw(g,x,y,20,GlassPalette.ROOT_EDGE,selectedAmount,hover);
        ItemStack icon = display(value.get());
        if (!icon.isEmpty()) g.renderItem(icon,x+2,y+2);
    }
    GenericStack value() { return value.get(); }
    boolean isSelected() { return highlighted.getAsBoolean(); }
    void open(PatternGeneratorScreen screen) {
        selected.run();
        GenericStack[] result = {null};
        GenericStack original=value.get();
        MultiPickerScreen picker;
        if (machine) {
            var allowedIds = BuiltInRegistries.ITEM.stream().filter(item -> item instanceof MetaMachineItem)
                    .map(BuiltInRegistries.ITEM::getKey).toList();
            picker = new MultiPickerScreen(screen,allowedIds,(stack,count)->result[0]=new GenericStack(AEItemKey.of(stack),1));
        } else {
            picker = new MultiPickerScreen(screen,false,(stack,count)->result[0]=identify(stack),
                    fluid -> result[0]=original!=null && original.what() instanceof AEFluidKey old && old.getFluid()==fluid.getFluid()
                            ?new GenericStack(old,1):new GenericStack(AEFluidKey.of(fluid.getFluid(),fluid.getTag()),1));
        }
        picker.replacing();
        if (original != null && original.what() instanceof AEItemKey item) picker.preselect(item.toStack(1),1);
        else if (original != null && original.what() instanceof AEFluidKey fluid)
            picker.preselect(dev.architectury.fluid.FluidStack.create(fluid.getFluid(),1,fluid.getTag()));
        picker.setAfterConfirm(() -> accept(result[0]));
        screen.openPicker(picker);
    }
    public static GenericStack identify(Object ingredient) {
        Object normalized = PhantomFluidWidget.checkJEIIngredient(ingredient);
        if (normalized instanceof GenericStack stack) return supported(stack);
        if (normalized instanceof com.lowdragmc.lowdraglib.side.fluid.FluidStack fluid && !fluid.isEmpty())
            return new GenericStack(AEFluidKey.of(fluid.getFluid(),fluid.getTag()),1);
        if (normalized instanceof net.minecraftforge.fluids.FluidStack fluid && !fluid.isEmpty())
            return new GenericStack(AEFluidKey.of(fluid.getFluid(),fluid.getTag()),1);
        if (normalized instanceof Ingredient items) {
            ItemStack[] choices=items.getItems(); normalized=choices.length==0 ? ItemStack.EMPTY : choices[0];
        }
        if (normalized instanceof ItemStack item && !item.isEmpty()) {
            GenericStack wrapped=GenericStack.unwrapItemStack(item);
            if (wrapped != null) return supported(wrapped);
            var fluid=PhantomFluidWidget.drainFrom(item.copy());
            if (!fluid.isEmpty()) return identify(fluid);
            return new GenericStack(AEItemKey.of(item),1);
        }
        return null;
    }
    private static GenericStack supported(GenericStack stack) {
        return stack.what() instanceof AEItemKey || stack.what() instanceof AEFluidKey ? new GenericStack(stack.what(),1) : null;
    }
    static ItemStack display(GenericStack stack) {
        return stack==null ? ItemStack.EMPTY : stack.what() instanceof AEItemKey item ? item.toStack(1) : GenericStack.wrapInItemStack(stack);
    }
}
