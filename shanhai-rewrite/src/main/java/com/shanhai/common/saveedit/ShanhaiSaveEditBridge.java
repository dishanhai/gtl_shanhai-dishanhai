package com.shanhai.common.saveedit;

import com.shanhai.ShanhaiMod;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.util.concurrent.CompletableFuture;

/**
 * 「确认修改并重载」的<b>公共侧调度</b>：第 ① 步（存盘）在服务端线程做完，剩下 ②~④ 交给客户端。
 *
 * <h2>一、为什么切成两半</h2>
 * <pre>
 *   服务端线程（本类）：① saveEverything(true, flush=true, false)
 *         │  这把世界"定格"在存档点上，之后的一切改动都以它为基线
 *         ▼
 *   客户端线程（{@code client.saveedit.ShanhaiSaveEditReload}）：
 *      ② level.disconnect() + clearLevel(加载屏)   ⇐ 阻塞到 stopServer 做完
 *      ③ 【窗口内】改 playerdata                    ⇐ 只有这里允许落盘
 *      ④ 直接重开同一个存档（不经过标题、不经过世界选择页）
 * </pre>
 * 分成两半的唯一原因：{@code Minecraft#clearLevel} 必须在客户端主线程上跑，而
 * {@code saveEverything} 必须在服务端线程上跑。两半之间用 {@link ShanhaiSaveEditFlow} 串起来。
 *
 * <h2>二、🔴 只在单机（集成服务端）可用</h2>
 * 专服没有 {@code Minecraft} 实例 ⇒ 没有"直接跳加载页重开"这回事。这里用
 * {@link FMLEnvironment#dist} 与 {@code MinecraftServer#isDedicatedServer()} 两个条件把它挡在门外，
 * 并如实回一句中文（<b>不假装成功</b>）。
 *
 * <h2>三、失败语义</h2>
 * 返回 {@code null} = 已经启动（后续失败会由客户端侧写日志 + 给屏）；
 * 返回非 {@code null} = 当场被拒（原因直接回显给玩家）。
 * 被拒时<b>什么都不会改</b>：flow 要么没开始，要么已经 {@code fail()} 放锁。
 */
public final class ShanhaiSaveEditBridge {

    public static final String PREFIX = ShanhaiSaveEditFlow.PREFIX;

    private ShanhaiSaveEditBridge() {}

    /**
     * 启动一次"存档直改 + 重载"（不判"上一次运行属于哪个面板"）。
     *
     * @return {@code null} = 已启动；否则是"为什么没启动"的中文
     */
    public static String confirm(ServerPlayer player, ShanhaiSaveEditRunContext ctx, ShanhaiPlayerDataEdit edit) {
        return confirm(player, ctx, edit, null);
    }

