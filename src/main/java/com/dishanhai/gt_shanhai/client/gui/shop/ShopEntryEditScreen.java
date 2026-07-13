package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog;
import com.dishanhai.gt_shanhai.common.shop.ExchangeEntry;
import com.dishanhai.gt_shanhai.common.shop.ShopCost;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopEditPacket;

import dev.architectury.fluid.FluidStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.registries.ForgeRegistries;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 商店商品编辑器（FTBQ 式可视化槽位，客户端，山海署名）。
 *
 * 商品槽 + 每份数量/分类 + <b>多元成本</b>（{@link ShopCost} 四通道）：
 * 星火框 + 币种(钱包)排 + 物品(实物)排 + 流体(实物)排。各排点空槽/加号调 {@link EditorWidgets}
 * 的 FTBLib 选择器（自带数量/mB），右键槽位删除。确认打成 {@link ShopEditPacket} 发服务端持久化。</p>
 */
public class ShopEntryEditScreen extends ScaledScreen {

    private static final int GOLD = -22016;
    private static final int GOLD_DARK = -7710208;
    private static final int PANEL_BG = -267382768;
    private static final int PANEL_INNER = -266724838;
    private static final int GRAY = -5592406;
    private static final int WHITE = -1;
    private static final int CYAN = -11141121;
    private static final int BTN_BG = -14935012;
    private static final int BTN_HOVER = -12303292;

    private static final int TARGET_W = 520;
    private static final int TARGET_H = 416;
    /** 服务器正常速度下 1 现实秒 = 20 tick；周期限购的「周期(秒)」按此换算成 tick。 */
    private static final long TICKS_PER_SECOND = 20L;
    private static final int SLOT = 20;
    private static final int PITCH = 24;

    private final ShopScreen parent;
    private final boolean isNew;
    private final ResourceLocation oldGoods;
    private final String oldCategory;
    private final long catalogRevision;       // 打开编辑器时的目录版本
    private final long oldEntryKey;           // 待编辑条目身份；新增时=-1

    private final List<ItemStack> goodsList = new ArrayList<>(); // 商品清单（≥1 项；首项数量由 count/countBox 驱动，第2项起用各自槽位自带数量）
    private int count;
    private String category;
    private String description;
    private long limit = -1L; // 限购次数（购买/出售共享）；-1 = 不限（默认）
    private long periodSeconds = -1L; // 周期限购的周期长度（现实秒，服务器正常速度下 1秒=20tick）；-1 = 不启用（默认）
    private long periodCap = -1L;  // 周期限购每周期额度（每玩家独立计数）；-1 = 不启用（默认），与 periodSeconds 须同时填写才生效
    // 多元成本草稿
    private BigInteger spark = BigInteger.ZERO;
    // 币种/物品：真实数量存 coinCounts/itemCounts（long），不再用 ItemStack.count（int，最多到 21 亿就截断，
    // 见反馈——旧版本 fillCost/buildCost 靠 stack.getCount() 存取，超 int 上限的配方悄悄被截断成 2,147,483,647）。
    // ItemStack 本身的 count 固定钉在 1（vanilla count!=1 才画数量角标，钉 1 就不会画出跟真实数量对不上的角标）。
    private final List<ItemStack> coins = new ArrayList<>();   // 钱包币种（身份+NBT，count 恒为 1）
    private final List<Long> coinCounts = new ArrayList<>();   // 与 coins 逐一对应的真实数量
    private final List<ItemStack> items = new ArrayList<>();   // 实物物品（身份+NBT，count 恒为 1）
    private final List<Long> itemCounts = new ArrayList<>();   // 与 items 逐一对应的真实数量
    private final List<FluidStack> fluids = new ArrayList<>(); // 实物流体
    private final List<ShopEntry.DisplayIcon> displayIcons = new ArrayList<>(); // 自定义显示图标（1主+最多4附属，物品/贴图二选一，不填=用商品本身图标）
    private static final int MAX_DISPLAY_ICONS = 5;
    private ShopEntry.RewardMode rewardMode = ShopEntry.RewardMode.NONE; // 奖励模式：自选/随机/全部，池非空才生效
    private final List<ShopEntry.RewardOption> rewardPool = new ArrayList<>(); // 奖励池（仿 FTBQ 奖励表，各项自带权重+数量区间）
    private static final int MAX_REWARD_OPTIONS = 20;
    private ShopEntry.RewardOption hoverRewardOption; // 悬停奖励池槽位时暂存，renderTooltips 消费
    private String ftbqTableId = ""; // FTBQ 奖励表 ID（十六进制），仅 rewardMode==FTBQ 时有意义
    private ShopEntry.RewardMode ftbqSubMode = ShopEntry.RewardMode.RANDOM; // FTBQ 表内交付子模式：自选/随机/全部
    private boolean hidden;   // 隐藏商品：不出现在分类网格/枚举里，只能被跳转直达（仿 FTBQ 隐藏任务）
    private String linkKey = ""; // 本条目的跳转目标别名（供其他条目 linkTo 引用），可空
    private String linkTo = "";  // 跳转到：指向某条目 linkKey，可空，非空则详情页显示可点击跳转
    private String displayName = ""; // 自定义显示名称，可空 = 用商品本身的物品名
    private ShopEntry.TradeMode tradeMode = ShopEntry.TradeMode.BOTH; // 交易方向限制：不限/仅购买/仅出售

    private EditBox countBox, catBox, sparkBox, descBox, limitBox, linkKeyBox, linkToBox, nameBox, periodSecondsBox, periodCapBox;
    private MultiLineTextArea descArea;       // 描述「展开编写」大图层里的多行编辑区（与 descBox 同源，双向同步）
    private boolean descEditorOpen;           // 描述展开编写大图层开关
    private int left, top, panelWidth, panelHeight;
    private boolean catPickerOpen;            // 分类下拉选择窗开关
    private int catPickerScroll;              // 下拉窗滚动偏移（分类较多时用滚轮翻页）
    private static final int CAT_ROW_H = 12;  // 下拉窗每行高

    // 币种/物品「精确数量」小弹窗：绕开 FTBLib 选择器数量框的 int 上限，直接文本输入任意 long。
    private EditBox qtyEditBox;
    private boolean qtyEditorOpen;
    private boolean qtyEditIsCoin; // true=编辑 coins/coinCounts，false=编辑 items/itemCounts
    private int qtyEditIndex;

    /**
     * @param defaultCategory 新增商品时的默认分类（继承自打开时所在的商店子页，如「无限盘区/前期」）；
     *                        编辑已有条目（entry != null）时忽略，为空/null 则回退 {@link ShopEntry#DEFAULT_CATEGORY}。
     */
    public ShopEntryEditScreen(ShopScreen parent, ShopEntry entry, boolean isNew, String defaultCategory) {
        super(Component.literal("编辑商品"));
        this.parent = parent;
        this.isNew = isNew;
        this.targetWidth = TARGET_W;
        this.targetHeight = TARGET_H;
        this.useOffset = false;
        this.minScale = 0.1f;
        this.maxScale = Float.MAX_VALUE;
        this.catalogRevision = ClientShopCatalog.revision();
        this.oldEntryKey = entry == null ? -1L : ClientShopCatalog.keyOf(entry);
        if (entry != null) {
            for (ShopEntry.GoodsStack gs : entry.getGoodsList()) {
                goodsList.add(gs.makeStack());
            }
            goodsList.get(0).setCount(1); // 首项数量由独立的 count/countBox 驱动，槽位图标本身只画 1（不叠数量角标）
            this.count = entry.getGoodsCount();
            this.category = entry.getCategory();
            this.description = entry.getDescription();
            this.limit = entry.getRemainingUses(); // 预填当前剩余次数；不动这个框就原样保留
            this.periodSeconds = entry.isPeriodLimited() ? entry.getPeriodTicks() / TICKS_PER_SECOND : -1L;
            this.periodCap = entry.isPeriodLimited() ? entry.getPeriodLimit() : -1L;
            this.oldGoods = entry.getGoodsId();
            this.oldCategory = entry.getCategory();
            fillCost(entry.getCost());
            displayIcons.addAll(entry.getDisplayIcons());
            this.rewardMode = entry.getRewardMode();
            rewardPool.addAll(entry.getRewardPool());
            this.ftbqTableId = entry.getFtbqTableId();
            this.ftbqSubMode = entry.getFtbqSubMode();
            this.hidden = entry.isHidden();
            this.linkKey = entry.getLinkKey();
            this.linkTo = entry.getLinkTo();
            this.displayName = entry.getDisplayName();
            this.tradeMode = entry.getTradeMode();
        } else {
            goodsList.add(ItemStack.EMPTY); // 新增商品不预填默认物品，留空槽等玩家自己选（submit() 已有空商品校验拦截）
            this.count = 1;
            this.category = (defaultCategory == null || defaultCategory.isBlank()) ? ShopEntry.DEFAULT_CATEGORY : defaultCategory;
            this.description = "";
            this.oldGoods = null;
            this.oldCategory = null;
        }
    }

    private boolean catalogSnapshotValid() {
        return ClientShopCatalog.revision() == catalogRevision
                && (isNew || (oldEntryKey >= 0L && ClientShopCatalog.stub(oldEntryKey) != null));
    }

