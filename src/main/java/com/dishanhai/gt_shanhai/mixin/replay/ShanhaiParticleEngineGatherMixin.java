package com.dishanhai.gt_shanhai.mixin.replay;

import com.dishanhai.gt_shanhai.client.time.ShanhaiReplayClientFx;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · 客户端：<b>回放期间的粒子初速度改成"朝格心收"</b>（倒放的粒子）。
 *
 * <h2>1. 用户口径（2026-11-14 13:26 录像）</h2>
 * <blockquote>「一些粒子特效是倒流结束那一瞬间出现的，而不是随着方块出现而出现，而且<b>也不是倒放的</b>」</blockquote>
 * ⇒ 正常挖掘 = 碎块从方块中心<b>向外</b>飞；倒流必须是碎块<b>从四面聚回中心</b>。
 *
 * <h2>2. 为什么只能在客户端做</h2>
 * 服务端 {@code ClientboundLevelParticlesPacket} 里只有"位置偏移"和 {@code maxSpeed}
 * （{@code ClientLevel#addParticle} 把偏移当随机分布、把 maxSpeed 当随机速度倍率），
 * <b>没有指定初速度方向的通道</b>。所以方向只能在粒子创建出来的那一刻改。
 *
 * <p>算法剥成纯函数 {@code ShanhaiReplayParticleVelocity#inward}（自检 X5 断言"与向外的点积 ≤ 0"）。
 *
 * <p>⚠️ 只在 {@code REPLAY} 生效：正常时间/暂停一条都不改。
 */
@Mixin(ParticleEngine.class)
public abstract class ShanhaiParticleEngineGatherMixin {

    @Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("TAIL"), require = 1)
    private void shanhai$gatherParticles(Particle particle, CallbackInfo ci) {
        try {
            ShanhaiReplayClientFx.gather(particle);
        } catch (Throwable ignored) {
            // 改速度失败 ⇒ 粒子照原版走（最多是方向没倒放）
        }
    }
}
