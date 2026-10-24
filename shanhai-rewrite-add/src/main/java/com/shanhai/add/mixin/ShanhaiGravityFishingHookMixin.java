package com.shanhai.add.mixin;

import com.shanhai.add.gravity.ShanhaiGravity;

import net.minecraft.world.entity.projectile.FishingHook;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 山海 · 全量调制重力【注入点 8/17】{@code FishingHook}（钓鱼钩）。
 *
 * <h2>位置</h2>
 * {@code FishingHook#tick} 里 {@code if (!fluidstate.is(FluidTags.WATER)) setDeltaMovement(...add(0.0D, -0.03D, 0.0D))}
 * （47.3.0 sources 第 214 行）。
 *
 * <h2>理由</h2>
 * 基准 -0.03；先 {@code scale(0.92)}（不是 0.98，鱼钩的阻力本来就更小）在 224 行之前，不缩放。
 * 本文件里 {@code -0.03D} 只出现这一次。
 */
@Mixin(FishingHook.class)
public abstract class ShanhaiGravityFishingHookMixin {

    @ModifyConstant(method = "tick", constant = @Constant(doubleValue = -0.03D), require = 1)
    private static double shanhai$gravity(double vanilla) {
        return ShanhaiGravity.scale(vanilla);
    }
}
