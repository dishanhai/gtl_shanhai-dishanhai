package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.item.NativeVirtualSetupState;
import com.dishanhai.gt_shanhai.common.item.RecipeTypePatternSearchHelper;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.logic.OCParams;
import com.gregtechceu.gtceu.api.recipe.logic.OCResult;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.Set;

/**
 * 原生 GTCEu/GTLCore 多方块机器（StorageMachine 等）虚拟跨类型样板配方执行。
 *
 * <p>注入 findAndHandleRecipe HEAD：在 GTLCore 的常规搜索之前，
 * 尝试收集并执行来自星律样板总成的跨类型虚拟配方。
 *
 * <p>说明：StorageMachine 在运行时被 GTLCore mixin 注入了 IRecipeCapabilityMachine 接口，
 * 所以不能按 isCapMachine 过滤（否则 StorageMachine 永远被跳过）。
 * GTLAdditions 机器走 GTLAddMultipleRecipesLogic，不走 RecipeLogic.findAndHandleRecipe，
 * 故此处仅按 IMultiController 过滤即可，不会重复处理。
 */
@Mixin(value = RecipeLogic.class, remap = false)
public abstract class NativeVirtualFindHandleRecipeMixin {

    @Shadow(remap = false)
    @Final
    public IRecipeLogicMachine machine;

    @Shadow(remap = false)
    protected GTRecipe lastRecipe;

    @Shadow(remap = false)
    protected GTRecipe lastOriginRecipe;

    @Shadow(remap = false)
    protected OCParams ocParams;

    @Shadow(remap = false)
    protected OCResult ocResult;

    @Shadow(remap = false)
    protected boolean recipeDirty;

    @Shadow(remap = false)
    protected Map<RecipeCapability<?>, Object2IntMap<?>> chanceCaches;

    @Shadow(remap = false)
    public abstract void setStatus(RecipeLogic.Status status);

    @Shadow(remap = false)
    public abstract RecipeLogic.Status getStatus();

    @Shadow(remap = false)
    public abstract void setupRecipe(GTRecipe recipe);

    @Inject(method = "findAndHandleRecipe", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$nativeVirtualFind(CallbackInfo ci) {
        // 仅处理多方块宿主
        if (!(machine instanceof IMultiController)) return;

        Set<GTRecipe> virtualRecipes = RecipeTypePatternSearchHelper.collectNativeVirtualRecipes(machine);
        if (virtualRecipes.isEmpty()) return;

        for (GTRecipe virtual : virtualRecipes) {
            if (virtual == null) continue;

            GTRecipe modified = machine.fullModifyRecipe(virtual.copy(), ocParams, ocResult);
            if (modified == null) continue;

            boolean match = RecipeRunnerHelper.matchRecipe(machine, modified)
                    && modified.matchTickRecipe(machine).isSuccess()
                    && modified.checkConditions(machine.getRecipeLogic()).isSuccess();
            if (!match) continue;

            // 调用 setupRecipe，同时激活 beforeWorking 绕过标记（宿主模式校验会拒绝跨类型配方）
            NativeVirtualSetupState.beginVirtualExecution();
            try {
                setupRecipe(modified);
            } finally {
                NativeVirtualSetupState.endVirtualExecution();
            }

            if (lastRecipe != null && getStatus() == RecipeLogic.Status.WORKING) {
                lastOriginRecipe = virtual;
                recipeDirty = false;
                ci.cancel();
                return;
            }
        }
    }
}
