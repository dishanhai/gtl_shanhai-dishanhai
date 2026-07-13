package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * 商店单个商品条目。商品本体(goods) + 多元成本(cost，见 {@link ShopCost}：星火/币种/物品/流体四通道)。
 *
 * <p>category：分类页签名（如「矿物」「食物」），缺省「杂货」。旧 shop.json 的
 * 单币 currency+price 由构造器自动迁移成 {@link ShopCost#singleCoin} 单币种成本。</p>
 */
public class ShopEntry {

    /** 未指定分类时的默认页签名。 */
    public static final String DEFAULT_CATEGORY = "杂货";

    /** 商品清单（≥1 项）。单项=普通商品；多项=组合商品，购买时一次性交付列表内每一项各自数量。 */
    private final List<GoodsStack> goods;
    private final String category;
    private final ShopCost cost;
    /** 商品描述（玩家自定义文案，可空；详情页 / 悬停显示）。 */
    private final String description;
    /** 剩余可交易（购买/出售共享）次数；-1 = 不限。非 final：随成交在服务端原地扣减，见 {@link #consumeUses}。 */
    private long remainingUses;
    /** 自定义显示图标（可空/空表列表 = 用商品本身图标）。网格格/详情页取代 {@link #makeGoodsStack} 的图标，
     *  第一项为主图标，其余最多 4 项在四角叠成小徽标，用于让「多元组合商品」（如生产核废料的无限盘）一眼看出成分。 */
    private final List<DisplayIcon> displayIcons;
    /** 奖励模式：NONE=普通固定商品；CHOICE/RANDOM/ALL 时 {@link #rewardPool} 必非空；FTBQ 时 {@link #ftbqTableId} 必非空。 */
    private final RewardMode rewardMode;
    /** 奖励池（每项自带权重 + 数量区间，见 {@link RewardOption}）。仅 rewardMode 为 CHOICE/RANDOM/ALL 时有意义。 */
    private final List<RewardOption> rewardPool;
    /** FTBQ 奖励表 ID（16 位十六进制字符串，见 {@link dev.ftb.mods.ftbquests.quest.QuestObjectBase#getCodeString}）。
     *  仅 rewardMode==FTBQ 时有意义；购买时直接读取 FTBQ 当前表内容（不做本地副本），表改了立即生效。 */
    private final String ftbqTableId;
    /** FTBQ 表内的交付子模式（仅取 CHOICE/RANDOM/ALL 三值，仅 rewardMode==FTBQ 时有意义，默认 RANDOM）：
     *  CHOICE=购买前从表内物品类奖励中选 1 项；RANDOM=复用 FTBQ 自身加权随机抽取（原有行为）；
     *  ALL=一次性交付表内每一项物品类奖励。与本地奖励池的三种子模式语义对齐，只是奖励来源换成 FTBQ 表。 */
    private final RewardMode ftbqSubMode;
    /** 隐藏商品：不出现在分类网格/枚举里，只能被另一条目的 {@link #linkTo} 直接跳转带达（仿 FTBQ 隐藏任务）。 */
    private final boolean hidden;
    /** 本条目的跳转目标别名（供其他条目的 {@link #linkTo} 引用），可空。仅需给「会被跳转到」的隐藏商品设置。 */
    private final String linkKey;
    /** 跳转目标：指向某条目 {@link #linkKey} 的别名，可空。非空时详情页显示一行可点击跳转。 */
    private final String linkTo;
    /** 自定义显示名称，可空 = 用商品本身的物品名（见 {@link #goodsDisplayName}）。 */
    private final String displayName;
    /** 交易方向限制：默认 BOTH（不限，兼容旧数据）。 */
    private final TradeMode tradeMode;

    /** 商品的交易方向限制。 */
    public enum TradeMode {
        /** 购买、出售都允许（默认）。 */
        BOTH,
        /** 仅允许购买，玩家不能把这件商品卖回商店。 */
        BUY_ONLY,
        /** 仅允许出售，玩家不能从商店购买这件商品（只能卖入）。 */
        SELL_ONLY
    }

    /** 仿 FTBQuests 奖励表的三种交付方式，rewardPool 非空时才生效。 */
    public enum RewardMode {
        /** 普通固定商品（不启用奖励池）。 */
        NONE,
        /** 自选：购买前弹出选择界面，玩家从池中选 1 项，只交付这一项（数量区间各自独立随机，× 购买次数）。 */
        CHOICE,
        /** 随机：服务端按各项权重加权随机抽 1 项交付（数量区间随机，× 购买次数），无需玩家额外操作。 */
        RANDOM,
        /** 全部：一次性交付池中每一项（各自数量区间随机，× 购买次数），无需玩家额外操作。 */
        ALL,
        /** FTBQ：直接读取一个 FTB Quests 奖励表（{@link #ftbqTableId}），按其权重/数量区间/loot_size
         *  抽取，只交付其中的物品类奖励（经验/指令等非物品奖励类型跳过）；表内容不做本地副本，改了即生效。 */
        FTBQ
    }

    /**
     * 奖励表单项：物品 + 权重（RANDOM 模式按权重占比抽取，CHOICE/ALL 不使用）+ 数量区间
     * （min==max 即固定数量；每次交付独立随机取整，仿 FTBQuests 战利品表的 count/weight 语义）。
     */
    public static final class RewardOption {
        private final ItemStack item;
        private final int weight;
        private final int minCount;
        private final int maxCount;

        private RewardOption(ItemStack item, int weight, int minCount, int maxCount) {
            this.item = item;
            this.weight = Math.max(1, weight);
            int base = Math.max(1, item.getCount());
            int mn = minCount > 0 ? minCount : base;
            int mx = maxCount > 0 ? maxCount : base;
            if (mx < mn) mx = mn;
            this.minCount = mn;
            this.maxCount = mx;
        }

        public static RewardOption of(ItemStack item, int weight, int minCount, int maxCount) {
            return new RewardOption(item.copy(), weight, minCount, maxCount);
        }

        /** 默认权重 1、固定数量 = 物品本身携带的 count（不填区间时的兼容默认值）。 */
        public static RewardOption simple(ItemStack item) {
            int c = Math.max(1, item.getCount());
            return new RewardOption(item.copy(), 1, c, c);
        }

        public ItemStack item() { return item; }
        public int weight() { return weight; }
        public int minCount() { return minCount; }
        public int maxCount() { return maxCount; }

        /** 数量区间内独立随机取整（闭区间，min==max 则恒定该值）。 */
        public int rollCount() {
            return minCount >= maxCount ? minCount
                    : minCount + java.util.concurrent.ThreadLocalRandom.current().nextInt(maxCount - minCount + 1);
        }
    }

    /** 自定义显示图标：物品图标或任意贴图路径二选一（不绑定注册表物品，可用来展示纯装饰性贴图）。 */
    public static final class DisplayIcon {
        private final ItemStack item;           // null = 贴图图标
        private final ResourceLocation texture; // null = 物品图标

        private DisplayIcon(ItemStack item, ResourceLocation texture) {
            this.item = item;
            this.texture = texture;
        }

        public static DisplayIcon ofItem(ItemStack stack) {
            return new DisplayIcon(stack.copy(), null);
        }

        public static DisplayIcon ofTexture(ResourceLocation tex) {
            return new DisplayIcon(null, tex);
        }

        public boolean isTexture() {
            return texture != null;
        }

        public ItemStack item() {
            return item;
        }

        public ResourceLocation texture() {
            return texture;
        }

        /** 悬停/tooltip 展示名：物品用本地化名，贴图用资源路径。 */
        public String displayName() {
            return isTexture() ? texture.toString() : item.getHoverName().getString();
        }
    }

    /** 商品清单单项：物品 + 数量 + NBT（可空）。{@link #goods} 列表 ≥2 项即「组合商品」，购买时一次性交付每一项。 */
    public static final class GoodsStack {
        private final ResourceLocation id;
        private final int count;
        private final net.minecraft.nbt.CompoundTag nbt;

        private GoodsStack(ResourceLocation id, int count, net.minecraft.nbt.CompoundTag nbt) {
            this.id = id;
            this.count = Math.max(1, count);
            this.nbt = (nbt == null || nbt.isEmpty()) ? null : nbt.copy();
        }

        public static GoodsStack of(ResourceLocation id, int count, net.minecraft.nbt.CompoundTag nbt) {
            return new GoodsStack(id, count, nbt);
        }

        public ResourceLocation id() { return id; }
        public int count() { return count; }
        public net.minecraft.nbt.CompoundTag nbt() { return nbt == null ? null : nbt.copy(); }

        public Item item() {
            Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
            return item != null ? item : net.minecraft.world.item.Items.AIR;
        }

        public ItemStack makeStack() {
            ItemStack stack = new ItemStack(item(), count);
            if (nbt != null) stack.setTag(nbt.copy());
            return stack;
        }
    }

    /** 过滤空项（防御性）；空/null 输入回退空列表。 */
    private static List<GoodsStack> normalizeGoods(List<GoodsStack> goods) {
        if (goods == null || goods.isEmpty()) return java.util.Collections.emptyList();
        List<GoodsStack> copy = new java.util.ArrayList<>(goods.size());
        for (GoodsStack g : goods) {
            if (g != null) copy.add(g);
        }
        return copy.isEmpty() ? java.util.Collections.emptyList() : java.util.Collections.unmodifiableList(copy);
    }

    // ===== 单商品构造：委托给多商品清单构造（包成 1 元素列表），保留旧调用方源码兼容 =====
    public ShopEntry(ResourceLocation goodsId, int goodsCount, String category,
                     net.minecraft.nbt.CompoundTag goodsNbt, ShopCost cost, String description, long remainingUses,
                     List<DisplayIcon> displayIcons, RewardMode rewardMode, List<RewardOption> rewardPool,
                     boolean hidden, String linkKey, String linkTo, String displayName, String ftbqTableId) {
        this(goodsId, goodsCount, category, goodsNbt, cost, description, remainingUses, displayIcons, rewardMode,
                rewardPool, hidden, linkKey, linkTo, displayName, ftbqTableId, RewardMode.RANDOM);
    }

    // ===== 单商品构造（含 FTBQ 子模式）：委托给多商品清单构造（包成 1 元素列表） =====
    public ShopEntry(ResourceLocation goodsId, int goodsCount, String category,
                     net.minecraft.nbt.CompoundTag goodsNbt, ShopCost cost, String description, long remainingUses,
                     List<DisplayIcon> displayIcons, RewardMode rewardMode, List<RewardOption> rewardPool,
                     boolean hidden, String linkKey, String linkTo, String displayName, String ftbqTableId,
                     RewardMode ftbqSubMode) {
        this(List.of(GoodsStack.of(goodsId, goodsCount, goodsNbt)), category, cost, description, remainingUses,
                displayIcons, rewardMode, rewardPool, hidden, linkKey, linkTo, displayName, ftbqTableId, ftbqSubMode);
    }

    // ===== 兼容构造：无 FTBQ 子模式（委托默认 RANDOM，即原有行为）=====
    public ShopEntry(List<GoodsStack> goods, String category,
                     ShopCost cost, String description, long remainingUses,
                     List<DisplayIcon> displayIcons, RewardMode rewardMode, List<RewardOption> rewardPool,
                     boolean hidden, String linkKey, String linkTo, String displayName, String ftbqTableId) {
        this(goods, category, cost, description, remainingUses, displayIcons, rewardMode, rewardPool,
                hidden, linkKey, linkTo, displayName, ftbqTableId, RewardMode.RANDOM);
    }

    // ===== 兼容构造：无交易方向限制（委托 BOTH，即原有行为）=====
    public ShopEntry(List<GoodsStack> goods, String category,
                     ShopCost cost, String description, long remainingUses,
                     List<DisplayIcon> displayIcons, RewardMode rewardMode, List<RewardOption> rewardPool,
                     boolean hidden, String linkKey, String linkTo, String displayName, String ftbqTableId,
                     RewardMode ftbqSubMode) {
        this(goods, category, cost, description, remainingUses, displayIcons, rewardMode, rewardPool,
                hidden, linkKey, linkTo, displayName, ftbqTableId, ftbqSubMode, TradeMode.BOTH);
    }

    // ===== 新构造：多元商品清单 + 多元成本 + 描述 + 限购次数 + 自定义显示图标 + 奖励池/FTBQ表(+子模式) + 隐藏/跳转 + 自定义名称 + 交易方向限制 =====
    public ShopEntry(List<GoodsStack> goods, String category,
                     ShopCost cost, String description, long remainingUses,
                     List<DisplayIcon> displayIcons, RewardMode rewardMode, List<RewardOption> rewardPool,
                     boolean hidden, String linkKey, String linkTo, String displayName, String ftbqTableId,
                     RewardMode ftbqSubMode, TradeMode tradeMode) {
        this.goods = normalizeGoods(goods);
        // 只认 CHOICE/ALL 为有效子模式，其余（含 null/NONE/FTBQ 误传）一律退回默认 RANDOM
        this.ftbqSubMode = (ftbqSubMode == RewardMode.CHOICE || ftbqSubMode == RewardMode.ALL) ? ftbqSubMode : RewardMode.RANDOM;
        this.category = (category == null || category.isBlank()) ? DEFAULT_CATEGORY : category;
        this.cost = cost == null ? new ShopCost(java.math.BigInteger.ZERO, null, null) : cost;
        this.description = description == null ? "" : description;
        this.remainingUses = remainingUses < 0L ? -1L : remainingUses;
        if (displayIcons == null || displayIcons.isEmpty()) {
            this.displayIcons = java.util.Collections.emptyList();
        } else {
            List<DisplayIcon> copy = new java.util.ArrayList<>(displayIcons.size());
            for (DisplayIcon d : displayIcons) {
                if (d == null) continue;
                copy.add(d.isTexture() ? DisplayIcon.ofTexture(d.texture()) : DisplayIcon.ofItem(d.item()));
            }
            this.displayIcons = java.util.Collections.unmodifiableList(copy);
        }
        String trimmedFtbqId = ftbqTableId == null ? "" : ftbqTableId.trim();
        if (rewardMode == RewardMode.FTBQ) {
            // FTBQ 模式不用本地池：只存表 ID，购买时直接读表，表空 ID 则和「池空」一样强制退回 NONE
            this.rewardPool = java.util.Collections.emptyList();
            this.rewardMode = trimmedFtbqId.isEmpty() ? RewardMode.NONE : RewardMode.FTBQ;
            this.ftbqTableId = this.rewardMode == RewardMode.FTBQ ? trimmedFtbqId : "";
        } else if (rewardPool == null || rewardPool.isEmpty()) {
            this.rewardPool = java.util.Collections.emptyList();
            this.rewardMode = RewardMode.NONE; // 池空则强制不启用，防止配置半残
            this.ftbqTableId = "";
        } else {
            List<RewardOption> copy = new java.util.ArrayList<>(rewardPool.size());
            for (RewardOption o : rewardPool) {
                if (o != null && o.item() != null && !o.item().isEmpty()) {
                    copy.add(RewardOption.of(o.item(), o.weight(), o.minCount(), o.maxCount()));
                }
            }
ea         this.rewardPool = copy.isEmpty() ? java.util.Collections.emptyList() : java.util.Collections.unmodifiableList(copy);
            this.rewardMode = this.rewardPool.isEmpty() ? RewardMode.NONE : (rewardMode == null ? RewardMode.NONE : rewardMode);
            this.ftbqTableId = "";
        }
        this.hidden = hidden;
        this.linkKey = linkKey == null ? "" : linkKey.trim();
        this.linkTo = linkTo == null ? "" : linkTo.trim();
        this.displayName = displayName == null ? "" : displayName.trim();
        this.tradeMode = tradeMode == null ? TradeMode.BOTH : tradeMode;
    }

    // ===== 兼容构造：无自定义名称/FTBQ表（委托空串）=====
    public ShopEntry(ResourceLocation goodsId, int goodsCount, String category,
                     net.minecraft.nbt.CompoundTag goodsNbt, ShopCost cost, String description, long remainingUses,
                     List<DisplayIcon> displayIcons, RewardMode rewardMode, List<RewardOption> rewardPool,
                     boolean hidden, String linkKey, String linkTo, String displayName) {
        this(goodsId, goodsCount, category, goodsNbt, cost, description, remainingUses, displayIcons, rewardMode, rewardPool,
                hidden, linkKey, linkTo, displayName, null);
    }

    // ===== 兼容构造：无自定义名称（委托空串）=====
    public ShopEntry(ResourceLocation goodsId, int goodsCount, String category,
                     net.minecraft.nbt.CompoundTag goodsNbt, ShopCost cost, String description, long remainingUses,
                     List<DisplayIcon> displayIcons, RewardMode rewardMode, List<RewardOption> rewardPool,
                     boolean hidden, String linkKey, String linkTo) {
        this(goodsId, goodsCount, category, goodsNbt, cost, description, remainingUses, displayIcons, rewardMode, rewardPool,
                hidden, linkKey, linkTo, null);
    }

    // ===== 兼容构造：无隐藏/跳转（委托 false + 空别名）=====
    public ShopEntry(ResourceLocation goodsId, int goodsCount, String category,
                     net.minecraft.nbt.CompoundTag goodsNbt, ShopCost cost, String description, long remainingUses,
                     List<DisplayIcon> displayIcons, RewardMode rewardMode, List<RewardOption> rewardPool) {
        this(goodsId, goodsCount, category, goodsNbt, cost, description, remainingUses, displayIcons, rewardMode, rewardPool,
                false, null, null);
    }

    // ===== 兼容构造：无奖励池（委托 NONE + 空池）=====
    public ShopEntry(ResourceLocation goodsId, int goodsCount, String category,
                     net.minecraft.nbt.CompoundTag goodsNbt, ShopCost cost, String description, long remainingUses,
                     List<DisplayIcon> displayIcons) {
        this(goodsId, goodsCount, category, goodsNbt, cost, description, remainingUses, displayIcons, RewardMode.NONE, null);
    }

    // ===== 兼容构造：无自定义图标（委托空列表）=====
    public ShopEntry(ResourceLocation goodsId, int goodsCount, String category,
                     net.minecraft.nbt.CompoundTag goodsNbt, ShopCost cost, String description, long remainingUses) {
        this(goodsId, goodsCount, category, goodsNbt, cost, description, remainingUses, null);
    }

    // ===== 兼容构造：无限购次数（委托 -1 = 不限）=====
    public ShopEntry(ResourceLocation goodsId, int goodsCount, String category,
                     net.minecraft.nbt.CompoundTag goodsNbt, ShopCost cost, String description) {
        this(goodsId, goodsCount, category, goodsNbt, cost, description, -1L);
    }

    // ===== 兼容构造：无描述（委托空串）=====
    public ShopEntry(ResourceLocation goodsId, int goodsCount, String category,
                     net.minecraft.nbt.CompoundTag goodsNbt, ShopCost cost) {
        this(goodsId, goodsCount, category, goodsNbt, cost, "");
    }

    // ===== 旧构造：单币 currency+price（自动迁移成单币种成本）=====
    public ShopEntry(ResourceLocation goodsId, int goodsCount, ResourceLocation currencyId, int price) {
        this(goodsId, goodsCount, DEFAULT_CATEGORY, null, ShopCost.singleCoin(currencyId, price));
    }

    public ShopEntry(ResourceLocation goodsId, int goodsCount, ResourceLocation currencyId, int price, String category) {
        this(goodsId, goodsCount, category, null, ShopCost.singleCoin(currencyId, price));
    }

    public ShopEntry(ResourceLocation goodsId, int goodsCount, ResourceLocation currencyId, int price,
                     String category, net.minecraft.nbt.CompoundTag goodsNbt) {
        this(goodsId, goodsCount, category, goodsNbt, ShopCost.singleCoin(currencyId, price));
    }

    public ShopCost getCost() {
        return cost;
    }

    /** 商品描述（可能为空串）。 */
    public String getDescription() {
        return description;
    }

    /** 剩余可交易次数；-1 = 不限。 */
    public long getRemainingUses() {
        return remainingUses;
    }

    /** 是否为限次商品（购买/出售共享同一计数）。 */
    public boolean isLimited() {
        return remainingUses >= 0L;
    }

    /** 把请求的交易次数夹到不超过剩余次数（不限商品原样返回）。 */
    public long clampByUses(long times) {
        return remainingUses < 0L ? times : Math.min(times, remainingUses);
    }

    /** 成交后原地扣减剩余次数（不限商品忽略）。调用方需确保 amount ≤ 剩余（先用 {@link #clampByUses} 约束）。 */
    public void consumeUses(long amount) {
        if (remainingUses < 0L || amount <= 0L) return;
        remainingUses = Math.max(0L, remainingUses - amount);
    }

    /** 主商品（列表首项）NBT 快照（可空）。返回副本。 */
    public net.minecraft.nbt.CompoundTag getGoodsNbt() {
        return goods.isEmpty() ? null : goods.get(0).nbt();
    }

    /** 自定义显示图标（不可变，可能为空列表）。为空时网格/详情页用 {@link #makeGoodsStack} 的图标。 */
    public List<DisplayIcon> getDisplayIcons() {
        return displayIcons;
    }

    /** 是否配置了自定义显示图标。 */
    public boolean hasCustomIcons() {
        return !displayIcons.isEmpty();
    }

    public RewardMode getRewardMode() {
        return rewardMode;
    }

    /** 奖励池（不可变，可能为空列表）。仅 {@link #getRewardMode} != NONE 时有意义。 */
    public List<RewardOption> getRewardPool() {
        return rewardPool;
    }

    /** 是否需要购买前弹出选择界面（CHOICE 模式）。 */
    public boolean isChoiceReward() {
        return rewardMode == RewardMode.CHOICE;
    }

    /** FTBQ 奖励表 ID（16 位十六进制字符串，可能为空串）。仅 {@link #getRewardMode} == FTBQ 时有意义。 */
    public String getFtbqTableId() {
        return ftbqTableId;
    }

    /** 是否配置了 FTBQ 奖励表（rewardMode==FTBQ 且 ID 非空）。 */
    public boolean hasFtbqTable() {
        return rewardMode == RewardMode.FTBQ && !ftbqTableId.isEmpty();
    }

    /** FTBQ 表内的交付子模式（CHOICE/RANDOM/ALL，默认 RANDOM）。仅 {@link #getRewardMode} == FTBQ 时有意义。 */
    public RewardMode getFtbqSubMode() {
        return ftbqSubMode;
    }

    /** 是否为隐藏商品（不出现在分类网格/枚举里，只能被跳转直达）。 */
    public boolean isHidden() {
        return hidden;
    }

    /** 本条目的跳转目标别名（可能为空串）。 */
    public String getLinkKey() {
        return linkKey;
    }

    /** 是否设置了跳转目标别名（供其他条目 {@link #linkTo} 引用）。 */
    public boolean hasLinkKey() {
        return !linkKey.isEmpty();
    }

    /** 跳转目标别名（可能为空串，空 = 不显示跳转入口）。 */
    public String getLinkTo() {
        return linkTo;
    }

    /** 详情页是否要显示可点击跳转入口。 */
    public boolean hasLinkTarget() {
        return !linkTo.isEmpty();
    }

    /** 自定义显示名称（可能为空串）。 */
    public String getDisplayName() {
        return displayName;
    }

    /** 是否配置了自定义显示名称。 */
    public boolean hasCustomName() {
        return !displayName.isEmpty();
    }

    /** 交易方向限制（默认 BOTH，不限）。 */
    public TradeMode getTradeMode() {
        return tradeMode;
    }

    /** 是否允许玩家从商店购买这条目（SELL_ONLY 时禁止）。 */
    public boolean allowsBuy() {
        return tradeMode != TradeMode.SELL_ONLY;
    }

    /** 是否允许玩家把这条目卖回商店（BUY_ONLY 时禁止）。 */
    public boolean allowsSell() {
        return tradeMode != TradeMode.BUY_ONLY;
    }

    /** 主商品身份（列表首项）——同物品多条目定位、网格分类等场景仍按这个身份识别整条商品条目。 */
    public ResourceLocation getGoodsId() {
        return goods.isEmpty() ? new ResourceLocation("minecraft:air") : goods.get(0).id();
    }

    /** 主商品（列表首项）每份数量。 */
    public int getGoodsCount() {
        return goods.isEmpty() ? 1 : goods.get(0).count();
    }

    public String getCategory() {
        return category;
    }

    /** 完整商品清单（不可变，至少 1 项）。size()>1 即「组合商品」，见 {@link #hasMultipleGoods}。 */
    public List<GoodsStack> getGoodsList() {
        return goods;
    }

    /** 是否为组合商品（清单内多于 1 种物品，购买时一次性全部交付）。 */
    public boolean hasMultipleGoods() {
        return goods.size() > 1;
    }

    /** 主商品（列表首项）物品，缺失时返回 AIR 对应的 Item。 */
    public Item getGoodsItem() {
        return goods.isEmpty() ? net.minecraft.world.item.Items.AIR : goods.get(0).item();
    }

    /** 商品清单非空、清单内每一项都已在当前注册表中存在 + 成本有效。购买结算拦截靠这个。 */
    public boolean isValid() {
        if (goods.isEmpty() || !cost.isValid()) return false;
        for (GoodsStack g : goods) {
            if (!ForgeRegistries.ITEMS.containsKey(g.id())) return false;
        }
        return true;
    }

    /**
     * 结构合法性：商品清单、成本都至少配置了 1 项，不检查物品是否仍在当前注册表里存在。
     * 用于「要不要把这条目列进分类/网格」的判断——即使商品/成本引用的物品因为对应模组缺失查不到，
     * 条目本身仍算结构合法，要展示出来（走 FTBQ 风格「缺失物品」占位提示玩家/编辑者），
     * 真正拦购买的地方在 {@link #isValid}，两者刻意分开。
     */
    public boolean isStructurallyValid() {
        return !goods.isEmpty() && !cost.isEmpty();
    }

    /** 商品清单里是否有任意一项已经不在当前物品注册表（模组被卸载/物品被移除）。仅用于展示判断。 */
    public boolean hasMissingGoods() {
        for (GoodsStack g : goods) {
            if (g.id() != null && !ForgeRegistries.ITEMS.containsKey(g.id())) return true;
        }
        return false;
    }

    /** 商品或成本任一处引用了当前注册表里查不到的物品/流体（模组缺失）。仅用于展示判断，购买拦截见 {@link #isValid}。 */
    public boolean hasMissingItems() {
        return hasMissingGoods() || cost.hasMissingIngredient();
    }

    private static final ResourceLocation FTBQ_MISSING_ITEM_ID = new ResourceLocation("ftbquests", "missing_item");

    /**
     * 用 FTBQ 自带的「缺失物品」占位样式（问号图标 + 红字提示，悬停 tooltip 直接显示原始物品 ID）代表一个
     * 查不到的物品引用，通常是配置该商品时用到的模组现在缺失/物品被移除。只用于渲染展示，不参与购买结算——
     * 结算该拦的地方仍然拦在 {@link #isValid}/{@link ShopCost#isValid}。本模组硬依赖 FTBQ，
     * {@code ftbquests:missing_item} 恒定存在，NBT 结构对齐 FTBQ 自己的 MissingItem 读取格式。
     */
    public static ItemStack missingItemStack(ResourceLocation id, int count) {
        Item missing = ForgeRegistries.ITEMS.getValue(FTBQ_MISSING_ITEM_ID);
        if (missing == null) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(missing, Math.max(1, count));
        net.minecraft.nbt.CompoundTag item = new net.minecraft.nbt.CompoundTag();
        item.putString("id", id == null ? "minecraft:air" : id.toString());
        item.putInt("Count", Math.max(1, count));
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.put("Item", item);
        stack.setTag(tag);
        return stack;
    }

    /** 主商品（列表首项）的展示用 ItemStack，带上其 NBT 快照（若有）。 */
    public ItemStack makeGoodsStack() {
        if (goods.isEmpty()) return ItemStack.EMPTY;
        return goods.get(0).makeStack();
    }

    /**
     * 主商品（列表首项）的<b>展示用</b>图标：缺失时用 {@link #missingItemStack} 占位代替（避免网格/详情页
     * 直接显示一个看起来像"卖空气"的空槽），否则等价于 {@link #makeGoodsStack}。
     * 只给渲染用；真正参与购买结算走的仍是 {@link #makeGoodsStack}/{@link #getGoodsItem}，不受影响。
     */
    public ItemStack displayGoodsStack() {
        if (goods.isEmpty()) return ItemStack.EMPTY;
        GoodsStack primary = goods.get(0);
        if (primary.id() != null && !ForgeRegistries.ITEMS.containsKey(primary.id())) {
            return missingItemStack(primary.id(), primary.count());
        }
        return primary.makeStack();
    }

    /**
     * 商品显示名：自定义名称优先；组合商品缺自定义名称时用「首项名称 等N种」提示这是个多物品条目。
     * 首项物品缺失（模组缺失/被移除）时不显示成 makeGoodsStack() 兜底出来的字面「Air」，改用红字
     * 「缺失物品:原始ID」，跟网格/详情页的 FTBQ 风格占位图标呼应，一眼就能看出是哪条模组物品缺了。
     */
    public String goodsDisplayName() {
        if (hasCustomName()) return displayName;
        GoodsStack primaryGs = goods.isEmpty() ? null : goods.get(0);
        boolean primaryMissing = primaryGs != null && primaryGs.id() != null
                && !ForgeRegistries.ITEMS.containsKey(primaryGs.id());
        String primary = primaryMissing ? "§c缺失物品:" + primaryGs.id() : makeGoodsStack().getHoverName().getString();
        return hasMultipleGoods() ? primary + " 等" + goods.size() + "种" : primary;
    }

    /**
     * 渲染用图标源：配了自定义显示图标优先用它；否则组合商品（≥2 项）取清单前 5 项组成物品图标
     * （复用 {@link DisplayIcon} 组合渲染，1 主+最多4 附属角标），单商品条目返回空列表——
     * 调用方按老规矩用 {@link #makeGoodsStack} 走单图标渲染路径，行为与改造前完全一致。
     */
    public List<DisplayIcon> effectiveIcons() {
        if (hasCustomIcons()) return displayIcons;
        if (goods.size() <= 1) return java.util.Collections.emptyList();
        List<DisplayIcon> list = new java.util.ArrayList<>(Math.min(goods.size(), 5));
        for (int i = 0; i < goods.size() && i < 5; i++) {
            GoodsStack g = goods.get(i);
            ItemStack st = (g.id() != null && !ForgeRegistries.ITEMS.containsKey(g.id()))
                    ? missingItemStack(g.id(), g.count()) : g.makeStack();
            list.add(DisplayIcon.ofItem(st));
        }
        return list;
    }
}
