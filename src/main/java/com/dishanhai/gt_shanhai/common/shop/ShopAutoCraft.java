package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopAutoCraftPlanPacket;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * 花费预览「一键下单缺口」（山海署名）：把花费预览格里当前不够的币种/物品/流体缺口，
 * 交给玩家绑定的在线 AE 网络（{@link ShopAeNetwork}）自动合成补齐——产出直接进 AE 网络，
 * 下次花费预览刷新自然显示为够。
 *
 * <p>流程仿 AE2 自己的 ME 终端自动合成确认框（{@code CraftAmountMenu}→{@code CraftConfirmMenu}）：
 * {@link #beginPlan} 先对每个缺口 {@link ICraftingService#isCraftable} 预检查有没有可用样板
 * （没有样板的直接跳过，绝不盲目起算），有样板的才起异步计算（{@code beginCraftingCalculation}
 * 返回 {@link Future}，AE2 自己在合成线程池跑，不阻塞主线程）。{@link #onServerTick} 每 tick 轮询，
 * 全部算完后把用料预览推给客户端确认，玩家确认后 {@link #confirmPlan} 才真正 {@code submitJob}。</p>
 *
 * <p>只缓存"每玩家最新一次"的计算/待确认会话——重新点「补齐全部缺口」直接顶掉旧会话（取消旧 Future），
 * 不做请求 ID 校验，简单直接，符合这个功能本身"一键"的定位。</p>
 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShopAutoCraft {

    private ShopAutoCraft() {}

    private static final long CALC_TIMEOUT_TICKS = 400L; // 20 秒：AE2 合成计算超这个还没完，大概率卡了或树太大

    /** 单个缺口目标的计算过程；noPattern=true 时 future 恒为 null（预检查就没样板，从未起算）。 */
    private static final class PlanItem {
        final AEKey key;
        final long amount;
        final String displayName;
        final boolean noPattern;
        Future<ICraftingPlan> future;
        ICraftingPlan result;

        PlanItem(AEKey key, long amount, String displayName, boolean noPattern) {
            this.key = key;
            this.amount = amount;
            this.displayName = displayName;
            this.noPattern = noPattern;
        }
    }

    private static final class Session {
        final UUID playerId;
        final List<PlanItem> items;   // 有样板、已起算的项（等待/完成 Future）
        final List<String> skipped;   // 预检查无样板的提示行（已带 §7/§c 颜色码）
        long ticksWaited = 0L;

        Session(UUID playerId, List<PlanItem> items, List<String> skipped) {
            this.playerId = playerId;
            this.items = items;
            this.skipped = skipped;
        }
    }

    // 计算中（等待 Future 完成）与待确认（Future 已完成，等玩家点确认）两阶段各一张表，同玩家新会话直接顶掉旧的。
    private static final Map<UUID, Session> CALCULATING = new ConcurrentHashMap<>();
    private static final Map<UUID, Session> READY = new ConcurrentHashMap<>();

    /** 花费预览「补齐全部缺口」按钮：对选中商品当前所有不够的成本项起一轮合成计算。 */
    public static void beginPlan(ServerPlayer player, ShopEntry entry, long times, boolean aeMode) {
        if (player == null || entry == null || !entry.isValid()) return;
        if (!aeMode) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 请先开启顶栏「AE模式」再一键下单"));
            return;
        }
        IGrid grid = ShopAeNetwork.findBoundGrid(player);
        if (grid == null) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 未绑定在线 AE 网络（需要提交器或商店终端）"));
            return;
        }
        ICraftingService craftingService = grid.getCraftingService();
        ShopCost cost = entry.getCost();
        ShopPurchase.CostPreview have = ShopPurchase.previewHave(player, cost, aeMode);
        BigInteger t = BigInteger.valueOf(Math.max(1L, times));

        List<PlanItem> items = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (Map.Entry<ResourceLocation, BigInteger> c : cost.coins.entrySet()) {
            BigInteger need = c.getValue().multiply(t);
            BigInteger got = have.coins().getOrDefault(c.getKey(), BigInteger.ZERO);
            if (got.compareTo(need) >= 0) continue;
            Item coin = ForgeRegistries.ITEMS.getValue(c.getKey());
            if (coin == null) continue;
            AEItemKey key = AEItemKey.of(new ItemStack(coin));
            if (key == null) continue;
            addPlanItem(items, skipped, craftingService, key, clampToLong(need.subtract(got)), ShopPurchase.coinName(c.getKey()));
        }
        List<ExchangeEntry.Ingredient> itemIns = cost.items();
        for (int i = 0; i < itemIns.size(); i++) {
            ExchangeEntry.Ingredient in = itemIns.get(i);
            BigInteger need = BigInteger.valueOf(in.count).multiply(t);
            Long gotL = i < have.items().size() ? have.items().get(i) : null;
            BigInteger got = BigInteger.valueOf(gotL == null ? 0L : gotL);
            if (got.compareTo(need) >= 0) continue;
            AEItemKey key = AEItemKey.of(in.makeUnitStack());
            if (key == null) continue;
            addPlanItem(items, skipped, craftingService, key, clampToLong(need.subtract(got)), in.makeUnitStack().getHoverName().getString());
        }
        List<ExchangeEntry.Ingredient> fluidIns = cost.fluids();
        for (int i = 0; i < fluidIns.size(); i++) {
            ExchangeEntry.Ingredient in = fluidIns.get(i);
            BigInteger need = BigInteger.valueOf(in.count).multiply(t);
            Long gotL = i < have.fluids().size() ? have.fluids().get(i) : null;
            BigInteger got = BigInteger.valueOf(gotL == null ? 0L : gotL);
            if (got.compareTo(need) >= 0) continue;
            Fluid fluid = ForgeRegistries.FLUIDS.getValue(in.id);
            if (fluid == null) continue;
            AEFluidKey key = AEFluidKey.of(fluid);
            if (key == null) continue;
            addPlanItem(items, skipped, craftingService, key, clampToLong(need.subtract(got)), key.getDisplayName().getString());
        }

        if (items.isEmpty()) {
            if (!skipped.isEmpty()) {
                // 全部缺口都没样板：也要走确认框列出来（哪些没样板一目了然），不能只甩一句聊天提示就完事——
                // 玩家没法从一句"无法一键下单"知道到底是哪几项、要去补哪条合成流程（见反馈）。
                ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new ShopAutoCraftPlanPacket(false, List.of(), skipped));
            } else {
                player.sendSystemMessage(Component.literal("§b[山海商店] §a当前没有缺口，无需补齐"));
            }
            return;
        }

        // 临时诊断：打开 AE2 自己的合成计算调试日志（appeng.core.AELog#craftingLogEnabled），排查"样板明明存在
        // 却被判定整批缺失"这个问题——只影响日志输出，不改配置文件，不需要重启，问题定位完就应该删掉这行。
        appeng.core.AELog.setCraftingLogEnabled(true);
        var level = player.level();
        // 实测确认：光绑玩家身份（IActionSource.ofPlayer(player)，不带机器）还是会把目标报成"自己缺自己"。
        // AE2 原生 ME 终端（CraftConfirmMenu）用的是 PlayerSource(player, actionHost)——机器身份也带上了；
        // 本模组的虚拟供给改写层（VirtualPatternEncodingHelper）很可能就是靠这个机器身份判断"请求算不算数"，
        // 缺了它会被当成看不见样板。这里改成带上绑定该玩家的商店终端/FTBQ提交器本身作为机器身份。
        IActionSource src = IActionSource.ofPlayer(player, ShopAeNetwork.findBoundHost(player));
        for (PlanItem pi : items) {
            pi.future = craftingService.beginCraftingCalculation(level, () -> src, pi.key, pi.amount, CalculationStrategy.REPORT_MISSING_ITEMS);
        }
        UUID uuid = player.getUUID();
        Session prior = CALCULATING.remove(uuid);
        if (prior != null) cancelSession(prior);
        READY.remove(uuid);
        CALCULATING.put(uuid, new Session(uuid, items, skipped));
        player.sendSystemMessage(Component.literal("§b[山海商店] §7正在计算合成方案（" + items.size() + " 项）…"));
    }

    /** 预检查样板可用性；没有样板的记进 skipped 提示行，绝不盲目起算一个注定失败的合成任务。 */
    private static void addPlanItem(List<PlanItem> items, List<String> skipped, ICraftingService craftingService,
                                      AEKey key, long amount, String displayName) {
        if (amount <= 0L) return;
        if (!craftingService.isCraftable(key)) {
            skipped.add("§7✗ " + displayName + " §c无可用合成流程");
            return;
        }
        items.add(new PlanItem(key, amount, displayName, false));
    }

    private static long clampToLong(BigInteger v) {
        if (v.signum() <= 0) return 0L;
        return v.bitLength() < 63 ? v.longValue() : Long.MAX_VALUE;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || CALCULATING.isEmpty()) return;
        for (UUID uuid : new ArrayList<>(CALCULATING.keySet())) {
            Session session = CALCULATING.get(uuid);
            if (session == null) continue;
            session.ticksWaited++;
            boolean allDone = true;
            for (PlanItem pi : session.items) {
                if (!pi.future.isDone()) { allDone = false; break; }
            }
            if (!allDone) {
                if (session.ticksWaited > CALC_TIMEOUT_TICKS) {
                    CALCULATING.remove(uuid);
                    cancelSession(session);
                    ServerPlayer player = findPlayer(uuid);
                    if (player != null) player.sendSystemMessage(Component.literal("§c[山海商店] 合成方案计算超时，已取消"));
                }
                continue;
            }
            CALCULATING.remove(uuid);
            for (PlanItem pi : session.items) {
                try {
                    pi.result = pi.future.get();
                } catch (Exception e) {
                    pi.result = null;
                }
            }
            READY.put(uuid, session);
            ServerPlayer player = findPlayer(uuid);
            if (player != null) sendPlanToClient(player, session);
        }
    }

    private static void sendPlanToClient(ServerPlayer player, Session session) {
        List<String> lines = new ArrayList<>(session.skipped);
        Map<String, Long> merged = new java.util.LinkedHashMap<>();
        boolean anySubmittable = false;
        for (PlanItem pi : session.items) {
            if (pi.result == null) {
                lines.add("§7✗ " + pi.displayName + " §c计算失败");
                continue;
            }
            if (pi.result.simulation()) {
                StringBuilder missing = new StringBuilder();
                for (var e : pi.result.missingItems()) {
                    if (missing.length() > 0) missing.append("§7, ");
                    missing.append(e.getKey().getDisplayName().getString()).append(" §7×").append(e.getLongValue());
                }
                lines.add("§7⚠ " + pi.displayName + " §c基础材料不足，还缺: §f" + missing);
                continue;
            }
            anySubmittable = true;
            for (var e : pi.result.usedItems()) {
                String name = e.getKey().getDisplayName().getString();
                merged.merge(name, e.getLongValue(), Long::sum);
            }
        }
        List<String> useLines = new ArrayList<>();
        for (Map.Entry<String, Long> e : merged.entrySet()) {
            useLines.add("§a" + e.getKey() + " §7×" + e.getValue());
        }
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ShopAutoCraftPlanPacket(anySubmittable, useLines, lines));
    }

    /** 花费预览确认框「确认合成」：把 READY 里已算好的可提交项真正 submitJob。 */
    public static void confirmPlan(ServerPlayer player) {
        if (player == null) return;
        Session session = READY.remove(player.getUUID());
        if (session == null) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 没有待确认的合成方案，请重新点击「补齐全部缺口」"));
            return;
        }
        IGrid grid = ShopAeNetwork.findBoundGrid(player);
        if (grid == null) {
            player.sendSystemMessage(Component.literal("§c[山海商店] AE 网络已离线，合成方案已失效"));
            return;
        }
        ICraftingService craftingService = grid.getCraftingService();
        IActionSource src = IActionSource.ofPlayer(player, ShopAeNetwork.findBoundHost(player)); // 带机器身份，跟 beginPlan 一致
        int submitted = 0, failed = 0, skipped = 0;
        List<String> failMsgs = new ArrayList<>();
        for (PlanItem pi : session.items) {
            if (pi.result == null || pi.result.simulation()) { skipped++; continue; }
            ICraftingSubmitResult r = craftingService.submitJob(pi.result, null, null, true, src);
            if (r.successful()) {
                submitted++;
            } else {
                failed++;
                failMsgs.add(pi.displayName + "§7(" + r.errorCode() + ")");
            }
        }
        if (submitted > 0) {
            player.sendSystemMessage(Component.literal("§b[山海商店] §a已提交 §f" + submitted + " §a项合成任务到 AE 网络"));
        }
        if (failed > 0) {
            player.sendSystemMessage(Component.literal("§c[山海商店] " + failed + " 项提交失败: §7" + String.join("、", failMsgs)));
        }
        if (skipped > 0) {
            player.sendSystemMessage(Component.literal("§7[山海商店] " + skipped + " 项材料不足/计算失败已跳过（需要手动补充基础材料）"));
        }
    }

    /** 花费预览关闭/取消确认框：丢弃待确认会话，取消其中仍未完成的 Future。 */
    public static void cancel(ServerPlayer player) {
        if (player == null) return;
        Session c = CALCULATING.remove(player.getUUID());
        if (c != null) cancelSession(c);
        READY.remove(player.getUUID());
    }

    private static void cancelSession(Session session) {
        for (PlanItem pi : session.items) {
            if (pi.future != null && !pi.future.isDone()) pi.future.cancel(true);
        }
    }

    private static ServerPlayer findPlayer(UUID uuid) {
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : server.getPlayerList().getPlayer(uuid);
    }
}