    /**
     * 启动一次"存档直改 + 重载"。
     *
     * @param owner 这一次运行所属的面板对象（服务端那份 {@code ShanhaiSaveEditFactory.Holder}）。
     *              只喂给 {@link ShanhaiSaveEditFlow#begin(String, Object)} 的"上一次那个面板是不是
     *              已经不在了"判据 —— 那条判据是"一进面板就永远 BUSY"的第二道保险。
     * @return {@code null} = 已启动；否则是"为什么没启动"的中文
     */
    public static String confirm(ServerPlayer player, ShanhaiSaveEditRunContext ctx,
                                 ShanhaiPlayerDataEdit edit, Object owner) {
        if (player == null || ctx == null) {
            return ShanhaiSaveEditFactory.STATUS_FAILED + "缺玩家或存档路径";
        }
        // 🔴 闸门 ⓪：算不出 levelId（或 level.dat 不在）⇒ 连存盘都不做。
        //    现场（2026-11-14 09:57）：levelId="." ⇒ validateAndCreateAccess(".") 指到不存在的
        //    saves\. ⇒ 重开必然掉回原版「数据包有错误」屏。这种时候**不许停服**（停了就回不去）。
        if (!ctx.hasLevelId()) {
            ShanhaiMod.LOGGER.error("{} level_id_unresolved candidates={} world_dir={} reason=confirm_rejected",
                    PREFIX, ctx.levelIdCandidatesLine(), ctx.worldDir);
            return ShanhaiSaveEditFactory.STATUS_FAILED + "算不出存档目录名（level_id_unresolved "
                    + ctx.levelIdCandidatesLine() + "），没停服也没改盘";
        }
        if (edit == null || edit.isEmpty()) {
            return ShanhaiSaveEditFactory.STATUS_NO_CHANGE;
        }
        if (edit.errors != null && !edit.errors.isEmpty()) {
            return "输入有问题：" + String.join("／", edit.errors);
        }
        // ① 能不能做（dist 检查必须在动任何东西之前）
        if (FMLEnvironment.dist != Dist.CLIENT) {
            ShanhaiMod.LOGGER.warn("{} confirm_rejected reason=not_client dist={}", PREFIX, FMLEnvironment.dist);
            return ShanhaiSaveEditFactory.STATUS_NOT_SINGLEPLAYER;
        }
        final MinecraftServer server = player.getServer();
        if (server == null) {
            return ShanhaiSaveEditFactory.STATUS_FAILED + "拿不到服务器";
        }
        if (server.isDedicatedServer()) {
            ShanhaiMod.LOGGER.warn("{} confirm_rejected reason=dedicated_server", PREFIX);
            return ShanhaiSaveEditFactory.STATUS_NOT_SINGLEPLAYER;
        }
        if (ctx.hasServerResources) {
            ShanhaiMod.LOGGER.warn("{} confirm_rejected reason=server_resources world={}", PREFIX, ctx.levelId);
            return ShanhaiSaveEditFactory.STATUS_HAS_SERVER_PACK;
        }
        // ② 全局互斥（并发第二次直接被拒；判据 ① 的第三段读这个计数）
        //    🔴 第三参 owner：上一次运行如果属于别的面板、且它还停在"停服之前"那三步 ⇒ 直接抢占
        //    （见 ShanhaiSaveEditFlow#begin(String, Object)；判据与日志 flow_stolen reason=）。
        final ShanhaiSaveEditFlow flow = ShanhaiSaveEditFlow.begin(ctx.levelId + "/" + ctx.playerName, owner);
        logFlowNotify();
        if (flow == null) {
            ShanhaiMod.LOGGER.warn("{} confirm_rejected reason=busy ctx={}", PREFIX, ctx.line());
            return ShanhaiSaveEditFactory.STATUS_BUSY;
        }
        // 🔴 从这里往下<b>整段</b>必须在 try 里：begin() 已经把全局锁抢到手了，
        //    之后任何一行抛异常都会让 flow 永远 active（= 之后每一次确认都 STATUS_BUSY）。
        //    现场（GTL山海9.10test / 2026-11-15 14:13:03.744）：confirm_started 之后 1 ms，
        //    下面那句 confirm_edit 的 edit.describe() 抛 NPE，被面板按钮那层 catch 吞成
        //    panel_button_failed ⇒ 没人 release ⇒ 14:13:04 起全是 confirm_rejected reason=busy。
        try {
            // ③ 🔴 第 ① 步：存盘（必须在停服之前，且 flush=true）
            boolean saved = false;
            try {
                saved = server.saveEverything(true, true, false);
            } catch (Throwable t) {
                flow.fail("save_failed:" + t);
                logFlowNotify();
                ShanhaiMod.LOGGER.error("{} confirm_save_failed err={}", PREFIX, t.toString());
                return ShanhaiSaveEditFactory.STATUS_FAILED + "保存存档失败（" + t + "），什么都没改";
            }
            if (!saved) {
                flow.fail("save_false");
                logFlowNotify();
                ShanhaiMod.LOGGER.error("{} confirm_save_failed reason=returned_false", PREFIX);
                return ShanhaiSaveEditFactory.STATUS_FAILED + "保存存档返回 false，什么都没改";
            }
            if (!flow.markSaved()) {
                flow.fail("bad_state");
                logFlowNotify();
                return ShanhaiSaveEditFactory.STATUS_FAILED + "状态机不在 SAVING";
            }
            flow.markStopping();
            ShanhaiMod.LOGGER.info("{} confirm_started {}", PREFIX, ctx.line());
            ShanhaiMod.LOGGER.info("{} confirm_edit staged=\"{}\" flow={}", PREFIX, edit.describe(), flow.traceLine());
            // ④ 手工切到客户端（专服已被上面挡掉；这里只是"把客户端类名留到真正需要时再解析"）
            final ShanhaiSaveEditRunContext fctx = ctx;
            final ShanhaiPlayerDataEdit fedit = edit;
            final ShanhaiSaveEditFlow fflow = flow;
            try {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> com.shanhai.client.saveedit.ShanhaiSaveEditReload.start(fctx, fedit, fflow));
            } catch (Throwable t) {
                flow.fail("client_handoff_failed:" + t);
                logFlowNotify();
                ShanhaiMod.LOGGER.error("{} client_handoff_failed err={}", PREFIX, t.toString());
                return ShanhaiSaveEditFactory.STATUS_FAILED + "切到客户端失败（" + t + "）";
            }
            return null;
        } catch (Throwable t) {
            // 兜底：begin() 之后任何没被上面几条接住的异常 ⇒ 立刻放锁（fail() 内部就是 fail+release）
            flow.fail("confirm_threw:" + t);
            logFlowNotify();
            ShanhaiMod.LOGGER.error("{} confirm_threw err={}", PREFIX, t.toString(), t);
            return ShanhaiSaveEditFactory.STATUS_FAILED + "确认流程启动时出错（" + t + "），一个字节都没改";
        }
    }

    /**
     * 把 {@link ShanhaiSaveEditFlow} 记下的那一行（{@code flow_stolen reason=…} /
     * {@code flow_released reason=…}）写进日志；没有就什么都不写。
     *
     * <p>为什么要把这一步单独拿出来：那个状态机是<b>纯 JDK</b>（不带日志依赖），它只"记账"，
     * 写日志由这里/工厂那一层做 —— 这样离线判据直接 new 它跑，不需要 MC。
     */
    static void logFlowNotify() {
        final String line = ShanhaiSaveEditFlow.takeNotify();
        if (line != null && !line.isEmpty()) {
            ShanhaiMod.LOGGER.info("{} {}", PREFIX, line);
        }
    }

    /**
     * 给客户端侧用的一行"这件事最后怎么了"的聊天回执（重开之后玩家才看得见）。
     *
     * <p>这里不直接发消息，因为重开过程中客户端正在换屏；由客户端在回到世界后发。
     */
    public static void reportToPlayer(ServerPlayer player, String text) {
        if (player == null || text == null || text.isEmpty()) {
            return;
        }
        try {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(text), false);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} report_failed err={}", PREFIX, t.toString());
        }
    }

    /** 客户端侧拿不到 {@code Util.ioPool()} 时的兜底执行器（理论上不会用到；只为"不 NPE"）。 */
    public static CompletableFuture<Void> runOnIoPool(Runnable body) {
        try {
            return CompletableFuture.runAsync(body, Util.ioPool());
        } catch (Throwable t) {
            return CompletableFuture.runAsync(body);
        }
    }
}
