package com.dishanhai.gt_shanhai.client.holo;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 山海重构 · 悬浮全息菜单的<b>客户端入口</b>（事件挂载 + 聊天命令）。
 *
 * <h2>1. 🔴 为什么命令走【客户端】注册，以及怎么确认它真的不会打到服务端</h2>
 * {@code /shanhai statistics} 是<b>服务端</b>命令（注册在
 * {@code ServerLifecycleHooks}/{@code RegisterCommandsEvent} 的 dispatcher 上）。
 * 本轮这条 {@code /shanhai menu} 是<b>纯客户端显示开关</b>（不开服务端权威的口子），
 * 所以注册在 {@link RegisterClientCommandsEvent} 上。
 *
 * <p>它<b>不会</b>被发到服务端 —— 这一条是 {@code javap} 取证的，不是推测：
 * <pre>
 *   ClientPacketListener.sendCommand(String) 的字节码第 0..7 条：
 *     0: aload_1
 *     1: invokestatic net/minecraftforge/client/ClientCommandHandler.runCommand:(Ljava/lang/String;)Z
 *     4: ifeq  8        ← 返回 false 才继续往下走
 *     7: return         ← 返回 true ⇒ 【就地执行、直接 return，不发包】
 *   ClientCommandHandler.runCommand(String) 的字节码：
 *     commands.execute(reader, source)                ← 客户端自己的 dispatcher
 *     … 捕获 CommandSyntaxException 时：只有当异常是
 *       dispatcherUnknownCommand / dispatcherUnknownArgument 才 return false（= 放给服务端）
 * </pre>
 * 而客户端的 dispatcher 是
 * {@code ClientPacketListener.handleCommands} 里
 * {@code new CommandDispatcher<>(服务端的 root) → ClientCommandHandler.mergeServerCommands(…)}，
 * 也就是<b>服务端命令树 + 客户端命令合并后的一棵树</b>
 * ⇒ {@code /shanhai statistics} 照旧走服务端、{@code /shanhai menu} 就地执行，两者<b>互不影响</b>。
 *
 * <h2>2. 命令名为什么不撞</h2>
 * 根字面量 {@code shanhai} 与服务端那条<b>共用</b>（Brigadier 的 {@code addChild} 会按名字合并子节点），
 * 子字面量 {@link #COMMAND_ARG} = {@code menu} 是本轮新加的。
 * 工程里既有的子命令只有 {@code statistics} / {@code Statistics} / {@code editprobe}
 * （取证：{@code ShanhaiRecipeStats.onRegisterCommands} 与
 * {@code ShanhaiRecipeEditProbe.COMMAND_ARG}）—— <b>没有 {@code menu}</b>。
 *
 * <h2>3. 三条命令都做了"机器可判"</h2>
 * 每次执行都往 {@code SHANHAI-HOLO} 前缀打一行日志（带完整的当前状态读数），
 * 于是"命令到底跑没跑"不靠肉眼：客户端 {@code latest.log} 里 grep
 * {@code [SHANHAI-HOLO] cmd} 就有。
 * <p>⚠️ {@code /shanhai menu dump} 那份 JSON <b>只会进日志</b>（太长，聊天栏放不下），
 * 聊天栏里给的是中文摘要 + "完整读数在日志里"。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiHoloMenuClient {

    private ShanhaiHoloMenuClient() {}

    /** 命令根：<b>与服务端那条 {@code /shanhai} 共用</b>（不是另一个根名）。 */
    public static final String COMMAND_ROOT = "shanhai";

    /** 子命令字面量。小写（Brigadier 逐字符比较，大小写是两条不同节点）。 */
    public static final String COMMAND_ARG = "menu";

    /** 本类所有日志行的前缀（便于 grep）。 */
    public static final String LOG_PREFIX = "[SHANHAI-HOLO]";

    // ------------------------------------------------------------------ 事件

    /** 世界空间渲染入口。挑 stage 的理由见 {@link ShanhaiHoloMenuRenderer} 类注释 §3。 */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        ShanhaiHoloMenuRenderer.onRenderLevelStage(event);
    }

    /**
     * 注册 {@code /shanhai menu …}。
     *
     * <p>这个事件由 Forge 在 {@code ClientCommandHandler.mergeServerCommands} 内派发，
     * 而后者在<b>每次 {@code ClientboundCommandsPacket} 到达时</b>都会跑
     * （进世界、重生、换维度都会）⇒ <b>不需要我们自己做任何"重连后重新注册"</b>。
     */
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(COMMAND_ROOT).then(menuBranch()));
        // 🔴 这一行必须【回读命令树】，不许打印拼出来的字符串。
        //    2026-10-05 踩过：当时写的是 register(menuBranch())（少了外层 literal(COMMAND_ROOT)），
        //    命令实际挂成了 `/menu`，而这行日志照样打印 "/shanhai menu" ⇒ 日志在自欺，
        //    排查时被它误导了一整轮（用户敲 /shanhai menu 得到"错误的命令参数"）。
        //    present=false 才是"没挂上"的机器可判证据。
        final boolean present = event.getDispatcher().getRoot().getChild(COMMAND_ROOT) != null
                && event.getDispatcher().getRoot().getChild(COMMAND_ROOT).getChild(COMMAND_ARG) != null;
        GTDishanhaiMod.LOGGER.info(LOG_PREFIX + " command_registered /" + COMMAND_ROOT + " " + COMMAND_ARG
                + " present=" + present + " (client-side)");
        // 顺手把离线自检跑一遍 —— 它不碰任何游戏状态，失败会打 ERROR（冒烟/日志一眼可见）
        ShanhaiHoloMenuState.logSelfTest();
    }

    // ------------------------------------------------------------------ 命令树

    private static LiteralArgumentBuilder<CommandSourceStack> menuBranch() {
        return Commands.literal(COMMAND_ARG)
                // 无参 = 切换
                .executes(ctx -> {
                    final boolean now = ShanhaiHoloMenuState.toggle();
                    log("toggle", ctx.getSource());
                    reply(ctx.getSource(), "悬浮全息菜单：" + (now ? "§a已打开" : "§c已关闭")
                            + " §7（当前 " + ShanhaiHoloMenuState.describe() + "）");
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    ShanhaiHoloMenuState.setEnabled(true);
                    log("on", ctx.getSource());
                    reply(ctx.getSource(), "悬浮全息菜单：§a已打开 §7（" + ShanhaiHoloMenuState.describe() + "）");
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    ShanhaiHoloMenuState.setEnabled(false);
                    log("off", ctx.getSource());
                    reply(ctx.getSource(), "悬浮全息菜单：§c已关闭");
                    return 1;
                }))
                // ---- 朝向档（用户要的那个"口子"）----
                .then(Commands.literal("facing")
                        .then(Commands.literal("follow").executes(ctx -> {
                            ShanhaiHoloMenuState.setFacing(ShanhaiHoloMenuState.Facing.FOLLOW);
                            log("facing=follow", ctx.getSource());
                            reply(ctx.getSource(), "朝向：§b平滑转向玩家 §7（τ="
                                    + ShanhaiHoloMenuTuning.YAW_TAU_FOLLOW_SEC
                                    + "s ⇒ 扭头时会慢半拍、能看到侧面）");
                            return 1;
                        }))
                        .then(Commands.literal("snap").executes(ctx -> {
                            ShanhaiHoloMenuState.setFacing(ShanhaiHoloMenuState.Facing.SNAP);
                            log("facing=snap", ctx.getSource());
                            reply(ctx.getSource(), "朝向：§e立即转向玩家 §7（τ=0；这是『像 HUD』的对照组）");
                            return 1;
                        }))
                        .then(Commands.literal("fixed").executes(ctx -> {
                            ShanhaiHoloMenuState.setFacing(ShanhaiHoloMenuState.Facing.FIXED);
                            log("facing=fixed", ctx.getSource());
                            reply(ctx.getSource(), "朝向：§6世界固定 §7（就停在现在这个方向，之后扭头它不再跟转）"
                                    + " §8[环绕档下这一档本来就不生效：那一圈恒不跟头转]");
                            return 1;
                        })))
                // ---- 排布档 ----
                .then(Commands.literal("layout")
                        .then(Commands.literal("ring").executes(ctx -> {
                            ShanhaiHoloMenuState.setLayout(ShanhaiHoloMenuState.Layout.RING);
                            log("layout=ring", ctx.getSource());
                            reply(ctx.getSource(), "排布：§b环绕玩家一圈 §7（默认；五块板沿水平面绕你排开、"
                                    + "板面都朝你，半径 " + ShanhaiHoloMenuState.ringRadius()
                                    + " 格；§e扭头它不跟转§7，走哪跟哪）");
                            return 1;
                        }))
                        .then(Commands.literal("column").executes(ctx -> {
                            ShanhaiHoloMenuState.setLayoutRow(false);
                            log("layout=column", ctx.getSource());
                            reply(ctx.getSource(), "排布：§6[对照档] 竖列 §7（甲案原样：五块板上下叠、"
                                    + "两端板向上下外倾，整块随你扭头一起转）");
                            return 1;
                        }))
                        .then(Commands.literal("row").executes(ctx -> {
                            ShanhaiHoloMenuState.setLayoutRow(true);
                            log("layout=row", ctx.getSource());
                            reply(ctx.getSource(), "排布：§6[对照档] 横排 §7（甲案原样：五块板左右排开、"
                                    + "两端板向左右外倾，整块随你扭头一起转）");
                            return 1;
                        })))
                // ---- 距离 ----
                .then(Commands.literal("distance")
                        .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(0.8D, 8.0D))
                                .executes(ctx -> {
                                    final float d = ShanhaiHoloMenuState.setDistance(
                                            (float) DoubleArgumentType.getDouble(ctx, "blocks"));
                                    log("distance=" + d, ctx.getSource());
                                    reply(ctx.getSource(), "距离：§b" + d + " 格");
                                    return 1;
                                })))
                // ---- 🆕 2026-10-06：二级面板（设置 / 命令）的开关 ----
                //   它存在的理由很具体：面板上的格子要【瞄准了才点得到】，而"瞄准"这件事
                //   在没有人的机器上验不了。这三条命令把"面板能不能开、开了长什么样、里面写了什么"
                //   变成一条可复现的文本读数（照旧往 [SHANHAI-HOLO] 前缀打一行）。
                .then(Commands.literal("panel")
                        .then(Commands.literal("settings").executes(ctx -> {
                            ShanhaiHoloMenuState.setPanelMode(ShanhaiHoloMenuPanel.MODE_SETTINGS);
                            log("panel=settings", ctx.getSource());
                            reply(ctx.getSource(), "全息面板：§b设置 §7（距离 / 布局 / 环绕特效 / 高级调参 / 返回）"
                                    + " §8· 点「返回」那一行回五块板；点「高级调参」进第二层翻那 14 项");
                            return 1;
                        }))
                        .then(Commands.literal("command").executes(ctx -> {
                            ShanhaiHoloMenuState.setPanelMode(ShanhaiHoloMenuPanel.MODE_COMMAND);
                            log("panel=command", ctx.getSource());
                            reply(ctx.getSource(), "全息面板：§b命令 §7（点「输入」那一行开始打字，"
                                    + "回车或点「执行」发出去；§e以管理员权限执行§7）");
                            return 1;
                        }))
                        .then(Commands.literal("close").executes(ctx -> {
                            ShanhaiHoloMenuState.closePanel();
                            log("panel=close", ctx.getSource());
                            reply(ctx.getSource(), "全息面板：§7已收起，回到五块板（§a投影还开着§7）");
                            return 1;
                        }))
                        .then(Commands.literal("dump").executes(ctx -> {
                            panelDump(ctx.getSource());
                            return 1;
                        })))
                // ---- 读数 ----
                .then(Commands.literal("dump").executes(ctx -> {
                    dump(ctx.getSource());
                    return 1;
                }))
                .then(Commands.literal("selftest").executes(ctx -> {
                    final String bad = ShanhaiHoloMenuState.selfTest();
                    log("selftest", ctx.getSource());
                    if (bad == null) {
                        reply(ctx.getSource(), "§a自检通过 §7（7 条判据，含 1 条负对照）");
                    } else {
                        reply(ctx.getSource(), "§c自检失败：" + bad);
                    }
                    return bad == null ? 1 : 0;
                }));
    }

    /**
     * 🆕 {@code /shanhai menu panel dump} —— 把<b>面板上每一格现在写着什么</b>打进日志。
     *
     * <p>为什么必须有这一条：面板是<b>世界空间</b>画的，没有人的机器上看不到它。
     * 而"面板上写的到底是哪一档"恰恰是设置面板唯一的判据 —— 于是把它变成文本：
     * 5 行 × 4 格逐格打出来（与 {@code ShanhaiHoloMenuPanel.cellText} 是同一个函数
     * ⇒ <b>日志里读到的就是屏幕上会画出来的</b>，不是另写一份）。
     */
    private static void panelDump(CommandSourceStack source) {
        final int mode = ShanhaiHoloMenuState.panelMode();
        log("panel_dump=" + ShanhaiHoloMenuPanel.modeName(mode), source);
        if (mode == ShanhaiHoloMenuPanel.MODE_NONE) {
            GTDishanhaiMod.LOGGER.info("{} panel_dump_json {\"panel\":\"none\"}", LOG_PREFIX);
            reply(source, "§7现在没有面板（显示的是五块板），可以用 §f/shanhai menu panel settings§7 打开");
            return;
        }
        final StringBuilder json = new StringBuilder(1024);
        json.append("{\"panel\":\"").append(ShanhaiHoloMenuPanel.modeName(mode)).append('"');
        json.append(",\"rows\":[");
        for (int r = 0; r < ShanhaiHoloMenuPanel.ROWS; r++) {
            if (r > 0) {
                json.append(',');
            }
            json.append('[');
            for (int c = 0; c < ShanhaiHoloMenuPanel.CELLS; c++) {
                if (c > 0) {
                    json.append(',');
                }
                json.append('"').append(ShanhaiHoloMenuPanel.cellText(mode, r, c)
                        .replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
            json.append(']');
        }
        json.append("],\"hover_row\":").append(ShanhaiHoloMenuState.panelHoverRow())
                .append(",\"hover_cell\":").append(ShanhaiHoloMenuState.panelHoverCell())
                .append(",\"command_text\":\"").append(ShanhaiHoloMenuPanel.command()
                        .replace("\\", "\\\\").replace("\"", "\\\""))
                .append("\",\"last_result\":\"").append(ShanhaiHoloMenuPanel.lastResult()
                        .replace("\\", "\\\\").replace("\"", "\\\""))
                .append("\",\"cell_width_bu\":").append(ShanhaiHoloMenuPanel.CELL_W_BU)
                .append(",\"row_pitch_blocks\":").append(ShanhaiHoloMenuTuning.PANEL_ROW_PITCH_BLOCKS)
                .append('}');
        GTDishanhaiMod.LOGGER.info("{} panel_dump_json {}", LOG_PREFIX, json.toString());
        reply(source, "§b面板逐格读数已写进日志 §7（grep §f" + LOG_PREFIX + " panel_dump_json§7）");
        reply(source, "§7当前：" + ShanhaiHoloMenuState.panelDescribe()
                + " §8· 面板下 " + ShanhaiHoloMenuPanel.ROWS + " 行 × "
                + ShanhaiHoloMenuPanel.CELLS + " 格");
    }

    // ------------------------------------------------------------------ 读数

    /**
     * {@code /shanhai menu dump} —— 把<b>全部可调参数</b>与五块板的解算位姿打成 JSON 写进日志。
     *
     * <h4>为什么必须只进日志</h4>
     * 那份 JSON 有 2 千多字符，塞进聊天栏会被折成十几行糊住屏幕；而它的用途是
     * "给 HTML 预览抄同一套参数" —— 那是一份要复制粘贴的文本，落在 {@code latest.log} 里正合适。
     */
    private static void dump(CommandSourceStack source) {
        final String tuningJson = ShanhaiHoloMenuTuning.toJson();
        log("dump", source);
        GTDishanhaiMod.LOGGER.info(LOG_PREFIX + " tuning_json " + tuningJson);
        GTDishanhaiMod.LOGGER.info(LOG_PREFIX + " layout_json_column " + ShanhaiHoloMenuLayout.toJson(false));
        GTDishanhaiMod.LOGGER.info(LOG_PREFIX + " layout_json_row " + ShanhaiHoloMenuLayout.toJson(true));

        // 🔴 A6 的机器判据：把【玩家的 yaw / 玩家坐标 / 每块板的 yaw 与 pos】一次打全，
        //    并附上两条断言（转头不动 / 走一步跟着走）与它们的负对照。
        final net.minecraft.client.player.LocalPlayer p = localPlayer(source);
        if (p == null) {
            GTDishanhaiMod.LOGGER.error(LOG_PREFIX + " pose_json UNAVAILABLE reason=no_local_player "
                    + "(这条读数需要玩家在场；命令是从控制台发的？)");
            reply(source, "§c拿不到玩家状态，pose_json 没打出来（日志里有 UNAVAILABLE 一行）");
            return;
        }
        final String layout = ShanhaiHoloMenuState.layoutName();
        final String poseJson = ShanhaiHoloMenuLayout.dumpJson(layout,
                p.getX(), p.getY(), p.getZ(),
                ShanhaiHoloMenuTuning.EYE_HEIGHT_BLOCKS, p.getYRot(), ShanhaiHoloMenuState.distance());
        final String checks = ShanhaiHoloMenuLayout.checksLine(layout,
                p.getX(), p.getY(), p.getZ(),
                ShanhaiHoloMenuTuning.EYE_HEIGHT_BLOCKS, p.getYRot(), ShanhaiHoloMenuState.distance());
        GTDishanhaiMod.LOGGER.info(LOG_PREFIX + " pose_json " + poseJson);
        GTDishanhaiMod.LOGGER.info(LOG_PREFIX + " pose_check layout=" + layout + " " + checks);

        reply(source, "§b全部参数与位姿已写进日志 §7（grep §f" + LOG_PREFIX + " pose_json§7 / "
                + "§f" + LOG_PREFIX + " pose_check§7 / §f" + LOG_PREFIX + " tuning_json§7）");
        reply(source, "§7判据：" + checks.replace("yaw_invariance=true", "§a转头板不动 §7")
                .replace("yaw_invariance=false", "§c转头板跟着转 §7")
                .replace("pos_follow=true", "§a走哪跟哪§7")
                .replace("pos_follow=false", "§c不跟位置§7"));
        reply(source, "§7当前读数：" + ShanhaiHoloMenuState.describe());
        if (ShanhaiHoloMenuState.isRing()) {
            // 环绕档的"整块尺寸"就是这一圈的直径（甲案那两个 TOTAL_* 常量是旧档的包围盒，别混用）
            final float r = ShanhaiHoloMenuState.ringRadius();
            reply(source, "§7环绕档：半径 §f" + r + "§7 格（直径 §f" + (2.0f * r)
                    + "§7 格）/ 板宽 §f" + (2.0f * ShanhaiHoloMenuTuning.BOARD_HALF_WIDTH_BLOCKS)
                    + "§7 格 / 板高 §f" + (2.0f * ShanhaiHoloMenuTuning.BOARD_HALF_HEIGHT_BLOCKS)
                    + "§7 格 / 五块板均匀分布（每 §f" + ShanhaiHoloMenuTuning.RING_STEP_DEG + "§7 度一块）");
        } else {
            reply(source, "§7板宽 §f" + (2.0f * ShanhaiHoloMenuTuning.BOARD_HALF_WIDTH_BLOCKS)
                    + "§7 格 / 板高 §f" + (2.0f * ShanhaiHoloMenuTuning.BOARD_HALF_HEIGHT_BLOCKS)
                    + "§7 格 / 板距 §f" + ShanhaiHoloMenuTuning.BOARD_PITCH_BLOCKS
                    + "§7 格 / 整块 §f" + ShanhaiHoloMenuTuning.TOTAL_WIDTH_BLOCKS + "×"
                    + ShanhaiHoloMenuTuning.TOTAL_HEIGHT_BLOCKS + "§7 格");
        }
    }

    /**
     * 取本地玩家：先看命令执行者，再退到 {@code Minecraft.getInstance().player}。
     * <p>客户端命令的执行者就是本地玩家（{@code getEntity()}）；退路是给"从控制台/日志里跑"的场合留的。
     */
    private static net.minecraft.client.player.LocalPlayer localPlayer(CommandSourceStack source) {
        final net.minecraft.world.entity.Entity e = source.getEntity();
        if (e instanceof net.minecraft.client.player.LocalPlayer lp) {
            return lp;
        }
        return net.minecraft.client.Minecraft.getInstance().player;
    }

    // ------------------------------------------------------------------ 小工具

    /**
     * 每条命令都打一行带完整状态的日志（"命令到底跑没跑"的机器判据）。
     *
     * <p>🔴 <b>取执行者名字必须用 {@code getEntity()}，不能用 {@code getPlayer()}</b>：
     * {@code CommandSourceStack.getPlayer()} 的返回类型是 <b>{@code ServerPlayer}</b>
     * （javap 原文：{@code public net.minecraft.server.level.ServerPlayer getPlayer();}），
     * 而客户端命令的实体是 {@code LocalPlayer} ⇒ {@code getPlayer()} <b>永远是 null</b>。
     * 用它就会把每一行都记成 {@code by=console}，那是一条会撒谎的日志。
     */
    private static void log(String what, CommandSourceStack source) {
        final net.minecraft.world.entity.Entity entity = source.getEntity();
        final String who = entity instanceof net.minecraft.world.entity.player.Player player
                ? player.getGameProfile().getName()
                : "console";
        GTDishanhaiMod.LOGGER.info(LOG_PREFIX + " cmd " + what + " by=" + who
                + " state{" + ShanhaiHoloMenuState.describe() + "}");
    }

    /** 给执行者回话（客户端命令的 source 一定是本地玩家，但这里仍做空判以免 NPE）。 */
    private static void reply(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }
}
