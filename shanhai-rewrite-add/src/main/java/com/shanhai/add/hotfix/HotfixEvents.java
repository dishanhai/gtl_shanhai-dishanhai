package com.shanhai.add.hotfix;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.ShanhaiAdd;

import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 预热时机：<b>服务端/世界起来那一刻</b>（{@link ServerStartedEvent}）。
 *
 * <h2>为什么是这个事件（选它与否掉别人，都写在这）</h2>
 * <ol>
 *   <li><b>它同时覆盖"专用服务端"和"单机"。</b>单机里集成服务器跑在客户端同一个 JVM 里，
 *       {@code ServerStartedEvent} 照样触发 ⇒ 一个订阅点就同时满足"服务端进世界 / 客户端进世界"
 *       两件事，不需要按 {@code FMLEnvironment.dist} 分叉（分叉一多，"跑起来的是哪条分支"
 *       就得靠日志猜了）。</li>
 *   <li><b>此刻世界已加载完</b>（服务端已打过 {@code Done (…)!}），不是"类还在疯狂加载"的启动早段
 *       —— 虽然实测在启动 1.5s 就 attach 也成功，但那时 {@code MinecraftServer} 还没加载，
 *       {@code getAllLoadedClasses()} 只有 1716 个；预热的价值是"拿到能力"，越稳越好。</li>
 *   <li><b>一次服务端生命周期只触发一次</b>（换维度不会触发，回标题再进世界会新建一个服务端 ⇒
 *       再触发一次，但 {@link HotfixBootstrap#prewarmOnce} 那道闩会跳过，只留一行
 *       {@code reason=already_installed|already_attempted}）。</li>
 *   <li><b>本工程已把它当"世界起来"的标准时点</b>（重力 {@code ShanhaiGravityEvents.onServerStarted}、
 *       时钟 {@code ShanhaiClockInit.onServerStarted} 都在这里）⇒ 判读日志时只有一个时间基准。</li>
 * </ol>
 *
 * <h3>被否掉的候选（以及否掉的理由）</h3>
 * <table border="1">
 *   <tr><th>候选</th><th>为什么不用</th></tr>
 *   <tr><td>{@code FMLCommonSetupEvent}</td>
 *       <td>太早：世界还不在，且那是启动关键路径 —— 在这里挂 1~30s 的 attach
 *           会直接拉长启动时间（也可能和 ModLauncher 正在做的类加载互相干扰）。</td></tr>
 *   <tr><td>{@code LevelEvent.Load}（每次进世界/换维度）</td>
 *       <td>按维度触发、次数不可控；"只做一次"会被迫靠闩实现，而闩一置上就再也看不到世界时机的真值。</td></tr>
 *   <tr><td>{@code PlayerEvent.PlayerLoggedInEvent}</td>
 *       <td>要有玩家。专用服务端空转（或我们的无头冒烟环境）永远不触发；而且每个玩家登录都会走一次。</td></tr>
 *   <tr><td>{@code ClientPlayerNetworkEvent.LoggingIn}</td>
 *       <td>只在客户端；连远程服务器时才触发，<b>覆盖不到专用服务端</b>，且要额外的 dist 分叉。</td></tr>
 *   <tr><td>每 tick 轮询"世界好了没"</td>
 *       <td>白白给主线程加活；而且失败形态是"永远不触发"，比"触发但失败"更难查。</td></tr>
 * </table>
 *
 * <h2>它只做两件极便宜的事</h2>
 * 置闩 + 起一个 daemon 线程（见 {@link HotfixBootstrap#prewarmOnce}）—— <b>attach 不在主线程上跑</b>。
 * 这里整体再包一层 try/catch：事件订阅者抛异常会污染事件总线，而"预热没成"绝不该影响世界。
 */
@Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HotfixEvents {

    private HotfixEvents() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        try {
            HotfixBootstrap.prewarmOnce("server_started");
        } catch (Throwable t) {
            // 只记录不反制：连"起线程"都失败也只留一行
            ShanhaiMod.LOGGER.error("{} event=prewarm trigger=server_started state=degraded"
                            + " reason=event_handler_exception exception={} elapsed_ms=0"
                            + " policy=record_only note=只记录不反制",
                    HotfixBootstrap.PREFIX, t.toString(), t);
        }
    }
}
