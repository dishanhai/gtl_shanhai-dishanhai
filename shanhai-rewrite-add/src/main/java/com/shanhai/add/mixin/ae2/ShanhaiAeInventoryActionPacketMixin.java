package com.shanhai.add.mixin.ae2;

import com.shanhai.add.time.ShanhaiClockHooks;

import appeng.core.sync.packets.InventoryActionPacket;
import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · 暂停期间 <b>AE2 第二条 action 包</b>
 * （{@code appeng.core.sync.packets.InventoryActionPacket#serverPacketData} 的 HEAD）。
 *
 * <h2>1. 它是第二条，不是唯一一条（主钩见 {@link ShanhaiAeInteractionPacketMixin}）</h2>
 * javap（15.4.10）体内只有两条分支：
 * <pre>
 *   17-36: action == InventoryAction.SET_FILTER ⇒ AEBaseMenu#setFilter(int, ItemStack)
 *   42-56: 其余                              ⇒ AEBaseMenu#doAction(ServerPlayer, InventoryAction, int, long)
 * </pre>
 * 而 {@code AEBaseMenu#doAction} 覆盖到的物品转移是：
 * <b>合成格</b>（{@code CraftingTermSlot#doClick}：把物品塞进/拿出合成格）、
 * <b>配置槽填/倒</b>（{@code FILL_ITEM}/{@code EMPTY_ITEM} → {@code GenericStackInv#extract/insert}，
 * 即"把手持容器里的液体/物品灌进网络"或反过来）、
 * <b>整区域搬运</b>（{@code MOVE_REGION}）。
 * 这些都会让物品在「玩家可见的容器/网络」之间挪动 ⇒ 暂停期间必须与主钩一致地拒。
 *
 * <h2>2. 已知的"多拦了一点"，必须写明</h2>
 * 本钩在 HEAD 整包取消，<b>不解析 {@code InventoryAction} 枚举</b>（那需要 import AE 的内部枚举类型，
 * 与"只许碰 public 入口"的红线冲突；而且我们连包里的 action 字段都不读）。
 * 因此暂停期间以下两类动作也会被一并拒（它们不移动物品）：
 * <ul>
 *   <li>{@code SET_FILTER}（给配置槽设过滤）—— 恢复后可正常设；</li>
 *   <li>{@code MOVE_REGION} 中"全是空操作"的那部分 —— 本来就没有物品可搬。</li>
 * </ul>
 * 「打开终端 / 看网络里的物品 / 滚动 / 搜索」<b>不走这个包</b>，不受影响。
 *
 * <h2>3. 三条硬约束</h2>
 * {@code require = 0} + 配置 {@code "required": false} + {@code remap = false}；
 * 类体内零 Minecraft 成员引用（判定全部下沉到
 * {@link ShanhaiClockHooks#denyAeActionPacket(Object, String)}）。
 *
 * <p>签名实证：{@code public void serverPacketData(net.minecraft.server.level.ServerPlayer)}
 * descriptor {@code (Lnet/minecraft/server/level/ServerPlayer;)V}。
 */
@Mixin(value = InventoryActionPacket.class, remap = false)
public abstract class ShanhaiAeInventoryActionPacketMixin {

    /**
     * @param player 原版形参，转交给转发层判菜单/回灌
     * @param ci     取消句柄
     */
    @Inject(
            method = "serverPacketData(Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At("HEAD"),
            require = 0,
            remap = false,
            cancellable = true)
    private void shanhai$denyAeInventoryActionPacket(ServerPlayer player, CallbackInfo ci) {
        try {
            if (ShanhaiClockHooks.denyAeActionPacket(player, "InventoryActionPacket")) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 判定坏了 ⇒ 放行
        }
    }
}
