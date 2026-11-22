package com.shanhai.common.saveedit.audit;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.saveedit.ShanhaiSaveEditFlow;
import com.shanhai.common.saveedit.ShanhaiSaveEditReopenGate;
import com.shanhai.common.saveedit.ShanhaiSaveEditStorage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Path;

/**
 * {@code /shanhai saveedit audit …} —— 「存档体检」（白名单之外一律算异常）的<b>无头入口</b>。
 *
 * <h2>一、命令树（用户 2026-11-15 口径）</h2>
 * <pre>
 *   /shanhai saveedit audit                    只读：扫一遍并列出（= list）
 *   /shanhai saveedit audit list               只读：列出白名单之外的全部条目（每级最多 50 条）
 *   /shanhai saveedit audit drop &lt;序号或相对路径&gt; 删一条（要停服窗口；目录递归删；删前进回收目录）
 *   /shanhai saveedit audit selfcheck           纯逻辑自检（不读盘、不写盘）
 * </pre>
 * 🔴 <b>面板才是主入口</b>：这几条命令是给"没图形界面（专服/控制台）""离线判据"和"排查现场"用的。
 *
 * <h2>二、🔴 这些命令<b>不走模块门槛</b></h2>
 * 面板那条 {@code /shanhai saveedit open} 要求玩家身上带着「创始现实修改模块」；
 * 体检这几条<b>不</b>查那个（用户明确要求：
 * 「⚠️ 这些命令不要走模块门槛」）—— 只要求权限等级 2，控制台也能跑。
 *
 * <h2>三、🔴 删除的窗口语义</h2>
 * {@code drop} 走 {@link ShanhaiSaveEditAuditFix#drop}（<b>带闸</b>）：
 * 先 {@link ShanhaiSaveEditFlow#tryApply(String)}，没放行就<b>零字节变更</b>并拒绝，
 * 日志里会留下 {@code window_closed=true … deleted_files=0}。
 * 窗口只由停服流程打开（面板「确认修改并重载」那一条），所以真删仍走同一个停服窗口。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiSaveEditAuditCommand {

    /** 日志前缀（沿用存档直改那条）。 */
    public static final String PREFIX = ShanhaiSaveEditFlow.PREFIX;

    /** 子命令名：{@code /shanhai saveedit audit}。 */
    public static final String AUDIT_ARG = "audit";

    /** 清单一次最多打多少条（超出用 {@code ...共 N 条} 收尾）。 */
    public static final int MAX_LINES = 50;

    private ShanhaiSaveEditAuditCommand() {}

    /** 命令分支（挂在既有的 {@code /shanhai saveedit} 下面）。 */
    public static LiteralArgumentBuilder<CommandSourceStack> commandBranch() {
        return Commands.literal(AUDIT_ARG)
                .requires(src -> src.hasPermission(2))
                .executes(ShanhaiSaveEditAuditCommand::list)
                .then(Commands.literal("list").executes(ShanhaiSaveEditAuditCommand::list))
                .then(Commands.literal("drop")
                        .then(Commands.argument("target", StringArgumentType.greedyString())
                                .executes(ShanhaiSaveEditAuditCommand::drop)))
                .then(Commands.literal("selfcheck").executes(ShanhaiSaveEditAuditCommand::selfcheck));
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("shanhai")
                .then(Commands.literal("saveedit").then(commandBranch())));
        // 机器可判：回读命令树，确认真挂上（同 ShanhaiSaveEditCommand / ShanhaiItemOverrideCommand 的做法）
        final var shanhai = event.getDispatcher().getRoot().getChild("shanhai");
        final var saveedit = shanhai == null ? null : shanhai.getChild("saveedit");
        final var audit = saveedit == null ? null : saveedit.getChild(AUDIT_ARG);
        final StringBuilder kids = new StringBuilder();
        if (audit != null) {
            for (var c : audit.getChildren()) {
                kids.append(c.getName()).append(' ');
            }
        }
        ShanhaiMod.LOGGER.info("{} audit_command_registered /shanhai saveedit {} present={} children=[{}] "
                        + "module_gate=none",
                PREFIX, AUDIT_ARG, audit != null, kids.toString().trim());
    }

    // ------------------------------------------------------------------ 只读：列出

    private static int list(CommandContext<CommandSourceStack> ctx) {
        final Path world = worldDir(ctx.getSource());
        if (world == null) {
            return denied(ctx, "拿不到存档目录（不是专用服/没进世界？）");
        }
        log("audit_start world=" + world);
        final ShanhaiSaveEditAuditScan.Report report;
        try {
            report = ShanhaiSaveEditAuditScan.scan(world);
        } catch (Throwable t) {
            log("audit_failed err=" + t);
            return denied(ctx, "扫描时抛了（看日志 audit_failed）：" + t);
        }
        log(report.summaryLine());
        for (String line : report.logLines(MAX_LINES)) {
            log(line);
        }
        chat(ctx.getSource(), "§7[存档体检] " + report.panelSummary());
        for (String line : report.chatLines(MAX_LINES)) {
            chat(ctx.getSource(), line);
        }
        if (report.empty()) {
            chat(ctx.getSource(), "§a白名单之外没有任何异常数据（存档是干净的原版布局）");
        } else {
            chat(ctx.getSource(), "§8删一条：§f/shanhai saveedit audit drop <序号或相对路径>"
                    + "§8（要停服窗口；删前会复制到 config\\shanhai\\audit-trash\\）");
        }
        if (report.stats().truncated()) {
            chat(ctx.getSource(), "§e条目太多，已截断（只列了 " + report.size() + " 条）");
        }
        return 1;
    }

    // ------------------------------------------------------------------ 修改：删一条

    private static int drop(CommandContext<CommandSourceStack> ctx) {
        final Path world = worldDir(ctx.getSource());
        if (world == null) {
            return denied(ctx, "拿不到存档目录");
        }
        final String target = StringArgumentType.getString(ctx, "target");
        final ShanhaiSaveEditFlow flow = ShanhaiSaveEditFlow.current();
        final String rel = resolveRel(world, target);
        log("drop_requested target=" + target + " rel=" + rel + " window_open=" + windowOpen(flow)
                + " phase=" + (flow == null ? "IDLE" : flow.phase().name()));
        final ShanhaiSaveEditAuditFix.Result r;
        try {
            r = ShanhaiSaveEditAuditFix.drop(world, rel, flow,
                    ShanhaiSaveEditStorage.stamp(System.currentTimeMillis()));
        } catch (Throwable t) {
            log("audit_drop ok=false reason=exception err=" + t);
            return denied(ctx, "删除时抛了（看日志）：" + t);
        }
        log(r.logLine());
        chat(ctx.getSource(), r.shortMessage());
        if (ShanhaiSaveEditAuditFix.REASON_WINDOW_CLOSED.equals(r.reason())) {
            chat(ctx.getSource(), "§8窗口只在「确认修改并重载」之后的停服窗口里打开；"
                    + "面板第 ⑦ 页点「删除选中项」+「确认修改并重载」走的就是那个窗口");
        }
        return r.ok() ? 1 : 0;
    }

    /** 全部是数字 ⇒ 当序号（1 起，指本次扫描的清单）；否则当相对路径。 */
    private static String resolveRel(Path world, String target) {
        final String t = target == null ? "" : target.trim();
        if (t.matches("\\d{1,9}")) {
            final int index = Integer.parseInt(t);
            final ShanhaiSaveEditAuditScan.Report report = ShanhaiSaveEditAuditScan.scan(world);
            final ShanhaiSaveEditAuditScan.Item item = report.item(index - 1);
            if (item != null) {
                return item.rel();
            }
            log("audit_drop_index_out_of_range index=" + index + " size=" + report.size());
            return t;                                   // 落到下面当相对路径，会如实报"不合法/不存在"
        }
        return t;
    }

    // ------------------------------------------------------------------ 纯逻辑自检

    private static int selfcheck(CommandContext<CommandSourceStack> ctx) {
        final StringBuilder sb = new StringBuilder();
        boolean pass = true;

        // ① 请求校验：好路径放行；空 / 绝对 / 含 .. 必须拒
        boolean reqOk = false;
        try {
            new ShanhaiSaveEditAuditFix.Request("examplemod/sidecar.bin").validate();
            reqOk = true;
        } catch (IllegalArgumentException bad) {
            reqOk = false;
        }
        final boolean reqRejects = rejects("") && rejects("../escape.bin")
                && rejects("C:/Windows/system32/x.dll") && rejects("/etc/passwd");
        pass &= reqOk && reqRejects;
        sb.append("audit_req_ok=").append(reqOk).append(" audit_req_reject=").append(reqRejects);

        // ② 白名单判据：正对照（原版布局全放行）+ 负对照（白名单外全拒绝）
        final String[] allow = {
                "level.dat", "level.dat_old", "session.lock", "icon.png",
                "region/r.0.0.mca", "entities/r.0.0.mca", "poi/r.0.0.mca", "data/raids.dat",
                "datapacks/vanilla.zip", "playerdata/00000000-0000-0000-0000-000000000000.dat",
                "advancements/x.json", "stats/x.json", "server-resources/x/data.bin",
                "DIM-1/region/r.0.0.mca", "DIM1/entities/r.0.0.mca",
                "dimensions/kubejs/void/region/r.0.0.mca",
                "dimensions/kubejs/void/entities/r.0.0.mca",
                "dimensions/kubejs/void/data/x.dat"};
        final String[] deny = {
                "examplemod/sidecar.bin", "somemod/data.bin", "weird_dir",
                "weird_dir/a.bin", "DIM-1/foo.bin", "DIM1/nested/thing.bin",
                "dimensions/kubejs/void/x.bin", "dimensions/kubejs/loose.bin", "dimensions/loose.bin",
                "level.dat.bak", "region_backup/r.0.0.mca"};
        final StringBuilder whitelistBad = new StringBuilder();
        for (String rel : allow) {
            if (!ShanhaiSaveEditAuditScan.whitelisted(rel)) {
                whitelistBad.append(" allow拒了:").append(rel);
            }
        }
        for (String rel : deny) {
            if (ShanhaiSaveEditAuditScan.whitelisted(rel)) {
                whitelistBad.append(" deny放了:").append(rel);
            }
        }
        final boolean whitelistOk = whitelistBad.length() == 0;
        pass &= whitelistOk;
        sb.append(" whitelist=").append(allow.length).append("/").append(deny.length)
                .append(" ok=").append(whitelistOk).append(whitelistBad);

        // ③ 窗口负对照：没有 flow ⇒ 必须拒绝，而且零字节变更
        final ShanhaiSaveEditAuditFix.Result noWindow = ShanhaiSaveEditAuditFix.drop(
                java.nio.file.Paths.get("C:/shanhai-selfcheck-world"), "weird_dir/a.bin", null, "selfcheck");
        final boolean windowRefused = !noWindow.ok() && noWindow.windowClosed()
                && ShanhaiSaveEditAuditFix.REASON_WINDOW_CLOSED.equals(noWindow.reason())
                && noWindow.deletedFiles() == 0 && noWindow.deletedBytes() == 0L;
        pass &= windowRefused;
        sb.append(" window_refused=").append(windowRefused);

        // ④ 白名单负对照：删白名单内的一条必须被拒（这条不需要窗口，闸在白名单那一层之后）
        final ShanhaiSaveEditAuditFix.Result white = ShanhaiSaveEditAuditFix.dropInWindow(
                java.nio.file.Paths.get("C:/shanhai-selfcheck-world"), "region/r.0.0.mca", "selfcheck");
        final boolean whitelistRefused = !white.ok()
                && ShanhaiSaveEditAuditFix.REASON_WHITELISTED.equals(white.reason())
                && white.deletedFiles() == 0;
        pass &= whitelistRefused;
        sb.append(" whitelist_refused=").append(whitelistRefused);

        // ⑤ 回收目录：必须在 <游戏根>/config/shanhai/audit-trash/<存档名>-<时间戳>，且不在存档里
        final Path clientRoot = ShanhaiSaveEditAuditFix.trashRoot(
                java.nio.file.Paths.get("C:/game/saves/MyWorld"), "20261115-120000-000");
        final Path serverRoot = ShanhaiSaveEditAuditFix.trashRoot(
                java.nio.file.Paths.get("C:/server/world"), "20261115-120000-000");
        final boolean trashOk = clientRoot.toString().replace('\\', '/')
                .equals("C:/game/config/shanhai/audit-trash/MyWorld-20261115-120000-000")
                && serverRoot.toString().replace('\\', '/')
                .equals("C:/server/config/shanhai/audit-trash/world-20261115-120000-000");
        pass &= trashOk;
        sb.append(" trash=").append(clientRoot.toString().replace('\\', '/')).append(" ok=").append(trashOk);

        final String line = "audit_selfcheck PASS=" + pass + " | " + sb;
        // pass 在上面被 `&=` 反复累加 ⇒ 不是 effectively final，lambda 里必须用这份副本
        final boolean passFinal = pass;
        ctx.getSource().sendSuccess(() -> Component.literal((passFinal ? "§a" : "§c") + line), false);
        ShanhaiMod.LOGGER.info("{} {}", PREFIX, line);
        return pass ? 1 : 0;
    }

    /** 这个请求写法必须被拒。 */
    private static boolean rejects(String rel) {
        try {
            new ShanhaiSaveEditAuditFix.Request(rel).validate();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    // ------------------------------------------------------------------ 路径 / 反馈

    /**
     * 存档根目录（🔴 与面板同一个闸：{@code LevelResource.ROOT} 的尾段是 {@code "."}，
     * 必须过 {@code ShanhaiSaveEditReopenGate.resolve} 才是真的存档目录）。
     */
    private static Path worldDir(CommandSourceStack src) {
        try {
            final MinecraftServer server = src.getServer();
            if (server == null) {
                return null;
            }
            final Path root = server.getWorldPath(LevelResource.ROOT);
            if (root == null) {
                return null;
            }
            final ShanhaiSaveEditReopenGate.Resolution res = ShanhaiSaveEditReopenGate.resolve(root);
            return res.worldDir != null ? res.worldDir : root.toAbsolutePath().normalize();
        } catch (Throwable t) {
            log("world_dir_unresolved err=" + t);
            return null;
        }
    }

    private static boolean windowOpen(ShanhaiSaveEditFlow flow) {
        return flow != null && ShanhaiSaveEditFlow.windowOpen(flow.phase());
    }

    private static void log(String line) {
        ShanhaiMod.LOGGER.info("{} {}", PREFIX, line);
    }

    private static void chat(CommandSourceStack src, String line) {
        src.sendSuccess(() -> Component.literal(line), false);
    }

    private static int denied(CommandContext<CommandSourceStack> ctx, String why) {
        ctx.getSource().sendFailure(Component.literal("§c" + why));
        return 0;
    }

    @Override
    public String toString() {
        return "ShanhaiSaveEditAuditCommand[arg=" + AUDIT_ARG + "]";
    }
}
