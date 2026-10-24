package com.shanhai.add.mixin;

import com.shanhai.add.gravity.ShanhaiGravity;

import net.minecraft.world.entity.projectile.ThrownPotion;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 山海 · 全量调制重力【注入点 7c/17】{@code ThrownPotion}（药水）的重力常量。
 *
 * <p>javap 实测：{@code ThrownPotion#getGravity()F} 方法体第 0 条指令就是
 * {@code ldc // float 0.05f}（后面还有别的逻辑，但 0.05f 在这个方法里只出现一次）。
 *
 * <p>三者关系见 {@link ShanhaiGravityThrowableMixin} 的类注释。
 */
@Mixin(ThrownPotion.class)
public abstract class ShanhaiGravityPotionMixin {

    /**
     * @param vanilla 原版常量（0.05f）
     * @return 缩放后的值
     */
    @ModifyConstant(method = "getGravity", constant = @Constant(floatValue = 0.05F), require = 1)
    private float shanhai$scaledGravity(float vanilla) {
        return ShanhaiGravity.scaleF(vanilla);
    }
}
