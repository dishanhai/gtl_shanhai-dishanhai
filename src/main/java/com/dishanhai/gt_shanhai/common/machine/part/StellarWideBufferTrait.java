package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.IntProviderIngredient;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferRecipeHandlerTrait;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferRecipeHandlerTraitBase;

import java.util.List;

/** Preserve the upstream output sampling rules while using the machine's wide buffer. */
final class StellarWideBufferTrait extends MEPatternBufferRecipeHandlerTrait {
    StellarWideBufferTrait(RecipeTypePatternBufferPartMachine machine, IO io) {
        super(machine, io);
    }

    void notifyPatternListeners() {
        getMeFluidHandler().notifyListeners();
        getMeItemHandler().notifyListeners();
    }

    @Override
    protected MEPatternBufferRecipeHandlerTraitBase.MEItemInputHandlerBase createMEItemHandler(IO io) {
        return new ItemHandler((RecipeTypePatternBufferPartMachine) getMachine(), io);
    }

    @Override
    protected MEPatternBufferRecipeHandlerTraitBase.MEFluidHandlerBase createMEFluidHandler(IO io) {
        return new FluidHandler((RecipeTypePatternBufferPartMachine) getMachine(), io);
    }

    private static final class ItemHandler extends MEPatternBufferRecipeHandlerTraitBase.MEItemInputHandlerBase {
        ItemHandler(RecipeTypePatternBufferPartMachine machine, IO io) { super(machine, io); }

        @Override
        public List<Ingredient> meHandleRecipeOutputInner(List<Ingredient> left, boolean simulate) {
            if (simulate) return List.of();
            var machine = (RecipeTypePatternBufferPartMachine) getMachine();
            for (Ingredient ingredient : left) {
                if (ingredient instanceof IntProviderIngredient sampled) {
                    sampled.setItemStacks(null);
                    sampled.setSampledCount(null);
                }
                ItemStack[] items = ingredient.getItems();
                if (items.length == 0 || items[0].isEmpty()) continue;
                long amount = ingredient instanceof LongIngredient large ? large.getActualAmount() : items[0].getCount();
                machine.gtShanhai$bufferOutput(AEItemKey.of(items[0]), amount);
            }
            return List.of();
        }
    }

    private static final class FluidHandler extends MEPatternBufferRecipeHandlerTraitBase.MEFluidHandlerBase {
        FluidHandler(RecipeTypePatternBufferPartMachine machine, IO io) { super(machine, io); }

        @Override
        public List<FluidIngredient> meHandleRecipeOutputInner(List<FluidIngredient> left, boolean simulate) {
            if (simulate) return List.of();
            var machine = (RecipeTypePatternBufferPartMachine) getMachine();
            for (FluidIngredient ingredient : left) {
                if (ingredient.isEmpty()) continue;
                FluidStack[] stacks = ingredient.getStacks();
                if (stacks.length != 0 && stacks[0] != null) {
                    machine.gtShanhai$bufferOutput(AEFluidKey.of(stacks[0].getFluid()), stacks[0].getAmount());
                }
            }
            return List.of();
        }
    }
}
