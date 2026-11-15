package com.shanhai.add.mixin;

import java.util.Map;
import java.util.Queue;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 山海重构 · 客户端：{@code ParticleEngine#particles} 的只读访问器。
 *
 * <p>用途只有一条：给 {@code particles_live=} 数"场上现在有几个粒子"。
 * 用户口径（2026-11-14 13:52）：粒子必须<b>倒流期间</b>就在动，不是攒到结束才喷。
 * 上一版这个数一直是 {@code -1}（取不到），等于这条判据形同不存在 ⇒ 这里修成真值。
 *
 * <p>⚠️ 只读：不提供 setter，绝不改粒子引擎状态。
 */
@Mixin(ParticleEngine.class)
public interface ShanhaiParticleEngineAccessor {

    @Accessor("particles")
    Map<ParticleRenderType, Queue<Particle>> shanhai$particles();
}
