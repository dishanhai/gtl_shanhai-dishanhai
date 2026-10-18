package com.shanhai.add.mixin;

import com.shanhai.add.gravity.ShanhaiGravity;

import net.minecraft.world.entity.item.FallingBlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 山海 · 全量调制重力【注入点 4/17】{@code FallingBlockEntity}（下落方块，沙/砾石/铁砧等）。
 *
 * <h2>位置</h2>
 * {@code FallingBlockEntity#tick} 里 {@code add(0.0D, -0.04D, 0.0D)}
 * （47.3.0 sources 第 116 行，{@code !this.isNoGravity()} 分支），随后第 119 行 {@code move(...)}。
 *
 * <h2>理由</h2>
 * 基准 -0.04；这一句在 {@code !this.level().isClientSide} 判断<b>之前</b> ⇒ 客户端也跑，两端必须同一个 G。
 */
@Mixin(FallingBlockEntity.class)
public abstract class ShanhaiGravityFallingBlockMixin {

    @ModifyConstant(method = "tick", constant = @Constant(doubleValue = -0.04D), require = 1)
    private static double shanhai$gravity(double vanilla) {
        return ShanhaiGravity.scale(vanilla);
    }
}
