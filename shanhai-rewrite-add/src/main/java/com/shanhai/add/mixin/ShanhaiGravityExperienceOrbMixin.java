package com.shanhai.add.mixin;

import com.shanhai.add.gravity.ShanhaiGravity;

import net.minecraft.world.entity.ExperienceOrb;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 山海 · 全量调制重力【注入点 3/17】{@code ExperienceOrb}（经验球）。
 *
 * <h2>位置</h2>
 * {@code ExperienceOrb#tick} 里 {@code setDeltaMovement(...add(0.0D, -0.03D, 0.0D))}
 * （47.3.0 sources 第 62 行，{@code else if (!this.isNoGravity())} 分支）。
 *
 * <h2>理由</h2>
 * 基准值是 <b>-0.03</b>（不是掉落物的 -0.04，也不是生物的 0.08）⇒ 必须"自己的基准 × G"。
 * 紧随其后的 {@code *0.98}（第 97 行）是阻力，不缩放。
 * 岩浆分支（第 66 行）是随机弹跳，不是重力，不动。
 */
@Mixin(ExperienceOrb.class)
public abstract class ShanhaiGravityExperienceOrbMixin {

    @ModifyConstant(method = "tick", constant = @Constant(doubleValue = -0.03D), require = 1)
    private static double shanhai$gravity(double vanilla) {
        return ShanhaiGravity.scale(vanilla);
    }
}
