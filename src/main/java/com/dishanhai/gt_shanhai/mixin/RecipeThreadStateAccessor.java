package com.dishanhai.gt_shanhai.mixin;

import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.logic.OCParams;
import com.gregtechceu.gtceu.api.recipe.logic.OCResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Author: dishanhai. Snapshot access; does not substitute the native logic instance. */
@Mixin(value = RecipeLogic.class, remap = false)
public interface RecipeThreadStateAccessor {
    @Accessor("lastRecipe") void shanhai$recipe(GTRecipe recipe);
    @Accessor("lastOriginRecipe") void shanhai$origin(GTRecipe recipe);
    @Accessor("progress") void shanhai$progress(int progress);
    @Accessor("duration") void shanhai$duration(int duration);
    @Accessor("fuelTime") void shanhai$fuel(int fuel);
    @Accessor("fuelMaxTime") void shanhai$maxFuel(int fuel);
    @Accessor("recipeDirty") void shanhai$dirty(boolean dirty);
    @Accessor("totalContinuousRunningTime") void shanhai$continuous(long ticks);
    @Accessor("ocParams") OCParams shanhai$ocParams();
    @Accessor("ocParams") void shanhai$ocParams(OCParams params);
    @Accessor("ocResult") OCResult shanhai$ocResult();
    @Accessor("ocResult") void shanhai$ocResult(OCResult result);
}
