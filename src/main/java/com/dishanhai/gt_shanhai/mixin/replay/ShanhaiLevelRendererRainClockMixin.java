package com.dishanhai.gt_shanhai.mixin.replay;

import com.dishanhai.gt_shanhai.client.time.ShanhaiReplayClientFx;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · 客户端：<b>冻结期间把原版雨的相位翻成"倒着走"</b>（不藏雨）。
 *
 * <h2>上一版为什么失败</h2>
 * 上一版在这里 {@code ci.cancel()} 掉原版雨、改成自己撒水滴粒子 ⇒ 用户 13:52 录像里
 * 「雨直接不见了」（原版被藏、替代粒子看不见）。
 *
 * <h2>现在的做法</h2>
 * 原版雨<b>照常整段渲染</b>（几何/密度/渐隐一个字不改），只在 HEAD 把
 * {@code LevelRenderer#ticks} 换成<b>由回放游标驱动的递减值</b>：
 * <pre>
 *   LevelRenderer.java:291  float f1 = (float)this.ticks + partialTicks;   ← 相位
 *   LevelRenderer.java:190  private int ticks;
 * </pre>
 * 相位递减 ⇒ 雨往上走；正常时间一行都不改。
 *
 * <p>读数：{@code vanilla_hidden=false rain_visible_quads=<格数×2> rain_frames=<n>}。
 */
@Mixin(LevelRenderer.class)
public abstract class ShanhaiLevelRendererRainClockMixin {

    @Inject(method = "renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V",
            at = @At("HEAD"), require = 0)
    private void shanhai$flipRainPhase(LightTexture lightTexture, float partialTick,
                                       double camX, double camY, double camZ, CallbackInfo ci) {
        try {
            ShanhaiReplayClientFx.countRainFrame();
            if (ShanhaiReplayClientFx.rainFlipped()) {
                ((ShanhaiLevelRendererTicksAccessor) this).shanhai$setTicks(
                        ShanhaiReplayClientFx.rainTicks());
            }
        } catch (Throwable ignored) {
            // 判定坏了 ⇒ 原版相位（最多是雨往下，不影响可见性）
        }
    }
}
