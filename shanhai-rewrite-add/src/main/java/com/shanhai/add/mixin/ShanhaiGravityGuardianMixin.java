package com.shanhai.add.mixin;

import com.shanhai.add.gravity.ShanhaiGravity;

import net.minecraft.world.entity.monster.Guardian;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 山海 · 全量调制重力【注入点 16/17】{@code Guardian}（守卫者）。
 *
 * <h2>位置</h2>
 * {@code Guardian#travel} 的水中分支 {@code add(0.0D, -0.005D, 0.0D)}
 * （47.3.0 sources 第 330 行）。
 *
 * <h2>理由</h2>
 * 基准 -0.005；{@code Guardian} 自己覆写 {@code travel}，必须单独一处。
 * 它的门控是 {@code isControlledByLocalInstance() && isInWater()}（不是 {@code isEffectiveAi}）；
 * 守卫者不会被玩家骑，所以客户端那条不成立 ⇒ 客户端不跑。
 */
@Mixin(Guardian.class)
public abstract class ShanhaiGravityGuardianMixin {

    @ModifyConstant(method = "travel", constant = @Constant(doubleValue = -0.005D), require = 1)
    private static double shanhai$gravity(double vanilla) {
        return ShanhaiGravity.scale(vanilla);
    }
}
