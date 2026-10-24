package com.shanhai.add.mixin;

import com.shanhai.add.gravity.ShanhaiGravity;

import net.minecraft.world.entity.animal.Dolphin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 山海 · 全量调制重力【注入点 15/17】{@code Dolphin}（海豚）。
 *
 * <h2>位置</h2>
 * {@code Dolphin#travel} 的水中分支 {@code add(0.0D, -0.005D, 0.0D)}
 * （47.3.0 sources 第 333 行）。
 *
 * <h2>理由</h2>
 * 基准 -0.005；{@code Dolphin} 自己覆写了 {@code travel}（没有复用 {@code AbstractFish} 那份），
 * 所以<b>必须单独注入一处</b> —— 这就是普查报告说的"四种水生生物各自独立实现，不能一处覆盖"。
 * 门控 {code isEffectiveAi()} ⇒ 客户端不跑；出水面走 {@code super.travel}（注入点 1 管）。
 */
@Mixin(Dolphin.class)
public abstract class ShanhaiGravityDolphinMixin {

    @ModifyConstant(method = "travel", constant = @Constant(doubleValue = -0.005D), require = 1)
    private static double shanhai$gravity(double vanilla) {
        return ShanhaiGravity.scale(vanilla);
    }
}
