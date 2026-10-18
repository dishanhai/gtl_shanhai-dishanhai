package com.shanhai.add.mixin;

import com.shanhai.add.client.time.ShanhaiClientClock;

import net.minecraft.client.renderer.LevelRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · 客户端第 1 处时钟：{@code LevelRenderer#tick()}（{@code ticks} 不增）。
 *
 * <p>1.20.1 的 {@code LevelRenderer#tick()} 只做两件事：{@code ++ticks}，以及每 20 tick 清理一次
 * "方块破坏进度"缓存。冻结时整段跳过 ⇒ 雨/雪/末地传送门/云的动画时钟停住。
 *
 * <p>⚠️ 剩下的亚 tick 锯齿（{@code ticks + partialTick}）刻意<b>不</b>处理：
 * 钉死 {@code partialTick} 会连玩家转视角一起冻住（理由与取证见
 * {@code ShanhaiClientClock} 类注释 §2）。实际可见的影响只有雨雪纹理的 ≤1/512 抖动。
 *
 * <p>附带职责：每次客户端 tick 顺手调一次 {@link ShanhaiClientClock#onClientTick()}（ack 心跳）。
 */
@Mixin(LevelRenderer.class)
public abstract class ShanhaiLevelRendererTickClockMixin {

    @Inject(method = "tick()V", at = @At("HEAD"), require = 1, cancellable = true)
    private void shanhai$freezeWorldAnimationClock(CallbackInfo ci) {
        try {
            ShanhaiClientClock.onClientTick();
            if (ShanhaiClientClock.freezeLevelRendererTick()) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 客户端时钟坏了绝不能把渲染循环带崩
        }
    }
}
