package com.shanhai.add.mixin.gtlcore;

import com.shanhai.add.client.time.ShanhaiClientClock;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 山海重构 · <b>缺陷 ④ 「机器动画还在动」的真正落点</b>（gtlcore 的连续渲染时钟）。
 *
 * <h2>1. 取证：动画到底吃什么时钟（不是 partialTick，也不是 tick）</h2>
 * 我们的机器渲染链：
 * <pre>
 *   AbstractRingRenderer#render(BlockEntity, float partialTick, …)               // 每帧
 *     → getSmoothTick(machine, partialTick)                                     // 子类实现
 *     → PrimordialOmegaEngineRenderer#getSmoothTick                             // 本仓库
 *     → org.gtlcore.gtlcore.utils.RenderUtil#getSmoothTick(MetaMachine, float)  // gtlcore-1.2.3.2
 *         return ConfigHolder.INSTANCE.enableSmoothAnimations
 *              ? GlobalRenderClock.getSmoothTick()          // ← 默认走这条
 *              : (float) machine.getOffsetTimer() + partialTick;
 * </pre>
 * 已核实（`vineflower` 反编译 `gtlcore` 同名类）：
 * <ul>
 *   <li>{@code ConfigHolder.enableSmoothAnimations = true}（默认值，`ConfigHolder.java:78`）⇒ 默认走
 *       {@code GlobalRenderClock}；</li>
 *   <li>{@code GlobalRenderClock#getSmoothTick()} = {@code (float)(getElapsedNanos() / 5.0E7)}，
 *       而 {@code getElapsedNanos()} 用 <b>{@code System.nanoTime()}</b> 算 ⇒ <b>真实时间驱动</b>，
 *       与 tick 冻结<b>完全无关</b>。它自带的"暂停"只认 {@code Minecraft#isPaused()}（原版暂停菜单），
 *       我们这条世界时钟它认不出来。</li>
 *   <li>另外扫了 {@code gtceu-1.20.1-1.4.4} / {@code gtladditions-3.2.8} / {@code ldlib-1.0.33}
 *       三个 jar 的<b>全部</b> class 常量池，<b>零</b>处 {@code Util.getMillis()} 引用
 *       （只有 gtlcore 的 {@code MachineStartupTickBudget} 用了，与渲染无关）。</li>
 * </ul>
 * ⇒ 结论：机器动画的时钟入口是 {@code GlobalRenderClock#getSmoothTick()}，冻它就冻住了那条链
 * （我们自己的 {@code RenderUtil.getSmoothTick(machine, partialTick)} 直调也一并覆盖）。
 *
 * <h2>2. 为什么冻法必须是"返回锁住的那个值"</h2>
 * 在 {@code @At("RETURN")} 拿到本帧真实值，<b>第一次</b>暂停帧把它锁住，之后每帧都返回它。
 * 若返回 0（或任何常数），动画会从时间原点重来 ⇒ 视觉上"咔"地跳一下。
 *
 * <h2>3. 没装/换了 gtlcore 也不会崩</h2>
 * 独立的 {@code shanhai.gtlcore.mixin.json}（{@code required:false}）+ {@code require = 0} +
 * {@code remap = false} + 全限定 {@code targets}（与 Oculus 那条同款，见
 * {@code ShanhaiOculusFrameTimeMixin}）。类不存在时 Mixin 只打一行 WARN，本注入静默失效。
 * 静默失效时还有第二道保险：{@code AbstractRingRenderer} 自己也会对 {@code smoothTick} 走同一个冻结判据。
 */
@Mixin(targets = "org.gtlcore.gtlcore.client.GlobalRenderClock", remap = false)
public abstract class ShanhaiGtlcoreSmoothTickClockMixin {

    /**
     * 冻结 gtlcore 的连续渲染时钟（20 tick/秒 的口径：{@code elapsedNanos / 5.0E7}）。
     *
     * <p>为什么是 {@code RETURN} 而不是 HEAD：需要"本帧真实值"来锁，否则只能给一个凭空常数。
     *
     * @param cir 返回值句柄（{@code float}）
     */
    @Inject(method = "getSmoothTick", at = @At("RETURN"), require = 0, cancellable = true)
    private static void shanhai$freezeSmoothTick(CallbackInfoReturnable<Float> cir) {
        try {
            cir.setReturnValue(ShanhaiClientClock.freezeSmoothTick(cir.getReturnValueF()));
        } catch (Throwable ignored) {
            // 冻不住就让原值回去（宁可视面上没停，也不能让渲染链抛异常）
        }
    }
}
