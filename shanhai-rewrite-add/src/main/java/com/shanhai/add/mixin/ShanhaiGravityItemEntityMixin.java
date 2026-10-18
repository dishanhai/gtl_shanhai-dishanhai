package com.shanhai.add.mixin;

import com.shanhai.add.gravity.ShanhaiGravity;

import net.minecraft.world.entity.item.ItemEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 山海 · 全量调制重力【注入点 2/17】{@code ItemEntity}（掉落物）。
 *
 * <h2>位置</h2>
 * {@code ItemEntity#tick} 里 {@code this.setDeltaMovement(this.getDeltaMovement().add(0.0D, -0.04D, 0.0D))}
 * （47.3.0 sources 第 125 行，包在 {@code else if (!this.isNoGravity())} 分支里）。
 *
 * <h2>理由</h2>
 * <ul>
 *   <li><b>为什么改常量而不是改 {@code travel}/速度</b>：这一句就是掉落物唯一的重力施加点；
 *       它前后的 {@code *0.98}（第 145 行）是空气阻力，<b>不该</b>跟着缩放（缩放阻力会顺手改掉终端速度）。</li>
 *   <li><b>为什么是 doubleValue = -0.04D</b>：javac 把 {@code -0.04D} 作为<b>一个</b>负数常量压栈
 *       （不是 `ldc 0.04; dneg`）；本文件里 {@code -0.04D} 只出现这一次（已 grep 核过）。
 *       方法限定 {@code tick} ⇒ 不会误伤同类的其它方法。</li>
 *   <li><b>客户端也跑</b>：这段在 {@code if (this.level().isClientSide)} 分支<b>之前</b>
 *       （第 128 行才是客户端分支），所以两端各跑一份 ⇒ 两端的 G 必须一致（由 S2C 包保证），
 *       否则画面抖/瞬移。</li>
 * </ul>
 */
@Mixin(ItemEntity.class)
public abstract class ShanhaiGravityItemEntityMixin {

    @ModifyConstant(method = "tick", constant = @Constant(doubleValue = -0.04D), require = 1)
    private static double shanhai$gravity(double vanilla) {
        return ShanhaiGravity.scale(vanilla);
    }
}
