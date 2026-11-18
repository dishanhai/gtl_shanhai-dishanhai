package com.dishanhai.gt_shanhai.common.saveedit.audit;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditFlow;
import com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditReopenGate;
import com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditStorage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Path;
import java.util.List;

/**
 * {@code /shanhai saveedit audit …} —— 「双份登记 · 存档体检」的<b>调试/无图形环境入口</b>。
 *
 * <h2>一、命令树</h2>
 * <pre>
 *   /shanhai saveedit audit                      只读：扫一遍，清单打进日志 + 玩家聊天（每级最多 50 条）
 *   /shanhai saveedit audit drop-entity  &lt;uuid&gt;  删"区块文件里"那一份（要停服窗口）
 *   /shanhai saveedit audit drop-sidecar &lt;uuid&gt;  删"纯文本旁挂文件"里含该 UUID/坐标的整行（要停服窗口）
 * </pre>
 * 🔴 <b>面板才是主入口</b>：这三条命令是给"面板还没接""没图形界面（专服/控制台）"和"排查现场"用的。
 * 面板页要用的极简 API 在 {@link ShanhaiSaveEditAuditApi}（{@code scan / findings / dropEntity / dropSidecar}）。
 *
 * <h2>二、为什么本类自己注册一个 {@code saveedit} 节点</h2>
 * 与 {@code ShanhaiSaveEditCommand} / {@code ShanhaiItemOverrideCommand} 同一个写法：
 * 另注册一个同名 {@code literal("shanhai")}，Brigadier 会把同名子节点<b>合并</b>，
 * 既有命令（{@code open/read/selfcheck}）一个字节都不用动 —— 本轮的红线是"不许改既有 saveedit 类"。
 *
 * <h2>三、🔴 两个删除动作的窗口语义</h2>
 * 它们走 {@link ShanhaiSaveEditAuditFix#dropEntity} / {@link ShanhaiSaveEditAuditFix#dropSidecar}
 * （<b>带闸</b>）：先 {@link ShanhaiSaveEditFlow#tryApply(String)}，没放行就<b>零字节写入</b>并拒绝，
 * 日志里会留下 {@code window_closed=true … bytes_written=0}。
 * 面板没接的这段时间，游戏里敲这两条命令<b>会</b>得到这个拒绝 —— 这是设计如此，不是坏了
 * （窗口只由停服流程打开，而停服流程属于面板那条线）。
 */
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
                .requires(com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditCommand::isAllowed)
                .executes(ShanhaiSaveEditAuditCommand::audit)
                .then(Commands.literal("drop-entity")
                        .then(Commands.argument("uuid", StringArgumentType.word())
                                .executes(ShanhaiSaveEditAuditCommand::dropEntity)))
                .then(Commands.literal("drop-sidecar")
                        .then(Commands.argument("uuid", StringArgumentType.word())
                                .executes(ShanhaiSaveEditAuditCommand::dropSidecar)));
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
        GTDishanhaiMod.LOGGER.info("{} audit_command_registered /shanhai saveedit {} present={} children=[{}]",
                PREFIX, AUDIT_ARG, audit != null, kids.toString().trim());
    }

    // ------------------------------------------------------------------ 只读：清单

    private static int audit(CommandContext<CommandSourceStack> ctx) {
        final Path world = worldDir(ctx.getSource());
        if (world == null) {
            return denied(ctx, com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.e01e77bcc5d05e02"));
        }
        log("audit_start world=" + world);
        final ShanhaiSaveEditAuditScan.Report report;
        try {
            report = ShanhaiSaveEditAuditScan.scan(world);
        } catch (Throwable t) {
            log("audit_failed err=" + t);
            return denied(ctx, com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.cac9f556851818f0") + t);
        }
        log(report.summaryLine());
        final List<String> logLines = report.logLines(MAX_LINES);
        for (String line : logLines) {
            log(line);
        }
        chat(ctx.getSource(), com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.d24559f96f0b242c") + report.panelSummary());
        for (String line : report.chatLines(MAX_LINES)) {
            chat(ctx.getSource(), line);
        }
        if (report.empty()) {
            chat(ctx.getSource(), com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.4db64851987ef09f"));
        }
        if (report.binarySidecars() > 0) {
            chat(ctx.getSource(), com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.95e51967fdbf8bca") + report.binarySidecars()
                    + com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.a745fa657b50f820"));
        }
        return 1;
    }

    // ------------------------------------------------------------------ 修改：删区块里那份

    private static int dropEntity(CommandContext<CommandSourceStack> ctx) {
        final Path world = worldDir(ctx.getSource());
        if (world == null) {
            return denied(ctx, com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.53753deffb92113d"));
        }
        final String uuid = StringArgumentType.getString(ctx, "uuid");
        final ShanhaiSaveEditFlow flow = ShanhaiSaveEditFlow.current();
        log("drop_requested action=drop-entity uuid=" + uuid + " window_open=" + windowOpen(flow)
                + " phase=" + (flow == null ? "IDLE" : flow.phase().name()));
        final ShanhaiSaveEditAuditFix.Result r;
        try {
            r = ShanhaiSaveEditAuditFix.dropEntity(world, uuid, flow, backupRoot(ctx.getSource()),
                    ShanhaiSaveEditStorage.stamp(System.currentTimeMillis()));
        } catch (Throwable t) {
            log("audit_drop_entity ok=false reason=exception err=" + t);
            return denied(ctx, com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.31026e30b6e7d2f1") + t);
        }
        log(r.logLine());
        chat(ctx.getSource(), r.shortMessage());
        return r.ok() ? 1 : 0;
    }

    // ------------------------------------------------------------------ 修改：删旁挂那行

    private static int dropSidecar(CommandContext<CommandSourceStack> ctx) {
        final Path world = worldDir(ctx.getSource());
        if (world == null) {
            return denied(ctx, com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.53753deffb92113d"));
        }
        final String uuid = StringArgumentType.getString(ctx, "uuid");
        final ShanhaiSaveEditFlow flow = ShanhaiSaveEditFlow.current();
        log("drop_requested action=drop-sidecar uuid=" + uuid + " window_open=" + windowOpen(flow)
                + " phase=" + (flow == null ? "IDLE" : flow.phase().name()));
        final ShanhaiSaveEditAuditFix.Result r;
        try {
            r = ShanhaiSaveEditAuditFix.dropSidecar(world, uuid, flow, backupRoot(ctx.getSource()),
                    ShanhaiSaveEditStorage.stamp(System.currentTimeMillis()));
        } catch (Throwable t) {
            log("audit_drop_sidecar ok=false reason=exception err=" + t);
            return denied(ctx, com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.bba196fad2e963b5") + t);
        }
        log(r.logLine());
        chat(ctx.getSource(), r.shortMessage());
        if (ShanhaiSaveEditAuditFix.REASON_BINARY.equals(r.reason())) {
            chat(ctx.getSource(), com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.15ed569cfa07a276"));
        }
        return r.ok() ? 1 : 0;
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

    /** 备份根目录（用户口径：{@code temp\saveedit-backup\<时间戳>\}，相对<b>游戏根目录</b>）。 */
    private static Path backupRoot(CommandSourceStack src) {
        try {
            final MinecraftServer server = src.getServer();
            if (server == null) {
                return null;
            }
            return server.getServerDirectory().toPath().resolve("temp").resolve("saveedit-backup");
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean windowOpen(ShanhaiSaveEditFlow flow) {
        return flow != null && ShanhaiSaveEditFlow.windowOpen(flow.phase());
    }

    private static void log(String line) {
        GTDishanhaiMod.LOGGER.info("{} {}", PREFIX, line);
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
