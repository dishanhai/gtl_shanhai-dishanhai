package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.math.BigInteger;
import java.util.Set;
import java.util.UUID;

/**
 * 商店购买 / 充值结算。全部逻辑只在服务端执行。
 *
 * <p>货币采用<b>虚拟余额</b>模式：余额存于服务端账户 {@link WalletAccountSavedData}（按玩家本体 UUID，
 * BigInteger），购买从账户扣款，不再逐次扣背包实体币。玩家通过“充值/提交”把背包里的实体币吸入账户。
 * 每次结算后由调用方 {@link WalletAccountAPI#sync(ServerPlayer)} 向客户端推快照。</p>
 *
 * <p>余额已从钱包 ItemStack NBT 迁出（旧 {@code WalletCurrency} 弃用），钱跟人不跟物。</p>
 */
public final class ShopPurchase {

    private ShopPurchase() {}

    public enum Result {
        SUCCESS,
        NOT_ENOUGH_CURRENCY,
        INVALID_ENTRY,
        NOT_ENOUGH_GOODS
    }

    /**
     * 奖励表模式（CHOICE/RANDOM/ALL/FTBQ）单次购买最多独立随机的次数（可配置，见 config 「shop.rewardRollCap」；
     * 超出部分静默夹到此值，购买次数本身不受限，分次购买即可）。每次交付数量都要独立开一次随机数（仿开箱），
     * 全程在服务端主线程同步跑完——调大这个值有让主线程长时间卡死甚至触发看门狗崩服的风险，见配置项注释。
     */
    public static long rewardRollCap() {
        long v = com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.shopRewardRollCap.get();
        return v > 0L ? v : 5000L; // 配置异常兜底，不至于变成 0/负数导致每次都抽不到
    }

    /** 出售回收价占买价的百分比（价差，见 config「shop.sellRatioPercent」），配置异常兜底 70。 */
    public static int sellRatioPercent() {
        int v = com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.shopSellRatioPercent.get();
        return v >= 1 && v <= 100 ? v : 70;
    }

    /** FTBQ 抽取次数夹到 int（FTBQ 自身 {@code generateWeightedRandomRewards} 的 nAttempts 是 int 参数，硬限制）。 */
    private static int clampFtbqRolls(long v) {
        return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
    }

