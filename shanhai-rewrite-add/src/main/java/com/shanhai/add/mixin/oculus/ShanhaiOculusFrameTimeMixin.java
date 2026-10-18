package com.shanhai.add.mixin.oculus;

import com.shanhai.add.client.time.ShanhaiClientClock;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · 光影层：冻住 Oculus/Iris 的<b>墙钟</b>时间 uniform（{@code frameTimeCounter}）。
 *
 * <h2>1. 为什么必须单独做这一层</h2>
 * 普查（{@code temp\time-census\报告.md} A③）逐条取证：
 * <ul>
 *   <li>{@code WorldTimeUniforms}（{@code worldTime}/{@code worldDay}/{@code moonPhase}）是
 *       {@code PER_TICK}，取 {@code ClientLevel.getDayTime()} ⇒ 我们冻了 tick 之后<b>它自己就停了</b>；
 *       太阳/月亮方向、{@code shadowAngle}、方块光照、天空亮度<b>免费冻住</b>。</li>
 *   <li>但 {@code SystemTimeUniforms.frameTimeCounter}/{@code frameTime} 是 <b>{@code PER_FRAME}</b>，
 *       来源是<b>墙钟</b>（{@code Util.getNanos()}），由 Oculus 自己的
 *       {@code MixinLevelRenderer} 在 {@code renderLevel(...F J ...)} 头部调
 *       {@code SystemTimeUniforms$Timer#beginFrame(J)} 推进。</li>
 *   ⇒ 不冻它，光影包的水波/草动/云影会继续摇 ⇒ 一眼假。
 * </ul>
 *
 * <h2>2. 怎么做到"没装光影也不报错"（三层保险）</h2>
 * <ol>
 *   <li><b>单独的 mixin 配置文件</b> {@code shanhai.oculus.mixin.json}，{@code "required": false}
 *       ⇒ Oculus 类不存在时 Mixin 只打一行 WARN（{@code Target class ... was not found}），
 *       不会让整个 mod 加载失败；</li>
 *   <li>{@code "injectors": {"defaultRequire": 0}} + 本类 {@code @Inject(require = 0)}
 *       ⇒ 就算 Oculus 换了内部实现（方法改名/签名变了）也只是这一条注入静默不生效，
 *       <b>不会崩游戏</b>；</li>
 *   <li>{@code remap = false} ⇒ 目标是第三方 mod 的类（名字本来就不是 SRG），不需要 refmap；
 *       同时用 {@code targets = "…"} 全限定名（含内部类 {@code $Timer}）而不是 import，
 *       这样编译期<b>根本不需要 Oculus 在类路径上</b>。</li>
 * </ol>
 *
 * <h2>3. 目标方法（1.8.0 实测，只读到 class 里的名字与签名）</h2>
 * <pre>
 *   net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer#beginFrame(J)V
 * </pre>
 * ⚠️ 这是<b>第三方内部实现</b>，Oculus 升级可能改名 ⇒ 靠上面的 {@code require = 0} 兜底。
 * 见报告「待你确认」。
 *
 * <h2>4. 哪些能停、哪些不能（诚实边界）</h2>
 * <ul>
 *   <li><b>能停</b>：太阳/阴影方向、方块光照、天空亮度（随 tick 免费冻）；水波/草动/云影/雨丝
 *       （由本 mixin 冻 {@code frameTimeCounter}）。</li>
 *   <li><b>不能停</b>：光影包自己的 uniform（比如它自算的噪声时间、屏幕空间累积、
 *       {@code frameTime} 驱动的运动模糊/曝光自适应、以及任何用 {@code iris} 之外机制取墙钟的效果）。
 *       那些不在我们的控制范围 —— 它们由光影包作者决定，只能靠"作者也用 frameTimeCounter"这一条覆盖。</li>
 * </ul>
 */
@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer", remap = false)
public abstract class ShanhaiOculusFrameTimeMixin {

    /**
     * 冻结时取消 {@code beginFrame} 的推进。
     *
     * <p>取消后 {@code frameTimeCounter} 停在暂停那一刻的值 ⇒ 所有基于它的 shader 动画定格。
     *
     * @param frameStartNanos 帧起始时间戳（Oculus 从不带 {@code pause} 门控，形参照抄以匹配描述符）
     * @param ci              取消句柄
     */
    @Inject(method = "beginFrame(J)V", at = @At("HEAD"), require = 0, cancellable = true)
    private void shanhai$freezeShaderWallClock(long frameStartNanos, CallbackInfo ci) {
        try {
            if (ShanhaiClientClock.shouldCancelOculusBeginFrame()) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 光影层绝不能把渲染带崩：出错就让它照常推进
        }
    }
}
