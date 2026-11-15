package com.shanhai.add.client.holo;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.shanhai.ShanhaiMod;
import com.shanhai.add.ShanhaiAdd;
import com.shanhai.client.holo.ShanhaiHoloDebugBox;
import com.shanhai.client.holo.ShanhaiHoloMenuRenderer;
import com.shanhai.client.holo.ShanhaiHoloOntop;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 山海扩展 · 全息「画到最上层」（ontop）的<b>客户端命令</b>：
 * <pre>
 *   /shanhai_add holo ontop on|off      显式设（不落配置；下次进世界回到默认 on）
 *   /shanhai_add holo ontop            无参 ⇒ 翻转
 *   /shanhai_add holo                  只看状态（不改）
 * </pre>
 *
 * <h2>0. 为什么命令必须挂在【扩展】、而且是【客户端】</h2>
 * <ul>
 *   <li><b>为什么是扩展</b>：本体的铁律是"<b>本体不许注册 {@code /shanhai_add} 的任何东西</b>；
 *       扩展不许往 {@code /shanhai} 上挂任何东西 —— 否则'有没有装扩展'在命令树上分不出来"
 *       （{@code ShanhaiGuardCommand} §"本类只保留本体那一半"原文）。本命令挂在 {@code /shanhai_add} 下，
 *       所以它只能住在扩展里。</li>
 *   <li><b>为什么是客户端</b>：开关改的是<b>本机渲染状态</b>（{@link ShanhaiHoloMenuRenderer#ontop()}）。
 *       扩展里既有的 {@code /shanhai_add clock|gravity|purge} 都注册在 <b>服务端</b>
 *       （{@code RegisterCommandsEvent}）⇒ 在那边加这条命令，单机会"看起来能用"
 *       （集成服务端与客户端同一个 JVM），但一到真正的多人服就<b>只改了服务端</b>、画面毫无变化。
 *       ⇒ 这条走 {@link RegisterClientCommandsEvent}（与本体 {@code ShanhaiHoloMenuClient} 同一条通道）。
 *       安全性取证同本体类注释：{@code ClientCommandHandler.runCommand} 命中客户端命令树就地执行、
 *       <b>不发包给服务端</b>。</li>
 * </ul>
 *
 * <h2>1. 不落配置（这是刻意的）</h2>
 * 开关的值只活在 {@link ShanhaiHoloMenuRenderer} 的静态字段里 ⇒ 重启/重进世界回到
 * {@link ShanhaiHoloOntop#DEFAULT_ON}（= on）。理由：它是一个<b>可逆的实验开关</b>，
 * 不该在 {@code config/} 里留一份"上次实验留下的状态"去干扰下一次判读。
 *
 * <h2>2. 机器可判（两条日志）</h2>
 * <pre>
 *   [SHANHAI-HOLO] holo_ontop_registered present=true root=/shanhai_add/holo/ontop client_side=true
 *   [SHANHAI-HOLO] holo_ontop on=true by=&lt;玩家名&gt; was=false
 * </pre>
 * {@code present} 是<b>回读命令树</b>得到的（不是打印"打算注册什么"）—— 本工程踩过：
 * 只打印拼出来的字符串 ⇒ 命令实际挂错了地方而日志照样好看（{@code ShanhaiHoloMenuClient}
 * 里那条 2026-10-05 的教训，逐字同款）。
 *
 * <p>⚠️ 本类整类 {@code @OnlyIn(Dist.CLIENT)} 且 {@code @EventBusSubscriber(value = Dist.CLIENT)}：
 * 专用服务端上它不会被注册，常量池里对 {@code net.minecraft.client.*} 的引用永远不解析。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiHoloOntopCommand {

    /** 命令根（与扩展其它命令共用；Brigadier 会按名字合并子节点）。 */
    public static final String ROOT = "shanhai_add";

    /** 二级字面量。 */
    public static final String HOLO = "holo";

    /** 三级字面量（就这一个功能）。 */
    public static final String ONTOP = "ontop";

    /** 🆕 三级字面量：三档（＋D）调试色块（{@code /shanhai_add holo debugbox [on|off]}）。 */
    public static final String DEBUGBOX = "debugbox";

    /** {@code debugbox} 那一行回读用的完整路径（日志里给人读的那一份）。 */
    public static final String DEBUGBOX_PATH = "/" + ROOT + "/" + HOLO + "/" + DEBUGBOX;

    /**
     * 注册回读的结果（{@code dispatcher.getRoot().getChild(...)} 拿到没有）。
     * <p>存在静态字段里只为一件事：这条命令<b>执行的时候</b>也要能把"它到底挂没挂上"打出来
     * （用户点名的 {@code holo_debugbox_registered present=<bool>}），而不是靠打印"打算注册什么"。
     */
    private static boolean debugBoxRegistered;

    private ShanhaiHoloOntopCommand() {}

    /**
     * 注册 {@code /shanhai_add holo …}（客户端命令树）。
     *
     * <p>这个事件由 Forge 在 {@code ClientCommandHandler.mergeServerCommands} 里派发，
     * 后者在<b>每次服务端命令树到达时</b>都会跑（进世界 / 重生 / 换维度）⇒ 不需要自己做"重连后再注册"。
     */
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(ROOT).then(holoBranch()));
        // 🔴 回读命令树（不许打印拼出来的字符串）——present=false 才是"没挂上"的机器可判证据
        final com.mojang.brigadier.tree.CommandNode<CommandSourceStack> root =
                event.getDispatcher().getRoot().getChild(ROOT);
        final com.mojang.brigadier.tree.CommandNode<CommandSourceStack> holo =
                root == null ? null : root.getChild(HOLO);
        final com.mojang.brigadier.tree.CommandNode<CommandSourceStack> ontop =
                holo == null ? null : holo.getChild(ONTOP);
        ShanhaiMod.LOGGER.info("[SHANHAI-HOLO] holo_ontop_registered present={} root=/{}/{}/{} "
                        + "client_side=true default_on={}",
                root != null && holo != null && ontop != null,
                ROOT, HOLO, ONTOP, ShanhaiHoloOntop.DEFAULT_ON);
        // 🆕 同一次回读里把 debugbox 那个子命令也验一遍（同一个口径：读 dispatcher，不打印"打算注册什么"）
        final com.mojang.brigadier.tree.CommandNode<CommandSourceStack> debugbox =
                holo == null ? null : holo.getChild(DEBUGBOX);
        debugBoxRegistered = debugbox != null;
        ShanhaiMod.LOGGER.info("[SHANHAI-HOLO] {}", ShanhaiHoloDebugBox.registeredLine(
                debugBoxRegistered, DEBUGBOX_PATH));
    }

    /** {@code holo} 这一棵子树。 */
    private static LiteralArgumentBuilder<CommandSourceStack> holoBranch() {
        return Commands.literal(HOLO)
                .executes(ctx -> status(ctx.getSource()))                 // 只看状态
                .then(Commands.literal(ONTOP)
                        .executes(ctx -> apply(ctx.getSource(), null))    // 无参 = 翻转
                        .then(Commands.literal("on").executes(ctx -> apply(ctx.getSource(), "on")))
                        .then(Commands.literal("off").executes(ctx -> apply(ctx.getSource(), "off"))))
                // 🆕 2026-11-08：三档（＋D）调试色块（默认 off；运行时开关，不落配置）
                .then(Commands.literal(DEBUGBOX)
                        .executes(ctx -> applyDebugBox(ctx.getSource(), null))    // 无参 = 翻转
                        .then(Commands.literal("on").executes(ctx -> applyDebugBox(ctx.getSource(), "on")))
                        .then(Commands.literal("off").executes(ctx -> applyDebugBox(ctx.getSource(), "off"))));
    }

    /** 只看状态（不改）。 */
    private static int status(CommandSourceStack source) {
        final boolean on = ShanhaiHoloMenuRenderer.ontop();
        ShanhaiMod.LOGGER.info("[SHANHAI-HOLO] {} by={}（只读取，未改动）",
                ShanhaiHoloOntop.switchLine(on), who(source));
        source.sendSuccess(() -> Component.literal("全息最上层（ontop）："
                + (on ? "§a开" : "§c关") + " §7· /shanhai_add holo ontop on|off 改"), false);
        return 1;
    }

    /**
     * 设开关 / 翻转（{@code arg == null} ⇒ 翻转）。
     *
     * <p>状态机在纯类 {@link ShanhaiHoloOntop#resolve}（非法参数 ⇒ 不改状态），本方法只做三件事：
     * 取值 → 打日志 → 回话。日志那一行是用户点名的判据：{@code holo_ontop on=true|false}。
     */
    private static int apply(CommandSourceStack source, String arg) {
        final boolean was = ShanhaiHoloMenuRenderer.ontop();
        final boolean now = ShanhaiHoloMenuRenderer.setOntop(ShanhaiHoloOntop.resolve(was, arg));
        ShanhaiMod.LOGGER.info("[SHANHAI-HOLO] {} by={} was={}",
                ShanhaiHoloOntop.switchLine(now), who(source), was);
        source.sendSuccess(() -> Component.literal("全息最上层（ontop）：" + (now ? "§a开" : "§c关")
                + " §7— " + (now ? "板子不吃世界深度（画在最上层）" : "板子恢复吃世界深度")), false);
        return 1;
    }

    /** 执行者名字（客户端命令的 source 实体就是本地玩家；拿不到就写 console）。 */
    private static String who(CommandSourceStack source) {
        final net.minecraft.world.entity.Entity e = source.getEntity();
        return e instanceof net.minecraft.world.entity.player.Player p
                ? p.getGameProfile().getName()
                : "console";
    }

    // ------------------------------------------------------------------ 🆕 2026-11-08：debugbox

    /**
     * 设 {@code debugbox} 开关 / 翻转（{@code arg == null} ⇒ 翻转）。
     *
     * <p>状态机在纯类 {@link ShanhaiHoloDebugBox#resolve}（非法参数 ⇒ 不改状态），本方法只做三件事：
     * 取值 → 打日志 → 回话。
     *
     * <p>🔴 用户点名的两条日志（**开的时候一定要能一眼看到**）：
     * <pre>
     *   [SHANHAI-HOLO] holo_debugbox on=true by=&lt;玩家名&gt; was=false readback=true
     *   [SHANHAI-HOLO] holo_debugbox_registered present=true path=/shanhai_add/holo/debugbox
     * </pre>
     * 第二条是<b>回读命令树</b>得到的（{@link #debugBoxRegistered} 来自
     * {@code dispatcher.getRoot().getChild(...)}），不是打印"打算注册什么"——
     * 本工程踩过"只打印拼出来的字符串 ⇒ 命令挂错了地方而日志照样好看"。
     * {@code readback} 则是把 {@code setDebugBox} 的<b>返回值</b>再读一遍，钉住"命令回了什么 = 状态是什么"。
     */
    private static int applyDebugBox(CommandSourceStack source, String arg) {
        final boolean was = ShanhaiHoloMenuRenderer.debugBox();
        final boolean now = ShanhaiHoloMenuRenderer.setDebugBox(ShanhaiHoloDebugBox.resolve(was, arg));
        ShanhaiMod.LOGGER.info("[SHANHAI-HOLO] {} by={} was={} readback={}",
                ShanhaiHoloDebugBox.switchLine(now), who(source), was,
                ShanhaiHoloMenuRenderer.debugBox());
        ShanhaiMod.LOGGER.info("[SHANHAI-HOLO] {}",
                ShanhaiHoloDebugBox.registeredLine(debugBoxRegistered, DEBUGBOX_PATH));
        source.sendSuccess(() -> Component.literal("全息调试色块（debugbox）：" + (now ? "§a开" : "§c关")
                + " §7— " + (now
                ? "A 红方块(相机空间) / B 品红方块(走我们的变换) / C 绿色整块板 / D 黄小方块(深度测试开着)；每秒一行日志"
                : "不再画调试色块")), false);
        return 1;
    }
}
