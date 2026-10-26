package com.shanhai.mixin;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.shanhai.machine.stellar.ShanhaiSlotTypeGate;
import com.shanhai.machine.stellar.ShanhaiWildcardRecipeBinding;
import com.shanhai.machine.stellar.StellarSampleAssemblerPartMachine;

import it.unimi.dsi.fastutil.objects.Object2LongMap;

import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <b>【落到槽上的类型闸】—— 引擎拿一条配方问某个槽时，那个槽自己回答"我能不能跑它"。</b>
 *
 * <h2>一、为什么必须是这一跳（字节码依据）</h2>
 * <pre>
 *   MEPatternBufferRecipeHandlerTraitBase$MEItemInputHandlerBase#meHandleRecipeInner
 *       (GTRecipe recipe, Object2LongMap left, boolean simulate, int slot)  ← 第 4 个参数就是【槽号】
 *   偏移 0–9   getInternalSlotOrNull(slot)         ← 按槽取池（一池一条配方）
 *   偏移 16–22 InternalSlot.isItemActive(simulate)
 *   偏移 62–74 InternalSlot.handleItemInternal(…)  ← 真正试配/扣料
 *   （流体侧 MEFluidHandlerBase#meHandleRecipeInner 同形，同样是第 4 个参数给槽号）
 * </pre>
 * 这就是"引擎问槽"的唯一入口 —— 在 HEAD 拒绝，引擎的下一槽逻辑（{@code MEPatternRecipeHandlePart}
 * 的候选循环）自动接手，<b>既不影响别的槽，也不需要任何"优先级/顺序"仲裁</b>
 * （用户口径：一个池只管一条配方 ⇒ 结构上不可能串）。
 *
 * <h2>二、安全边界（三条，缺一不可）</h2>
 * <ol>
 *   <li><b>只对星律生效</b>：{@code getMachine() instanceof StellarSampleAssemblerPartMachine}
 *       —— 其它样板总成 / 总线走原路，一个字节不变；</li>
 *   <li><b>拿不到就放行</b>：配方类型读不出、机器可执行类型表读不出、槽号越界 ⇒ 一律放行；</li>
 *   <li><b>只拒明确不一致</b>：判定核是纯逻辑 {@link ShanhaiSlotTypeGate}
 *       （有声明且类型不同 / 机器不支持该声明 / 无声明且闸开），其余全放。</li>
 * </ol>
 *
 * <h2>三、一键关</h2>
 * 环境变量（或 {@code -D}）{@code SHANHAI_DISABLE_TYPE_FILTER=1} ⇒
 * {@link ShanhaiSlotTypeGate#disabled()} 为真 ⇒ 本注入只做一次 boolean 判断就返回，
 * <b>行为与没有本 mixin 逐字节相同</b>。
 *
 * <h2>四、与"全局收窄候选类型集"的区别（上一批的错误）</h2>
 * 上一批改的是控制器 {@code lookupRecipeSet()} 的候选<b>类型集合</b>（整机粒度）——
 * 那会让"一个槽说了算"变成"所有槽都被同一张类型表约束"。本条改的是<b>槽的准入</b>：
 * 候选配方照旧由引擎/控制器按机器能力搜，<b>只是某个槽在轮到自己时可以选择不接</b>。
 */
@Mixin(targets = {
        "org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferRecipeHandlerTraitBase$MEItemInputHandlerBase",
        "org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferRecipeHandlerTraitBase$MEFluidHandlerBase"
}, remap = false)
public abstract class ShanhaiSlotTypeGateMixin {

    /** 目标类自己的 {@code getMachine()}（返回 {@code MEPatternBufferPartMachineBase}）。 */
    @Shadow
    public abstract MEPatternBufferPartMachineBase getMachine();

    @Inject(method = "meHandleRecipeInner", at = @At("HEAD"), cancellable = true, remap = false)
    private void shanhai$slotTypeGate(GTRecipe recipe, Object2LongMap<?> left, boolean simulate, int slot,
                                      CallbackInfoReturnable<Boolean> cir) {
        // 一键关：连 instanceof 都不做（最省，也最不容易出意外）。
        if (ShanhaiSlotTypeGate.disabled()) {
            return;
        }
        try {
            if (recipe == null) {
                return;
            }
            final MEPatternBufferPartMachineBase machine = this.getMachine();
            if (!(machine instanceof StellarSampleAssemblerPartMachine stellar)) {
                return;
            }
            final String recipeType = ShanhaiWildcardRecipeBinding.typeIdOf(recipe);
            final ShanhaiSlotTypeGate.Outcome outcome = stellar.shanhaiSlotTypeVerdict(slot, recipeType);
            if (!outcome.blocked()) {
                return;
            }
            // 拒绝 = "这个槽不接这条配方"；引擎换下一槽（原版语义词，不是"整机失败"）。
            cir.setReturnValue(Boolean.FALSE);
        } catch (Throwable ignored) {
            // 闸不许把机器弄停：任何异常 ⇒ 当作放行（不 setReturnValue）。
        }
    }
}
