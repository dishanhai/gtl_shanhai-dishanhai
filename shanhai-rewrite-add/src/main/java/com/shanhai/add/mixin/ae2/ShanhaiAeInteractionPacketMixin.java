package com.shanhai.add.mixin.ae2;

import com.shanhai.add.time.ShanhaiClockHooks;

import appeng.core.sync.packets.MEInteractionPacket;
import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · 暂停期间 <b>「AE 终端里的物品进/出玩家背包」的主钩</b>
 * （{@code appeng.core.sync.packets.MEInteractionPacket#serverPacketData} 的 HEAD）。
 *
 * <h2>1. 为什么主钩是它，而不是任务书里写的 {@code InventoryActionPacket}</h2>
 * <b>javap 原文</b>（{@code libs\appliedenergistics2-forge-15.4.10.jar}，2026-10-18 核过）：
 * <pre>
 *   MEInteractionPacket#serverPacketData(ServerPlayer)                       ← 本类挂这里
 *     5: aload_3 / 6: instanceof appeng/menu/me/common/IMEInteractionHandler
 *    17: 取 player.containerMenu.containerId 与包里的 containerId 比
 *    32-41: invokeinterface IMEInteractionHandler.handleInteraction:(JLappeng/helpers/InventoryAction;)V
 *
 *   InventoryActionPacket#serverPacketData(ServerPlayer)                     ← 另一条（见兄弟类）
 *    17-39: 只有两条分支：action==SET_FILTER ⇒ AEBaseMenu.setFilter(int, ItemStack)
 *                          其余            ⇒ AEBaseMenu.doAction(ServerPlayer, InventoryAction, int, long)
 *    ⇒ 它【不调】handleInteraction
 * </pre>
 * 全 jar 扫常量池：引用 {@code handleInteraction} 的只有 {@code MEInteractionPacket}、
 * {@code IMEInteractionHandler}、{@code MEStorageMenu}、客户端 {@code MEStorageScreen}。
 * 客户端 {@code MEStorageScreen#slotClicked} 里"点网络格子"的每一个分支（拿起 / 单放 / Shift 搬整叠 /
 * 拖区域 / 自动合成）都调 {@code MEStorageMenu#handleInteraction}，而它客户端第一句就是
 * {@code if (isClientSide()) sendToServer(new MEInteractionPacket(...))}。
 * ⇒ <b>只挂 {@code InventoryActionPacket} 的话"拿出来"照样成功</b>（正是用户 2026-10-18 实测的现象）。
 *
 * <h2>2. 三条硬约束（AE2 是第三方 mod）</h2>
 * <ol>
 *   <li>{@code require = 0} + 本类所在配置 {@code "required": false} ⇒ 上游改名/改签名时
 *       代价只是"少一层拦截"，<b>绝不把整合包启动打崩</b>；</li>
 *   <li>{@code remap = false} ⇒ AE2 的类/方法名本来就不是 SRG，不需要映射；</li>
 *   <li>类体内<b>零 Minecraft 成员引用</b>（只有类型 + 一次普通类静态调用 + 常量字符串）——
 *       本工程实测：refmap 只记 {@code @Inject} 选中的方法名，混入类体里的 MC 调用
 *       在生产环境会拿开发名去找 SRG 命名的目标 ⇒ 混入失败。所有判定下沉到
 *       {@link ShanhaiClockHooks#denyAeActionPacket(Object, String)}。</li>
 * </ol>
 *
 * <h2>3. 判定与提示</h2>
 * 暂停 且 当前菜单不是 {@code InventoryMenu} ⇒ 整包取消（取与放对称）；
 * 提示/计数复用 {@code ShanhaiClockGuard} 的 {@code CONTAINER} 类别（与"原版容器点击"同一句黄字、
 * 同一套 5 秒 1 行节流），另打一行 {@code ae_blocked src=…} 以便分清是哪一层拦的。
 *
 * <p>⚠️ 「打开终端 / 看网络里的物品 / 滚动 / 搜索」<b>不走这个包</b>（服务器→客户端是
 * {@code MEInventoryUpdatePacket}，开界面是 {@code SwitchGuisPacket}）⇒ 不会被误伤。
 *
 * <p>签名实证：{@code public void serverPacketData(net.minecraft.server.level.ServerPlayer)}
 * descriptor {@code (Lnet/minecraft/server/level/ServerPlayer;)V}。
 */
@Mixin(value = MEInteractionPacket.class, remap = false)
public abstract class ShanhaiAeInteractionPacketMixin {

    /**
     * @param player 原版形参（{@code serverPacketData} 的唯一实参），转交给转发层判菜单/回灌
     * @param ci     取消句柄
     */
    @Inject(
            method = "serverPacketData(Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At("HEAD"),
            require = 0,
            remap = false,
            cancellable = true)
    private void shanhai$denyAeInteractionPacket(ServerPlayer player, CallbackInfo ci) {
        try {
            if (ShanhaiClockHooks.denyAeActionPacket(player, "MEInteractionPacket")) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 判定坏了 ⇒ 放行（不能因为时钟逻辑把 AE 终端玩崩）
        }
    }
}
