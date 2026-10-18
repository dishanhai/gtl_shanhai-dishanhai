package com.shanhai.mixin.probe;

import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.shanhai.common.diag.RecipeProbe;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>只读诊断探针 P1 + P2</b>：{@code RecipeLogic} 上那两个"把进度弄没"的静默入口。
 *
 * <table border="1">
 *   <caption>注入点</caption>
 *   <tr><th>探针</th><th>目标方法</th><th>注入点</th><th>打什么</th></tr>
 *   <tr><td>P1</td><td>{@code interruptRecipe()V}</td><td>HEAD</td>
 *       <td>{@code interrupt …}（含过滤后的调用栈 = "谁调的"）</td></tr>
 *   <tr><td>P2</td><td>{@code doDamping()V}</td><td>HEAD</td>
 *       <td>{@code damp …}（含 {@code reason=} 等待原因）</td></tr>
 * </table>
 *
 * <h2>🔴 三条红线（本类一个都不许违反）</h2>
 * <ol>
 *   <li><b>只 HEAD、不 cancellable、不 Redirect、不 ModifyVariable</b> ⇒ 目标方法的行为逐字节不变；</li>
 *   <li>{@code require = 0} ⇒ 上游改签名/改名时<b>不会把整合包启动打崩</b>，
 *       代价是"注入失败完全静默"，所以必须靠 {@code armed P1=…} 那行自证；</li>
 *   <li>{@code remap = false} ⇒ {@code RecipeLogic} 是 GT 自己的类，名字本来就不是 SRG，不需要映射。</li>
 * </ol>
 *
 * <h2>签名来源（必须可复现）</h2>
 * <pre>
 * javap -p -s -classpath libs/gtceu-1.20.1-1.4.4.jar com.gregtechceu.gtceu.api.machine.trait.RecipeLogic
 *   protected void doDamping();      descriptor: ()V
 *   public    void interruptRecipe();descriptor: ()V
 * </pre>
 * 包名注意：上游那台"工作型多方块"在 <b>{@code api.machine.multiblock}</b> 下，
 * 不是某些资料里写的 {@code common.machine.multiblock.workable}（本工程已 javap 核过，见 P3 的类注释）。
 */
@Mixin(value = RecipeLogic.class, remap = false)
public class ShanhaiRecipeLogicProbeMixin {

    /** P1：{@code interruptRecipe()} 一进来就记 —— 记的是<b>清零之前</b>的值。 */
    @Inject(method = "interruptRecipe()V", at = @At("HEAD"), require = 0, remap = false)
    private void shanhai$probeInterrupt(CallbackInfo ci) {
        RecipeProbe.interrupt((RecipeLogic) (Object) this);
    }

    /** P2：{@code doDamping()} 一进来就记 —— 记的是<b>回退之前</b>的 progress。 */
    @Inject(method = "doDamping()V", at = @At("HEAD"), require = 0, remap = false)
    private void shanhai$probeDamp(CallbackInfo ci) {
        RecipeProbe.damp((RecipeLogic) (Object) this);
    }
}
