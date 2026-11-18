package com.dishanhai.gt_shanhai.mixin.replay;

import net.minecraft.client.particle.Particle;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 山海重构 · 客户端：{@code Particle} 的坐标 / 年龄 / 重力的访问器。
 *
 * <p>用途（用户 2026-11-14 13:52：「他这个粒子显然不是倒流的好吧」）：
 * <b>逐 tick</b> 把粒子的速度重新指向格心、{@code gravity=0}、{@code age} 递减
 * —— 只改一次初速是不够的（粒子仍然受重力下坠、年龄往前走）。
 */
@Mixin(Particle.class)
public interface ShanhaiParticleAccessor {

    @Accessor("x")
    double shanhai$x();

    @Accessor("y")
    double shanhai$y();

    @Accessor("z")
    double shanhai$z();

    @Accessor("age")
    int shanhai$age();

    @Accessor("age")
    void shanhai$setAge(int age);

    @Accessor("gravity")
    void shanhai$setGravity(float gravity);
}