    /** 按权重加权随机抽 1 项（权重全非正时退化为等概率）。 */
    private static ShopEntry.RewardOption pickWeighted(java.util.List<ShopEntry.RewardOption> pool) {
        int total = 0;
        for (ShopEntry.RewardOption o : pool) total += o.weight();
        if (total <= 0) return pool.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.size()));
        int r = java.util.concurrent.ThreadLocalRandom.current().nextInt(total);
        int acc = 0;
        for (ShopEntry.RewardOption o : pool) {
            acc += o.weight();
            if (r < acc) return o;
        }
        return pool.get(pool.size() - 1);
    }

    /**
     * 充值：把背包里所有“被商店接受的货币”实体币按 1:1 吸入账户余额。仅服务端调用。
     * @return 本次充值吸入的币总枚数（0 表示背包没有可充值的币）
     */
    public static long deposit(ServerPlayer player) {
        if (player == null) return 0L;
        MinecraftServer server = player.getServer();
        if (server == null) return 0L;
        Set<ResourceLocation> accepted = ShopConfig.getAcceptedCurrencies();
        if (accepted.isEmpty()) return 0L;
        UUID uuid = player.getUUID();
        long total = 0L;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(s.getItem());
            if (id == null || !accepted.contains(id)) continue;
            int count = s.getCount();
            WalletAccountAPI.addCurrency(server, uuid, id, BigInteger.valueOf(count));
            total += count;
            s.setCount(0); // 实体币被吸入账户
        }
        return total;
    }

    /** 某货币 ID 的可读短名（星火 → ★星火，否则优先物品显示名，回退到路径）。 */
    public static String coinName(ResourceLocation currency) {
        if (WalletAccountAPI.isSpark(currency)) return "★星火";
        Item item = ForgeRegistries.ITEMS.getValue(currency);
        if (item != null) {
            return new ItemStack(item).getHoverName().getString();
        }
        return currency.getPath();
    }

    /** 统计玩家背包内某物品总数（不区分 NBT）。 */
    public static int countItem(Player player, Item item) {
        return countItem(player, item, null);
    }

    /**
     * 统计玩家背包内某物品总数；nbt 非空时额外要求 NBT 精确匹配（同「无限盘」这类靠 NBT 区分实例的物品，
     * 见 {@link ExchangeEntry.Ingredient}）。nbt 为空则退化成旧的仅按物品 ID 统计。
     */
    public static int countItem(Player player, Item item, net.minecraft.nbt.CompoundTag nbt) {
        int total = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !s.is(item)) continue;
            if (nbt != null && !nbtMatches(s, nbt)) continue;
            total += s.getCount();
        }
        return total;
    }

    /** 背包物品 NBT 是否与 required 精确一致（CompoundTag 结构相等，与键插入顺序无关）。 */
    private static boolean nbtMatches(ItemStack stack, net.minecraft.nbt.CompoundTag required) {
        return java.util.Objects.equals(stack.getTag(), required);
    }

    /** 从背包移除指定数量的物品（假定已确认足够，不区分 NBT）。 */
    public static void removeItems(Player player, Item item, int amount) {
        removeItems(player, item, amount, null);
    }

    /** 从背包移除指定数量的物品（假定已确认足够）；nbt 非空时只移除 NBT 精确匹配的堆叠。 */
    public static void removeItems(Player player, Item item, int amount, net.minecraft.nbt.CompoundTag nbt) {
        int remaining = amount;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !s.is(item)) continue;
            if (nbt != null && !nbtMatches(s, nbt)) continue;
            int take = Math.min(remaining, s.getCount());
            s.shrink(take);
            remaining -= take;
        }
    }

    /**
     * 从随身背包 + 精妙背包（{@link ShopBackpack}）合计扣 amount，不碰 AE；backpackMode 决定先扣哪边——
     * 关闭时随身背包优先（原有顺序），开启时精妙背包优先。返回两边加起来还是不够扣的余量，交给调用方走 AE 兜底。
     */
    private static long deductFromCarried(ServerPlayer player, Item item, net.minecraft.nbt.CompoundTag nbt, long amount, boolean backpackMode) {
        if (amount <= 0L) return 0L;
        long remaining = amount;
        if (backpackMode) {
            remaining = deductBackpack(player, item, nbt, remaining);
            remaining = deductInventory(player, item, nbt, remaining);
        } else {
            remaining = deductInventory(player, item, nbt, remaining);
            remaining = deductBackpack(player, item, nbt, remaining);
        }
        return remaining;
    }

    private static long deductInventory(ServerPlayer player, Item item, net.minecraft.nbt.CompoundTag nbt, long amount) {
        if (amount <= 0L) return 0L;
        long take = Math.min(amount, countItem(player, item, nbt));
        if (take > 0) removeItems(player, item, (int) take, nbt);
        return amount - take;
    }

    private static long deductBackpack(ServerPlayer player, Item item, net.minecraft.nbt.CompoundTag nbt, long amount) {
        if (amount <= 0L) return 0L;
        long take = Math.min(amount, ShopBackpack.countItem(player, item, nbt));
        if (take > 0) ShopBackpack.removeItems(player, item, take, nbt);
        return amount - take;
    }

    /** 大数字紧凑显示（聊天提示用）：1234567 → "1,234,567"（保留精确值，加千分位）。 */
    public static String formatCount(long n) {
        return String.format(java.util.Locale.ROOT, "%,d", n);
    }

    public static String formatCount(java.math.BigInteger n) {
        if (n == null) return "0";
        return String.format(java.util.Locale.ROOT, "%,d", n);
    }

    // ==================== 批量购买 / 分层交付（AE注入 / SDA打包 / 背包）====================

    /** 批量购买结果：实际成交次数 done、请求次数 requested、主交付方式 via（"ae"/"sda"/"inventory"/null=没买成）。 */
    public record BulkBuyResult(long done, long requested, String via) {}

    /**
     * 批量购买。一次性按"买得起多少"结算，绝不逐次循环（防 Long.MAX 卡死服务器）。
     * 交付分层（防吞币不吞货，顺序：算 affordable → 定交付方式 → 扣款 → 交付）：
     * aeMode 且有绑定在线 AE 网络（提交器/商店终端，见 {@link ShopAeNetwork}）能全额收下 → 注入 AE 网络（SIMULATE→MODULATE）
     * 否则货物总量 ≥ 配置阈值 → 打包成超级磁盘阵列赠送
     * 否则进背包，背包也塞不下的余量再尝试塞进随身穿戴的精妙背包（见 {@link ShopBackpack}），最终还剩的打包成 SDA（阈值设过大也不会卡死）
     */
    public static BulkBuyResult buyBulk(ServerPlayer player, ShopEntry entry, long times, boolean aeMode, boolean backpackMode) {
        long capped = capByFluidGoodsCapacity(player, entry, times);
        if (capped <= 0L) return new BulkBuyResult(0L, times, null);
        long affordable = affordAndDeduct(player, entry, capped, backpackMode);
        if (affordable <= 0L) return new BulkBuyResult(0L, times, null);
        String via = deliverGoodsList(player, entry, affordable, aeMode, backpackMode);
        return new BulkBuyResult(affordable, times, via);
    }

    /**
     * 商品清单里流体部分按当前绑定 AE 网络的可注入余量把 times 降量（只会调小，不会调大）——流体没有背包/
     * 精妙背包容器可落地，网络余量不够全额时按余量降量成交，而不是整单卡死不能买；无流体商品/网络余量充足
     * 原样返回 times。流体本身缺失（对应模组被卸载）直接判 0，整单不可交付。调用方须确保开启 AE 模式才走到
     * 这里（{@link ShopEntry#hasFluidGoods} 非空必须 aeMode=true，见 ShopActionPacket#doBuy 的前置拦截）。
     */
    private static long capByFluidGoodsCapacity(ServerPlayer player, ShopEntry entry, long times) {
        if (times <= 0L || entry == null || !entry.hasFluidGoods()) return times;
        long cap = times;
        for (ShopEntry.GoodsStack gs : entry.getGoodsList()) {
            if (!gs.isFluid()) continue;
            net.minecraft.world.level.material.Fluid fluid = gs.fluid();
            if (fluid == net.minecraft.world.level.material.Fluids.EMPTY) return 0L;
            appeng.api.stacks.AEFluidKey key = appeng.api.stacks.AEFluidKey.of(fluid);
            long capacity = key == null ? 0L : ShopAeNetwork.simulateInjectCapacityForPlayer(player, key);
            long capTimes = gs.count() <= 0 ? 0L : capacity / gs.count();
            cap = Math.min(cap, capTimes);
            if (cap <= 0L) return 0L;
        }
        return cap;
    }

    /**
     * 按商品清单逐项分层交付（单商品/组合商品统一走这条路，与 {@link #buyBulkAll} 相同的批量交付基建）：
     * 每项各自 count × times，物品聚合后一次 {@link #deliverItemBatch} 调用，需要打包的部分合并进同一片 SDA；
     * 流体项直接注入绑定 AE 网络（{@link #capByFluidGoodsCapacity} 已保证不会超额，这里 SIMULATE→MODULATE 兜底防吞）。
     */
    private static String deliverGoodsList(ServerPlayer player, ShopEntry entry, long times, boolean aeMode, boolean backpackMode) {
        java.math.BigInteger t = java.math.BigInteger.valueOf(times);
        java.util.List<ItemDelivery> deliveries = new java.util.ArrayList<>(entry.getGoodsList().size());
        boolean fluidDelivered = false;
        for (ShopEntry.GoodsStack gs : entry.getGoodsList()) {
            if (gs.isFluid()) {
                net.minecraft.world.level.material.Fluid fluid = gs.fluid();
                if (fluid == net.minecraft.world.level.material.Fluids.EMPTY) continue;
                appeng.api.stacks.AEFluidKey key = appeng.api.stacks.AEFluidKey.of(fluid);
                if (key == null) continue;
                java.math.BigInteger needBig = java.math.BigInteger.valueOf(gs.count()).multiply(t);
                long need = needBig.bitLength() < 63 ? needBig.longValue() : Long.MAX_VALUE;
                if (ShopAeNetwork.injectForPlayer(player, key, need) > 0L) fluidDelivered = true;
                continue;
            }
            ItemStack unit = gs.makeStack();
            unit.setCount(1);
            deliveries.add(new ItemDelivery(unit, java.math.BigInteger.valueOf(gs.count()).multiply(t)));
        }
        String via = deliveries.isEmpty() ? null : deliverItemBatch(player, deliveries, aeMode, backpackMode);
        return via == null && fluidDelivered ? "ae" : via;
    }

    /**
     * 自选（{@link ShopEntry.RewardMode#CHOICE}）购买：只交付 chosen 这一项，每次成交都在
     * {@link ShopEntry.RewardOption#rollCount()} 的区间内独立随机取数量（仿开箱，非固定值 × 次数），
     * 其余成本结算与 {@link #buyBulk} 完全一致。chosen 须由调用方从 {@link ShopEntry#getRewardPool()}
     * 按服务端下发的索引解出（不可直接信任客户端传来的物品数据）。
     */
    public static BulkBuyResult buyBulkChoice(ServerPlayer player, ShopEntry entry, long times, boolean aeMode, boolean backpackMode, ShopEntry.RewardOption chosen) {
        if (chosen == null) return new BulkBuyResult(0L, times, null);
        long cappedTimes = Math.min(times, rewardRollCap());
        long affordable = affordAndDeduct(player, entry, cappedTimes, backpackMode);
        if (affordable <= 0L) return new BulkBuyResult(0L, times, null);
        ItemStack unit = chosen.item().copy();
        unit.setCount(1);
        java.math.BigInteger goodsTotal = rollTotal(chosen, affordable);
        String via = deliverItems(player, unit, goodsTotal, aeMode, backpackMode);
        return new BulkBuyResult(affordable, times, via);
    }

    /** 随机（{@link ShopEntry.RewardMode#RANDOM}）购买：每次成交都按权重独立抽 1 项 + 独立随机数量，聚合后按物品类型分别交付。 */
    public static BulkBuyResult buyBulkRandom(ServerPlayer player, ShopEntry entry, long times, boolean aeMode, boolean backpackMode) {
        if (entry == null) return new BulkBuyResult(0L, times, null);
        java.util.List<ShopEntry.RewardOption> pool = entry.getRewardPool();
        if (pool.isEmpty()) return new BulkBuyResult(0L, times, null);
        long cappedTimes = Math.min(times, rewardRollCap());
        long affordable = affordAndDeduct(player, entry, cappedTimes, backpackMode);
        if (affordable <= 0L) return new BulkBuyResult(0L, times, null);
        java.util.Map<ShopEntry.RewardOption, java.math.BigInteger> agg = new java.util.LinkedHashMap<>();
        for (long i = 0; i < affordable; i++) {
            ShopEntry.RewardOption picked = pickWeighted(pool);
            agg.merge(picked, java.math.BigInteger.valueOf(picked.rollCount()), java.math.BigInteger::add);
        }
        java.util.List<ItemDelivery> deliveries = new java.util.ArrayList<>();
        for (var e : agg.entrySet()) {
            ItemStack unit = e.getKey().item().copy();
            unit.setCount(1);
            deliveries.add(new ItemDelivery(unit, e.getValue()));
        }
        String via = deliverItemBatch(player, deliveries, aeMode, backpackMode);
        return new BulkBuyResult(affordable, times, via);
    }

    /** 全部（{@link ShopEntry.RewardMode#ALL}）购买：一次性交付奖励池每一项，各项每次成交都独立随机数量。 */
    public static BulkBuyResult buyBulkAll(ServerPlayer player, ShopEntry entry, long times, boolean aeMode, boolean backpackMode) {
        if (entry == null) return new BulkBuyResult(0L, times, null);
        java.util.List<ShopEntry.RewardOption> pool = entry.getRewardPool();
        if (pool.isEmpty()) return new BulkBuyResult(0L, times, null);
        long cappedTimes = Math.min(times, rewardRollCap());
        long affordable = affordAndDeduct(player, entry, cappedTimes, backpackMode);
        if (affordable <= 0L) return new BulkBuyResult(0L, times, null);
        java.util.List<ItemDelivery> deliveries = new java.util.ArrayList<>();
        for (ShopEntry.RewardOption opt : pool) {
            ItemStack unit = opt.item().copy();
            unit.setCount(1);
            deliveries.add(new ItemDelivery(unit, rollTotal(opt, affordable)));
        }
        String via = deliverItemBatch(player, deliveries, aeMode, backpackMode);
        return new BulkBuyResult(affordable, times, via);
    }

    /** 对同一奖励项独立开 rolls 次随机数量并累加（仿每次开箱各自独立结算）。 */
    private static java.math.BigInteger rollTotal(ShopEntry.RewardOption opt, long rolls) {
        java.math.BigInteger total = java.math.BigInteger.ZERO;
        for (long i = 0; i < rolls; i++) total = total.add(java.math.BigInteger.valueOf(opt.rollCount()));
        return total;
    }

    /**
     * FTBQ（{@link ShopEntry.RewardMode#FTBQ}）购买：直接读取一个 FTB Quests 奖励表，不做本地副本
     * （表改了立即生效）。加权抽取/loot_size 复用 FTBQ 自己的 {@code RewardTable.generateWeightedRandomRewards}，
     * 每份的随机数量（count + random_bonus）复用 {@code ItemReward.automatedClaimPre}，本处只负责按物品聚合后交付。
     * 只支持物品类奖励（{@code ItemReward}），表里其余奖励类型（经验/指令等）静默跳过。
     */
    public static BulkBuyResult buyBulkFtbq(ServerPlayer player, ShopEntry entry, long times, boolean aeMode, boolean backpackMode) {
        if (entry == null || !entry.hasFtbqTable()) return new BulkBuyResult(0L, times, null);
        dev.ftb.mods.ftbquests.quest.loot.RewardTable table = resolveFtbqTable(entry.getFtbqTableId());
        if (table == null) return new BulkBuyResult(0L, times, null);
        long cappedTimes = Math.min(times, rewardRollCap());
        long affordable = affordAndDeduct(player, entry, cappedTimes, backpackMode);
        if (affordable <= 0L) return new BulkBuyResult(0L, times, null);
        String via = deliverFtbqDraws(player, table, clampFtbqRolls(affordable), aeMode, backpackMode);
        return new BulkBuyResult(affordable, times, via);
    }

    /** 直接获取（作弊）+ FTBQ：不结算成本，直接从表里抽 min(times, 抽取上限) 次并交付。 */
    public static BulkBuyResult giveBulkFtbq(ServerPlayer player, ShopEntry entry, long times, boolean aeMode, boolean backpackMode) {
        if (player == null || entry == null || times <= 0L || !entry.hasFtbqTable()) {
            return new BulkBuyResult(0L, times, null);
        }
        dev.ftb.mods.ftbquests.quest.loot.RewardTable table = resolveFtbqTable(entry.getFtbqTableId());
        if (table == null) return new BulkBuyResult(0L, times, null);
        long rolls = Math.min(times, rewardRollCap());
        String via = deliverFtbqDraws(player, table, clampFtbqRolls(rolls), aeMode, backpackMode);
        return new BulkBuyResult(rolls, times, via);
    }

    /**
     * FTBQ 全部（{@link ShopEntry.RewardMode#ALL} 作为 {@link ShopEntry#getFtbqSubMode()}）购买：
     * 绕开 FTBQ 自己的加权随机，一次性交付表内每一个物品类奖励，各自独立随机数量（× 购买次数），
     * 语义对齐本地 {@link #buyBulkAll}，只是奖励来源换成表内容。
     */
    public static BulkBuyResult buyBulkFtbqAll(ServerPlayer player, ShopEntry entry, long times, boolean aeMode, boolean backpackMode) {
        if (entry == null || !entry.hasFtbqTable()) return new BulkBuyResult(0L, times, null);
        dev.ftb.mods.ftbquests.quest.loot.RewardTable table = resolveFtbqTable(entry.getFtbqTableId());
        if (table == null) return new BulkBuyResult(0L, times, null);
        long cappedTimes = Math.min(times, rewardRollCap());
        long affordable = affordAndDeduct(player, entry, cappedTimes, backpackMode);
        if (affordable <= 0L) return new BulkBuyResult(0L, times, null);
        String via = deliverFtbqAll(player, table, clampFtbqRolls(affordable), aeMode, backpackMode);
        return new BulkBuyResult(affordable, times, via);
    }

    /** 直接获取（作弊）+ FTBQ 全部：不结算成本，直接把表内每项按 min(times, 抽取上限) 次交付。 */
    public static BulkBuyResult giveBulkFtbqAll(ServerPlayer player, ShopEntry entry, long times, boolean aeMode, boolean backpackMode) {
        if (player == null || entry == null || times <= 0L || !entry.hasFtbqTable()) {
            return new BulkBuyResult(0L, times, null);
        }
        dev.ftb.mods.ftbquests.quest.loot.RewardTable table = resolveFtbqTable(entry.getFtbqTableId());
        if (table == null) return new BulkBuyResult(0L, times, null);
        long rolls = Math.min(times, rewardRollCap());
        String via = deliverFtbqAll(player, table, clampFtbqRolls(rolls), aeMode, backpackMode);
        return new BulkBuyResult(rolls, times, via);
    }

    /**
     * FTBQ 自选（{@link ShopEntry.RewardMode#CHOICE} 作为 {@link ShopEntry#getFtbqSubMode()}）购买：
     * 只交付 chosenIndex 指向的那一项物品类奖励，每次成交都独立随机数量（仿开箱，语义对齐本地
     * {@link #buyBulkChoice}）。chosenIndex 是表内「仅物品类奖励」子序列的下标（见
     * {@link #resolveFtbqItemRewardByIndex}），须由调用方从客户端选择界面传来，服务端按此重新解出
     * 实际物品（不信任客户端物品数据）。
     */
    public static BulkBuyResult buyBulkFtbqChoice(ServerPlayer player, ShopEntry entry, long times, boolean aeMode, boolean backpackMode, int chosenIndex) {
        if (entry == null || !entry.hasFtbqTable()) return new BulkBuyResult(0L, times, null);
        dev.ftb.mods.ftbquests.quest.loot.RewardTable table = resolveFtbqTable(entry.getFtbqTableId());
        if (table == null) return new BulkBuyResult(0L, times, null);
        dev.ftb.mods.ftbquests.quest.reward.ItemReward chosen = resolveFtbqItemRewardByIndex(table, chosenIndex);
        if (chosen == null) return new BulkBuyResult(0L, times, null);
        long cappedTimes = Math.min(times, rewardRollCap());
        long affordable = affordAndDeduct(player, entry, cappedTimes, backpackMode);
        if (affordable <= 0L) return new BulkBuyResult(0L, times, null);
        String via = deliverFtbqChoice(player, chosen, clampFtbqRolls(affordable), aeMode, backpackMode);
        return new BulkBuyResult(affordable, times, via);
    }

    /** 直接获取（作弊）+ FTBQ 自选：不结算成本，直接把 chosenIndex 那一项按 min(times, 抽取上限) 次交付。 */
    public static BulkBuyResult giveBulkFtbqChoice(ServerPlayer player, ShopEntry entry, long times, boolean aeMode, boolean backpackMode, int chosenIndex) {
        if (player == null || entry == null || times <= 0L || !entry.hasFtbqTable()) {
            return new BulkBuyResult(0L, times, null);
        }
        dev.ftb.mods.ftbquests.quest.loot.RewardTable table = resolveFtbqTable(entry.getFtbqTableId());
        if (table == null) return new BulkBuyResult(0L, times, null);
        dev.ftb.mods.ftbquests.quest.reward.ItemReward chosen = resolveFtbqItemRewardByIndex(table, chosenIndex);
        if (chosen == null) return new BulkBuyResult(0L, times, null);
        long rolls = Math.min(times, rewardRollCap());
        String via = deliverFtbqChoice(player, chosen, clampFtbqRolls(rolls), aeMode, backpackMode);
        return new BulkBuyResult(rolls, times, via);
    }

    /**
     * 按「表内仅物品类奖励」子序列的下标解出 ItemReward（与客户端选择界面按同样规则遍历
     * {@code table.getWeightedRewards()} 过滤出的顺序必须一致，见 FtbqRewardChoiceScreen）。
     * 下标越界/非物品类奖励/表内容变化导致对不上，一律返回 null（调用方视为“选择无效”）。
     */
    private static dev.ftb.mods.ftbquests.quest.reward.ItemReward resolveFtbqItemRewardByIndex(
            dev.ftb.mods.ftbquests.quest.loot.RewardTable table, int index) {
        if (index < 0) return null;
        int i = 0;
        for (dev.ftb.mods.ftbquests.quest.loot.WeightedReward wr : table.getWeightedRewards()) {
            if (!(wr.getReward() instanceof dev.ftb.mods.ftbquests.quest.reward.ItemReward ir)) continue;
            if (i == index) return ir;
            i++;
        }
        return null;
    }

    /** 表内每一个物品类奖励各自独立随机 rolls 次数量（不经过表的加权随机），聚合后一次性交付。 */
    private static String deliverFtbqAll(ServerPlayer player, dev.ftb.mods.ftbquests.quest.loot.RewardTable table,
                                         int rolls, boolean aeMode, boolean backpackMode) {
        if (rolls <= 0) return null;
        java.util.List<ItemDelivery> deliveries = new java.util.ArrayList<>();
        java.util.List<ItemStack> tmp = new java.util.ArrayList<>(4);
        for (dev.ftb.mods.ftbquests.quest.loot.WeightedReward wr : table.getWeightedRewards()) {
            if (!(wr.getReward() instanceof dev.ftb.mods.ftbquests.quest.reward.ItemReward ir)) continue;
            long total = 0L;
            for (int i = 0; i < rolls; i++) {
                tmp.clear();
                ir.automatedClaimPre(null, tmp, player.getRandom(), player.getUUID(), player);
                for (int j = 0, n = tmp.size(); j < n; j++) {
                    ItemStack st = tmp.get(j);
                    if (st != null && !st.isEmpty()) total += st.getCount();
                }
            }
            if (total <= 0L) continue;
            ItemStack unit = ir.getItem().copy();
            unit.setCount(1);
            deliveries.add(new ItemDelivery(unit, java.math.BigInteger.valueOf(total)));
        }
        if (deliveries.isEmpty()) return null;
        return deliverItemBatch(player, deliveries, aeMode, backpackMode);
    }

    /** 单个物品类奖励独立随机 rolls 次数量并累加（仿每次开箱各自独立结算），交付这一项。 */
    private static String deliverFtbqChoice(ServerPlayer player, dev.ftb.mods.ftbquests.quest.reward.ItemReward chosen,
                                            int rolls, boolean aeMode, boolean backpackMode) {
        if (rolls <= 0) return null;
        long total = 0L;
        java.util.List<ItemStack> tmp = new java.util.ArrayList<>(4);
        for (int i = 0; i < rolls; i++) {
            tmp.clear();
            chosen.automatedClaimPre(null, tmp, player.getRandom(), player.getUUID(), player);
            for (int j = 0, n = tmp.size(); j < n; j++) {
                ItemStack st = tmp.get(j);
                if (st != null && !st.isEmpty()) total += st.getCount();
            }
        }
        if (total <= 0L) return null;
        ItemStack unit = chosen.getItem().copy();
        unit.setCount(1);
        return deliverItems(player, unit, java.math.BigInteger.valueOf(total), aeMode, backpackMode);
    }

    /** 按 FTBQ 表 ID（十六进制字符串）查活的服务端奖励表实例；未加载/ID 非法返回 null。 */
    private static dev.ftb.mods.ftbquests.quest.loot.RewardTable resolveFtbqTable(String hexId) {
        if (hexId == null || hexId.isEmpty()) return null;
        dev.ftb.mods.ftbquests.quest.ServerQuestFile file = dev.ftb.mods.ftbquests.quest.ServerQuestFile.INSTANCE;
        if (file == null) return null;
        long id = dev.ftb.mods.ftbquests.quest.QuestObjectBase.parseCodeString(hexId);
        if (id == 0L) return null;
        return file.getRewardTable(id);
    }

    /**
     * 从 table 里加权抽 rolls 次（一次调 FTBQ 自己的 generateWeightedRandomRewards，内部已含 loot_size 倍数），
     * 只取物品类奖励（ItemReward，经 automatedClaimPre 拿到含 random_bonus 的最终数量），按 Item 聚合后逐项交付。
     *
     * <p>百万级 rolls 下的性能关键：聚合改用原生 {@code long[1]} 累加而非每抽一次都 new 一个 BigInteger
     * 相加（BigInteger 每次运算都会分配新对象），且每个不同 Item 只在首次出现时建一次基准 ItemStack
     * （原先 {@code putIfAbsent(it, new ItemStack(...))} 的第二个参数是立即求值的，哪怕 key 已存在也会
     * 白白 new 一次并丢弃）。两者都是百万次调用级别的纯浪费小对象分配，是主线程卡顿的直接来源。</p>
     */
    private static String deliverFtbqDraws(ServerPlayer player, dev.ftb.mods.ftbquests.quest.loot.RewardTable table,
                                           int rolls, boolean aeMode, boolean backpackMode) {
        if (rolls <= 0) return null;
        java.util.Collection<dev.ftb.mods.ftbquests.quest.loot.WeightedReward> drawn =
                table.generateWeightedRandomRewards(player.getRandom(), rolls, false);
        if (drawn.isEmpty()) return null;
        java.util.Map<Item, ItemStack> unitOf = new java.util.LinkedHashMap<>();
        java.util.Map<Item, long[]> agg = new java.util.LinkedHashMap<>();
        java.util.List<ItemStack> tmp = new java.util.ArrayList<>(4);
        for (dev.ftb.mods.ftbquests.quest.loot.WeightedReward wr : drawn) {
            dev.ftb.mods.ftbquests.quest.reward.Reward reward = wr.getReward();
            if (!(reward instanceof dev.ftb.mods.ftbquests.quest.reward.ItemReward)) continue; // 只支持物品类奖励
            tmp.clear();
            reward.automatedClaimPre(null, tmp, player.getRandom(), player.getUUID(), player);
            for (int i = 0, n = tmp.size(); i < n; i++) {
                ItemStack st = tmp.get(i);
                if (st == null || st.isEmpty()) continue;
                Item it = st.getItem();
                long[] acc = agg.get(it);
                if (acc == null) {
                    acc = new long[1];
                    agg.put(it, acc);
                    unitOf.put(it, new ItemStack(it, 1));
                }
                acc[0] += st.getCount();
            }
        }
        if (agg.isEmpty()) return null;
        java.util.List<ItemDelivery> deliveries = new java.util.ArrayList<>(agg.size());
        for (var e : agg.entrySet()) {
            deliveries.add(new ItemDelivery(unitOf.get(e.getKey()), java.math.BigInteger.valueOf(e.getValue()[0])));
        }
        return deliverItemBatch(player, deliveries, aeMode, backpackMode);
    }

    /**
     * 购买前置：算 affordable（min(times, 各成本通道约束)，夹到剩余限购次数）、扣成本、扣限购剩余次数并存盘。
     * {@link #buyBulk}/{@link #buyBulkChoice}/{@link #buyBulkRandom}/{@link #buyBulkAll} 共用这一步，
     * 只有随后交付什么内容不同。
     * @return 实际可成交次数（0 = 买不起/条目无效/参数非法，未做任何扣款）
     */
    private static long affordAndDeduct(ServerPlayer player, ShopEntry entry, long times, boolean backpackMode) {
        if (player == null || entry == null || !entry.isValid() || times <= 0L) return 0L;
        MinecraftServer server = player.getServer();
        if (server == null) return 0L;
        UUID uuid = player.getUUID();
        // 买价按限时折扣 + 会员累计消费折扣叠加结算（见 ShopEntry#getEffectiveCost(int)/ShopMembership，
        // 仅打折星火/币种/EU，实物成本原价不变）；出售价（sellBulk）不走这个口子，走独立的价差折价。
        ShopCost cost = entry.getEffectiveCost(ShopMembership.discountPercent(server, uuid));

        // affordable = min(times, 各成本通道约束)：星火/币种走钱包余额，EU走无线电网余额，
        // 物品走背包+精妙背包+绑定AE兜底，流体走绑定 AE
        BigInteger aff = BigInteger.valueOf(times);
        if (cost.spark.signum() > 0) {
            aff = aff.min(WalletAccountAPI.getDigital(server, uuid).divide(cost.spark));
        }
        if (cost.eu.signum() > 0) {
            aff = aff.min(ShopWirelessEu.getBalance(player).divide(cost.eu));
        }
        for (java.util.Map.Entry<ResourceLocation, BigInteger> c : cost.coins.entrySet()) {
            BigInteger have = WalletAccountAPI.getCurrency(server, uuid, c.getKey()); // 账户余额
            Item coin = ForgeRegistries.ITEMS.getValue(c.getKey());
            if (coin != null) {
                have = have.add(BigInteger.valueOf(countItem(player, coin))); // + 背包实体币
                have = have.add(BigInteger.valueOf(ShopBackpack.countItem(player, coin, null))); // + 精妙背包实体币
                appeng.api.stacks.AEItemKey ck = appeng.api.stacks.AEItemKey.of(new ItemStack(coin));
                if (ck != null) have = have.add(BigInteger.valueOf(
                        ShopAeNetwork
                                .availableForPlayer(player, ck))); // + 绑定AE实体币
            }
            aff = aff.min(have.divide(c.getValue()));
        }
        for (ExchangeEntry.Ingredient in : cost.physical) {
            if (aff.signum() <= 0) break;
            long available;
            if (in.isFluid) {
                net.minecraft.world.level.material.Fluid fluid = ForgeRegistries.FLUIDS.getValue(in.id);
                if (fluid == null) return 0L;
                available = ShopAeNetwork
                        .availableForPlayer(player, appeng.api.stacks.AEFluidKey.of(fluid));
            } else {
                Item item = ForgeRegistries.ITEMS.getValue(in.id);
                if (item == null) return 0L;
                long inv = countItem(player, item, in.nbt());
                long backpack = ShopBackpack.countItem(player, item, in.nbt());
                long invPlusBackpack = (inv > Long.MAX_VALUE - backpack) ? Long.MAX_VALUE : inv + backpack;
                long ae = 0L;
                appeng.api.stacks.AEItemKey aek = appeng.api.stacks.AEItemKey.of(in.makeUnitStack());
                if (aek != null) ae = ShopAeNetwork
                        .availableForPlayer(player, aek);
                available = (invPlusBackpack > Long.MAX_VALUE - ae) ? Long.MAX_VALUE : invPlusBackpack + ae; // 背包+精妙背包+绑定AE，防溢出
            }
            aff = aff.min(BigInteger.valueOf(available / in.count));
        }
        long affordable = aff.signum() <= 0 ? 0L : (aff.bitLength() < 63 ? aff.longValue() : Long.MAX_VALUE);
        affordable = entry.clampByUses(affordable); // 限购商品：再夹到不超过剩余次数
        String periodKey = WalletAccountAPI.purchaseKey(entry.getGoodsId(), entry.getCategory());
        affordable = ShopPeriodLimiter.clamp(player, entry, periodKey, affordable); // 周期限购：再夹到不超过当前周期窗口剩余额度（每玩家独立）
        if (affordable <= 0L) return 0L;

        // 扣成本（affordable 已被各通道约束，各项必成，不溢出）
        BigInteger affBig = BigInteger.valueOf(affordable);
        if (cost.spark.signum() > 0) {
            WalletAccountAPI.tryDeductDigital(server, uuid, cost.spark.multiply(affBig));
        }
        if (cost.eu.signum() > 0) {
            ShopWirelessEu.tryDeduct(player, cost.eu.multiply(affBig));
        }
        for (java.util.Map.Entry<ResourceLocation, BigInteger> c : cost.coins.entrySet()) {
            BigInteger need = c.getValue().multiply(affBig);
            BigInteger fromAcct = need.min(WalletAccountAPI.getCurrency(server, uuid, c.getKey())); // 先扣账户
            if (fromAcct.signum() > 0) WalletAccountAPI.tryDeductCurrency(server, uuid, c.getKey(), fromAcct);
            BigInteger remBig = need.subtract(fromAcct);
            long rem = remBig.bitLength() < 63 ? remBig.longValue() : Long.MAX_VALUE; // 剩余走背包+精妙背包+AE，clamp
            if (rem > 0) {
                Item coin = ForgeRegistries.ITEMS.getValue(c.getKey());
                if (coin != null) {
                    long fromAe = deductFromCarried(player, coin, null, rem, backpackMode); // 先扣背包/精妙背包（顺序看模式），不足再从绑定AE抽
                    if (fromAe > 0) {
                        appeng.api.stacks.AEItemKey ck = appeng.api.stacks.AEItemKey.of(new ItemStack(coin));
                        if (ck != null) ShopAeNetwork
                                .extractForPlayer(player, ck, fromAe);
                    }
                }
            }
        }
        for (ExchangeEntry.Ingredient in : cost.physical) {
            long need = in.count * affordable; // ≤ available，不溢出
            if (in.isFluid) {
                net.minecraft.world.level.material.Fluid fluid = ForgeRegistries.FLUIDS.getValue(in.id);
                ShopAeNetwork
                        .extractForPlayer(player, appeng.api.stacks.AEFluidKey.of(fluid), need);
            } else {
                Item item = ForgeRegistries.ITEMS.getValue(in.id);
                long fromAe = deductFromCarried(player, item, in.nbt(), need, backpackMode); // 先扣背包/精妙背包（顺序看模式），不足再从绑定AE抽
                if (fromAe > 0) {
                    appeng.api.stacks.AEItemKey aek = appeng.api.stacks.AEItemKey.of(in.makeUnitStack());
                    if (aek != null) ShopAeNetwork
                            .extractForPlayer(player, aek, fromAe);
                }
            }
        }

        if (entry.isLimited()) {
            entry.consumeUses(affordable);
            // 限购总量按存档隔离，见 ShopLimitSavedData——不再写回 shop.json（全局配置目录，会跨存档"继承"消耗）
            ShopLimitSavedData.get(server).set(entry.getStableId(), entry.getRemainingUses());
        }
        ShopPeriodLimiter.consume(player, entry, periodKey, affordable); // 周期限购：记账当前窗口已用量
        return affordable;
    }

    /**
     * 花费预览用只读余量（{@code SIMULATE}，不扣任何东西）：币种/物品/流体各自「当前可用总量」，
     * 供客户端「花费预览」格子显示玩家是否够、缺多少，供货渠道与 {@link #affordAndDeduct} 的 aff 计算一致——
     * 币种含账户虚拟余额+背包+精妙背包，物品含背包+精妙背包，流体只走 AE（无背包容器概念）；
     * AE 网络那部分只在 aeMode 为真时才计入，对齐商店顶栏「AE模式」开关的玩家预期（该开关平时只管交付去向，
     * 这里额外用它来控制预览是否把 AE 存量算进"够不够"，避免没开 AE 模式却显示"够"实际却买不到的错觉）。
     * 不含星火——星火纯数字账户余额，客户端已有 {@link WalletAccountAPI} 同步的快照可直接比对，不必打这趟往返。
     * @return coins 按 {@link ShopCost#coins} 同 key；items/fluids 顺序对齐 {@link ShopCost#items()}/{@link ShopCost#fluids()}
     */
    public static CostPreview previewHave(ServerPlayer player, ShopCost cost, boolean aeMode) {
        java.util.Map<ResourceLocation, BigInteger> coinsHave = new java.util.LinkedHashMap<>();
        java.util.List<Long> itemsHave = new java.util.ArrayList<>();
        java.util.List<Long> fluidsHave = new java.util.ArrayList<>();
        if (player == null || cost == null) return new CostPreview(coinsHave, itemsHave, fluidsHave);
        MinecraftServer server = player.getServer();
        UUID uuid = player.getUUID();

        for (java.util.Map.Entry<ResourceLocation, BigInteger> c : cost.coins.entrySet()) {
            BigInteger have = server != null ? WalletAccountAPI.getCurrency(server, uuid, c.getKey()) : BigInteger.ZERO;
            Item coin = ForgeRegistries.ITEMS.getValue(c.getKey());
            if (coin != null) {
                have = have.add(BigInteger.valueOf(countItem(player, coin)));
                have = have.add(BigInteger.valueOf(ShopBackpack.countItem(player, coin, null)));
                if (aeMode) {
                    appeng.api.stacks.AEItemKey ck = appeng.api.stacks.AEItemKey.of(new ItemStack(coin));
                    if (ck != null) have = have.add(BigInteger.valueOf(ShopAeNetwork.availableForPlayer(player, ck)));
                }
            }
            coinsHave.put(c.getKey(), have);
        }
        for (ExchangeEntry.Ingredient in : cost.items()) {
            Item item = ForgeRegistries.ITEMS.getValue(in.id);
            long have = 0L;
            if (item != null) {
                long inv = countItem(player, item, in.nbt());
                long backpack = ShopBackpack.countItem(player, item, in.nbt());
                have = (inv > Long.MAX_VALUE - backpack) ? Long.MAX_VALUE : inv + backpack;
                if (aeMode) {
                    appeng.api.stacks.AEItemKey aek = appeng.api.stacks.AEItemKey.of(in.makeUnitStack());
                    if (aek != null) {
                        long ae = ShopAeNetwork.availableForPlayer(player, aek);
                        have = (have > Long.MAX_VALUE - ae) ? Long.MAX_VALUE : have + ae;
                    }
                }
            }
            itemsHave.add(have);
        }
        for (ExchangeEntry.Ingredient in : cost.fluids()) {
            long have = 0L;
            if (aeMode) {
                net.minecraft.world.level.material.Fluid fluid = ForgeRegistries.FLUIDS.getValue(in.id);
                if (fluid != null) have = ShopAeNetwork.availableForPlayer(player, appeng.api.stacks.AEFluidKey.of(fluid));
            }
            fluidsHave.add(have);
        }
        return new CostPreview(coinsHave, itemsHave, fluidsHave);
    }

    /** {@link #previewHave} 的返回值：coins 按币种 ID，items/fluids 按 {@link ShopCost#items()}/{@link ShopCost#fluids()} 下标对齐。 */
    public record CostPreview(java.util.Map<ResourceLocation, BigInteger> coins, java.util.List<Long> items, java.util.List<Long> fluids) {}

    /**
     * 直接获取（作弊模式）：不结算任何成本，直接把 times 份商品交付给玩家。
     * 仅当 {@link ShopCheatMode#isEnabled} 为该玩家开启时，由 ShopActionPacket.doBuy 调用。
     * 交付走与购买同款分层逻辑（AE注入 / SDA打包 / 背包）。
     */
    public static BulkBuyResult giveBulk(ServerPlayer player, ShopEntry entry, long times, boolean aeMode, boolean backpackMode) {
        if (player == null || entry == null || !entry.isValid() || times <= 0L) {
            return new BulkBuyResult(0L, times, null);
        }
        long capped = capByFluidGoodsCapacity(player, entry, times);
        if (capped <= 0L) return new BulkBuyResult(0L, times, null);
        String via = deliverGoodsList(player, entry, capped, aeMode, backpackMode);
        return new BulkBuyResult(capped, times, via);
    }

    /** 直接获取（作弊）+ 自选：不结算成本，交付 chosen 这一项，每次独立随机数量（× min(times, 抽取上限)）。 */
    public static BulkBuyResult giveBulkChoice(ServerPlayer player, ShopEntry entry, long times, boolean aeMode, boolean backpackMode, ShopEntry.RewardOption chosen) {
        if (player == null || entry == null || !entry.isValid() || times <= 0L || chosen == null) {
            return new BulkBuyResult(0L, times, null);
        }
        long rolls = Math.min(times, rewardRollCap());
        ItemStack unit = chosen.item().copy();
        unit.setCount(1);
        java.math.BigInteger goodsTotal = rollTotal(chosen, rolls);
        String via = deliverItems(player, unit, goodsTotal, aeMode, backpackMode);
        return new BulkBuyResult(rolls, times, via);
    }

    /** 直接获取（作弊）+ 随机：每次独立按权重抽 1 项 + 独立随机数量，聚合后交付。 */
    public static BulkBuyResult giveBulkRandom(ServerPlayer player, ShopEntry entry, long times, boolean aeMode, boolean backpackMode) {
        if (player == null || entry == null || !entry.isValid() || times <= 0L) return new BulkBuyResult(0L, times, null);
        java.util.List<ShopEntry.RewardOption> pool = entry.getRewardPool();
        if (pool.isEmpty()) return new BulkBuyResult(0L, times, null);
        long rolls = Math.min(times, rewardRollCap());
        java.util.Map<ShopEntry.RewardOption, java.math.BigInteger> agg = new java.util.LinkedHashMap<>();
        for (long i = 0; i < rolls; i++) {
            ShopEntry.RewardOption picked = pickWeighted(pool);
            agg.merge(picked, java.math.BigInteger.valueOf(picked.rollCount()), java.math.BigInteger::add);
        }
        java.util.List<ItemDelivery> deliveries = new java.util.ArrayList<>();
        for (var e : agg.entrySet()) {
            ItemStack unit = e.getKey().item().copy();
            unit.setCount(1);
            deliveries.add(new ItemDelivery(unit, e.getValue()));
        }
        String via = deliverItemBatch(player, deliveries, aeMode, backpackMode);
        return new BulkBuyResult(rolls, times, via);
    }

    /** 直接获取（作弊）+ 全部：一次性交付奖励池每一项，各项独立随机数量（× min(times, 抽取上限)）。 */
    public static BulkBuyResult giveBulkAll(ServerPlayer player, ShopEntry entry, long times, boolean aeMode, boolean backpackMode) {
        if (player == null || entry == null || !entry.isValid() || times <= 0L) return new BulkBuyResult(0L, times, null);
        java.util.List<ShopEntry.RewardOption> pool = entry.getRewardPool();
        if (pool.isEmpty()) return new BulkBuyResult(0L, times, null);
        long rolls = Math.min(times, rewardRollCap());
        java.util.List<ItemDelivery> deliveries = new java.util.ArrayList<>();
        for (ShopEntry.RewardOption opt : pool) {
            ItemStack unit = opt.item().copy();
            unit.setCount(1);
            deliveries.add(new ItemDelivery(unit, rollTotal(opt, rolls)));
        }
        String via = deliverItemBatch(player, deliveries, aeMode, backpackMode);
        return new BulkBuyResult(rolls, times, via);
    }

    /**
     * 分层交付一批物品给玩家（购买 / 兑换反向共用）。不扣款，只负责把 total 个 unit 送达。
     * aeMode 且绑定在线 AE 能全额收下 → 注入 AE 网络（SIMULATE→MODULATE 防吞）
     * 否则总量 ≥ 配置阈值 → 打包超级磁盘阵列赠送
     * 否则进背包，装不下的余量再打包 SDA
     * @return 主交付方式 "ae"/"disk_hatch"/"sda"/"inventory"（null = 参数无效）
     */
    public static String deliverItems(net.minecraft.server.level.ServerPlayer player, ItemStack unit,
                                      java.math.BigInteger total, boolean aeMode, boolean backpackMode) {
        if (player == null || unit == null || unit.isEmpty() || total == null || total.signum() <= 0) return null;
        stripSdaClaim(unit); // 商品模板若是从已认领的 SDA 实例捕获的，须清掉标记才能让买家副本独立分家
        boolean directSdaGoods = isSdaItem(unit);
        boolean directInfinityCellGoods = isInfinityCellItem(unit);
        boolean directMountableGoods = directSdaGoods || directInfinityCellGoods;
        java.math.BigInteger directSdaLeftover = tryDeliverDirectSdaToDiskHatch(player, unit, total);
        boolean anyDirectSdaMounted = directSdaLeftover.compareTo(total) < 0;
        if (directSdaLeftover.signum() <= 0) return "disk_hatch";
        total = directSdaLeftover;
        // 「AE 禁止注入」开启时，AE 模式只管拉取材料付款/检索库存，交付一律走 SDA/背包（见商店设置）
        if (com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.shopAeDeliverDisabled.get()) aeMode = false;
        appeng.api.stacks.AEItemKey key = appeng.api.stacks.AEItemKey.of(unit);
        long threshold = com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.shopSdaPackThreshold.get();
        boolean fitsLong = total.bitLength() < 63; // ≤ Long.MAX_VALUE，AE/背包按 long 处理
        boolean atThreshold = total.compareTo(java.math.BigInteger.valueOf(threshold)) >= 0;
        String via;
        if (aeMode && key != null && fitsLong
                && ShopAeNetwork
                        .canInjectForPlayer(player, key, total.longValue())) {
            via = "ae";
        } else if (!directMountableGoods && key != null && atThreshold) {
            via = "sda";
        } else {
            via = "inventory";
        }
        switch (via) {
            case "ae" -> ShopAeNetwork
                    .injectForPlayer(player, key, total.longValue());
            case "sda" -> {
                if (packAsSda(player, key, total)) via = "disk_hatch";
            }
            default -> {
                long leftover = deliverToInventory(player, unit, total, backpackMode); // 返回装不下的余量
                if (!directMountableGoods && leftover > 0L && key != null) {
                    if (packAsSda(player, key, java.math.BigInteger.valueOf(leftover))) via = "disk_hatch";
                } else if (directMountableGoods && leftover > 0L) {
                    dropDirectItemCopies(player, unit, leftover);
                }
            }
        }
        if (anyDirectSdaMounted) return "disk_hatch";
        return via;
    }

    /** 一份「物品原型 + 总数」交付请求，供 {@link #deliverItemBatch} 批量处理。 */
    public record ItemDelivery(ItemStack unit, java.math.BigInteger total) {}

    /**
     * 分层交付「一次购买产出多种不同物品」的整批货（RANDOM/ALL/FTBQ 等奖励表模式专用）。
     * 与 {@link #deliverItems} 的关键区别：多种物品里凡是没进 AE、需要打包的部分，
     * 统一合并进<b>同一个</b>超级磁盘阵列，而不是每种物品各自开一个 SDA——避免大规模抽奖
     * （比如一次买 20 万次）炸出几十个「独立一片」的磁盘阵列，玩家还得一个个找。
     * 进 AE 的（有绑定在线网络且吃得下）仍各走各的，AE 网络本身就是统一存储，不受这个问题影响。
     * 是否打包按<b>整批总量</b>（各物品 total 之和）判断，而非逐个物品各自的量——抽奖类商品
     * 常见「有的物品类型数量多、有的少」，若按单个物品各自判断阈值，总量早已达标的一批货里，
     * 数量没单独达标的类型会被漏判成散件塞进背包，同一批货一部分进 SDA、一部分散落背包，
     * 观感上像是磁盘阵列"漏包"了一堆（见反馈）。整批达标就整批一起进 SDA，不再逐个物品判断。
     * @return 主交付方式："disk_hatch"（有任意物品直注入磁盘仓室）
     *         /"sda"（只要有任意物品打了包就报这个，信息量最大）
     *         /"ae"（全部进了 AE，没有任何打包/进背包）/"inventory"（全部进了背包，没有任何 AE/打包）
     *         /null（没有任何有效物品）
     */
    public static String deliverItemBatch(net.minecraft.server.level.ServerPlayer player,
                                          java.util.List<ItemDelivery> deliveries, boolean aeMode, boolean backpackMode) {
        if (player == null || deliveries == null || deliveries.isEmpty()) return null;
        // 「AE 禁止注入」开启时，AE 模式只管拉取材料付款/检索库存，交付一律走 SDA/背包（见商店设置）
        if (com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.shopAeDeliverDisabled.get()) aeMode = false;
        long threshold = com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.shopSdaPackThreshold.get();
        java.math.BigInteger grandTotal = java.math.BigInteger.ZERO;
        for (ItemDelivery d : deliveries) {
            if (d.unit() == null || d.unit().isEmpty() || d.total() == null || d.total().signum() <= 0) continue;
            stripSdaClaim(d.unit()); // 商品模板若是从已认领的 SDA 实例捕获的，须清掉标记才能让买家副本独立分家
            grandTotal = grandTotal.add(d.total());
        }
        boolean batchAtThreshold = grandTotal.compareTo(java.math.BigInteger.valueOf(threshold)) >= 0;
        java.util.Map<appeng.api.stacks.AEKey, java.math.BigInteger> sdaBatch = new java.util.LinkedHashMap<>();
        boolean anyAe = false, anyInventory = false;
        boolean anySda = false;
        boolean anyDiskHatch = false;
        for (ItemDelivery d : deliveries) {
            ItemStack unit = d.unit();
            java.math.BigInteger total = d.total();
            if (unit == null || unit.isEmpty() || total == null || total.signum() <= 0) continue;
            boolean directSdaGoods = isSdaItem(unit);
            boolean directInfinityCellGoods = isInfinityCellItem(unit);
            boolean directMountableGoods = directSdaGoods || directInfinityCellGoods;
            java.math.BigInteger directSdaLeftover = tryDeliverDirectSdaToDiskHatch(player, unit, total);
            if (directSdaLeftover.compareTo(total) < 0) anyDiskHatch = true;
            if (directSdaLeftover.signum() <= 0) continue;
            total = directSdaLeftover;
            appeng.api.stacks.AEItemKey key = appeng.api.stacks.AEItemKey.of(unit);
            boolean fitsLong = total.bitLength() < 63;
            if (aeMode && key != null && fitsLong
                    && ShopAeNetwork
                            .canInjectForPlayer(player, key, total.longValue())) {
                ShopAeNetwork
                        .injectForPlayer(player, key, total.longValue());
                anyAe = true;
            } else if (!directMountableGoods && key != null && batchAtThreshold) {
                sdaBatch.merge(key, total, java.math.BigInteger::add);
            } else {
                long leftover = deliverToInventory(player, unit, total, backpackMode); // 返回装不下的余量
                anyInventory = true;
                if (!directMountableGoods && leftover > 0L && key != null) {
                    sdaBatch.merge(key, java.math.BigInteger.valueOf(leftover), java.math.BigInteger::add);
                } else if (directMountableGoods && leftover > 0L) {
                    dropDirectItemCopies(player, unit, leftover);
                }
            }
        }
        boolean packedToDiskHatch = false;
        if (!sdaBatch.isEmpty()) {
            anySda = true;
            packedToDiskHatch = packAsSdaBatch(player, sdaBatch);
        }
        if (anyDiskHatch || packedToDiskHatch) return "disk_hatch";
        if (anySda) return "sda";
        if (anyAe) return "ae";
        if (anyInventory) return "inventory";
        return null;
    }

    /**
     * 批量出售。一次性结算，成交量受背包持有量（int 上限）天然约束。
     * @return 实际出售份数
     */
    public static long sellBulk(ServerPlayer player, ShopEntry entry, long times, boolean backpackMode) {
        if (player == null || entry == null || !entry.isValid() || times <= 0L) {
            return 0L;
        }
        MinecraftServer server = player.getServer();
        if (server == null) return 0L;
        ShopCost cost = entry.getCost();
        if (cost.hasPhysical()) return 0L; // 含实物成本的商品不支持出售（反向退实物语义不清）
        if (entry.hasMultipleGoods()) return 0L; // 组合商品不支持出售（反向该退哪几种、退多少语义不清）
        Item goods = entry.getGoodsItem();
        int per = entry.getGoodsCount();
        if (per <= 0) return 0L;
        // 出售的货源同购买成本一样，背包 + 精妙背包 + 绑定AE网络合计可用量，不再只认随身背包
        long inv = countItem(player, goods);
        long backpack = ShopBackpack.countItem(player, goods, null);
        long invPlusBackpack = (inv > Long.MAX_VALUE - backpack) ? Long.MAX_VALUE : inv + backpack;
        long ae = 0L;
        appeng.api.stacks.AEItemKey key = appeng.api.stacks.AEItemKey.of(new ItemStack(goods));
        if (key != null) ae = ShopAeNetwork.availableForPlayer(player, key);
        long held = (invPlusBackpack > Long.MAX_VALUE - ae) ? Long.MAX_VALUE : invPlusBackpack + ae;
        long sell = Math.min(times, held / per);
        sell = entry.clampByUses(sell); // 限购商品：出售与购买共享同一剩余次数
        String periodKey = WalletAccountAPI.purchaseKey(entry.getGoodsId(), entry.getCategory());
        sell = ShopPeriodLimiter.clamp(player, entry, periodKey, sell); // 周期限购：出售与购买共享同一周期窗口额度（每玩家独立）
        if (sell <= 0L) return 0L;
        long need = sell * per; // ≤ held，不溢出
        long fromAe = deductFromCarried(player, goods, null, need, backpackMode); // 先扣背包/精妙背包（顺序看模式），不足再从绑定AE抽
        if (fromAe > 0 && key != null) {
            ShopAeNetwork.extractForPlayer(player, key, fromAe);
        }
        // 退回纯钱包成本（星火 + 币种 + EU）× 份数，按出售价差折价（回收价=买价×sellRatioPercent%，
        // 防止「买了立刻卖回零损耗」的套利，见 config「shop.sellRatioPercent」）
        ShopCost sellCost = cost.scaledTo(sellRatioPercent());
        BigInteger sellBig = BigInteger.valueOf(sell);
        UUID uuid = player.getUUID();
        if (sellCost.spark.signum() > 0) {
            WalletAccountAPI.addDigital(server, uuid, sellCost.spark.multiply(sellBig));
        }
        if (sellCost.eu.signum() > 0) {
            ShopWirelessEu.add(player, sellCost.eu.multiply(sellBig));
        }
        for (java.util.Map.Entry<ResourceLocation, BigInteger> c : sellCost.coins.entrySet()) {
            WalletAccountAPI.addCurrency(server, uuid, c.getKey(), c.getValue().multiply(sellBig));
        }
        if (entry.isLimited()) {
            entry.consumeUses(sell);
            // 限购总量按存档隔离，见 ShopLimitSavedData——不再写回 shop.json（全局配置目录，会跨存档"继承"消耗）
            ShopLimitSavedData.get(server).set(entry.getStableId(), entry.getRemainingUses());
        }
        ShopPeriodLimiter.consume(player, entry, periodKey, sell); // 周期限购：记账当前窗口已用量
        return sell;
    }

    /**
     * 随身背包 + 精妙背包（{@link ShopBackpack}）分层交付，backpackMode 决定先塞哪边——关闭时随身背包
     * 优先（原有顺序），开启时精妙背包优先。两边都塞不下的余量交由调用方打包 SDA。只塞不丢，避免海量掉落卡顿。
     */
    private static long deliverToInventory(net.minecraft.server.level.ServerPlayer player, ItemStack unit,
                                           java.math.BigInteger count, boolean backpackMode) {
        java.math.BigInteger remaining = count;
        if (backpackMode) {
            remaining = insertBackpack(player, unit, remaining);
            remaining = insertInventory(player, unit, remaining);
        } else {
            remaining = insertInventory(player, unit, remaining);
            remaining = insertBackpack(player, unit, remaining);
        }
        return remaining.bitLength() < 63 ? remaining.longValue() : Long.MAX_VALUE;
    }

    private static java.math.BigInteger insertInventory(net.minecraft.server.level.ServerPlayer player, ItemStack unit, java.math.BigInteger count) {
        if (count.signum() <= 0) return java.math.BigInteger.ZERO;
        int max = Math.max(1, unit.getMaxStackSize());
        java.math.BigInteger remaining = count;
        java.math.BigInteger bigMax = java.math.BigInteger.valueOf(max);
        // 背包最多 36 格，循环上界受背包容量约束（装满即 break），不会因 count 巨大而卡死
        while (remaining.signum() > 0) {
            int give = remaining.compareTo(bigMax) >= 0 ? max : remaining.intValue();
            ItemStack stack = unit.copy();
            stack.setCount(give);
            boolean added = player.getInventory().add(stack);
            int actuallyAdded = give - stack.getCount(); // add 可能部分放入
            remaining = remaining.subtract(java.math.BigInteger.valueOf(actuallyAdded));
            if (!added || actuallyAdded <= 0) break; // 背包满
        }
        return remaining;
    }

    private static java.math.BigInteger insertBackpack(net.minecraft.server.level.ServerPlayer player, ItemStack unit, java.math.BigInteger count) {
        if (count.signum() <= 0) return java.math.BigInteger.ZERO;
        return ShopBackpack.insert(player, unit, count); // 随身背包塞不下的（或未穿戴）→ 原样退回
    }

    /**
     * 交付前清掉 SDA 的"已认领"标记（{@code shanhai_sda_runtime_uuid}）。
     * 商品/奖励模板可能是管理员从一个已经"取出即分家"过的 SDA 实例捕获的，若原样带着该标记交付，
     * {@code SuperDiskArrayInventory.claimOwnership()} 会误判"已认领"而跳过分家，
     * 导致所有买家的副本共享同一份后端存储、互相串内容。交付前清掉标记，
     * 让每份新副本都能在买家首次真实访问（放入 AE 网络/奇点数据中枢等）时独立分家。
     */
    private static void stripSdaClaim(ItemStack stack) {
        if (stack == null || stack.isEmpty()
                || !(stack.getItem() instanceof com.dishanhai.gt_shanhai.common.item.SuperDiskArrayItem)) return;
        net.minecraft.nbt.CompoundTag tag = stack.getTag();
        if (tag != null) tag.remove(com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory.TAG_RUNTIME_UUID);
    }

    private static boolean isSdaItem(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && stack.getItem() instanceof com.dishanhai.gt_shanhai.common.item.SuperDiskArrayItem;
    }

    private static boolean isInfinityCellItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && "expatternprovider:infinity_cell".equals(id.toString());
    }

    private static java.math.BigInteger tryDeliverDirectSdaToDiskHatch(net.minecraft.server.level.ServerPlayer player,
                                                                       ItemStack unit,
                                                                       java.math.BigInteger total) {
        if (!com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.shopSdaDirectDiskHatchInject.get()) return total;
        if (!hasDirectMountableSdaContent(player, unit)) return total;
        java.math.BigInteger remaining = total;
        while (remaining.signum() > 0) {
            ItemStack sda = unit.copyWithCount(1);
            if (!ShopAeNetwork.injectSdaIntoBoundDiskHatch(player, sda)) break;
            remaining = remaining.subtract(java.math.BigInteger.ONE);
        }
        return remaining;
    }

    private static boolean hasDirectMountableSdaContent(net.minecraft.server.level.ServerPlayer player, ItemStack stack) {
        if (!isSdaItem(stack)) {
            return isInfinityCellItem(stack)
                    && com.dishanhai.gt_shanhai.common.machine.part.MEDiskHatchPartMachine
                            .createEaeInfinityCellStorage(stack) != null;
        }
        net.minecraft.nbt.CompoundTag tag = stack.getTag();
        if (tag == null) return false;
        if (tag.contains("keys", net.minecraft.nbt.Tag.TAG_LIST)
                && tag.contains("amts", net.minecraft.nbt.Tag.TAG_LONG_ARRAY)) return true;
        String TAG_TYPES = com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory.TAG_TYPES;
        if (tag.getInt(TAG_TYPES) > 0) return true;
        if (com.dishanhai.gt_shanhai.common.item.SuperDiskArrayItem.getVirtualCellCount(stack) > 0) return true;
        if (!tag.hasUUID(com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory.TAG_UUID)) return false;
        java.util.UUID uuid = tag.getUUID(com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory.TAG_UUID);
        net.minecraft.server.MinecraftServer server = player == null ? null : player.getServer();
        if (server != null && !com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData.get(server)
                .readCellBigAmounts(uuid).isEmpty()) return true;
        return com.dishanhai.gt_shanhai.api.ae.DShanhaiSdaContentStore.hasStored(uuid)
                && !com.dishanhai.gt_shanhai.api.ae.DShanhaiSdaContentStore.restore(uuid).isEmpty();
    }

    private static void dropDirectItemCopies(net.minecraft.server.level.ServerPlayer player, ItemStack unit, long count) {
        long remaining = count;
        while (remaining-- > 0L) {
            player.drop(unit.copyWithCount(1), false);
        }
    }

    /**
     * 打包成一个预装 amount 个 key 的超级磁盘阵列，发给玩家（装不下则掉落）。
     * 直接写服务端 backend（BigInteger 无上限）；玩家首 tick 由 SDA "取出即分家" 自动认领私有 UUID。
     */
    private static boolean packAsSda(net.minecraft.server.level.ServerPlayer player,
                                     appeng.api.stacks.AEItemKey key, java.math.BigInteger amount) {
        if (key == null || amount.signum() <= 0) return false;
        java.util.Map<appeng.api.stacks.AEKey, java.math.BigInteger> amounts = new java.util.LinkedHashMap<>();
        amounts.put(key, amount);
        return packAsSdaBatch(player, amounts);
    }

    /**
     * 打包成一个装着<b>多种</b> key（各自 amount）的超级磁盘阵列，发给玩家（装不下则掉落）。
     * {@link #packAsSda} 单物品版就是套一层这个方法；{@link #deliverItemBatch} 靠这个把一次
     * 抽奖里所有需要打包的物品合并进同一片磁盘，不再一种物品一个磁盘阵列。
     */
    private static boolean packAsSdaBatch(net.minecraft.server.level.ServerPlayer player,
                                          java.util.Map<appeng.api.stacks.AEKey, java.math.BigInteger> amounts) {
        if (player == null || amounts == null || amounts.isEmpty()) return false;
        net.minecraft.server.MinecraftServer server = player.getServer();
        if (server == null) return false;
        java.util.UUID uuid = java.util.UUID.randomUUID();
        ItemStack sda = new ItemStack(com.dishanhai.gt_shanhai.GTDishanhaiMod.SUPER_DISK_ARRAY.get());
        sda.getOrCreateTag().putUUID(com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory.TAG_UUID, uuid);
        com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData.get(server)
                .updateCellBig(uuid, "sda", com.dishanhai.gt_shanhai.common.item.SuperDiskArrayItem.TOTAL_BYTES, amounts);
        if (com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.shopSdaDirectDiskHatchInject.get()
                && ShopAeNetwork.injectSdaIntoBoundDiskHatch(player, sda)) return true;
        if (!player.getInventory().add(sda)) player.drop(sda, false);
        return false;
    }

    // ==================== 货币 ATM：单币提交 / 币种兑换 / AE 抽取 ====================

    /**
     * 单币种提交：把背包里该币种的实体币全部吸入账户余额（1:1）。
     * @return 本次吸入枚数
     */
    public static long depositOne(ServerPlayer player, ResourceLocation currency) {
        if (player == null || currency == null) return 0L;
        MinecraftServer server = player.getServer();
        if (server == null) return 0L;
        UUID uuid = player.getUUID();
        long total = 0L;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(s.getItem());
            if (id == null || !id.equals(currency)) continue;
            int count = s.getCount();
            WalletAccountAPI.addCurrency(server, uuid, currency, BigInteger.valueOf(count));
            total += count;
            s.setCount(0);
        }
        return total;
    }

    /** 币种兑换结果：实际消耗的源币量 consumed（显示用）+ 实际换得的目标币量 gained（0 = 兑换失败）。 */
    public record ExchangeResult(long consumed, long gained) {
        public static final ExchangeResult FAIL = new ExchangeResult(0L, 0L);
    }

    /**
     * 币种兑换：把账户里最多 amount 个 from 币按汇率换成 to 币。
     * 请求量超过账户余额时按<b>余额封顶</b>成交（而非要求精确匹配整体失败）；
     * 目标量 = floor(consumed × from币值 / to币值)，BigInteger 全程无损；
     * 封顶后源量≤0、目标量≤0 或币值未配置 → 返回 {@link ExchangeResult#FAIL}（不动账户）。
     * @return 实际消耗/换得（显示用，可能截断到 Long.MAX）
     */
    public static ExchangeResult exchange(ServerPlayer player, ResourceLocation from, ResourceLocation to, long amount) {
        if (player == null || from == null || to == null || from.equals(to) || amount <= 0L) return ExchangeResult.FAIL;
        MinecraftServer server = player.getServer();
        if (server == null) return ExchangeResult.FAIL;
        UUID uuid = player.getUUID();
        long fromValue = CurrencyRateConfig.getValue(from);
        long toValue = CurrencyRateConfig.getValue(to);
        if (fromValue <= 0L || toValue <= 0L) return ExchangeResult.FAIL; // 未配置币值不可兑换
        BigInteger amt = WalletAccountAPI.getCurrency(server, uuid, from).min(BigInteger.valueOf(amount)); // 余额不足按余额最大值成交
        if (amt.signum() <= 0) return ExchangeResult.FAIL;
        BigInteger dst = amt.multiply(BigInteger.valueOf(fromValue)).divide(BigInteger.valueOf(toValue));
        if (dst.signum() <= 0) return ExchangeResult.FAIL; // 源量太小，换不出 1 个目标币
        if (!WalletAccountAPI.tryDeductCurrency(server, uuid, from, amt)) return ExchangeResult.FAIL;
        WalletAccountAPI.addCurrency(server, uuid, to, dst);
        long consumed = amt.bitLength() < 63 ? amt.longValue() : Long.MAX_VALUE;
        long gained = dst.bitLength() < 63 ? dst.longValue() : Long.MAX_VALUE;
        return new ExchangeResult(consumed, gained);
    }

    /**
     * AE 抽取：从绑定的在线 AE 网络（提交器/商店终端）抽取 amount 个该币种，存入账户余额。
     * 网络不足则按实际可抽量成交。
     * @return 实际抽取并入账的枚数
     */
    public static long aeExtractCoin(ServerPlayer player, ResourceLocation currency, long amount) {
        if (player == null || currency == null || amount <= 0L) return 0L;
        MinecraftServer server = player.getServer();
        if (server == null) return 0L;
        Item coin = ForgeRegistries.ITEMS.getValue(currency);
        if (coin == null) return 0L;
        appeng.api.stacks.AEItemKey key = appeng.api.stacks.AEItemKey.of(new ItemStack(coin));
        if (key == null) return 0L;
        long got = ShopAeNetwork
                .extractForPlayer(player, key, amount);
        if (got > 0L) WalletAccountAPI.addCurrency(server, player.getUUID(), currency, BigInteger.valueOf(got));
        return got;
    }

    /**
     * 查询绑定该玩家的在线 AE 网络当前有多少枚 currency 可抽（纯 SIMULATE，不改动网络），
     * 供 ATM 界面「从 AE 抽取」前预览，好让玩家知道该输多少而不用试错。
     * @return 可抽枚数（0 = 无绑定在线网络 / 网络无此币）
     */
    public static long aeAvailableCoin(ServerPlayer player, ResourceLocation currency) {
        if (player == null || currency == null) return 0L;
        Item coin = ForgeRegistries.ITEMS.getValue(currency);
        if (coin == null) return 0L;
        appeng.api.stacks.AEItemKey key = appeng.api.stacks.AEItemKey.of(new ItemStack(coin));
        if (key == null) return 0L;
        return ShopAeNetwork.availableForPlayer(player, key);
    }

    /**
     * 币种 → 星火（数字余额）：按币值把最多 coins 枚 currency 换成星火，
     * 请求量超过账户余额时按余额封顶成交（见 {@link WalletAccountAPI#convertCurrencyToDigital}）。
     * @return 换得的星火数（显示用，可能截断到 Long.MAX）
     */
    public static long toDigital(ServerPlayer player, ResourceLocation currency, long coins) {
        if (player == null || currency == null || coins <= 0L) return 0L;
        MinecraftServer server = player.getServer();
        if (server == null) return 0L;
        BigInteger gained = WalletAccountAPI.convertCurrencyToDigital(server, player.getUUID(), currency, BigInteger.valueOf(coins));
        return gained.bitLength() < 63 ? gained.longValue() : Long.MAX_VALUE;
    }

    /**
     * 星火（数字余额）→ 币种：花 {@code coins × 币值} 星火，换 coins 枚 currency
     * （数量语义 = 想要的目标币数量，非花的星火数）；想要的数量超过星火余额能兑的上限时
     * 按星火余额封顶成交（见 {@link WalletAccountAPI#convertDigitalToCurrency}）。
     * @return 实际换得的币数（0 = 失败，显示用，可能截断到 Long.MAX）
     */
    public static long fromDigital(ServerPlayer player, ResourceLocation currency, long coins) {
        if (player == null || currency == null || coins <= 0L) return 0L;
        MinecraftServer server = player.getServer();
        if (server == null) return 0L;
        BigInteger got = WalletAccountAPI.convertDigitalToCurrency(server, player.getUUID(), currency, BigInteger.valueOf(coins));
        return got.bitLength() < 63 ? got.longValue() : Long.MAX_VALUE;
    }

    /**
     * 提取实体币：从账户扣至多 amount 枚 currency，交付走 {@link #deliverItems} 同款分层逻辑
     * （量大直接打包超级磁盘阵列，不会先塞一堆实体币进背包再匀余量）。
     * @return 实际提取（已扣账户）的枚数（显示用，可能截断到 Long.MAX）
     */
    public static long withdraw(ServerPlayer player, ResourceLocation currency, long amount) {
        if (player == null || currency == null || amount <= 0L) return 0L;
        MinecraftServer server = player.getServer();
        if (server == null) return 0L;
        Item coin = ForgeRegistries.ITEMS.getValue(currency);
        if (coin == null) return 0L;
        UUID uuid = player.getUUID();
        BigInteger bal = WalletAccountAPI.getCurrency(server, uuid, currency);
        BigInteger amt = bal.min(BigInteger.valueOf(amount));
        if (amt.signum() <= 0) return 0L;
        if (!WalletAccountAPI.tryDeductCurrency(server, uuid, currency, amt)) return 0L;
        deliverItems(player, new ItemStack(coin), amt, false, false);
        return amt.bitLength() < 63 ? amt.longValue() : Long.MAX_VALUE;
    }
}
