package com.shanhai.add.mixin;

import com.shanhai.add.client.time.ShanhaiClientClock;

import net.minecraft.client.Timer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 山海重构 · 客户端：渲染插值 α（{@code Timer#partialTick}）的可选钉住。
 *
 * <h2>🔴 默认<b>不</b>钉（这是与普查建议的一处刻意偏差，理由已写进类注释）</h2>
 * {@code partialTick} 同时驱动世界动画的亚 tick 分量<b>和玩家自己的相机/视角</b>
 * （{@code Entity#getEyePosition(α)}、{@code getViewYRot(α)}；{@code yRot} 每帧变、{@code yRotO} 每 tick 变）。
 * ⇒ 钉成 0 ⇒ <b>鼠标转视角完全不动</b>；钉成 0.5 ⇒ 转头速度减半、位置 20 Hz 定格。
 * 用户的口径 ② 是红线（玩家不受影响），所以默认保持 {@code partialTick} 实时；
 * "水面不流"由 {@code ShanhaiTextureManagerClockMixin} 负责，雨雪/传送门那点 ≤1/512 的锯齿肉眼不可见。
 *
 * <p>机制仍然保留并可用：`{@code -Dshanhai.clock.pinPartialTick=true}` 或
 * {@link ShanhaiClientClock#setPinPartialTick(boolean)} 打开（用于对比验收）。
 *
 * <p>为什么用"返回后回调 + 直接写 public 字段"而不是 {@code @Shadow}：
 * {@code Timer#partialTick} 在 1.20.1 是 <b>public</b> 字段（{@code Timer.java:8}），
 * 普通类直接赋值即可，不需要 {@code @Shadow}（本工程从未使用 {@code @Shadow}，见类注释的取证）。
 */
@Mixin(Timer.class)
public abstract class ShanhaiTimerClockMixin {

    @Inject(method = "advanceTime(J)I", at = @At("RETURN"), require = 1)
    private void shanhai$pinPartialTick(long wallMs, CallbackInfoReturnable<Integer> cir) {
        try {
            ShanhaiClientClock.onTimerAdvance((Object) this);
        } catch (Throwable ignored) {
            // 钉不住就让它照常跑，绝不能因为时钟逻辑让游戏卡死
        }
    }
}
