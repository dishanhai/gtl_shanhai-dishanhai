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

    /** 统计玩家背包内某物品总数。 */
    public static int countItem(Player player, Item item) {
        int total = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item)) {
                total += s.getCount();
            }
        }
        return total;
    }

    /** 从背包移除指定数量的物品（假定已确认足够）。 */
    public static void removeItems(Player player, Item item, int amount) {
        int remaining = amount;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !s.is(item)) continue;
            int take = Math.min(remaining, s.getCount());
            s.shrink(take);
            remaining -= take;
        }
    }

    /** 大数字紧凑显示（聊天提示用）：1234567 → "1,234,567"（保留精确值，加千分位）。 */
    public static String formatCount(long n) {
        return String.format(java.util.Locale.ROOT, "%,d", n);
    }

    // ==================== 批量购买 / 分层交付（AE注入 / SDA打包 / 背包）====================

    /** 批量购买结果：实际成交次数 done、请求次数 requested、主交付方式 via（"ae"/"sda"/"inventory"/null=没买成）。 */
    public record BulkBuyResult(long done, long requested, String via) {}

    /**
     * 批量购买。一次性按"买得起多少"结算，绝不逐次循环（防 Long.MAX 卡死服务器）。
     * 交付分层（防吞币不吞货，顺序：算 affordable → 定交付方式 → 扣款 → 交付）：
     * aeMode 且有绑定在线 AE 提交器能全额收下 → 注入 AE 网络（SIMULATE→MODULATE）
     * 否则货物总量 ≥ 配置阈值 → 打包成超级磁盘阵列赠送
     * 否则进背包，装不下的余量再打包成 SDA（阈值设过大也不会卡死）
     */
    public static BulkBuyResult buyBulk(ServerPlayer player, ShopEntry entry, long times, boolean aeMode) {
        long affordable = affordAndDeduct(player, entry, times);
        if (affordable <= 0L) return new BulkBuyResult(0L, times, null);
        String via = deliverGoodsList(player, entry, affordable, aeMode);
        return new BulkBuyResult(affordable, times, via);
    }

    /**
     * 按商品清单逐项分层交付（单商品/组合商品统一走这条路，与 {@link #buyBulkAll} 相同的批量交付基建）：
     * 每项各自 count × times，聚合后一次 {@link #deliverItemBatch} 调用，需要打包的部分合并进同一片 SDA。
     */
    private static String deliverGoodsList(ServerPlayer player, ShopEntry entry, long times, boolean aeMode) {
        java.math.BigInteger t = java.math.BigInteger.valueOf(times);
        java.util.List<ItemDelivery> deliveries = new java.util.ArrayList<>(entry.getGoodsList().size());
        for (ShopEntry.GoodsStack gs : entry.getGoodsList()) {
            ItemStack unit = gs.makeStack();
            unit.setCount(1);
            deliveries.add(new ItemDelivery(unit, java.math.BigInteger.valueOf(gs.count()).multiply(t)));
        }
        return deliverItemBatch(player, deliveries, aeMode);
    }

    /**
     * 自选（{@link ShopEntry.RewardMode#CHOICE}）购买：只交付 chosen 这一项，每次成交都在
     * {@link ShopEntry.RewardOption#rollCount()} 的区间内独立随机取数量（仿开箱，非固定值 × 次数），
     * 其余成本结算与 {@link #buyBulk} 完全一致。chosen 须由调用方从 {@link ShopEntry#getRewardPool()}
     * 按服务端下发的索引解出（不可直接信任客户端传来的物品数据）。
     */
    public static BulkBuyResult buyBulkChoice(ServerPlayer player, ShopEntry entry, long times, boolean aeMode, ShopEntry.RewardOption chosen) {
        if (chosen == null) return new BulkBuyResult(0L, times, null);
        long cappedTimes = Math.min(times, rewardRollCap());
        long affordable = affordAndDeduct(player, entry, cappedTimes);
        if (affordable <= 0L) return new BulkBuyResult(0L, times, null);
        ItemStack unit = chosen.item().copy();
        unit.setCount(1);
        java.math.BigInteger goodsTotal = rollTotal(chosen, affordable);
        String via = deliverItems(player, unit, goodsTotal, aeMode);
        return new BulkBuyResult(affordable, times, via);
    }

    /** 随机（{@link ShopEntry.RewardMode#RANDOM}）购买：每次成交都按权重独立抽 1 项 + 独立随机数量，聚合后按物品类型分别交付。 */
    public static BulkBuyResult buyBulkRandom(ServerPlayer player, ShopEntry entry, long times, boolean aeMode) {
        if (entry == null) return new BulkBuyResult(0L, times, null);
        java.util.List<ShopEntry.RewardOption> pool = entry.getRewardPool();
        if (pool.isEmpty()) return new BulkBuyResult(0L, times, null);
        long cappedTimes = Math.min(times, rewardRollCap());
        long affordable = affordAndDeduct(player, entry, cappedTimes);
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
        String via = deliverItemBatch(player, deliveries, aeMode);
        return new BulkBuyResult(affordable, times, via);
    }

    /** 全部（{@link ShopEntry.RewardMode#ALL}）购买：一次性交付奖励池每一项，各项每次成交都独立随机数量。 */
    public static BulkBuyResult buyBulkAll(ServerPlayer player, ShopEntry entry, long times, boolean aeMode) {
        if (entry == null) return new BulkBuyResult(0L, times, null);
        java.util.List<ShopEntry.RewardOption> pool = entry.getRewardPool();
        if (pool.isEmpty()) return new BulkBuyResult(0L, times, null);
        long cappedTimes = Math.min(times, rewardRollCap());
        long affordable = affordAndDeduct(player, entry, cappedTimes);
        if (affordable <= 0L) return new BulkBuyResult(0L, times, null);
        java.util.List<ItemDelivery> deliveries = new java.util.ArrayList<>();
        for (ShopEntry.RewardOption opt : pool) {
            ItemStack unit = opt.item().copy();
            unit.setCount(1);
            deliveries.add(new ItemDelivery(unit, rollTotal(opt, affordable)));
        }
        String via = deliverItemBatch(player, deliveries, aeMode);
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
    public static BulkBuyResult buyBulkFtbq(ServerPlayer player, ShopEntry entry, long times, boolean aeMode) {
        if (entry == null || !entry.hasFtbqTable()) return new BulkBuyResult(0L, times, null);
        dev.ftb.mods.ftbquests.quest.loot.RewardTable table = resolveFtbqTable(entry.getFtbqTableId());
        if (table == null) return new BulkBuyResult(0L, times, null);
        long cappedTimes = Math.min(times, rewardRollCap());
        long affordable = affordAndDeduct(player, entry, cappedTimes);
        if (affordable <= 0L) return new BulkBuyResult(0L, times, null);
        String via = deliverFtbqDraws(player, table, clampFtbqRolls(affordable), aeMode);
        return new BulkBuyResult(affordable, times, via);
    }

    /** 直接获取（作弊）+ FTBQ：不结算成本，直接从表里抽 min(times, 抽取上限) 次并交付。 */
    public static BulkBuyResult giveBulkFtbq(ServerPlayer player, ShopEntry entry, long times, boolean aeMode) {
        if (player == null || entry == null || times <= 0L || !entry.hasFtbqTable()) {
            return new BulkBuyResult(0L, times, null);
        }
        dev.ftb.mods.ftbquests.quest.loot.RewardTable table = resolveFtbqTable(entry.getFtbqTableId());
        if (table == null) return new BulkBuyResult(0L, times, null);
        long rolls = Math.min(times, rewardRollCap());
        String via = deliverFtbqDraws(player, table, clampFtbqRolls(rolls), aeMode);
        return new BulkBuyResult(rolls, times, via);
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
                                           int rolls, boolean aeMode) {
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
        return deliverItemBatch(player, deliveries, aeMode);
    }

    /**
     * 购买前置：算 affordable（min(times, 各成本通道约束)，夹到剩余限购次数）、扣成本、扣限购剩余次数并存盘。
     * {@link #buyBulk}/{@link #buyBulkChoice}/{@link #buyBulkRandom}/{@link #buyBulkAll} 共用这一步，
     * 只有随后交付什么内容不同。
     * @return 实际可成交次数（0 = 买不起/条目无效/参数非法，未做任何扣款）
     */
    private static long affordAndDeduct(ServerPlayer player, ShopEntry entry, long times) {
        if (player == null || entry == null || !entry.isValid() || times <= 0L) return 0L;
        MinecraftServer server = player.getServer();
        if (server == null) return 0L;
        UUID uuid = player.getUUID();
        ShopCost cost = entry.getCost();

        // affordable = min(times, 各成本通道约束)：星火/币种走钱包余额，物品走背包+绑定AE兜底，流体走绑定 AE
        BigInteger aff = BigInteger.valueOf(times);
        if (cost.spark.signum() > 0) {
            aff = aff.min(WalletAccountAPI.getDigital(server, uuid).divide(cost.spark));
        }
        for (java.util.Map.Entry<ResourceLocation, BigInteger> c : cost.coins.entrySet()) {
            BigInteger have = WalletAccountAPI.getCurrency(server, uuid, c.getKey()); // 账户余额
            Item coin = ForgeRegistries.ITEMS.getValue(c.getKey());
            if (coin != null) {
                have = have.add(BigInteger.valueOf(countItem(player, coin))); // + 背包实体币
                appeng.api.stacks.AEItemKey ck = appeng.api.stacks.AEItemKey.of(new ItemStack(coin));
                if (ck != null) have = have.add(BigInteger.valueOf(
                        com.dishanhai.gt_shanhai.common.machine.misc.FtbqAeSubmitterMachine
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
                available = com.dishanhai.gt_shanhai.common.machine.misc.FtbqAeSubmitterMachine
                        .availableForPlayer(player, appeng.api.stacks.AEFluidKey.of(fluid));
            } else {
                Item item = ForgeRegistries.ITEMS.getValue(in.id);
                if (item == null) return 0L;
                long inv = countItem(player, item);
                long ae = 0L;
                appeng.api.stacks.AEItemKey aek = appeng.api.stacks.AEItemKey.of(new ItemStack(item));
                if (aek != null) ae = com.dishanhai.gt_shanhai.common.machine.misc.FtbqAeSubmitterMachine
                        .availableForPlayer(player, aek);
                available = (inv > Long.MAX_VALUE - ae) ? Long.MAX_VALUE : inv + ae; // 背包+绑定AE，防溢出
            }
            aff = aff.min(BigInteger.valueOf(available / in.count));
        }
        long affordable = aff.signum() <= 0 ? 0L : (aff.bitLength() < 63 ? aff.longValue() : Long.MAX_VALUE);
        affordable = entry.clampByUses(affordable); // 限购商品：再夹到不超过剩余次数
        if (affordable <= 0L) return 0L;

        // 扣成本（affordable 已被各通道约束，各项必成，不溢出）
        BigInteger affBig = BigInteger.valueOf(affordable);
        if (cost.spark.signum() > 0) {
            WalletAccountAPI.tryDeductDigital(server, uuid, cost.spark.multiply(affBig));
        }
        for (java.util.Map.Entry<ResourceLocation, BigInteger> c : cost.coins.entrySet()) {
            BigInteger need = c.getValue().multiply(affBig);
            BigInteger fromAcct = need.min(WalletAccountAPI.getCurrency(server, uuid, c.getKey())); // 先扣账户
            if (fromAcct.signum() > 0) WalletAccountAPI.tryDeductCurrency(server, uuid, c.getKey(), fromAcct);
            BigInteger remBig = need.subtract(fromAcct);
            long rem = remBig.bitLength() < 63 ? remBig.longValue() : Long.MAX_VALUE; // 剩余走背包+AE，clamp
            if (rem > 0) {
                Item coin = ForgeRegistries.ITEMS.getValue(c.getKey());
                if (coin != null) {
                    long fromInv = Math.min(rem, countItem(player, coin)); // 再扣背包实体币
                    if (fromInv > 0) removeItems(player, coin, (int) fromInv);
                    long fromAe = rem - fromInv;                            // 再从绑定AE抽
                    if (fromAe > 0) {
                        appeng.api.stacks.AEItemKey ck = appeng.api.stacks.AEItemKey.of(new ItemStack(coin));
                        if (ck != null) com.dishanhai.gt_shanhai.common.machine.misc.FtbqAeSubmitterMachine
                                .extractForPlayer(player, ck, fromAe);
                    }
                }
            }
        }
        for (ExchangeEntry.Ingredient in : cost.physical) {
            long need = in.count * affordable; // ≤ available，不溢出
            if (in.isFluid) {
                net.minecraft.world.level.material.Fluid fluid = ForgeRegistries.FLUIDS.getValue(in.id);
                com.dishanhai.gt_shanhai.common.machine.misc.FtbqAeSubmitterMachine
                        .extractForPlayer(player, appeng.api.stacks.AEFluidKey.of(fluid), need);
            } else {
                Item item = ForgeRegistries.ITEMS.getValue(in.id);
                long fromInv = Math.min(need, countItem(player, item)); // 先扣背包
                if (fromInv > 0) removeItems(player, item, (int) fromInv);
                long fromAe = need - fromInv;                            // 不足再从绑定AE抽
                if (fromAe > 0) {
                    appeng.api.stacks.AEItemKey aek = appeng.api.stacks.AEItemKey.of(new ItemStack(item));
                    if (aek != null) com.dishanhai.gt_shanhai.common.machine.misc.FtbqAeSubmitterMachine
                            .extractForPlayer(player, aek, fromAe);
                }
            }
        }

        if (entry.isLimited()) {
            entry.consumeUses(affordable);
            ShopConfig.save(); // 限购商品：剩余次数随成交即时存盘，重启不丢
        }
        return affordable;
    }

    /**
     * 直接获取（作弊模式）：不结算任何成本，直接把 times 份商品交付给玩家。
     * 仅当 {@link ShopCheatMode#isEnabled} 为该玩家开启时，由 ShopActionPacket.doBuy 调用。
     * 交付走与购买同款分层逻辑（AE注入 / SDA打包 / 背包）。
     */
    public static BulkBuyResult giveBulk(ServerPlayer player, ShopEntry entry, long times, boolean aeMode) {
        if (player == null || entry == null || !entry.isValid() || times <= 0L) {
            return new BulkBuyResult(0L, times, null);
        }
        String via = deliverGoodsList(player, entry, times, aeMode);
        return new BulkBuyResult(times, times, via);
    }

    /** 直接获取（作弊）+ 自选：不结算成本，交付 chosen 这一项，每次独立随机数量（× min(times, 抽取上限)）。 */
    public static BulkBuyResult giveBulkChoice(ServerPlayer player, ShopEntry entry, long times, boolean aeMode, ShopEntry.RewardOption chosen) {
        if (player == null || entry == null || !entry.isValid() || times <= 0L || chosen == null) {
            return new BulkBuyResult(0L, times, null);
        }
        long rolls = Math.min(times, rewardRollCap());
        ItemStack unit = chosen.item().copy();
        unit.setCount(1);
        java.math.BigInteger goodsTotal = rollTotal(chosen, rolls);
        String via = deliverItems(player, unit, goodsTotal, aeMode);
        return new BulkBuyResult(rolls, times, via);
    }

    /** 直接获取（作弊）+ 随机：每次独立按权重抽 1 项 + 独立随机数量，聚合后交付。 */
    public static BulkBuyResult giveBulkRandom(ServerPlayer player, ShopEntry entry, long times, boolean aeMode) {
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
        String via = deliverItemBatch(player, deliveries, aeMode);
        return new BulkBuyResult(rolls, times, via);
    }

    /** 直接获取（作弊）+ 全部：一次性交付奖励池每一项，各项独立随机数量（× min(times, 抽取上限)）。 */
    public static BulkBuyResult giveBulkAll(ServerPlayer player, ShopEntry entry, long times, boolean aeMode) {
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
        String via = deliverItemBatch(player, deliveries, aeMode);
        return new BulkBuyResult(rolls, times, via);
    }

    /**
     * 分层交付一批物品给玩家（购买 / 兑换反向共用）。不扣款，只负责把 total 个 unit 送达。
     * aeMode 且绑定在线 AE 能全额收下 → 注入 AE 网络（SIMULATE→MODULATE 防吞）
     * 否则总量 ≥ 配置阈值 → 打包超级磁盘阵列赠送
     * 否则进背包，装不下的余量再打包 SDA
     * @return 主交付方式 "ae"/"sda"/"inventory"（null = 参数无效）
     */
    public static String deliverItems(net.minecraft.server.level.ServerPlayer player, ItemStack unit,
                                      java.math.BigInteger total, boolean aeMode) {
        if (player == null || unit == null || unit.isEmpty() || total == null || total.signum() <= 0) return null;
        appeng.api.stacks.AEItemKey key = appeng.api.stacks.AEItemKey.of(unit);
        long threshold = com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.shopSdaPackThreshold.get();
        boolean fitsLong = total.bitLength() < 63; // ≤ Long.MAX_VALUE，AE/背包按 long 处理
        boolean atThreshold = total.compareTo(java.math.BigInteger.valueOf(threshold)) >= 0;
        String via;
        if (aeMode && key != null && fitsLong
                && com.dishanhai.gt_shanhai.common.machine.misc.FtbqAeSubmitterMachine
                        .canInjectForPlayer(player, key, total.longValue())) {
            via = "ae";
        } else if (key != null && atThreshold) {
            via = "sda";
        } else {
            via = "inventory";
        }
        switch (via) {
            case "ae" -> com.dishanhai.gt_shanhai.common.machine.misc.FtbqAeSubmitterMachine
                    .injectForPlayer(player, key, total.longValue());
            case "sda" -> packAsSda(player, key, total);
            default -> {
                long leftover = deliverToInventory(player, unit, total); // 返回装不下的余量
                if (leftover > 0L && key != null) {
                    packAsSda(player, key, java.math.BigInteger.valueOf(leftover));
                }
            }
        }
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
     * @return 主交付方式："sda"（只要有任意物品打了包就报这个，信息量最大）
     *         /"ae"（全部进了 AE，没有任何打包/进背包）/"inventory"（全部进了背包，没有任何 AE/打包）
     *         /null（没有任何有效物品）
     */
    public static String deliverItemBatch(net.minecraft.server.level.ServerPlayer player,
                                          java.util.List<ItemDelivery> deliveries, boolean aeMode) {
        if (player == null || deliveries == null || deliveries.isEmpty()) return null;
        long threshold = com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.shopSdaPackThreshold.get();
        java.util.Map<appeng.api.stacks.AEKey, java.math.BigInteger> sdaBatch = new java.util.LinkedHashMap<>();
        boolean anyAe = false, anyInventory = false;
        for (ItemDelivery d : deliveries) {
            ItemStack unit = d.unit();
            java.math.BigInteger total = d.total();
            if (unit == null || unit.isEmpty() || total == null || total.signum() <= 0) continue;
            appeng.api.stacks.AEItemKey key = appeng.api.stacks.AEItemKey.of(unit);
            boolean fitsLong = total.bitLength() < 63;
            boolean atThreshold = total.compareTo(java.math.BigInteger.valueOf(threshold)) >= 0;
            if (aeMode && key != null && fitsLong
                    && com.dishanhai.gt_shanhai.common.machine.misc.FtbqAeSubmitterMachine
                            .canInjectForPlayer(player, key, total.longValue())) {
                com.dishanhai.gt_shanhai.common.machine.misc.FtbqAeSubmitterMachine
                        .injectForPlayer(player, key, total.longValue());
                anyAe = true;
            } else if (key != null && atThreshold) {
                sdaBatch.merge(key, total, java.math.BigInteger::add);
            } else {
                long leftover = deliverToInventory(player, unit, total); // 返回装不下的余量
                anyInventory = true;
                if (leftover > 0L && key != null) {
                    sdaBatch.merge(key, java.math.BigInteger.valueOf(leftover), java.math.BigInteger::add);
                }
            }
        }
        boolean anySda = !sdaBatch.isEmpty();
        if (anySda) packAsSdaBatch(player, sdaBatch);
        if (anySda) return "sda";
        if (anyAe) return "ae";
        if (anyInventory) return "inventory";
        return null;
    }

    /**
     * 批量出售。一次性结算，成交量受背包持有量（int 上限）天然约束。
     * @return 实际出售份数
     */
    public static long sellBulk(ServerPlayer player, ShopEntry entry, long times) {
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
        int held = countItem(player, goods);
        long sell = Math.min(times, (long) (held / per));
        sell = entry.clampByUses(sell); // 限购商品：出售与购买共享同一剩余次数
        if (sell <= 0L) return 0L;
        removeItems(player, goods, (int) (sell * per)); // sell*per ≤ held ≤ int 上限，安全
        // 退回纯钱包成本（星火 + 币种）× 份数
        BigInteger sellBig = BigInteger.valueOf(sell);
        UUID uuid = player.getUUID();
        if (cost.spark.signum() > 0) {
            WalletAccountAPI.addDigital(server, uuid, cost.spark.multiply(sellBig));
        }
        for (java.util.Map.Entry<ResourceLocation, BigInteger> c : cost.coins.entrySet()) {
            WalletAccountAPI.addCurrency(server, uuid, c.getKey(), c.getValue().multiply(sellBig));
        }
        if (entry.isLimited()) {
            entry.consumeUses(sell);
            ShopConfig.save(); // 限购商品：剩余次数随成交即时存盘，重启不丢
        }
        return sell;
    }

    /**
     * 尽量塞进背包，返回装不下的余量（由调用方打包 SDA）。只塞不丢，避免海量掉落卡顿。
     */
    private static long deliverToInventory(net.minecraft.server.level.ServerPlayer player, ItemStack unit, java.math.BigInteger count) {
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
        return remaining.bitLength() < 63 ? remaining.longValue() : Long.MAX_VALUE;
    }

    /**
     * 打包成一个预装 amount 个 key 的超级磁盘阵列，发给玩家（装不下则掉落）。
     * 直接写服务端 backend（BigInteger 无上限）；玩家首 tick 由 SDA "取出即分家" 自动认领私有 UUID。
     */
    private static void packAsSda(net.minecraft.server.level.ServerPlayer player,
                                  appeng.api.stacks.AEItemKey key, java.math.BigInteger amount) {
        if (key == null || amount.signum() <= 0) return;
        java.util.Map<appeng.api.stacks.AEKey, java.math.BigInteger> amounts = new java.util.LinkedHashMap<>();
        amounts.put(key, amount);
        packAsSdaBatch(player, amounts);
    }

    /**
     * 打包成一个装着<b>多种</b> key（各自 amount）的超级磁盘阵列，发给玩家（装不下则掉落）。
     * {@link #packAsSda} 单物品版就是套一层这个方法；{@link #deliverItemBatch} 靠这个把一次
     * 抽奖里所有需要打包的物品合并进同一片磁盘，不再一种物品一个磁盘阵列。
     */
    private static void packAsSdaBatch(net.minecraft.server.level.ServerPlayer player,
                                       java.util.Map<appeng.api.stacks.AEKey, java.math.BigInteger> amounts) {
        if (player == null || amounts == null || amounts.isEmpty()) return;
        net.minecraft.server.MinecraftServer server = player.getServer();
        if (server == null) return;
        java.util.UUID uuid = java.util.UUID.randomUUID();
        ItemStack sda = new ItemStack(com.dishanhai.gt_shanhai.GTDishanhaiMod.SUPER_DISK_ARRAY.get());
        sda.getOrCreateTag().putUUID(com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory.TAG_UUID, uuid);
        com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData.get(server)
                .updateCellBig(uuid, "sda", com.dishanhai.gt_shanhai.common.item.SuperDiskArrayItem.TOTAL_BYTES, amounts);
        if (!player.getInventory().add(sda)) player.drop(sda, false);
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

    /**
     * 币种兑换：把账户里 amount 个 from 币按汇率换成 to 币。
     * 目标量 = floor(amount × from币值 / to币值)，BigInteger 全程无损；
     * 目标量≤0 或余额不足或币值未配置 → 返回 0（不动账户）。账户加满额目标量，返回值仅为聊天显示截断到 Long.MAX。
     * @return 换得的 to 币数量（显示用，可能截断）
     */
    public static long exchange(ServerPlayer player, ResourceLocation from, ResourceLocation to, long amount) {
        if (player == null || from == null || to == null || from.equals(to) || amount <= 0L) return 0L;
        MinecraftServer server = player.getServer();
        if (server == null) return 0L;
        UUID uuid = player.getUUID();
        long fromValue = CurrencyRateConfig.getValue(from);
        long toValue = CurrencyRateConfig.getValue(to);
        if (fromValue <= 0L || toValue <= 0L) return 0L; // 未配置币值不可兑换
        BigInteger amt = BigInteger.valueOf(amount);
        if (WalletAccountAPI.getCurrency(server, uuid, from).compareTo(amt) < 0) return 0L;
        BigInteger dst = amt.multiply(BigInteger.valueOf(fromValue)).divide(BigInteger.valueOf(toValue));
        if (dst.signum() <= 0) return 0L; // 源量太小，换不出 1 个目标币
        if (!WalletAccountAPI.tryDeductCurrency(server, uuid, from, amt)) return 0L;
        WalletAccountAPI.addCurrency(server, uuid, to, dst);
        return dst.bitLength() < 63 ? dst.longValue() : Long.MAX_VALUE;
    }

    /**
     * AE 抽取：从绑定的在线 AE 提交器网络抽取 amount 个该币种，存入账户余额。
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
        long got = com.dishanhai.gt_shanhai.common.machine.misc.FtbqAeSubmitterMachine
                .extractForPlayer(player, key, amount);
        if (got > 0L) WalletAccountAPI.addCurrency(server, player.getUUID(), currency, BigInteger.valueOf(got));
        return got;
    }

    /**
     * 币种 → 星火（数字余额）：按币值把 coins 枚 currency 换成星火。
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
     * 星火（数字余额）→ 币种：花 {@code coins × 币值} 星火，换恰好 coins 枚 currency
     * （数量语义 = 想要的目标币数量，非花的星火数）。星火不足则整体不动。
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
        deliverItems(player, new ItemStack(coin), amt, false);
        return amt.bitLength() < 63 ? amt.longValue() : Long.MAX_VALUE;
    }
}
