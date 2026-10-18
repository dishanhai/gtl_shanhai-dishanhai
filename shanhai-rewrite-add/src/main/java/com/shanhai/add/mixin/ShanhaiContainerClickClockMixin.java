package com.shanhai.add.mixin;

import com.shanhai.add.time.ShanhaiClockHooks;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · 「冻结期间容器/机器界面里的物品转移一律拒绝」。
 *
 * <p>为什么必须挂在 {@code AbstractContainerMenu#clicked}（而不是 Forge 事件）：
 * 1.20.1 <b>没有</b>任何可取消的"容器点击"事件（{@code ContainerScreenEvent} 只有客户端、且不可取消）。
 * 所以这是服务端唯一的漏斗：{@code ServerboundContainerClickPacket} →
 * {@code ServerGamePacketListenerImpl.handleContainerClick} → {@code menu.clicked(...)}。
 *
 * <p>口径：<b>玩家自己的背包放行</b>（{@code InventoryMenu}），其余（箱子/熔炉/GT 机器/交易）拒绝。
 * 详细理由见 {@link ShanhaiClockHooks#denyContainerClick}。
 *
 * <p>⚠️ 客户端也会走这个方法（客户端本地预测）。两边用的是同一份镜像状态 ⇒ 一起拒绝，不会出现
 * "客户端拿起来了、服务端没有"的幽灵物品；万一有一帧错位，服务端下发的槽位快照会把它纠正回来。
 */
@Mixin(AbstractContainerMenu.class)
public abstract class ShanhaiContainerClickClockMixin {

    @Inject(
            method = "clicked(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"),
            require = 1,
            cancellable = true)
    private void shanhai$denyContainerClick(int slotId, int button, ClickType clickType, Player player,
                                            CallbackInfo ci) {
        try {
            // 2026-10-18：多传 clickType + slotId —— 客户端自己背包里"丢到地上"（-999）与 THROW
            // 也会先 safeTake/清 carried 再 player.drop(...)，被 ItemTossEvent 取消就是凭空消失，
            // 所以这两种点击即使菜单是 InventoryMenu 也要拒（见 ShanhaiClockRules#denyContainerClick）。
            if (ShanhaiClockHooks.denyContainerClick((Object) this, player, clickType, slotId)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 判定坏了 ⇒ 放行（不能因为时钟逻辑让玩家没法整理背包）
        }
    }
}
