package com.shanhai.add.mixin;

import com.shanhai.add.client.time.ShanhaiClientClock;

import net.minecraft.client.particle.ParticleEngine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · 客户端：<b>粒子冻在空中</b>。
 *
 * <p>关键是"已经飘着的也停"，所以冻的是 {@code ParticleEngine#tick()}（推进位置/寿命/碰撞的那一层），
 * <b>不是</b>渲染或生成。取消它之后：
 * <ul>
 *   <li>粒子的 {@code x == xo}（上一帧位置不再推进）⇒ 渲染插值无论 α 取什么都得到同一个点 ⇒ 定格；</li>
 *   <li>寿命不推进 ⇒ 不会一边停一边消失；</li>
 *   <li>新粒子由 {@code ShanhaiClientLevelAnimateTickClockMixin} 一并挡住更彻底。</li>
 * </ul>
 */
@Mixin(ParticleEngine.class)
public abstract class ShanhaiParticleEngineClockMixin {

    @Inject(method = "tick()V", at = @At("HEAD"), require = 1, cancellable = true)
    private void shanhai$freezeParticles(CallbackInfo ci) {
        try {
            if (ShanhaiClientClock.freezeParticles()) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 粒子系统出问题也不能把客户端 tick 带崩
        }
    }
}
