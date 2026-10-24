package com.shanhai.add.mixin;

import com.shanhai.add.gravity.ShanhaiGravity;

import net.minecraft.world.entity.animal.Turtle;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 山海 · 全量调制重力【注入点 17/17】{@code Turtle}（海龟）。
 *
 * <h2>位置</h2>
 * {@code Turtle#travel} 的水中分支 {@code add(0.0D, -0.005D, 0.0D)}
 * （47.3.0 sources 第 297 行）。
 *
 * <h2>理由</h2>
 * 基准 -0.005；{@code Turtle} 自己覆写 {@code travel}，必须单独一处。
 * 方法限定 {@code travel}：同类第 503 行 {@code MoveControl} 里有个 <b>+0.005D</b> 的上浮，不碰。
 */
@Mixin(Turtle.class)
public abstract class ShanhaiGravityTurtleMixin {

    @ModifyConstant(method = "travel", constant = @Constant(doubleValue = -0.005D), require = 1)
    private static double shanhai$gravity(double vanilla) {
        return ShanhaiGravity.scale(vanilla);
    }
}
