package com.shanhai.add.mixin;

import com.shanhai.add.client.time.ShanhaiClientClock;

import net.minecraft.client.renderer.texture.TextureManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · 客户端：<b>贴图动画帧不推进</b> —— 这才是"水面不流"的真正落点。
 *
 * <h2>🔴 为什么"水面流动"不归 {@code LevelRenderer.ticks} 也不归 {@code partialTick} 管</h2>
 * 水/岩浆/火/下界传送门/海晶灯这些方块的动画来自 {@code .mcmeta} 的帧序列，
 * 由 {@code TextureManager#tick()} 每客户端 tick 遍历所有 {@code Tickable} 推进一帧。
 * 推进器是 {@code SpriteContents$Ticker#tickAndUpload}：
 * <pre>
 *   ++this.subFrame;
 *   if (this.subFrame &gt;= frame.time) { this.frame = (this.frame + 1) % frames.size(); this.subFrame = 0; upload }
 * </pre>
 * ⇒ 它是<b>帧计数器</b>，既不读 {@code Util.getMillis()}（墙钟），也不读 {@code partialTick}。
 * 所以只冻 tick/partialTick 是<b>冻不住水面</b>的；必须冻这里。
 *
 * <p>⚠️ {@code Minecraft.tick()} 里这一句<b>没有</b> {@code !pause} 门控（{@code Minecraft.java:1758-1761}），
 * 所以它跟"打开暂停菜单"的原生暂停是不一样的，必须我们自己取消。
 */
@Mixin(TextureManager.class)
public abstract class ShanhaiTextureManagerClockMixin {

    @Inject(method = "tick()V", at = @At("HEAD"), require = 1, cancellable = true)
    private void shanhai$freezeTextureAnimation(CallbackInfo ci) {
        try {
            if (ShanhaiClientClock.freezeTextureAnimation()) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 同上
        }
    }
}
