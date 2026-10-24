package com.shanhai.common.itemedit;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.shanhai.ShanhaiMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * {@code /shanhai item …} —— 「改写任意已存在物品」的<b>唯一入口</b>。
 *
 * <h2>命令树</h2>
 * <pre>
 *   /shanhai item name    &lt;id&gt; &lt;名字&gt;              改显示名（名字后面可以有空格，吃整行）
 *   /shanhai item lore    &lt;id&gt; &lt;用 \n 分隔的多行&gt;   改描述（字面量 \n 与真换行都认）
 *   /shanhai item mode    &lt;id&gt; append|replace    描述模式（默认 append）
 *   /shanhai item stack   &lt;id&gt; &lt;n&gt;                堆叠上限（1 … int 上限）
 *   /shanhai item clear   &lt;id&gt;                    删掉这条覆盖 = 立刻恢复原样
 *   /shanhai item list                            列出当前所有覆盖项（控制台可跑）
 *   /shanhai item reload                          强制重读磁盘（手改过 json 之后用）
 *   /shanhai item selfcheck                        当场跑表自检（纯算术，控制台可跑）
 * </pre>
 *
 * <h2>🔴 为什么另注册一个 {@code literal("shanhai")} 而不是改现有那棵树</h2>
 * 与 {@code ShanhaiCreativeItemsCommand} / {@code ShanhaiRecipeStats} 同一口径：
 * Brigadier 会把同名子节点<b>合并</b>，既有命令（{@code statistics|menu|edit|items|…}）一个字节都不用动。
 *
 * <h2>🔴 两道校验</h2>
 * <ol>
 *   <li><b>权限</b>：{@code hasPermission(2)}（同原版 {@code /give} 一档）；</li>
 *   <li><b>保留 id</b>：{@code shanhai:reality_item_1..128} 这 128 个壳物品<b>拒绝覆盖</b>
 *       —— 它们自己有 {@code RealityShellItem} 的动态显示名，两个来源一起改会打架
 *       （用户明确要求跳过）。这条在表侧也有一份（手改 json 也会被跳过）。</li>
 * </ol>
 * ⚠️ 本题<b>刻意没有</b>加「身上带着创始现实修改模块」那道门槛（{@code /shanhai items} 有）：
 * 验收判据里没提模块，加了会让"照判据敲命令"直接失败。这条写进报告「待你确认」。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiItemOverrideCommand {

    public static final String PREFIX = ShanhaiItemOverrideTable.PREFIX;

    /** 子命令名（与 {@code items|statistics|menu|edit} 同族）。 */
    public static final String COMMAND_ARG = "item";

    /** id 参数补全：直接列物品注册表的 key（不依赖任何 helper 的自定义签名，最稳）。 */
    private static final SuggestionProvider<CommandSourceStack> ITEM_IDS = (ctx, builder) -> {
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            builder.suggest(id.toString());
        }
        return builder.buildFuture();
    };

    private ShanhaiItemOverrideCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> commandBranch() {
        return Commands.literal(COMMAND_ARG)
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("name")
                        .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(ITEM_IDS)
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(ShanhaiItemOverrideCommand::setName))))
                .then(Commands.literal("lore")
                        .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(ITEM_IDS)
                                .then(Commands.argument("lore", StringArgumentType.greedyString())
                                        .executes(ShanhaiItemOverrideCommand::setLore))))
                .then(Commands.literal("mode")
                        .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(ITEM_IDS)
                                .then(Commands.literal(ShanhaiItemOverrideTable.MODE_APPEND)
                                        .executes(ctx -> setMode(ctx, ShanhaiItemOverrideTable.MODE_APPEND)))
                                .then(Commands.literal(ShanhaiItemOverrideTable.MODE_REPLACE)
                                        .executes(ctx -> setMode(ctx, ShanhaiItemOverrideTable.MODE_REPLACE)))))
                .then(Commands.literal("stack")
                        .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(ITEM_IDS)
                                .then(Commands.argument("n", IntegerArgumentType.integer(
                                                ShanhaiItemOverrideTable.MIN_STACK,
                                                ShanhaiItemOverrideTable.MAX_STACK))
                                        .executes(ShanhaiItemOverrideCommand::setStack))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(ITEM_IDS)
                                .executes(ShanhaiItemOverrideCommand::clear)))
                .then(Commands.literal("list").executes(ShanhaiItemOverrideCommand::list))
                // ============================================================
                // 🆕 删除/抹除（第一版后端；用户 2026-10-17 规格）
                //   ⚠️ 没有「方块连带删」：「remove」只做 藏 / 抹实例 / 摘配方产出 / 拾取销毁
                // ============================================================
                .then(Commands.literal("impact")
                        .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(ITEM_IDS)
                                .executes(ShanhaiItemOverrideCommand::impact)))
                .then(Commands.literal("hide")
                        .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(ITEM_IDS)
                                .executes(ctx -> hide(ctx, true))
                                .then(Commands.literal("on").executes(ctx -> hide(ctx, true)))
                                .then(Commands.literal("off").executes(ctx -> hide(ctx, false)))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(ITEM_IDS)
                                .executes(ctx -> remove(ctx, false))
                                // 等价写法：remove <id> confirm（同样要求先跑过 impact）
                                .then(Commands.literal("confirm").executes(ctx -> remove(ctx, true)))))
                .then(Commands.literal("restore")
                        .then(Commands.argument("id", ResourceLocationArgument.id()).suggests(ITEM_IDS)
                                .executes(ShanhaiItemOverrideCommand::restore)))
                // 🆕 补丁 3 ③：一键恢复（用户口径：他要"一键"，**不加二次确认**）
                //    `restoreall` 与 `restoreall confirm` 行为完全一样（confirm 只是给人一个"我确认过"的写法）
                .then(Commands.literal("restoreall")
                        .executes(ShanhaiItemOverrideCommand::restoreAll)
                        .then(Commands.literal("confirm").executes(ShanhaiItemOverrideCommand::restoreAll)))
                .then(Commands.literal("removed").executes(ShanhaiItemOverrideCommand::removedList))
                .then(Commands.literal("reload").executes(ShanhaiItemOverrideCommand::reload))
                .then(Commands.literal("selfcheck").executes(ShanhaiItemOverrideCommand::selfcheck));
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("shanhai").then(commandBranch()));
        final var node = event.getDispatcher().getRoot().getChild("shanhai");
        final StringBuilder kids = new StringBuilder();
        if (node != null) {
            for (var c : node.getChildren()) {
                kids.append(c.getName()).append(' ');
            }
        }
        ShanhaiItemOverrideTable.ensureLoaded();
        ShanhaiMod.LOGGER.info("{} command_registered /shanhai {} ; shanhai_children=[{}] ; {}",
                PREFIX, COMMAND_ARG, kids.toString().trim(), ShanhaiItemOverrideTable.statsLine());
    }

    // ------------------------------------------------------------------ 子命令

    private static int setName(CommandContext<CommandSourceStack> ctx) {
        final ResourceLocation id = id(ctx);
        if (id == null) {
            return 0;
        }
        if (!reservedOk(ctx, id)) {
            return 0;
        }
        final String name = StringArgumentType.getString(ctx, "name");
        final boolean ok = ShanhaiItemOverrideTable.setName(id, name);
        return feedback(ctx, ok, id, "✓ 显示名已设");
    }

    private static int setLore(CommandContext<CommandSourceStack> ctx) {
        final ResourceLocation id = id(ctx);
        if (id == null) {
            return 0;
        }
        if (!reservedOk(ctx, id)) {
            return 0;
        }
        final String raw = StringArgumentType.getString(ctx, "lore");
        final boolean ok = ShanhaiItemOverrideTable.setLore(id, raw, null);
        final int lines = ShanhaiItemOverrideTable.normalizeNewlines(raw).split("\\R").length;
        return feedback(ctx, ok, id, "✓ 描述已设（" + lines + " 行）");
    }

    private static int setMode(CommandContext<CommandSourceStack> ctx, String mode) {
        final ResourceLocation id = id(ctx);
        if (id == null) {
            return 0;
        }
        if (!reservedOk(ctx, id)) {
            return 0;
        }
        final boolean ok = ShanhaiItemOverrideTable.setLoreMode(id, mode);
        return feedback(ctx, ok, id, "✓ 描述模式 = " + mode);
    }

    private static int setStack(CommandContext<CommandSourceStack> ctx) {
        final ResourceLocation id = id(ctx);
        if (id == null) {
            return 0;
        }
        if (!reservedOk(ctx, id)) {
            return 0;
        }
        final int n = IntegerArgumentType.getInteger(ctx, "n");
        final boolean ok = ShanhaiItemOverrideTable.setMaxStack(id, n);
        return feedback(ctx, ok, id, "✓ 堆叠上限 = " + n + (n > 64 ? " §7(>64：部分原版逻辑仍按 64 处理)" : ""));
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        final ResourceLocation id = id(ctx);
        if (id == null) {
            return 0;
        }
        final boolean ok = ShanhaiItemOverrideTable.clear(id);
        return feedback(ctx, ok, id, "✓ 已清空（立刻恢复原样）");
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        ShanhaiItemOverrideTable.refreshNow();
        final java.util.List<ShanhaiItemOverrideTable.Entry> all = ShanhaiItemOverrideTable.entries();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a物品覆盖 §f" + all.size() + " §a条 §8(" + ShanhaiItemOverrideTable.statsLine() + ")"), false);
        final int shown = Math.min(all.size(), 20);
        for (int i = 0; i < shown; i++) {
            final ShanhaiItemOverrideTable.Entry e = all.get(i);
            ctx.getSource().sendSuccess(() -> Component.literal("§7· §f" + e.id() + " §8" + e.signature()), false);
        }
        if (all.size() > shown) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§8…还有 " + (all.size() - shown) + " 条（日志里看全量）"), false);
        }
        final StringBuilder sb = new StringBuilder();
        for (ShanhaiItemOverrideTable.Entry e : all) {
            sb.append("\n  ").append(e.id()).append("  ").append(e.signature());
        }
        ShanhaiMod.LOGGER.info("{} cmd_list entries={}{}", PREFIX, all.size(), sb);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        ShanhaiItemOverrideTable.reloadFromDisk();
        ShanhaiRemovedItemsLedger.reloadFromDisk();
        final String line = ShanhaiItemOverrideTable.statsLine();
        ShanhaiMod.LOGGER.info("{} cmd_reload {} ; {}", PREFIX, line, ShanhaiItemRemovalHooks.statsLine());
        ctx.getSource().sendSuccess(() -> Component.literal("§a已重读磁盘 §8· " + line
                + " §8· 台账 " + ShanhaiRemovedItemsLedger.size() + " 条"), true);
        return 1;
    }

    // ================================================================== 🆕 删除/抹除

    /** 本次会话里跑过 {@code impact} 的 id（remove 的前置闸门）。 */
    private static final java.util.Set<String> IMPACT_SEEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * {@code /shanhai item impact <id>} —— <b>只读</b>影响面报告。
     *
     * <p>同时是 {@code remove} 的前置：没跑过它，remove 会被拒（用户点名"要先跑过 impact"）。
     */
    private static int impact(CommandContext<CommandSourceStack> ctx) {
        final ResourceLocation id = id(ctx);
        if (id == null) {
            return 0;
        }
        final net.minecraft.server.MinecraftServer server = ctx.getSource().getServer();
        final ShanhaiItemRemovalOps.Impact im = ShanhaiItemRemovalOps.impact(server, id);
        IMPACT_SEEN.add(id.toString());
        ShanhaiMod.LOGGER.info("{} impact_begin {} ", PREFIX, im.oneLine());
        final java.util.List<String> lines = ShanhaiItemRemovalOps.impactLines(im);
        for (String l : lines) {
            ctx.getSource().sendSuccess(() -> Component.literal(l), false);
            ShanhaiMod.LOGGER.info("{} impact_line {}", PREFIX, stripColors(l));
        }
        // 🆕 ④+ 已加载区块里的方块数量（只统计不删；不是方块 ⇒ not_a_block）
        final String blockLine = ShanhaiItemRemovalOps.blockCountLine(server, id);
        ctx.getSource().sendSuccess(() -> Component.literal(blockLine), false);
        ShanhaiMod.LOGGER.info("{} impact_line {}", PREFIX, stripColors(blockLine));
        ShanhaiMod.LOGGER.info("{} impact_done {}（后续 /shanhai item remove {} 已解锁）",
                PREFIX, im.oneLine(), id);
        return 1;
    }

    private static int hide(CommandContext<CommandSourceStack> ctx, boolean on) {
        final ResourceLocation id = id(ctx);
        if (id == null || !reservedOk(ctx, id)) {
            return 0;
        }
        final boolean ok = ShanhaiItemRemovalOps.hide(ctx.getSource().getServer(), id, on);
        return feedback(ctx, ok, id, on ? "✓ 已隐藏（JEI/创造栏看不到，实例还在）" : "✓ 已取消隐藏");
    }

    private static int remove(CommandContext<CommandSourceStack> ctx, boolean confirmArg) {
        final ResourceLocation id = id(ctx);
        if (id == null || !reservedOk(ctx, id)) {
            return 0;
        }
        if (!IMPACT_SEEN.contains(id.toString())) {
            // 规格：没跑过 impact 就拒绝（并明确告诉人下一步敲什么）
            ctx.getSource().sendFailure(Component.literal(
                    "§c先跑 §f/shanhai item impact " + id + " §c看影响面，再执行 remove"
                            + (confirmArg ? "（confirm 不能跳过这一步）" : "")));
            ShanhaiMod.LOGGER.warn("{} cmd_rejected reason=impact_not_run id={} confirm_arg={}",
                    PREFIX, id, confirmArg);
            return 0;
        }
        final ShanhaiItemRemovalOps.Result r =
                ShanhaiItemRemovalOps.remove(ctx.getSource().getServer(), id, true);
        if (!r.ok()) {
            ctx.getSource().sendFailure(Component.literal("§c" + r.message()));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a" + r.message() + " §8· 被摘/停用的配方 §f" + r.stripped()
                        + " §8条（其中整条停用 §f" + r.disabled() + "§8 条）"
                        + " §8· 台账 " + ShanhaiRemovedItemsLedger.RELATIVE_PATH
                        + " §8· §7方块形态只做藏/抹/摘配方，世界里已放置的§c不动§7（第一版）"), true);
        ShanhaiMod.LOGGER.info("{} cmd_remove id={} stripped={} disabled={}",
                PREFIX, id, r.stripped(), r.disabled());
        return 1;
    }

    private static int restore(CommandContext<CommandSourceStack> ctx) {
        final ResourceLocation id = id(ctx);
        if (id == null) {
            return 0;
        }
        final ShanhaiItemRemovalOps.Result r =
                ShanhaiItemRemovalOps.restore(ctx.getSource().getServer(), id);
        if (!r.ok()) {
            ctx.getSource().sendFailure(Component.literal("§c撤回失败（看日志 del_restore）"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a" + r.message() + " §8· hidden/removed 已取消，配方产出已按台账恢复"
                        + " §8· §7方块：不再删（已经长在原处的一直都在）"), true);
        return 1;
    }

    /**
     * 🆕 补丁 3 ③：{@code /shanhai item restoreall} —— <b>一键恢复全部</b>。
     *
     * <p>逐个走 {@link ShanhaiItemRemovalOps#restore} 现有逻辑，本方法只做回执；
     * 汇总行在报表侧：{@code del_restore_all entries=N ok=M failed=K}。
     * <p>用户口径：他要"一键" ⇒ <b>没有二次确认</b>（{@code restoreall confirm} 与它完全等价）。
     */
    private static int restoreAll(CommandContext<CommandSourceStack> ctx) {
        final ShanhaiItemRemovalOps.Result r =
                ShanhaiItemRemovalOps.restoreAll(ctx.getSource().getServer());
        if (!r.ok()) {
            ctx.getSource().sendFailure(Component.literal("§c" + r.message()
                    + " §7（逐条原因见日志 del_restore_all / del_restore_gt）"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a" + r.message() + " §8· hidden/removed 全部取消，配方产出按台账恢复"
                        + " §8· §7JEI 的物品列表会在 1 秒内（20 tick）自己同步回来"), true);
        return 1;
    }

    /** {@code /shanhai item removed} —— 列出当前所有 hidden/removed ＋ 做了什么 ＋ 时间。 */
    private static int removedList(CommandContext<CommandSourceStack> ctx) {
        ShanhaiItemOverrideTable.refreshNow();
        ShanhaiRemovedItemsLedger.reloadFromDisk();
        final java.util.List<ShanhaiItemOverrideTable.Entry> all = ShanhaiItemOverrideTable.suppressed();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a隐藏/抹除中 §f" + all.size() + " §a条 §8(" + ShanhaiItemRemovalHooks.statsLine() + ")"), false);
        int shown = 0;
        for (ShanhaiItemOverrideTable.Entry e : all) {
            if (shown++ >= 20) {
                break;
            }
            final ResourceLocation id = ResourceLocation.tryParse(e.id());
            final ShanhaiRemovedItemsLedger.RemovedEntry le = id == null ? null
                    : ShanhaiRemovedItemsLedger.get(id);
            final String what = e.removed() ? "removed（藏+抹实例+摘配方）" : "hidden（只藏）";
            final String when = com.shanhai.common.itemedit.ShanhaiItemOverrideTable.nowTs();
            final String extra = le == null ? " §8无台账" : " §8台账: 摘配方 " + le.stripped().size() + " 条";
            final String line = "§7· §f" + e.id() + " §8" + what + extra
                    + " §8ts=" + e.ts() + " §8(now " + when + ")";
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        // lambda 只能捕获 effectively-final ⇒ 先把两个可变计数拍成常量
        final int totalCount = all.size();
        final int shownCount = shown;
        if (totalCount > shownCount) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§8…还有 " + (totalCount - shownCount) + " 条（日志里看全量）"), false);
        }
        final StringBuilder sb = new StringBuilder();
        for (ShanhaiItemOverrideTable.Entry e : all) {
            sb.append("\n  ").append(e.id()).append("  ").append(e.signature());
        }
        ShanhaiMod.LOGGER.info("{} cmd_removed_list entries={}{}", PREFIX, all.size(), sb);
        return 1;
    }

    private static String stripColors(String s) {
        return s == null ? "" : s.replaceAll("§.", "");
    }

    private static int selfcheck(CommandContext<CommandSourceStack> ctx) {
        final String line = ShanhaiItemOverrideTable.selfCheck();
        ctx.getSource().sendSuccess(() -> Component.literal("§a" + line), false);
        ShanhaiMod.LOGGER.info("{} {}", PREFIX, line);
        return line.contains("PASS=true") ? 1 : 0;
    }

    // ------------------------------------------------------------------ 小工具

    private static ResourceLocation id(CommandContext<CommandSourceStack> ctx) {
        try {
            return ResourceLocationArgument.getId(ctx, "id");
        } catch (Throwable t) {
            ctx.getSource().sendFailure(Component.literal("§cid 解析失败：" + t));
            return null;
        }
    }

    /** 保留 id（128 个壳物品）当场拒绝，并把「为什么」说清楚。 */
    private static boolean reservedOk(CommandContext<CommandSourceStack> ctx, ResourceLocation id) {
        if (!ShanhaiItemOverrideTable.isReserved(id)) {
            return true;
        }
        ctx.getSource().sendFailure(Component.literal(
                "§c" + id + " 是「现实物品」壳（shanhai:reality_item_1..128），不吃覆盖 —— "
                        + "它自己的名字/提示/堆叠请用 §f/shanhai items set|icon|clear §c改"));
        ShanhaiMod.LOGGER.warn("{} cmd_rejected reason=reserved_reality_shell id={}", PREFIX, id);
        return false;
    }

    /** 统一回执：写盘成功才回绿的；同时把 id 是否在注册表里如实说出来（不拦）。 */
    private static int feedback(CommandContext<CommandSourceStack> ctx, boolean ok, ResourceLocation id, String what) {
        if (!ok) {
            ctx.getSource().sendFailure(Component.literal(
                    "§c写入失败（看日志里的 store_write_failed / edit_rejected）"));
            return 0;
        }
        final boolean registered = BuiltInRegistries.ITEM.containsKey(id);
        final ShanhaiItemOverrideTable.Entry now = ShanhaiItemOverrideTable.get(id);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a" + what + " §8· §f" + id + " §8· " + (now == null ? "<removed>" : now.signature())
                        + (registered ? "" : " §e⚠ 这个 id 不在物品注册表里（仍已写入；若物品存在即生效）")
                        + " §8· " + ShanhaiItemOverrideTable.statsLine()), true);
        return 1;
    }
}
