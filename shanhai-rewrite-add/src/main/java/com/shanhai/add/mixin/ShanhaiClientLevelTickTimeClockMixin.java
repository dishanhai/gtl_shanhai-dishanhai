package com.shanhai.add.mixin;

import com.shanhai.add.client.time.ShanhaiClientClock;

import net.minecraft.client.multiplayer.ClientLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · 客户端第 2 处时钟：{@code ClientLevel#tickTime()}（{@code gameTime/dayTime} 不增）。
 *
 * <p>为什么必须单独钉这一处：客户端的世界时间是<b>自己每 tick +1</b> 的
 * （{@code ClientLevel.java:224-230}：{@code setGameTime(getGameTime()+1)}、{@code setDayTime(getDayTime()+1)}），
 * 不等服务器。只冻服务端 ⇒ 太阳/月亮/天空亮度/<b>光照贴图</b>照样往前走（普查 A② 的"半冻"）。
 *
 * <p>⚠️ 只取消 {@code tickTime()}，<b>不</b>取消 {@code ClientLevel#tick}：后者还负责
 * {@code chunkSource.tick(...)}（客户端区块加载/卸载）—— 那条留着，玩家才能边走边看。
 */
@Mixin(ClientLevel.class)
public abstract class ShanhaiClientLevelTickTimeClockMixin {

    @Inject(method = "tickTime()V", at = @At("HEAD"), require = 1, cancellable = true)
    private void shanhai$freezeClientWorldTime(CallbackInfo ci) {
        try {
            if (ShanhaiClientClock.freezeClientTickTime()) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 同上：绝不把客户端 tick 带崩
        }
    }
}
