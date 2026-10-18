package com.shanhai.add.mixin;

import com.shanhai.add.gravity.ShanhaiGravity;

import net.minecraft.world.entity.projectile.LlamaSpit;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 山海 · 全量调制重力【注入点 9/17】{@code LlamaSpit}（羊驼口水）。
 *
 * <h2>位置</h2>
 * {@code LlamaSpit#tick} 里
 * {@code this.setDeltaMovement(this.getDeltaMovement().add(0.0D, (double)-0.06F, 0.0D))}
 * （47.3.0 sources 第 47 行，{@code !this.isNoGravity()} 分支）。
 *
 * <h2>🔴 为什么是 {@code doubleValue = -0.05999999865889549}（不是 -0.06，也不是 float）</h2>
 * 源码写的是 {@code (double)-0.06F} —— 编译期常量表达式，javac 直接折成
 * <b>double -0.05999999865889549</b>（0.06F 的精确 double 值），常量池里【没有】-0.06 这个 double，
 * 也没有负的 float。javap 实证（47.4.16 编译产物）：
 * <pre>
 *   83: ldc    #156  // float  0.06f                        ← 源码第 39 行 "float f1 = 0.06F;"（未被使用，正号）
 *  156: ldc2_w #206  // double -0.05999999865889549d       ← 真正的重力（tick 内）
 * </pre>
 * ⇒ 写 {@code @Constant(doubleValue = -0.06D)} 会<b>匹配不到</b>（require=1 会在启动时炸，
 * 那还算是好的）；写 {@code floatValue = -0.06F} 同样匹配不到。
 * 方法限定 {@code tick} 进一步把第 39 行那个正 0.06F 的局部变量排除掉。
 */
@Mixin(LlamaSpit.class)
public abstract class ShanhaiGravityLlamaSpitMixin {

    @ModifyConstant(method = "tick", constant = @Constant(doubleValue = -0.05999999865889549D), require = 1)
    private static double shanhai$gravity(double vanilla) {
        return ShanhaiGravity.scale(vanilla);
    }
}
