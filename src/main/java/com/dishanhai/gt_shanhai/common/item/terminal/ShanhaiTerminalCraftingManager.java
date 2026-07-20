package com.dishanhai.gt_shanhai.common.item.terminal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.stacks.AEKey;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.item.ShanhaiUltimateTerminalConfig;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiTerminalAeBinding.Context;

import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
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

    private ShanhaiTerminalCraftingManager() {}

    public static boolean begin(ServerPlayer player, ItemStack terminal, ShanhaiStructurePlan plan,
                                Context ae, ShanhaiTerminalMaterialService materials) {
        if (player == null || terminal.isEmpty() || plan == null || ae == null) return false;
        List<ShanhaiTerminalMaterialService.RequestTarget> shortages =
                materials.requestableShortages(plan, player, ae);
        if (shortages.isEmpty()) return false;
        ICraftingService crafting = ae.grid().getCraftingService();
        List<WorkItem> items = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (ShanhaiTerminalMaterialService.RequestTarget shortage : shortages) {
            if (!crafting.isCraftable(shortage.key())) {
                unavailable.add(shortage.displayName());
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
        if (items.isEmpty()) return false;
        UUID terminalId = ShanhaiUltimateTerminalConfig.getTerminalUuid(terminal);
        Session previous = SESSIONS.put(terminalId, new Session(
                player.getUUID(), terminalId, plan.target(),
                ShanhaiUltimateTerminalConfig.getBoundAe(terminal), plan.fingerprint(), items));
        cancel(previous);
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
        cancel(SESSIONS.put(terminalId, session));
    }

    public static boolean confirmSubmit(ServerPlayer player, ItemStack terminal,
                                        ShanhaiStructurePlan currentPlan, Context ae) {
        Session session = validSession(player, terminal, currentPlan);
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
                failed.add(item.displayName + "(" + result.errorCode() + ")");
            }
        }
        if (!failed.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "§c[山海终端] 部分合成提交失败: §f" + String.join("、", failed)));
        }
        session.phase = Phase.SUBMITTED;
        if (submitted == 0) return false;
        player.sendSystemMessage(Component.literal(
                "§b[山海终端] 已提交 §f" + submitted + " §b项任务；材料到齐后再次右击控制器"));
        return true;
    }

    public static boolean refreshBuildReadiness(ServerPlayer player, ItemStack terminal,
                                                ShanhaiStructurePlan currentPlan, Context ae,
                                                ShanhaiTerminalMaterialService materials) {
        Session session = validSession(player, terminal, currentPlan);
        if (session == null || session.phase != Phase.SUBMITTED || ae == null) return false;
        if (!currentPlan.fingerprint().equals(session.planFingerprint)) return false;
        if (hasPendingItems(session)) {
            startPendingCalculations(session, player, ae);
            return false;
        }
        if (!materials.shortages(currentPlan, player, ae).isEmpty()) return false;
        session.phase = Phase.READY_TO_BUILD;
        player.sendSystemMessage(Component.literal(
                "§a[山海终端] 材料已齐；潜行右击同一控制器确认施工"));
        return true;
    }

    public static boolean consumeBuildConfirmation(ServerPlayer player, ItemStack terminal,
                                                   ShanhaiStructurePlan currentPlan) {
        Session session = validSession(player, terminal, currentPlan);
        if (session == null || session.phase != Phase.READY_TO_BUILD) return false;
        if (!currentPlan.fingerprint().equals(session.planFingerprint)) return false;
        SESSIONS.remove(session.terminalId);
        return true;
    }

    public static void clear(ItemStack terminal) {
        cancel(SESSIONS.remove(ShanhaiUltimateTerminalConfig.getTerminalUuid(terminal)));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SESSIONS.isEmpty()) return;
        for (Session session : new ArrayList<>(SESSIONS.values())) {
            if (session.phase != Phase.CALCULATING && session.phase != Phase.RETRY_CALCULATING) continue;
            session.ticks++;
            if (session.ticks > CALC_TIMEOUT_TICKS) {
                SESSIONS.remove(session.terminalId);
                cancel(session);
                ServerPlayer player = findPlayer(session.playerId);
                if (player != null) player.sendSystemMessage(Component.literal("§c[山海终端] 合成方案计算超时"));
                continue;
            }
            if (session.items.stream().anyMatch(item -> !item.submitted
                    && item.future != null && !item.future.isDone())) continue;
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
                }
                continue;
            }
            if (usable == 0) {
                SESSIONS.remove(session.terminalId);
                if (player != null) player.sendSystemMessage(Component.literal(
                        "§c[山海终端] 合成方案均不可提交: §f" + summarize(failures)));
                continue;
            }
            session.phase = Phase.READY_TO_SUBMIT;
            if (player != null) player.sendSystemMessage(Component.literal(
                    "§e[山海终端] 合成方案已就绪；再次右击同一控制器确认下单"
                            + (failures.isEmpty() ? "" : " §7（跳过 " + failures.size() + " 项）")));
        }
    }

    private static boolean hasPendingItems(Session session) {
        for (WorkItem item : session.items) {
            if (!item.submitted) return true;
        }
        return false;
    }

    private static boolean startPendingCalculations(Session session, ServerPlayer player, Context ae) {
        if (session.phase == Phase.RETRY_CALCULATING) return true;
        ICraftingService crafting = ae.grid().getCraftingService();
        boolean started = false;
        for (WorkItem item : session.items) {
            if (item.submitted || !crafting.isCraftable(item.key)) continue;
            item.future = crafting.beginCraftingCalculation(
                    player.level(), ae::source, item.key, item.amount,
                    CalculationStrategy.REPORT_MISSING_ITEMS);
            started = true;
        }
        if (!started) return false;
        session.phase = Phase.RETRY_CALCULATING;
        session.ticks = 0;
        player.sendSystemMessage(Component.literal("§e[山海终端] 正在重试之前失败的合成任务"));
        return true;
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

    private static Session validSession(ServerPlayer player, ItemStack terminal,
                                        ShanhaiStructurePlan currentPlan) {
        if (player == null || terminal.isEmpty() || currentPlan == null) return null;
        UUID terminalId = ShanhaiUltimateTerminalConfig.getTerminalUuid(terminal);
        Session session = SESSIONS.get(terminalId);
        if (session == null || !session.playerId.equals(player.getUUID())) return null;
        if (!session.target.equals(currentPlan.target())) return null;
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
