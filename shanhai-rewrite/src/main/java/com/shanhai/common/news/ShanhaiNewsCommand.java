package com.shanhai.common.news;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shanhai.ShanhaiMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * 游戏内更新公告的 <b>Minecraft 侧外壳</b>：注册 {@code /shanhai 公告}、在登录那一拍发送、打机器可判的日志。
 *
 * <p>全部「公告表 → 要发出的行」的纯逻辑都在 {@link ShanhaiNewsCore}（不依赖 MC）；本类只做三件事：
 * <b>取决议</b> → <b>投递</b> → <b>打日志</b>。
 *
 * <h2>1. 🔴 显示时机：接在现有「私货配方统计」横幅的<b>同一拍</b></h2>
 * 用户 2026-10-06 原话：「显示就和左下角那个配方统计数量的横幅<b>同步显示</b>」。
 * <p>
 * 现有横幅的时机链（一个字都没动）：
 * <pre>
 *   ShanhaiRecipeStats.onPlayerLoggedIn  →  PENDING_BANNER.put(uuid, BANNER_DELAY_TICKS=160)
 *   ShanhaiRecipeStats.onServerTick(END) →  数满 160 tick ⇒ sendBannerTo(player)
 * </pre>
 * 本子系统<b>不新增任何事件订阅者、不新增任何延迟计数</b>：
 * 只在 {@code ShanhaiRecipeStats.onServerTick} 里 {@code sendBannerTo(player)} 的<b>下一行</b>调
 * {@link #sendNewsOnLogin(ServerPlayer)}（那一行就是本次改动的全部"接线"）。
 * ⇒ 「统计横幅在前、公告紧接其后」这件事是<b>代码顺序</b>保证的，不是时序巧合。
 *
 * <h2>2. 🔴 命令：挂在现有 {@code /shanhai} 根下</h2>
 * <pre>
 *   /shanhai 公告           显示最新一条（= 第 1 条）
 *   /shanhai 公告 &lt;序号&gt;     显示第 N 条（1 = 最新，往前翻 2 / 3 / …）
 *   /shanhai 公告 list      列出全部（序号 + 日期 + 标题）
 * </pre>
 * {@code /shanhai} 这个根节点已被 {@code ShanhaiRecipeStats} 与 {@code ShanhaiRecipeEditorCommand}
 * <b>各自 register 了一次</b>（Brigadier 按 literal 合并同名子节点，见 {@code ShanhaiRecipeEditorCommand}
 * 类注释 §1）；本类照它们的写法再注册一次，<b>只往同一个 {@code shanhai} 节点上加一支 {@code 公告}</b>，
 * 不会顶掉既有子命令。
 * <p>
 * 🔴 <b>纯备用入口，刻意不做 GUI</b>（用户原话：「老的公告也存着，可以通过打指令显示」
 * —— 要的就是一个"不依赖界面"的兜底通路）。
 * <p>
 * ⚠️ <b>刻意不加 {@code .requires()}</b>：与既有的 {@code /shanhai statistics} 一致 ——
 * 用户自己可能是非 OP 的单机玩家，加了权限门会把他挡在外面。
 *
 * <h2>3. 🔴 机器判据（读日志，不靠眼看）</h2>
 * <pre>
 * 启动期：
 *   [SHANHAI-NEWS] news_table total=1 newest=2026-10-06
 *   [SHANHAI-NEWS] news_preview idx=0 &amp;$body_golden-· 悬浮全息菜单：…
 *   …
 *   [SHANHAI-NEWS] news_preview_lines=5
 *   [SHANHAI-NEWS] news_core_selftest ok cases=14
 *   [SHANHAI-NEWS] news_cmd_tree shanhai_children=[…] 公告_present=true
 * 发出去的那一刻（登录那一拍 / 命令）：
 *   [SHANHAI-NEWS] banner_sent id=2026-10-06 lines=5 total=1 by=player ordinal=1
 * 发不出去时（越界 / 空表）：
 *   [SHANHAI-NEWS] news_miss ordinal=2 total=1 reason=没有第 2 条公告（现在一共 1 条，1 = 最新）
 * </pre>
 * ⚠️ 公告本体<b>只有玩家登录才发得出去</b>（与横幅同理）⇒ 无头专服里 {@code banner_sent} 不会出现，
 * 但上面的 {@code news_table} / {@code news_preview} / {@code news_core_selftest} / {@code news_cmd_tree}
 * 四族行<b>在冒烟日志里就有</b>，足以判「表对不对、命令挂没挂上、越界会不会炸」。
 *
 * <h2>4. 不许把既有横幅拖下水</h2>
 * {@link #sendNewsOnLogin(ServerPlayer)} 里是<b>整段 try/catch</b>：本子系统任何异常都就地收成一行
 * ERROR 日志，<b>绝不外抛</b> —— 调用点在 {@code ShanhaiRecipeStats.onServerTick} 的循环里，
 * 外抛会打断那一轮的横幅投递（那是已验收的行为，一个字节都不许受影响）。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiNewsCommand {

    private ShanhaiNewsCommand() {}

    /**
     * 子命令字面量 = {@code 公告}（<b>中文</b>，用户 2026-10-06 任务书逐字指定）。
     *
     * <p>Brigadier 的 literal 只做<b>字符串相等</b>比较，不校验字符集 ⇒ 中文 literal 完全合法
     * （注册与解析都不经过任何 ASCII 正则；命令树进客户端靠 {@code FriendlyByteBuf.writeUtf}，
     * 中文照发）。{@code CommandSuggestions} 里也会正常显示成 {@code 公告}。
     */
    public static final String COMMAND_ARG = "公告";

    /** {@code list} 那一支的字面量（ASCII，照用户任务书）。 */
    public static final String SUB_LIST = "list";

    /** 序号那个参数节点的名字（只影响用法提示的显示：{@code /shanhai 公告 <序号>}）。 */
    public static final String ARG_ORDINAL = "序号";

    // ================================================================== 命令

    /**
     * {@code /shanhai 公告 …} 那一支。
     *
     * <p>三个分支的<b>顺序</b>有意如此：{@code list} 是 literal、序数是 integer，两者不可能互相误吃
     * （{@code "list"} 解析不出整数）⇒ 顺序只影响用法提示的排版，不影响语义。
     * <p>
     * 🔴 <b>参数用 {@link IntegerArgumentType} 而不是自己解析字符串</b>：
     * 「{@code /shanhai 公告 abc}」「{@code /shanhai 公告 0}」这类写错参数的情况，
     * Brigadier 会直接给出<b>本地化</b>的报错（原版 {@code zh_cn} 里
     * {@code argument.integer.invalid=「无效的整数“%s”」} / {@code argument.integer.low=「%s」必须大于等于%s}）
     * —— 也就是中文提示，而且<b>根本走不到我们的处理函数</b>。
     * 我们只负责「参数合法但序号超出表长」这一种（{@link ShanhaiNewsCore#notFoundMessage}）。
     */
    public static LiteralArgumentBuilder<CommandSourceStack> commandBranch() {
        return Commands.literal(COMMAND_ARG)
                // 无参数 = 最新一条（用户原话：「/shanhai 公告 = 显示最新一条」）
                .executes(ctx -> show(ctx, 1))
                .then(Commands.literal(SUB_LIST).executes(ShanhaiNewsCommand::list))
                .then(Commands.argument(ARG_ORDINAL, IntegerArgumentType.integer(1))
                        .executes(ctx -> show(ctx, IntegerArgumentType.getInteger(ctx, ARG_ORDINAL))));
    }

    /**
     * 注册到现有 {@code /shanhai} 根下（<b>只加一支，不动任何既有子命令</b>）。
     *
     * <p>根字面量取 {@code ShanhaiRecipeStats.COMMAND_ROOT}（{@code "shanhai"}）而不是再写一遍字面量：
     * 本工程的既有教训是「同一个常量两处各写一份，必然漂移」。
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal(com.shanhai.common.recipe.ShanhaiRecipeStats.COMMAND_ROOT)
                        .then(commandBranch()));
        ShanhaiMod.LOGGER.info("{} command_registered /{} {}",
                ShanhaiNewsCore.LOG_PREFIX,
                com.shanhai.common.recipe.ShanhaiRecipeStats.COMMAND_ROOT,
                COMMAND_ARG);
    }

    // ================================================================== 登录那一拍（唯一的外部接线点）

    /**
     * 🔴 <b>本次改动唯一的"接线"</b>：在 {@code ShanhaiRecipeStats.onServerTick} 里，
     * {@code sendBannerTo(player)} 的<b>下一行</b>被调用 ⇒ 与横幅<b>同一拍</b>、<b>紧随其后</b>。
     *
     * <p>为什么不做成自己的 {@code PlayerLoggedInEvent} 订阅者：那会<b>另起一个时间点</b>
     * （要么立刻发、要么自己再数一遍 160 tick），用户要的是「和那个横幅同步显示」。
     *
     * <p>本方法<b>自己兜住全部异常</b>（见类注释 §4）：调用点在别人的 tick 循环里，
     * 绝不允许多抛出一个异常过去。
     *
     * @param player 到点收横幅的那个玩家（调用方已判非 null）
     * @return 真的发出去的行数；没发（越界/空表）⇒ 0
     */
    public static int sendNewsOnLogin(ServerPlayer player) {
        try {
            if (player == null) {
                return 0;
            }
            return deliver(line -> player.sendSystemMessage(Component.literal(line)), 1, "player");
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} login_deliver FAILED (横幅不受影响): {}",
                    ShanhaiNewsCore.LOG_PREFIX, t.toString(), t);
            return 0;
        }
    }

    /**
     * 投递一条公告的<b>唯一出口</b>（登录那一拍与 {@code /shanhai 公告} 命令共用这一段）。
     *
     * <h4>为什么要收一个 {@code sink} 而不是直接收一个玩家</h4>
     * 两条路的目的地不同，但<b>内容、顺序、日志口径必须完全一致</b>：
     * <ul>
     *   <li>登录那一拍：目的地是那个玩家的聊天框
     *       （{@code line -> player.sendSystemMessage(Component.literal(line))}）；</li>
     *   <li>{@code /shanhai 公告}：目的地是<b>命令源</b>，可能既是玩家也可能是控制台
     *       （{@code line -> reply(source, line)}）。</li>
     * </ul>
     * 把"往哪儿写"作为参数传进来，{@link ShanhaiNewsCore#resolve} 的调用、逐行循环、
     * {@code banner_sent} 那一行日志就都<b>只有一处</b> —— 不存在「两条路各写一遍然后慢慢漂移」。
     *
     * <p>投递形态与横幅<b>完全一致</b>：<b>一行一条聊天消息</b>，不是把一个多行字符串塞进一条消息，
     * 也不是另开一个界面。用户原话：「显示就和左下角那个配方统计数量的横幅同步显示」。
     *
     * <p>发不出去时<b>一行都不发</b>（决议里的 {@code lines} 是空表），并打一行 {@code news_miss}
     * —— <b>本方法是 {@code news_miss} 与 {@code banner_sent} 两行的唯一打点处</b>。
     *
     * @param sink    每一行的去处（非 null）
     * @param ordinal 序号（1 = 最新）
     * @param by      日志里的 {@code by=} 字段（{@code player} / {@code console}）
     * @return 真的发出去的行数；没发（越界 / 空表 / 正文坏了）⇒ 0
     */
    private static int deliver(java.util.function.Consumer<String> sink, int ordinal, String by) {
        final List<ShanhaiNewsCore.News> table = ShanhaiNewsCore.table();
        final ShanhaiNewsCore.Delivery d = ShanhaiNewsCore.resolve(table, ordinal);
        if (!d.ok()) {
            ShanhaiMod.LOGGER.info("{} news_miss ordinal={} total={} reason={}",
                    ShanhaiNewsCore.LOG_PREFIX, ordinal, table.size(), d.message());
            return 0;
        }
        for (String line : d.lines()) {
            sink.accept(line);
        }
        final ShanhaiNewsCore.News n = ShanhaiNewsCore.byOrdinal(table, ordinal);
        ShanhaiMod.LOGGER.info("{}", ShanhaiNewsCore.sentLogLine(
                n == null ? "" : n.id(), d.lines().size(), table.size(), by, ordinal));
        return d.lines().size();
    }

    // ================================================================== 命令实现

    /**
     * {@code /shanhai 公告 [序号]} —— 把某一条公告<b>重新发一遍</b>（历史公告的查看入口）。
     *
     * <p>投递走的是与登录那一拍<b>同一个</b> {@link #deliver} ⇒ 两条路发出去的字符串集合必然一致，
     * 不存在「命令看到的是另一份文案」。区别只有目的地：这里的目的地是命令源本身
     * （玩家 ⇒ 他的聊天框；控制台/命令方块 ⇒ 控制台与 {@code latest.log}）——
     * 于是无头专服里这条命令<b>也是机器可判的</b>。
     */
    private static int show(CommandContext<CommandSourceStack> ctx, int ordinal) {
        final CommandSourceStack source = ctx.getSource();
        final int sent = deliver(line -> reply(source, line), ordinal,
                source.getPlayer() != null ? "player" : "console");
        if (sent == 0) {
            // 中文提示（越界 / 空表 / 正文坏了，三种措辞不同 —— 见 ShanhaiNewsCore.resolve 的注释）。
            // 走到这里说明 deliver 已经打过 news_miss 了，这里只负责给【命令源】一句人话。
            // resolve 是纯函数、无副作用，重算一次必然得到同一个决议。
            reply(source, "§c" + ShanhaiNewsCore.resolve(ShanhaiNewsCore.table(), ordinal).message());
        }
        return sent;
    }

    /** {@code /shanhai 公告 list} —— 列出全部（序号 + 日期 + 标题）。 */
    private static int list(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        final List<ShanhaiNewsCore.News> table = ShanhaiNewsCore.table();
        final List<String> lines = ShanhaiNewsCore.listLines(table);
        ShanhaiMod.LOGGER.info("{} news_list total={}", ShanhaiNewsCore.LOG_PREFIX, lines.size());
        if (lines.isEmpty()) {
            reply(source, "§e" + ShanhaiNewsCore.MSG_NO_NEWS);
            return 0;
        }
        reply(source, "§7公告一共 " + lines.size() + " 条（1 = 最新）：");
        for (String line : lines) {
            reply(source, "§f" + line);
        }
        return lines.size();
    }

    /** 往命令源回一行。<b>发不出去不影响任何判据</b>（判据全在日志里）。 */
    private static void reply(CommandSourceStack source, String text) {
        try {
            source.sendSuccess(() -> Component.literal(text), false);
        } catch (Throwable ignored) {
            // 控制台/命令方块源在某些阶段可能不可写；吞掉，别把命令执行炸掉。
        }
    }

    // ================================================================== 启动期读数（无头也能判）

    /**
     * <b>服务器完全起来之后</b>打这几族机器可判的行（表内容 / 逐行正文 / 纯核自检 / 命令树）。
     *
     * <p>为什么要这个钩子：公告与横幅一样，<b>只有玩家登录才发得出去</b>，无头专服里
     * {@code banner_sent} 永远不出现 ⇒ 必须在启动期把「表对不对」「命令挂没挂上」变成日志。
     *
     * <p>⚠️ 本方法<b>只写日志</b>，不碰玩家、不碰存档、不发任何聊天消息。
     * 整段包在 try/catch 里：自检失败只打 ERROR，<b>绝不让服务端起不来</b>
     * （与 {@code ShanhaiRecipeStats.onServerStarted} 里 {@code batch_counters_selftest} 的处理同款）。
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        final List<ShanhaiNewsCore.News> table = ShanhaiNewsCore.table();
        try {
            ShanhaiMod.LOGGER.info("{}", ShanhaiNewsCore.tableLogLine(table));
            final ShanhaiNewsCore.Delivery d = ShanhaiNewsCore.resolve(table, 1);
            if (d.ok()) {
                for (int i = 0; i < d.lines().size(); i++) {
                    ShanhaiMod.LOGGER.info("{} news_preview idx={} {}",
                            ShanhaiNewsCore.LOG_PREFIX, i, d.lines().get(i));
                }
                ShanhaiMod.LOGGER.info("{} news_preview_lines={}", ShanhaiNewsCore.LOG_PREFIX, d.lines().size());
            } else {
                ShanhaiMod.LOGGER.warn("{} news_preview none reason={}", ShanhaiNewsCore.LOG_PREFIX, d.message());
                ShanhaiMod.LOGGER.info("{} news_preview_lines=0", ShanhaiNewsCore.LOG_PREFIX);
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} news_preview FAILED: {}", ShanhaiNewsCore.LOG_PREFIX, t.toString(), t);
        }
        try {
            ShanhaiMod.LOGGER.info("{} news_core_selftest {}", ShanhaiNewsCore.LOG_PREFIX, ShanhaiNewsCore.selfTest());
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} news_core_selftest FAILED: {}", ShanhaiNewsCore.LOG_PREFIX, t.toString(), t);
        }
        try {
            ShanhaiMod.LOGGER.info("{} news_cmd_tree {}", ShanhaiNewsCore.LOG_PREFIX, commandTreeLine(event));
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} news_cmd_tree FAILED: {}", ShanhaiNewsCore.LOG_PREFIX, t.toString(), t);
        }
    }

    /**
     * {@code shanhai_children=[…] 公告_present=true} —— 「{@code 公告} 真的挂在了<b>现有的</b>
     * {@code /shanhai} 根下、并且没有顶掉别的子命令」这条判据的机器读数。
     *
     * <p>刻意放在 {@link #onServerStarted}（而不是注册那一刻）：注册期各订阅者的<b>先后顺序不保证</b>，
     * 在注册期打印可能看不到后面才注册进来的兄弟节点，会给出一个误导性的读数。
     * 服务端起来时命令树早已定稿 ⇒ 这里读到的就是玩家真正能用的那棵树。
     */
    private static String commandTreeLine(ServerStartedEvent event) {
        final StringBuilder kids = new StringBuilder();
        boolean present = false;
        try {
            final var root = event.getServer().getCommands().getDispatcher().getRoot();
            final var shanhai = root.getChild(com.shanhai.common.recipe.ShanhaiRecipeStats.COMMAND_ROOT);
            if (shanhai != null) {
                for (var c : shanhai.getChildren()) {
                    kids.append(c.getName()).append(' ');
                    if (COMMAND_ARG.equals(c.getName())) {
                        present = true;
                    }
                }
            }
        } catch (Throwable t) {
            return "unavailable reason=" + t;
        }
        return "shanhai_children=[" + kids.toString().trim() + "] " + COMMAND_ARG + "_present=" + present;
    }
}
