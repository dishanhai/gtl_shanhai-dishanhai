package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.ae2.CraftingPlanOverflowDetector;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingCPU;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopAutoCraftPlanPacket;
import com.dishanhai.gt_shanhai.mixin.ShopCraftingJobAccessors;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * 山海商店：合併實際交易成本，只補扣除現貨及在製成品後的缺口。
 * AE2 原生引擎負責配方樹；多目標依序計算並共用庫存預留，不為消耗的中間物額外補單。
 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShopAutoCraft {
    private ShopAutoCraft() {}

    private static final long CALC_TIMEOUT_TICKS = 400L;
    private static final long READY_TIMEOUT_TICKS = 1200L;
    private static final int MAX_PLAN_ITEMS = 256;
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final Map<UUID, Session> CALCULATING = new ConcurrentHashMap<>();
    private static final Map<UUID, Session> READY = new ConcurrentHashMap<>();

    /** 补齐目标种类：商品成本 / 阶段解锁前置 / 商品一次性提交前置 / 整个购物车的合并成本。 */
    public enum Target {
        COST,
        STAGE,
        ENTRY_SUBMISSION,
        CART
    }

    /** 购物车里一件候选：商品 + 候选购买次数。 */
    private record CartLine(ShopEntry entry, long times) {}

    private static final class PlanItem {
        final AEKey key;
        final long amount;
        Future<ICraftingPlan> future;
        ICraftingPlan result;
        String errorKey;
        Map<AEKey, BigInteger> used = Map.of();

        PlanItem(AEKey key, long amount) {
            this.key = key;
            this.amount = amount;
        }
    }

    private static final class Snapshot {
        final Map<AEKey, BigInteger> shortages;
        final Map<AEKey, BigInteger> retained;

        Snapshot(Map<AEKey, BigInteger> shortages, Map<AEKey, BigInteger> retained) {
            this.shortages = shortages;
            this.retained = retained;
        }
    }

    private static final class Session {
        final UUID planId = UUID.randomUUID();
        final IGrid grid;
        final ShopEntry entry;
        final long times;
        final Target target;
        final String stagePath;
        final Map<AEKey, BigInteger> shortages;
        final List<CartLine> lines;
        final Map<AEKey, BigInteger> reserved = new LinkedHashMap<>();
        final List<PlanItem> items = new ArrayList<>();
        final List<Component> notes = new ArrayList<>();
        Map<AEKey, Long> planningStock;
        int nextItem;
        long ticks;
        int requestId;

        Session(IGrid grid, ShopEntry entry, long times, Target target, String stagePath,
                Snapshot snapshot, List<CartLine> lines) {
            this.grid = grid;
            this.lines = lines;
            this.entry = entry;
            this.times = times;
            this.target = target;
            this.stagePath = stagePath;
            this.shortages = snapshot.shortages;
            this.reserved.putAll(snapshot.retained);
        }
    }

    public static void beginPlan(ServerPlayer player, ShopEntry entry, long times, boolean aeMode, int requestId) {
        if (!canPlanPurchase(player, entry)) {
            reject(player, requestId, "target_changed");
            return;
        }
        IGrid grid = resolveGrid(player, aeMode, requestId);
        if (grid == null) return;
        times = Math.max(1L, times);
        startPlan(player, grid, entry, times, Target.COST, "", List.of(), requestId);
    }

    /** 购物车「AE下单补齐缺口」：把购物车里所有可买候选的成本合并成一份缺口，一次出方案、一次确认。 */
    public static void beginCartPlan(ServerPlayer player, boolean aeMode, int requestId) {
        IGrid grid = resolveGrid(player, aeMode, requestId);
        if (grid == null) return;
        List<CartLine> lines = cartLines(player);
        if (lines.isEmpty()) {
            reject(player, requestId, "cart_empty");
            return;
        }
        startPlan(player, grid, null, 1L, Target.CART, "", lines, requestId);
    }

    private static List<CartLine> cartLines(ServerPlayer player) {
        List<CartLine> lines = new ArrayList<>();
        for (Map.Entry<String, Long> candidate : ShopCartAPI.getAll(player.getServer(), player.getUUID()).entrySet()) {
            ShopEntry entry = ShopConfig.resolveByStableId(candidate.getKey());
            long times = candidate.getValue();
            if (times > 0L && canPlanPurchase(player, entry)) lines.add(new CartLine(entry, times));
        }
        return lines;
    }

    private static boolean canPlanPurchase(ServerPlayer player, ShopEntry entry) {
        return canAccessEntry(player, entry) && entry.allowsBuy() && !entry.hasMissingItems();
    }

    private static boolean canAccessEntry(ServerPlayer player, ShopEntry entry) {
        return entry != null && entry.isValid() && (!entry.isHidden() || ShopEditPermission.canEditCatalog(player));
    }

    public static void beginStagePlan(ServerPlayer player, String stagePath, boolean aeMode, int requestId) {
        if (ShopStageConfig.get(stagePath).isEmpty() || hasSubmission(player, "stage:" + stagePath)) {
            reject(player, requestId, "target_changed");
            return;
        }
        IGrid grid = resolveGrid(player, aeMode, requestId);
        if (grid != null) startPlan(player, grid, null, 1L, Target.STAGE, stagePath, List.of(), requestId);
    }

    public static void beginEntrySubmissionPlan(ServerPlayer player, ShopEntry entry, boolean aeMode, int requestId) {
        if (!canAccessEntry(player, entry) || !entry.hasSubmissionRequirement()
                || hasSubmission(player, "entry:" + entry.getStableId())) {
            reject(player, requestId, "target_changed");
            return;
        }
        IGrid grid = resolveGrid(player, aeMode, requestId);
        if (grid != null) startPlan(player, grid, entry, 1L, Target.ENTRY_SUBMISSION, entry.getStableId(), List.of(), requestId);
    }

    private static boolean hasSubmission(ServerPlayer player, String key) {
        return ShopSubmissionSavedData.get(player.getServer()).has(player, key);
    }

    private static IGrid resolveGrid(ServerPlayer player, boolean aeMode, int requestId) {
        if (!aeMode) {
            reject(player, requestId, "enable_ae");
            return null;
        }
        IGrid grid = ShopAeNetwork.findBoundGrid(player);
        if (grid == null) reject(player, requestId, "no_network");
        return grid;
    }

    private static void startPlan(ServerPlayer player, IGrid grid, ShopEntry entry, long times, Target target,
                                  String stagePath, List<CartLine> lines, int requestId) {
        Session existing = CALCULATING.get(player.getUUID());
        if (existing != null && existing.target == target && existing.grid == grid && existing.entry == entry
                && existing.times == times && existing.stagePath.equals(stagePath) && existing.lines.equals(lines)) {
            if (existing.requestId != requestId) sendPlanFeedback(player, existing.requestId, false);
            existing.requestId = requestId;
            return;
        }
        cancelAll(player.getUUID());
        IActionSource identity = source(player);
        Snapshot snapshot = switch (target) {
            case STAGE -> snapshotIngredients(player, ShopStageConfig.get(stagePath), grid, identity);
            case ENTRY_SUBMISSION -> snapshotIngredients(player, entry.getSubmissionItems(), grid, identity);
            case CART -> snapshotLines(player, lines, grid, identity);
            case COST -> snapshotLines(player, List.of(new CartLine(entry, times)), grid, identity);
        };
        if (snapshot.shortages.isEmpty()) {
            message(player, "covered", ChatFormatting.GREEN);
            sendPlanFeedback(player, requestId, true);
            return;
        }
        Session session = new Session(grid, entry, times, target, stagePath, snapshot, lines);
        session.requestId = requestId;
        for (Map.Entry<AEKey, BigInteger> shortage : session.shortages.entrySet()) {
            AEKey key = shortage.getKey();
            BigInteger amount = shortage.getValue();
            if (session.items.size() >= MAX_PLAN_ITEMS) {
                session.notes.add(label("plan_limit").withStyle(ChatFormatting.YELLOW));
                break;
            } else if (amount.compareTo(LONG_MAX) > 0) {
                session.notes.add(issue(key, amount, "amount_limit"));
            } else if (!grid.getCraftingService().isCraftable(key)) {
                session.notes.add(issue(key, amount, "no_pattern"));
            } else {
                session.items.add(new PlanItem(key, amount.longValueExact()));
            }
        }
        if (session.items.isEmpty()) {
            sendPlanToClient(player, session);
            return;
        }
        CALCULATING.put(player.getUUID(), session);
        startNextCalculation(player, session);
    }

    private static Snapshot snapshotIngredients(ServerPlayer player, List<ExchangeEntry.Ingredient> requirements,
                                                IGrid grid, IActionSource identity) {
        Map<AEKey, BigInteger> demand = new LinkedHashMap<>();
        for (ExchangeEntry.Ingredient ingredient : requirements) {
            if (ingredient == null || ingredient.isFluid) continue;
            ShopAutoCraftAmounts.add(demand, AEItemKey.of(ingredient.makeUnitStack()), BigInteger.valueOf(ingredient.count));
        }
        return snapshotDemand(player, demand, grid, identity);
    }

    /** 多件商品的成本合并成一份需求：錢包餘額、隨身物品、AE 庫存都只扣一次，不按件重複抵銷。 */
    private static Snapshot snapshotLines(ServerPlayer player, List<CartLine> lines, IGrid grid, IActionSource identity) {
        Map<AEKey, BigInteger> demand = new LinkedHashMap<>();
        Map<ResourceLocation, BigInteger> coinTotals = new LinkedHashMap<>();
        List<ShopCost> costs = new ArrayList<>(lines.size());
        List<BigInteger> multipliers = new ArrayList<>(lines.size());
        int discount = ShopMembership.discountPercent(player.getServer(), player.getUUID());
        for (CartLine line : lines) {
            ShopEntry entry = line.entry();
            ShopCost cost = entry.getEffectiveCost(discount);
            BigInteger multiplier = BigInteger.valueOf(line.times());
            costs.add(cost);
            multipliers.add(multiplier);
            for (var coin : cost.coins.entrySet()) {
                ShopAutoCraftAmounts.add(coinTotals, coin.getKey(), coin.getValue().multiply(multiplier));
            }
        }
        for (var coin : coinTotals.entrySet()) {
            var item = ForgeRegistries.ITEMS.getValue(coin.getKey());
            if (item == null) continue;
            AEItemKey key = AEItemKey.of(new ItemStack(item));
            ShopAutoCraftAmounts.add(demand, key, coin.getValue());
            // 錢包餘額只能抵銷幣種成本，不能抵銷同種物品的實物成本（實物成本在下面才加進來）。
            ShopAutoCraftAmounts.consume(demand, key,
                    WalletAccountAPI.getCurrency(player.getServer(), player.getUUID(), coin.getKey()));
        }
        for (int i = 0; i < costs.size(); i++) {
            for (ExchangeEntry.Ingredient ingredient : costs.get(i).physical) {
                AEKey key;
                if (ingredient.isFluid) {
                    var fluid = ForgeRegistries.FLUIDS.getValue(ingredient.id);
                    key = fluid == null ? null : AEFluidKey.of(fluid);
                } else {
                    key = AEItemKey.of(ingredient.makeUnitStack());
                }
                ShopAutoCraftAmounts.add(demand, key, BigInteger.valueOf(ingredient.count).multiply(multipliers.get(i)));
            }
        }
        return snapshotDemand(player, demand, grid, identity);
    }

    private static Snapshot snapshotDemand(ServerPlayer player, Map<AEKey, BigInteger> demand,
                                           IGrid grid, IActionSource identity) {
        if (demand.isEmpty()) return new Snapshot(Map.of(), Map.of());
        Map<AEKey, BigInteger> carried = new LinkedHashMap<>();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) addCarried(carried, inventory.getItem(i));
        ShopBackpack.equipped(player).ifPresent(backpack -> {
            for (int i = 0; i < backpack.getSlots(); i++) addCarried(carried, backpack.getStackInSlot(i));
        });
        // 先配置精確 NBT 需求，再讓無 NBT 限制的成本使用剩餘堆疊；每份庫存僅扣一次。
        for (var stack : carried.entrySet()) {
            BigInteger used = ShopAutoCraftAmounts.consume(demand, stack.getKey(), stack.getValue());
            stack.setValue(stack.getValue().subtract(used));
        }
        for (var stack : carried.entrySet()) {
            if (!(stack.getKey() instanceof AEItemKey key) || stack.getValue().signum() <= 0) continue;
            ShopAutoCraftAmounts.consume(demand, AEItemKey.of(key.getItem()), stack.getValue());
        }
        Map<AEKey, BigInteger> stock = new LinkedHashMap<>();
        if (demand.isEmpty()) return new Snapshot(Map.of(), Map.of());
        var storage = grid.getStorageService().getInventory();
        for (AEKey key : demand.keySet()) {
            long available = storage.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, identity);
            ShopAutoCraftAmounts.add(stock, key, BigInteger.valueOf(Math.max(0L, available)));
        }
        Map<AEKey, BigInteger> pending = new LinkedHashMap<>();
        // 在途的中間物不是承諾交付的成品；只統計 CPU 尚未交付的最終產出。
        for (var cpu : grid.getCraftingService().getCpus()) {
            var status = cpu.getJobStatus();
            if (status == null || status.crafting() == null || !demand.containsKey(status.crafting().what())) continue;
            var output = status.crafting();
            ShopAutoCraftAmounts.add(pending, output.what(), BigInteger.valueOf(pendingOutputAmount(cpu, output)));
        }
        return new Snapshot(ShopAutoCraftAmounts.missing(demand, stock, pending),
                ShopAutoCraftAmounts.reserve(demand, stock));
    }

    private static long pendingOutputAmount(ICraftingCPU cpu, GenericStack output) {
        long remaining = output.amount();
        ICraftingLink link;
        if (cpu instanceof CraftingCPUCluster cluster) {
            var job = ((ShopCraftingJobAccessors.CpuLogic) cluster.craftingLogic).gtShanhai$getJob();
            if (job == null) return 0L;
            // 原生 getJobStatus() 提供原始訂單總量，不隨交付遞減。
            remaining = ((ShopCraftingJobAccessors.Job) job).gtShanhai$getRemainingAmount();
            link = cluster.craftingLogic.getLastLink();
        } else if (cpu instanceof QuantumCraftingCPU quantum) {
            link = quantum.craftingLogic.getLastLink();
        } else {
            return 0L;
        }
        boolean returnsToNetwork = link != null && link.isStandalone() && !link.isCanceled() && !link.isDone();
        return ShopAutoCraftAmounts.pendingOutput(output.amount(), remaining, returnsToNetwork);
    }

    private static void addCarried(Map<AEKey, BigInteger> carried, ItemStack stack) {
        if (!stack.isEmpty()) {
            ShopAutoCraftAmounts.add(carried, AEItemKey.of(stack), BigInteger.valueOf(stack.getCount()));
        }
    }

    private static IActionSource source(ServerPlayer player) {
        return IActionSource.ofPlayer(player, ShopAeNetwork.findBoundHost(player));
    }

    private static void startNextCalculation(ServerPlayer player, Session session) {
        PlanItem item = session.items.get(session.nextItem);
        Map<AEKey, Long> reservations = new LinkedHashMap<>();
        session.reserved.forEach((key, amount) -> reservations.put(key, amount.min(LONG_MAX).longValueExact()));
        try {
            IActionSource identity = source(player);
            if (session.planningStock == null) {
                session.planningStock = ShopAutoCraftActionSource.captureStock(session.grid.getStorageService(), identity);
            }
            IActionSource source = new ShopAutoCraftActionSource(identity, reservations, session.planningStock);
            item.future = session.grid.getCraftingService().beginCraftingCalculation(
                    player.level(), () -> source, item.key, item.amount, CalculationStrategy.REPORT_MISSING_ITEMS);
        } catch (RuntimeException exception) {
            item.errorKey = "calculation_failed";
            GTDishanhaiMod.LOGGER.warn("Shop crafting calculation could not start", exception);
        }
        session.ticks = 0L;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (ShopAvailability.isDisabled()) {
            CALCULATING.keySet().forEach(ShopAutoCraft::cancelAll);
            READY.clear();
            return;
        }
        for (Map.Entry<UUID, Session> entry : READY.entrySet()) {
            if (ShopAvailability.isPersonallyDisabled(entry.getKey())) {
                READY.remove(entry.getKey(), entry.getValue());
                continue;
            }
            if (++entry.getValue().ticks > READY_TIMEOUT_TICKS) READY.remove(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<UUID, Session> entry : CALCULATING.entrySet()) {
            UUID uuid = entry.getKey();
            Session session = entry.getValue();
            ServerPlayer player = findPlayer(uuid);
            if (player == null || ShopAvailability.isDisabled(player) || ShopAeNetwork.findBoundGrid(player) != session.grid) {
                if (CALCULATING.remove(uuid, session)) {
                    cancelSession(session);
                    if (player != null) reject(player, session.requestId, "target_changed");
                }
                continue;
            }
            PlanItem item = session.items.get(session.nextItem);
            session.ticks++;
            if (item.future != null && !item.future.isDone()) {
                if (session.ticks <= CALC_TIMEOUT_TICKS) continue;
                item.future.cancel(true);
                item.future = null;
                item.errorKey = "calculation_timeout";
            }
            if (item.future != null) {
                try {
                    item.result = item.future.get();
                    if (item.result == null || item.result.finalOutput() == null
                            || !item.key.equals(item.result.finalOutput().what())
                            || item.result.finalOutput().amount() != item.amount
                            || !CraftingPlanOverflowDetector.collectOverflowKeys(item.result).isEmpty()) {
                        item.result = null;
                        item.errorKey = "invalid_amount";
                    }
                } catch (Exception exception) {
                    item.errorKey = "calculation_failed";
                    GTDishanhaiMod.LOGGER.warn("Shop crafting calculation failed", exception);
                }
            }
            if (submittable(item)) {
                item.used = Map.copyOf(realUsedItems(item.result));
                item.used.forEach((key, amount) ->
                        ShopAutoCraftAmounts.add(session.reserved, key, amount));
            }
            session.nextItem++;
            if (session.nextItem < session.items.size()) {
                startNextCalculation(player, session);
                continue;
            }
            if (!CALCULATING.remove(uuid, session)) continue;
            session.ticks = 0L;
            session.planningStock = null; // Only required while calculating, not for the confirmation timeout.
            if (session.items.stream().anyMatch(ShopAutoCraft::submittable)) READY.put(uuid, session);
            sendPlanToClient(player, session);
        }
    }

    private static boolean submittable(PlanItem item) {
        return item.result != null && item.errorKey == null && !item.result.simulation();
    }

    private static Map<AEKey, BigInteger> realUsedItems(ICraftingPlan plan) {
        Map<AEKey, BigInteger> result = new LinkedHashMap<>();
        boolean virtual = VirtualPatternEncodingHelper.containsPresenceInputs(plan);
        Object2LongMap<AEKey> consumable = virtual
                ? VirtualPatternEncodingHelper.collectConsumableRequirements(plan) : null;
        for (var used : plan.usedItems()) {
            long amount = Math.max(0L, used.getLongValue());
            if (consumable != null) amount = Math.min(amount, Math.max(0L, consumable.getLong(used.getKey())));
            ShopAutoCraftAmounts.add(result, used.getKey(), BigInteger.valueOf(amount));
        }
        return result;
    }

    private static void sendPlanToClient(ServerPlayer player, Session session) {
        List<Component> notes = new ArrayList<>(session.notes);
        List<Component> willCraft = new ArrayList<>();
        Map<AEKey, BigInteger> merged = new LinkedHashMap<>();
        for (PlanItem item : session.items) {
            BigInteger amount = BigInteger.valueOf(item.amount);
            if (submittable(item)) {
                willCraft.add(quantity(item.key, amount));
                item.used.forEach((key, used) -> ShopAutoCraftAmounts.add(merged, key, used));
            } else if (item.result != null && item.result.simulation()) {
                notes.add(issue(item.key, amount, "missing_materials"));
                int shown = 0;
                for (var missing : item.result.missingItems()) {
                    if (shown++ >= 6) {
                        notes.add(label("more_materials").withStyle(ChatFormatting.GRAY));
                        break;
                    }
                    notes.add(label("missing_input", quantity(missing.getKey(), BigInteger.valueOf(missing.getLongValue())))
                            .withStyle(ChatFormatting.GRAY));
                }
            } else {
                notes.add(issue(item.key, amount, item.errorKey == null ? "calculation_failed" : item.errorKey));
            }
        }
        List<Component> useLines = new ArrayList<>();
        merged.forEach((key, amount) -> useLines.add(quantity(key, amount)));
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ShopAutoCraftPlanPacket(session.planId, willCraft, useLines, notes,
                        session.requestId, session.shortages.size()));
    }

    private static Component quantity(AEKey key, BigInteger amount) {
        return label(key instanceof AEFluidKey ? "fluid_quantity" : "item_quantity", key.getDisplayName(), amount.toString());
    }

    private static Component issue(AEKey key, BigInteger amount, String errorKey) {
        return label("issue", quantity(key, amount), label(errorKey)).withStyle(ChatFormatting.RED);
    }

    public static void reject(ServerPlayer player, int requestId, String reason) {
        message(player, reason, ChatFormatting.RED);
        sendPlanFeedback(player, requestId, false);
    }

    public static void sendPlanFeedback(ServerPlayer player, int requestId, boolean success) {
        if (player == null || requestId == 0) return;
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ShopAutoCraftPlanPacket(requestId, success));
    }

    public static void confirmPlan(ServerPlayer player, UUID planId) {
        if (player == null) return;
        if (!ShopAvailability.requireEnabled(player)) return;
        Session session = READY.get(player.getUUID());
        if (session == null || !session.planId.equals(planId)) {
            message(player, "expired", ChatFormatting.YELLOW);
            return;
        }
        // 先消費這份方案；重複確認、舊視窗確認均不能提交第二次。
        if (!READY.remove(player.getUUID(), session)) return;
        IGrid grid = ShopAeNetwork.findBoundGrid(player);
        if (session.grid != grid || !targetStillValid(player, session)) {
            message(player, "target_changed", ChatFormatting.RED);
            return;
        }
        IActionSource source = source(player);
        Snapshot current = snapshotForSession(player, session, grid, source);
        Map<AEKey, BigInteger> plannedOutputs = new LinkedHashMap<>();
        List<Map<AEKey, BigInteger>> consumptions = new ArrayList<>();
        for (PlanItem item : session.items) {
            if (!submittable(item)) continue;
            ShopAutoCraftAmounts.add(plannedOutputs, item.key, BigInteger.valueOf(item.amount));
            consumptions.add(item.used);
        }
        // A completed CPU can move items from "pending" to storage without changing the gap.
        // Retained stock is therefore recomputed, not compared with the old preview snapshot.
        if (!ShopAutoCraftAmounts.matchesPlannedOutputs(plannedOutputs, current.shortages)) {
            message(player, "shortage_changed", ChatFormatting.YELLOW);
            return;
        }
        Map<AEKey, BigInteger> required = ShopAutoCraftAmounts.submissionStock(current.retained, consumptions);
        if (!hasReservedStock(player, session, required, source)) {
            message(player, "stock_changed", ChatFormatting.YELLOW);
            return;
        }
        int submitted = 0;
        List<Component> failures = new ArrayList<>();
        // 計算時各計畫已預留互不重複的實物；按同一順序提交，不再擴張或追加依賴單。
        for (PlanItem item : session.items) {
            if (!submittable(item)) continue;
            try {
                var result = grid.getCraftingService().submitJob(item.result, null, null, true, source);
                if (result.successful()) submitted++;
                else failures.add(label("issue", item.key.getDisplayName(),
                        label("submit_error." + result.errorCode().name().toLowerCase(Locale.ROOT))));
            } catch (RuntimeException exception) {
                failures.add(label("issue", item.key.getDisplayName(), label("submit_error.exception")));
                GTDishanhaiMod.LOGGER.warn("Shop crafting submission failed", exception);
            }
        }
        int attempted = plannedOutputs.size();
        message(player, "submitted", failures.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.YELLOW, submitted, attempted);
        if (!failures.isEmpty()) {
            List<Component> shown = new ArrayList<>(failures.subList(0, Math.min(6, failures.size())));
            if (failures.size() > shown.size()) shown.add(label("more_failures", failures.size() - shown.size()));
            message(player, "submit_failed", ChatFormatting.RED, ComponentUtils.formatList(shown, Component.literal("; ")));
        }
    }

    private static boolean targetStillValid(ServerPlayer player, Session session) {
        return switch (session.target) {
            case CART -> session.lines.equals(cartLines(player));
            case COST -> canPlanPurchase(player, session.entry) && ShopConfig.getEntries().contains(session.entry);
            case STAGE -> !ShopStageConfig.get(session.stagePath).isEmpty()
                    && !hasSubmission(player, "stage:" + session.stagePath);
            case ENTRY_SUBMISSION -> canAccessEntry(player, session.entry) && ShopConfig.getEntries().contains(session.entry)
                    && session.entry.hasSubmissionRequirement() && !hasSubmission(player, "entry:" + session.stagePath);
        };
    }

    private static Snapshot snapshotForSession(ServerPlayer player, Session session, IGrid grid, IActionSource source) {
        return switch (session.target) {
            case STAGE -> snapshotIngredients(player, ShopStageConfig.get(session.stagePath), grid, source);
            case ENTRY_SUBMISSION -> snapshotIngredients(player, session.entry.getSubmissionItems(), grid, source);
            case CART -> snapshotLines(player, session.lines, grid, source);
            case COST -> snapshotLines(player, List.of(new CartLine(session.entry, session.times)), grid, source);
        };
    }

    private static boolean hasReservedStock(ServerPlayer player, Session session, Map<AEKey, BigInteger> required, IActionSource source) {
        for (var requirement : required.entrySet()) {
            if (requirement.getValue().compareTo(LONG_MAX) > 0) return false;
            long available = session.grid.getStorageService().getInventory()
                    .extract(requirement.getKey(), requirement.getValue().longValueExact(), Actionable.SIMULATE, source);
            if (BigInteger.valueOf(Math.max(0L, available)).compareTo(requirement.getValue()) < 0) {
                GTDishanhaiMod.LOGGER.info(
                        "[ShopAutoCraft] reserved stock missing player={} planId={} key={} required={} available={}",
                        player.getGameProfile().getName(), session.planId,
                        requirement.getKey(), requirement.getValue(), Math.max(0L, available));
                return false;
            }
        }
        return true;
    }

    public static void cancel(ServerPlayer player, UUID planId) {
        if (player == null) return;
        Session session = READY.get(player.getUUID());
        if (session != null && session.planId.equals(planId)) READY.remove(player.getUUID(), session);
        session = CALCULATING.get(player.getUUID());
        if (session != null && session.planId.equals(planId) && CALCULATING.remove(player.getUUID(), session)) {
            cancelSession(session);
        }
    }

    private static void cancelAll(UUID playerId) {
        Session session = CALCULATING.remove(playerId);
        if (session != null) {
            cancelSession(session);
            sendPlanFeedback(findPlayer(playerId), session.requestId, false);
        }
        READY.remove(playerId);
    }

    private static void cancelSession(Session session) {
        session.planningStock = null;
        for (PlanItem item : session.items) {
            if (item.future != null && !item.future.isDone()) item.future.cancel(true);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        cancelAll(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CALCULATING.values().forEach(ShopAutoCraft::cancelSession);
        CALCULATING.clear();
        READY.clear();
    }

    private static void message(ServerPlayer player, String key, ChatFormatting color, Object... args) {
        player.sendSystemMessage(label("shop_message", label(key, args).withStyle(color)));
    }

    private static net.minecraft.network.chat.MutableComponent label(String key, Object... args) {
        return Component.translatable("gt_shanhai.shop.confirmation.auto_craft." + key, args);
    }

    private static ServerPlayer findPlayer(UUID uuid) {
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : server.getPlayerList().getPlayer(uuid);
    }
}
