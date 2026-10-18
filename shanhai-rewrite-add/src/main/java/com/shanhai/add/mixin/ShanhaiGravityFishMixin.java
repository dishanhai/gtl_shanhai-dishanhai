package com.shanhai.add.mixin;

import com.shanhai.add.gravity.ShanhaiGravity;

import net.minecraft.world.entity.animal.AbstractFish;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 山海 · 全量调制重力【注入点 14/17】{@code AbstractFish}（鳕鱼/鲑鱼/河豚/热带鱼的共同基类）。
 *
 * <h2>位置</h2>
 * {@code AbstractFish#travel} 的水中分支里
 * {@code if (this.getTarget() == null) setDeltaMovement(...add(0.0D, -0.005D, 0.0D))}
 * （47.3.0 sources 第 102 行）。
 *
 * <h2>理由</h2>
 * <ul>
 *   <li>基准 <b>-0.005</b>（水中"缓慢下沉"，只有 0.08 的 1/16）—— 又一处不能共用基准值的证据。</li>
 *   <li>方法限定 {@code travel}：同类里第 160 行有个 <b>+0.005D</b>（{@code MoveControl} 里的上浮），
 *       符号不同、类也不同，本注入不碰它。</li>
 *   <li>整个分支被 {@code this.isEffectiveAi() && this.isInWater()} 门控（= {@code !isClientSide}）
 *       ⇒ 客户端不跑；出水面走 {@code super.travel(...)}（那条由注入点 1 管）。</li>
 * </ul>
 */
@Mixin(AbstractFish.class)
public abstract class ShanhaiGravityFishMixin {

    @ModifyConstant(method = "travel", constant = @Constant(doubleValue = -0.005D), require = 1)
    private static double shanhai$gravity(double vanilla) {
        return ShanhaiGravity.scale(vanilla);
    }
}
