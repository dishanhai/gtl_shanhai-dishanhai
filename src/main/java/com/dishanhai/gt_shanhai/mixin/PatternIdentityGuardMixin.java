package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.PatternIdentityGuard;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.MachineTrait;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;

import it.unimi.dsi.fastutil.objects.Object2LongMap;

import net.minecraft.world.item.crafting.Ingredient;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 星律样板总成防串配方守卫（样板身份驱动匹配）。
 *
 * <p>根因：GTLCore 宿主选槽逻辑是「库存驱动」——遍历所有 active 样板槽，只要某槽库存能凑出
 * 候选配方的输入就把配方绑到该槽执行，从不校验该槽样板本身是不是这个配方。当同一配方类型下存在
 * 重复输入（A: 橡木+水→白桦木，B: 橡木+白桦木→钻石）时，单发 A 也可能被拿去执行 B。星律样板总成
 * 由于 {@code isItemActive/isFluidActive} 被强制置 true（见 {@code MEPatternBufferInternalSlotActiveMixin}），
 * 串配方风险更高。
 *
 * <p>修复：拦截基类 {@code meHandleRecipeInner} 的模拟匹配阶段（simulate=true），
 * 校验候选配方是否与 {@code trySlot} 槽当前样板的输入 / 输出指纹一致，不一致则直接判定该槽不可执行该配方。
 * 由此把匹配从「库存驱动」纠正为「样板身份驱动」，从根上杜绝串配方。
 *
 * <p>作用域：仅对 {@link RecipeTypePatternBufferPartMachine}（星律样板总成）生效，
 * 原版 GTLCore 样板总成行为零改动。
 *
 * <p>性能：仅在 simulate 阶段（配方查找）执行；实际扣料阶段（simulate=false）不介入。
 * 每次校验按 trySlot 从 {@code slot2PatternMap} 直接取样板，指纹在 {@link PatternIdentityMatcher} 内即时计算，
 * 集合规模等于单张样板的输入 / 输出条目数，代价可控。
 */
public final class PatternIdentityGuardMixin {

    private PatternIdentityGuardMixin() {
    }

    /**
     * 拦截物品输入 handler 的模拟匹配。
     */
    @Mixin(targets = "org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferRecipeHandlerTraitBase$MEItemInputHandlerBase",
            remap = false)
    public abstract static class ItemHandler {

        @Inject(method = "meHandleRecipeInner", at = @At("HEAD"), cancellable = true, remap = false)
        private void gtShanhai$guardItemSlotIdentity(GTRecipe recipe, Object2LongMap<Ingredient> left,
                                                     boolean simulate, int trySlot,
                                                     CallbackInfoReturnable<Boolean> cir) {
            if (!simulate) {
                return;
            }
            MetaMachine machine = ((MachineTrait) (Object) this).getMachine();
            if (PatternIdentityGuard.slotRejectsRecipe(machine, recipe, trySlot)) {
                cir.setReturnValue(false);
            }
        }
    }

    /**
     * 拦截流体输入 handler 的模拟匹配。
     */
    @Mixin(targets = "org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferRecipeHandlerTraitBase$MEFluidHandlerBase",
            remap = false)
    public abstract static class FluidHandler {

        @Inject(method = "meHandleRecipeInner", at = @At("HEAD"), cancellable = true, remap = false)
        private void gtShanhai$guardFluidSlotIdentity(GTRecipe recipe, Object2LongMap<FluidIngredient> left,
                                                      boolean simulate, int trySlot,
                                                      CallbackInfoReturnable<Boolean> cir) {
            if (!simulate) {
                return;
            }
            MetaMachine machine = ((MachineTrait) (Object) this).getMachine();
            if (PatternIdentityGuard.slotRejectsRecipe(machine, recipe, trySlot)) {
                cir.setReturnValue(false);
            }
        }
    }
}
