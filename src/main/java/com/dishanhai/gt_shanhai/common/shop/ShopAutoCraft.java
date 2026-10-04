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
import net.minecraft.network.chat.Component;
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

    private static final class PlanItem {
        final AEKey key;
        final long amount;
        Future<ICraftingPlan> future;
        ICraftingPlan result;
        String error;

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
        final Map<AEKey, BigInteger> shortages;
        final Map<AEKey, BigInteger> retained;
        final Map<AEKey, BigInteger> reserved = new LinkedHashMap<>();
        final List<PlanItem> items = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        int nextItem;
        long ticks;

        Session(IGrid grid, ShopEntry entry, long times, Snapshot snapshot) {
            this.grid = grid;
            this.entry = entry;
            this.times = times;
            this.shortages = snapshot.shortages;
            this.retained = snapshot.retained;
            this.reserved.putAll(retained);
        }
    }

    public static void beginPlan(ServerPlayer player, ShopEntry entry, long times, boolean aeMode) {
        if (player == null || entry == null || !entry.isValid()) return;
        if (!aeMode) {
            message(player, "§c請先開啟「AE模式」");
            return;
        }
        IGrid grid = ShopAeNetwork.findBoundGrid(player);
        if (grid == null) {
            message(player, "§c未綁定在線 AE 網路");
            return;
        }
        times = Math.max(1L, times);
        Session existing = CALCULATING.get(player.getUUID());
        if (existing == null) existing = READY.get(player.getUUID());
        if (existing != null && existing.entry == entry && existing.times == times && existing.grid == grid) {
            if (READY.get(player.getUUID()) == existing) sendPlanToClient(player, existing);
            return;
        }
        cancelAll(player.getUUID());
        Session session = new Session(grid, entry, times, snapshot(player, entry, times, grid));
        for (Map.Entry<AEKey, BigInteger> shortage : session.shortages.entrySet()) {
            AEKey key = shortage.getKey();
            String name = key.getDisplayName().getString();
            if (shortage.getValue().compareTo(LONG_MAX) > 0) {
                session.notes.add("§c" + name + " 缺口超出 AE 單次數量上限，請減少購買次數");
            } else if (!grid.getCraftingService().isCraftable(key)) {
                session.notes.add("§7✗ " + name + " §c無可用合成流程");
            } else if (session.items.size() >= MAX_PLAN_ITEMS) {
                session.notes.add("§c目標種類過多，請拆分下單");
                break;
            } else {
                session.items.add(new PlanItem(key, shortage.getValue().longValueExact()));
            }
        }
        if (session.items.isEmpty()) {
            if (session.notes.isEmpty()) {
                message(player, "§a目前沒有未下單的缺口（已扣除 AE 在製成品）");
            } else {
                READY.put(player.getUUID(), session);
                sendPlanToClient(player, session);
            }
            return;
        }
        CALCULATING.put(player.getUUID(), session);
        startNextCalculation(player, session);
        message(player, "§7正在計算合成方案（" + session.items.size() + " 項）");
    }

    private static Snapshot snapshot(ServerPlayer player, ShopEntry entry, long times, IGrid grid) {
        ShopCost cost = entry.getEffectiveCost(ShopMembership.discountPercent(player.getServer(), player.getUUID()));
        BigInteger multiplier = BigInteger.valueOf(times);
        Map<AEKey, BigInteger> demand = new LinkedHashMap<>();
        for (var coin : cost.coins.entrySet()) {
            var item = ForgeRegistries.ITEMS.getValue(coin.getKey());
            if (item == null) continue;
            AEItemKey key = AEItemKey.of(new ItemStack(item));
            ShopAutoCraftAmounts.add(demand, key, coin.getValue().multiply(multiplier));
            // 錢包餘額只能抵銷幣種成本，不能抵銷同種物品的實物成本。
            ShopAutoCraftAmounts.consume(demand, key,
                    WalletAccountAPI.getCurrency(player.getServer(), player.getUUID(), coin.getKey()));
        }
        for (ExchangeEntry.Ingredient ingredient : cost.physical) {
            AEKey key;
            if (ingredient.isFluid) {
                var fluid = ForgeRegistries.FLUIDS.getValue(ingredient.id);
                key = fluid == null ? null : AEFluidKey.of(fluid);
            } else {
                key = AEItemKey.of(ingredient.makeUnitStack());
            }
            ShopAutoCraftAmounts.add(demand, key, BigInteger.valueOf(ingredient.count).multiply(multiplier));
        }
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
        IActionSource source = source(player);
        for (AEKey key : demand.keySet()) {
            long available = grid.getStorageService().getInventory()
                    .extract(key, Long.MAX_VALUE, Actionable.SIMULATE, source);
            ShopAutoCraftAmounts.add(stock, key, BigInteger.valueOf(Math.max(0L, available)));
        }
        Map<AEKey, BigInteger> pending = new LinkedHashMap<>();
        // 在途的中間物不是承諾交付的成品；只統計 CPU 尚未交付的最終產出。
        for (var cpu : grid.getCraftingService().getCpus()) {
            var status = cpu.getJobStatus();
            if (status == null || status.crafting() == null) continue;
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
        IActionSource source = new ShopAutoCraftActionSource(source(player), reservations);
        try {
            item.future = session.grid.getCraftingService().beginCraftingCalculation(
                    player.level(), () -> source, item.key, item.amount, CalculationStrategy.REPORT_MISSING_ITEMS);
        } catch (RuntimeException exception) {
            item.error = "計算啟動失敗";
            GTDishanhaiMod.LOGGER.warn("Shop crafting calculation could not start", exception);
        }
        session.ticks = 0L;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (Map.Entry<UUID, Session> entry : READY.entrySet()) {
            if (++entry.getValue().ticks > READY_TIMEOUT_TICKS) READY.remove(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<UUID, Session> entry : CALCULATING.entrySet()) {
            UUID uuid = entry.getKey();
            Session session = entry.getValue();
            ServerPlayer player = findPlayer(uuid);
            if (player == null || ShopAeNetwork.findBoundGrid(player) != session.grid) {
                if (CALCULATING.remove(uuid, session)) cancelSession(session);
                continue;
            }
            PlanItem item = session.items.get(session.nextItem);
            session.ticks++;
            if (item.future != null && !item.future.isDone()) {
                if (session.ticks > CALC_TIMEOUT_TICKS && CALCULATING.remove(uuid, session)) {
                    cancelSession(session);
                    message(player, "§c合成方案計算逾時，已取消");
                }
                continue;
            }
            if (item.future != null) {
                try {
                    item.result = item.future.get();
                    if (item.result == null || item.result.finalOutput() == null
                            || !item.key.equals(item.result.finalOutput().what())
                            || item.result.finalOutput().amount() != item.amount
                            || !CraftingPlanOverflowDetector.collectOverflowKeys(item.result).isEmpty()) {
                        item.result = null;
                        item.error = "計算數量不可信，請減少購買次數";
                    }
                } catch (Exception exception) {
                    item.error = "計算失敗";
                    GTDishanhaiMod.LOGGER.warn("Shop crafting calculation failed", exception);
                }
            }
            if (submittable(item)) {
                realUsedItems(item.result).forEach((key, amount) ->
                        ShopAutoCraftAmounts.add(session.reserved, key, amount));
            }
            session.nextItem++;
            if (session.nextItem < session.items.size()) {
                startNextCalculation(player, session);
                continue;
            }
            if (!CALCULATING.remove(uuid, session)) continue;
            session.ticks = 0L;
            READY.put(uuid, session);
            sendPlanToClient(player, session);
        }
    }

    private static boolean submittable(PlanItem item) {
        return item.result != null && item.error == null && !item.result.simulation();
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
        List<String> notes = new ArrayList<>(session.notes);
        List<String> willCraft = new ArrayList<>();
        Map<AEKey, BigInteger> merged = new LinkedHashMap<>();
        for (PlanItem item : session.items) {
            String name = item.key.getDisplayName().getString();
            if (submittable(item)) {
                willCraft.add("§b" + name + " §7×" + item.amount);
                realUsedItems(item.result).forEach((key, amount) -> ShopAutoCraftAmounts.add(merged, key, amount));
            } else if (item.result != null && item.result.simulation()) {
                notes.add("§c" + name + " 基礎材料不足，未下單");
                int shown = 0;
                for (var missing : item.result.missingItems()) {
                    if (shown++ >= 6) { notes.add("§7其餘缺料已省略"); break; }
                    notes.add("§7  " + missing.getKey().getDisplayName().getString() + " ×" + missing.getLongValue());
                }
            } else {
                notes.add("§c" + name + " " + (item.error == null ? "計算失敗" : item.error));
            }
        }
        List<String> useLines = new ArrayList<>();
        if (!willCraft.isEmpty()) {
            useLines.add("§b將會補齊（" + willCraft.size() + " 項任務）");
            useLines.addAll(willCraft);
            useLines.add("§a將會消耗");
        }
        merged.forEach((key, amount) -> useLines.add("§a" + key.getDisplayName().getString() + " §7×" + amount));
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ShopAutoCraftPlanPacket(session.planId, !willCraft.isEmpty(), useLines, notes));
    }

    public static void confirmPlan(ServerPlayer player, UUID planId) {
        if (player == null) return;
        Session session = READY.get(player.getUUID());
        if (session == null || !session.planId.equals(planId)) return;
        // 先消費這份方案；重複確認、舊視窗確認均不能提交第二次。
        if (!READY.remove(player.getUUID(), session)) return;
        IGrid grid = ShopAeNetwork.findBoundGrid(player);
        if (session.grid != grid || !ShopConfig.getEntries().contains(session.entry)) {
            message(player, "§cAE 網路或商品已改變，請重新計算");
            return;
        }
        Snapshot current = snapshot(player, session.entry, session.times, grid);
        boolean shortagesChanged = !current.shortages.equals(session.shortages);
        boolean retainedChanged = !current.retained.equals(session.retained);
        boolean reservedStockAvailable = hasReservedStock(player, session);
        if (shortagesChanged || retainedChanged || !reservedStockAvailable) {
            GTDishanhaiMod.LOGGER.info(
                    "[ShopAutoCraft] plan invalidated player={} planId={} shortagesChanged={} retainedChanged={} reservedStockAvailable={}",
                    player.getGameProfile().getName(), planId,
                    shortagesChanged, retainedChanged, reservedStockAvailable);
            message(player, "§e庫存、價格或在製數量已改變，正在重新計算，請再次確認");
            beginPlan(player, session.entry, session.times, true);
            return;
        }
        int submitted = 0;
        List<String> failures = new ArrayList<>();
        IActionSource source = source(player);
        // 計算時各計畫已預留互不重複的實物；按同一順序提交，不再擴張或追加依賴單。
        for (PlanItem item : session.items) {
            if (!submittable(item)) continue;
            try {
                var result = grid.getCraftingService().submitJob(item.result, null, null, true, source);
                if (result.successful()) submitted++;
                else failures.add(item.key.getDisplayName().getString() + "(" + result.errorCode() + ")");
            } catch (RuntimeException exception) {
                failures.add(item.key.getDisplayName().getString());
                GTDishanhaiMod.LOGGER.warn("Shop crafting submission failed", exception);
            }
        }
        if (submitted > 0) message(player, "§a已提交 " + submitted + " 項合成任務到 AE 網路");
        if (!failures.isEmpty()) message(player, "§c提交失敗：" + String.join("、", failures));
    }

    private static boolean hasReservedStock(ServerPlayer player, Session session) {
        IActionSource source = source(player);
        for (var requirement : session.reserved.entrySet()) {
            long available = session.grid.getStorageService().getInventory()
                    .extract(requirement.getKey(), Long.MAX_VALUE, Actionable.SIMULATE, source);
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
        if (session != null) cancelSession(session);
        READY.remove(playerId);
    }

    private static void cancelSession(Session session) {
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

    private static void message(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal("§b[山海商店] " + text));
    }

    private static ServerPlayer findPlayer(UUID uuid) {
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : server.getPlayerList().getPlayer(uuid);
    }
}
