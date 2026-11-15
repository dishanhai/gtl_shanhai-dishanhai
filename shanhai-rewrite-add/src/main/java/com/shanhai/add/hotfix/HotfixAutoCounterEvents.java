package com.shanhai.add.hotfix;

import com.shanhai.add.ShanhaiAdd;

import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 主动热改的两个时机：<b>进世界就订阅</b>，<b>退出世界／停服就全部还原</b>。
 *
 * <h2>为什么订阅要放在 {@code ServerStartedEvent}</h2>
 * 与预热（{@link HotfixEvents}）、重力、时钟同一个时点 —— 判读日志时只有一个时间基准；
 * 而且此刻本体已经打完 {@code Done (…)!}，分诊后面每一拍都会走。
 * 两个订阅者（本类与 {@link HotfixEvents}）互不依赖，各自 try/catch。
 *
 * <h2>为什么"退出世界"必须还原</h2>
 * 改动是<b>进程内</b>的（{@code retransformClasses}），进程一退自然没了。但单机里
 * "退出世界"<b>不等于</b>进程结束（回到标题界面还是同一个 JVM）：下一个世界会复用同一个
 * 类加载器，于是<b>上一局改过的方法还是改着的</b>。
 * ⇒ 退出世界／停服时一律 {@link HotfixAutoCounter#undoAll}，
 * 让下一局从"干净态"开始（这也是 {@code /shanhai_add hotfix undoall} 的自动版）。
 *
 * <h2>失败姿势</h2>
 * 只打日志、只返回，绝不冒泡到事件总线（订阅者抛异常会污染总线，而"没还原成"不该影响世界）。
 */
@Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HotfixAutoCounterEvents {

    private HotfixAutoCounterEvents() {
        // 纯静态订阅
    }

    /** 进世界：接线 ＋ 订阅本体分诊日志（幂等）。 */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        try {
            HotfixAutoCounterGlue.ensure("server_started");
        } catch (Throwable t) {
            HotfixAutoCounterGlue.LOG.line("tap trigger=server_started installed=false reason="
                    + HotfixAutoCounter.token("exception_" + t.getClass().getSimpleName())
                    + " policy=record_only note=只记录不反制");
        }
    }

    /** 停服开始：先把内存里的原字节还原（此时类都还在，还原最可靠）。 */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        undoAll("server_stopping");
    }

    /** 已停服（单机回到标题界面就会走到这里，JVM 还在）。再兜一次，免得 Stopping 那一步抛过异常。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        undoAll("server_stopped");
    }

    private static void undoAll(String trigger) {
        try {
            HotfixAutoCounterGlue.bind();
            HotfixAutoCounter.undoAll(trigger);
        } catch (Throwable t) {
            try {
                HotfixAutoCounterGlue.LOG.line("auto_counter_undoall trigger=" + HotfixAutoCounter.token(trigger)
                        + " count=? restored=0 failed=? detail="
                        + HotfixAutoCounter.token("exception_" + t.getClass().getSimpleName())
                        + " policy=record_only note=只记录不反制");
            } catch (Throwable ignored) {
                // 连日志都打不出去：什么也不做（进程内的改动退出即失效）
            }
        }
    }
}
