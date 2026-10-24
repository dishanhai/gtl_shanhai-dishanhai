package com.shanhai.add.mixin;

import com.shanhai.add.gravity.ShanhaiGravity;

import net.minecraft.world.entity.item.PrimedTnt;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 山海 · 全量调制重力【注入点 5/17】{@code PrimedTnt}（点燃的 TNT）。
 *
 * <h2>位置</h2>
 * {@code PrimedTnt#tick} 开头 {@code add(0.0D, -0.04D, 0.0D)}
 * （47.3.0 sources 第 55 行，{@code !this.isNoGravity()} 分支）；第 59 行 {@code scale(0.98D)} 是阻力，不缩放。
 *
 * <h2>理由</h2>
 * 基准 -0.04；G=0 时 TNT 点燃后悬空（配合 0.98 的阻力会慢慢停住），G&lt;0 时往上升 —— 这正是"全部实体都被调"。
 */
@Mixin(PrimedTnt.class)
public abstract class ShanhaiGravityPrimedTntMixin {

    @ModifyConstant(method = "tick", constant = @Constant(doubleValue = -0.04D), require = 1)
    private static double shanhai$gravity(double vanilla) {
        return ShanhaiGravity.scale(vanilla);
    }
}
