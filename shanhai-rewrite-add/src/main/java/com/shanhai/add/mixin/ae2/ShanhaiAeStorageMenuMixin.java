package com.shanhai.add.mixin.ae2;

import com.shanhai.add.time.ShanhaiClockHooks;

import appeng.menu.me.common.MEStorageMenu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · 暂停期间 <b>AE 终端的纵深第三道</b>
 * （{@code appeng.menu.me.common.MEStorageMenu#handleInteraction(long, InventoryAction)} 的 HEAD）。
 *
 * <h2>1. 它挡的是什么（与两个包钩的分工）</h2>
 * <table border="1">
 *   <caption>三道 AE 拦截</caption>
 *   <tr><th>层</th><th>目标</th><th>挡得住什么 / 挡不住什么</th></tr>
 *   <tr><td>主钩</td><td>{@code MEInteractionPacket#serverPacketData}</td>
 *       <td>挡得住"客户端发来的取/放"（用户实测那条路）；<b>挡不住</b>AE 内部或加装模组
 *           直接调 {@code handleInteraction} 的路径</td></tr>
 *   <tr><td>副钩</td><td>{@code InventoryActionPacket#serverPacketData}</td>
 *       <td>挡得住另一条 action 包（合成格/配置槽/整区域搬运）</td></tr>
 *   <tr><td><b>本类</b></td><td>{@code MEStorageMenu#handleInteraction}</td>
 *       <td>挡得住<b>所有</b>最终落到这个 public 入口的调用（包只是其中一条来路）；
 *           但<b>只在能定位到服务端玩家时取消</b> —— 客户端那一份故意放行，
 *           否则玩家连一句黄字都收不到（详见 {@link ShanhaiClockHooks#denyAeMenuInteraction(Object, String)} 方法头）</td></tr>
 * </table>
 *
 * <h2>2. 为什么只挂 {@code handleInteraction}（不挂 {@code handleNetworkInteraction}）</h2>
 * {@code handleNetworkInteraction(ServerPlayer, AEKey, InventoryAction)} 的实参里有
 * {@code appeng.api.stacks.AEKey}（AE 的内部数据模型）。本工程对第三方 mod 的红线是
 * <b>只许碰 public 入口、不许依赖它的字段/内部类</b> ⇒ 只挂 {@code handleInteraction(long, InventoryAction)}：
 * 它的形参只有 {@code long} 与一个公共枚举，handler 连参数都不用取。
 *
 * <h2>3. 🔴 为什么不从 AE 的字段里取玩家</h2>
 * 菜单里有 {@code getPlayerInventory()} 之类，但那要碰 AE 的内部结构。
 * 这里改用原版 API 反查（{@code MinecraftServer#getPlayerList()} 里谁的 {@code containerMenu} 是这一个，
 * 且只在服务端线程做），逻辑全在 {@link ShanhaiClockHooks#denyAeMenuInteraction(Object, String)}。
 * 查不到（客户端那一份菜单 / 玩家已关界面）⇒ <b>不取消</b>，让包照常飞到服务端由主钩拦下并给提示。
 *
 * <h2>4. 三条硬约束</h2>
 * <ol>
 *   <li>{@code require = 0} + 配置 {@code "required": false} ⇒ 上游改签名只是一层静默失效；</li>
 *   <li>{@code remap = false} ⇒ AE2 的方法名不需要 SRG 映射；</li>
 *   <li>类体内零 Minecraft 成员引用；handler 只收 {@code CallbackInfo}（不取目标形参 ⇒
 *       不必 import {@code appeng.helpers.InventoryAction}）。</li>
 * </ol>
 *
 * <p>签名实证（15.4.10）：{@code public final void handleInteraction(long, appeng.helpers.InventoryAction)}
 * descriptor {@code (JLappeng/helpers/InventoryAction;)V}。
 * 目标方法本身是 {@code final} —— 对注入没有影响（final 只禁覆写）。
 */
@Mixin(value = MEStorageMenu.class, remap = false)
public abstract class ShanhaiAeStorageMenuMixin {

    /**
     * @param ci 取消句柄
     */
    @Inject(
            method = "handleInteraction(JLappeng/helpers/InventoryAction;)V",
            at = @At("HEAD"),
            require = 0,
            remap = false,
            cancellable = true)
    private void shanhai$denyAeMenuInteraction(CallbackInfo ci) {
        try {
            if (ShanhaiClockHooks.denyAeMenuInteraction((Object) this, "MEStorageMenu")) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 判定坏了 ⇒ 放行
        }
    }
}
