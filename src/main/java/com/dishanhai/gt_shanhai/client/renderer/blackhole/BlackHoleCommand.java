package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 山海重构 · 黑洞渲染件的 <b>客户端命令</b>：{@code /shanhai bh …}。
 *
 * <h2>1. 为什么是客户端命令（与 {@code /shanhai menu} 完全同款）</h2>
 * 见 {@code com.shanhai.client.holo.ShanhaiHoloMenuClient} 类注释 §1 的逐字节码取证：
 * 客户端命令在 {@link RegisterClientCommandsEvent} 上注册后由
 * {@code ClientCommandHandler.runCommand} <b>就地执行、不发包</b>；
 * 只有当命令树里压根没有这条时才会退回服务端。
 * ⇒ 本命令<b>纯客户端</b>：改的是渲染参数，服务端连"有没有这颗黑洞"都不知道。
 *
 * <h2>2. 命令名为什么不撞</h2>
 * 根字面量 {@code shanhai} 与工程里既有的 {@code /shanhai statistics|menu|editprobe} <b>共用</b>
 * （Brigadier 的 {@code addChild} 按名字合并子节点）。子字面量 {@code bh} 是新的
 * —— 注册时我会<b>回读命令树</b>并把 {@code present=true/false} 打进日志（学
 * {@code ShanhaiHoloMenuClient} 那条：不许打印拼出来的字符串，2026-10-05 踩过"日志自欺"）。
 *
 * <h2>3. 全部"当场生效、不落盘"（用户裁决①）</h2>
 * 每个 setter 改的是 {@link BlackHoleTuning} 里的<b>运行期覆盖值</b>；渲染器每帧只读字段。
 * 退出游戏即失效；toml 里那 4 个 {@code [debug]} 键仍然是默认值来源。
 *
 * <h2>4. 每一条都做"机器可判"</h2>
 * 除聊天栏回话外，每条命令都往 {@code [SHANHAI-BH]} 前缀打一行日志（带执行者与当时的完整读数）
 * ⇒ "命令到底跑没跑"不靠肉眼，grep {@code [SHANHAI-BH] cmd} 就有。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BlackHoleCommand {

    /** 与任务书逐字一致的日志前缀。 */
    public static final String PREFIX = BlackHoleFallbackGuard.PREFIX;

    /** 命令根：与既有 {@code /shanhai …} <b>共用</b>（不是另起一个根名）。 */
    public static final String ROOT = "shanhai";

    /** 子树字面量：{@code /shanhai bh …}。 */
    public static final String ARG = "bh";

    private BlackHoleCommand() {}

    // ------------------------------------------------------------------ 注册

    /**
     * 注册 {@code /shanhai bh …}。
     *
     * <p>这个事件由 Forge 在 {@code ClientCommandHandler.mergeServerCommands} 里派发，
     * 而后者在<b>每次收到服务端命令树</b>（进世界 / 重生 / 换维度）时都会跑
     * ⇒ 不需要我们自己做任何"重连后重新注册"。
     */
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(ROOT).then(bhBranch()));
        final boolean present = event.getDispatcher().getRoot().getChild(ROOT) != null
                && event.getDispatcher().getRoot().getChild(ROOT).getChild(ARG) != null;
        GTDishanhaiMod.LOGGER.info(PREFIX + " command_registered /" + ROOT + " " + ARG
                + " present=" + present + " (client-side) tuning{" + BlackHoleTuning.describe() + "}");
    }

    // ------------------------------------------------------------------ 命令树

    private static LiteralArgumentBuilder<CommandSourceStack> bhBranch() {
        return Commands.literal(ARG)
                // 裸 /shanhai bh = status（最常敲的那条，省一次输入）
                .executes(ctx -> {
                    status(ctx.getSource());
                    return 1;
                })
                // ---- 开关 ----
                .then(Commands.literal("on").executes(ctx -> {
                    BlackHoleTuning.setEnabled(true);
                    log("on", ctx.getSource());
                    reply(ctx.getSource(), "黑洞：§a已打开 §7（运行期覆盖；toml 的 blackhole_enabled 不再起作用，"
                            + "重启后回到 toml）");
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    BlackHoleTuning.setEnabled(false);
                    log("off", ctx.getSource());
                    reply(ctx.getSource(), "黑洞：§c已关闭 §7（这一帧起就不画，也不做背景拷贝）");
                    return 1;
                }))
                // ---- 中心坐标（支持 ~ / clear / 整数补 0.5）----
                .then(Commands.literal("center")
                        .then(Commands.literal("clear").executes(ctx -> {
                            BlackHoleTuning.setCenter(null);
                            log("center=clear", ctx.getSource());
                            reply(ctx.getSource(), "黑洞中心：§7运行期覆盖已清除 ⇒ 回到 toml 的 "
                                    + "blackhole_center（留空 = 不画）");
                            return 1;
                        }))
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(ctx -> {
                                    center(ctx.getSource(), Vec3Argument.getCoordinates(ctx, "pos"));
                                    return 1;
                                })))
                // ---- 视界倍率 ----
                .then(Commands.literal("scale")
                        .then(Commands.argument("mul", DoubleArgumentType.doubleArg(
                                        BlackHoleTuning.SCALE_MIN, BlackHoleTuning.SCALE_MAX))
                                .executes(ctx -> {
                                    final double want = DoubleArgumentType.getDouble(ctx, "mul");
                                    final double got = BlackHoleTuning.setScale(want);
                                    log("scale=" + got, ctx.getSource());
                                    reply(ctx.getSource(), "视界倍率：§b" + f3(got) + " ×"
                                            + " §7⇒ 史瓦西半径 §f" + f3(BlackHoleLensRenderer.rsBlocks())
                                            + "§7 格 / 黑盘半径 §f"
                                            + f3(BlackHoleLensRenderer.rsBlocks()
                                                    * BlackHoleLensMath.B_CRIT) + "§7 格"
                                            + (Math.abs(got - want) > 1e-9
                                                    ? " §8（夹到范围内：" + BlackHoleTuning.ranges() + "）" : ""));
                                    return 1;
                                })))
                // ---- 偏折强度 ----
                .then(Commands.literal("strength")
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(
                                        BlackHoleTuning.STRENGTH_MIN, BlackHoleTuning.STRENGTH_MAX))
                                .executes(ctx -> {
                                    final double want = DoubleArgumentType.getDouble(ctx, "value");
                                    final double got = BlackHoleTuning.setStrength(want);
                                    log("strength=" + got, ctx.getSource());
                                    reply(ctx.getSource(), "偏折强度：§b" + f3(got)
                                            + " §7（0 = 不弯背景但盘/环还在；1 = 物理弱场偏折；"
                                            + "2 = 夸张效果）"
                                            + (Math.abs(got - want) > 1e-9
                                                    ? " §8（夹到 " + BlackHoleTuning.ranges() + "）" : ""));
                                    return 1;
                                })))
                // ---- 吸积盘亮度 ----
                .then(Commands.literal("disk")
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(
                                        BlackHoleTuning.DISK_MIN, BlackHoleTuning.DISK_MAX))
                                .executes(ctx -> {
                                    final double want = DoubleArgumentType.getDouble(ctx, "value");
                                    final double got = BlackHoleTuning.setDisk(want);
                                    log("disk=" + got, ctx.getSource());
                                    reply(ctx.getSource(), "吸积盘亮度：§b" + f3(got)
                                            + " §7（0 = 只看黑影+光子环；1 = 默值；2 = 过曝）"
                                            + (Math.abs(got - want) > 1e-9
                                                    ? " §8（夹到 " + BlackHoleTuning.ranges() + "）" : ""));
                                    return 1;
                                })))
                // ---- 一级/二级的取舍 ----
                .then(Commands.literal("lens")
                        .then(Commands.literal("auto").executes(ctx -> {
                            BlackHoleTuning.setLensMode(BlackHoleTuning.LensMode.AUTO);
                            log("lens=auto", ctx.getSource());
                            reply(ctx.getSource(), "透镜：§bauto §7（默认；§e开了光影包就自动退二级§7，"
                                    + "保证不会去搬一帧旧画面）");
                            return 1;
                        }))
                        .then(Commands.literal("on").executes(ctx -> {
                            BlackHoleTuning.setLensMode(BlackHoleTuning.LensMode.ON);
                            log("lens=on", ctx.getSource());
                            reply(ctx.getSource(), "透镜：§a强制一级 §7（§e即使光影包生效也试真透镜§7；"
                                    + "那种情况下背景可能来自上一帧 —— 日志会写明「用户强制」）");
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            BlackHoleTuning.setLensMode(BlackHoleTuning.LensMode.OFF);
                            log("lens=off", ctx.getSource());
                            reply(ctx.getSource(), "透镜：§c强制二级 §7（只画程序化的黑盘+光环，"
                                    + "背景不弯 —— 用来和一级做 A/B 对照）");
                            return 1;
                        })))
                // ---- 读数 ----
                .then(Commands.literal("status").executes(ctx -> {
                    status(ctx.getSource());
                    return 1;
                }))
                // ---- 验兜底（故意让 shader 加载失败）----
                .then(Commands.literal("test").executes(ctx -> {
                    BlackHoleLensRenderer.requestSimulatedFailure();
                    log("test(simulated_failure)", ctx.getSource());
                    reply(ctx.getSource(), "§e已请求「模拟一次 shader 加载失败」§7 ⇒ 下一帧会走二级兜底，"
                            + "并打一行 §f[SHANHAI-BH] fallback=2 reason=shader_load:simulated_by_command");
                    reply(ctx.getSource(), "§7看画面应从「背景被扭曲」变成「静态黑盘+光环」；"
                            + "改回来用 §f/shanhai bh reload");
                    return 1;
                }))
                // ---- 重试一级（资源重载 / 验完 test 之后）----
                .then(Commands.literal("reload").executes(ctx -> {
                    BlackHoleLensRenderer.reloadShaders();
                    log("reload", ctx.getSource());
                    reply(ctx.getSource(), "§a已作废 shader 缓存并复位兜底状态 §7 ⇒ 下一帧重新加载 "
                            + "assets/gt_shanhai/shaders/core/guixu_blackhole.*（改了着色器不用重启游戏）");
                    return 1;
                }));
    }

    // ------------------------------------------------------------------ 子命令实现

    /**
     * {@code /shanhai bh center <x> <y> <z>}。
     *
     * <p>两条与 vanilla 不同的约定（用户裁决③）：
     * <ol>
     *   <li><b>{@code ~} 按眼睛位置取</b>：vanilla 的 {@code ~} 基准是实体坐标（脚），
     *       所以<b>Y 是相对的时候</b>补一个 {@code getEyeHeight()}；</li>
     *   <li><b>整数维自动 +0.5</b>：{@code 120 64 -33} 被理解成"方块 120,64,-33 的中心"。
     *       逐维判（小数部分为 0 才补），并打一行 {@code center_snapped} 说明补了多少。</li>
     * </ol>
     */
    private static void center(CommandSourceStack source, Coordinates coords) {
        final Vec3 pos = coords.getPosition(source);
        double y = pos.y;
        final LocalPlayer player = localPlayer(source);
        final boolean eyeBasis = coords.isYRelative();
        if (eyeBasis && player != null) {
            y += player.getEyeHeight();
        }
        final double[] raw = {pos.x, y, pos.z};
        final BlackHoleDebugSpec.Snap snap = BlackHoleDebugSpec.snapToCenter(raw);
        if (snap == null) {
            reply(source, "§c坐标解析失败");
            return;
        }
        BlackHoleTuning.setCenter(snap.coords);
        if (snap.snapped) {
            GTDishanhaiMod.LOGGER.info(PREFIX + " center_snapped dx={} dy={} dz={} from={} to={}"
                            + " note=整数坐标按方块中心+0.5 eye_basis={}",
                    f3(snap.dx), f3(snap.dy), f3(snap.dz),
                    BlackHoleDebugSpec.format(raw), BlackHoleDebugSpec.format(snap.coords), eyeBasis);
        }
        log("center=" + BlackHoleDebugSpec.format(snap.coords), source);
        reply(source, "黑洞中心：§b" + BlackHoleDebugSpec.format(snap.coords) + " §7（运行期覆盖）"
                + (snap.snapped ? " §8· 整数维已补 +0.5（方块中心）："
                        + f3(snap.dx) + "/" + f3(snap.dy) + "/" + f3(snap.dz) : "")
                + (eyeBasis ? " §8· ~ 已按眼睛位置取" : ""));
        reply(source, "§7如果看不到东西：先 §f/shanhai bh status§7 看 skip= 与 dist=；"
                + "超过 64 格不画，背后/离屏也不画");
    }

    private static void status(CommandSourceStack source) {
        final String line = BlackHoleLensRenderer.statusLine();
        GTDishanhaiMod.LOGGER.info(line);
        final int tier = BlackHoleLensRenderer.statusTier();
        reply(source, "§b黑洞 §7fallback=" + tier + " §7（0=一级正常 / 1=贴图兜底 / 2=什么都不画）");
        reply(source, "§7" + stripPrefix(line));
        reply(source, "§8· 完整读数也写进了日志（grep §f" + PREFIX + " status§8）；"
                + "调参数：§f/shanhai bh scale|strength|disk|center|lens|on|off");
    }

    // ------------------------------------------------------------------ 小工具

    /** 每条命令一行日志（"命令到底跑没跑"的机器判据）。 */
    private static void log(String what, CommandSourceStack source) {
        final net.minecraft.world.entity.Entity entity = source.getEntity();
        final String who = entity instanceof net.minecraft.world.entity.player.Player player
                ? player.getGameProfile().getName() : "console";
        GTDishanhaiMod.LOGGER.info(PREFIX + " cmd " + what + " by=" + who
                + " tuning{" + BlackHoleTuning.describe() + "}");
    }

    /** 给执行者回话（客户端命令的 source 一定是本地玩家；这里仍做空判以免 NPE）。 */
    private static void reply(CommandSourceStack source, String text) {
        if (source == null) {
            return;
        }
        source.sendSuccess(() -> Component.literal(text), false);
    }

    /**
     * 取本地玩家：先看命令执行者，再退到 {@code Minecraft.getInstance().player}。
     * <p>⚠️ 不能用 {@code source.getPlayer()}：它的返回类型是 {@code ServerPlayer}，
     * 而客户端命令的实体是 {@code LocalPlayer} ⇒ 永远是 null
     * （同一条坑，{@code ShanhaiHoloMenuClient.log} 里有原话）。
     */
    private static LocalPlayer localPlayer(CommandSourceStack source) {
        final net.minecraft.world.entity.Entity e = source == null ? null : source.getEntity();
        if (e instanceof LocalPlayer lp) {
            return lp;
        }
        return Minecraft.getInstance().player;
    }

    private static String f3(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /** 聊天栏里去掉日志前缀（那一行本身已经写进日志了）。 */
    private static String stripPrefix(String line) {
        return line.startsWith(PREFIX) ? line.substring(PREFIX.length()).trim() : line;
    }
}
