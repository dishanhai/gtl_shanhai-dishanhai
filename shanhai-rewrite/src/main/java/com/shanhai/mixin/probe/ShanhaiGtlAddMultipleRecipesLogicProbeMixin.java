package com.shanhai.mixin.probe;

import com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleRecipesLogic;
import com.shanhai.common.diag.RecipeProbe;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>只读诊断探针 P4b + P5b</b>：gtladditions 的 {@code GTLAddMultipleRecipesLogic}
 * （{@code MutableRecipesLogic} 的同族实现，伪神之锻炉那一线用它）上的两个"先扣料后判产出"闸门。
 *
 * <p>两个闸门的语义、以及"为什么 P5 才是进度清零的完整足迹"，逐字同
 * {@link ShanhaiMutableRecipesLogicProbeMixin} 的类注释，这里不重复。
 *
 * <h2>签名来源（对装好的 jar 核过）</h2>
 * <pre>
 * javap -p -s -classpath libs/gtladditions-3.2.8Custom-fix2.jar \
 *   com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleRecipesLogic
 *   public void findAndHandleRecipe();  descriptor: ()V
 *   public void onRecipeFinish();       descriptor: ()V
 * </pre>
 * 🔴 注意方法名与 {@code MutableRecipesLogic} <b>不一样</b>：
 * 这个类继承 {@code org.gtlcore.gtlcore.common.machine.trait.MultipleRecipesLogic}，
 * 闸门叫 {@code findAndHandleRecipe}（不是 {@code findAndHandleMultipleRecipe}）；
 * 完工钩子叫 {@code onRecipeFinish}（不是 {@code onMultipleRecipeFinish}）。
 * 用错名字的后果是注入静默失败 ⇒ 全靠 {@code armed P4b=…} 那行揭发。
 */
@Mixin(value = GTLAddMultipleRecipesLogic.class, remap = false)
public class ShanhaiGtlAddMultipleRecipesLogicProbeMixin {

    /** P4b 进闸。 */
    @Inject(method = "findAndHandleRecipe()V", at = @At("HEAD"), require = 0, remap = false)
    private void shanhai$probeGateEnter(CallbackInfo ci) {
        RecipeProbe.gateEnter(this, "GTLAddMultipleRecipesLogic");
    }

    /** P4b 出闸。 */
    @Inject(method = "findAndHandleRecipe()V", at = @At("RETURN"), require = 0, remap = false)
    private void shanhai$probeGateExit(CallbackInfo ci) {
        RecipeProbe.gateExit(this, "GTLAddMultipleRecipesLogic");
    }

    /** P5b 进闸（完工后再找下一条配方）。 */
    @Inject(method = "onRecipeFinish()V", at = @At("HEAD"), require = 0, remap = false)
    private void shanhai$probeFinishEnter(CallbackInfo ci) {
        RecipeProbe.finishEnter(this, "GTLAddMultipleRecipesLogic");
    }

    /** P5b 出闸：{@code progress=0 duration=0} ⇒ 走的就是把进度清零的 IDLE 分支。 */
    @Inject(method = "onRecipeFinish()V", at = @At("RETURN"), require = 0, remap = false)
    private void shanhai$probeFinishExit(CallbackInfo ci) {
        RecipeProbe.finishExit(this, "GTLAddMultipleRecipesLogic");
    }
}
