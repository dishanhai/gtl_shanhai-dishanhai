package com.dishanhai.gt_shanhai.common.item.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.item.ShanhaiUltimateTerminalBehavior;
import com.dishanhai.gt_shanhai.common.item.ShanhaiUltimateTerminalConfig;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiTerminalAeBinding.Context;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiTerminalCraftingManager {

    public enum Phase {
        CALCULATING,
        RETRY_CALCULATING,
        READY_TO_SUBMIT,
        SUBMITTED,
        READY_TO_BUILD
    }

    private static final long CALC_TIMEOUT_TICKS = 400;

    private static final class WorkItem {
        final AEKey key;
        final long amount;
        final String displayName;
        Future<ICraftingPlan> future;
        ICraftingPlan plan;
        boolean submitted;
        String submitError;

        WorkItem(AEKey key, long amount, String displayName) {
            this.key = key;
            this.amount = amount;
            this.displayName = displayName;
        }
    }

    private static final class Session {
        final UUID playerId;
        final UUID terminalId;
        final GlobalPos target;
        final GlobalPos binding;
        final String planFingerprint;
        final List<WorkItem> items;
        Phase phase = Phase.CALCULATING;
        long ticks;
        /** 没有合成流程、没进入计算的缺口；合成报告里单独列出。 */
        List<WorkItem> unavailable = List.of();

        Session(UUID playerId, UUID terminalId, GlobalPos target, GlobalPos binding,
                String planFingerprint, List<WorkItem> items) {
            this.playerId = playerId;
            this.terminalId = terminalId;
            this.target = target;
            this.binding = binding;
            this.planFingerprint = planFingerprint;
            this.items = items;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, Session> ORDER_SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<GlobalPos, Map<AEKey, Long>>> SUBMITTED_AMOUNTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<GlobalPos, Map<AEKey, Long>>> BLOCKED_AMOUNTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Session> ACTIVE_CALCULATIONS = new ConcurrentHashMap<>();

    private ShanhaiTerminalCraftingManager() {}

    public static boolean begin(ServerPlayer player, ItemStack terminal, ShanhaiStructurePlan plan,
                                Context ae, ShanhaiTerminalMaterialService materials) {
        if (player == null || terminal.isEmpty() || plan == null || ae == null) return false;
        UUID terminalId = ShanhaiUltimateTerminalConfig.getTerminalUuid(terminal);
        Session existing = ORDER_SESSIONS.get(terminalId);
        if (existing != null
                && existing.target.equals(plan.target())
                && plan.fingerprint().equals(existing.planFingerprint)
                && (existing.phase == Phase.CALCULATING
                    || existing.phase == Phase.RETRY_CALCULATING
                    || existing.phase == Phase.READY_TO_SUBMIT)) {
            return false;
        }
        List<ShanhaiTerminalMaterialService.RequestTarget> shortages =
                remainingShortages(terminalId, plan.target(),
                        materials.requestableShortages(plan, player, ae));
        if (shortages.isEmpty()) return false;
        ICraftingService crafting = ae.grid().getCraftingService();
        List<WorkItem> items = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        List<WorkItem> unavailableItems = new ArrayList<>();
        for (ShanhaiTerminalMaterialService.RequestTarget shortage : shortages) {
            if (!crafting.isCraftable(shortage.key())) {
                unavailable.add(shortage.displayName());
                unavailableItems.add(new WorkItem(shortage.key(), shortage.amount(), shortage.displayName()));
                continue;
            }
            WorkItem item = new WorkItem(shortage.key(), shortage.amount(), shortage.displayName());
            item.future = crafting.beginCraftingCalculation(
                    player.level(), ae::source, item.key, item.amount,
                    CalculationStrategy.REPORT_MISSING_ITEMS);
            items.add(item);
        }
        if (!unavailable.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "§c[山海终端] 无可用合成流程: §f" + String.join("、", unavailable)));
        }
        if (items.isEmpty()) {
            Session none = new Session(player.getUUID(), terminalId, plan.target(), null, plan.fingerprint(), items);
            none.unavailable = unavailableItems;
            sendCraftReport(player, none, Stage.CALCULATED, "全部缺口都没有可用合成流程", ChatFormatting.RED);
            return false;
        }
        Session session = new Session(
                player.getUUID(), terminalId, plan.target(),
                ShanhaiUltimateTerminalConfig.getBoundAe(terminal), plan.fingerprint(), items);
        session.unavailable = unavailableItems;
        Session previous = ORDER_SESSIONS.put(terminalId, session);
        if (previous != null) ACTIVE_CALCULATIONS.remove(terminalId, previous);
        cancel(previous);
        ACTIVE_CALCULATIONS.put(terminalId, session);
        player.sendSystemMessage(Component.literal(
                "§b[山海终端] 正在计算 §f" + items.size() + " §b项 AE 合成方案"));
        return true;
    }

    public static Phase phase(ItemStack terminal) {
        Session session = SESSIONS.get(ShanhaiUltimateTerminalConfig.getTerminalUuid(terminal));
        return session == null ? null : session.phase;
    }

    public static void armDirectBuild(ServerPlayer player, ItemStack terminal,
                                      ShanhaiStructurePlan plan) {
        UUID terminalId = ShanhaiUltimateTerminalConfig.getTerminalUuid(terminal);
        Session session = new Session(player.getUUID(), terminalId, plan.target(),
                ShanhaiUltimateTerminalConfig.getBoundAe(terminal), plan.fingerprint(), List.of());
        session.phase = Phase.READY_TO_BUILD;
        Session previous = SESSIONS.put(terminalId, session);
        if (previous != null) ACTIVE_CALCULATIONS.remove(terminalId, previous);
        cancel(previous);
    }

    public static boolean confirmSubmit(ServerPlayer player, ItemStack terminal,
                                        ShanhaiStructurePlan currentPlan, Context ae) {
        Session session = validOrderSession(player, terminal, currentPlan);
        if (session == null || session.phase != Phase.READY_TO_SUBMIT || ae == null) return false;
        if (!sameBinding(session, terminal)) return false;
        int submitted = 0;
        List<String> failed = new ArrayList<>();
        for (WorkItem item : session.items) {
            if (item.submitted) continue;
            if (item.plan == null || item.plan.simulation()) continue;
            ICraftingSubmitResult result = ae.grid().getCraftingService().submitJob(
                    item.plan, null, null, true, ae.source());
            if (result.successful()) {
                item.submitted = true;
                submitted++;
            } else {
                item.submitError = String.valueOf(result.errorCode());
                failed.add(item.displayName + "(" + result.errorCode() + ")");
            }
        }
        if (!failed.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "§c[山海终端] 部分合成提交失败: §f" + String.join("、", failed)));
        }
        ORDER_SESSIONS.remove(session.terminalId, session);
        ACTIVE_CALCULATIONS.remove(session.terminalId, session);
        for (WorkItem item : session.items) {
            if (item.submitted) {
                rememberAmount(SUBMITTED_AMOUNTS, session.terminalId, session.target, item.key, item.amount);
            } else if (item.plan != null && item.plan.simulation()) {
                rememberAmount(BLOCKED_AMOUNTS, session.terminalId, session.target, item.key, item.amount);
            }
        }
        if (submitted == 0) {
            player.sendSystemMessage(Component.literal(
                    "§e[山海终端] 未成功提交 AE 合成任务；可继续按现有材料施工，缺材料位置跳过"));
            sendCraftReport(player, session, Stage.SUBMITTED,
                    "未成功提交 AE 合成任务；可继续按现有材料施工，缺材料位置跳过", ChatFormatting.YELLOW);
            return true;
        }
        player.sendSystemMessage(Component.literal(
                "§b[山海终端] 已提交 §f" + submitted
                        + " §b项缺口下单；可继续按现有材料施工，缺材料位置跳过"));
        sendCraftReport(player, session, Stage.SUBMITTED,
                "已提交 " + submitted + " 项缺口下单；可继续按现有材料施工，缺材料位置跳过", ChatFormatting.AQUA);
        return true;
    }

    public static boolean refreshBuildReadiness(ServerPlayer player, ItemStack terminal,
                                                ShanhaiStructurePlan currentPlan, Context ae,
                                                ShanhaiTerminalMaterialService materials) {
        Session session = validSession(player, terminal, currentPlan);
        if (session == null || session.phase != Phase.SUBMITTED || ae == null) return false;
        if (!currentPlan.fingerprint().equals(session.planFingerprint)) return false;
        session.phase = Phase.READY_TO_BUILD;
        player.sendSystemMessage(Component.literal(
                "§a[山海终端] 可施工；会按当前背包/AE库存放置，缺材料位置跳过"));
        return true;
    }

    public static boolean consumeBuildConfirmation(ServerPlayer player, ItemStack terminal,
                                                   ShanhaiStructurePlan currentPlan) {
        Session session = validSession(player, terminal, currentPlan);
        if (session == null || session.phase != Phase.READY_TO_BUILD) return false;
        if (!currentPlan.fingerprint().equals(session.planFingerprint)) return false;
        SESSIONS.remove(session.terminalId, session);
        ACTIVE_CALCULATIONS.remove(session.terminalId, session);
        return true;
    }

    public static void clear(ItemStack terminal) {
        UUID terminalId = ShanhaiUltimateTerminalConfig.getTerminalUuid(terminal);
        Session build = SESSIONS.remove(terminalId);
        Session order = ORDER_SESSIONS.remove(terminalId);
        if (order != null) ACTIVE_CALCULATIONS.remove(terminalId, order);
        SUBMITTED_AMOUNTS.remove(terminalId);
        BLOCKED_AMOUNTS.remove(terminalId);
        cancel(build);
        cancel(order);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE_CALCULATIONS.isEmpty()) return;
        for (Map.Entry<UUID, Session> entry : ACTIVE_CALCULATIONS.entrySet()) {
            Session session = entry.getValue();
            if (session.phase != Phase.CALCULATING && session.phase != Phase.RETRY_CALCULATING) {
                ACTIVE_CALCULATIONS.remove(entry.getKey(), session);
                continue;
            }
            session.ticks++;
            if (session.ticks > CALC_TIMEOUT_TICKS) {
                ACTIVE_CALCULATIONS.remove(session.terminalId, session);
                ORDER_SESSIONS.remove(session.terminalId, session);
                cancel(session);
                ServerPlayer player = findPlayer(session.playerId);
                if (player != null) player.sendSystemMessage(Component.literal("§c[山海终端] 合成方案计算超时"));
                continue;
            }
            if (hasPendingCalculation(session)) continue;
            ACTIVE_CALCULATIONS.remove(session.terminalId, session);
            int usable = 0;
            List<String> failures = new ArrayList<>();
            for (WorkItem item : session.items) {
                if (item.submitted) continue;
                try {
                    if (item.future == null) {
                        item.plan = null;
                        failures.add(item.displayName + " 当前无可用合成流程");
                        continue;
                    }
                    item.plan = item.future.get();
                    if (item.plan != null && !item.plan.simulation()) {
                        usable++;
                    } else {
                        failures.add(describeFailedPlan(item));
                    }
                } catch (Exception ignored) {
                    item.plan = null;
                    failures.add(item.displayName + " 计算失败");
                }
            }
            ServerPlayer player = findPlayer(session.playerId);
            if (session.phase == Phase.RETRY_CALCULATING) {
                session.phase = usable == 0 ? Phase.SUBMITTED : Phase.READY_TO_SUBMIT;
                if (player != null) {
                    player.sendSystemMessage(Component.literal(usable == 0
                            ? "§e[山海终端] 失败任务暂时仍不可提交；材料或配方就绪后再次普通右击重试"
                            : "§e[山海终端] 失败任务的合成方案已就绪；再次右击控制器确认补单"));
                    sendCraftReport(player, session, Stage.CALCULATED, usable == 0
                            ? "失败任务暂时仍不可提交；材料或配方就绪后再次普通右击重试"
                            : "失败任务的合成方案已就绪；再次右击控制器确认补单", ChatFormatting.YELLOW);
                }
                continue;
            }
            if (usable == 0) {
                for (WorkItem item : session.items) {
                    rememberAmount(BLOCKED_AMOUNTS, session.terminalId, session.target, item.key, item.amount);
                }
                ORDER_SESSIONS.remove(session.terminalId, session);
                if (player != null) player.sendSystemMessage(Component.literal(
                        "§c[山海终端] 合成方案均不可提交: §f" + summarize(failures)
                                + " §7补料后请开关一次 AE 模式再试"));
                if (player != null) sendCraftReport(player, session, Stage.CALCULATED,
                        "合成方案均不可提交；补料后请开关一次 AE 模式再试", ChatFormatting.RED);
                continue;
            }
            session.phase = Phase.READY_TO_SUBMIT;
            if (player != null) player.sendSystemMessage(Component.literal(
                    "§e[山海终端] 合成方案已就绪；再次右击同一控制器确认下单"
                            + (failures.isEmpty() ? "" : " §7（跳过 " + failures.size() + " 项）")));
            if (player != null) sendCraftReport(player, session, Stage.CALCULATED,
                    "合成方案已就绪；再次右击同一控制器确认下单"
                            + (failures.isEmpty() ? "" : "（跳过 " + failures.size() + " 项）"), ChatFormatting.YELLOW);
        }
    }

    // ---------- 合成报告 ----------
    private enum Stage { CALCULATED, SUBMITTED }

    private static final String LANG = "gui.gt_shanhai.ultimate_terminal.report.craft.";

    private record Status(String key, int color) {}
    // 排序即显示顺序：最需要处理的在前。
    private static final Status SUBMIT_FAILED = new Status("submit_failed", 0xFF5555);
    private static final Status FAILED = new Status("failed", 0xFF5555);
    private static final Status UNAVAILABLE = new Status("unavailable", 0xFFAA00);
    private static final Status READY = new Status("ready", 0x55FF55);
    private static final Status SUBMITTED = new Status("submitted", 0x55FFFF);
    private static final List<Status> STATUS_ORDER = List.of(SUBMIT_FAILED, FAILED, UNAVAILABLE, READY, SUBMITTED);

    private static Status statusOf(WorkItem item, boolean unavailable, Stage stage) {
        if (unavailable) return UNAVAILABLE;
        if (item.submitted) return SUBMITTED;
        boolean usable = item.plan != null && !item.plan.simulation();
        if (!usable) return FAILED;
        return stage == Stage.SUBMITTED ? SUBMIT_FAILED : READY;
    }

    private static Component keysText(KeyCounter counter) {
        MutableComponent joined = Component.empty();
        boolean first = true;
        for (var entry : counter) {
            if (!first) joined.append(Component.literal("、"));
            first = false;
            joined.append(entry.getKey().getDisplayName()).append(Component.literal(" ×" + entry.getLongValue()));
        }
        return joined;
    }

    private static Component detailOf(WorkItem item, Status status) {
        if (status == UNAVAILABLE) return Component.translatable(LANG + "detail_unavailable");
        if (status == SUBMIT_FAILED)
            return Component.translatable(LANG + "detail_submit_failed", String.valueOf(item.submitError));
        if (status == FAILED) {
            if (item.plan == null) return Component.translatable(LANG + "detail_error");
            Component missing = keysText(item.plan.missingItems());
            return missing.getString().isEmpty() ? Component.translatable(LANG + "detail_none")
                    : Component.translatable(LANG + "detail_missing", missing);
        }
        if (status == READY && item.plan != null) {
            MutableComponent detail = Component.translatable(LANG + "detail_bytes", item.plan.bytes());
            Component used = keysText(item.plan.usedItems());
            if (!used.getString().isEmpty()) detail.append(Component.literal("   ")).append(Component.translatable(LANG + "detail_used", used));
            return detail;
        }
        return Component.empty();
    }

    /** 合成明细：每个缺口一行（图标 + 名称 ×数量 + 缺料/占用说明），聊天栏只留摘要与连结。 */
    private static void sendCraftReport(ServerPlayer player, Session session, Stage stage,
                                        String summary, ChatFormatting color) {
        if (player == null) return;
        List<WorkItem> all = new ArrayList<>(session.items);
        all.addAll(session.unavailable);
        Map<Status, List<WorkItem>> grouped = new java.util.HashMap<>();
        int ready = 0, bad = 0, submitted = 0;
        for (WorkItem item : all) {
            Status status = statusOf(item, session.unavailable.contains(item), stage);
            grouped.computeIfAbsent(status, key -> new ArrayList<>()).add(item);
            if (status == READY) ready++;
            else if (status == SUBMITTED) submitted++;
            else bad++;
        }
        ShanhaiTerminalReport.Builder report = new ShanhaiTerminalReport.Builder(
                ShanhaiTerminalReport.Type.CRAFT, session.target.pos(), all.size(), ready, bad, submitted)
                .summary(Component.literal(summary).withStyle(color));
        for (Status status : STATUS_ORDER) {
            for (WorkItem item : grouped.getOrDefault(status, List.of())) {
                List<ItemStack> icon = item.key instanceof AEItemKey itemKey ? List.of(itemKey.toStack()) : List.of();
                // 合成项没有世界坐标，坐标栏不使用。
                report.add(BlockPos.ZERO, status.key(), Component.translatable(LANG + status.key()), status.color(),
                        Component.literal(item.displayName + " ×" + item.amount), icon, detailOf(item, status));
            }
        }
        ShanhaiUltimateTerminalBehavior.sendReportLink(player, report.build(),
                Component.translatable("message.gt_shanhai.ultimate_terminal.craft_report_more"),
                "message.gt_shanhai.ultimate_terminal.craft_report_link");
    }

    private static boolean hasPendingCalculation(Session session) {
        for (WorkItem item : session.items) {
            if (!item.submitted
                    && item.future != null && !item.future.isDone()) return true;
        }
        return false;
    }

    private static String describeFailedPlan(WorkItem item) {
        if (item.plan == null) return item.displayName + " 计算失败";
        if (!item.plan.simulation()) return item.displayName;
        List<String> missing = new ArrayList<>();
        for (var entry : item.plan.missingItems()) {
            if (missing.size() >= 4) break;
            missing.add(entry.getKey().getDisplayName().getString() + "×" + entry.getLongValue());
        }
        return missing.isEmpty()
                ? item.displayName + " 无可提交方案"
                : item.displayName + " 缺 " + String.join("、", missing);
    }

    private static String summarize(List<String> lines) {
        if (lines.isEmpty()) return "未知原因";
        if (lines.size() <= 3) return String.join("；", lines);
        return String.join("；", lines.subList(0, 3)) + " 等 " + lines.size() + " 项";
    }

    private static List<ShanhaiTerminalMaterialService.RequestTarget> remainingShortages(
            UUID terminalId, GlobalPos target, List<ShanhaiTerminalMaterialService.RequestTarget> shortages) {
        if (shortages.isEmpty()) return shortages;
        Map<AEKey, Long> submitted = amountsFor(SUBMITTED_AMOUNTS, terminalId, target);
        Map<AEKey, Long> blocked = amountsFor(BLOCKED_AMOUNTS, terminalId, target);
        if (submitted.isEmpty() && blocked.isEmpty()) return shortages;
        List<ShanhaiTerminalMaterialService.RequestTarget> remaining = new ArrayList<>();
        for (ShanhaiTerminalMaterialService.RequestTarget shortage : shortages) {
            long covered = saturatedAdd(submitted.getOrDefault(shortage.key(), 0L),
                    blocked.getOrDefault(shortage.key(), 0L));
            if (covered >= shortage.amount()) continue;
            remaining.add(new ShanhaiTerminalMaterialService.RequestTarget(
                    shortage.key(), shortage.amount() - covered, shortage.displayName()));
        }
        return remaining;
    }

    private static Map<AEKey, Long> amountsFor(
            Map<UUID, Map<GlobalPos, Map<AEKey, Long>>> amounts, UUID terminalId, GlobalPos target) {
        Map<GlobalPos, Map<AEKey, Long>> byTarget = amounts.get(terminalId);
        if (byTarget == null) return Map.of();
        Map<AEKey, Long> found = byTarget.get(target);
        return found == null ? Map.of() : found;
    }

    private static void rememberAmount(Map<UUID, Map<GlobalPos, Map<AEKey, Long>>> amounts,
                                       UUID terminalId, GlobalPos target, AEKey key, long amount) {
        if (key == null || amount <= 0) return;
        Map<AEKey, Long> bucket = amounts
                .computeIfAbsent(terminalId, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(target, ignored -> new ConcurrentHashMap<>());
        long current = bucket.getOrDefault(key, 0L);
        bucket.put(key, saturatedAdd(current, amount));
    }

    private static long saturatedAdd(long left, long right) {
        long sum = left + right;
        return (left ^ right) < 0 || (left ^ sum) >= 0 ? sum : Long.MAX_VALUE;
    }

    private static Session validSession(ServerPlayer player, ItemStack terminal,
                                        ShanhaiStructurePlan currentPlan) {
        return validSession(SESSIONS, player, terminal, currentPlan, false);
    }

    private static Session validOrderSession(ServerPlayer player, ItemStack terminal,
                                             ShanhaiStructurePlan currentPlan) {
        return validSession(ORDER_SESSIONS, player, terminal, currentPlan, true);
    }

    private static Session validSession(Map<UUID, Session> sessions, ServerPlayer player, ItemStack terminal,
                                        ShanhaiStructurePlan currentPlan, boolean requireFingerprint) {
        if (player == null || terminal.isEmpty() || currentPlan == null) return null;
        UUID terminalId = ShanhaiUltimateTerminalConfig.getTerminalUuid(terminal);
        Session session = sessions.get(terminalId);
        if (session == null || !session.playerId.equals(player.getUUID())) return null;
        if (!session.target.equals(currentPlan.target())) return null;
        if (requireFingerprint && !currentPlan.fingerprint().equals(session.planFingerprint)) return null;
        return session;
    }

    private static boolean sameBinding(Session session, ItemStack terminal) {
        GlobalPos current = ShanhaiUltimateTerminalConfig.getBoundAe(terminal);
        return session.binding != null && session.binding.equals(current);
    }

    private static void cancel(Session session) {
        if (session == null) return;
        for (WorkItem item : session.items) {
            if (item.future != null && !item.future.isDone()) item.future.cancel(true);
        }
    }

    private static ServerPlayer findPlayer(UUID playerId) {
        var server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : server.getPlayerList().getPlayer(playerId);
    }
}
