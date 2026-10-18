package com.shanhai.add.mixin;

import com.shanhai.add.gravity.ShanhaiGravity;

import net.minecraft.world.entity.projectile.ThrownExperienceBottle;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 山海 · 全量调制重力【注入点 7b/17】{@code ThrownExperienceBottle}（经验瓶）的重力常量。
 *
 * <p>javap 实测：{@code ThrownExperienceBottle#getGravity()F} 方法体只有
 * {@code 0: ldc // float 0.07f} + {@code freturn} ⇒ 常量唯一，无需 {@code ordinal}。
 *
 * <p>它与 {@link ShanhaiGravityThrowableMixin}（基类 0.03f）、
 * {@link ShanhaiGravityPotionMixin}（药水 0.05f）是同一族的三个常量，必须<b>分别</b>注入：
 * {@code getGravity()} 是虚方法，子类覆写之后基类那一段不会被执行。
 */
@Mixin(ThrownExperienceBottle.class)
public abstract class ShanhaiGravityXpBottleMixin {

    /**
     * @param vanilla 原版常量（0.07f）
     * @return 缩放后的值
     */
    @ModifyConstant(method = "getGravity", constant = @Constant(floatValue = 0.07F), require = 1)
    private float shanhai$scaledGravity(float vanilla) {
        return ShanhaiGravity.scaleF(vanilla);
    }
}
