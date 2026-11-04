package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 切換邏輯執行緒時不廣播機器狀態變更；每 tick 結尾再投影一次。 */
@Mixin(value = RecipeLogic.class, remap = false)
public interface PrimordialRecipeThreadStateAccessor {
    @Accessor("status") void shanhai$threadStatus(RecipeLogic.Status status);
    @Accessor("isActive") boolean shanhai$threadActive();
    @Accessor("isActive") void shanhai$threadActive(boolean active);
    @Accessor("waitingReason") Component shanhai$threadWaitingReason();
    @Accessor("waitingReason") void shanhai$threadWaitingReason(Component reason);
}
