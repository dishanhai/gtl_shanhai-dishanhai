package com.shanhai.add.mixin;

import com.shanhai.add.gravity.ShanhaiGravity;

import net.minecraft.world.entity.animal.Squid;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 山海 · 全量调制重力【注入点 13/17】{@code Squid}（鱿鱼）。
 *
 * <h2>位置</h2>
 * {@code Squid#aiStep} 里 {@code d1 -= 0.08D;}
 * （47.3.0 sources 第 145 行，包在 {@code if (!this.level().isClientSide)} 里）。
 *
 * <h2>理由</h2>
 * <ul>
 *   <li>它是 <b>0.08</b>，不是 0.04 / 0.005 —— 这是"各基准值不同、不能共用 0.08"的最好反例：
 *       它恰好和生物轨的 0.08 同值，但<b>走的是另一条写死路径</b>（{@code Squid} 是 {@code WaterAnimal}，
 *       这里是自己 {@code aiStep} 里直接减，不是 {@code LivingEntity#travel} 的 {@code d0}）。</li>
 *   <li>⚠️ 常量是<b>正的 0.08D</b>（{@code d1 -= 0.08D} 编译成 {@code ldc 0.08; dsub}），
 *       不是 -0.08 ⇒ {@code @Constant(doubleValue = 0.08D)}。乘 G 后语义自动正确：
 *       G=0 ⇒ 不减；G=-1 ⇒ 减 -0.08 = 加 0.08（上浮）。</li>
 *   <li>客户端不跑这段（{@code !isClientSide}）⇒ 无两端分叉。</li>
 *   <li>同方法的 {@code LEVITATION} 分支（第 143 行）是漂浮药水，不动。</li>
 * </ul>
 */
@Mixin(Squid.class)
public abstract class ShanhaiGravitySquidMixin {

    @ModifyConstant(method = "aiStep", constant = @Constant(doubleValue = 0.08D), require = 1)
    private static double shanhai$gravity(double vanilla) {
        return ShanhaiGravity.scale(vanilla);
    }
}
