package com.shanhai.add.mixin;

import com.shanhai.add.client.time.ShanhaiClientClock;

import net.minecraft.client.player.LocalPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 山海重构 · <b>缺陷 ① 的客户端一半</b>（{@code LocalPlayer#drop(boolean)}）。
 *
 * <h2>为什么客户端也要拦</h2>
 * 原版（47.3.0 sources `LocalPlayer.java:279-285`）：
 * <pre>
 *   public boolean drop(boolean p_108701_) {
 *      Action action = p_108701_ ? DROP_ALL_ITEMS : DROP_ITEM;
 *      ...
 *      ItemStack itemstack = this.getInventory().removeFromSelected(p_108701_);   // ← 本地先扣！
 *      this.connection.send(new ServerboundPlayerActionPacket(action, ...));      // ← 再发包
 *      return !itemstack.isEmpty();
 *   }
 * </pre>
 * 调用点：{@code Minecraft#handleKeybinds} 第 1942-1946 行（{@code while (keyDrop.consumeClick())}）。
 *
 * <p>🔴 所以<b>只拦服务端是不够的</b>：客户端会先把本地背包减一（界面立刻少一个），
 * 服务端拒绝后不回滚（它本来就没动）⇒ 界面上少了、实际没少，等下一次容器同步才"跳回来"。
 * 在 HEAD 返回 false ⇒ 本地不减、也不发包 ⇒ 两端一致、无闪烁。
 *
 * <p>返回值语义：{@code false} = "没丢成"，{@code Minecraft#handleKeybinds} 因此不会挥手（正确表现）。
 */
@Mixin(LocalPlayer.class)
public abstract class ShanhaiClientPlayerDropClockMixin {

    /**
     * @param dropAll 原版形参（true = 整叠丢），本注入不使用，照抄以匹配描述符
     * @param cir     返回值句柄
     */
    @Inject(method = "drop(Z)Z", at = @At("HEAD"), require = 1, cancellable = true)
    private void shanhai$denyClientDrop(boolean dropAll, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (ShanhaiClientClock.denyLocalDrop()) {
                cir.setReturnValue(Boolean.FALSE);
            }
        } catch (Throwable ignored) {
            // 同上：判定坏了就放行
        }
    }
}
