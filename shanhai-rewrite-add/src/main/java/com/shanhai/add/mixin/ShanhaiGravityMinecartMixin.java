package com.shanhai.add.mixin;

import com.shanhai.add.gravity.ShanhaiGravity;

import net.minecraft.world.entity.vehicle.AbstractMinecart;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 山海 · 全量调制重力【注入点 11/17】{@code AbstractMinecart}（矿车）。
 *
 * <h2>位置</h2>
 * {@code AbstractMinecart#tick} 里
 * {@code double d0 = this.isInWater() ? -0.005D : -0.04D;}
 * （47.3.0 sources 第 302 行）—— <b>一句里两个常量</b>，所以要两条注入。
 *
 * <h2>理由</h2>
 * <ul>
 *   <li>空中 -0.04、水里 -0.005（水里阻力大，原版就是 1/8）：两个都是重力，都要 × G。</li>
 *   <li><b>客户端不跑这段</b>：第 284-299 行客户端只按 {@code lSteps} 插值 ⇒ 不存在两端分叉问题。</li>
 *   <li>轨道上的运动是另一套（沿轨速度），本来就不吃重力，这里不动。</li>
 * </ul>
 */
@Mixin(AbstractMinecart.class)
public abstract class ShanhaiGravityMinecartMixin {

    /** 空中：-0.04。 */
    @ModifyConstant(method = "tick", constant = @Constant(doubleValue = -0.04D), require = 1)
    private static double shanhai$airGravity(double vanilla) {
        return ShanhaiGravity.scale(vanilla);
    }

    /** 水里：-0.005。 */
    @ModifyConstant(method = "tick", constant = @Constant(doubleValue = -0.005D), require = 1)
    private static double shanhai$waterGravity(double vanilla) {
        return ShanhaiGravity.scale(vanilla);
    }
}
