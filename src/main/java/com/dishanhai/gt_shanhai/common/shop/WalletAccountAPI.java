package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.WalletAccountSyncPacket;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 钱包账户服务端读写 API（山海署名）。全部按<b>玩家本体 UUID</b> 操作
 * {@link WalletAccountSavedData}，数值一律 BigInteger（真无限，不封顶，扣到 0 不为负）。
 *
 * <p>双账本：币种余额（商店结算）+ 数字余额（星火，兑换/多物品流体）。二者按
 * {@link CurrencyRateConfig#getValue 币值} 互转（{@link #convertCurrencyToDigital} /
 * {@link #convertDigitalToCurrency}）。</p>
 *
 * <p><b>仅服务端调用</b>：客户端界面读 {@code ClientWalletAccount} 快照缓存（阶段 C）。</p>
 */
public final class WalletAccountAPI {

    /**
     * 星火（数字余额）作为"伪货币"的保留 ID。商店条目把结算货币填成此值即表示
     * <b>用数字余额（星火）付款/收款</b>，而非某个实体币种。此 ID 不对应任何注册物品。
     */
    public static final ResourceLocation SPARK = new ResourceLocation("gt_shanhai", "spark");

    /** 判断某货币 ID 是否为星火（数字余额伪货币）。 */
    public static boolean isSpark(ResourceLocation id) {
        return SPARK.equals(id);
    }

    private WalletAccountAPI() {}

    private static WalletAccountSavedData data(MinecraftServer server) {
        return WalletAccountSavedData.get(server);
    }

    // ===================== 币种余额 =====================

    public static BigInteger getCurrency(MinecraftServer server, UUID uuid, ResourceLocation currency) {
        WalletAccount acc = data(server).get(uuid);
        return acc == null ? BigInteger.ZERO : acc.getCurrency(currency);
    }

    /** 增减某币种余额（delta 可负，扣到 0 封底）。 */
    public static void addCurrency(MinecraftServer server, UUID uuid, ResourceLocation currency, BigInteger delta) {
        if (delta == null || delta.signum() == 0 || currency == null) return;
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.getOrCreate(uuid);
        BigInteger next = acc.getCurrency(currency).add(delta);
        if (next.signum() < 0) next = BigInteger.ZERO;
        acc.setCurrency(currency, next);
        d.setDirty();
    }

    /** 尝试扣某币种，余额不足则不扣返回 false。 */
    public static boolean tryDeductCurrency(MinecraftServer server, UUID uuid, ResourceLocation currency, BigInteger cost) {
        if (cost == null || cost.signum() <= 0) return true;
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.getOrCreate(uuid);
        BigInteger bal = acc.getCurrency(currency);
        if (bal.compareTo(cost) < 0) return false;
        acc.setCurrency(currency, bal.subtract(cost));
        d.setDirty();
        return true;
    }

    /** 读全部币种余额（副本，保序）。 */
    public static Map<ResourceLocation, BigInteger> getAllCurrencies(MinecraftServer server, UUID uuid) {
        WalletAccount acc = data(server).get(uuid);
        return acc == null ? new LinkedHashMap<>() : acc.getCurrencyBalances();
    }

    // ===================== 数字余额（星火） =====================

    public static BigInteger getDigital(MinecraftServer server, UUID uuid) {
        WalletAccount acc = data(server).get(uuid);
        return acc == null ? BigInteger.ZERO : acc.getDigital();
    }

    public static void addDigital(MinecraftServer server, UUID uuid, BigInteger delta) {
        if (delta == null || delta.signum() == 0) return;
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.getOrCreate(uuid);
        BigInteger next = acc.getDigital().add(delta);
        if (next.signum() < 0) next = BigInteger.ZERO;
        acc.setDigital(next);
        d.setDirty();
    }

    public static boolean tryDeductDigital(MinecraftServer server, UUID uuid, BigInteger cost) {
        if (cost == null || cost.signum() <= 0) return true;
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.getOrCreate(uuid);
        BigInteger bal = acc.getDigital();
        if (bal.compareTo(cost) < 0) return false;
        acc.setDigital(bal.subtract(cost));
        d.setDirty();
        return true;
    }

    // ===================== 会员（付费直购，永久买断，见 ShopMembership） =====================

    /** 当前会员档位（-1=未购买任何档位，0/1/2=青铜/白银/黄金）。 */
    public static int getMemberTier(MinecraftServer server, UUID uuid) {
        WalletAccount acc = data(server).get(uuid);
        return acc == null ? -1 : acc.getMemberTier();
    }

    /**
     * 购买/升级会员档位：永久买断，直接付目标档位全价（见 {@link ShopMembership#priceOf}），
     * 不退旧档位已花的钱、不补差价；已拥有档位 ≥ 目标档位时拒绝（防降级/重复花钱）；
     * 星火余额不足同样拒绝，不扣款。
     * @return 是否购买成功
     */
    public static boolean buyMemberTier(MinecraftServer server, UUID uuid, int targetTier) {
        if (targetTier < 0 || targetTier >= ShopMembership.tierCount()) return false;
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.getOrCreate(uuid);
        if (acc.getMemberTier() >= targetTier) return false;
        BigInteger price = BigInteger.valueOf(ShopMembership.priceOf(targetTier));
        if (acc.getDigital().compareTo(price) < 0) return false;
        acc.setDigital(acc.getDigital().subtract(price));
        acc.setMemberTier(targetTier);
        d.setDirty();
        return true;
    }

    // ===================== 银行：定期存款（利滚利，见 ShopBank） =====================

    /** 结算存款利息（折进本金、刷新计息起点），账户有变动才标脏，返回是否有变动。 */
    private static boolean settleDeposit(WalletAccount acc) {
        long now = System.currentTimeMillis();
        long rate = com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.shopBankDepositRateBpPerHour.get();
        BigInteger interest = ShopBank.accrue(acc.getBankDeposit(), rate, now - acc.getBankDepositLastMs());
        if (interest.signum() <= 0) {
            if (acc.getBankDepositLastMs() <= 0L) acc.setBankDepositLastMs(now); // 首次记账起点，不算变动不标脏
            return false;
        }
        acc.setBankDeposit(acc.getBankDeposit().add(interest));
        acc.setBankDepositLastMs(now);
        return true;
    }

    /** 读当前定期存款本息合计（惰性结算最新利息）；无存档返回 0。 */
    public static BigInteger getBankDeposit(MinecraftServer server, UUID uuid) {
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.get(uuid);
        if (acc == null) return BigInteger.ZERO;
        if (settleDeposit(acc)) d.setDirty();
        return acc.getBankDeposit();
    }

    /** 存入：数字余额（星火）→ 定期存款本金。返回实际存入量（余额不足按余额封顶，0=没存进去）。 */
    public static BigInteger bankDeposit(MinecraftServer server, UUID uuid, BigInteger amount) {
        if (amount == null || amount.signum() <= 0) return BigInteger.ZERO;
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.getOrCreate(uuid);
        settleDeposit(acc);
        BigInteger take = acc.getDigital().min(amount);
        if (take.signum() <= 0) return BigInteger.ZERO;
        acc.setDigital(acc.getDigital().subtract(take));
        acc.setBankDeposit(acc.getBankDeposit().add(take));
        if (acc.getBankDepositLastMs() <= 0L) acc.setBankDepositLastMs(System.currentTimeMillis());
        d.setDirty();
        return take;
    }

    /** 取出：定期存款本息 → 数字余额（星火）。返回实际取出量（存款不足按存款封顶，0=没取到）。 */
    public static BigInteger bankWithdraw(MinecraftServer server, UUID uuid, BigInteger amount) {
        if (amount == null || amount.signum() <= 0) return BigInteger.ZERO;
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.getOrCreate(uuid);
        settleDeposit(acc);
        BigInteger take = acc.getBankDeposit().min(amount);
        if (take.signum() <= 0) return BigInteger.ZERO;
        acc.setBankDeposit(acc.getBankDeposit().subtract(take));
        acc.setDigital(acc.getDigital().add(take));
        d.setDirty();
        return take;
    }

    // ===================== 银行：贷款（复利越滚越多，见 ShopBank） =====================

    private static boolean settleDebt(WalletAccount acc) {
        long now = System.currentTimeMillis();
        long rate = com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.shopBankLoanRateBpPerHour.get();
        BigInteger interest = ShopBank.accrue(acc.getBankDebt(), rate, now - acc.getBankDebtLastMs());
        if (interest.signum() <= 0) {
            if (acc.getBankDebtLastMs() <= 0L) acc.setBankDebtLastMs(now);
            return false;
        }
        acc.setBankDebt(acc.getBankDebt().add(interest));
        acc.setBankDebtLastMs(now);
        return true;
    }

    /** 读当前欠款本息合计（惰性结算最新利息）；无存档返回 0。 */
    public static BigInteger getBankDebt(MinecraftServer server, UUID uuid) {
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.get(uuid);
        if (acc == null) return BigInteger.ZERO;
        if (settleDebt(acc)) d.setDirty();
        return acc.getBankDebt();
    }

    /**
     * 借款：新增欠款本金 + 等额加进数字余额（星火），受配置最大欠款上限约束（见
     * {@code DShanhaiConfig.COMMON.shopBankMaxLoanSpark}）。返回实际借到的量（0=已到上限/无效请求）。
     * 无强制追讨/抵押没收机制——欠款只会持续计息累积，靠数字倒逼玩家自觉还款。
     */
    public static BigInteger bankBorrow(MinecraftServer server, UUID uuid, BigInteger amount) {
        if (amount == null || amount.signum() <= 0) return BigInteger.ZERO;
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.getOrCreate(uuid);
        settleDebt(acc);
        BigInteger cap = BigInteger.valueOf(com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.shopBankMaxLoanSpark.get());
        BigInteger room = cap.subtract(acc.getBankDebt());
        if (room.signum() <= 0) return BigInteger.ZERO;
        BigInteger take = room.min(amount);
        acc.setBankDebt(acc.getBankDebt().add(take));
        if (acc.getBankDebtLastMs() <= 0L) acc.setBankDebtLastMs(System.currentTimeMillis());
        acc.setDigital(acc.getDigital().add(take));
        d.setDirty();
        return take;
    }

    /** 还款：数字余额（星火）→ 冲抵欠款。返回实际还款量（余额/欠款取小者封顶，0=没还成）。 */
    public static BigInteger bankRepay(MinecraftServer server, UUID uuid, BigInteger amount) {
        if (amount == null || amount.signum() <= 0) return BigInteger.ZERO;
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.getOrCreate(uuid);
        settleDebt(acc);
        BigInteger pay = acc.getDigital().min(acc.getBankDebt()).min(amount);
        if (pay.signum() <= 0) return BigInteger.ZERO;
        acc.setDigital(acc.getDigital().subtract(pay));
        acc.setBankDebt(acc.getBankDebt().subtract(pay));
        d.setDirty();
        return pay;
    }

    // ===================== 已购买次数（展示用统计） =====================

    /**
     * 商品条目的稳定统计 key：goodsId+category（与 {@code ShopActionPacket#locate} 同一定位口径）。
     * 同 goodsId 不同 NBT 的多条目（如各种超级磁盘阵列）会共享同一份统计，这是已知取舍——
     * 展示"已购买次数"是给玩家看的参考数字，不追求逐条目精确到 NBT 级别。
     */
    public static String purchaseKey(ResourceLocation goodsId, String category) {
        return goodsId + "|" + (category == null ? "" : category);
    }

    public static long getPurchaseCount(MinecraftServer server, UUID uuid, String key) {
        WalletAccount acc = data(server).get(uuid);
        return acc == null ? 0L : acc.getPurchaseCount(key);
    }

    /** 累加已购买次数（delta 通常为本次成交次数，见 {@code ShopActionPacket#doBuy}）。 */
    public static void addPurchaseCount(MinecraftServer server, UUID uuid, String key, long delta) {
        if (key == null || delta == 0L) return;
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.getOrCreate(uuid);
        acc.addPurchaseCount(key, delta);
        d.setDirty();
    }

    /** 读全部已购买次数统计（副本，保序）。 */
    public static Map<String, Long> getAllPurchaseCounts(MinecraftServer server, UUID uuid) {
        WalletAccount acc = data(server).get(uuid);
        return acc == null ? new LinkedHashMap<>() : acc.getPurchaseCounts();
    }

    // ===================== 周期限购（每玩家独立计数，见 ShopPeriodLimiter） =====================

    public static long getPeriodWindow(MinecraftServer server, UUID uuid, String key) {
        WalletAccount acc = data(server).get(uuid);
        return acc == null ? -1L : acc.getPeriodWindow(key);
    }

    public static long getPeriodUsed(MinecraftServer server, UUID uuid, String key) {
        WalletAccount acc = data(server).get(uuid);
        return acc == null ? 0L : acc.getPeriodUsed(key);
    }

    /** 覆盖写某商品条目 key 的周期窗口状态。 */
    public static void setPeriodState(MinecraftServer server, UUID uuid, String key, long window, long used) {
        if (key == null) return;
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.getOrCreate(uuid);
        acc.setPeriodState(key, window, used);
        d.setDirty();
    }

    /** 读全部 key 的开窗锚点（副本，保序），随账户快照一起推给客户端展示"剩余刷新倒计时"。 */
    public static Map<String, Long> getAllPeriodAnchors(MinecraftServer server, UUID uuid) {
        WalletAccount acc = data(server).get(uuid);
        return acc == null ? new LinkedHashMap<>() : acc.getPeriodWindows();
    }

    // ===================== 币值互转（阶段 B） =====================

    /**
     * 币种 → 数字余额：扣 count 枚币种，加 {@code count × 币值} 数字余额。
     * 请求量超过账户余额时按<b>余额封顶</b>成交（而非要求精确匹配整体失败），
     * 封顶后仍为 0 或币值≤0（未配置）才返回 0。
     */
    public static BigInteger convertCurrencyToDigital(MinecraftServer server, UUID uuid, ResourceLocation currency, BigInteger count) {
        if (count == null || count.signum() <= 0 || currency == null) return BigInteger.ZERO;
        long value = CurrencyRateConfig.getValue(currency);
        if (value <= 0L) return BigInteger.ZERO;
        BigInteger amt = getCurrency(server, uuid, currency).min(count); // 余额不足按余额最大值成交
        if (amt.signum() <= 0) return BigInteger.ZERO;
        if (!tryDeductCurrency(server, uuid, currency, amt)) return BigInteger.ZERO;
        BigInteger gain = amt.multiply(BigInteger.valueOf(value));
        addDigital(server, uuid, gain);
        return gain;
    }

    /**
     * 数字余额 → 币种：花 {@code coins × 币值} 星火，换 {@code coins} 枚币种
     * （数量语义 = 想要的目标币数量，与其余 ATM 操作一致，非"花的星火数"）。
     * 想要的数量超过星火余额能兑的上限时按<b>星火余额封顶</b>成交（而非要求精确匹配整体失败），
     * 封顶后仍为 0 或币值≤0（未配置）才返回 0。
     */
    public static BigInteger convertDigitalToCurrency(MinecraftServer server, UUID uuid, ResourceLocation currency, BigInteger coinsWanted) {
        if (coinsWanted == null || coinsWanted.signum() <= 0 || currency == null) return BigInteger.ZERO;
        long value = CurrencyRateConfig.getValue(currency);
        if (value <= 0L) return BigInteger.ZERO;
        BigInteger affordable = getDigital(server, uuid).divide(BigInteger.valueOf(value)); // 星火余额最多能换的币数
        BigInteger coins = coinsWanted.min(affordable); // 星火不足按余额最大值成交
        if (coins.signum() <= 0) return BigInteger.ZERO;
        BigInteger spend = coins.multiply(BigInteger.valueOf(value));
        if (!tryDeductDigital(server, uuid, spend)) return BigInteger.ZERO;
        addCurrency(server, uuid, currency, coins);
        return coins;
    }

    // ===================== 客户端同步 =====================

    /** 向该玩家推送其账户全量快照（打开钱包 / 每次账户变动后调用）。 */
    public static void sync(ServerPlayer player) {
        if (player == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        UUID uuid = player.getUUID();
        ShanhaiNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new WalletAccountSyncPacket(getAllCurrencies(server, uuid), getDigital(server, uuid),
                        getAllPurchaseCounts(server, uuid), getAllPeriodAnchors(server, uuid),
                        ShopWirelessEu.getBalance(player), ShopAeNetwork.hasBoundNetwork(player),
                        getMemberTier(server, uuid)));
    }
}
