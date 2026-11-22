package com.shanhai.add.hotfix;

import java.lang.instrument.Instrumentation;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shanhai.ShanhaiMod;
import com.shanhai.add.ShanhaiAdd;
import com.shanhai.add.config.ShanhaiAddConfig;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * {@code /shanhai_add hotfix …} —— 热更新地基的<b>手动入口与自检</b>。
 *
 * <h2>命令树</h2>
 * <pre>
 *   /shanhai_add hotfix                  同 status
 *   /shanhai_add hotfix status           打印：有没有拿到 Instrumentation / 体检结论 / 工作目录
 *   /shanhai_add hotfix prewarm          手动重跑一次预热（绕过"只做一次"的闩；同步执行，会阻塞到出结果）
 *   /shanhai_add hotfix selftest         闭环自检：① 常量替换靶子 ② 换方法体靶子（{@link HotfixAutoCounter#selftest()}）
 *   /shanhai_add hotfix auto             查主动热改开关（配置键 {@code hotfix.auto}，默认开）
 *   /shanhai_add hotfix auto on|off      打开／关闭主动热改（🔴 写回 config/shanhai_add-common.toml；off 会立刻 undoall）
 *   /shanhai_add hotfix chainwipe        查整链抹除开关（配置键 {@code hotfix.chain_wipe}，默认开）
 *   /shanhai_add hotfix chainwipe on|off 热开关整链抹除（🔴 写回同一份配置；off ⇒ 只记录不写字节：chain_wipe_refused）
 *   /shanhai_add hotfix undo &lt;类&gt;#&lt;方法&gt;  还原一个被改过的方法
 *   /shanhai_add hotfix restore &lt;类&gt;[#方法] 恢复一个关键类（只摘外部 handler；OP 2）
 *   /shanhai_add hotfix undoall          还原全部（退出世界／停服时也会自动跑）
 * </pre>
 *
 * <h2>权限</h2>
 * {@code status} 谁都能看（纯读，不改变任何东西）。{@code prewarm} / {@code selftest} 要 OP 等级 2
 * —— 与 {@code /shanhai_add gravity}/{@code clock} 里"改运行期状态"的那几条同一个口径。
 *
 * <h2>为什么 prewarm 是同步的</h2>
 * 它是<b>手动</b>动作：管理员按下回车就是在等结果，异步返回"已安排"没有任何用处。
 * 而自动触发的 {@link HotfixBootstrap#prewarmOnce} 走的是 daemon 线程（不许卡服务端主线程）。
 *
 * <h2>为什么 selftest 要打那么多行</h2>
 * 判据是"最后一行 {@code selftest PASS|FAIL} + 每一行 {@code step=… pass=…}"。
 * 只回报一个红/绿等于把"哪一步断了"这个信息扔掉，而那正是自检唯一有价值的部分。
 */
@Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HotfixCommand {

    /** 本模块在 {@code /shanhai_add} 下的子命令名。 */
    public static final String COMMAND_ARG = "hotfix";

    private HotfixCommand() {
    }

    /** {@code /shanhai_add} 下的 {@code hotfix} 分支（注册见 {@link #onRegisterCommands}）。 */
    public static LiteralArgumentBuilder<CommandSourceStack> commandBranch() {
        return Commands.literal(COMMAND_ARG)
                .executes(HotfixCommand::status)
                .then(Commands.literal("status").executes(HotfixCommand::status))
                .then(Commands.literal("prewarm").requires(HotfixCommand::canEdit)
                        .executes(HotfixCommand::prewarm))
                .then(Commands.literal("selftest").requires(HotfixCommand::canEdit)
                        .executes(HotfixCommand::selftest))
                // ---- 第 3 档：主动热改（默认开；2026-11-20 用户拍板）
                .then(Commands.literal("auto")
                        .executes(HotfixCommand::autoQuery)
                        .then(Commands.literal("on").requires(HotfixCommand::canEdit)
                                .executes(HotfixCommand::autoOn))
                        .then(Commands.literal("off").requires(HotfixCommand::canEdit)
                                .executes(HotfixCommand::autoOff)))
                .then(Commands.literal("undo").requires(HotfixCommand::canEdit)
                        .then(Commands.argument("target", StringArgumentType.greedyString())
                                .executes(HotfixCommand::undo)))
                // ---- 🆕 2026-11-21（④）：整链抹除的开关（默认开；用户点名"可以指令开关"）
                .then(Commands.literal("chainwipe")
                        .executes(HotfixCommand::chainWipeQuery)
                        .then(Commands.literal("on").requires(HotfixCommand::canEdit)
                                .executes(HotfixCommand::chainWipeOn))
                        .then(Commands.literal("off").requires(HotfixCommand::canEdit)
                                .executes(HotfixCommand::chainWipeOff)))
                // ---- 🆕 2026-11-21：手动恢复关键类（只清空外部 handler，不动我们的）
                .then(Commands.literal("restore").requires(HotfixCommand::canEdit)
                        .then(Commands.argument("target", StringArgumentType.greedyString())
                                .executes(HotfixCommand::restore)))
                .then(Commands.literal("undoall").requires(HotfixCommand::canEdit)
                        .executes(HotfixCommand::undoAll));
    }

    /** 改运行期状态的那几条要 OP 等级 2（与 gravity/clock 一致）；{@code status} 不设门。 */
    private static boolean canEdit(CommandSourceStack src) {
        return src.hasPermission(2);
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // 与 /shanhai_add gravity、/shanhai_add clock 同根：Brigadier 会把同名根的子节点合并，
        // 所以这里再 register 一次是安全且是本工程一贯的写法（见 ShanhaiGravityCommand:73-84）。
        event.getDispatcher().register(Commands.literal("shanhai_add").then(commandBranch()));

        // 自证行：把根下所有子节点打出来 —— "hotfix 有没有挂上"在这行里就能判
        final var root = event.getDispatcher().getRoot().getChild("shanhai_add");
        final StringBuilder kids = new StringBuilder();
        if (root != null) {
            for (var child : root.getChildren()) {
                kids.append(child.getName()).append(' ');
            }
        }
        ShanhaiMod.LOGGER.info("{} command_registered /shanhai_add {} ; shanhai_children=[{}]",
                HotfixBootstrap.PREFIX, COMMAND_ARG, kids.toString().trim());
    }

    // ------------------------------------------------------------------ 子命令

    /** 纯读：把"到底有没有 Instrumentation / 体检结论 / 工作目录"打出来（聊天栏 + 日志各一份）。 */
    private static int status(CommandContext<CommandSourceStack> ctx) {
        final String line = HotfixBootstrap.statusLine();
        final boolean ready = HotfixHolder.installed() && HotfixHolder.stateLine().contains("state=ready");
        final String msg = "§b[热更新] " + (ready ? "§a已就绪" : "§e未就绪")
                + " §8" + line;
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        ShanhaiMod.LOGGER.info("{} event=status {}", HotfixBootstrap.PREFIX, line);

        // 🆕 2026-11-20：<b>逐行列出"每个目标当前用的是哪一档"</b>（用户点名要的那一条）。
        //   为什么要单独几行而不是塞进上面那一行：{@code targets=} 只放得下"键=档"，
        //   而"为什么是这一档"（换掉几条指令、站点叫什么、被打到第几次）才是判读的依据。
        final java.util.List<String> tiers;
        try {
            tiers = HotfixAutoCounter.patchedTiers();
        } catch (Throwable t) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§c[热更新] 目标档位读不出来：" + t.getClass().getSimpleName()), false);
            return ready ? 1 : 0;
        }
        if (tiers.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§7[热更新] 当前没有目标（patched=0）"), false);
        } else {
            for (final String row : tiers) {
                ctx.getSource().sendSuccess(() -> Component.literal("§7[热更新] §f" + row), false);
                ShanhaiMod.LOGGER.info("{} event=status_target {}", HotfixBootstrap.PREFIX, row);
            }
        }
        // 行内改写那一路的读数（普通攻击真的生效过几次／原样转发过几次）
        final String shield = HotfixShield.statusLine();
        ctx.getSource().sendSuccess(() -> Component.literal("§8[热更新] " + shield), false);
        ShanhaiMod.LOGGER.info("{} event=status_line_attack {}", HotfixBootstrap.PREFIX, shield);
        return ready ? 1 : 0;
    }

    /** 手动重跑预热（同步；绕过"只做一次"）。失败只打日志、返回 0，不抛。 */
    private static int prewarm(CommandContext<CommandSourceStack> ctx) {
        final long t0 = System.currentTimeMillis();
        final boolean ok;
        try {
            ok = HotfixBootstrap.prewarm("command");
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} event=prewarm trigger=command state=degraded reason=command_exception"
                    + " exception={} elapsed_ms={} policy=record_only note=只记录不反制",
                    HotfixBootstrap.PREFIX, t.toString(), System.currentTimeMillis() - t0, t);
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§c[热更新] 预热抛异常：" + t.getClass().getSimpleName() + "（已降级，不影响游戏）"), false);
            return 0;
        }
        final String line = HotfixBootstrap.statusLine();
        ctx.getSource().sendSuccess(() -> Component.literal(
                (ok ? "§a[热更新] 预热完成" : "§c[热更新] 预热失败（已降级，不影响游戏）")
                        + " §8" + line), false);
        return ok ? 1 : 0;
    }

    /**
     * 两段闭环自检（同步）。
     *
     * <p>① {@link HotfixBootstrap#selftest()}：常量替换靶子（{@code HotfixProbeTarget}）；
     * ② {@link HotfixAutoCounter#selftest()}：换方法体靶子（{@code HotfixAutoCounterProbe}）。
     * 两段<b>互不依赖</b>（各自 try/catch），任一段红都算总红；返回值同时进日志与聊天栏。
     */
    private static int selftest(CommandContext<CommandSourceStack> ctx) {
        final long t0 = System.currentTimeMillis();
        try {
            HotfixAutoCounterGlue.ensure("command_selftest");
        } catch (Throwable ignored) {
            // 接不上日志出口时下面那一段会自己报 instrumentation/无日志，不影响游戏
        }
        boolean first;
        try {
            first = HotfixBootstrap.selftest();
        } catch (Throwable t) {
            // 自检自己炸了也是一种"FAIL"，但不能让它冒泡到命令树
            ShanhaiMod.LOGGER.error("{} event=selftest state=degraded reason=selftest_exception exception={}"
                            + " elapsed_ms={} policy=record_only note=只记录不反制",
                    HotfixBootstrap.PREFIX, t.toString(), System.currentTimeMillis() - t0, t);
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§c[热更新] 自检抛异常：" + t.getClass().getSimpleName()), false);
            return 0;
        }
        boolean second;
        try {
            second = HotfixAutoCounter.selftest();
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} event=selftest phase=auto_counter state=degraded"
                            + " reason=selftest_exception exception={} elapsed_ms={}"
                            + " policy=record_only note=只记录不反制",
                    HotfixBootstrap.PREFIX, t.toString(), System.currentTimeMillis() - t0, t);
            second = false;
        }
        final boolean pass = first && second;
        final String msg = "[热更新] selftest " + (pass ? "§aPASS" : "§cFAIL")
                + " §8stamp=" + (first ? "PASS" : "FAIL") + " auto_counter=" + (second ? "PASS" : "FAIL");
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        ShanhaiMod.LOGGER.info("{} event=selftest pass={} stamp={} auto_counter={} elapsed_ms={}",
                HotfixBootstrap.PREFIX, pass, first, second, System.currentTimeMillis() - t0);
        return pass ? 1 : 0;
    }

    // ------------------------------------------------------------------ 第 3 档：主动热改

    /** 查开关（只读；不设权限门，和 {@code status} 一个口径）。 */
    private static int autoQuery(CommandContext<CommandSourceStack> ctx) {
        final boolean on = HotfixAutoCounter.auto();
        final String line = HotfixAutoCounter.statusLine();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§b[热更新] 主动热改 auto=" + (on ? "§a开" : "§7关") + " §8" + line), false);
        ShanhaiMod.LOGGER.info("{} event=auto_query {}", HotfixBootstrap.PREFIX, line);
        return on ? 1 : 0;
    }

    /** 打开（{@code auto on}）。顺手把日志出口与分诊订阅接上 —— 否则开了也收不到触发。 */
    private static int autoOn(CommandContext<CommandSourceStack> ctx) {
        return autoSet(ctx, true);
    }

    /** 关闭（{@code auto off}）。⚠️ 只关"以后不再动手"，已经改过的东西不自动还原（要还原用 {@code undoall}）。 */
    private static int autoOff(CommandContext<CommandSourceStack> ctx) {
        return autoSet(ctx, false);
    }

    private static int autoSet(CommandContext<CommandSourceStack> ctx, boolean on) {
        final long t0 = System.currentTimeMillis();
        // ① 先落盘：{@code config/shanhai_add-common.toml [hotfix] auto} 是【唯一真相】。
        //    🔴 顺序有意义：off 会立刻 undoAll（一次 retransform 风暴），万一那一步出事，
        //       配置里也应该已经记着 off —— 而不是"内存关了、文件还是 on"。
        final boolean persisted = ShanhaiAddConfig.setHotfixAuto(on);
        boolean tap = false;
        try {
            tap = HotfixAutoCounterGlue.ensure(on ? "auto_on" : "auto_off");
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} event=auto_switch phase=glue state=degraded reason={} policy=record_only",
                    HotfixBootstrap.PREFIX, t.toString());
        }
        // ② 再改内存（两处同时改 ⇒ 不再分叉）
        final boolean now = HotfixAutoCounter.setAuto(on);
        final String line = HotfixAutoCounter.statusLine();
        final boolean finalTap = tap;
        // ③ 回显用的是【刚落盘的结果】，不是"内存改成什么了"
        final String tail = persisted
                ? " §8· 已写进 config/" + ShanhaiAddConfig.FILE_NAME + " [hotfix] auto=" + now + "（重启还是这个值）"
                : " §c· 配置文件没写成功 ⇒ 重启会回到上次保存的值（看日志 cfg_write）";
        ctx.getSource().sendSuccess(() -> Component.literal(
                (now ? "§a[热更新] 主动热改已开" : "§e[热更新] 主动热改已关") + tail
                        + " §8tap=" + finalTap + " " + line), false);
        ShanhaiMod.LOGGER.info("{} event=auto_switch auto={} saved={} key={} tap={} elapsed_ms={} {}",
                HotfixBootstrap.PREFIX, now, persisted, ShanhaiAddConfig.KEY_HOTFIX_AUTO,
                finalTap, System.currentTimeMillis() - t0, line);
        return now ? 1 : 0;
    }

    // ------------------------------------------------------------------ 🆕 ④ 整链抹除开关

    /** 查整链抹除开关（只读；与 {@code auto} 同口径：不设权限门）。 */
    private static int chainWipeQuery(CommandContext<CommandSourceStack> ctx) {
        final boolean on = HotfixChainWipe.enabled();
        final String line = HotfixChainWipe.statusLine();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§b[热更新] 整链抹除 chainwipe=" + (on ? "§a开" : "§7关")
                        + " §8（配置键 " + HotfixChainWipe.KEY + "，默认 "
                        + HotfixChainWipe.DEFAULT_ENABLED + "）" + line), false);
        ShanhaiMod.LOGGER.info("{} event=chainwipe_query {}", HotfixBootstrap.PREFIX, line);
        return on ? 1 : 0;
    }

    private static int chainWipeOn(CommandContext<CommandSourceStack> ctx) {
        return chainWipeSet(ctx, true);
    }

    private static int chainWipeOff(CommandContext<CommandSourceStack> ctx) {
        return chainWipeSet(ctx, false);
    }

    /**
     * 热开关（默认开）。{@code off} 之后整链抹除<b>只记录不写字节</b>：
     * 日志是 {@code chain_wipe_refused reason=chainwipe_off classes=[…]}，且 {@code retransform} 计数不涨。
     *
     * <p>🔴 2026-11-22（用户报"重开游戏配置重置为默认"）：本命令改成<b>先写 config／落盘、再改内存</b>。
     * 以前只有 {@code HotfixChainWipe.setEnabled(on)} —— 配置键 {@code hotfix.chain_wipe} 从来没人写，
     * 于是重开游戏时 {@code applyConfiguredDefault(文件里的 true)} 一定把它打回默认。
     *
     * <p>⚠️ 与 {@code auto off} 一样：只关"以后不再动手"，已经抹掉的类不自动还原（要还原用 {@code undoall}）。
     */
    private static int chainWipeSet(CommandContext<CommandSourceStack> ctx, boolean on) {
        // ① 先落盘（唯一真相 = config/shanhai_add-common.toml [hotfix] chain_wipe）
        final boolean persisted = ShanhaiAddConfig.setChainWipe(on);
        // ② 再改内存里的开关（影子与配置同时更新）
        final boolean now = HotfixChainWipe.setEnabled(on);
        final String line = HotfixChainWipe.statusLine();
        // ③ 回显用【刚落盘的结果】
        final String tail = persisted
                ? " §8· 已写进 config/" + ShanhaiAddConfig.FILE_NAME + " [hotfix] chain_wipe=" + now
                        + "（重启还是这个值）"
                : " §c· 配置文件没写成功 ⇒ 重启会回到上次保存的值（看日志 cfg_write）";
        ctx.getSource().sendSuccess(() -> Component.literal(
                (now ? "§a[热更新] 整链抹除已开" : "§e[热更新] 整链抹除已关（只记录不写字节）") + tail
                        + " §8" + line), false);
        ShanhaiMod.LOGGER.info("{} event=chainwipe_switch chainwipe={} saved={} key={} {}",
                HotfixBootstrap.PREFIX, now, persisted, ShanhaiAddConfig.KEY_CHAIN_WIPE, line);
        return now ? 1 : 0;
    }

    /**
     * 手动恢复一个关键类：{@code /shanhai_add hotfix restore <类名>[#<方法>]}。
     *
     * <p>只接受六个已监控关键类（判据在 {@link HotfixKeyClassRestore#restoreByName}）。
     * 回执一行给玩家、一行进日志；{@code restoreByName} 自己还会打一条
     * {@code keyclass_restore …}（与 {@code keyclass_restore_inline} 区分开）。
     */
    private static int restore(CommandContext<CommandSourceStack> ctx) {
        final String target = StringArgumentType.getString(ctx, "target");
        try {
            // 与 undo 同一个口径：先把日志出口接上，否则 keyclass_restore… 那行没地方落
            HotfixAutoCounterGlue.bind();
        } catch (Throwable ignored) {
            // 接不上也不影响恢复本身（回执行仍会打）
        }
        final HotfixKeyClassRestore.Result r;
        try {
            final Instrumentation inst = HotfixHolder.instrumentation();
            // 🆕 2026-11-21（P2 追加）：走**串行化入口**（与 patch／chain_wipe 同一把按类锁）——
            //   手动 restore 也是一次独立的 retransformClasses，不许与另外两条路径交错。
            r = HotfixAutoCounter.restoreByNameSerialized(inst, target, null);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} event=restore target={} result=failed detail=exception_{} policy=record_only",
                    HotfixBootstrap.PREFIX, target, t.getClass().getSimpleName());
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§c[热更新] restore " + target + " 失败：" + t.getClass().getSimpleName()), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§b[热更新] restore " + target + " ⇒ " + (r.ok ? "§a已恢复" : "§c未恢复")
                        + " §8stripped=" + r.stripped + " ours_kept=" + r.oursKept
                        + " retransforms=" + r.retransforms + " reason=" + r.reason), false);
        ShanhaiMod.LOGGER.info("{} event=restore target={} ok={} stripped={} ours_kept={} retransforms={} reason={}",
                HotfixBootstrap.PREFIX, target, r.ok, r.stripped, r.oursKept, r.retransforms, r.reason);
        return r.ok ? 1 : 0;
    }

    /** 还原一个目标：{@code /shanhai_add hotfix undo <类>#<方法>}。 */
    private static int undo(CommandContext<CommandSourceStack> ctx) {
        final String target = StringArgumentType.getString(ctx, "target");
        boolean ok;
        try {
            HotfixAutoCounterGlue.bind();
            ok = HotfixAutoCounter.undo(target);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} event=undo target={} result=failed detail=exception_{}"
                            + " policy=record_only note=只记录不反制",
                    HotfixBootstrap.PREFIX, target, t.getClass().getSimpleName());
            ok = false;
        }
        final boolean done = ok;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[热更新] undo " + target + " ⇒ " + (done ? "§a已还原" : "§c没还原（看日志 detail=）")), false);
        return done ? 1 : 0;
    }

    /** 还原全部：{@code /shanhai_add hotfix undoall}（退出世界／停服时也会自动跑一次）。 */
    private static int undoAll(CommandContext<CommandSourceStack> ctx) {
        int restored;
        try {
            HotfixAutoCounterGlue.bind();
            restored = HotfixAutoCounter.undoAll("command");
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} event=undoall result=failed detail=exception_{}"
                            + " policy=record_only note=只记录不反制",
                    HotfixBootstrap.PREFIX, t.getClass().getSimpleName());
            restored = -1;
        }
        final int count = restored;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[热更新] undoall 还原 " + count + " 个"), false);
        return restored >= 0 ? 1 : 0;
    }
}
