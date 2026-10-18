package com.shanhai.add.mixin;

import com.shanhai.add.client.time.ShanhaiClientClock;

import net.minecraft.client.multiplayer.ClientLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · 客户端：冻结期间不再生成<b>新的</b>环境粒子（{@code ClientLevel#animateTick}）。
 *
 * <p>与"粒子停在原地"是两件事：那个由 {@code ParticleEngine#tick} 的取消负责
 * （已在飘的粒子不再更新位置/寿命）；这里管的是"火把继续冒新火星、下界传送门继续吐粒子"
 * —— 不冻它的话，画面里会不断出现新粒子，一眼就露出"时间没停"。
 */
@Mixin(ClientLevel.class)
public abstract class ShanhaiClientLevelAnimateTickClockMixin {

    @Inject(method = "animateTick(III)V", at = @At("HEAD"), require = 1, cancellable = true)
    private void shanhai$freezeAmbientParticles(int x, int y, int z, CallbackInfo ci) {
        try {
            if (ShanhaiClientClock.freezeAnimateTick()) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 同上
        }
    }
}
