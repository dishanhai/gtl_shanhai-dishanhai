package com.shanhai.add.mixin.client;

import com.shanhai.add.client.judge.ShanhaiClientJudge;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · <b>判据层（消费点 · 第二条路）：受保护玩家的「战斗死亡包」整个不处理</b>（2026-11-21）。
 *
 * <h2>为什么这条路必须单独拦（它<b>完全不看血量</b>）</h2>
 * <pre>
 *   javap -p -c -classpath forge-1.20.1-47.4.16-srg.jar net.minecraft.client.multiplayer.ClientPacketListener
 *     public void m_142747_(ClientboundPlayerCombatKillPacket)      ← 补丁后与 vanilla 逐字一致
 *        9-17: level.getEntity(packet.m_179078_())                 ← 只看包里的 playerId
 *       21-29: if (该实体 == minecraft.f_91074_(LocalPlayer))
 *       32-42: if (LocalPlayer.m_108632_() (shouldShowDeathScreen))
 *       45-70:    minecraft.m_91152_(new DeathScreen(packet.m_179079_(), hardcore))   ← 直接 new！
 *       76-83:   else LocalPlayer.m_7583_() (respawn)
 * </pre>
 * 关键点：这条路上的 {@code DeathScreen} 是<b>直接 new</b> 出来的，而且传给 {@code m_91152_} 的 Screen
 * <b>非空</b> ⇒ {@code setScreen} 里"screen == null 才重包装"的那一段（{@code ShanhaiSetScreenDeadJudgeMixin}
 * 劫持的 {@code m_21224_} 读取）<b>根本不在路上</b>。
 * 它也不读血量、不读 {@code isDeadOrDying}，只读一个客户端布尔 ⇒ 只能靠<b>拦包</b>。
 *
 * <h2>取消之后会怎样（逐条）</h2>
 * <pre>
 *   死亡界面            不会开（73-70 那段不跑）
 *   自动重生请求        不会发（76-83 的 respawn 也不跑）—— 这正是我们要的：人还在，不该重生
 *   别的玩家受影响      ❌ 不会：判据里要求 playerId == 自己，见 {@link ShanhaiClientJudge#suppressCombatKill}
 *   没受保护时           ❌ 不取消（isGuarded=false ⇒ 原样放行 ⇒ 原版行为逐字不变）
 * </pre>
 *
 * <h2>为什么 {@code HEAD} + {@code cancellable} 而不是 {@code @Redirect} 里面的 {@code new DeathScreen}</h2>
 * 本方法一共只有四件事（查实体 / 比身份 / 开界面 / 重生），<b>全部</b>都是"这个人死了"的后果。
 * 在 HEAD 上取消 ⇒ 一条都不执行，比逐个 redirect 少三处注入点，也少三次"版本漂了就静默失效"的机会。
 * 包里的内容不落任何状态（{@code ClientboundPlayerCombatKillPacket} 是只读数据类）——丢掉它没有副作用。
 *
 * <h2>为什么 {@code require = 0}</h2>
 * 与 {@code shanhai_add.mixin.json} 的 {@code "required": false} / {@code defaultRequire: 0} 同口径：
 * 找不到就只打一行 WARN，整合包照常起（项目血账：{@code require = 1} 会让整个服务端起不来）。
 *
 * <h2>为什么放 {@code client} 数组</h2>
 * {@code ClientPacketListener} 在专用服务端不存在 ⇒ 只能挂 {@code "client"} 数组。
 */
@Mixin(ClientPacketListener.class)
public abstract class ShanhaiCombatKillGuardMixin {

    /**
     * @param packet 死亡包（只读；判据与日志全在普通类里）
     * @param ci     回调（本类只可能 {@code cancel()}）
     */
    @Inject(
            method = "m_142747_",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0)
    private void shanhai$refuseCombatKill(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
        try {
            if (ShanhaiClientJudge.suppressCombatKill(packet)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 第二道保险：我们坏了，也绝不许改变原版的死亡包处理（不 cancel）
        }
    }
}
