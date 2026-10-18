package com.shanhai.add.mixin;

import com.shanhai.add.gravity.ShanhaiGravity;

import net.minecraft.world.entity.projectile.AbstractArrow;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 山海 · 全量调制重力【注入点 6/17】{@code AbstractArrow}（箭 / 药箭 / 光谱箭 / 三叉戟）。
 *
 * <h2>位置</h2>
 * {@code AbstractArrow#tick} 里
 * {@code this.setDeltaMovement(vec34.x, vec34.y - (double)0.05F, vec34.z)}
 * （47.3.0 sources 第 262 行，{@code !this.isNoGravity() && !flag} 分支）。
 *
 * <h2>🔴 为什么是 {@code doubleValue = 0.05000000074505806} 而不是 {@code floatValue = 0.05F}</h2>
 * 这一处<b>踩过坑</b>，用 javap 对 47.4.16 的编译产物逐条核过：
 * <pre>
 *   javap -p -c ...AbstractArrow | grep 0.05
 *     908: ldc_w   #516  // float  0.05f                    ← 源码第 249 行 "float f1 = 0.05F;"（本版【未被使用】）
 *    1028: ldc2_w  #540  // double 0.05000000074505806d     ← 真正的重力（tick 内）
 *      49: ldc2_w  #540  // double 0.05000000074505806d     ← onHitBlock 里的 vec3.normalize().scale(...)
 * </pre>
 * 原因：{@code (double)0.05F} 是<b>编译期常量表达式</b>，javac 直接把它折成一个 <b>double</b> 常量
 * （0.05000000074505806 = 0.05F 的精确 double 值），根本不会留下 float 常量。
 * ⇒ 若按 {@code @Constant(floatValue = 0.05F)} 写，命中的是第 908 行那个<b>没人读的</b> f1，
 * {@code require=1} 也照样满足 —— 结果是"注入成功、重力没变"的静默失败（本工程最坏的失败形态）。
 * <p>方法限定 {@code tick} 把第 49 行（onHitBlock，那是反弹位移不是重力）排除掉了。
 */
@Mixin(AbstractArrow.class)
public abstract class ShanhaiGravityAbstractArrowMixin {

    @ModifyConstant(method = "tick", constant = @Constant(doubleValue = 0.05000000074505806D), require = 1)
    private static double shanhai$gravity(double vanilla) {
        return ShanhaiGravity.scale(vanilla);
    }
}
