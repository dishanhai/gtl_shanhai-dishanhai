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

    /** 某货币 ID 的可读短名（优先物品显示名，回退到路径）。 */
    public static String coinName(ResourceLocation currency) {
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
    private static void removeItems(Player player, Item item, int amount) {
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
     * <p>交付分层（防吞币不吞货，顺序：算 affordable → 定交付方式 → 扣款 → 交付）：
     * <ul><li>aeMode 且有绑定在线 AE 提交器能全额收下 → 注入 AE 网络（SIMULATE→MODULATE）</li>
     * <li>否则货物总量 ≥ 配置阈值 → 打包成超级磁盘阵列赠送</li>
     * <li>否则进背包，装不下的余量再打包成 SDA（阈值设过大也不会卡死）</li></ul></p>
     */
    public static BulkBuyResult buyBulk(ServerPlayer player, ShopEntry entry, long times, boolean aeMode) {
        if (player == null || entry == null || !entry.isValid() || times <= 0L) {
            return new BulkBuyResult(0L, times, null);
        }
        MinecraftServer server = player.getServer();
        if (server == null) return new BulkBuyResult(0L, times, null);
        UUID uuid = player.getUUID();
        ResourceLocation currency = entry.getCurrencyId();
        long price = entry.getPrice();

        // affordable = min(times, floor(balance / price))（BigInteger 余额，结果 ≤ times ≤ long）
        long affordable;
        if (price > 0L) {
            BigInteger maxByBalance = WalletAccountAPI.getCurrency(server, uuid, currency)
                    .divide(BigInteger.valueOf(price));
            BigInteger aff = maxByBalance.min(BigInteger.valueOf(times));
            affordable = aff.signum() <= 0 ? 0L : (aff.bitLength() < 63 ? aff.longValue() : Long.MAX_VALUE);
        } else {
            affordable = times;
        }
        if (affordable <= 0L) return new BulkBuyResult(0L, times, null);

        java.math.BigInteger goodsTotal = java.math.BigInteger.valueOf(entry.getGoodsCount())
                .multiply(java.math.BigInteger.valueOf(affordable));
        ItemStack unit = entry.makeGoodsStack();
        unit.setCount(1);
        appeng.api.stacks.AEItemKey key = appeng.api.stacks.AEItemKey.of(unit);

        long threshold = com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.shopSdaPackThreshold.get();
        boolean fitsLong = goodsTotal.bitLength() < 63; // ≤ Long.MAX_VALUE，AE/背包按 long 处理
        boolean atThreshold = goodsTotal.compareTo(java.math.BigInteger.valueOf(threshold)) >= 0;

        // 定交付方式（不改动任何状态）
        String via;
        if (aeMode && key != null && fitsLong
                && com.dishanhai.gt_shanhai.common.machine.misc.FtbqAeSubmitterMachine
                        .canInjectForPlayer(player, key, goodsTotal.longValue())) {
            via = "ae";
        } else if (key != null && atThreshold) {
            via = "sda";
        } else {
            via = "inventory";
        }

        // 扣款（cost = price×affordable ≤ balance，BigInteger 防溢出，tryDeduct 必成）
        BigInteger cost = BigInteger.valueOf(price).multiply(BigInteger.valueOf(affordable));
        WalletAccountAPI.tryDeductCurrency(server, uuid, currency, cost);

        // 交付
        switch (via) {
            case "ae" -> com.dishanhai.gt_shanhai.common.machine.misc.FtbqAeSubmitterMachine
                    .injectForPlayer(player, key, goodsTotal.longValue());
            case "sda" -> packAsSda(player, key, goodsTotal);
            default -> {
                long leftover = deliverToInventory(player, unit, goodsTotal); // 返回装不下的余量
                if (leftover > 0L && key != null) {
                    packAsSda(player, key, java.math.BigInteger.valueOf(leftover));
                }
            }
        }
        return new BulkBuyResult(affordable, times, via);
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
        Item goods = entry.getGoodsItem();
        int per = entry.getGoodsCount();
        if (per <= 0) return 0L;
        int held = countItem(player, goods);
        long sell = Math.min(times, (long) (held / per));
        if (sell <= 0L) return 0L;
        removeItems(player, goods, (int) (sell * per)); // sell*per ≤ held ≤ int 上限，安全
        long gain = (long) entry.getPrice() * sell;     // price×份数，份数受背包约束不溢出
        WalletAccountAPI.addCurrency(server, player.getUUID(), entry.getCurrencyId(), BigInteger.valueOf(gain));
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
        net.minecraft.server.MinecraftServer server = player.getServer();
        if (server == null || key == null || amount.signum() <= 0) return;
        java.util.UUID uuid = java.util.UUID.randomUUID();
        ItemStack sda = new ItemStack(com.dishanhai.gt_shanhai.GTDishanhaiMod.SUPER_DISK_ARRAY.get());
        sda.getOrCreateTag().putUUID(com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory.TAG_UUID, uuid);
        java.util.Map<appeng.api.stacks.AEKey, java.math.BigInteger> amounts = new java.util.HashMap<>();
        amounts.put(key, amount);
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
}
