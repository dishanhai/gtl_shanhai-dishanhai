package com.shanhai.add.mixin;

import com.shanhai.add.time.ShanhaiClockHooks;

import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · <b>缺陷 ② 「AE 能拿不能放」的服务端漏斗</b>。
 *
 * <h2>1. 为什么原来只拦到"放回去"</h2>
 * 旧实现只有 {@code ShanhaiContainerClickClockMixin}（挂在 {@code AbstractContainerMenu#clicked} 的 HEAD）。
 * 而 AE2 的终端菜单<b>覆盖了</b> {@code clicked}：网络槽自己处理（<b>不调 super</b>），
 * 只有普通槽才交给 super ⇒ 我们的拦截在 AE 界面里只对"普通槽"生效，网络槽的"取"完全不经过我们。
 * 这就是"可以从 AE 里拿东西，但是放不回去"。
 *
 * <h2>2. 为什么这一层一定盖得住</h2>
 * {@code ServerboundContainerClickPacket} 在服务端<b>只有这一个</b>入口：
 * <pre>
 *   ServerGamePacketListenerImpl#handleContainerClick(ServerboundContainerClickPacket)   // :1535
 *     → player.containerMenu.clicked(slot, button, clickType, player)                    // :1550
 * </pre>
 * 它不关心菜单是哪个子类、有没有覆写 ⇒ 在 HEAD 取消，AE 的自处理路径也进不来。
 *
 * <h2>3. 口径</h2>
 * 暂停 且 {@code player.containerMenu} 不是 {@code InventoryMenu} ⇒ 整包拒（取与放对称）。
 * 玩家自己背包（整理）照常；客户端预判的纠正由 {@code sendAllDataToRemote()} 完成
 * （见 {@link ShanhaiClockHooks#denyContainerPacketClick}）。
 *
 * <h2>4. 这一层与 {@code AbstractContainerMenu#clicked} 那层的关系</h2>
 * <b>纵深防御，不是二选一</b>：{@code clicked} 那层还负责<b>客户端本地预判</b>（客户端也走 {@code clicked}），
 * 拦掉它客户端就不会先"拿走"一格 ⇒ 少一次回灌闪烁。两层都保留。
 *
 * <p>⚠️ 本类体内零 Minecraft 成员引用（只有类型 + {@code (Object) this} + 普通类静态调用）。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ShanhaiContainerPacketClockMixin {

    /**
     * @param packet 原版形参，本注入不使用，照抄以匹配描述符
     * @param ci     取消句柄
     */
    @Inject(
            method = "handleContainerClick(Lnet/minecraft/network/protocol/game/ServerboundContainerClickPacket;)V",
            at = @At("HEAD"),
            require = 1,
            cancellable = true)
    private void shanhai$denyContainerClickPacket(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        try {
            if (ShanhaiClockHooks.denyContainerPacketClick((Object) this)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 判定坏了 ⇒ 放行（不能让时钟逻辑把容器玩崩）
        }
    }
}