    private void showCatalogSnapshotExpired() {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.literal("§c[商店] 商品目录已更新，请关闭并重新打开编辑器"), false);
        }
    }

    private void fillCost(ShopCost cost) {
        this.spark = cost.spark;
        for (var e : cost.coins.entrySet()) {
            var item = ForgeRegistries.ITEMS.getValue(e.getKey());
            if (item != null) {
                coins.add(new ItemStack(item, 1));
                java.math.BigInteger v = e.getValue();
                coinCounts.add(v.bitLength() < 63 ? Math.max(1L, v.longValue()) : Long.MAX_VALUE);
            }
        }
        for (ExchangeEntry.Ingredient in : cost.items()) {
            var item = ForgeRegistries.ITEMS.getValue(in.id);
            if (item == null) continue;
            ItemStack st = new ItemStack(item, 1);
            if (in.hasNbt()) st.setTag(in.nbt());
            items.add(st);
            itemCounts.add(Math.max(1L, in.count));
        }
        for (ExchangeEntry.Ingredient in : cost.fluids()) {
            Fluid fluid = ForgeRegistries.FLUIDS.getValue(in.id);
            if (fluid != null) fluids.add(FluidStack.create(fluid, Math.max(1L, in.count)));
        }
    }

    /**
     * 主商品栏是否被奖励池接管：奖励模式启用且池非空时，交付内容完全由 {@link #rewardPool} 决定，
     * 「商品」槽只是冗余的身份占位，禁用手动选择，改镜像奖励池首项（保留给 goodsId 有效性校验/兜底图标用）。
     */
    private boolean goodsLockedByReward() {
        if (rewardMode == ShopEntry.RewardMode.FTBQ) return !ftbqTableId.isEmpty();
        return rewardMode != ShopEntry.RewardMode.NONE && !rewardPool.isEmpty();
    }

    /** 奖励池接管时的商品身份图标：本地池取首项；FTBQ 模式取所选表里第一个物品类奖励（客户端已同步数据）。 */
    private ItemStack lockedGoodsDisplay() {
        if (rewardMode == ShopEntry.RewardMode.FTBQ) {
            dev.ftb.mods.ftbquests.quest.loot.RewardTable table = resolveClientFtbqTable();
            return table == null ? ItemStack.EMPTY : firstFtbqItemIcon(table);
        }
        return rewardPool.isEmpty() ? ItemStack.EMPTY : rewardPool.get(0).item();
    }

    /** 按 {@link #ftbqTableId} 查客户端已同步的 FTBQ 奖励表实例（未装 FTBQ / 未选 / 未同步返回 null）。 */
    private dev.ftb.mods.ftbquests.quest.loot.RewardTable resolveClientFtbqTable() {
        if (ftbqTableId == null || ftbqTableId.isEmpty()) return null;
        dev.ftb.mods.ftbquests.client.ClientQuestFile file = dev.ftb.mods.ftbquests.client.ClientQuestFile.INSTANCE;
        if (file == null) return null;
        long id = dev.ftb.mods.ftbquests.quest.QuestObjectBase.parseCodeString(ftbqTableId);
        if (id == 0L) return null;
        return file.getRewardTable(id);
    }

    /** 表内第一个物品类奖励的图标（没有则空）。 */
    private static ItemStack firstFtbqItemIcon(dev.ftb.mods.ftbquests.quest.loot.RewardTable table) {
        for (dev.ftb.mods.ftbquests.quest.loot.WeightedReward wr : table.getWeightedRewards()) {
            if (wr.getReward() instanceof dev.ftb.mods.ftbquests.quest.reward.ItemReward ir) {
                ItemStack st = ir.getItem();
                if (st != null && !st.isEmpty()) return st;
            }
        }
        return ItemStack.EMPTY;
    }

    // ===== 布局 =====
    private int cx() { return left + 14; }
    private int goodsY() { return top + 24; }
    private int fieldsY() { return top + 48; }
    /** 周期限购行：紧接「次数」行下方，新增独立于永久总量之外的第二套限购（见 ShopPeriodLimiter）。 */
    private int periodY() { return fieldsY() + 16; }
    private int sparkY() { return periodY() + 26; }
    private int coinY() { return sparkY() + 18; }
    private int itemY() { return coinY() + 28; }
    private int fluidY() { return itemY() + 28; }
    private int descY() { return fluidY() + 32; }
    private int iconY() { return descY() + 50; }
    private int rewardModeY() { return iconY() + 34; }
    private int rewardPoolY() { return rewardModeY() + 20; }
    private int hiddenY() { return rewardPoolY() + 34; }
    private int linkKeyY() { return hiddenY() + 20; }
    private int linkToY() { return linkKeyY() + 18; }
    private int slotsX() { return cx() + 36; }
    private int confirmX() { return left + panelWidth - 12 - 70; }
    private int cancelX() { return confirmX() - 6 - 56; }

    // 描述「展开编写」按钮：紧贴描述标签行右侧，面板右边界对齐
    private static final int EXPAND_DESC_W = 60;
    private int expandDescX() { return left + panelWidth - 12 - EXPAND_DESC_W; }
    private int expandDescY() { return descY() - 1; }

    /**
     * 描述展开编写大图层边界 {x, y, w, h}：居中于编辑面板本身（而非整个虚拟画布——面板通常比画布窄，
     * 若按画布居中，矮的大图层会被摆在面板中段，正好撞上物品/图标/奖励模式等行，见 UI 冲突问题），
     * 四周留 20px 边距，封顶 440×220。
     */
    private int[] descEditorBounds() {
        int ow = Math.min(panelWidth - 40, 440);
        int oh = Math.min(panelHeight - 40, 220);
        int ox = left + (panelWidth - ow) / 2;
        int oy = top + (panelHeight - oh) / 2;
        return new int[]{ox, oy, ow, oh};
    }

    /** 一排最多槽位数（按面板宽度动态算，避免溢出；至少 3，末尾加号占一格，可一直点「+」扩展）。 */
    private int rowMax() {
        int avail = panelWidth - (slotsX() - left) - 8;
        return Math.max(3, Math.min(24, avail / PITCH));
    }

    /** 商品排最多槽位数：同一行右侧还有「显示名称」输入框（{@link #nameBoxX}），槽位止步于框前，不与其重叠。 */
    private int goodsRowMax() {
        int avail = nameBoxX() - 6 - slotsX();
        return Math.max(1, Math.min(24, avail / PITCH));
    }

    /** 分类循环按钮位置（分类框右侧）。x 与 initScaled 里 catBox 对齐：cx()+160，宽 110。 */
    private int catCycleX() { return cx() + 160 + 110 + 4; }

    /** 「次数」标签 x（分类下拉按钮右侧）。 */
    private int limitLabelX() { return catCycleX() + 22; }

    /** 「次数」输入框 x（标签右侧，宽 46）。 */
    private int limitBoxX() { return limitLabelX() + 26; }

    /** 分类提示文案 x（次数框右侧，让出输入框空间）。 */
    private int catHintX() { return limitBoxX() + 46 + 6; }

    /** 自定义名称输入框：贴商品行右边界，不管真实物品名多长都不会撞到。 */
    private static final int NAME_BOX_W = 160;
    private int nameBoxX() { return left + panelWidth - 12 - NAME_BOX_W; }

    // ===== 周期限购行布局（次数行下方，独立于永久总量的第二套限购）=====
    private static final int PERIOD_SECONDS_W = 46;
    private static final int PERIOD_CAP_W = 70;
    private int periodSecondsBoxX() { return cx() + 60; }
    private int periodMidLabelX() { return periodSecondsBoxX() + PERIOD_SECONDS_W + 4; }   // "秒限"
    private int periodCapBoxX() { return periodMidLabelX() + 20; }
    private int periodHintX() { return periodCapBoxX() + PERIOD_CAP_W + 22; }         // "次" 标签后的说明文字

    @Override
    protected void initScaled() {
        left = Math.max(6, (vWidth - TARGET_W) / 2);
        top = Math.max(8, (vHeight - TARGET_H) / 2);
        panelWidth = Math.min(TARGET_W, vWidth - 12);
        panelHeight = Math.min(TARGET_H, vHeight - 16);

        int fx = cx() + 60;
        countBox = mkNumBox(fx, fieldsY(), 60, Integer.toString(count), 1, 8192, v -> count = v);
        catBox = new EditBox(this.font, fx + 60 + 40, fieldsY(), 110, 12, Component.literal("分类"));
        catBox.setMaxLength(32);
        catBox.setValue(category == null ? "" : category);
        catBox.setBordered(true);
        catBox.setTextColor(0xFFFFFF);
        catBox.setResponder(s -> category = s);
        limitBox = new EditBox(this.font, limitBoxX(), fieldsY(), 46, 12, Component.literal("次数"));
        limitBox.setMaxLength(19);
        limitBox.setValue(limit < 0L ? "" : Long.toString(limit));
        limitBox.setBordered(true);
        limitBox.setTextColor(0xFFFFFF);
        limitBox.setHint(Component.literal("§8不限"));
        limitBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        limitBox.setResponder(s -> limit = parseLimit(s));
        periodSecondsBox = new EditBox(this.font, periodSecondsBoxX(), periodY(), PERIOD_SECONDS_W, 12, Component.literal("周期秒数"));
        periodSecondsBox.setMaxLength(9);
        periodSecondsBox.setValue(periodSeconds < 0L ? "" : Long.toString(periodSeconds));
        periodSecondsBox.setBordered(true);
        periodSecondsBox.setTextColor(0xFFFFFF);
        periodSecondsBox.setHint(Component.literal("§8不限"));
        periodSecondsBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        periodSecondsBox.setResponder(s -> periodSeconds = parseLimit(s));
        periodCapBox = new EditBox(this.font, periodCapBoxX(), periodY(), PERIOD_CAP_W, 12, Component.literal("周期额度"));
        periodCapBox.setMaxLength(19);
        periodCapBox.setValue(periodCap < 0L ? "" : Long.toString(periodCap));
        periodCapBox.setBordered(true);
        periodCapBox.setTextColor(0xFFFFFF);
        periodCapBox.setHint(Component.literal("§8不限"));
        periodCapBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        periodCapBox.setResponder(s -> periodCap = parseLimit(s));
        sparkBox = new EditBox(this.font, slotsX(), sparkY(), 120, 12, Component.literal("星火"));
        sparkBox.setMaxLength(40); // 必须先设，否则 BigInteger 超 32 位数字会被 setValue 按默认长度截断
        sparkBox.setValue(spark.signum() > 0 ? spark.toString() : "");
        sparkBox.setBordered(true);
        sparkBox.setTextColor(0xFFFF55);
        sparkBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        sparkBox.setResponder(s -> spark = parseBig(s));
        int descW = Math.max(120, panelWidth - (cx() - left) - 14);
        descBox = new EditBox(this.font, cx(), descY() + 10, descW, 14, Component.literal("描述"));
        // 必须先设最大长度再 setValue——setValue 按“当前” maxLength 截断，默认 32，晚设等于白设。
        // 256 太小：capture()/closeDescEditor() 都会把 descBox.getValue() 回写进权威的 description 字段，
        // 一旦大编辑区写的内容超过这个内联预览框的 maxLength，回写时就会被静默截断成 256 字——调大到 4096 兜底。
        descBox.setMaxLength(4096);
        descBox.setValue(description == null ? "" : description);
        descBox.setBordered(true);
        descBox.setTextColor(0xFFFFFF);
        descBox.setResponder(s -> description = s);
        int[] eb = descEditorBounds();
        int descAreaW = eb[2] - 16;
        // 保底可视高度：无论 eb[3]（大图层高度）算出来多小，编辑区至少留够 10 行的显示空间，
        // 避免 h 太小时 visibleLines() 被钳到 1，导致换行后的内容全部不可见（排查用：见行号提示）。
        // 底部要留够「行数提示」这一行（渲染在 y+h+1 处，约 10px 高）+ 确认保存按钮（18px + 6px 间隙）
        // 的空间，原先只留 50px 不够塞下两者，行数提示会被确认保存按钮盖住一截——留 64px 兜底。
        int descAreaH = Math.max(90, eb[3] - 64);
        descArea = new MultiLineTextArea(this.font, descAreaW);
        descArea.setBounds(eb[0] + 8, eb[1] + 20, descAreaW, descAreaH);
        descArea.setValue(description == null ? "" : description);
        linkKeyBox = new EditBox(this.font, cx() + 60, linkKeyY(), 140, 12, Component.literal("别名"));
        linkKeyBox.setMaxLength(32);
        linkKeyBox.setValue(linkKey == null ? "" : linkKey);
        linkKeyBox.setBordered(true);
        linkKeyBox.setTextColor(0xFFFFFF);
        linkKeyBox.setHint(Component.literal("§8留空=不可被跳转"));
        linkKeyBox.setResponder(s -> linkKey = s);
        linkToBox = new EditBox(this.font, cx() + 60, linkToY(), 140, 12, Component.literal("跳转到"));
        linkToBox.setMaxLength(32);
        linkToBox.setValue(linkTo == null ? "" : linkTo);
        linkToBox.setBordered(true);
        linkToBox.setTextColor(0xFFFFFF);
        linkToBox.setHint(Component.literal("§8留空=不显示跳转"));
        linkToBox.setResponder(s -> linkTo = s);
        nameBox = new EditBox(this.font, nameBoxX(), goodsY(), NAME_BOX_W, 12, Component.literal("显示名称"));
        nameBox.setMaxLength(48); // 必须先设，否则超 32 字的显示名称会被 setValue 按默认长度截断
        nameBox.setValue(displayName == null ? "" : displayName);
        nameBox.setBordered(true);
        nameBox.setTextColor(0xFFFFFF);
        nameBox.setHint(Component.literal("§8留空=用商品名称"));
        nameBox.setResponder(s -> displayName = s);
        int[] qb = qtyEditorBounds();
        qtyEditBox = new EditBox(this.font, qb[0] + 8, qb[1] + 20, qb[2] - 16, 14, Component.literal("数量"));
        qtyEditBox.setMaxLength(20);
        qtyEditBox.setBordered(true);
        qtyEditBox.setTextColor(0xFFFFFF);
        qtyEditBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        qtyEditBox.setVisible(qtyEditorOpen);
        addRenderableWidget(countBox);
        addRenderableWidget(catBox);
        addRenderableWidget(limitBox);
        addRenderableWidget(periodSecondsBox);
        addRenderableWidget(periodCapBox);
        addRenderableWidget(sparkBox);
        addRenderableWidget(descBox);
        addRenderableWidget(linkKeyBox);
        addRenderableWidget(linkToBox);
        addRenderableWidget(nameBox);
        addRenderableWidget(qtyEditBox);
    }

    private EditBox mkNumBox(int x, int y, int w, String val, int min, int max, java.util.function.IntConsumer setter) {
        EditBox b = new EditBox(this.font, x, y, w, 12, Component.literal(""));
        b.setMaxLength(9);
        b.setValue(val);
        b.setBordered(true);
        b.setTextColor(0xFFFFFF);
        b.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        b.setResponder(s -> {
            int v;
            try { v = s.isEmpty() ? min : Integer.parseInt(s); }
            catch (NumberFormatException e) { v = max; }
            setter.accept(Math.max(min, Math.min(max, v)));
        });
        return b;
    }

    private static BigInteger parseBig(String s) {
        if (s == null || s.isEmpty()) return BigInteger.ZERO;
        try { return new BigInteger(s); } catch (NumberFormatException e) { return BigInteger.ZERO; }
    }

    /** 空串 = 不限（-1）；否则解析为剩余次数，非法输入回退不限。 */
    private static long parseLimit(String s) {
        if (s == null || s.isBlank()) return -1L;
        try {
            long v = Long.parseLong(s.trim());
            return v < 0L ? -1L : v;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private void capture() {
        if (catBox != null) category = catBox.getValue();
        if (limitBox != null) limit = parseLimit(limitBox.getValue());
        if (periodSecondsBox != null) periodSeconds = parseLimit(periodSecondsBox.getValue());
        if (periodCapBox != null) periodCap = parseLimit(periodCapBox.getValue());
        if (sparkBox != null) spark = parseBig(sparkBox.getValue());
        if (descBox != null) description = descBox.getValue();
        if (linkKeyBox != null) linkKey = linkKeyBox.getValue();
        if (linkToBox != null) linkTo = linkToBox.getValue();
        if (nameBox != null) displayName = nameBox.getValue();
    }

    private void rebuild() {
        capture();
        this.init(Minecraft.getInstance(), this.width, this.height);
    }

    // ===== 分类下拉选择窗（点▾展开，列出所有已存在分类，点行直接选中） =====
    private int catDropX() { return cx() + 160; }         // 与 catBox 左对齐
    private int catDropY() { return fieldsY() + 13; }      // catBox 正下方
    private int catDropW() { return 150; }

    /** 下拉窗最多可见行（按面板下边界动态算，多余走滚轮）。 */
    private int catDropMaxRows() {
        int avail = (top + panelHeight - 6) - catDropY();
        return Math.max(3, Math.min(14, avail / CAT_ROW_H));
    }

    private int catDropScrollClamped(int total, int maxRows) {
        return Math.max(0, Math.min(catPickerScroll, Math.max(0, total - maxRows)));
    }

    /** 命中下拉窗某行 → 选中该分类填入分类框并关窗，返回 true。 */
    private boolean handleCatPickerClick(double mx, double my) {
        java.util.List<String> cats = com.dishanhai.gt_shanhai.common.shop.ShopConfig.getCategories();
        if (cats.isEmpty()) return false;
        int x = catDropX(), y = catDropY(), w = catDropW();
        int maxRows = catDropMaxRows();
        int scroll = catDropScrollClamped(cats.size(), maxRows);
        int visible = Math.min(maxRows, cats.size());
        for (int i = 0; i < visible; i++) {
            int ry = y + 1 + i * CAT_ROW_H;
            if (GuiRenderUtil.isHovering(mx, my, x, ry, w, CAT_ROW_H)) {
                String cat = cats.get(scroll + i);
                category = cat;
                if (catBox != null) catBox.setValue(cat);
                catPickerOpen = false;
                return true;
            }
        }
        return false;
    }

    // ===== 渲染 =====
    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        hoverRewardOption = null;
        // 展开编写大图层/数量小弹窗打开时，原本的字段输入框全部隐藏（同帧同步，避免闪烁；本方法在 super.render 画控件前执行）
        boolean editorOpen = descEditorOpen || qtyEditorOpen;
        if (countBox != null) countBox.setVisible(!editorOpen);
        if (catBox != null) catBox.setVisible(!editorOpen);
        if (limitBox != null) limitBox.setVisible(!editorOpen);
        if (periodSecondsBox != null) periodSecondsBox.setVisible(!editorOpen);
        if (periodCapBox != null) periodCapBox.setVisible(!editorOpen);
        if (sparkBox != null) sparkBox.setVisible(!editorOpen);
        if (descBox != null) descBox.setVisible(!editorOpen);
        if (linkKeyBox != null) linkKeyBox.setVisible(!editorOpen);
        if (linkToBox != null) linkToBox.setVisible(!editorOpen);
        if (nameBox != null) nameBox.setVisible(!editorOpen);
        if (qtyEditBox != null) qtyEditBox.setVisible(qtyEditorOpen);
        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        g.fill(left + 5, top + 18, left + panelWidth - 5, top + panelHeight - 5, PANEL_INNER);

        g.drawString(this.font, "§6" + (isNew ? "新增商品" : "编辑商品"), left + 10, top + 5, GOLD, true);
        drawBtn(g, cancelX(), top + 3, 56, 14, "§c取消", mx, my);
        drawBtn(g, confirmX(), top + 3, 70, 14,
                catalogSnapshotValid() ? "§a确认保存" : "§8请重新打开", mx, my);

        int c = cx();
        // 商品（奖励模式启用且池非空时，交付内容完全由奖励池决定，主商品栏禁用手动选择、镜像池首项）
        // 未接管时改多槽位排（同「币种/物品/流体」交互），支持组合商品；接管时保留原单槽+名称行样式
        boolean goodsLocked = goodsLockedByReward();
        if (goodsLocked) {
            ItemStack goodsDisplay = lockedGoodsDisplay();
            g.drawString(this.font, "§7商品", c, goodsY() + 6, GRAY, true);
            EditorWidgets.itemSlot(g, this.font, slotsX(), goodsY(), goodsDisplay, false);
            int goodsLabelMaxW = nameBoxX() - 6 - (slotsX() + 26);
            String goodsLabel = "§f" + GuiRenderUtil.trimText(this.font, goodsDisplay.getHoverName().getString(), Math.max(20, goodsLabelMaxW))
                    + " §8(奖励池接管)";
            g.drawString(this.font, goodsLabel, slotsX() + 26, goodsY() + 6, WHITE, true);
        } else {
            drawRow(g, "§7商品", goodsList, false, goodsY(), goodsRowMax(), mx, my);
        }
        // 数量 / 分类（框由 super.render 绘制）
        g.drawString(this.font, "§7每份数量", c, fieldsY() + 2, GRAY, true);
        g.drawString(this.font, "§7分类", c + 60 + 60 + 14, fieldsY() + 2, GRAY, true);
        // 读取已有分区循环按钮（分类框右侧「▸」，点一下切到下一个已存在分类）
        drawBtn(g, catCycleX(), fieldsY() - 1, 18, 14, "§e▾", mx, my);
        // 限购次数（框由 super.render 绘制；空=不限，购买/出售共享同一计数）
        g.drawString(this.font, "§7次数", limitLabelX(), fieldsY() + 2, GRAY, true);
        g.drawString(this.font, "§8可填「主/子」建子分组", catHintX(), fieldsY() + 2, GRAY, true);
        // 周期限购（框由 super.render 绘制；两框都空=不启用，独立于上面的永久总量，每玩家各自计数，到点自动刷新）
        g.drawString(this.font, "§7周期限购", c, periodY() + 2, GRAY, true);
        g.drawString(this.font, "§7秒限", periodMidLabelX(), periodY() + 2, GRAY, true);
        g.drawString(this.font, "§7次", periodCapBoxX() + PERIOD_CAP_W + 4, periodY() + 2, GRAY, true);
        g.drawString(this.font, "§8(现实秒；如填1000即每1000秒刷新，每玩家独立计数，留空=不启用)", periodHintX(), periodY() + 2, GRAY, true);

        // 成本区
        g.drawString(this.font, "§6成本（四通道可并存）", c, sparkY() - 10, GOLD, true);
        g.drawString(this.font, "§e★星火:", c, sparkY() + 2, GOLD, true);
        // 币种 / 物品 / 流体 三排
        drawCostIngredientRow(g, "§7币种", coins, coinCounts, coinY(), rowMax(), mx, my);
        drawCostIngredientRow(g, "§7物品", items, itemCounts, itemY(), rowMax(), mx, my);
        drawFluidRow(g, fluidY(), mx, my);

        // 描述（框由 super.render 绘制）
        g.drawString(this.font, "§6描述（详情页显示）", c, descY(), GOLD, true);
        drawBtn(g, expandDescX(), expandDescY(), EXPAND_DESC_W, 10, "§b展开编写", mx, my);

        // 显示图标（可选，网格格/详情页封面用；不填=用商品本身图标；物品/贴图可混选）
        g.drawString(this.font, "§6显示图标（可选，1主+最多4附属角标，不填=用商品本身图标）", c, iconY() - 10, GOLD, true);
        drawIconRow(g, "§7图标", iconY(), Math.min(MAX_DISPLAY_ICONS, rowMax()), mx, my);

        // 奖励模式（仿 FTBQ 奖励表）：不启用=普通固定商品；自选=购买前选1项；随机=系统随机给1项；全部=一次性全给
        g.drawString(this.font, "§6奖励模式（自选/随机/全部/FTBQ表，非空才生效）", c, rewardModeY() - 10, GOLD, true);
        drawBtn(g, c, rewardModeY(), 56, 14, rewardModeLabel(), mx, my);
        if (rewardMode == ShopEntry.RewardMode.FTBQ) {
            // FTBQ 表本身也能像本地奖励池一样走自选/随机/全部三种交付方式，只是奖励来源换成表内容
            g.drawString(this.font, "§7抽取方式:", c + 64, rewardModeY() + 3, GRAY, true);
            drawBtn(g, c + 64 + 46, rewardModeY(), 44, 14, ftbqSubModeLabel(), mx, my);
        }
        drawRewardRow(g, rewardPoolY(), Math.min(MAX_REWARD_OPTIONS, rowMax()), mx, my);

        // 隐藏/跳转（仿 FTBQ 隐藏任务）：隐藏商品不进分类网格，只能被别的条目「跳转到」直达
        g.drawString(this.font, "§6隐藏/跳转（隐藏商品不进分类网格，靠跳转直达）· 交易方向", c, hiddenY() - 10, GOLD, true);
        drawBtn(g, c, hiddenY(), 56, 14, hidden ? "§c隐藏中" : "§a未隐藏", mx, my);
        drawBtn(g, c + 56 + 8, hiddenY(), 64, 14, tradeModeLabel(), mx, my);
        g.drawString(this.font, "§7别名", c, linkKeyY() + 2, GRAY, true);
        g.drawString(this.font, "§7跳转到", c, linkToY() + 2, GRAY, true);

        g.drawString(this.font, "§8币种=钱包余额扣 · 物品=背包扣 · 流体=绑定AE抽 · 星火=数字余额", c, top + panelHeight - 14, GRAY, true);

        // 描述展开编写大图层：遮罩 + 面板背景先画在这里，descArea 本身在 renderScaledForeground 里画（压在遮罩之上）
        // 内部背景必须完全不透明（不能沿用 PANEL_BG 那种带一点透明度的底色），否则面板本身的文字/图标会透出来
        if (descEditorOpen) {
            // fill() 用的 RenderType.gui() 是 LEQUAL 深度测试，币种/物品/图标等槽位的图标和这里的遮罩/
            // 面板同在 Z=0，谁赢深度测试取决于批次提交 GPU 的脆弱时机，光 flush() 治标不治本（同
            // ScaledScreen 里 renderScaledForeground 穿模那次的根因）。这里顶到 Z=200（低于
            // renderScaledForeground 统一顶的 400，留出层级），深度测试直接稳赢，不再看批次时机。
            g.flush();
            g.pose().pushPose();
            g.pose().translate(0.0f, 0.0f, 200.0f);
            int[] r = descEditorBounds();
            g.fill(0, 0, vWidth, vHeight, 0xC0000000);
            g.fill(r[0] - 1, r[1] - 1, r[0] + r[2] + 1, r[1] + r[3] + 1, GOLD_DARK);
            g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0xFF101010);
            g.pose().popPose();
        }
    }

    /** 前景层（在所有控件之上绘制）：分类下拉选择窗 / 描述展开编写大图层，故能盖住成本行与输入框。 */
    @Override
    protected void renderScaledForeground(GuiGraphics g, int mx, int my, float pt) {
        if (descEditorOpen) {
            renderDescEditorForeground(g, mx, my);
            return;
        }
        if (qtyEditorOpen) {
            renderQtyEditorForeground(g, mx, my);
            return;
        }
        if (!catPickerOpen) return;
        java.util.List<String> cats = com.dishanhai.gt_shanhai.common.shop.ShopConfig.getCategories();
        int x = catDropX(), y = catDropY(), w = catDropW();
        if (cats.isEmpty()) {
            int h = CAT_ROW_H + 4;
            g.fill(x - 1, y - 1, x + w + 1, y + h + 1, GOLD_DARK);
            g.fill(x, y, x + w, y + h, PANEL_BG);
            g.drawString(this.font, "§8暂无已有分类，直接在框内输入", x + 4, y + 4, GRAY, false);
            return;
        }
        int maxRows = catDropMaxRows();
        int total = cats.size();
        int scroll = catDropScrollClamped(total, maxRows);
        catPickerScroll = scroll;
        int visible = Math.min(maxRows, total);
        int h = visible * CAT_ROW_H + 2;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, GOLD_DARK);
        g.fill(x, y, x + w, y + h, PANEL_BG);
        String cur = catBox != null ? catBox.getValue() : category;
        for (int i = 0; i < visible; i++) {
            String cat = cats.get(scroll + i);
            int ry = y + 1 + i * CAT_ROW_H;
            if (GuiRenderUtil.isHovering(mx, my, x, ry, w, CAT_ROW_H)) g.fill(x, ry, x + w, ry + CAT_ROW_H, BTN_HOVER);
            String label = (cat.equals(cur) ? "§a✔ " : "§f") + GuiRenderUtil.trimText(this.font, cat, w - 14);
            g.drawString(this.font, label, x + 4, ry + 2, WHITE, false);
        }
        if (total > maxRows) {
            g.drawString(this.font, "§8滚轮翻页 " + (scroll + 1) + "-" + (scroll + visible) + "/" + total, x, y + h + 2, GRAY, false);
        }
    }

    /** 描述展开编写大图层前景：标题 + 关闭 X + 真多行编辑区（支持回车换行/方向键/选择/复制粘贴）+ 确认保存按钮。 */
    private void renderDescEditorForeground(GuiGraphics g, int mx, int my) {
        int[] r = descEditorBounds();
        int ox = r[0], oy = r[1], ow = r[2], oh = r[3];
        g.drawString(this.font, "§6编写描述 §8(回车换行，Ctrl+回车快速保存)", ox + 8, oy + 8, GOLD, true);
        int closeX = ox + ow - EDITOR_CLOSE_W - 4, closeY = oy + 4;
        drawBtn(g, closeX, closeY, EDITOR_CLOSE_W, EDITOR_CLOSE_H, "§cX", mx, my);

        if (descArea != null) descArea.render(g, mx, my);

        int confirmH = 18;
        int confirmX = ox + 8, confirmY = oy + oh - confirmH - 6, confirmW = ow - 16;
        drawBtn(g, confirmX, confirmY, confirmW, confirmH, "§a确认保存", mx, my);
    }

    private static final int EDITOR_CLOSE_W = 16;
    private static final int EDITOR_CLOSE_H = 12;

    /** 打开描述展开编写大图层：与分类下拉窗互斥，同步当前描述文本进编辑区。 */
    private void openDescEditor() {
        catPickerOpen = false;
        if (descArea != null) {
            descArea.setValue(description == null ? "" : description);
            descArea.setFocused(true);
        }
        setFocused(null); // Screen 级焦点清空：descArea 不接入控件树，靠 keyPressed/charTyped 手动转发
        descEditorOpen = true;
    }

    /** 关闭大图层；save=true 才把编辑区内容写回 description（并同步内联 descBox），点关闭/点面板外都是丢弃改动。 */
    private void closeDescEditor(boolean save) {
        if (save && descArea != null) {
            description = descArea.getValue();
            if (descBox != null) descBox.setValue(description);
        }
        if (descArea != null) descArea.setFocused(false);
        descEditorOpen = false;
        setFocused(null);
    }

    /** 描述大图层打开时的点击拦截：点 X/面板外=丢弃改动关闭，点确认保存=写回，点编辑区内部转发去定位光标，其余原地吞掉。 */
    private boolean handleDescEditorClick(double mx, double my) {
        int[] r = descEditorBounds();
        int closeX = r[0] + r[2] - EDITOR_CLOSE_W - 4, closeY = r[1] + 4;
        if (GuiRenderUtil.isHovering(mx, my, closeX, closeY, EDITOR_CLOSE_W, EDITOR_CLOSE_H)) {
            closeDescEditor(false);
            return true;
        }
        int confirmH = 18;
        int confirmX = r[0] + 8, confirmY = r[1] + r[3] - confirmH - 6, confirmW = r[2] - 16;
        if (GuiRenderUtil.isHovering(mx, my, confirmX, confirmY, confirmW, confirmH)) {
            closeDescEditor(true);
            return true;
        }
        if (descArea != null) descArea.mouseClicked(mx, my);
        if (!GuiRenderUtil.isHovering(mx, my, r[0], r[1], r[2], r[3])) {
            closeDescEditor(false);
        }
        return true;
    }

    /** 精确数量小弹窗边界：居中，尺寸固定（单行输入，不用像描述详情那样按内容动态算高）。 */
    private int[] qtyEditorBounds() {
        int ow = Math.min(vWidth - 40, 200);
        int oh = 56;
        int ox = (vWidth - ow) / 2;
        int oy = (vHeight - oh) / 2;
        return new int[]{ox, oy, ow, oh};
    }

    /**
     * 打开「精确数量」小弹窗：绕开 FTBLib 选择器数量框的 int 上限，直接文本框输入任意 long（见反馈：
     * 币种/物品成本超过 21 亿会被旧逻辑悄悄截断成 Integer.MAX_VALUE）。isCoin 决定改 coinCounts 还是 itemCounts。
     */
    private void openQtyEditor(boolean isCoin, int index) {
        catPickerOpen = false;
        qtyEditIsCoin = isCoin;
        qtyEditIndex = index;
        long current = isCoin
                ? (index < coinCounts.size() ? coinCounts.get(index) : 1L)
                : (index < itemCounts.size() ? itemCounts.get(index) : 1L);
        if (qtyEditBox != null) {
            qtyEditBox.setValue(Long.toString(current));
            qtyEditBox.setFocused(true);
            setFocused(qtyEditBox); // 真实控件，走 Screen 级焦点，keyPressed/charTyped 自动路由过去，不用手动转发
        }
        qtyEditorOpen = true;
    }

    /** 关闭数量小弹窗；save=true 才把输入框内容解析写回对应的 counts 列表，非法输入回退 1，点关闭/点弹窗外都是丢弃改动。 */
    private void closeQtyEditor(boolean save) {
        if (save && qtyEditBox != null) {
            long v;
            try { v = Long.parseLong(qtyEditBox.getValue().trim()); } catch (Exception e) { v = 1L; }
            v = Math.max(1L, v);
            java.util.List<Long> target = qtyEditIsCoin ? coinCounts : itemCounts;
            if (qtyEditIndex >= 0 && qtyEditIndex < target.size()) target.set(qtyEditIndex, v);
        }
        if (qtyEditBox != null) qtyEditBox.setFocused(false);
        qtyEditorOpen = false;
        setFocused(null);
    }

    /** 数量小弹窗前景：标题 + 关闭 X + 输入框（真实控件，由 super.render 画）+ 确认按钮。 */
    private void renderQtyEditorForeground(GuiGraphics g, int mx, int my) {
        int[] r = qtyEditorBounds();
        int ox = r[0], oy = r[1], ow = r[2], oh = r[3];
        g.fill(0, 0, vWidth, vHeight, 0xC0000000);
        g.fill(ox - 1, oy - 1, ox + ow + 1, oy + oh + 1, GOLD_DARK);
        g.fill(ox, oy, ox + ow, oy + oh, PANEL_BG);
        g.drawString(this.font, "§6精确数量 §8(支持超大整数)", ox + 8, oy + 6, GOLD, true);
        int closeX = ox + ow - EDITOR_CLOSE_W - 4, closeY = oy + 4;
        drawBtn(g, closeX, closeY, EDITOR_CLOSE_W, EDITOR_CLOSE_H, "§cX", mx, my);
        int confirmH = 16, confirmX = ox + 8, confirmY = oy + oh - confirmH - 6, confirmW = ow - 16;
        drawBtn(g, confirmX, confirmY, confirmW, confirmH, "§a确认", mx, my);
    }

    @Override
    protected boolean universalMouseScrolled(double mx, double my, double d) {
        if (descEditorOpen) {
            if (descArea != null && descArea.isHovering(mx, my)) descArea.scroll(d);
            return true;
        }
        if (catPickerOpen) {
            java.util.List<String> cats = com.dishanhai.gt_shanhai.common.shop.ShopConfig.getCategories();
            int maxRows = catDropMaxRows();
            if (cats.size() > maxRows) {
                int dir = d > 0 ? -1 : 1;
                catPickerScroll = Math.max(0, Math.min(catPickerScroll + dir, cats.size() - maxRows));
                return true;
            }
        }
        return super.universalMouseScrolled(mx, my, d);
    }

    /** 画一排物品型槽位（币种/物品共用），返回无。 */
    private void drawRow(GuiGraphics g, String label, List<ItemStack> list, boolean unused, int y, int max, int mx, int my) {
        int c = cx();
        g.drawString(this.font, label, c, y + 6, GRAY, true);
        int sx = slotsX();
        for (int i = 0; i < list.size(); i++) {
            int x = sx + i * PITCH;
            EditorWidgets.itemSlot(g, this.font, x, y, list.get(i), hover(mx, my, x, y));
        }
        if (list.size() < max) {
            int x = sx + list.size() * PITCH;
            EditorWidgets.plusSlot(g, this.font, x, y, hover(mx, my, x, y));
        }
    }

    /**
     * 画币种/物品成本排：图标 + 槽下数量文字，数量取自并行的 counts 列表（long，不是 ItemStack.getCount()）。
     * 槽位本身 count 恒为 1，vanilla 角标不会画（count==1 时 renderItemDecorations 不画数字），只有槽下这行文字是真数量。
     */
    private void drawCostIngredientRow(GuiGraphics g, String label, List<ItemStack> list, List<Long> counts, int y, int max, int mx, int my) {
        int c = cx();
        g.drawString(this.font, label, c, y + 6, GRAY, true);
        int sx = slotsX();
        for (int i = 0; i < list.size(); i++) {
            int x = sx + i * PITCH;
            EditorWidgets.itemSlot(g, this.font, x, y, list.get(i), hover(mx, my, x, y));
            long cnt = i < counts.size() ? counts.get(i) : 1L;
            g.drawCenteredString(this.font, compactQty(cnt), x + 10, y + 21, CYAN);
        }
        if (list.size() < max) {
            int x = sx + list.size() * PITCH;
            EditorWidgets.plusSlot(g, this.font, x, y, hover(mx, my, x, y));
        }
    }

    /** 数量紧凑显示（同商店主界面的缩写风格，K/M/B/T），槽下 24px 窄条塞不下完整数字。 */
    private static String compactQty(long v) {
        long n = Math.max(0L, v);
        if (n >= 1_000_000_000_000L) return (n / 1_000_000_000_000L) + "T+";
        if (n >= 1_000_000_000L) return (n / 1_000_000_000L) + "B+";
        if (n >= 1_000_000L) return (n / 1_000_000L) + "M+";
        if (n >= 1_000L) return (n / 1_000L) + "K+";
        return String.valueOf(n);
    }

    /**
     * 币种/物品成本排点击：左键点图标——开 FTBLib 选择器改物品身份（数量不受它影响，仍是 counts 里的 long）；
     * 左键点槽下数量文字——开精确数量小弹窗（绕开 FTBLib 数量框的 int 上限）；右键删除（连同 counts 同步删）；
     * 点「+」开多选加新项。isCoin 决定弹窗写回 coinCounts 还是 itemCounts。
     */
    private boolean costIngredientRowClicked(List<ItemStack> list, List<Long> counts, int y, int max,
                                             double mx, double my, int btn, Runnable onOpenAdd, boolean isCoin) {
        int sx = slotsX();
        for (int i = 0; i < list.size(); i++) {
            int x = sx + i * PITCH;
            if (GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT)) {
                if (btn == 1) {
                    list.remove(i);
                    if (i < counts.size()) counts.remove(i);
                    rebuild();
                } else {
                    final int idx = i;
                    capture();
                    EditorWidgets.openItemPicker(list.get(idx), st -> {
                        if (st == null || st.isEmpty()) {
                            list.remove(idx);
                            if (idx < counts.size()) counts.remove(idx);
                        } else {
                            ItemStack template = st.copy();
                            template.setCount(1); // 身份/NBT 换了没关系，数量仍是 counts 里存的那份，不吃选择器给的数量
                            list.set(idx, template);
                        }
                    });
                }
                return true;
            }
            // 槽下数量文字命中框：跟图标同宽，紧贴图标下方一行
            if (GuiRenderUtil.isHovering(mx, my, x, y + 21, SLOT, 9)) {
                capture();
                openQtyEditor(isCoin, i);
                return true;
            }
        }
        if (list.size() < max) {
            int x = sx + list.size() * PITCH;
            if (GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT)) {
                if (onOpenAdd != null) onOpenAdd.run();
                return true;
            }
        }
        return false;
    }

    /**
     * 画奖励池排：物品图标 + 悬停显示权重/概率/数量区间 tooltip（对标 FTBQ 战利品表的 weight/count），
     * 左键开 {@link RewardOptionEditScreen} 编辑权重与数量区间，右键快速删除，点「+」加新项。
     */
    private void drawRewardRow(GuiGraphics g, int y, int max, int mx, int my) {
        if (rewardMode == ShopEntry.RewardMode.FTBQ) {
            drawFtbqTableRow(g, y, mx, my);
            return;
        }
        int c = cx();
        g.drawString(this.font, "§7奖励池", c, y + 6, GRAY, true);
        int sx = slotsX();
        int totalWeight = 0;
        for (ShopEntry.RewardOption o : rewardPool) totalWeight += o.weight();
        for (int i = 0; i < rewardPool.size(); i++) {
            int x = sx + i * PITCH;
            ShopEntry.RewardOption opt = rewardPool.get(i);
            boolean hv = hover(mx, my, x, y);
            EditorWidgets.itemSlot(g, this.font, x, y, opt.item(), hv);
            if (hv) hoverRewardOption = opt;
        }
        if (rewardPool.size() < max) {
            int x = sx + rewardPool.size() * PITCH;
            EditorWidgets.plusSlot(g, this.font, x, y, hover(mx, my, x, y));
        }
    }

    /** FTBQ 模式的「奖励池」行：单槽显示所选表的图标+标题（+项数），点击打开选择器，右键清空。 */
    private void drawFtbqTableRow(GuiGraphics g, int y, int mx, int my) {
        int c = cx();
        g.drawString(this.font, "§7FTBQ表", c, y + 6, GRAY, true);
        int sx = slotsX();
        dev.ftb.mods.ftbquests.quest.loot.RewardTable table = resolveClientFtbqTable();
        boolean hv = hover(mx, my, sx, y);
        if (table != null) {
            EditorWidgets.itemSlot(g, this.font, sx, y, firstFtbqItemIcon(table), hv);
            String title = table.getRawTitle().isEmpty()
                    ? ("#" + dev.ftb.mods.ftbquests.quest.QuestObjectBase.getCodeString(table)) : table.getRawTitle();
            g.drawString(this.font, "§f" + GuiRenderUtil.trimText(this.font, title, 140)
                    + " §8(" + table.getWeightedRewards().size() + "项)", sx + 24, y + 6, WHITE, true);
        } else {
            EditorWidgets.plusSlot(g, this.font, sx, y, hv);
            String tip = ftbqTableId.isEmpty() ? "§8点击选择" : "§c表未同步/不存在 #" + ftbqTableId;
            g.drawString(this.font, tip, sx + 24, y + 6, GRAY, true);
        }
    }

    /** 悬停奖励池槽位时的 tooltip 文案：权重 + 占比（总权重 > 0 才算百分比）+ 数量区间。 */
    private java.util.List<Component> rewardTooltip(ShopEntry.RewardOption opt) {
        int total = 0;
        for (ShopEntry.RewardOption o : rewardPool) total += o.weight();
        String pct = total > 0 ? String.format(java.util.Locale.ROOT, " [%.2f%%]", opt.weight() * 100.0 / total) : "";
        String amount = opt.minCount() == opt.maxCount() ? "§7数量: §f" + opt.minCount()
                : "§7数量: §f" + opt.minCount() + "-" + opt.maxCount();
        return java.util.List.of(
                Component.literal(opt.item().getHoverName().getString()),
                Component.literal("§7权重: §f" + opt.weight() + "§8" + pct),
                Component.literal(amount));
    }

    /** FTBQ 模式的单槽点击：左键打开表选择器，右键清空已选表。 */
    private boolean ftbqRowClicked(int y, double mx, double my, int btn) {
        int sx = slotsX();
        if (GuiRenderUtil.isHovering(mx, my, sx, y, SLOT, SLOT)) {
            if (btn == 1) { ftbqTableId = ""; return true; }
            capture();
            openFtbqTablePicker();
            return true;
        }
        return false;
    }

    /** 打开 FTBQ 奖励表选择器：选中即回填 {@link #ftbqTableId}。 */
    private void openFtbqTablePicker() {
        capture();
        Minecraft.getInstance().setScreen(new FtbqTableSelectScreen(this, id -> ftbqTableId = id));
    }

    /** 奖励池排点击：左键开权重/数量区间编辑器，右键直接删除，点「+」开多选选择器。 */
    private boolean rewardRowClicked(int y, int max, double mx, double my, int btn) {
        if (rewardMode == ShopEntry.RewardMode.FTBQ) return ftbqRowClicked(y, mx, my, btn);
        int sx = slotsX();
        for (int i = 0; i < rewardPool.size(); i++) {
            int x = sx + i * PITCH;
            if (GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT)) {
                if (btn == 1) {
                    rewardPool.remove(i);
                    rebuild();
                } else {
                    final int idx = i;
                    capture();
                    Minecraft.getInstance().setScreen(new RewardOptionEditScreen(this, rewardPool.get(idx),
                            updated -> rewardPool.set(idx, updated), () -> rewardPool.remove(idx)));
                }
                return true;
            }
        }
        if (rewardPool.size() < max) {
            int x = sx + rewardPool.size() * PITCH;
            if (GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT)) {
                openRewardPicker();
                return true;
            }
        }
        return false;
    }

    private void drawFluidRow(GuiGraphics g, int y, int mx, int my) {
        int c = cx();
        g.drawString(this.font, "§7流体", c, y + 6, GRAY, true);
        int sx = slotsX();
        for (int j = 0; j < fluids.size(); j++) {
            int x = sx + j * PITCH;
            EditorWidgets.fluidSlot(g, this.font, x, y, fluids.get(j), hover(mx, my, x, y));
            g.drawCenteredString(this.font, fluids.get(j).getAmount() + "", x + 10, y + 21, CYAN);
        }
        if (fluids.size() < rowMax()) {
            int x = sx + fluids.size() * PITCH;
            EditorWidgets.plusSlot(g, this.font, x, y, hover(mx, my, x, y));
        }
    }

    private boolean hover(int mx, int my, int x, int y) {
        return GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT);
    }

    private String rewardModeLabel() {
        return switch (rewardMode) {
            case NONE -> "§8不启用";
            case CHOICE -> "§d自选";
            case RANDOM -> "§b随机";
            case ALL -> "§a全部";
            case FTBQ -> "§9FTBQ表";
        };
    }

    private void cycleRewardMode() {
        rewardMode = switch (rewardMode) {
            case NONE -> ShopEntry.RewardMode.CHOICE;
            case CHOICE -> ShopEntry.RewardMode.RANDOM;
            case RANDOM -> ShopEntry.RewardMode.ALL;
            case ALL -> ShopEntry.RewardMode.FTBQ;
            case FTBQ -> ShopEntry.RewardMode.NONE;
        };
    }

    /** FTBQ 表交付子模式按钮文案（自选/随机/全部，与本地奖励池三种子模式同名同义）。 */
    private String ftbqSubModeLabel() {
        return switch (ftbqSubMode) {
            case CHOICE -> "§d自选";
            case ALL -> "§a全部";
            default -> "§b随机"; // RANDOM 及任何异常值统一显示「随机」（构造器本就会把异常值归一成 RANDOM）
        };
    }

    private void cycleFtbqSubMode() {
        ftbqSubMode = switch (ftbqSubMode) {
            case CHOICE -> ShopEntry.RewardMode.RANDOM;
            case RANDOM -> ShopEntry.RewardMode.ALL;
            default -> ShopEntry.RewardMode.CHOICE;
        };
    }

    /** 交易方向按钮文案（不限/仅购买/仅出售）。 */
    private String tradeModeLabel() {
        return switch (tradeMode) {
            case BUY_ONLY -> "§b仅购买";
            case SELL_ONLY -> "§e仅出售";
            default -> "§a不限";
        };
    }

    private void cycleTradeMode() {
        tradeMode = switch (tradeMode) {
            case BOTH -> ShopEntry.TradeMode.BUY_ONLY;
            case BUY_ONLY -> ShopEntry.TradeMode.SELL_ONLY;
            default -> ShopEntry.TradeMode.BOTH;
        };
    }

    // ===== 交互 =====
    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        // 精确数量小弹窗打开时优先接管：点关闭/点弹窗外=丢弃改动关闭，点确认=写回关闭，
        // 点弹窗内其余位置（数量输入框本身）放行给正常控件点击，好定位光标（qtyEditBox 是真实控件，不用手动转发）
        if (qtyEditorOpen) {
            int[] r = qtyEditorBounds();
            if (!GuiRenderUtil.isHovering(mx, my, r[0], r[1], r[2], r[3])) { closeQtyEditor(false); return true; }
            int closeX = r[0] + r[2] - EDITOR_CLOSE_W - 4, closeY = r[1] + 4;
            if (GuiRenderUtil.isHovering(mx, my, closeX, closeY, EDITOR_CLOSE_W, EDITOR_CLOSE_H)) { closeQtyEditor(false); return true; }
            int confirmH = 16, confirmX = r[0] + 8, confirmY = r[1] + r[3] - confirmH - 6, confirmW = r[2] - 16;
            if (GuiRenderUtil.isHovering(mx, my, confirmX, confirmY, confirmW, confirmH)) { closeQtyEditor(true); return true; }
            return super.universalMouseClicked(mx, my, btn);
        }
        // 描述展开编写大图层打开时优先接管，一律吞掉本次点击避免误触下层控件
        if (descEditorOpen) return handleDescEditorClick(mx, my);
        // 分类下拉窗打开时优先接管：点行选中，点窗外关闭；一律吞掉本次点击避免误触下层控件
        if (catPickerOpen) {
            if (handleCatPickerClick(mx, my)) return true;
            catPickerOpen = false;
            return true;
        }
        if (GuiRenderUtil.isHovering(mx, my, cancelX(), top + 3, 56, 14)) {
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        if (GuiRenderUtil.isHovering(mx, my, confirmX(), top + 3, 70, 14)) {
            if (!catalogSnapshotValid()) {
                showCatalogSnapshotExpired();
                return true;
            }
            submit();
            return true;
        }
        // 分类下拉：打开/关闭「已存在分类」选择窗
        if (GuiRenderUtil.isHovering(mx, my, catCycleX(), fieldsY() - 1, 18, 14)) {
            capture();
            catPickerScroll = 0;
            catPickerOpen = !catPickerOpen;
            return true;
        }
        // 描述展开编写：FTBQ 风格大图层，方便查看/编辑长描述
        if (GuiRenderUtil.isHovering(mx, my, expandDescX(), expandDescY(), EXPAND_DESC_W, 10)) {
            openDescEditor();
            return true;
        }
        int c = cx();
        // 商品排（奖励池接管时禁用，不响应点击）：左键选/重开选择器改数量，右键删（保底留 1 项），点「+」加新项
        if (!goodsLockedByReward() && goodsRowClicked(mx, my, btn)) return true;
        if (costIngredientRowClicked(coins, coinCounts, coinY(), rowMax(), mx, my, btn, this::openCurrencyPicker, true)) return true;
        if (costIngredientRowClicked(items, itemCounts, itemY(), rowMax(), mx, my, btn, () -> openMultiPicker(false), false)) return true;
        if (fluidRowClicked(fluidY(), mx, my, btn)) return true;
        if (iconRowClicked(iconY(), Math.min(MAX_DISPLAY_ICONS, rowMax()), mx, my, btn)) return true;
        // 奖励模式循环按钮
        if (GuiRenderUtil.isHovering(mx, my, c, rewardModeY(), 56, 14)) {
            cycleRewardMode();
            return true;
        }
        // FTBQ 表交付子模式循环按钮（自选/随机/全部），只在 FTBQ 模式下显示/生效
        if (rewardMode == ShopEntry.RewardMode.FTBQ
                && GuiRenderUtil.isHovering(mx, my, c + 64 + 46, rewardModeY(), 44, 14)) {
            cycleFtbqSubMode();
            return true;
        }
        if (rewardRowClicked(rewardPoolY(), Math.min(MAX_REWARD_OPTIONS, rowMax()), mx, my, btn)) return true;
        // 隐藏商品开关
        if (GuiRenderUtil.isHovering(mx, my, c, hiddenY(), 56, 14)) {
            hidden = !hidden;
            return true;
        }
        // 交易方向循环按钮（不限/仅购买/仅出售）
        if (GuiRenderUtil.isHovering(mx, my, c + 56 + 8, hiddenY(), 64, 14)) {
            cycleTradeMode();
            return true;
        }
        return super.universalMouseClicked(mx, my, btn);
    }

    /** 描述大图层打开时拦住 ESC/Enter：ESC 丢弃改动关闭，Enter/Shift+Enter 确认保存关闭（单行框无需换行 Enter）。 */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 数量小弹窗打开时拦住 ESC/Enter：ESC 丢弃改动关闭，Enter 确认写回关闭；qtyEditBox 是真实控件，
        // 其余按键（数字输入/退格/方向键）走 super 走正常的 Screen 焦点路由，不用像 descArea 那样手动转发
        if (qtyEditorOpen) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { closeQtyEditor(false); return true; }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                closeQtyEditor(true);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (descEditorOpen) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { closeDescEditor(false); return true; }
            // Ctrl+回车快速保存关闭；普通回车交给 descArea 当换行处理（真多行编辑区，回车不再等于确认）
            if ((keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)
                    && net.minecraft.client.gui.screens.Screen.hasControlDown()) {
                closeDescEditor(true);
                return true;
            }
            if (descArea != null) descArea.keyPressed(keyCode);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (descEditorOpen) {
            if (descArea != null) descArea.charTyped(c);
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    @Override
    protected boolean universalMouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (descEditorOpen) {
            if (descArea != null) descArea.mouseDragged(mx, my);
            return true;
        }
        return super.universalMouseDragged(mx, my, btn, dx, dy);
    }

    /**
     * 商品排点击：左键选/重开选择器改数量，右键删除（保底留 1 项，最后一项不响应右键，防清空商品），
     * 点「+」开多选选择器加新项。语义同 {@link #itemRowClicked}，多一条「不能删空」的保底。
     */
    private boolean goodsRowClicked(double mx, double my, int btn) {
        int y = goodsY(), max = goodsRowMax();
        int sx = slotsX();
        for (int i = 0; i < goodsList.size(); i++) {
            int x = sx + i * PITCH;
            if (GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT)) {
                if (btn == 1) {
                    if (goodsList.size() > 1) { goodsList.remove(i); rebuild(); }
                } else {
                    final int idx = i;
                    capture();
                    EditorWidgets.openItemPicker(goodsList.get(idx),
                            st -> { if (st != null && !st.isEmpty()) goodsList.set(idx, st); });
                }
                return true;
            }
        }
        if (goodsList.size() < max) {
            int x = sx + goodsList.size() * PITCH;
            if (GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT)) {
                openGoodsPicker();
                return true;
            }
        }
        return false;
    }

    /** 打开商品多选选择器：加入 goodsList 排（只收物品，商品不支持流体）。 */
    private void openGoodsPicker() {
        capture();
        Minecraft.getInstance().setScreen(new MultiPickerScreen(this, false,
                st -> { if (st != null && !st.isEmpty()) goodsList.add(st); },
                fs -> {}));
    }

    /** 打开自建多选选择器：物品加入 items 排、流体加入 fluids 排（一次会话可混选）。 */
    private void openMultiPicker(boolean browseFluid) {
        capture();
        Minecraft.getInstance().setScreen(new MultiPickerScreen(this, browseFluid,
                st -> {
                    if (st == null || st.isEmpty()) return;
                    long cnt = Math.max(1L, (long) st.getCount());
                    ItemStack template = st.copy();
                    template.setCount(1); // 真实数量走 itemCounts，stack 本身钉 1（避免 vanilla 角标跟真实值对不上）
                    items.add(template);
                    itemCounts.add(cnt);
                },
                fs -> { if (fs != null && !fs.isEmpty()) fluids.add(fs); }));
    }

    /**
     * 打开奖励池多选选择器：加入 rewardPool 排，最多 {@link #MAX_REWARD_OPTIONS} 项（超出静默丢弃）。只收物品（要能直接进背包/AE）。
     * 新加入项默认权重 1、固定数量 = 所选物品自带的 count；权重/数量区间随后可点槽位打开 {@link RewardOptionEditScreen} 调整。
     */
    private void openRewardPicker() {
        capture();
        Minecraft.getInstance().setScreen(new MultiPickerScreen(this, false,
                st -> { if (st != null && !st.isEmpty() && rewardPool.size() < MAX_REWARD_OPTIONS) rewardPool.add(ShopEntry.RewardOption.simple(st)); },
                fs -> {}));
    }

    /**
     * 打开受限多选选择器：只列出 {@code currency_rates.json} 里已配置的货币，加入 coins 排。
     * 取代原先全物品注册表大海捞针的 FTBLib 单选。
     */
    private void openCurrencyPicker() {
        capture();
        java.util.List<ResourceLocation> allowed = com.dishanhai.gt_shanhai.common.shop.CurrencyRateConfig.getCurrencies();
        Minecraft.getInstance().setScreen(new MultiPickerScreen(this, allowed,
                st -> {
                    if (st == null || st.isEmpty()) return;
                    long cnt = Math.max(1L, (long) st.getCount());
                    ItemStack template = st.copy();
                    template.setCount(1);
                    coins.add(template);
                    coinCounts.add(cnt);
                }));
    }

    /** 画显示图标排：物品/贴图混合渲染（贴图无数量角标）。 */
    private void drawIconRow(GuiGraphics g, String label, int y, int max, int mx, int my) {
        int c = cx();
        g.drawString(this.font, label, c, y + 6, GRAY, true);
        int sx = slotsX();
        for (int i = 0; i < displayIcons.size(); i++) {
            int x = sx + i * PITCH;
            ShopEntry.DisplayIcon d = displayIcons.get(i);
            boolean hv = hover(mx, my, x, y);
            if (d.isTexture()) {
                EditorWidgets.checkerSlot(g, x, y, hv);
                EditorWidgets.textureThumb(g, x + 2, y + 2, 16, d.texture());
            } else {
                EditorWidgets.itemSlot(g, this.font, x, y, d.item(), hv);
            }
        }
        if (displayIcons.size() < max) {
            int x = sx + displayIcons.size() * PITCH;
            EditorWidgets.plusSlot(g, this.font, x, y, hover(mx, my, x, y));
        }
    }

    /**
     * 显示图标排点击：左键——物品图标开单项编辑器（贴图图标无编辑，FTBLib 无贴图单选器，只能删了重选）；
     * 右键删除；点「+」开多选选择器。
     */
    private boolean iconRowClicked(int y, int max, double mx, double my, int btn) {
        int sx = slotsX();
        for (int i = 0; i < displayIcons.size(); i++) {
            int x = sx + i * PITCH;
            if (GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT)) {
                if (btn == 1) {
                    displayIcons.remove(i);
                    rebuild();
                } else {
                    ShopEntry.DisplayIcon d = displayIcons.get(i);
                    if (!d.isTexture()) {
                        final int idx = i;
                        capture();
                        EditorWidgets.openItemPicker(d.item(), st -> {
                            if (st == null || st.isEmpty()) displayIcons.remove(idx);
                            else displayIcons.set(idx, ShopEntry.DisplayIcon.ofItem(st));
                        });
                    }
                }
                return true;
            }
        }
        if (displayIcons.size() < max) {
            int x = sx + displayIcons.size() * PITCH;
            if (GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT)) {
                openIconPicker();
                return true;
            }
        }
        return false;
    }

    /** 打开显示图标多选选择器：物品/贴图混选加入 displayIcons 排，最多 1 主 4 附属（超出静默丢弃）。 */
    private void openIconPicker() {
        capture();
        Minecraft.getInstance().setScreen(new MultiPickerScreen(this,
                st -> {
                    if (st != null && !st.isEmpty() && displayIcons.size() < MAX_DISPLAY_ICONS) {
                        ItemStack icon = st.copy();
                        icon.setCount(1);
                        displayIcons.add(ShopEntry.DisplayIcon.ofItem(icon));
                    }
                },
                tex -> {
                    if (tex != null && displayIcons.size() < MAX_DISPLAY_ICONS) {
                        displayIcons.add(ShopEntry.DisplayIcon.ofTexture(tex));
                    }
                }));
    }

    private boolean fluidRowClicked(int y, double mx, double my, int btn) {
        int sx = slotsX();
        for (int j = 0; j < fluids.size(); j++) {
            int x = sx + j * PITCH;
            if (GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT)) {
                if (btn == 1) { fluids.remove(j); rebuild(); }
                else { final int jdx = j; capture(); EditorWidgets.openFluidPicker(fluids.get(jdx),
                        fs -> { if (fs == null || fs.isEmpty()) fluids.remove(jdx); else fluids.set(jdx, fs); }); }
                return true;
            }
        }
        if (fluids.size() < rowMax()) {
            int x = sx + fluids.size() * PITCH;
            if (GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT)) {
                openMultiPicker(true);
                return true;
            }
        }
        return false;
    }

    private ShopCost buildCost() {
        LinkedHashMap<ResourceLocation, BigInteger> coinMap = new LinkedHashMap<>();
        for (int i = 0; i < coins.size(); i++) {
            ItemStack st = coins.get(i);
            if (st == null || st.isEmpty()) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(st.getItem());
            long cnt = i < coinCounts.size() ? Math.max(1L, coinCounts.get(i)) : 1L;
            if (id != null) coinMap.merge(id, BigInteger.valueOf(cnt), BigInteger::add);
        }
        List<ExchangeEntry.Ingredient> physical = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ItemStack st = items.get(i);
            if (st == null || st.isEmpty()) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(st.getItem());
            long cnt = i < itemCounts.size() ? Math.max(1L, itemCounts.get(i)) : 1L;
            if (id != null) physical.add(new ExchangeEntry.Ingredient(id, false, cnt, st.getTag()));
        }
        for (FluidStack fs : fluids) {
            if (fs == null || fs.isEmpty()) continue;
            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fs.getFluid());
            if (id != null) physical.add(new ExchangeEntry.Ingredient(id, true, Math.max(1L, fs.getAmount())));
        }
        return new ShopCost(spark, coinMap, physical);
    }

    private void submit() {
        if (!catalogSnapshotValid()) {
            showCatalogSnapshotExpired();
            return;
        }
        capture();
        if (rewardMode == ShopEntry.RewardMode.FTBQ && ftbqTableId.isEmpty()) {
            var p = Minecraft.getInstance().player;
            if (p != null) p.displayClientMessage(Component.literal("§c[商店] 奖励模式选了 FTBQ 但未选表，未保存"), false);
            return;
        }
        // 商品清单：奖励池接管时只镜像池首项（身份占位，非空校验/兜底图标用）；否则用编辑器里的商品排——
        // 首项数量吃独立的 count/countBox（与改造前单商品行为一致），第2项起用各自槽位自带数量（点槽位重开选择器改）
        List<ItemStack> sourceStacks = goodsLockedByReward() ? java.util.List.of(lockedGoodsDisplay()) : goodsList;
        List<ShopEntry.GoodsStack> goodsForSubmit = new ArrayList<>();
        for (int i = 0; i < sourceStacks.size(); i++) {
            ItemStack st = sourceStacks.get(i);
            if (st == null || st.isEmpty()) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(st.getItem());
            if (id == null) continue;
            int cnt = (i == 0) ? Math.max(1, count) : Math.max(1, st.getCount());
            goodsForSubmit.add(ShopEntry.GoodsStack.of(id, cnt, st.getTag()));
        }
        if (goodsForSubmit.isEmpty()) {
            var p = Minecraft.getInstance().player;
            if (p != null) p.displayClientMessage(Component.literal("§c[商店] 商品身份解析失败（FTBQ表里没有物品类奖励？），未保存"), false);
            return;
        }
        ShopCost cost = buildCost();
        if (!cost.isValid()) {
            var p = Minecraft.getInstance().player;
            if (p != null) p.displayClientMessage(Component.literal("§c[商店] 成本不能为空（至少一项星火/币种/物品/流体），未保存"), false);
            return;
        }
        String cat = (category == null || category.isBlank()) ? ShopEntry.DEFAULT_CATEGORY : category.trim();
        String desc = description == null ? "" : description.trim();
        // 周期限购的周期在 UI 层用现实秒，发包前换算成 tick；两框须都非空才生效（半填在 ShopEntry 构造器里会被归一成不启用）
        long periodTicksToSend = periodSeconds > 0L ? periodSeconds * TICKS_PER_SECOND : -1L;
        ShopEditPacket pkt = new ShopEditPacket(
                isNew ? ShopEditPacket.Action.ADD : ShopEditPacket.Action.EDIT,
                goodsForSubmit, cat, desc, cost, oldGoods, oldCategory == null ? "" : oldCategory, -1, limit,
                displayIcons, rewardMode, rewardPool, hidden, linkKey, linkTo, displayName, ftbqTableId, ftbqSubMode, tradeMode,
                periodTicksToSend, periodCap, catalogRevision, oldEntryKey);
        ShanhaiNetwork.CHANNEL.sendToServer(pkt);
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    protected void renderTooltips(GuiGraphics g, int smx, int smy, int mx, int my) {
        if (hoverRewardOption != null) {
            g.renderComponentTooltip(this.font, rewardTooltip(hoverRewardOption), mx, my);
        }
    }

    private void drawBtn(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my) {
        boolean hv = GuiRenderUtil.isHovering(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, GOLD_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hv ? BTN_HOVER : BTN_BG);
        g.drawCenteredString(this.font, label, x + w / 2, y + (h - 8) / 2, WHITE);
    }
}
