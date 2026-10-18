package com.shanhai.add.mixin;

import com.shanhai.add.time.ShanhaiClockHooks;

import java.util.function.BooleanSupplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;

/**
 * 山海重构 · <b>暂停时间：服务端的落点</b>。
 *
 * <h2>1. 🔴 为什么挂在 {@code ServerLevel#tick} 的 HEAD，而不是 {@code MinecraftServer#runServer}</h2>
 * 普查（{@code temp\time-census\报告.md} A①）的硬结论：keep-alive 在 tick 管道里
 * （{@code MinecraftServer.tickChildren} → {@code getConnection().tick()} →
 * {@code ServerGamePacketListenerImpl.tick()}，{@code :301-312}，15000 ms），
 * 而客户端/服务端两边都有 {@code ReadTimeoutHandler(30)} 且全 jar 无人移除。
 * ⇒ <b>停整条 runServer 循环 = 30 秒双向超时断线</b>。
 *
 * <p>所以停的是 <b>levels 循环里的那一句 {@code serverlevel.tick(...)}</b>。
 * 等价落点有两条：
 * <ul>
 *   <li>在 {@code MinecraftServer#tickChildren} 里 {@code @Redirect} 掉这个调用 —— 需要
 *       {@code @At(target="Lnet/minecraft/server/level/ServerLevel;tick(...)V")}，
 *       而本工程的 mixin 处理器只把 {@code @Inject} 的方法名写进 refmap
 *       （{@code build\mixinrefmap\shanhai.refmap.json} 实证），<b>{@code @At} 的成员目标不会被映射</b>
 *       ⇒ 生产环境会拿开发名去找 SRG 名 ⇒ 静默失效或崩。这条路被排除。</li>
 *   <li><b>直接在 {@code ServerLevel#tick} 的 HEAD 取消</b> —— 只用到"被注入的方法名"，
 *       而那个名字一定会进 refmap（与既有的 {@code ShanhaiSetBlockWatchMixin} 同款）。✅ 本类选这条。</li>
 * </ul>
 *
 * <h2>2. 逐项：跳过了什么、保留了什么</h2>
 * {@code ServerLevel#tick(BooleanSupplier)} 会被整段跳过，即下面这些<b>全部不跑</b>：
 * {@code getWorldBorder().tick()}、{@code advanceWeatherCycle()}、睡觉/唤雷逻辑、
 * {@code updateSkyBrightness()}、{@code tickTime()}（⇒ {@code gameTime}/{@code dayTime} 不增）、
 * {@code blockTicks.tick(...)}、{@code fluidTicks.tick(...)}、{@code raids.tick()}、
 * {@code getChunkSource().tick(...)}（⇒ 区块加载/卸载、随机 tick、刷怪、实体追踪 全停）、
 * {@code runBlockEvents()}、{@code entityManager.tick()}（⇒ 所有实体，<b>含服务端玩家实体</b>）。
 * <p><b>保留</b>（它们在 {@code tickChildren} 里、本方法之外）：{@code ++tickCount}、
 * {@code getFunctions().tick()}、{@code if (tickCount % 20 == 0) synchronizeTime(level)}
 * （每秒发一次<b>冻结的</b> dayTime/gameTime，客户端因此不会自己漂）、
 * {@code getConnection().tick()}（<b>keep-alive 与收包</b>）、{@code playerList.tick()}、
 * {@code tickables}、以及 {@code runServer} 里那个写死的 {@code nextTickTime += 50L} 定拍。
 * ⇒ 网络层完全活着（≥60 秒不掉线），世界层完全停住。
 *
 * <h2>3. 为什么玩家还能动</h2>
 * 玩家的移动包走的是"主线程任务"（{@code PacketUtils.ensureRunningOnSameThread}），
 * 最终落到 {@code ServerGamePacketListenerImpl.handleMovePlayer} → {@code player.absMoveTo(...)}；
 * 它<b>不经过</b> level tick ⇒ 世界冻结时玩家位置照样被服务端接受。
 * （副作用：服务端侧的玩家实体 tick 也停了 ⇒ 饥饿/药水计时/容器同步一并不走。
 *   这是"世界停住"的应有之义，已写入报告「待你确认」。）
 *
 * <h2>4. 本类体内零 Minecraft 成员引用</h2>
 * 理由与 {@code ShanhaiSetBlockWatchMixin} 类注释 §2 完全相同：混入类只当转发壳。
 */
@Mixin(ServerLevel.class)
public abstract class ShanhaiServerLevelTickClockMixin {

    /**
     * 冻结时直接取消整个 level tick。
     *
     * @param haveTime 原版形参（"这个 tick 还有没有时间预算"），本注入不使用，照抄以匹配描述符
     * @param ci       取消句柄
     */
    @Inject(
            method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At("HEAD"),
            require = 1,
            cancellable = true)
    private void shanhai$freezeLevelTick(BooleanSupplier haveTime, CallbackInfo ci) {
        try {
            // 🔴 不是"什么都不跑"：转发里会先补两段(①区块源 tick ②玩家自身 tick)，
            //    见 ShanhaiClockHooks#freezeServerLevelTick 类注释的"三段式"。
            if (ShanhaiClockHooks.freezeServerLevelTick((Object) this, haveTime)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 第二道保险：时钟坏了也绝不能让世界 tick 停住（否则服务器看起来"卡死"）
        }
    }
}
