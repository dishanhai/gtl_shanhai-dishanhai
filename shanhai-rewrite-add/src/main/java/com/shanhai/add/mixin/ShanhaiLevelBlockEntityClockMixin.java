package com.shanhai.add.mixin;

import com.shanhai.add.client.time.ShanhaiClientClock;

import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · 客户端：方块实体的 tick 也停（{@code Level#tickBlockEntities}）。
 *
 * <p>为什么需要：{@code ClientLevel#tickEntities()} 的最后一句就是 {@code this.tickBlockEntities()}。
 * GT 机器的客户端动画（转子/进度条/激光）、箱子盖开合、信标光柱、刷怪笼的客户端表现
 * 全都挂在这条链上 —— 不冻它，机器看起来还在转。
 *
 * <p>本 mixin 只列在 {@code shanhai.mixin.json} 的 {@code client} 段 ⇒ 专用服务端不会应用它。
 * （客户端只有 {@code ClientLevel} 会调到这里；服务端那边整个 {@code ServerLevel#tick} 已经被取消了。）
 */
@Mixin(Level.class)
public abstract class ShanhaiLevelBlockEntityClockMixin {

    @Inject(method = "tickBlockEntities()V", at = @At("HEAD"), require = 1, cancellable = true)
    private void shanhai$freezeBlockEntities(CallbackInfo ci) {
        try {
            if (ShanhaiClientClock.freezeBlockEntities()) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 同上
        }
    }
}
