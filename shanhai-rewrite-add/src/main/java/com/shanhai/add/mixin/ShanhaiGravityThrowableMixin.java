package com.shanhai.add.mixin;

import com.shanhai.add.gravity.ShanhaiGravity;

import net.minecraft.world.entity.projectile.ThrowableProjectile;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 山海 · 全量调制重力【注入点 7/17】{@code ThrowableProjectile} 家族（雪球 / 鸡蛋 / 末影珍珠 …）的<b>基类</b>重力。
 *
 * <h2>1. 位置与取值（javap 实测，不是猜）</h2>
 * <pre>
 *   net.minecraft.world.entity.projectile.ThrowableProjectile#getGravity()F
 *     Code:
 *        0: ldc   // float 0.03f
 *        2: freturn
 * </pre>
 * 子类覆写自己那一份（javap 实测）：
 * <ul>
 *   <li>{@code ThrownExperienceBottle#getGravity} → {@code float 0.07f}（经验瓶）</li>
 *   <li>{@code ThrownPotion#getGravity} → {@code float 0.05f}（药水）</li>
 * </ul>
 * 三个常量各在<b>各自的方法体</b>里，所以拆成三个 mixin 各管一段（本类管基类 0.03f）。
 *
 * <h2>2. 🔴 为什么【不】用 {@code @Redirect} 去改 {@code tick} 里那次调用（改过一次，踩了坑）</h2>
 * 第一版写的是「{@code @Redirect} 掉 {@code tick} 里的 {@code this.getGravity()} 调用 + {@code @Invoker} 取原值」，
 * 冒烟直接失败：
 * <pre>
 *   InvalidInjectionException: 'static' modifier of handler method does not match target
 *     in ThrowableProjectile::shanhai$scaledGravity
 * </pre>
 * 即 {@code @Redirect} 处理器写成了 {@code static}，而目标方法 {@code getGravity()} 是实例方法 ⇒ 修饰符必须一致。
 * 更要紧的是第二层原因：本工程 refmap <b>只导出 {@code @Inject} 的方法名</b>
 * （{@code -AdisableTargetExport=true}），{@code @At(target = "L…;getGravity()F")} 这种字符串目标
 * <b>拿不到 SRG 映射</b> ⇒ 即使在开发环境能跑，生产包里也很可能匹配不到。
 * ⇒ 结论：**只用 {@code @ModifyConstant} + 方法名**（方法名会被 refmap 映射），不碰 at-target、不用 {@code @Invoker}。
 *
 * <h2>3. 口径</h2>
 * 原值 × G（{@link ShanhaiGravity#scaleF(float)}）；G=1 时逐字返回原值。
 */
@Mixin(ThrowableProjectile.class)
public abstract class ShanhaiGravityThrowableMixin {

    /**
     * 把基类那一段重力常量乘上 G。
     *
     * @param vanilla 原版常量（0.03f）
     * @return 缩放后的值
     */
    @ModifyConstant(method = "getGravity", constant = @Constant(floatValue = 0.03F), require = 1)
    private float shanhai$scaledGravity(float vanilla) {
        return ShanhaiGravity.scaleF(vanilla);
    }
}
