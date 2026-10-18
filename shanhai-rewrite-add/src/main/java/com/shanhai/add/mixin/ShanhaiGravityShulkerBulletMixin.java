package com.shanhai.add.mixin;

import com.shanhai.add.gravity.ShanhaiGravity;

import net.minecraft.world.entity.projectile.ShulkerBullet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 山海 · 全量调制重力【注入点 10/17】{@code ShulkerBullet}（潜影贝子弹）。
 *
 * <h2>位置</h2>
 * {@code ShulkerBullet#tick} 里"没有目标"分支：
 * {@code if (!this.isNoGravity()) setDeltaMovement(...add(0.0D, -0.04D, 0.0D))}
 * （47.3.0 sources 第 204 行）。
 *
 * <h2>理由</h2>
 * 基准 -0.04。<b>只在这一支里</b>：有目标时走 {@code 0.2} 插值追踪（第 208 行起），那是制导不是重力。
 * 整段被 {@code !this.level().isClientSide}（第 194 行）包着 ⇒ <b>客户端不跑</b>，无两端一致问题；
 * 但 mixin 仍然两端都装（同一份类），只是客户端那条分支永远不会被走到。
 */
@Mixin(ShulkerBullet.class)
public abstract class ShanhaiGravityShulkerBulletMixin {

    @ModifyConstant(method = "tick", constant = @Constant(doubleValue = -0.04D), require = 1)
    private static double shanhai$gravity(double vanilla) {
        return ShanhaiGravity.scale(vanilla);
    }
}
