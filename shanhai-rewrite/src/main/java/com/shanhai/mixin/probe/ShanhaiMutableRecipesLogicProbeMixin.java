package com.shanhai.mixin.probe;

import com.gtladd.gtladditions.api.machine.logic.MutableRecipesLogic;
import com.shanhai.common.diag.RecipeProbe;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>只读诊断探针 P4a + P5a</b>：gtladditions 的 {@code MutableRecipesLogic}（Kotlin）里那两个
 * "先扣料、后判产出"的闸门。
 *
 * <h2>为什么是这两个方法（不是 {@code getRecipe}）</h2>
 * 任务书里的描述是"判 {@code matchRecipeOutput} 的那个方法"。对装好的 jar 做
 * {@code javap -p -c} 后，{@code matchRecipeOutput} 在这两个类里一共只有 2 个调用点：
 * <pre>
 * MutableRecipesLogic#findAndHandleMultipleRecipe  偏移 27   → RecipeRunnerHelper.matchRecipeOutput(holder, recipe)
 * MutableRecipesLogic#onMultipleRecipeFinish       偏移 128  → 同上
 * GTLAddMultipleRecipesLogic#findAndHandleRecipe   偏移 27   → 同上
 * GTLAddMultipleRecipesLogic#onRecipeFinish        偏移 128  → 同上
 * </pre>
 * ⇒ 本类挂的就是这两条（{@code getRecipe()} 内部并<b>没有</b> {@code matchRecipeOutput}，
 * 所以挂 {@code getRecipe} 抓不到产出判定）。
 *
 * <h2>两条闸门的区别（判读时最关键的一点）</h2>
 * <ul>
 *   <li><b>P4 = findAndHandle…</b>（找配方）：失败时只留 {@code lastRecipe=null}，
 *       {@code progress}/{@code duration} <b>原样停住</b> ⇒ 现场表现为"机器空转、进度不动"；</li>
 *   <li><b>P5 = on…Finish</b>（完工后找下一条）：失败时走
 *       {@code status=IDLE; progress=0; duration=0}（javap 偏移 158-175）
 *       ⇒ 这才是<b>"配方进度清零、从头再跑"的完整足迹</b>，而且它<b>不经过</b>
 *       {@code interruptRecipe}，所以 P1 抓不到它。</li>
 * </ul>
 *
 * <h2>签名来源（Kotlin 编译产物，注意没有 {@code Continuation} 参数）</h2>
 * <pre>
 * javap -p -s -classpath libs/gtladditions-3.2.8Custom-fix2.jar \
 *   com.gtladd.gtladditions.api.machine.logic.MutableRecipesLogic
 *   protected void findAndHandleMultipleRecipe();  descriptor: ()V
 *   protected void onMultipleRecipeFinish();       descriptor: ()V
 * </pre>
 * （这两个都是普通实例方法；Kotlin 的 suspend 函数才带 {@code Continuation}，这里不是。）
 *
 * <h2>红线</h2>
 * 只 {@code @Inject}，{@code require = 0}，不 cancellable、不 Redirect、不 ModifyVariable
 * ⇒ 上游 Kotlin 代码的行为一个字节都不变。本类与它的配置
 * （{@code shanhai.probe.mixin.json}）都是 {@code required:false}：
 * gtladditions 改类名/方法名时，代价是<b>一条 mixin 警告 + 本探针 =false</b>，不是整合包起不来。
 */
@Mixin(value = MutableRecipesLogic.class, remap = false)
public class ShanhaiMutableRecipesLogicProbeMixin {

    /** P4a 进闸：记下"进来时手上还有没有配方 + 进度/时长"。 */
    @Inject(method = "findAndHandleMultipleRecipe()V", at = @At("HEAD"), require = 0, remap = false)
    private void shanhai$probeGateEnter(CallbackInfo ci) {
        RecipeProbe.gateEnter(this, "MutableRecipesLogic");
    }

    /** P4a 出闸：{@code hadRecipe=0} ⇒ 这次闸门把配方丢了（料已经扣掉了）。 */
    @Inject(method = "findAndHandleMultipleRecipe()V", at = @At("RETURN"), require = 0, remap = false)
    private void shanhai$probeGateExit(CallbackInfo ci) {
        RecipeProbe.gateExit(this, "MutableRecipesLogic");
    }

    /** P5a 进闸（完工后再找下一条配方）。 */
    @Inject(method = "onMultipleRecipeFinish()V", at = @At("HEAD"), require = 0, remap = false)
    private void shanhai$probeFinishEnter(CallbackInfo ci) {
        RecipeProbe.finishEnter(this, "MutableRecipesLogic");
    }

    /** P5a 出闸：{@code progress=0 duration=0} ⇒ 走的就是那条把进度清零的 IDLE 分支。 */
    @Inject(method = "onMultipleRecipeFinish()V", at = @At("RETURN"), require = 0, remap = false)
    private void shanhai$probeFinishExit(CallbackInfo ci) {
        RecipeProbe.finishExit(this, "MutableRecipesLogic");
    }
}
