package com.shanhai.mixin.probe;

import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine;
import com.shanhai.common.diag.RecipeProbe;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <b>只读诊断探针 P3</b>：{@code WorkableMultiblockMachine#onWorking()} 返回 {@code false} 的那一下。
 *
 * <h2>为什么盯这一个返回值</h2>
 * {@code RecipeLogic#handleRecipeWorking} 的字节码（javap 偏移 72-88）是：
 * <pre>
 *   setStatus(WORKING)
 *   if (!machine.onWorking()) { interruptRecipe(); return; }   // ← 就是这一下
 *   progress++
 * </pre>
 * ⇒ <b>"{@code onWorking()} 说不行" 是 interruptRecipe 最常见的触发者</b>。
 * 但 {@code onWorking()} 返回 {@code false} 本身<b>完全静默</b>（上游一行日志都不打），
 * 而且它返回 false 的<b>原因</b>（仓室罢工 / 无线电网输入失败 / 结构刚散）分不出来，
 * 所以这里把当时的仓室简表一起打出来。
 *
 * <h2>⚠️ 覆盖范围的诚实说明（别把"没打"读成"没返回 false"）</h2>
 * 本探针只挂在<b>基类实现</b>上。子类若<b>重写</b>了 {@code onWorking()} 且不调 {@code super}
 * （GT 里确实有这种写法），那台机器返回 false 时这里<b>不会</b>响。
 * ⇒ 判据要三角对读：{@code onworking_false}（本行）+ 紧跟的 {@code interrupt}（P1，含调用栈）
 * + {@code summary} 里的 {@code onworkingFalse=} 计数。三者对不上时先怀疑本探针的覆盖范围。
 *
 * <h2>签名来源</h2>
 * <pre>
 * javap -p -s -classpath libs/gtceu-1.20.1-1.4.4.jar \
 *   com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine
 *   public boolean onWorking();      descriptor: ()Z
 * </pre>
 * 🔴 类在 <b>{@code com.gregtechceu.gtceu.api.machine.multiblock}</b>；
 * {@code com.gregtechceu.gtceu.common.machine.multiblock.workable.WorkableMultiblockMachine}
 * 在装好的 jar 里<b>不存在</b>（{@code jar tf} 已核）。
 */
@Mixin(value = WorkableMultiblockMachine.class, remap = false)
public class ShanhaiWorkableMultiblockProbeMixin {

    /** RETURN：只在返回 false 时说话（返回 true 是正常路径，一行都不打）。 */
    @Inject(method = "onWorking()Z", at = @At("RETURN"), require = 0, remap = false)
    private void shanhai$probeOnWorking(CallbackInfoReturnable<Boolean> cir) {
        try {
            if (cir.getReturnValueZ()) {
                return;
            }
            RecipeProbe.onWorkingFalse((WorkableMultiblockMachine) (Object) this);
        } catch (Throwable ignored) {
            // 探针绝不许影响 onWorking 的返回值 —— 这里连日志都不写，避免二级异常
        }
    }
}
