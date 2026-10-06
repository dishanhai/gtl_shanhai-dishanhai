package com.shanhai.common.recipe.editor;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shanhai.ShanhaiMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * {@code /shanhai edit} —— 本刀的<b>入口</b>。
 *
 * <h2>1. 它挂在现有 {@code /shanhai} 根下，但【不改】任何既有文件</h2>
 * Brigadier 的 {@code CommandDispatcher.register(...)} 是按 literal <b>合并</b>子节点的
 * （同名 literal 复用同一个节点），而 {@code ShanhaiRecipeStats.onRegisterCommands} 也是
 * 一个 {@code RegisterCommandsEvent} 订阅者 ⇒ 我这里再 register 一条
 * {@code Commands.literal("shanhai")} 只会把 {@code edit} 挂到<b>同一个</b>
 * {@code shanhai} 节点上，不会顶掉 {@code statistics}。
 * 这样做的收益：本刀的全部改动都落在<b>新文件</b>里，既有命令的字节码一行不动。
 * ⚠️ 这一条是<b>推断</b>（Brigadier 语义），自检里有一拍是它的机器判据：
 * 打出 {@code shanhai} 节点的全部子节点名，{@code edit} 与 {@code statistics} <b>都要在</b>。
 *
 * <h2>2. 🔴 权限门：这一刀【故意不加】</h2>
 * 本命令是<b>写操作</b>（改配方表、写 config），但本轮<b>不加权限门</b>，与现有
 * {@code /shanhai statistics} 一致 —— 理由是用户自己就可能是非 OP 的单机玩家，加了门会把他挡在外面。
 * <b>以后要加门，做法</b>（照 GTCEu 自己 {@code GTCommands} 的写法）：
 * <pre>
 *   .requires(src -&gt; src.hasPermission(3))     // 3 = 管理员；放进每个子命令的 builder 上
 * </pre>
 * 或者在 {@code onRegisterCommands} 里对整支 {@code edit} 加一层 {@code .requires(...)}。
 * 本轮把这条写在这里，是为了下一次改的人不必重新推。
 *
 * <h2>3. 无头也能用的那一半</h2>
 * 面板要开客户端（红线禁止），但同一条后端链在控制台里能跑：
 * <pre>
 *   /shanhai edit                        进游戏用：对手里拿着的物品开面板
 *   /shanhai edit list &lt;物品id&gt;           列出命中条数 + 前 10 条（控制台可跑）
 *   /shanhai edit fp &lt;配方id&gt;             打印这条配方的 base_fp（控制台可跑）
 *   /shanhai edit duration &lt;配方id&gt; &lt;值&gt;  改时长 + 立刻生效 + 落盘（控制台可跑）
 *   /shanhai edit remove &lt;配方id&gt;         删这条 + 立刻生效 + 落盘（控制台可跑）
 *   /shanhai edit selfcheck              当场跑一遍机器可判的自检
 * </pre>
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiRecipeEditorCommand {

    public static final String PREFIX = "[SHANHAI-EDIT] editor";
    public static final String COMMAND_ARG = "edit";

    /** 自动自检的开关（值 {@code 1} 即开）。 */
    public static final String ENV_ENABLE = "SHANHAI_EDITOR";

    /** 自检里的那一拍"真的往 config 里写一条并留着"（用于下一局的"重启不丢"验证）。 */
    public static final String ENV_WRITE = "SHANHAI_EDITOR_WRITE";

    /** 清掉上一轮留下的自检条目。 */
    public static final String ENV_CLEANUP = "SHANHAI_EDITOR_CLEANUP";

    /**
     * 🆕 阶段 2：<b>下一局开机</b>时验"非 GT 输入的编辑被覆盖层重放回来了"。
     *
     * <p>它只影响 {@code case=vanilla_overlay_replay} 判不判红：
     * 不设它时那一拍只打一行读数（因为"靶子是不是被写过"取决于上一局跑没跑 {@link #ENV_WRITE}）。
     */
    public static final String ENV_VVERIFY = "SHANHAI_EDITOR_VVERIFY";

    private ShanhaiRecipeEditorCommand() {}

    // ------------------------------------------------------------------ 命令树

    public static LiteralArgumentBuilder<CommandSourceStack> commandBranch() {
        return Commands.literal(COMMAND_ARG)
                .executes(ShanhaiRecipeEditorCommand::openPanel)
                // 第二刀：直接跳到某条配方的编辑屏（面板仍是三段式，只是进来时停在哪一段不同）
                .then(Commands.literal("open")
                        .then(Commands.argument("recipe", ResourceLocationArgument.id())
                                .executes(ctx -> openRecipe(ctx, ResourceLocationArgument.getId(ctx, "recipe")))))
                .then(Commands.literal("list")
                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                .executes(ctx -> listItem(ctx, ResourceLocationArgument.getId(ctx, "item")))))
                .then(Commands.literal("fp")
                        .then(Commands.argument("recipe", ResourceLocationArgument.id())
                                .executes(ctx -> printFp(ctx, ResourceLocationArgument.getId(ctx, "recipe")))))
                .then(Commands.literal("duration")
                        .then(Commands.argument("recipe", ResourceLocationArgument.id())
                                .then(Commands.argument("value", IntegerArgumentType.integer(1, Integer.MAX_VALUE))
                                        .executes(ctx -> setDuration(ctx,
                                                ResourceLocationArgument.getId(ctx, "recipe"),
                                                IntegerArgumentType.getInteger(ctx, "value"))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("recipe", ResourceLocationArgument.id())
                                .executes(ctx -> removeRecipe(ctx, ResourceLocationArgument.getId(ctx, "recipe")))))
                // ───────────────────────── 第二刀：输入 / 输出 / 电压 / 一键恢复 ─────────────────────
                .then(Commands.literal("io-dump")
                        .then(Commands.argument("recipe", ResourceLocationArgument.id())
                                .executes(ctx -> ioDump(ctx, ResourceLocationArgument.getId(ctx, "recipe")))))
                .then(Commands.literal("io-set")
                        .then(Commands.argument("recipe", ResourceLocationArgument.id())
                                .then(Commands.literal("in")
                                        .then(Commands.argument("slot", IntegerArgumentType.integer(0, 63))
                                                .then(Commands.argument("item", ResourceLocationArgument.id())
                                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, ShanhaiIoTable.MAX_ITEM_COUNT))
                                                                .executes(ctx -> ioSet(ctx, true))))))
                                .then(Commands.literal("out")
                                        .then(Commands.argument("slot", IntegerArgumentType.integer(0, 63))
                                                .then(Commands.argument("item", ResourceLocationArgument.id())
                                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, ShanhaiIoTable.MAX_ITEM_COUNT))
                                                                .executes(ctx -> ioSet(ctx, false))))))))
                .then(Commands.literal("io-del")
                        .then(Commands.argument("recipe", ResourceLocationArgument.id())
                                .then(Commands.literal("in")
                                        .then(Commands.argument("slot", IntegerArgumentType.integer(0, 63))
                                                .executes(ctx -> ioDel(ctx, true))))
                                .then(Commands.literal("out")
                                        .then(Commands.argument("slot", IntegerArgumentType.integer(0, 63))
                                                .executes(ctx -> ioDel(ctx, false))))))
                .then(Commands.literal("eut")
                        .then(Commands.argument("recipe", ResourceLocationArgument.id())
                                .then(Commands.argument("value", com.mojang.brigadier.arguments.LongArgumentType.longArg(1, Long.MAX_VALUE))
                                        .executes(ctx -> setEut(ctx, ResourceLocationArgument.getId(ctx, "recipe"),
                                                com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "value"))))))
                // 🔴 一键恢复：不打参数 = 全部恢复；带配方 id = 只恢复那一条。两条都【立刻生效】。
                .then(Commands.literal("restore")
                        .executes(this0 -> restoreAll(this0))
                        .then(Commands.argument("recipe", ResourceLocationArgument.id())
                                .executes(ctx -> restoreOne(ctx, ResourceLocationArgument.getId(ctx, "recipe")))))
                .then(Commands.literal("selfcheck")
                        .executes(ctx -> {
                            ShanhaiRecipeEditorSelfcheck.run(ctx.getSource().getServer(), true);
                            return 1;
                        }));
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // ⚠️ 与 ShanhaiRecipeStats 各自 register 一次 literal("shanhai")：Brigadier 会合并同名子节点。
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

    // ------------------------------------------------------------------ 自检挂载

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // 🔴 2026-10-05 第 5 轮（#6 的第二半）：
        //    「重进存档」= 集成服务端重新起来 ⇒ RecipeManager 里换成了【一批新的配方对象】，
        //    而反查索引里缓存的是【上一局的那些对象引用】⇒ 面板会读到一份已经不生效的旧对象
        //    （实测现场：15:01:55 索引按新表重建过一次，而 15:02:04 面板读到的 conditions=0）。
        //    ⇒ 每次服务端起来就作废这个纯缓存（它本来就是从 RecipeManager 现建的，作废只换来一次重建）。
        ShanhaiRecipeReverseIndex.invalidate();
        ShanhaiMod.LOGGER.info("{} reverse_index_invalidated reason=server_started "
                + "（重进存档/重启之后，面板不会再去读上一局那些配方对象）", PREFIX);
        // 网络通道的装机读数（第二刀：这一步的判据就是日志里那一行 net_channel_registered）
        ShanhaiMod.LOGGER.info("{} net_stats {} uifactory_factories={}", PREFIX,
                ShanhaiJeiBridge.statsLine(),
                com.lowdragmc.lowdraglib.gui.factory.UIFactory.FACTORIES.size());
        final boolean enabled = "1".equals(System.getenv(ENV_ENABLE));
        ShanhaiMod.LOGGER.info("{} selftest_gate env:{}={} write:{} cleanup:{} will_run={}",
                PREFIX, ENV_ENABLE, System.getenv(ENV_ENABLE),
                System.getenv(ENV_WRITE), System.getenv(ENV_CLEANUP), enabled);
        if (!enabled) {
            return;
        }
        // 第二刀：JEI 那半边的判据核心（纯算术，不依赖 JEI/MC）——正对照 3 组 ＋ 负对照 1 组
        for (String line : ShanhaiJeiSyncPlan.selfcheck()) {
            ShanhaiMod.LOGGER.info("{} {}", PREFIX, line);
        }
        // 🆕 2026-10-05：id 口径自检（靶子 = shanhai: 命名空间的真配方 ＋ 故意拼错的负对照）
        for (String line : ShanhaiRecipeEditorIdCheck.run(event.getServer())) {
            ShanhaiMod.LOGGER.info("{} {}", PREFIX, line);
        }
        // 🆕 2026-10-05：拖动的可机器判读数（四个计数器，判据见 ShanhaiDragStats 类注释）
        ShanhaiMod.LOGGER.info("{} {}", PREFIX, ShanhaiDragStats.statsLine());
        ShanhaiMod.LOGGER.info("{} max_io_table {}", PREFIX,
                com.shanhai.common.recipe.ShanhaiRecipeTypes.maxIoStatsLine());
        // 第二刀：大工作区面板那半边的机器可判自检（三段式数据面 ＋ 三种手势 ＋ 保存后从活索引读回）
        ShanhaiRecipeEditorWorkspaceCheck.run(event.getServer(), "1".equals(System.getenv(ENV_WRITE)));
        ShandaiSelfcheckLauncher.run(event.getServer());
    }

    /** 把自检放进自己的小壳里：自检抛异常绝不能让服务端起来不了。 */
    private static final class ShandaiSelfcheckLauncher {
        static void run(net.minecraft.server.MinecraftServer server) {
            try {
                ShanhaiRecipeEditorSelfcheck.run(server, false);
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.error("{} SELFTEST CRASHED (server keeps running): {}", PREFIX, t.toString(), t);
            }
        }
    }

    // ------------------------------------------------------------------ 子命令实现

    /**
     * {@code /shanhai edit} —— <b>第二刀的入口：打开大工作区面板</b>。
     *
     * <h4>🔴 为什么这里【不】再要"手上拿着一个物品"</h4>
     * 第一刀的门槛是"拿物品右键/命令进面板"，再按物品反查配方。用户当场否掉了这个入口：
     * 「入口也不是拿个物品就进入，而是<b>先选择配方类型</b>」⇒ 面板第一屏就是类型列表，
     * 手里什么都不用拿。
     *
     * <h4>🔴 仍然是服务端权威路径</h4>
     * 在服务端建 UI ＋ 开 {@code ModularUIContainer}，再让客户端重建一份；
     * 纯客户端开面板的话，控件动作会被服务端<b>静默丢弃</b>（不报错不写日志）——见工厂类注释。
     */
    private static int openPanel(CommandContext<CommandSourceStack> ctx) {
        final ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这条子命令要由玩家执行（控制台没有界面）"));
            return 0;
        }
        // 🔴 服务端权威路径：在服务端建 UI + 开 ModularUIContainer，再让客户端重建一份。
        final boolean ok = ShanhaiRecipeEditorFactory.open(
                new ShanhaiRecipeEditorHolder(null, ShanhaiRecipeEditorHolder.STAGE_TYPES,
                        null, null, false), player);
        if (!ok) {
            ctx.getSource().sendFailure(Component.literal("§c面板没打开成功（看日志里的 openUI ok=false）"));
        }
        return ok ? 1 : 0;
    }

    /**
     * {@code /shanhai edit open <配方id>} —— 直接跳到某条配方的编辑屏（无头自检/录像用）。
     * 面板那三段仍然照走，只是"进来时停在哪一段"不同（见 holder）。
     */
    private static int openRecipe(CommandContext<CommandSourceStack> ctx, ResourceLocation recipeId) {
        final ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这条子命令要由玩家执行（控制台没有界面）"));
            return 0;
        }
        final var recipe = ShanhaiRecipeReverseIndex.byId(ctx.getSource().getServer(), recipeId);
        if (recipe == null) {
            ctx.getSource().sendFailure(Component.literal("§c找不到这条配方：" + recipeId));
            return 0;
        }
        final var type = recipe.getType();
        final ResourceLocation typeId = type == null ? null : type.registryName;
        final boolean ok = ShanhaiRecipeEditorFactory.open(
                new ShanhaiRecipeEditorHolder(null, ShanhaiRecipeEditorHolder.STAGE_EDIT,
                        typeId, recipeId, false), player);
        return ok ? 1 : 0;
    }

    private static int listItem(CommandContext<CommandSourceStack> ctx, ResourceLocation itemId) {
        final var server = ctx.getSource().getServer();
        final var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(itemId);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            ctx.getSource().sendFailure(Component.literal("§c没有这个物品：" + itemId));
            return 0;
        }
        final var session = new ShanhaiRecipeEditorSession(server, itemId);
        session.reload();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a命中 §f" + session.total() + " §a条使用 §f" + itemId + " §a的配方"), false);
        final int shown = Math.min(session.total(), ShanhaiRecipeEditorSession.ROWS);
        for (int i = 0; i < shown; i++) {
            final int idx = i;
            ctx.getSource().sendSuccess(() -> Component.literal("§7· " + session.absoluteRowText(idx)), false);
        }
        ShanhaiMod.LOGGER.info("{} cmd_list item={} total={} reverse={}",
                PREFIX, itemId, session.total(), ShanhaiRecipeReverseIndex.statsLine());
        return 1;
    }

    private static int printFp(CommandContext<CommandSourceStack> ctx, ResourceLocation recipeId) {
        final String fp = ShanhaiRecipeFingerprint.forRecipeId(recipeId);
        final var recipe = ShanhaiRecipeReverseIndex.byId(ctx.getSource().getServer(), recipeId);
        final String cand = recipe == null ? "(recipe not found)" : ShanhaiRecipeFingerprint.diagnosticGtCodecFp(recipe);
        ctx.getSource().sendSuccess(() -> Component.literal("§abase_fp(" + recipeId + ") = §f" + fp), false);
        ShanhaiMod.LOGGER.info("{} cmd_fp id={} fp={}", PREFIX, recipeId, fp);
        ShanhaiMod.LOGGER.info("{} cmd_fp_diag id={} gtcodec_candidate={}", PREFIX, recipeId, cand);
        ShanhaiMod.LOGGER.info("{} cmd_fp_diag id={} kubejs_reachable={} diagnosis={}",
                PREFIX, recipeId, ShanhaiRecipeFingerprint.kubeJsReachable(),
                ShanhaiRecipeFingerprint.lastDiagnosis());
        return 1;
    }

    private static int setDuration(CommandContext<CommandSourceStack> ctx, ResourceLocation recipeId, int value) {
        final var server = ctx.getSource().getServer();
        final var recipe = ShanhaiRecipeReverseIndex.byId(server, recipeId);
        if (recipe == null) {
            ctx.getSource().sendFailure(Component.literal("§c找不到这条配方：" + recipeId));
            return 0;
        }
        final var r = ShanhaiRecipeEditorOps.setDuration(server, recipe, value, true, true);
        ctx.getSource().sendSuccess(() -> Component.literal("§a" + r.message() + " §8(" + r.detail() + ")"), true);
        return r.ok() ? 1 : 0;
    }

    private static int removeRecipe(CommandContext<CommandSourceStack> ctx, ResourceLocation recipeId) {
        final var server = ctx.getSource().getServer();
        final var recipe = ShanhaiRecipeReverseIndex.byId(server, recipeId);
        if (recipe == null) {
            ctx.getSource().sendFailure(Component.literal("§c找不到这条配方：" + recipeId));
            return 0;
        }
        final var r = ShanhaiRecipeEditorOps.removeRecipe(server, recipe, true);
        ctx.getSource().sendSuccess(() -> Component.literal("§a" + r.message() + " §8(" + r.detail() + ")"), true);
        return r.ok() ? 1 : 0;
    }

    // ------------------------------------------------------- 第二刀：IO / 电压 / 恢复

    /** 打这条配方当前的输入/输出/电压（判据来源：改前改后各读一次，比这两个字符串）。 */
    private static int ioDump(CommandContext<CommandSourceStack> ctx, ResourceLocation recipeId) {
        final var server = ctx.getSource().getServer();
        final var recipe = ShanhaiRecipeReverseIndex.byId(server, recipeId);
        if (recipe == null) {
            ctx.getSource().sendFailure(Component.literal("§c找不到这条配方：" + recipeId));
            return 0;
        }
        final String in = ShanhaiRecipeIoApply.tableJson(recipe, "inputs").toString();
        final String out = ShanhaiRecipeIoApply.tableJson(recipe, "outputs").toString();
        final String tick = ShanhaiRecipeIoApply.tableJson(recipe, "tickInputs").toString();
        ctx.getSource().sendSuccess(() -> Component.literal("§ainputs §f" + in), false);
        ctx.getSource().sendSuccess(() -> Component.literal("§aoutputs §f" + out), false);
        ctx.getSource().sendSuccess(() -> Component.literal("§atickInputs §f" + tick), false);
        ctx.getSource().sendSuccess(() -> Component.literal("§aEU/t §f" + ShanhaiRecipeIoApply.euOf(recipe)
                + " §7euTier §f" + recipe.data.getInt("euTier")
                + " §7side §f" + ShanhaiRecipeIoApply.euSideOf(recipe)), false);
        ShanhaiMod.LOGGER.info("{} cmd_io_dump id={} eu={} euTier={} side={} inputs={} outputs={} tickInputs={}",
                PREFIX, recipeId, ShanhaiRecipeIoApply.euOf(recipe), recipe.data.getInt("euTier"),
                ShanhaiRecipeIoApply.euSideOf(recipe), in, out, tick);
        return 1;
    }

    /** {@code /shanhai edit io-set <配方> in|out <槽位> <物品id> <数量>} —— 槽位 == 现有条数时是追加。 */
    private static int ioSet(CommandContext<CommandSourceStack> ctx, boolean input) {
        final var server = ctx.getSource().getServer();
        final ResourceLocation recipeId = ResourceLocationArgument.getId(ctx, "recipe");
        final int slot = IntegerArgumentType.getInteger(ctx, "slot");
        final ResourceLocation itemId = ResourceLocationArgument.getId(ctx, "item");
        final int count = IntegerArgumentType.getInteger(ctx, "count");

        final var recipe = ShanhaiRecipeReverseIndex.byId(server, recipeId);
        if (recipe == null) {
            ctx.getSource().sendFailure(Component.literal("§c找不到这条配方：" + recipeId));
            return 0;
        }
        final String table = input ? "inputs" : "outputs";
        final com.google.gson.JsonObject io = ShanhaiRecipeIoApply.tableJson(recipe, table);
        final com.google.gson.JsonArray arr = io.has("item") && io.get("item").isJsonArray()
                ? io.getAsJsonArray("item") : new com.google.gson.JsonArray();
        if (slot > arr.size()) {
            ctx.getSource().sendFailure(Component.literal("§c槽位 " + slot + " 越界：现在只有 " + arr.size()
                    + " 条（槽位只能填 0.." + arr.size() + "，填 " + arr.size() + " 是追加）"));
            return 0;
        }
        final com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
        final com.google.gson.JsonObject content = new com.google.gson.JsonObject();
        content.addProperty("type", "gtceu:sized");
        content.addProperty("count", count);
        final com.google.gson.JsonObject ing = new com.google.gson.JsonObject();
        ing.addProperty("item", itemId.toString());
        content.add("ingredient", ing);
        entry.add("content", content);
        entry.addProperty("chance", 10000);
        entry.addProperty("maxChance", 10000);
        entry.addProperty("tierChanceBoost", 0);
        if (slot == arr.size()) {
            arr.add(entry);
        } else {
            arr.set(slot, entry);
        }
        io.add("item", arr);

        final var r = ShanhaiRecipeEditorOps.setIo(server, recipe,
                input ? io : null, input ? null : io, null, true, true);
        final int n = arr.size();
        ctx.getSource().sendSuccess(() -> Component.literal("§a" + r.message() + " §7" + table + " 现在 " + n
                + " 条 §8(" + r.detail() + ")"), true);
        return r.ok() ? 1 : 0;
    }

    /** {@code /shanhai edit io-del <配方> in|out <槽位>} —— 删掉那一条（删光就是"输入为空"）。 */
    private static int ioDel(CommandContext<CommandSourceStack> ctx, boolean input) {
        final var server = ctx.getSource().getServer();
        final ResourceLocation recipeId = ResourceLocationArgument.getId(ctx, "recipe");
        final int slot = IntegerArgumentType.getInteger(ctx, "slot");
        final var recipe = ShanhaiRecipeReverseIndex.byId(server, recipeId);
        if (recipe == null) {
            ctx.getSource().sendFailure(Component.literal("§c找不到这条配方：" + recipeId));
            return 0;
        }
        final String table = input ? "inputs" : "outputs";
        final com.google.gson.JsonObject io = ShanhaiRecipeIoApply.tableJson(recipe, table);
        final com.google.gson.JsonArray arr = io.has("item") && io.get("item").isJsonArray()
                ? io.getAsJsonArray("item") : new com.google.gson.JsonArray();
        if (slot < 0 || slot >= arr.size()) {
            ctx.getSource().sendFailure(Component.literal("§c槽位 " + slot + " 越界：只有 " + arr.size() + " 条"));
            return 0;
        }
        arr.remove(slot);
        io.add("item", arr);
        final var r = ShanhaiRecipeEditorOps.setIo(server, recipe,
                input ? io : null, input ? null : io, null, true, true);
        final int n = arr.size();
        ctx.getSource().sendSuccess(() -> Component.literal("§a已删除 " + table + " 槽位 " + slot
                + " §7现在 " + n + " 条 §8(" + r.detail() + ")"), true);
        return r.ok() ? 1 : 0;
    }

    /** {@code /shanhai edit eut <配方> <EU/t>} —— 同拍写 tickInputs 与 data.euTier。 */
    private static int setEut(CommandContext<CommandSourceStack> ctx, ResourceLocation recipeId, long value) {
        final var server = ctx.getSource().getServer();
        final var recipe = ShanhaiRecipeReverseIndex.byId(server, recipeId);
        if (recipe == null) {
            ctx.getSource().sendFailure(Component.literal("§c找不到这条配方：" + recipeId));
            return 0;
        }
        final var r = ShanhaiRecipeEditorOps.setEut(server, recipe, value, true, true);
        ctx.getSource().sendSuccess(() -> Component.literal("§a" + r.message() + " §8(" + r.detail() + ")"), true);
        return r.ok() ? 1 : 0;
    }

    /** {@code /shanhai edit restore} —— 全部恢复（清覆盖文件 + 台账 + 只重建受影响的类型）。 */
    private static int restoreAll(CommandContext<CommandSourceStack> ctx) {
        final var server = ctx.getSource().getServer();
        final var r = ShanhaiRecipeEditorOps.restoreAll(server);
        ctx.getSource().sendSuccess(() -> Component.literal("§a" + r.message() + " §8(" + r.detail() + ")"), true);
        return r.ok() ? 1 : 0;
    }

    /** {@code /shanhai edit restore <配方id>} —— 只恢复那一条。 */
    private static int restoreOne(CommandContext<CommandSourceStack> ctx, ResourceLocation recipeId) {
        final var server = ctx.getSource().getServer();
        final var recipe = ShanhaiRecipeReverseIndex.byId(server, recipeId);
        if (recipe == null) {
            ctx.getSource().sendFailure(Component.literal("§c找不到这条配方：" + recipeId));
            return 0;
        }
        final var r = ShanhaiRecipeEditorOps.restoreOne(server, recipe, true, true);
        ctx.getSource().sendSuccess(() -> Component.literal("§a" + r.message() + " §8(" + r.detail() + ")"), true);
        return r.ok() ? 1 : 0;
    }
}
