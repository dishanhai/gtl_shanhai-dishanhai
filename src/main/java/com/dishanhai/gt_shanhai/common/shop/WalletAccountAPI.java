package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
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

    /** 会员购买结果。失败不扣款。 */
    public enum MemberBuyResult {
        OK,
        INVALID,
        ALREADY_OWNED,
        INSUFFICIENT
    }

    /**
     * 购买/升级会员档位：永久买断，直接付目标档位全价（见 {@link ShopMembership#priceOf}），
     * 不退旧档位已花的钱、不补差价；已拥有档位 ≥ 目标档位时拒绝（防降级/重复花钱）；
     * 星火余额不足同样拒绝，不扣款。
     */
    public static MemberBuyResult buyMemberTier(MinecraftServer server, UUID uuid, int targetTier) {
        if (targetTier < 0 || targetTier >= ShopMembership.tierCount()) return MemberBuyResult.INVALID;
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.getOrCreate(uuid);
        if (acc.getMemberTier() >= targetTier) return MemberBuyResult.ALREADY_OWNED;
        BigInteger price = BigInteger.valueOf(ShopMembership.priceOf(targetTier));
        if (acc.getDigital().compareTo(price) < 0) return MemberBuyResult.INSUFFICIENT;
        acc.setDigital(acc.getDigital().subtract(price));
        acc.setMemberTier(targetTier);
        d.setDirty();
        return MemberBuyResult.OK;
    }

    // ===================== 银行：定期存款 / 贷款（线性单利，见 ShopBank） =====================

    /** 打开银行或执行指令时用的快照。利息已经按当前墙钟结过。 */
    public static final class BankView {
        public final BigInteger depositPrincipal;
        public final BigInteger depositInterest;
        public final BigInteger debtPrincipal;
        public final BigInteger debtInterest;
        public final BigInteger loanRoom;
        public final int depositRateBp;
        public final int loanRateBp;
        public final long maxLoan;
        /** 到期后仍有欠款。为真时 {@link #loanRoom} 为 0，借款会被拒绝。 */
        public final boolean overdue;

        public BankView(BigInteger depositPrincipal, BigInteger depositInterest,
                        BigInteger debtPrincipal, BigInteger debtInterest, BigInteger loanRoom,
                        int depositRateBp, int loanRateBp, long maxLoan, boolean overdue) {
            this.depositPrincipal = depositPrincipal;
            this.depositInterest = depositInterest;
            this.debtPrincipal = debtPrincipal;
            this.debtInterest = debtInterest;
            this.loanRoom = loanRoom;
            this.depositRateBp = depositRateBp;
            this.loanRateBp = loanRateBp;
            this.maxLoan = maxLoan;
            this.overdue = overdue;
        }

        public BigInteger depositTotal() {
            return depositPrincipal.add(depositInterest);
        }

        public BigInteger debtTotal() {
            return debtPrincipal.add(debtInterest);
        }
    }

    /** 读当前定期存款本息合计（惰性结算最新利息）；无存档返回 0。 */
    public static BigInteger getBankDeposit(MinecraftServer server, UUID uuid) {
        return bankView(server, uuid).depositTotal();
    }

    /** 读当前欠款本息合计（惰性结算最新利息）；无存档返回 0。 */
    public static BigInteger getBankDebt(MinecraftServer server, UUID uuid) {
        return bankView(server, uuid).debtTotal();
    }

    /** 结清两边利息并返回本金、利息、可借额度和服务器利率。无账户也不创建存档。 */
    public static BankView bankView(MinecraftServer server, UUID uuid) {
        int depositRate = DShanhaiConfig.COMMON.shopBankDepositRateBpPerHour.get();
        int loanRate = DShanhaiConfig.COMMON.shopBankLoanRateBpPerHour.get();
        long cap = DShanhaiConfig.COMMON.shopBankMaxLoanSpark.get();
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.get(uuid);
        if (acc == null) {
            return new BankView(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                    BigInteger.valueOf(Math.max(0L, cap)), depositRate, loanRate, cap, false);
        }
        long now = System.currentTimeMillis();
        ShopBank.Book deposit = ShopBank.settle(depositBook(acc), now, depositRate);
        ShopBank.Book debt = ShopBank.settle(debtBook(acc), now, loanRate);
        boolean dirty = applyDepositBook(acc, deposit) | applyDebtBook(acc, debt) | syncDebtDue(acc, debt, now);
        if (dirty) d.setDirty();
        boolean overdue = ShopBank.overdue(debt.total(), acc.getBankDebtDueMs(), now);
        BigInteger room = overdue ? BigInteger.ZERO : ShopBank.room(debt, BigInteger.valueOf(cap));
        return new BankView(deposit.principal, deposit.interest, debt.principal, debt.interest,
                room, depositRate, loanRate, cap, overdue);
    }

    /** 存入：数字余额（星火）→ 定期存款本金。返回实际存入量（余额不足按余额封顶，0=没存进去）。 */
    public static BigInteger bankDeposit(MinecraftServer server, UUID uuid, BigInteger amount) {
        if (amount == null || amount.signum() <= 0) return BigInteger.ZERO;
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.getOrCreate(uuid);
        long now = System.currentTimeMillis();
        ShopBank.Move mv = ShopBank.deposit(depositBook(acc), acc.getDigital(), amount, now, depositRate());
        return finishDeposit(d, acc, mv);
    }

    /** 把当前星火余额全部存入。 */
    public static BigInteger bankDepositAll(MinecraftServer server, UUID uuid) {
        WalletAccount acc = data(server).get(uuid);
        BigInteger digital = acc == null ? BigInteger.ZERO : acc.getDigital();
        return bankDeposit(server, uuid, digital);
    }

    /** 取出：先取利息再取本金，转入数字余额。返回实际取出量。 */
    public static BigInteger bankWithdraw(MinecraftServer server, UUID uuid, BigInteger amount) {
        if (amount == null || amount.signum() <= 0) return BigInteger.ZERO;
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.getOrCreate(uuid);
        long now = System.currentTimeMillis();
        ShopBank.Move mv = ShopBank.withdraw(depositBook(acc), amount, now, depositRate());
        return finishWithdraw(d, acc, mv);
    }

    /** 取出全部存款本息。 */
    public static BigInteger bankWithdrawAll(MinecraftServer server, UUID uuid) {
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.getOrCreate(uuid);
        long now = System.currentTimeMillis();
        long rate = depositRate();
        ShopBank.Book settled = ShopBank.settle(depositBook(acc), now, rate);
        ShopBank.Move mv = ShopBank.withdraw(settled, settled.total(), now, rate);
        return finishWithdraw(d, acc, mv);
    }

    /**
     * 借款：新增欠款本金 + 等额加进数字余额（星火）。上限比较的是本金加利息。
     * 到期后仍有欠款时直接拒绝，直到还清。返回实际借到的量（0=逾期、已到上限或无效请求）。
     */
    public static BigInteger bankBorrow(MinecraftServer server, UUID uuid, BigInteger amount) {
        if (amount == null || amount.signum() <= 0) return BigInteger.ZERO;
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.getOrCreate(uuid);
        long now = System.currentTimeMillis();
        long rate = loanRate();
        ShopBank.Book settled = ShopBank.settle(debtBook(acc), now, rate);
        boolean changed = applyDebtBook(acc, settled) | syncDebtDue(acc, settled, now);
        if (ShopBank.overdue(settled.total(), acc.getBankDebtDueMs(), now)) {
            if (changed) d.setDirty();
            return BigInteger.ZERO;
        }
        boolean hadDebt = settled.total().signum() > 0;
        ShopBank.Move mv = ShopBank.borrow(settled, amount, loanCap(), now, rate);
        changed |= applyDebtBook(acc, mv.book);
        if (mv.amount.signum() <= 0) {
            if (changed) d.setDirty();
            return BigInteger.ZERO;
        }
        if (!hadDebt) acc.setBankDebtDueMs(ShopBank.freshDue(now, loanTermMs()));
        acc.setDigital(acc.getDigital().add(mv.amount));
        d.setDirty();
        return mv.amount;
    }

    /** 还款：数字余额先冲利息，再冲本金。返回实际还款量。 */
    public static BigInteger bankRepay(MinecraftServer server, UUID uuid, BigInteger amount) {
        if (amount == null || amount.signum() <= 0) return BigInteger.ZERO;
        WalletAccountSavedData d = data(server);
        WalletAccount acc = d.getOrCreate(uuid);
        long now = System.currentTimeMillis();
        ShopBank.Move mv = ShopBank.repay(debtBook(acc), acc.getDigital(), amount, now, loanRate());
        return finishRepay(d, acc, mv);
    }

    /** 用当前星火余额能还多少就还多少。 */
    public static BigInteger bankRepayAll(MinecraftServer server, UUID uuid) {
        WalletAccount acc = data(server).get(uuid);
        BigInteger digital = acc == null ? BigInteger.ZERO : acc.getDigital();
        return bankRepay(server, uuid, digital);
    }

    private static BigInteger finishDeposit(WalletAccountSavedData data, WalletAccount acc, ShopBank.Move mv) {
        boolean changed = applyDepositBook(acc, mv.book);
        if (mv.amount.signum() <= 0) {
            if (changed) data.setDirty();
            return BigInteger.ZERO;
        }
        acc.setDigital(acc.getDigital().subtract(mv.amount));
        data.setDirty();
        return mv.amount;
    }

    private static BigInteger finishWithdraw(WalletAccountSavedData data, WalletAccount acc, ShopBank.Move mv) {
        boolean changed = applyDepositBook(acc, mv.book);
        if (mv.amount.signum() <= 0) {
            if (changed) data.setDirty();
            return BigInteger.ZERO;
        }
        acc.setDigital(acc.getDigital().add(mv.amount));
        data.setDirty();
        return mv.amount;
    }

    private static BigInteger finishRepay(WalletAccountSavedData data, WalletAccount acc, ShopBank.Move mv) {
        boolean changed = applyDebtBook(acc, mv.book);
        if (mv.amount.signum() <= 0) {
            if (changed) data.setDirty();
            return BigInteger.ZERO;
        }
        acc.setDigital(acc.getDigital().subtract(mv.amount));
        data.setDirty();
        return mv.amount;
    }

    private static ShopBank.Book depositBook(WalletAccount acc) {
        return new ShopBank.Book(acc.getBankDeposit(), acc.getBankDepositInterest(), acc.getBankDepositLastMs());
    }

    private static ShopBank.Book debtBook(WalletAccount acc) {
        return new ShopBank.Book(acc.getBankDebt(), acc.getBankDebtInterest(), acc.getBankDebtLastMs());
    }

    private static boolean applyDepositBook(WalletAccount acc, ShopBank.Book book) {
        boolean changed = acc.getBankDeposit().compareTo(book.principal) != 0
                || acc.getBankDepositInterest().compareTo(book.interest) != 0
                || acc.getBankDepositLastMs() != book.lastMs;
        if (!changed) return false;
        acc.setBankDeposit(book.principal);
        acc.setBankDepositInterest(book.interest);
        acc.setBankDepositLastMs(book.lastMs);
        return true;
    }

    private static boolean applyDebtBook(WalletAccount acc, ShopBank.Book book) {
        boolean changed = acc.getBankDebt().compareTo(book.principal) != 0
                || acc.getBankDebtInterest().compareTo(book.interest) != 0
                || acc.getBankDebtLastMs() != book.lastMs;
        if (book.isEmpty() && acc.getBankDebtDueMs() != 0L) {
            acc.setBankDebtDueMs(0L);
            changed = true;
        }
        if (!changed) return false;
        acc.setBankDebt(book.principal);
        acc.setBankDebtInterest(book.interest);
        acc.setBankDebtLastMs(book.lastMs);
        return true;
    }

    /** 还清则清掉到期时刻。旧档有欠款但没有到期时刻时，从现在起给一个完整期限，避免刚更新就停贷。 */
    private static boolean syncDebtDue(WalletAccount acc, ShopBank.Book debt, long now) {
        if (debt.isEmpty()) {
            if (acc.getBankDebtDueMs() == 0L) return false;
            acc.setBankDebtDueMs(0L);
            return true;
        }
        if (acc.getBankDebtDueMs() > 0L) return false;
        acc.setBankDebtDueMs(ShopBank.freshDue(now, loanTermMs()));
        return true;
    }

    private static long depositRate() {
        return DShanhaiConfig.COMMON.shopBankDepositRateBpPerHour.get();
    }

    private static long loanRate() {
        return DShanhaiConfig.COMMON.shopBankLoanRateBpPerHour.get();
    }

    private static BigInteger loanCap() {
        return BigInteger.valueOf(DShanhaiConfig.COMMON.shopBankMaxLoanSpark.get());
    }

    private static long loanTermMs() {
        int hours = DShanhaiConfig.COMMON.shopBankLoanTermHours.get();
        if (hours <= 0) return ShopBank.MS_PER_HOUR;
        return hours * ShopBank.MS_PER_HOUR;
    }

    // ===================== 已购买次数（展示用统计） =====================

    /**
     * 旧商品定位 key：goodsId+category。仅供仍按物品 ID/分类聚合的业务（目前为周期限购）使用，
     * 不可用于已购买次数统计，否则同物品 ID 的不同 NBT 商品会串号。
     */
    public static String purchaseKey(ResourceLocation goodsId, String category) {
        return goodsId + "|" + (category == null ? "" : category);
    }

    /**
     * 单个商品条目的已购买次数 key：使用稳定身份 ID，确保同物品 ID/分类下的不同商品各自统计。
     */
    public static String purchaseKey(ShopEntry entry) {
        if (entry == null || entry.getStableId() == null || entry.getStableId().isBlank()) return null;
        return "entry:" + entry.getStableId();
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
