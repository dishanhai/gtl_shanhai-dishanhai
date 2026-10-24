package com.shanhai.common.reality;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shanhai.ShanhaiMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * {@code /shanhai items} —— 「自己新增物品」的第二条入口（与 {@code /shanhai statistics|menu|edit}
 * 同族：<b>另注册一个同名 literal，Brigadier 会把子节点合并</b>，既有命令一个字节都不动）。
 *
 * <h2>命令树</h2>
 * <pre>
 *   /shanhai items                     开面板（要玩家 + 身上带着创始现实修改模块）
 *   /shanhai items open                同上（显式写法，录像/排查用）
 *   /shanhai items list                列出已定义的槽位（控制台可跑）
 *   /shanhai items set &lt;槽位&gt; "&lt;名字&gt;"   设名字（控制台可跑；玩家执行要模块）
 *   /shanhai items icon &lt;槽位&gt; &lt;图标&gt;    图标：item:ns:id / builtin:K / 空串=兜底
 *   /shanhai items clear &lt;槽位&gt;          清空这个名字（物品不删，退回默认壳）
 *   /shanhai items selfcheck           当场跑一遍表自检（纯算术，控制台可跑）
 * </pre>
 * <p>⚠️ {@code set} 的名字要加引号（Brigadier 的 string 参数）：{@code /shanhai items set 1 "测试物品"}。
 *
 * <h2>🔴 门槛（第 ⑥ 条）</h2>
 * 玩家执行时必须身上带着「创始现实修改模块」；<b>控制台/命令方块不受限</b>
 * （那是运维通道：用户自己进游戏改，控制台/自动化脚本不该被一个背包判定挡死）。
 * 这一条与全息板那条入口<b>共用同一个纯函数</b> {@link ShanhaiRealityItemTable#playerHasModule}。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiCreativeItemsCommand {

    public static final String PREFIX = ShanhaiRealityItemTable.PREFIX;

    /** 子命令名（与 {@code statistics/menu/edit} 同族）。 */
    public static final String COMMAND_ARG = "items";

    private ShanhaiCreativeItemsCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> commandBranch() {
        return Commands.literal(COMMAND_ARG)
                .executes(ShanhaiCreativeItemsCommand::openPanel)
                .then(Commands.literal("open").executes(ShanhaiCreativeItemsCommand::openPanel))
                .then(Commands.literal("list").executes(ShanhaiCreativeItemsCommand::list))
                .then(Commands.literal("set")
                        .then(Commands.argument("slot", IntegerArgumentType.integer(1,
                                        ShanhaiRealityItemTable.SLOT_COUNT))
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .executes(ctx -> setSlot(ctx,
                                                StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("icon")
                        .then(Commands.argument("slot", IntegerArgumentType.integer(1,
                                        ShanhaiRealityItemTable.SLOT_COUNT))
                                .then(Commands.argument("icon", StringArgumentType.string())
                                        .executes(ctx -> setIcon(ctx,
                                                StringArgumentType.getString(ctx, "icon"))))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("slot", IntegerArgumentType.integer(1,
                                        ShanhaiRealityItemTable.SLOT_COUNT))
                                .executes(ShanhaiCreativeItemsCommand::clear)))
                .then(Commands.literal("selfcheck").executes(ShanhaiCreativeItemsCommand::selfcheck));
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // ⚠️ 与 ShanhaiRecipeStats / ShanhaiRecipeEditorCommand 各 register 一次 literal("shanhai")：
        //     Brigadier 会合并同名子节点（既有命令的 children 一个都不会丢）。
        event.getDispatcher().register(Commands.literal("shanhai").then(commandBranch()));
        final var shanhaiNode = event.getDispatcher().getRoot().getChild("shanhai");
        final StringBuilder kids = new StringBuilder();
        if (shanhaiNode != null) {
            for (var c : shanhaiNode.getChildren()) {
                kids.append(c.getName()).append(' ');
            }
        }
        ShanhaiMod.LOGGER.info("{} command_registered /shanhai {} ; shanhai_children=[{}]",
                PREFIX, COMMAND_ARG, kids.toString().trim());
    }

    // ------------------------------------------------------------------ 子命令

    private static int openPanel(CommandContext<CommandSourceStack> ctx) {
        final ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这条子命令要由玩家执行（控制台没有界面）"));
            return 0;
        }
        if (!ShanhaiRealityItemTable.playerHasModule(player)) {
            ctx.getSource().sendFailure(Component.literal(ShanhaiRealityItemTable.moduleRequiredText()));
            ShanhaiMod.LOGGER.info("{} cmd_open_rejected reason=no_module player={}",
                    PREFIX, player.getName().getString());
            return 0;
        }
        final boolean ok = ShanhaiCreativeItemsFactory.open(player);
        if (!ok) {
            ctx.getSource().sendFailure(Component.literal("§c面板没打开成功（看日志里的 openUI ok=false）"));
        }
        return ok ? 1 : 0;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        ShanhaiRealityItemTable.ensureLoaded();
        final int n = ShanhaiRealityItemTable.definedCount();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a已定义 §f" + n + " §a/ §f" + ShanhaiRealityItemTable.SLOT_COUNT
                        + " §a个现实物品槽位 §8(" + ShanhaiRealityItemTable.statsLine() + ")"), false);
        final java.util.List<Integer> slots = ShanhaiRealityItemTable.definedSlots();
        final int shown = Math.min(slots.size(), 20);
        for (int i = 0; i < shown; i++) {
            final int slot = slots.get(i);
            final ShanhaiRealityItemTable.Entry e = ShanhaiRealityItemTable.entry(slot);
            ctx.getSource().sendSuccess(() -> Component.literal("§7· §f#" + (slot + 1) + " §f" + e.name()
                    + " §8icon=" + ShanhaiRealityItemTable.iconOf(slot)
                    + " stack=" + e.maxStack() + " rarity=" + e.rarity()), false);
        }
        if (slots.size() > shown) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§8…还有 " + (slots.size() - shown) + " 条（日志里看全量）"), false);
        }
        ShanhaiMod.LOGGER.info("{} cmd_list defined={} slots={}", PREFIX, n, slots);
        return 1;
    }

    private static int setSlot(CommandContext<CommandSourceStack> ctx, String name) {
        if (!gate(ctx)) {
            return 0;
        }
        final int slot = IntegerArgumentType.getInteger(ctx, "slot") - 1;
        final ShanhaiRealityItemTable.Entry cur = ShanhaiRealityItemTable.entry(slot);
        final boolean ok = ShanhaiRealityItemTable.apply(slot, name,
                cur == null ? "" : cur.icon(),
                cur == null ? ShanhaiRealityItemTable.MAX_STACK : cur.maxStack(),
                cur == null ? "common" : cur.rarity(),
                cur == null ? "" : cur.lore());
        return feedback(ctx, ok, slot, "✓ 已设名字");
    }

    private static int setIcon(CommandContext<CommandSourceStack> ctx, String icon) {
        if (!gate(ctx)) {
            return 0;
        }
        final int slot = IntegerArgumentType.getInteger(ctx, "slot") - 1;
        final ShanhaiRealityItemTable.Entry cur = ShanhaiRealityItemTable.entry(slot);
        if (cur == null || !cur.defined()) {
            ctx.getSource().sendFailure(Component.literal(
                    "§c槽位 " + (slot + 1) + " 还没填名字 ⇒ 先 §f/shanhai items set "
                            + (slot + 1) + " \"名字\"§c（图标只能挂在已定义的槽位上）"));
            return 0;
        }
        final boolean ok = ShanhaiRealityItemTable.apply(slot, cur.name(),
                ShanhaiRealityItemTable.sanitizeIcon(icon), cur.maxStack(), cur.rarity(), cur.lore());
        return feedback(ctx, ok, slot, "✓ 已设图标");
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        if (!gate(ctx)) {
            return 0;
        }
        final int slot = IntegerArgumentType.getInteger(ctx, "slot") - 1;
        final boolean ok = ShanhaiRealityItemTable.clear(slot);
        return feedback(ctx, ok, slot, "✓ 已清空（物品退回默认壳，没有被删）");
    }

    private static int selfcheck(CommandContext<CommandSourceStack> ctx) {
        final String line = ShanhaiRealityItemTable.selfCheck();
        ctx.getSource().sendSuccess(() -> Component.literal("§a" + line), false);
        ShanhaiMod.LOGGER.info("{} {}", PREFIX, line);
        return line.contains("PASS=true") ? 1 : 0;
    }

    /** 玩家执行 ⇒ 要模块；控制台/命令方块 ⇒ 放行。 */
    private static boolean gate(CommandContext<CommandSourceStack> ctx) {
        final ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return true;
        }
        if (ShanhaiRealityItemTable.playerHasModule(player)) {
            return true;
        }
        ctx.getSource().sendFailure(Component.literal(ShanhaiRealityItemTable.moduleRequiredText()));
        return false;
    }

    /** 统一回执 + 广播整表（单人也走这条，客户端那一份才会跟着变）。 */
    private static int feedback(CommandContext<CommandSourceStack> ctx, boolean ok, int slot, String what) {
        if (!ok) {
            ctx.getSource().sendFailure(Component.literal("§c写入失败（看日志里的 store_write_failed / apply_rejected）"));
            return 0;
        }
        ShanhaiCreativeItemsNetwork.syncToAll(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a" + what + " §8· 槽位 " + (slot + 1) + " §8· shanhai:reality_item_" + (slot + 1)
                        + " §8· " + ShanhaiRealityItemTable.statsLine()), true);
        return 1;
    }
}
