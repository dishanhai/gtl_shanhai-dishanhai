package com.shanhai.add.mixin.client;

import com.shanhai.add.client.judge.ShanhaiClientJudge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 山海重构 · <b>判据层（消费点 · 主刀）：客户端"死亡界面被重包装"那两次读取，受保护玩家一律读成「没死」</b>
 * （2026-11-21）。
 *
 * <h2>为什么必须打这里，而不是打 {@code isDeadOrDying()} 本身</h2>
 * <pre>
 *   对手在 LivingEntity#m_21224_ / #m_21223_ 上挂的是 @At("HEAD") + cancellable + setReturnValue
 *   （其中一个 priority = Integer.MAX_VALUE）。HEAD 注入后应用者排最前 ⇒ 一旦 HEAD-cancel 并
 *   setReturnValue，**后面（含 RETURN 注入）一条都不跑**。
 *   ⇒ 抢那两个方法没用（也抢不到更高 priority）。但**调用点没人抢**：
 *     @Redirect 劫持的是"这一次读取"，与被读方法的注入完全无关。
 * </pre>
 *
 * <h2>🔴 两个消费点都是在 Forge 47.4.16 的【补丁后字节码】上核过的（不是猜的）</h2>
 * <pre>
 *   javap -p -c -classpath forge-1.20.1-47.4.16-srg.jar net.minecraft.client.Minecraft
 *
 *   ① public void m_91152_(Screen)                    ← setScreen，死亡界面的【制造处】
 *        49: aload_1 / ifnonnull 105                  ← 形参 screen 非空就跳过
 *        53: getfield f_91074_ (LocalPlayer)
 *        57: invokevirtual LocalPlayer.m_21224_:()Z   ← 🔴 我们劫持的就是这一条
 *        60: ifeq 105
 *        63: … LocalPlayer.m_108632_()Z (shouldShowDeathScreen) / 73: new DeathScreen(...) / 94: astore_1
 *      ⇒ 返回 false 之后 screen 仍是 null ⇒ 105 之后 ScreenEvent.Opening 那一段根本不会发（见 114: ifnull 146）
 *
 *   ② public void m_91398_()                         ← runTick，死亡界面的【每 tick 驱动处】
 *       138: getfield f_91080_ (Screen) / 142: ifnonnull 211     ← 当前没有界面
 *       145: getfield f_91074_ (LocalPlayer) / 149: ifnull 211
 *       156: invokevirtual LocalPlayer.m_21224_:()Z  ← 🔴 第二次读取（现场 20/s 就是它）
 *       159: ifeq 180 / 162: instanceof DeathScreen / 169: ifne 180
 *       172: aconst_null / 174: invokevirtual m_91152_(Screen)   ← 每 tick 拿 null 去 setScreen
 *      ⇒ 返回 false ⇒ 不再"每 tick 把 null 灌进 setScreen"，也就不再被重包装成 DeathScreen
 * </pre>
 * 两处各自 {@code require = 0}：一处版本漂了不会带走另一处。
 *
 * <h2>第一条为什么"劫持调用点"是对的</h2>
 * 本类<b>不改</b> {@code isDeadOrDying()} 的任何读数（那是别人的地盘，抢不到也不该抢）；
 * 它只回答"<b>这一次</b> setScreen/runTick 要不要把玩家当死的" ⇒ 语义更窄、更不可能误伤。
 *
 * <h2>负向路径</h2>
 * <pre>
 *   player == null                     ⇒ false（原版那里会 NPE；我们不给 NPE，也不造假死亡界面）
 *   不是受保护玩家（{@link ShanhaiClientJudge#reportDeadOrDying}）⇒ 原版读数（逐位放行）
 *   本类抛任何 Throwable               ⇒ false（🔴 绝不因为"我们坏了"就凭空造一个死亡界面；
 *                                          原版死亡界面仍能从死亡包那一路出来）
 * </pre>
 *
 * <h2>为什么放 {@code client} 数组</h2>
 * 目标类 {@code Minecraft} / {@code LocalPlayer} 在专用服务端不存在 ⇒ 只能挂
 * {@code shanhai_add.mixin.json} 的 {@code "client"} 数组（Forge/Mixin 只在客户端应用它）。
 */
@Mixin(Minecraft.class)
public abstract class ShanhaiSetScreenDeadJudgeMixin {

    /**
     * 消费点 ①：{@code Minecraft#setScreen} 里那次 {@code LocalPlayer#m_21224_()}。
     *
     * @param player 被读取的那个 LocalPlayer（{@code this.player}）
     * @return 这次读取要用的"死没死"：受保护玩家恒为 {@code false}
     */
    @Redirect(
            method = "m_91152_",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;m_21224_()Z"),
            remap = false,
            require = 0)
    private boolean shanhai$setScreenDeadJudge(LocalPlayer player) {
        try {
            return ShanhaiClientJudge.reportDeadOrDying(player, "setScreen");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 消费点 ②：{@code Minecraft#runTick}（{@code m_91398_}）里那次 {@code LocalPlayer#m_21224_()}。
     *
     * <p>这一处是现场"20/s 想开死亡界面"的<b>驱动源</b>：它每 tick 判死、然后拿 {@code null} 去调
     * {@code setScreen(null)}，后者再被重包装成 {@code DeathScreen}。挡住它，客户端就不再每 tick 折腾。
     *
     * @param player 被读取的那个 LocalPlayer（{@code this.player}）
     * @return 这次读取要用的"死没死"：受保护玩家恒为 {@code false}
     */
    @Redirect(
            method = "m_91398_",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;m_21224_()Z"),
            remap = false,
            require = 0)
    private boolean shanhai$runTickDeadJudge(LocalPlayer player) {
        try {
            return ShanhaiClientJudge.reportDeadOrDying(player, "runTick");
        } catch (Throwable ignored) {
            return false;
        }
    }
}
