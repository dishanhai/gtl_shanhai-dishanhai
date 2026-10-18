package com.shanhai.add.mixin;

import com.shanhai.add.gravity.ShanhaiGravity;

import net.minecraft.world.entity.vehicle.Boat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 山海 · 全量调制重力【注入点 12/17】{@code Boat}（船）。
 *
 * <h2>位置</h2>
 * {@code Boat#floatBoat} 里
 * {@code double d0 = (double)-0.04F;}（第 573 行，本版<b>未被使用</b>）与
 * {@code double d1 = this.isNoGravity() ? 0.0D : (double)-0.04F;}（第 574 行，真正施加在 603 行）
 * —— 47.3.0 sources。
 *
 * <h2>🔴 为什么是 {@code doubleValue = -0.03999999910593033}（不是 -0.04，也不是 float）</h2>
 * 源码是 {@code (double)-0.04F}：编译期常量表达式，javac 折成 <b>double -0.03999999910593033</b>
 * （0.04F 的精确 double 值）。javap 实证（47.4.16 编译产物）：
 * <pre>
 *   floatBoat 内: 0: ldc2_w #928 // double -0.03999999910593033d   ← d0（未使用）
 *                15: ldc2_w #928 // double -0.03999999910593033d   ← d1（真正生效）
 *   整个类里 -0.03999999910593033 只有这两处（都在 floatBoat）
 * </pre>
 * ⇒ 写 {@code @Constant(doubleValue = -0.04D)} 匹配不到（差 1e-9 也是不匹配）。
 *
 * <h2>为什么两条一起缩放是安全的</h2>
 * 两条都是重力常量；{@code d0} 本版没人读，缩放它对行为零影响，不缩放反而会在以后某版被使用时漏掉。
 * <b>不能扩大到整个类</b>：{@code controlBoat} 里还有一个 {@code f += 0.04F}（第 630 行，正 float 0.04f），
 * 那是"按前进键加水推力"，跟着缩放会让船走不动 —— 本注入的 {@code floatBoat} 限定正好把它排除。
 *
 * <h2>两端</h2>
 * 本地玩家在船上时 {@code isControlledByLocalInstance()} 为真、客户端也 {@code floatBoat + move}
 * ⇒ 两端必须同一个 G（S2C 包保证），否则船抖/被拉回。
 */
@Mixin(Boat.class)
public abstract class ShanhaiGravityBoatMixin {

    @ModifyConstant(method = "floatBoat", constant = @Constant(doubleValue = -0.03999999910593033D), require = 1)
    private static double shanhai$gravity(double vanilla) {
        return ShanhaiGravity.scale(vanilla);
    }
}
