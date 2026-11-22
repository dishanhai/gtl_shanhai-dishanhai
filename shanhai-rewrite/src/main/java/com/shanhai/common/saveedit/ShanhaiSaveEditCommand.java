package com.shanhai.common.saveedit;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.holo.ShanhaiHoloMenuNetwork;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * {@code /shanhai saveedit} —— 存档直改的<b>唯一入口</b>（与 {@code ShanhaiGuardCommand} /
 * {@code ShanhaiCreativeItemsCommand} 同一种写法：另注册一个同名 {@code literal("shanhai")}，
 * Brigadier 会把同名子节点合并，既有命令的 children 一个都不会丢）。
 *
 * <h2>命令树</h2>
 * <pre>
 *   /shanhai saveedit            开面板（要玩家 + 身上带着「创始现实修改模块」）
 *   /shanhai saveedit open       同上（显式写法，录像/排查用）
 *   /shanhai saveedit read       只读：把 playerdata 摘要打到聊天栏与日志（不开界面）
 *   /shanhai saveedit selfcheck  纯逻辑自检（窗口真值表；控制台可跑）
 * </pre>
 *
 * <h2>🔴 门槛（与既有命令同口径）</h2>
 * <b>玩家</b>执行时必须身上带着「创始现实修改模块」（{@link ShanhaiHoloMenuNetwork#moduleInInventory}
 * —— 与 {@code /shanhai items}／全息命令输入框同一个纯函数口径）；
 * <b>控制台/命令方块不受限</b>（那是运维通道 —— 但开面板那条仍然要求是玩家，因为面板要有界面）。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiSaveEditCommand {

    public static final String PREFIX = ShanhaiSaveEditFlow.PREFIX;

    /** 子命令名。 */
    public static final String COMMAND_ARG = "saveedit";

    private ShanhaiSaveEditCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> commandBranch() {
        return Commands.literal(COMMAND_ARG)
                .executes(ShanhaiSaveEditCommand::openPanel)
                .then(Commands.literal("open").executes(ShanhaiSaveEditCommand::openPanel))
                .then(Commands.literal("read").executes(ShanhaiSaveEditCommand::read))
                .then(Commands.literal("selfcheck").executes(ShanhaiSaveEditCommand::selfcheck));
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("shanhai").then(commandBranch()));
        // 机器可判：回读命令树，没挂上就是 false（照 ShanhaiGuardCommand 的做法）
        final var shanhaiNode = event.getDispatcher().getRoot().getChild("shanhai");
        final boolean present = shanhaiNode != null && shanhaiNode.getChild(COMMAND_ARG) != null;
        ShanhaiMod.LOGGER.info("{} command_registered /shanhai {} present={}",
                PREFIX, COMMAND_ARG, present);
    }

    // ------------------------------------------------------------------ 子命令

    /** 开面板（必须玩家 + 带模块）。 */
    private static int openPanel(CommandContext<CommandSourceStack> ctx) {
        final ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这条子命令要由玩家执行（控制台没有界面）"));
            return 0;
        }
        final String denied = gate(player);
        if (denied != null) {
            ctx.getSource().sendFailure(Component.literal(denied));
            ShanhaiMod.LOGGER.info("{} cmd_rejected reason=no_module player={}",
                    PREFIX, player.getName().getString());
            return 0;
        }
        final ShanhaiSaveEditRunContext probe = ShanhaiSaveEditFactory.capture(player);
        if (probe == null) {
            ctx.getSource().sendFailure(Component.literal("§c拿不到存档路径（看日志里的 capture_failed）"));
            return 0;
        }
        ShanhaiMod.LOGGER.info("{} cmd_open {}", PREFIX, probe.line());
        final boolean ok = ShanhaiSaveEditFactory.open(player);
        if (!ok) {
            ctx.getSource().sendFailure(Component.literal("§c面板没打开成功（看日志里的 openUI ok=false）"));
        }
        return ok ? 1 : 0;
    }

    /** 只读摘要（不开界面；玩家与日志都能看）。 */
    private static int read(CommandContext<CommandSourceStack> ctx) {
        final ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c这条子命令要由玩家执行（要靠你的 uuid 找 playerdata）"));
            return 0;
        }
        final ShanhaiSaveEditRunContext probe = ShanhaiSaveEditFactory.capture(player);
        if (probe == null) {
            ctx.getSource().sendFailure(Component.literal("§c拿不到存档路径"));
            return 0;
        }
        final ShanhaiPlayerDataSnapshot snap = ShanhaiPlayerDataEditor.summarize(probe.playerDataFile);
        ctx.getSource().sendSuccess(() -> Component.literal(snap.line1()), false);
        ctx.getSource().sendSuccess(() -> Component.literal(snap.line2()), false);
        ctx.getSource().sendSuccess(() -> Component.literal(snap.line3()), false);
        ShanhaiMod.LOGGER.info("{} cmd_read present={} {}", PREFIX, snap.present, snap.joined());
        return snap.present ? 1 : 0;
    }

    /**
     * 纯逻辑自检：窗口判据的真值表 + 一次完整的四步驱动 + 三段负对照。
     *
     * <p>🔴 负对照是必需的：没有它，一个"永远拒绝"的假实现也能全绿。
     */
    private static int selfcheck(CommandContext<CommandSourceStack> ctx) {
        final StringBuilder sb = new StringBuilder();
        boolean pass = true;

        // ① 真值表：只有 STOPPED 是窗口
        int admit = 0;
        for (ShanhaiSaveEditFlow.Phase p : ShanhaiSaveEditFlow.Phase.values()) {
            final boolean open = ShanhaiSaveEditFlow.windowOpen(p);
            if (open) {
                admit++;
                if (p != ShanhaiSaveEditFlow.Phase.STOPPED) {
                    pass = false;
                }
            }
        }
        if (admit != 1) {
            pass = false;
        }
        sb.append("window_true_for=").append(admit).append("/")
                .append(ShanhaiSaveEditFlow.Phase.values().length);

        // ② 正面对照：drive 一次完整四步
        ShanhaiSaveEditFlow.resetGlobalForProbe();
        final ShanhaiSaveEditFlow flow = ShanhaiSaveEditFlow.begin("selfcheck");
        boolean okFlow = flow != null;
        if (flow != null) {
            okFlow &= !flow.tryApply("early").permitted();          // 存前不许
            okFlow &= flow.markSaved();
            okFlow &= !flow.tryApply("saved").permitted();          // 停服前不许
            okFlow &= flow.markStopping();
            okFlow &= flow.markStopped();
            okFlow &= flow.tryApply("window").permitted();          // 窗口内许
            okFlow &= flow.markApplied();
            okFlow &= flow.markReopening();
            okFlow &= flow.markDone();
            okFlow &= !flow.tryApply("after").permitted();          // 重开后不许
        }
        final ShanhaiSaveEditFlow.Criterion1 c1 = ShanhaiSaveEditFlow.criterion1(
                flow == null ? java.util.List.of() : flow.attempts(),
                ShanhaiSaveEditFlow.concurrentAcceptedCount(),
                ShanhaiSaveEditFlow.concurrentRejectedCount());
        okFlow &= c1.pass();
        sb.append(" ok_drive=").append(okFlow).append(" c1=").append(c1.pass());
        pass &= okFlow;

        // ③ 负对照：并发两次（第二次必须被拒）
        ShanhaiSaveEditFlow.resetGlobalForProbe();
        final ShanhaiSaveEditFlow a = ShanhaiSaveEditFlow.begin("conc-a");
        final ShanhaiSaveEditFlow b = ShanhaiSaveEditFlow.begin("conc-b");
        final boolean mutex = a != null && b == null;
        if (a != null) {
            a.release();
        }
        sb.append(" mutex=").append(mutex);
        pass &= mutex;
        ShanhaiSaveEditFlow.resetGlobalForProbe();

        // ④ 编辑模型：空串 = 不改；非法值必须报错
        final java.util.Map<String, String> empty = new java.util.LinkedHashMap<>();
        empty.put("health", "");
        empty.put("x", "");
        final ShanhaiPlayerDataEdit e0 = ShanhaiPlayerDataEdit.parse(empty);
        final boolean emptyOk = e0.isEmpty() && e0.errors.isEmpty();
        final java.util.Map<String, String> bad = new java.util.LinkedHashMap<>();
        bad.put("health", "abc");
        bad.put("gamemode", "9");
        final ShanhaiPlayerDataEdit e1 = ShanhaiPlayerDataEdit.parse(bad);
        final boolean badRejected = !e1.errors.isEmpty() && e1.health == null && e1.gameMode == null;
        sb.append(" empty_noop=").append(emptyOk).append(" bad_rejected=").append(badRejected);
        pass &= emptyOk && badRejected;

        // ⑤ 二期：level.dat 白名单 + 拒绝理由（纯逻辑，不读盘）
        final java.util.Map<String, String> lv = new java.util.LinkedHashMap<>();
        lv.put("Data.DayTime", "6000");
        final ShanhaiLevelDataEdit lvOk = ShanhaiLevelDataEdit.parse(lv);
        final java.util.Map<String, String> lvBad = new java.util.LinkedHashMap<>();
        lvBad.put("fml.LoadingModList[0].ModVersion", "9.9.9");
        final ShanhaiLevelDataEdit lvRej = ShanhaiLevelDataEdit.parse(lvBad);
        final java.util.Map<String, String> lvRange = new java.util.LinkedHashMap<>();
        lvRange.put("Data.Difficulty", "9");
        final ShanhaiLevelDataEdit lvOut = ShanhaiLevelDataEdit.parse(lvRange);
        final boolean lvWhy = ShanhaiLevelDataSpec.FIELDS.stream()
                .allMatch(f -> f.why().contains("读") && f.why().contains("写回"));
        final boolean lvPass = ShanhaiLevelDataSpec.FIELDS.size() == 21 && lvWhy
                && lvOk.values.size() == 1 && lvOk.errors.isEmpty()
                && !lvRej.errors.isEmpty() && lvRej.values.isEmpty()
                && lvRej.errors.get(0).contains("Forge")
                && !lvOut.errors.isEmpty() && lvOut.values.isEmpty();
        sb.append(" level_fields=").append(ShanhaiLevelDataSpec.FIELDS.size())
                .append(" level_why_ok=").append(lvWhy)
                .append(" level_reject=").append(!lvRej.errors.isEmpty())
                .append(" level_range_reject=").append(!lvOut.errors.isEmpty());
        pass &= lvPass;

        // ⑥ 三期：维度路径 / Y 范围 / 方块状态串（纯逻辑，不读盘）
        final java.nio.file.Path probeWorld = java.nio.file.Paths.get("C:/shanhai-selfcheck");
        final boolean dims = ShanhaiRegionEdit.regionFile(probeWorld, "minecraft:overworld", 0, 0)
                .endsWith(java.nio.file.Paths.get("region", "r.0.0.mca"))
                && ShanhaiRegionEdit.regionFile(probeWorld, "minecraft:the_nether", 0, 0)
                .endsWith(java.nio.file.Paths.get("DIM-1", "region", "r.0.0.mca"))
                && ShanhaiRegionEdit.regionFile(probeWorld, "minecraft:the_end", 0, 0)
                .endsWith(java.nio.file.Paths.get("DIM1", "region", "r.0.0.mca"));
        final boolean yRange = java.util.Arrays.equals(
                ShanhaiRegionEdit.yRange(null, "minecraft:overworld"), new int[] {-64, 319})
                && java.util.Arrays.equals(
                ShanhaiRegionEdit.yRange(null, "minecraft:the_nether"), new int[] {0, 255});
        boolean stateOk = false;
        try {
            final ShanhaiChunkEditor.State st = ShanhaiRegionEdit.parseState("minecraft:oak_log", "axis=y");
            stateOk = st.name().equals("minecraft:oak_log") && "y".equals(st.props().get("axis"))
                    && st.toString().equals("minecraft:oak_log[axis=y]");
        } catch (Throwable ignored) {
            stateOk = false;
        }
        boolean stateBad = false;
        try {
            ShanhaiRegionEdit.parseState("Stone", "");
            stateBad = false;               // 居然没抛 ⇒ 这条判据失效
        } catch (IllegalArgumentException expected) {
            stateBad = true;
        }
        sb.append(" dims=").append(dims).append(" y_range=").append(yRange)
                .append(" state_parse=").append(stateOk).append(" state_reject=").append(stateBad);
        pass &= dims && yRange && stateOk && stateBad;

        // ⑦ 四期起：版式 / 10 条维度映射 / 新页的请求校验（纯逻辑，不读盘、不写盘）
        // 五期把页数从 6 改成 7（+ ⑦ 存档体检）：这里不再写死 6，改成"六页的老读数一个不差 + 第七页也有控件"
        // 五期续（第 ④ 页改版：按维度列表 → 点一行 → 覆盖层 + 翻页）：控件数 26 → 19 → 22
        // S2b（第 ⑤⑥ 页照第 ④ 页同构：列表 → 点一行 → 覆盖层 + 翻页）：18/22 → 19/19
        // S2d（第 ⑦ 页改语义：白名单之外一律算异常，7 行列表 + 翻页 + 搜索）：18 → 19
        final boolean layout6 = ShanhaiSaveEditLayout.PAGE_COUNT == 7
                && ShanhaiSaveEditLayout.pageWidgetCount(0) == 29
                && ShanhaiSaveEditLayout.pageWidgetCount(1) == 1 + ShanhaiLevelDataSpec.FIELDS.size() * 3
                && ShanhaiSaveEditLayout.pageWidgetCount(2) == 25
                && ShanhaiSaveEditLayout.pageWidgetCount(3) == 22
                && ShanhaiSaveEditLayout.pageWidgetCount(4) == 19
                && ShanhaiSaveEditLayout.pageWidgetCount(5) == 19
                && ShanhaiSaveEditLayout.pageWidgetCount(ShanhaiSaveEditLayout.PAGE_AUDIT) == 19
                && ShanhaiSaveEditLayout.auditSlots().size() == 19
                && ShanhaiSaveEditLayout.AU_ROWS == ShanhaiSaveEditLayout.NEW_LINES
                && ShanhaiSaveEditLayout.layoutProblems().isEmpty();
        final boolean dimTable = ShanhaiSaveEditDimension.MAPPING_SAMPLES.size() == 10
                && ShanhaiSaveEditDimension.normalize("ad_astra:moon") != null
                && ShanhaiSaveEditDimension.normalize("Not A Dim") == null
                && ShanhaiSaveEditDimension.entitiesFile(probeWorld, "minecraft:the_nether", -1, -1)
                .endsWith(java.nio.file.Paths.get("DIM-1", "entities", "r.-1.-1.mca"))
                && ShanhaiSaveEditDimension.entitiesFile(probeWorld, "kubejs:void", 0, -2)
                .endsWith(java.nio.file.Paths.get("dimensions", "kubejs", "void", "entities",
                        "r.0.-2.mca"));
        boolean entityReqOk = false;
        boolean advReqOk = false;
        boolean statsReqOk = false;
        boolean entityReqRejects = false;
        boolean advReqRejects = false;
        boolean statsReqRejects = false;
        try {
            new ShanhaiEntityEdit.Request("minecraft:the_nether", -1, -1, "minecraft:item", "").validate();
            entityReqOk = true;
        } catch (IllegalArgumentException entityBad) {
            entityReqOk = false;
        }
        try {
            new ShanhaiAdvancementEdit.Request(ShanhaiAdvancementEdit.ACTION_GRANT,
                    "minecraft:story/mine_diamond", java.util.List.of("mine_diamond")).validate();
            advReqOk = true;
        } catch (IllegalArgumentException advBad) {
            advReqOk = false;
        }
        try {
            new ShanhaiStatsEdit.Request("", "minecraft:jump", ShanhaiStatsEdit.ACTION_SET, 1L).validate();
            statsReqOk = true;
        } catch (IllegalArgumentException statsBad) {
            statsReqOk = false;
        }
        try {
            new ShanhaiEntityEdit.Request("minecraft:overworld", 0, 0, "", "").validate();
        } catch (IllegalArgumentException expected) {
            entityReqRejects = true;
        }
        try {
            new ShanhaiAdvancementEdit.Request(ShanhaiAdvancementEdit.ACTION_GRANT, "Not An Id",
                    java.util.List.of("a")).validate();
        } catch (IllegalArgumentException expected) {
            advReqRejects = true;
        }
        try {
            new ShanhaiStatsEdit.Request("", "Not A Key", ShanhaiStatsEdit.ACTION_SET, 1L).validate();
        } catch (IllegalArgumentException expected) {
            statsReqRejects = true;
        }
        // S2d：第 ⑦ 页（存档体检）的请求校验 —— 好路径放行、绝对路径/含 ../空请求必须拒
        boolean auditReqOk = false;
        boolean auditReqRejects = false;
        try {
            new com.shanhai.common.saveedit.audit.ShanhaiSaveEditAuditFix.Request(
                    "examplemod/sidecar.bin").validate();
            auditReqOk = true;
        } catch (IllegalArgumentException auditBad) {
            auditReqOk = false;
        }
        try {
            new com.shanhai.common.saveedit.audit.ShanhaiSaveEditAuditFix.Request("../escape.bin").validate();
        } catch (IllegalArgumentException expected) {
            auditReqRejects = true;
        }
        // 白名单判据的正/负对照（纯函数，不读盘）
        final boolean auditWhitelisted = com.shanhai.common.saveedit.audit.ShanhaiSaveEditAuditScan
                .whitelisted("region/r.0.0.mca")
                && com.shanhai.common.saveedit.audit.ShanhaiSaveEditAuditScan.whitelisted("level.dat")
                && com.shanhai.common.saveedit.audit.ShanhaiSaveEditAuditScan
                .whitelisted("DIM-1/entities/r.0.0.mca")
                && com.shanhai.common.saveedit.audit.ShanhaiSaveEditAuditScan
                .whitelisted("dimensions/kubejs/void/region/r.0.0.mca")
                && !com.shanhai.common.saveedit.audit.ShanhaiSaveEditAuditScan
                .whitelisted("examplemod/sidecar.bin")
                && !com.shanhai.common.saveedit.audit.ShanhaiSaveEditAuditScan.whitelisted("weird_dir");
        sb.append(" layout6=").append(layout6).append(" dim_table=").append(dimTable)
                .append(" new_req_ok=").append(entityReqOk && advReqOk && statsReqOk)
                .append(" new_req_reject=").append(entityReqRejects && advReqRejects && statsReqRejects)
                .append(" audit_req_ok=").append(auditReqOk)
                .append(" audit_req_reject=").append(auditReqRejects)
                .append(" audit_whitelist=").append(auditWhitelisted);
        pass &= layout6 && dimTable && entityReqOk && advReqOk && statsReqOk
                && entityReqRejects && advReqRejects && statsReqRejects
                && auditReqOk && auditReqRejects && auditWhitelisted;

        // ⑧ S2c：第 ① 页「物品栏 41 格 + 末影箱」覆盖层（纯逻辑：格号↔槽位 / 四段 / 数量 / 名字回退）
        //    题面点名的三处同步：页① 的控件数（1 换 1，仍是 29，见 layout6）、槽位表
        //    （ShanhaiSaveEditLayout.inventoryOverlaySlots + inventoryCellRects）、这里的数字。
        final int invCells = ShanhaiSaveEditLayout.inventoryCellRects(false).size();
        final int enderCells = ShanhaiSaveEditLayout.inventoryCellRects(true).size();
        final int invSlots = ShanhaiSaveEditLayout.inventoryOverlaySlots().size();
        final int pickSlots = ShanhaiSaveEditLayout.inventoryPickerSlots().size();
        final int pickCells = ShanhaiSaveEditLayout.pickerCellRects().size();
        boolean segmentOk = ShanhaiSaveEditInventoryUi.SEGMENTS.length == 4;
        final StringBuilder segRead = new StringBuilder();
        for (ShanhaiSaveEditInventoryUi.Segment seg : ShanhaiSaveEditInventoryUi.SEGMENTS) {
            segRead.append(seg.key()).append('=').append(seg.count()).append(' ');
        }
        segmentOk &= ShanhaiSaveEditInventoryUi.SEGMENTS[0].count() == 27
                && ShanhaiSaveEditInventoryUi.SEGMENTS[1].count() == 9
                && ShanhaiSaveEditInventoryUi.SEGMENTS[2].count() == 4
                && ShanhaiSaveEditInventoryUi.SEGMENTS[3].count() == 1;
        // 格号 → 槽位：41 格里每个槽号<b>恰好出现一次</b>，副手那格是 -106（不是 150）
        final boolean[] seen = new boolean[ShanhaiPlayerDataEdit.INVENTORY_GRID_SLOTS];
        boolean mapOk = invCells == 41 && enderCells == 27;
        for (int i = 0; i < ShanhaiPlayerDataEdit.INVENTORY_GRID_SLOTS; i++) {
            final int slot = ShanhaiSaveEditInventoryUi.slotAt(false, i);
            final int back = ShanhaiSaveEditInventoryUi.indexOf(false, slot);
            mapOk &= slot == ShanhaiPlayerDataEdit.inventorySlotAt(i) && back == i && !seen[i];
            seen[i] = true;
        }
        mapOk &= ShanhaiSaveEditInventoryUi.slotAt(false, 40) == ShanhaiPlayerDataEdit.OFFHAND_SLOT
                && ShanhaiSaveEditInventoryUi.slotAt(false, 40) != ShanhaiPlayerDataEdit.OFFHAND_SLOT_UNSIGNED_LEGACY
                && ShanhaiSaveEditInventoryUi.indexOf(false, ShanhaiPlayerDataEdit.OFFHAND_SLOT_UNSIGNED_LEGACY) == 40
                && ShanhaiSaveEditInventoryUi.slotAt(true, 26) == 26
                && ShanhaiSaveEditInventoryUi.indexOf(true, 27) < 0;
        // 名字回退（没有名字表也必须非空）
        final String labelNull = ShanhaiSaveEditInventoryUi.itemLabel("minecraft:stone", null);
        final String labelEmpty = ShanhaiSaveEditInventoryUi.itemLabel("", null);
        final boolean nameOk = labelNull.contains("minecraft:stone")
                && labelNull.contains(ShanhaiSaveEditInventoryUi.MARK_UNREGISTERED)
                && !labelEmpty.isEmpty();
        // 数量校验：1..64 放行，0 / 65 / 空 / 非数字必须拒
        final boolean countOk = ShanhaiSaveEditInventoryUi.countProblem("1") == null
                && ShanhaiSaveEditInventoryUi.countProblem("64") == null
                && ShanhaiSaveEditInventoryUi.countProblem("0") != null
                && ShanhaiSaveEditInventoryUi.countProblem("65") != null
                && ShanhaiSaveEditInventoryUi.countProblem("") != null
                && ShanhaiSaveEditInventoryUi.countProblem("abc") != null;
        final int invSegments = ShanhaiSaveEditInventoryUi.SEGMENTS.length;
        sb.append(" inv_cells=").append(invCells).append("/").append(enderCells)
                .append(" inv_overlay_slots=").append(invSlots)
                .append(" inv_picker=").append(pickSlots).append("/").append(pickCells)
                .append(" inv_segments=").append(invSegments).append("[").append(segRead.toString().trim())
                .append("] inv_map_ok=").append(mapOk).append(" inv_name_ok=").append(nameOk)
                .append(" inv_count_ok=").append(countOk);
        final boolean invOk = invCells == 41 && enderCells == 27 && invSlots == 16 && pickSlots == 11
                && pickCells == 48 && invSegments == 4 && segmentOk && mapOk && nameOk && countOk;
        pass &= invOk;

        final String line = "saveedit_selfcheck PASS=" + pass + " | " + sb;
        ctx.getSource().sendSuccess(() -> Component.literal("§a" + line), false);
        ShanhaiMod.LOGGER.info("{} {}", PREFIX, line);
        return pass ? 1 : 0;
    }

    /** 门槛：玩家必须身上带模块；控制台放行（本类里开面板那条已经另外要求"是玩家"）。 */
    private static String gate(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        if (ShanhaiHoloMenuNetwork.moduleInInventory(player)) {
            return null;
        }
        return "§c这条命令要身上带着「创始现实修改模块」";
    }
}
