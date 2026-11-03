package com.shanhai.common.diag;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;

import com.google.common.collect.Table;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeHandler;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shanhai.ShanhaiMod;
import com.shanhai.machine.stellar.ShanhaiPatternTypeReader;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 山海重构 · {@code /shanhai why [x y z]} —— <b>只读</b>的"这台机为什么不动"诊断命令。
 *
 * <h2>1. 命令签名（挂在既有 {@code /shanhai} 这棵树上，<b>不新增命令根</b>）</h2>
 * <pre>
 *   /shanhai why            自动锁定：先看视线 8 格，再看脚下/身旁 8×5×8 里最近的那台【我们自己的】机器
 *   /shanhai why &lt;x&gt; &lt;y&gt; &lt;z&gt;  指定坐标（点控制器、点任意仓室都行 —— 仓室会回溯到它的控制器）
 * </pre>
 * 回显 ①–⑥ 六段（排版与判定在 {@link ShanhaiWhyCore}，纯 JDK 可离线单跑）；
 * 每一行同时以 {@code [SHANHAI-WHY]} 前缀写进日志（聊天栏放不下时聊天栏只发摘要 ①③④⑥）。
 *
 * <h2>2. 🔴 只读：本类<b>不扣料、不 push、不改任何状态、不触发重建</b></h2>
 * <table border="1">
 *   <tr><th>要做的事</th><th>用的 API</th><th>为什么它是只读的</th></tr>
 *   <tr><td>⑤ 能不能匹配</td>
 *       <td>{@code RecipeRunnerHelper.matchRecipe(holder, recipe)}</td>
 *       <td>{@code javap -c} 实证：{@code matchRecipeInput} → {@code handleRecipe(IO.IN, holder,
 *           recipe.inputs, Collections.emptyMap(), false, recipe, <b>true</b>)} —— 最后一个参数
 *           就是 {@code isSimulate}。这条路只"判够不够"，<b>不</b>调 {@code forgetLastInput} /
 *           {@code modifyRecipeInput}（那两个才是真扣）。</td></tr>
 *   <tr><td>③ 样板数 / 清单</td><td>{@code ICraftingProvider#getAvailablePatterns()}</td>
 *       <td>两台自家总成的覆写都是"读缓存字段"；样板详情只读 {@code getOutputs()}
 *           与样板自己的 NBT（{@link ShanhaiPatternTypeReader} 全程 try/catch、只读）。</td></tr>
 *   <tr><td>⑤ 现有多少</td><td>{@code getCapabilitiesProxy().row(IO.IN / IO.BOTH)} → {@code getContents()}</td>
 *       <td>与 {@code PrimordialModuleMachine#shanhai$scanInputs}（丢料探针）<b>同一层、同一口径</b>：
 *           引擎扣的就是这一层，所以"比现有"只能在这一层上读。</td></tr>
 * </table>
 * <b>并且命令自己在运行时验一遍只读性</b>：跑之前 / 跑完各取一次"内容物指纹"
 * （{@link ShanhaiWhyCore#fingerprint}），两次必须<b>逐字节相同</b>，结果写进日志
 * {@code readonly_ok=…}。这一条同时是自证第 ③ 条的负对照入口
 * （把 SIMULATE 改成真扣 ⇒ 指纹必然变 ⇒ 断言红）。
 *
 * <h2>3. 为什么不复用 {@code ShanhaiGuardCommand} 那道"背包里要有模块"的门槛</h2>
 * 这是<b>只读</b>诊断：不扣料、不改开关、不发包。门槛挡住的恰恰是"机器坏了、我手上没模块"的那一刻
 * —— 而那正是最需要它的时刻。日志里记 {@code who=} 谁跑的。（这一条写进报告的「待用户确认」。）
 *
 * <h2>4. 任何异常都不许崩服</h2>
 * 整条链路包在 try/catch 里：异常只打日志 + 聊天栏报一句「诊断失败：<一句话>」。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiWhyCommand {

    public static final String PREFIX = ShanhaiWhyCore.LOG_PREFIX;

    /** 视线锁定机器的距离（格）。 */
    private static final double LOOK_REACH = 8.0D;

    /** "身旁最近"那个搜索盒（水平半径 / 垂直半径）。 */
    private static final int NEAR_R = 8;
    private static final int NEAR_DY = 5;

    private ShanhaiWhyCommand() {}

    // ══════════════════════════════════ 注册 ══════════════════════════════════

    /** 命令分支：{@code /shanhai} 下面的 {@code why} 一棵子树。 */
    public static LiteralArgumentBuilder<CommandSourceStack> commandBranch() {
        return Commands.literal(ShanhaiWhyCore.COMMAND_WHY)
                .executes(ctx -> run(ctx, ShanhaiWhyCore.Args.auto()))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> run(ctx, ShanhaiWhyCore.Args.at(
                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                IntegerArgumentType.getInteger(ctx, "z")))))));
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // ⚠️ 与 ShanhaiGuardCommand / ShanhaiRecipeStats / ShanhaiRecipeEditorCommand / ShanhaiCreativeItemsCommand
        //    各自 register 一次 literal("shanhai")：Brigadier 会合并同名子节点（既有命令的 children 一个都不丢）。
        event.getDispatcher().register(Commands.literal("shanhai").then(commandBranch()));
        final var shanhaiNode = event.getDispatcher().getRoot().getChild("shanhai");
        final StringBuilder kids = new StringBuilder();
        if (shanhaiNode != null) {
            for (var c : shanhaiNode.getChildren()) {
                kids.append(c.getName()).append(' ');
            }
        }
        ShanhaiMod.LOGGER.info("{} command_registered /shanhai {} ; shanhai_children=[{}]",
                PREFIX, ShanhaiWhyCore.COMMAND_WHY, kids.toString().trim());
    }

    // ══════════════════════════════════ 入口 ══════════════════════════════════

    private static int run(CommandContext<CommandSourceStack> ctx, ShanhaiWhyCore.Args args) {
        final CommandSourceStack source = ctx.getSource();
        final String who = safeName(source);
        final long startedNanos = System.nanoTime();
        try {
            if (args.error != null) {
                // 参数本身就错：一句话报回去（这是"打错字母不许崩"那条自证的落点）
                ShanhaiMod.LOGGER.info("{} reject who={} reason=bad_args detail={}", PREFIX, who, args.error);
                source.sendFailure(Component.literal("§c" + ShanhaiWhyCore.failLine(args.error)));
                return 0;
            }
            final Resolved resolved = resolve(source, args);
            if (resolved.machine == null) {
                ShanhaiMod.LOGGER.info("{} none who={} {} reason={}", PREFIX, who, args.describe(),
                        resolved.why);
                source.sendFailure(Component.literal("§c没有找到可诊断的机器：" + resolved.why));
                return 0;
            }
            final MultiblockControllerMachine machine = resolved.machine;
            final Scan scan = scan(machine);
            final List<ShanhaiWhyCore.Target<GTRecipe>> targets = targetsOf(source, scan);
            final IRecipeCapabilityHolder holder = holderOf(machine);
            if (holder == null) {
                throw new IllegalStateException("这台机器不实现 IRecipeCapabilityHolder ⇒ 判不了配方");
            }
            final ShanhaiWhyCore.RecipeProbe<GTRecipe> probe = probeOf(holder);

            // 🔴 只读自证：判之前 / 判之后各取一次内容物指纹（中间不会有 tick 插进来，我们在主线程）
            final Map<String, Long> contentsBefore = scanContents(holder).counts;
            final ShanhaiWhyCore.Report report = ShanhaiWhyCore.build(scan.view, targets, probe);
            final Map<String, Long> contentsAfter = scanContents(holder).counts;
            final String fpBefore = ShanhaiWhyCore.fingerprint(contentsBefore);
            final String fpAfter = ShanhaiWhyCore.fingerprint(contentsAfter);
            final boolean readOnlyOk = fpBefore.equals(fpAfter);
            final int matched = countMatched(report);

            // ---- 日志：全量（用户规格：完整清单打日志） ----
            for (String line : report.all) {
                ShanhaiMod.LOGGER.info("{} {}", PREFIX, line);
            }
            ShanhaiMod.LOGGER.info("{} run who={} {} machine={} formed={} status={} patterns={} "
                            + "assemblies={} types={} targets={} recipes_evaluated={} matched={} "
                            + "route={} lines={} readonly_ok={} fp_before={} fp_after={} ms={}",
                    PREFIX, who, args.describe(), scan.view.id, scan.view.formed,
                    scan.view.working ? "WORKING" : "IDLE", scan.view.patternCount(),
                    scan.view.assemblyCount(), scan.view.declaredTypes.size(), targets.size(),
                    evaluatedOf(targets), matched, report.chatRoute,
                    report.all.size(), readOnlyOk, shortDigest(fpBefore), shortDigest(fpAfter),
                    Long.valueOf((System.nanoTime() - startedNanos) / 1_000_000L));
            if (!readOnlyOk) {
                ShanhaiMod.LOGGER.error("{} READONLY-VIOLATION 内容物指纹变了！before={} after={} "
                                + "⇒ 这条命令已经不再是只读的，别信它的读数（机器={} {}）",
                        PREFIX, fpBefore, fpAfter, scan.view.id, args.describe());
            }

            // ---- 聊天栏：放得下发全部，放不下只发摘要（①③④⑥） ----
            for (String line : report.forChat()) {
                final String shown = "§b" + line;
                source.sendSuccess(() -> Component.literal(shown), false);
            }
            return 1;
        } catch (Throwable t) {
            // 用户规格：任何异常只打日志、聊天栏报一句"诊断失败：<一句话>"，不许崩服
            ShanhaiMod.LOGGER.error("{} failed who={} {} err={}", PREFIX, who, args.describe(),
                    ShanhaiWhyCore.oneLine(t), t);
            try {
                source.sendFailure(Component.literal("§c" + ShanhaiWhyCore.failLine(t)));
            } catch (Throwable ignored) {
                // 连回显都失败（玩家刚掉线之类）⇒ 日志里已经有了，不再往外抛
            }
            return 0;
        }
    }

    private static int evaluatedOf(List<ShanhaiWhyCore.Target<GTRecipe>> targets) {
        int n = 0;
        for (ShanhaiWhyCore.Target<GTRecipe> t : targets) {
            n += t.recipes.size();
        }
        return n;
    }

    private static int countMatched(ShanhaiWhyCore.Report report) {
        int n = 0;
        for (String line : report.all) {
            if (line != null && line.contains("匹配成功：")) {
                n++;
            }
        }
        return n;
    }

    /**
     * 指纹的短摘要（16 个十六进制字符，SHA-256 前缀）。
     *
     * <p>为什么不把整份指纹写进日志：一台 AE 供料的机器可能有几百个条目 ⇒ 一行日志几 KB，刷屏。
     * <b>但变了的时候</b>上面那句 ERROR 会把两份**完整**指纹打出来 —— 摘要只用于"平时可 grep、一眼可比"。
     */
    private static String shortDigest(String s) {
        try {
            final java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            final byte[] d = md.digest(String.valueOf(s).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8 && i < d.length; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(d[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            // 拿不到摘要就退回"长度＋首尾"，总比没有强（但这时可读性差，所以上面那条才是主判据）
            return "len=" + (s == null ? -1 : s.length());
        }
    }

    // ══════════════════════════════════ 找机器 ══════════════════════════════════

    /** 找机器的结果：{@code machine == null} 时 {@code why} 是给玩家看的那句话。 */
    private static final class Resolved {

        final MultiblockControllerMachine machine;
        final String why;

        Resolved(MultiblockControllerMachine machine, String why) {
            this.machine = machine;
            this.why = why;
        }

        static Resolved ok(MultiblockControllerMachine m) {
            return new Resolved(m, null);
        }

        static Resolved fail(String why) {
            return new Resolved(null, why);
        }
    }

    private static Resolved resolve(CommandSourceStack source, ShanhaiWhyCore.Args args) {
        final ServerLevel level = source.getLevel();
        if (args.hasPos()) {
            return at(level, new BlockPos(args.pos[0], args.pos[1], args.pos[2]));
        }
        final ServerPlayer player = source.getPlayer();
        if (player == null) {
            return Resolved.fail("控制台没有「视线」，请给坐标：/shanhai " + ShanhaiWhyCore.COMMAND_WHY
                    + " <x> <y> <z>");
        }
        // ① 视线先（"指着它问"是最自然的用法）
        final Resolved looked = lookAt(player);
        if (looked.machine != null) {
            ShanhaiMod.LOGGER.info("{} lock_by=look pos={}", PREFIX, looked.machine.getPos());
            return looked;
        }
        // ② 身旁最近
        final Resolved near = nearestOwn(level, player.blockPosition());
        if (near.machine != null) {
            ShanhaiMod.LOGGER.info("{} lock_by=near pos={}", PREFIX, near.machine.getPos());
            return near;
        }
        return Resolved.fail("视线 " + (int) LOOK_REACH + " 格内和身旁 " + NEAR_R + " 格内都没有我们自己的机器"
                + "（用 /shanhai " + ShanhaiWhyCore.COMMAND_WHY + " <x> <y> <z> 指定坐标）");
    }

    private static Resolved lookAt(ServerPlayer player) {
        try {
            final net.minecraft.world.phys.HitResult hit = player.pick(LOOK_REACH, 1.0F, false);
            if (!(hit instanceof BlockHitResult blockHit)) {
                return Resolved.fail("视线没打到方块");
            }
            return at(player.level(), blockHit.getBlockPos());
        } catch (Throwable t) {
            return Resolved.fail("视线取方块失败：" + ShanhaiWhyCore.oneLine(t));
        }
    }

    private static Resolved at(Level level, BlockPos pos) {
        final MetaMachine any;
        try {
            any = MetaMachine.getMachine(level, pos);
        } catch (Throwable t) {
            return Resolved.fail("读 " + pos.toShortString() + " 上的机器失败：" + ShanhaiWhyCore.oneLine(t));
        }
        if (any == null) {
            return Resolved.fail(pos.toShortString() + " 上没有机器方块");
        }
        final MultiblockControllerMachine controller = asController(any);
        if (controller == null) {
            return Resolved.fail(pos.toShortString() + " 是 " + simpleName(any)
                    + "，它不是多方块控制器、也不属于任何多方块");
        }
        final String id = definitionId(controller);
        if (!ours(id)) {
            return Resolved.fail(pos.toShortString() + " 那台是 " + id + "（不是我们的机器）");
        }
        return Resolved.ok(controller);
    }

    /** 一台机器 → 它所属的多方块控制器（控制器就是它自己 / 仓室回溯 / 控制器自引用）。 */
    private static MultiblockControllerMachine asController(MetaMachine machine) {
        if (machine == null) {
            return null;
        }
        try {
            if (machine instanceof MultiblockControllerMachine controller) {
                return controller;
            }
            if (machine instanceof IMultiPart part) {
                final List<IMultiController> controllers = part.getControllers();
                if (controllers != null) {
                    for (IMultiController c : controllers) {
                        if (c == null) {
                            continue;
                        }
                        final MultiblockControllerMachine self = c.self();
                        if (self != null) {
                            return self;
                        }
                    }
                }
            }
            if (machine instanceof IMultiController controller) {
                return controller.self();
            }
        } catch (Throwable ignored) {
            // 单台读不出来就当"不是控制器"，不拖垮整条命令
        }
        return null;
    }

    /** 身旁最近的一台"我们自己的"多方块控制器（只读、只查我们自己命名空间的方块）。 */
    private static Resolved nearestOwn(Level level, BlockPos center) {
        MultiblockControllerMachine best = null;
        double bestDist = Double.MAX_VALUE;
        final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int dx = -NEAR_R; dx <= NEAR_R; dx++) {
            for (int dy = -NEAR_DY; dy <= NEAR_DY; dy++) {
                for (int dz = -NEAR_R; dz <= NEAR_R; dz++) {
                    probe.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!level.isLoaded(probe)) {
                        continue;
                    }
                    final BlockState state;
                    try {
                        state = level.getBlockState(probe);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    // 先看方块命名空间，绝大多数方块在这一步就被筛掉了（不必去摸方块实体）
                    if (!blockOurs(state)) {
                        continue;
                    }
                    final MultiblockControllerMachine controller;
                    try {
                        controller = asController(MetaMachine.getMachine(level, probe));
                    } catch (Throwable t) {
                        continue;
                    }
                    if (controller == null || !ours(definitionId(controller))) {
                        continue;
                    }
                    final double d = probe.distSqr(center);
                    if (d < bestDist) {
                        bestDist = d;
                        best = controller;
                    }
                }
            }
        }
        return best == null ? Resolved.fail("身旁没有") : Resolved.ok(best);
    }

    private static boolean blockOurs(BlockState state) {
        try {
            final ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return key != null && ShanhaiMod.MOD_ID.equals(key.getNamespace());
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean ours(String definitionId) {
        if (definitionId == null) {
            return false;
        }
        final int colon = definitionId.indexOf(':');
        return colon > 0 && ShanhaiMod.MOD_ID.equals(definitionId.substring(0, colon));
    }

    // ══════════════════════════════════ 读机器（只读） ══════════════════════════════════

    /** 一次扫描的结果：给 {@link ShanhaiWhyCore} 的纯数据 ＋ 判定目标要用的类型对象。 */
    private static final class Scan {

        ShanhaiWhyCore.MachineView view;
        /** 机器声明的类型对象（④⑤ 的候选池）。 */
        final List<GTRecipeType> declared = new ArrayList<>();
        /** 机器当前激活的类型（样板/机器都读不出类型时的兜底）。 */
        GTRecipeType active;
    }

    private static Scan scan(MultiblockControllerMachine controller) {
        final Scan out = new Scan();
        final String id = definitionId(controller);
        final Level level = controller.getLevel();
        final String dim = level == null ? "?" : String.valueOf(level.dimension().location());
        final BlockPos pos = controller.getPos();

        boolean formed = false;
        boolean working = false;
        String running = null;
        final List<String> declaredIds = new ArrayList<>();
        String activeId = null;
        if (controller instanceof IRecipeLogicMachine rlm) {
            try {
                formed = controller.isFormed();
            } catch (Throwable ignored) {
                formed = false;
            }
            try {
                final GTRecipeType[] types = rlm.getRecipeTypes();
                if (types != null) {
                    for (GTRecipeType t : types) {
                        if (t == null) {
                            continue;
                        }
                        out.declared.add(t);
                        declaredIds.add(typeId(t));
                    }
                }
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn("{} read_types_failed machine={} err={}", PREFIX, id,
                        ShanhaiWhyCore.oneLine(t));
            }
            try {
                out.active = rlm.getRecipeType();
                activeId = typeId(out.active);
            } catch (Throwable ignored) {
                out.active = null;
            }
            try {
                final RecipeLogic logic = rlm.getRecipeLogic();
                if (logic != null) {
                    working = logic.isWorking();
                    running = recipeId(logic.getLastRecipe());
                }
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn("{} read_logic_failed machine={} err={}", PREFIX, id,
                        ShanhaiWhyCore.oneLine(t));
            }
        } else {
            try {
                formed = controller.isFormed();
            } catch (Throwable ignored) {
                formed = false;
            }
        }

        // ---- ③ 挂着的样板总成（逐台读 getAvailablePatterns()，只读缓存） ----
        //
        //  🔴 2026-11-01（用户那台是【装在主机上的模块】）：模块自己的结构里可能一块样板总成都没有，
        //     样板其实挂在【主机】那台的结构上。只读模块本体会把"主机上有 12 块样板"报成
        //     "一块样板都没有"——那是一条【假结论】，比不报更坏。所以这里把主机结构上的也并进来，
        //     并明确告诉用户"合并计数"（不静默混算）。
        final List<MetaMachine> providers = providersOf(controller);
        String hostMergedNote = null;
        try {
            if (controller instanceof com.shanhai.machine.module.PrimordialModuleMachine module) {
                final MultiblockControllerMachine host = module.getHost();   // 纯字段 getter（:1775）
                if (host != null && host != controller) {
                    final List<MetaMachine> hostProviders = providersOf(host);
                    int added = 0;
                    for (MetaMachine m : hostProviders) {
                        if (!containsIdentity(providers, m)) {
                            providers.add(m);
                            added++;
                        }
                    }
                    if (added > 0) {
                        hostMergedNote = "⚠ 这台模块挂在主机 " + definitionId(host)
                                + " 上；③ 的样板总成与样板清单把【主机结构上的 " + added
                                + " 处】也并了进来（合并计数，不是各自分列）。";
                    }
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} read_host_failed machine={} err={}", PREFIX, id,
                    ShanhaiWhyCore.oneLine(t));
        }

        final Map<String, int[]> byClass = new LinkedHashMap<>();   // 类名 → {台数, 样板数, 读不出}
        final List<ShanhaiWhyCore.PatternView> patterns = new ArrayList<>();
        for (MetaMachine provider : providers) {
            final String cn = simpleName(provider);
            final int[] acc = byClass.computeIfAbsent(cn, k -> new int[3]);
            acc[0]++;
            List<IPatternDetails> list = null;
            try {
                list = ((ICraftingProvider) provider).getAvailablePatterns();
            } catch (Throwable t) {
                acc[2]++;
                ShanhaiMod.LOGGER.warn("{} read_patterns_failed machine={} part={} err={}", PREFIX,
                        id, cn, ShanhaiWhyCore.oneLine(t));
            }
            if (list == null) {
                continue;
            }
            acc[1] += list.size();
            for (IPatternDetails pattern : list) {
                if (pattern == null) {
                    continue;
                }
                patterns.add(patternView(pattern));
            }
        }
        final List<ShanhaiWhyCore.Assembly> assemblies = new ArrayList<>(byClass.size());
        for (Map.Entry<String, int[]> e : byClass.entrySet()) {
            final int[] v = e.getValue();
            assemblies.add(new ShanhaiWhyCore.Assembly(e.getKey(), v[0], v[1], v[2]));
        }

        out.view = new ShanhaiWhyCore.MachineView(id, dim,
                pos == null ? 0 : pos.getX(), pos == null ? 0 : pos.getY(), pos == null ? 0 : pos.getZ(),
                formed, working, running, declaredIds, assemblies, patterns, activeId,
                hostMergedNote == null ? List.of() : List.of(hostMergedNote));
        return out;
    }

    /** 按<b>对象身份</b>判重（{@code MetaMachine} 没有可靠的 equals）。 */
    private static boolean containsIdentity(List<MetaMachine> list, MetaMachine machine) {
        for (MetaMachine m : list) {
            if (m == machine) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本结构里所有"可能装着样板"的仓室（控制器自己 ＋ 每个 {@code ICraftingProvider} 仓室）。
     *
     * <p>为什么把控制器也算进去：{@code MEPatternBufferPartMachineBase implements ICraftingProvider}
     * ({@code javap} 实证)，而"控制器本身直接实现它"的情形在别的机型上真实存在 ——
     * 少算一台就会把"有样板"报成"没样板"，那是最坏的一种错。
     */
    private static List<MetaMachine> providersOf(MultiblockControllerMachine controller) {
        final List<MetaMachine> out = new ArrayList<>();
        addProvider(out, controller);
        List<IMultiPart> parts = null;
        try {
            parts = controller.getParts();
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} read_parts_failed machine={} err={}", PREFIX,
                    definitionId(controller), ShanhaiWhyCore.oneLine(t));
        }
        if (parts != null) {
            for (IMultiPart part : parts) {
                if (part == null) {
                    continue;
                }
                try {
                    addProvider(out, part.self());
                } catch (Throwable ignored) {
                    // 单个仓室读不出来就跳过（与 ShanhaiLocateDiscovery 同一条纪律）
                }
            }
        }
        return out;
    }

    private static void addProvider(List<MetaMachine> out, MetaMachine machine) {
        if (machine == null || !(machine instanceof ICraftingProvider)) {
            return;
        }
        for (MetaMachine m : out) {
            if (m == machine) {
                return;
            }
        }
        out.add(machine);
    }

    /** 一块样板的只读快照：产物（id × 数量）＋ 它自己声明的配方类型（读不出 = unknown）。 */
    private static ShanhaiWhyCore.PatternView patternView(IPatternDetails pattern) {
        final List<String> outputs = new ArrayList<>();
        try {
            final GenericStack[] stacks = pattern.getOutputs();
            if (stacks != null) {
                for (GenericStack g : stacks) {
                    if (g == null || g.what() == null) {
                        continue;
                    }
                    outputs.add(keyId(g.what()) + " × " + g.amount());
                }
            }
        } catch (Throwable ignored) {
            // 一条样板读不出产物 ⇒ 就让它空着（画面上是 unknown，不是"没产物"）
        }
        String type = null;
        try {
            final AEItemKey definition = pattern.getDefinition();
            if (definition != null) {
                // 与星律那边同一个读取层（四个来源、去重后只剩 1 个才采信；本方法全程 try/catch）
                final ShanhaiPatternTypeReader.Resolution resolution =
                        ShanhaiPatternTypeReader.resolve(definition.getReadOnlyStack());
                if (resolution != null) {
                    final String unique = resolution.uniqueId();
                    if (unique != null && !unique.isEmpty()) {
                        type = unique;
                    } else if (resolution.ids() != null && !resolution.ids().isEmpty()) {
                        // 歧义：原样把候选列出来，不挑一个（不猜）
                        type = String.join("|", resolution.ids()) + "（歧义，不采信）";
                    }
                }
            }
        } catch (Throwable ignored) {
            type = null;
        }
        return new ShanhaiWhyCore.PatternView(outputs, type);
    }

    // ══════════════════════════════════ ④⑤ 目标类型与配方 ══════════════════════════════════

    /**
     * 挑"目标类型"并把这个类型下的配方捞出来。
     *
     * <pre>
     *   样板里读得出类型 ⇒ 用【样板声明的类型 ∩ 机器声明的类型】
     *     交集为空      ⇒ 醒目报一条"两边没有交集"（这本身就是病），目标退回机器声明的全部类型
     *   样板读不出类型   ⇒ 用机器【当前激活】的类型；连激活类型都读不出 ⇒ 用机器声明的全部类型
     * </pre>
     * 数据源与 {@code ShanhaiRecipeStats#countRecipesOfType} 同一口径：
     * {@code RecipeManager#getRecipes()} 全表拉平后按<b>类型句柄身份</b>过滤
     * （{@code byType(RecipeType)} 在 1.20.1 是 private）。
     */
    private static List<ShanhaiWhyCore.Target<GTRecipe>> targetsOf(CommandSourceStack source, Scan scan) {
        if (scan.declared.isEmpty()) {
            return List.of();
        }
        final Set<String> sampleTypes = new LinkedHashSet<>();
        for (ShanhaiWhyCore.PatternView p : scan.view.patterns) {
            if (p.declaredType != null && !p.declaredType.isEmpty() && !p.declaredType.contains("歧义")) {
                sampleTypes.add(p.declaredType);
            }
        }
        final List<GTRecipeType> chosen = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        if (!sampleTypes.isEmpty()) {
            for (GTRecipeType t : scan.declared) {
                if (sampleTypes.contains(typeId(t))) {
                    chosen.add(t);
                }
            }
            if (chosen.isEmpty()) {
                warnings.add("⚠ 样板声明的类型（" + String.join("，", sampleTypes)
                        + "）与机器声明的类型（" + joinDeclared(scan) + "）【没有交集】"
                        + " ⇒ 这台机不可能按这些样板跑配方，先把样板的类型改对。");
                chosen.addAll(scan.declared);
            }
        } else if (scan.active != null && containsIdentity(scan.declared, scan.active)) {
            chosen.add(scan.active);
        } else {
            chosen.addAll(scan.declared);
        }
        // warnings 塞进 view（view 是 final 字段，这里重建一次）
        if (!warnings.isEmpty()) {
            final ShanhaiWhyCore.MachineView v = scan.view;
            scan.view = new ShanhaiWhyCore.MachineView(v.id, v.dim, v.x, v.y, v.z, v.formed, v.working,
                    v.runningRecipeId, v.declaredTypes, v.assemblies, v.patterns, v.activeTypeId, warnings);
        }

        // 全表扫一次，按类型句柄身份分桶
        final Map<GTRecipeType, List<GTRecipe>> byType = new IdentityHashMap<>();
        try {
            for (Recipe<?> r : source.getServer().getRecipeManager().getRecipes()) {
                if (r instanceof GTRecipe gr && gr.recipeType != null && gr.id != null) {
                    byType.computeIfAbsent(gr.recipeType, k -> new ArrayList<>()).add(gr);
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} recipe_table_scan_failed err={}", PREFIX, ShanhaiWhyCore.oneLine(t), t);
        }

        final List<ShanhaiWhyCore.Target<GTRecipe>> out = new ArrayList<>(chosen.size());
        int budget = ShanhaiWhyCore.SIMULATE_CAP;
        for (GTRecipeType t : chosen) {
            final List<GTRecipe> all = byType.get(t);
            final int total = all == null ? 0 : all.size();
            final List<ShanhaiWhyCore.RecipeRef<GTRecipe>> refs = new ArrayList<>();
            if (all != null && !all.isEmpty()) {
                final List<GTRecipe> sorted = new ArrayList<>(all);
                sorted.sort(Comparator.comparing((GTRecipe x) -> String.valueOf(x.id)));
                for (GTRecipe gr : sorted) {
                    if (budget <= 0) {
                        break;
                    }
                    refs.add(new ShanhaiWhyCore.RecipeRef<>(String.valueOf(gr.id), gr));
                    budget--;
                }
            }
            out.add(new ShanhaiWhyCore.Target<>(typeId(t), refs, total));
        }
        ShanhaiMod.LOGGER.info("{} targets = {}", PREFIX, joinTargets(out));
        return out;
    }

    private static String joinDeclared(Scan scan) {
        final StringBuilder sb = new StringBuilder();
        for (GTRecipeType t : scan.declared) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append(typeId(t));
        }
        return sb.toString();
    }

    private static String joinTargets(List<ShanhaiWhyCore.Target<GTRecipe>> targets) {
        final StringBuilder sb = new StringBuilder();
        for (ShanhaiWhyCore.Target<GTRecipe> t : targets) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(t.typeId).append("(total=").append(t.totalInIndex)
                    .append(",evaluated=").append(t.recipes.size()).append(')');
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    private static boolean containsIdentity(List<GTRecipeType> list, GTRecipeType wanted) {
        for (GTRecipeType t : list) {
            if (t == wanted) {
                return true;
            }
        }
        return false;
    }

    // ══════════════════════════════════ ⑤ 只读判定器 ══════════════════════════════════

    private static IRecipeCapabilityHolder holderOf(MultiblockControllerMachine controller) {
        return controller instanceof IRecipeCapabilityHolder holder ? holder : null;
    }

    /**
     * 注入给纯核的<b>唯一</b>会碰机器的入口：与引擎 {@code checkRecipe} 第一行<b>同一个调用</b>。
     *
     * <p>🔴 {@code matchRecipe} 就是 SIMULATE（{@code javap -c} 见类注释 §2）。
     * 它在失败时只回 {@code false}，所以 {@code shortages} 另外读一次输入侧 ——
     * <b>那一次只用来"说明缺什么"，不参与"能不能匹配"的判定</b>（判定永远只来自 {@code matchRecipe}）。
     */
    private static ShanhaiWhyCore.RecipeProbe<GTRecipe> probeOf(IRecipeCapabilityHolder holder) {
        return new ShanhaiWhyCore.RecipeProbe<GTRecipe>() {

            /**
             * 输入侧读数<b>整个命令只取一次</b>。
             *
             * <p>为什么这里敢缓存：整条命令跑在服务端主线程上，中间<b>不会有 tick 插进来</b>
             * （所以机器内容物在这一次调用期间是不变的）；而 {@code SIMULATE} 本身也不改内容物。
             * 不缓存的话，512 条配方 × 每台机的全部仓室条目 = 白白扫几百遍。
             */
            private Contents cachedContents;

            /** 逐条拆 inputs/outputs 的上限：失败配方可能上百条，拆一遍是 2 次额外 SIMULATE。 */
            private static final int DETAIL_CAP = 16;
            private int detailCount;

            @Override
            public boolean matches(GTRecipe recipe) {
                return RecipeRunnerHelper.matchRecipe(holder, recipe);
            }

            @Override
            public List<ShanhaiWhyCore.Shortage> shortages(GTRecipe recipe) {
                if (cachedContents == null) {
                    cachedContents = scanContents(holder);
                }
                return shortagesOf(recipe, cachedContents);
            }

            @Override
            public String detail(GTRecipe recipe) {
                if (++detailCount > DETAIL_CAP) {
                    return detailCount == DETAIL_CAP + 1
                            ? "（失败配方超过 " + DETAIL_CAP + " 条，不再逐条拆 inputs/outputs）" : "";
                }
                // 拆成两半：告诉用户问题在"料不够"还是"产物塞不下"（同样是 SIMULATE，同一个引擎类）
                final StringBuilder sb = new StringBuilder();
                try {
                    sb.append("inputs=").append(yesNo(RecipeRunnerHelper.matchRecipeInput(holder, recipe)));
                } catch (Throwable t) {
                    sb.append("inputs=").append(ShanhaiWhyCore.UNKNOWN);
                }
                try {
                    sb.append(" outputs=").append(yesNo(RecipeRunnerHelper.matchRecipeOutput(holder, recipe)));
                } catch (Throwable t) {
                    sb.append(" outputs=").append(ShanhaiWhyCore.UNKNOWN);
                }
                return sb.toString();
            }
        };
    }

    /**
     * 逐项列出"缺什么"。
     *
     * <h2>需要的来源（一定读得到）</h2>
     * {@code GTRecipe.inputs} 的每条 {@code content}：物品 / 流体 / 认不出的。
     *
     * <h2>现有的来源（可读性有闸门）</h2>
     * {@link #scanContents} 只把 {@code ItemStack} / LDLib 流体 / Forge 流体认成数字；
     * <b>只要看到一个读不懂的条目</b>（AE 的长库存、任何陌生类型）⇒ 本次全部写 {@code unknown}。
     * 理由：漏算 AE 网络里的库存会把"有 64"报成"现有 0"，那是<b>假数字</b>，比 unknown 更害人。
     */
    private static List<ShanhaiWhyCore.Shortage> shortagesOf(GTRecipe recipe, Contents have) {
        final List<ShanhaiWhyCore.Shortage> out = new ArrayList<>();
        final List<Req> reqs = new ArrayList<>();
        final Map<RecipeCapability<?>, List<Content>> inputs = recipe.inputs;
        if (inputs != null) {
            for (Map.Entry<RecipeCapability<?>, List<Content>> e : inputs.entrySet()) {
                final List<Content> contents = e.getValue();
                if (contents == null) {
                    continue;
                }
                for (Content c : contents) {
                    wants(reqs, c == null ? null : c.content);
                }
            }
        }
        if (!have.complete) {
            out.add(ShanhaiWhyCore.Shortage.note("输入侧有 " + have.unparsed
                    + " 个条目读不出（" + (have.note == null ? "陌生类型" : have.note)
                    + "）⇒ 本次「现有」全部标 unknown，一个数都不敢填"));
        }
        int ambiguous = 0;
        for (Req r : reqs) {
            if (r.label == null) {
                out.add(ShanhaiWhyCore.Shortage.note("有 1 项输入认不出是什么（原样跳过）"));
                continue;
            }
            if (r.key == null) {
                ambiguous++;                       // tag / 多候选 / 认不出：不猜
                continue;
            }
            final Long haveVal = have.complete
                    ? Long.valueOf(have.counts.getOrDefault(r.key, 0L)) : null;
            if (haveVal != null && haveVal.longValue() >= r.amount) {
                continue;                          // 这一项确定不缺
            }
            out.add(new ShanhaiWhyCore.Shortage(r.label, Long.valueOf(r.amount), haveVal, r.unit));
        }
        if (ambiguous > 0) {
            out.add(ShanhaiWhyCore.Shortage.note("另有 " + ambiguous + " 项是 tag / 多候选 / 认不出的类型"
                    + "（本工具不猜：「需要」读得到，「现有」读不出）"));
        }
        return out;
    }

    /** 一条输入需求。{@code key == null} ⇒ 多候选（tag）/ 认不出，"现有"读不出。 */
    private static final class Req {

        final String label;
        final String key;
        final long amount;
        final String unit;

        Req(String label, String key, long amount, String unit) {
            this.label = label;
            this.key = key;
            this.amount = amount;
            this.unit = unit == null ? "" : unit;
        }
    }

    private static void wants(List<Req> out, Object content) {
        if (content == null) {
            return;
        }
        try {
            if (content instanceof SizedIngredient sized) {
                final long amount = sized.getAmount();
                final List<String> ids = itemIds(sized.getInner());
                out.add(reqOf("item", ids, amount, ""));
                return;
            }
            if (content instanceof ItemStack stack) {
                if (stack.isEmpty()) {
                    return;
                }
                final List<String> ids = List.of(idOf(ForgeRegistries.ITEMS.getKey(stack.getItem())));
                out.add(reqOf("item", ids, stack.getCount(), ""));
                return;
            }
            if (content instanceof Ingredient ingredient) {
                final List<String> ids = itemIds(ingredient);
                out.add(reqOf("item", ids, 1L, ""));
                return;
            }
            if (content instanceof FluidIngredient fluid) {
                final List<String> ids = fluidIds(fluid);
                out.add(reqOf("fluid", ids, fluid.getAmount(), " mB"));
                return;
            }
            if (content instanceof com.lowdragmc.lowdraglib.side.fluid.FluidStack fs) {
                if (fs.isEmpty()) {
                    return;
                }
                final List<String> ids = List.of(idOf(ForgeRegistries.FLUIDS.getKey(fs.getFluid())));
                out.add(reqOf("fluid", ids, fs.getAmount(), " mB"));
                return;
            }
            if (content instanceof net.minecraftforge.fluids.FluidStack fs) {
                if (fs.isEmpty()) {
                    return;
                }
                final List<String> ids = List.of(idOf(ForgeRegistries.FLUIDS.getKey(fs.getFluid())));
                out.add(reqOf("fluid", ids, fs.getAmount(), " mB"));
                return;
            }
        } catch (Throwable ignored) {
            // 落到下面那句"认不出"
        }
        out.add(new Req(simpleName(content), null, 1L, ""));
    }

    private static Req reqOf(String kind, List<String> ids, long amount, String unit) {
        if (ids == null || ids.isEmpty()) {
            return new Req(kind + ":" + ShanhaiWhyCore.UNKNOWN, null, amount, unit);
        }
        if (ids.size() == 1) {
            final String id = ids.get(0);
            return new Req(id, kind + ":" + id, amount, unit);
        }
        final StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(id);
        }
        return new Req("其一(" + ids.size() + " 种): " + sb, null, amount, unit);
    }

    private static List<String> itemIds(Ingredient ingredient) {
        final Set<String> ids = new LinkedHashSet<>();
        if (ingredient != null) {
            final ItemStack[] stacks = ingredient.getItems();
            if (stacks != null) {
                for (ItemStack s : stacks) {
                    if (s == null || s.isEmpty()) {
                        continue;
                    }
                    ids.add(idOf(ForgeRegistries.ITEMS.getKey(s.getItem())));
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private static List<String> fluidIds(FluidIngredient ingredient) {
        final Set<String> ids = new LinkedHashSet<>();
        final com.lowdragmc.lowdraglib.side.fluid.FluidStack[] stacks =
                ingredient == null ? null : ingredient.getStacks();
        if (stacks != null) {
            for (com.lowdragmc.lowdraglib.side.fluid.FluidStack s : stacks) {
                if (s == null || s.isEmpty()) {
                    continue;
                }
                ids.add(idOf(ForgeRegistries.FLUIDS.getKey(s.getFluid())));
            }
        }
        return new ArrayList<>(ids);
    }

    // ══════════════════════════════════ 输入侧内容（只读） ══════════════════════════════════

    /**
     * 机器输入侧的一份读数。
     *
     * <p>{@link #complete} = <b>所有</b>条目都认成了"物品 / 流体 + 数量"。
     * 只要有一个认不出（AE 长库存会走 {@code AEKey} / {@code GenericStack} 之类），
     * {@code complete=false} ⇒ 上层一律写 {@code unknown}，<b>不许拿不完整的和去比。</b>
     */
    private static final class Contents {

        final Map<String, Long> counts = new TreeMap<>();
        boolean complete = true;
        int unparsed;
        String note;
    }

    private static Contents scanContents(IRecipeCapabilityHolder holder) {
        final Contents out = new Contents();
        if (holder == null) {
            out.complete = false;
            out.note = "机器不实现 IRecipeCapabilityHolder";
            return out;
        }
        try {
            final Table<IO, RecipeCapability<?>, List<IRecipeHandler<?>>> proxy =
                    holder.getCapabilitiesProxy();
            if (proxy == null) {
                out.complete = false;
                out.note = "能力代理为 null";
                return out;
            }
            for (IO io : new IO[]{IO.IN, IO.BOTH}) {
                for (Map.Entry<RecipeCapability<?>, List<IRecipeHandler<?>>> cell : proxy.row(io).entrySet()) {
                    final List<IRecipeHandler<?>> handlers = cell.getValue();
                    if (handlers == null) {
                        continue;
                    }
                    for (IRecipeHandler<?> handler : handlers) {
                        final List<Object> contents = handler.getContents();
                        if (contents == null) {
                            continue;
                        }
                        for (Object content : contents) {
                            accumulate(out, content);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            out.complete = false;
            out.note = "读输入侧抛异常：" + ShanhaiWhyCore.oneLine(t);
        }
        return out;
    }

    private static void accumulate(Contents out, Object content) {
        if (content == null) {
            return;
        }
        try {
            if (content instanceof ItemStack stack) {
                if (stack.isEmpty()) {
                    return;
                }
                out.counts.merge("item:" + idOf(ForgeRegistries.ITEMS.getKey(stack.getItem())),
                        Long.valueOf(stack.getCount()), Long::sum);
                return;
            }
            if (content instanceof com.lowdragmc.lowdraglib.side.fluid.FluidStack fs) {
                if (fs.isEmpty()) {
                    return;
                }
                out.counts.merge("fluid:" + idOf(ForgeRegistries.FLUIDS.getKey(fs.getFluid())),
                        Long.valueOf(fs.getAmount()), Long::sum);
                return;
            }
            if (content instanceof net.minecraftforge.fluids.FluidStack fs) {
                if (fs.isEmpty()) {
                    return;
                }
                out.counts.merge("fluid:" + idOf(ForgeRegistries.FLUIDS.getKey(fs.getFluid())),
                        Long.valueOf(fs.getAmount()), Long::sum);
                return;
            }
        } catch (Throwable t) {
            // 落到下面那句"认不出"
        }
        // 🔴 认不出：这一个条目就让整份读数降级（宁可写 unknown，也不许拿不完整的和去比）
        out.complete = false;
        out.unparsed++;
    }

    // ══════════════════════════════════ 小工具 ══════════════════════════════════

    private static String yesNo(boolean b) {
        return b ? "是" : "否";
    }

    private static String typeId(GTRecipeType type) {
        if (type == null) {
            return null;
        }
        try {
            return type.registryName == null ? "?" : String.valueOf(type.registryName);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String recipeId(GTRecipe recipe) {
        if (recipe == null) {
            return null;
        }
        try {
            return recipe.id == null ? "?" : String.valueOf(recipe.id);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String keyId(appeng.api.stacks.AEKey key) {
        if (key == null) {
            return "?";
        }
        try {
            return String.valueOf(key.getId());
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String idOf(ResourceLocation id) {
        return id == null ? "?" : String.valueOf(id);
    }

    private static String simpleName(Object o) {
        if (o == null) {
            return "?";
        }
        try {
            final String n = o.getClass().getSimpleName();
            return n == null || n.isEmpty() ? o.getClass().getName() : n;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String definitionId(MultiblockControllerMachine machine) {
        if (machine == null) {
            return "?";
        }
        try {
            final var def = machine.getDefinition();
            if (def == null || def.getId() == null) {
                return "?";
            }
            return String.valueOf(def.getId());
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String safeName(CommandSourceStack source) {
        try {
            return source.getTextName();
        } catch (Throwable t) {
            return "?";
        }
    }
}
