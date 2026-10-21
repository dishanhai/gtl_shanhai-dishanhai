package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.shop.ShopEntryEditLayout.Row;
import com.dishanhai.gt_shanhai.client.gui.shop.ShopEntryEditLayout.Tab;
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
import net.minecraft.util.FormattedCharSequence;
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
 *
 * <p>字段分在五个页签（商品/价格/限购/奖励/跳转，见 {@link ShopEntryEditLayout}），每页的行按实际高度
 * 往下堆，说明换行就长高；取消/保存在页脚右下。</p>
 */
public class ShopEntryEditScreen extends ScaledScreen {

    private static final int GOLD = -22016;
    private static final int GOLD_DARK = -7710208;
    private static final int PANEL_BG = -267382768;
    private static final int PANEL_INNER = -266724838;
    private static final int GRAY = -5592406;
    private static final int CYAN = -11141121;
    private static final int BTN_BG = -14935012;
    private static final int BTN_HOVER = -12303292;

    private static final int TARGET_W = 520;
    // 編輯內容固定在可視區內，超出的欄位透過右側垂直滾動條查看；TARGET_H 不再需要覆蓋全部欄位。
    private static final int TARGET_H = 380;
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

    private final List<ItemStack> goodsList = new ArrayList<>(); // 商品清单-物品部分（首项数量由 count/countBox 驱动，第2项起用各自槽位自带数量）
    // 商品清单-流体部分：紧接 goodsList 之后共享同一排槽位（同「商品排」交互），槽下数字为 mB，自带数量独立编辑。
    // 流体商品没有背包容器可落地，购买交付强制要求开启 AE 模式且绑定在线 AE 网络能全额收下，见 ShopPurchase#buyBulk。
    private final List<FluidStack> goodsFluids = new ArrayList<>();
    private int count;
    private String category;
    private String description;
    private long limit = -1L; // 服務端次數；-1 = 不限
    private long saveUses = -1L; // 當前存檔剩餘次數；-1 = 不限
    private final long initialSaveUses;
    private long periodSeconds = -1L; // 周期限购的周期长度（现实秒，服务器正常速度下 1秒=20tick）；-1 = 不启用（默认）
    private long periodCap = -1L;  // 周期限购每周期额度（每玩家独立计数）；-1 = 不启用（默认），与 periodSeconds 须同时填写才生效
    private int discountPercent;   // 限时折扣百分比（1-90）；0 = 不打折（默认）
    private long discountMinutes = -1L; // UI 层「持续多少分钟」暂存，-1 = 不启用；提交时按「从现在开始」换算成绝对起止时间戳
    // 多元成本草稿
    private BigInteger spark = BigInteger.ZERO;
    private BigInteger eu = BigInteger.ZERO; // 无线电网 EU 成本（第5通道，gtladditions/gtmthings，见 ShopWirelessEu）
    private boolean freeGoods; // 真正免费：五通道全空，购买不扣任何东西。出售关闭，避免白扣物品。
    // 币种/物品：真实数量存 coinCounts/itemCounts（long），不再用 ItemStack.count（int，最多到 21 亿就截断，
    // 见反馈——旧版本 fillCost/buildCost 靠 stack.getCount() 存取，超 int 上限的配方悄悄被截断成 2,147,483,647）。
    // ItemStack 本身的 count 固定钉在 1（vanilla count!=1 才画数量角标，钉 1 就不会画出跟真实数量对不上的角标）。
    private final List<ItemStack> coins = new ArrayList<>();   // 钱包币种（身份+NBT，count 恒为 1）
    private final List<Long> coinCounts = new ArrayList<>();   // 与 coins 逐一对应的真实数量
    private final List<ItemStack> items = new ArrayList<>();   // 实物物品（身份+NBT，count 恒为 1）
    private final List<Long> itemCounts = new ArrayList<>();   // 与 items 逐一对应的真实数量
    private final List<ItemStack> submissionItems = new ArrayList<>(); // 购买前一次性提交物品
    private final List<Long> submissionCounts = new ArrayList<>();
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
    private String prerequisiteQuestId = ""; // 前置 FTBQ 任务 ID（十六进制），可空 = 不要求前置

    private EditBox countBox, catBox, sparkBox, euBox, descBox, limitBox, linkKeyBox, linkToBox, nameBox, periodSecondsBox, periodCapBox;
    private EditBox discountPercentBox, discountMinutesBox, saveUsesBox;
    private MultiLineTextArea descArea;       // 描述「展开编写」大图层里的多行编辑区（与 descBox 同源，双向同步）
    private boolean descEditorOpen;           // 描述展开编写大图层开关
    private int left, top, panelWidth, panelHeight;
    private Tab tab = Tab.GOODS;
    private final int[] tabScroll = new int[Tab.values().length]; // 各页各自记住滚到哪
    private final int[] rowYs = new int[Row.values().length];      // 本页各行相对内容顶端的 y
    private final int[] rowHs = new int[Row.values().length];      // 本页各行高度；不在本页/被隐藏 = 0
    private int contentH;
    /** 按钮/页签/整页滚动条：和商店弹窗同一套按下、回弹、点击声。 */
    private final ShopDialogUi ui = new ShopDialogUi(GOLD_DARK, GOLD, BTN_BG, BTN_HOVER);
    private boolean catPickerOpen;            // 分类下拉选择窗开关
    private int catPickerScroll;              // 下拉窗滚动偏移（分类较多时用滚轮翻页）
    private static final int CAT_ROW_H = 12;  // 下拉窗每行高
    private int coinCostScroll;               // 币种成本排横向槽位滚动
    private int itemCostScroll;               // 物品成本排横向槽位滚动
    private int fluidCostScroll;              // 流体成本排横向槽位滚动
    private int submissionScroll;             // 提交物品排横向槽位滚动
    private int goodsRowScroll;               // 商品排横向槽位滚动（商品排右边界是「显示名称」框，viewport 比成本排窄）
    private int draggingCostRow;              // 1=币种，2=物品，3=流体，4=商品；0=未拖拽
    /** 商品排的「行号」：与币种/物品/流体共用同一套横向滚动机制，只是 viewport 右界不同。 */
    private static final int GOODS_ROW = 4;

    // 币种/物品「精确数量」小弹窗：绕开 FTBLib 选择器数量框的 int 上限，直接文本输入任意 long。
    private EditBox qtyEditBox;
    private boolean qtyEditorOpen;
    private boolean qtyEditIsCoin; // true=编辑 coins/coinCounts，false=编辑 items/itemCounts
    private int qtyEditIndex;

    // 取消/Esc 回商店时，草稿和打开时不一样就先问一句「放弃修改？」
    private final String initialFingerprint;
    private boolean discardConfirmOpen;
    private static final String DISCARD_TITLE = "§6放弃未保存的修改？";
    private static final String DISCARD_BODY = "§7回到商店后，这次的改动都不会保存。§8Esc 继续编辑 · Enter 放弃修改";
    private static final String KEY_HINT = "§8Ctrl+1~5 切页 · Ctrl+Tab 下一页 · Ctrl+Enter 保存 · Esc 返回";

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
        // isNew=true 时恒为 -1：entry!=null && isNew==true 是「复制为新条目」场景（预填字段但不关联旧条目身份），
        // 否则 submit() 会按 EDIT 语义误把 oldEntryKey 一起发出去，服务端 apply() 的 ADD 分支会直接拒绝。
        this.oldEntryKey = (entry == null || isNew) ? -1L : ClientShopCatalog.keyOf(entry);
        if (entry != null) {
            int firstItemCount = 1;
            boolean sawFirstItem = false;
            for (ShopEntry.GoodsStack gs : entry.getGoodsList()) {
                if (gs.isFluid()) {
                    Fluid fluid = gs.fluid();
                    if (fluid != net.minecraft.world.level.material.Fluids.EMPTY) {
                        goodsFluids.add(FluidStack.create(fluid, Math.max(1L, gs.count())));
                    }
                } else {
                    goodsList.add(gs.makeStack());
                    if (!sawFirstItem) { firstItemCount = gs.count(); sawFirstItem = true; }
                }
            }
            if (goodsList.isEmpty() && goodsFluids.isEmpty()) goodsList.add(ItemStack.EMPTY); // 保底占位，避免整单商品全空
            if (!goodsList.isEmpty()) goodsList.get(0).setCount(1); // 首项数量由独立的 count/countBox 驱动，槽位图标本身只画 1（不叠数量角标）
            this.count = firstItemCount;
            this.category = entry.getCategory();
            this.description = entry.getDescription();
            this.limit = entry.getServerUses();
            this.saveUses = isNew ? limit : entry.getRemainingUses();
            this.periodSeconds = entry.isPeriodLimited() ? entry.getPeriodTicks() / TICKS_PER_SECOND : -1L;
            this.periodCap = entry.isPeriodLimited() ? entry.getPeriodLimit() : -1L;
            // 折扣生效中才预填：按剩余时间倒推「持续分钟数」，原样提交约等于保留原定结束时间；
            // 已过期/未配置一律显示不启用，不把历史起止时间戳当当前状态展示
            this.discountPercent = entry.isDiscountActive() ? entry.getDiscountPercent() : 0;
            this.discountMinutes = entry.isDiscountActive()
                    ? Math.max(1L, (entry.discountRemainingMs() + 59_999L) / 60_000L) : -1L;
            this.oldGoods = entry.getGoodsId();
            this.oldCategory = entry.getCategory();
            fillCost(entry.getCost());
            displayIcons.addAll(entry.getDisplayIcons());
            this.rewardMode = entry.getRewardMode();
            rewardPool.addAll(entry.getRewardPool());
            this.ftbqTableId = entry.getFtbqTableId();
            this.ftbqSubMode = entry.getFtbqSubMode();
            this.hidden = entry.isHidden();
            // 复制模式不继承别名：linkKey 是「供其他条目跳转引用」的身份标识，照抄会出现两条目认领同一别名
            this.linkKey = isNew ? "" : entry.getLinkKey();
            this.linkTo = entry.getLinkTo();
            this.displayName = entry.getDisplayName();
            this.tradeMode = entry.getTradeMode();
            this.prerequisiteQuestId = entry.getPrerequisiteQuestId();
            for (ExchangeEntry.Ingredient item : entry.getSubmissionItems()) {
                ItemStack stack = item.makeUnitStack();
                if (!stack.isEmpty()) {
                    submissionItems.add(stack);
                    submissionCounts.add(item.count);
                }
            }
        } else {
            goodsList.add(ItemStack.EMPTY); // 新增商品不预填默认物品，留空槽等玩家自己选（submit() 已有空商品校验拦截）
            this.count = 1;
            this.category = (defaultCategory == null || defaultCategory.isBlank()) ? ShopEntry.DEFAULT_CATEGORY : defaultCategory;
            this.description = "";
            this.oldGoods = null;
            this.oldCategory = null;
        }
        this.initialSaveUses = saveUses;
        this.initialFingerprint = draftFingerprint();
    }

    /** 草稿的指纹：取消/Esc 时和打开时比，变了才问要不要放弃。涵盖所有会被保存的字段。 */
    private String draftFingerprint() {
        StringBuilder sb = new StringBuilder();
        for (ItemStack st : goodsList) sb.append(stackKey(st)).append(';');
        sb.append('|');
        for (FluidStack fs : goodsFluids) sb.append(fluidKey(fs)).append(';');
        sb.append('|').append(count).append('|').append(category).append('|').append(description)
                .append('|').append(limit).append('|').append(saveUses)
                .append('|').append(periodSeconds).append('|').append(periodCap)
                .append('|').append(discountPercent).append('|').append(discountMinutes)
                .append('|').append(spark).append('|').append(eu).append('|').append(freeGoods).append('|');
        appendCounted(sb, coins, coinCounts);
        appendCounted(sb, items, itemCounts);
        appendCounted(sb, submissionItems, submissionCounts);
        for (FluidStack fs : fluids) sb.append(fluidKey(fs)).append(';');
        sb.append('|');
        for (ShopEntry.DisplayIcon d : displayIcons) sb.append(d.isTexture() ? String.valueOf(d.texture()) : stackKey(d.item())).append(';');
        sb.append('|').append(rewardMode).append('|');
        for (ShopEntry.RewardOption o : rewardPool) {
            sb.append(stackKey(o.item())).append(',').append(o.weight()).append(',')
                    .append(o.minCount()).append(',').append(o.maxCount()).append(';');
        }
        sb.append('|').append(ftbqTableId).append('|').append(ftbqSubMode).append('|').append(hidden)
                .append('|').append(linkKey).append('|').append(linkTo).append('|').append(displayName)
                .append('|').append(tradeMode).append('|').append(prerequisiteQuestId);
        return sb.toString();
    }

    private static void appendCounted(StringBuilder sb, List<ItemStack> stacks, List<Long> counts) {
        for (int i = 0; i < stacks.size(); i++) {
            sb.append(stackKey(stacks.get(i))).append('*').append(i < counts.size() ? counts.get(i) : 1L).append(';');
        }
        sb.append('|');
    }

    private static String stackKey(ItemStack st) {
        if (st == null || st.isEmpty()) return "-";
        return ForgeRegistries.ITEMS.getKey(st.getItem()) + "x" + st.getCount() + (st.hasTag() ? String.valueOf(st.getTag()) : "");
    }

    private static String fluidKey(FluidStack fs) {
        return fs == null || fs.isEmpty() ? "-" : ForgeRegistries.FLUIDS.getKey(fs.getFluid()) + "x" + fs.getAmount();
    }

    /** 取消/Esc 回商店：有没保存的修改先问一句，免得一下全丢。 */
    private void requestClose() {
        capture();
        if (!draftFingerprint().equals(initialFingerprint)) {
            catPickerOpen = false;
            setFocused(null);
            discardConfirmOpen = true;
            return;
        }
        Minecraft.getInstance().setScreen(parent);
    }

    private void discardAndClose() {
        discardConfirmOpen = false;
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void onClose() {
        requestClose();
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
        this.freeGoods = cost.isEmpty();
        this.spark = cost.spark;
        this.eu = cost.eu;
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

    /** 按 {@link #prerequisiteQuestId} 查客户端已同步的 FTBQ 任务实例（未装 FTBQ / 未选 / 未同步返回 null）。 */
    private dev.ftb.mods.ftbquests.quest.Quest resolveClientPrerequisiteQuest() {
        if (prerequisiteQuestId == null || prerequisiteQuestId.isEmpty()) return null;
        dev.ftb.mods.ftbquests.client.ClientQuestFile file = dev.ftb.mods.ftbquests.client.ClientQuestFile.INSTANCE;
        if (file == null) return null;
        long id = dev.ftb.mods.ftbquests.quest.QuestObjectBase.parseCodeString(prerequisiteQuestId);
        if (id == 0L) return null;
        return file.getQuest(id);
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

    // ===== 布局：五页签，行按实际高度往下堆（见 ShopEntryEditLayout） =====
    private static final int ROW_GAP = 8;
    private static final int LINE_H = 10;
    private static final int FIELD_H = 14;
    private static final int SLOT_ROW_H = SLOT + 10; // 槽 20 + 槽下数量文字
    /** 左侧标签栏宽：所有输入框、槽位、按钮都从这条竖线起，上下对齐。 */
    private static final int LABEL_W = 64;
    private static final int TAB_H = 14;
    private static final int TAB_GAP = 3;
    private static final int FOOTER_BTN_H = 16;
    private static final int CANCEL_W = 56;
    private static final int CONFIRM_W = 70;

    private static final String CAT_HINT = "§8可填「主/子」建子分组";
    private static final String ICON_HINT = "§8可选：1主+最多4附属角标，物品/贴图可混选；不填=用商品本身图标";
    private static final String PRICE_HINT = "§8币种=钱包余额扣 · 物品=背包扣 · 流体=绑定AE抽 · 星火=数字余额 · EU=无线电网。"
            + "点槽位换物品，币种/物品点槽下数字改精确数量，右键删除。";
    private static final String DISCOUNT_HINT = "§81-90%；两框都填才生效，从保存时起算，只打折星火/币种/EU，不动实物成本。"
            + "保存后重新编辑=按剩余时间续算。";
    private static final String PERIOD_HINT = "§8现实秒；如填1000即每1000秒刷新，每玩家独立计数，两框都填才生效，留空=不启用。";
    private static final String REWARD_MODE_HINT = "§8自选=购买前选1项 · 随机=系统随机给1项 · 全部=一次性全给 · FTBQ表=用任务书奖励表。"
            + "奖励池非空才生效，生效后商品栏改由奖励池接管。";
    private static final String REWARD_POOL_HINT = "§8左键改权重与数量区间，右键删除，悬停看概率。";
    private static final String HIDDEN_HINT = "§8隐藏商品不进分类网格，只能靠其他商品「跳转到」直达。";
    private static final String LINK_KEY_HINT = "§8本商品的别名，供其他商品「跳转到」引用。";
    private static final String LINK_TO_HINT = "§8填另一商品的别名，详情页显示跳转按钮。";

    private int cx() { return left + 14; }
    private int fieldX() { return cx() + LABEL_W; }
    private int slotsX() { return fieldX(); }
    /** 内容右界：再往右是整页滚动条。 */
    private int contentRight() { return left + panelWidth - 14; }
    private int tabY() { return top + 18; }
    private int viewTop() { return top + 38; }
    private int viewBottom() { return top + panelHeight - footerH(); }
    private int viewH() { return Math.max(20, viewBottom() - viewTop()); }
    /** 页脚高：左边的提示文字换几行就多高，至少放得下按钮。 */
    private int footerH() { return Math.max(FOOTER_BTN_H + 12, footerLines().size() * LINE_H + 10); }
    private int footerTextW() { return cancelX() - 8 - cx(); }
    private String footerText() { return KEY_HINT; }
    private List<FormattedCharSequence> footerLines() { return wrap(footerText(), footerTextW()); }
    private int footerBtnY() { return top + panelHeight - FOOTER_BTN_H - 6; }
    private int confirmX() { return left + panelWidth - 12 - CONFIRM_W; }
    private int cancelX() { return confirmX() - 6 - CANCEL_W; }

    private int scroll() { return tabScroll[tab.ordinal()]; }
    private void setScroll(int v) { tabScroll[tab.ordinal()] = ShopEntryEditLayout.clampScroll(v, contentH, viewH()); }
    private int rowTop(Row r) { return viewTop() + rowYs[r.ordinal()] - scroll(); }
    private int rowH(Row r) { return rowHs[r.ordinal()]; }
    private boolean rowShown(Row r) { return r.tab == tab && rowHs[r.ordinal()] > 0; }
    private boolean inViewport(double mx, double my) {
        return GuiRenderUtil.isHovering(mx, my, left + 5, viewTop(), panelWidth - 10, viewH());
    }

    // 各行顶端（绘制、命中、拖拽都按它）
    private int goodsY() { return rowTop(Row.GOODS); }
    private int nameY() { return rowTop(Row.NAME); }
    private int countY() { return rowTop(Row.COUNT); }
    private int catY() { return rowTop(Row.CATEGORY); }
    private int descY() { return rowTop(Row.DESC); }
    private int iconY() { return rowTop(Row.ICONS); }
    private int freeBtnX() { return fieldX(); }
    private int freeBtnY() { return rowTop(Row.FREE); }
    private int sparkY() { return rowTop(Row.SPARK); }
    private int coinY() { return rowTop(Row.COIN); }
    private int itemY() { return rowTop(Row.ITEM); }
    private int fluidY() { return rowTop(Row.FLUID); }
    private int discountY() { return rowTop(Row.DISCOUNT); }
    private int usesY() { return rowTop(Row.USES); }
    /** 周期限购：独立于永久总量之外的第二套限购（见 ShopPeriodLimiter）。 */
    private int periodY() { return rowTop(Row.PERIOD); }
    private int tradeY() { return rowTop(Row.TRADE); }
    private int prereqQuestY() { return rowTop(Row.PREREQ); }
    private int submissionY() { return rowTop(Row.SUBMISSION); }
    private int rewardModeY() { return rowTop(Row.REWARD_MODE); }
    private int rewardPoolY() { return rowTop(Row.REWARD_POOL); }
    private int hiddenY() { return rowTop(Row.HIDDEN); }
    private int linkKeyY() { return rowTop(Row.LINK_KEY); }
    private int linkToY() { return rowTop(Row.LINK_TO); }

    private static final int FREE_BTN_W = 56;
    private static final int FREE_BTN_H = FIELD_H;
    // EU 成本：紧跟星火框右侧，同一行（星火/EU 都是纯钱包型通道，物品/流体排另起行）
    private static final int EU_BOX_W = 110;
    private int euLabelX() { return fieldX() + 120 + 10; }
    private int euBoxX() { return euLabelX() + 32; }

    // 描述「展开编写」按钮：描述框右侧，内容右界对齐
    private static final int EXPAND_DESC_W = 60;
    private int expandDescX() { return contentRight() - EXPAND_DESC_W; }
    private int expandDescY() { return descY(); }

    /** 长文字按宽度换行（§ 颜色在续行保留），宽度至少 40，窄到一个字一行也不截断。 */
    private List<FormattedCharSequence> wrap(String text, int width) {
        return this.font.split(DShanhaiTextUtil.fromLegacy(text), Math.max(40, width));
    }

    private void drawLines(GuiGraphics g, List<FormattedCharSequence> lines, int x, int y) {
        for (int i = 0; i < lines.size(); i++) g.drawString(this.font, lines.get(i), x, y + i * LINE_H, GRAY, true);
    }

    /** 控件右侧的说明：在行内垂直居中，多行时从行顶起。 */
    private void drawSideHint(GuiGraphics g, List<FormattedCharSequence> lines, int x, int y, int rowH) {
        drawLines(g, lines, x, y + Math.max(0, (rowH - lines.size() * LINE_H) / 2) + 1);
    }

    private void label(GuiGraphics g, String text, int y) {
        g.drawString(this.font, "§7" + text, cx(), y + 3, GRAY, true);
    }

    private int catHintX() { return catCycleX() + 18 + 6; }
    private int iconHintX() { return slotsX() + MAX_DISPLAY_ICONS * PITCH + 6; }
    private int freeHintX() { return freeBtnX() + FREE_BTN_W + 6; }
    private int hiddenHintX() { return fieldX() + 56 + 6; }
    private int linkHintX() { return fieldX() + 140 + 6; }

    private String freeHint() {
        return freeGoods
                ? "§a免费：购买不消耗星火、币种、物品、流体、EU。§8再点「免费中」可恢复填写成本；免费商品不能出售。"
                : "§8五种成本可同时填写，购买时一起扣。";
    }

    private String submissionHint() {
        return submissionItems.isEmpty() ? "§8留空=购买无需提交" : "§8提交一次后永久解锁";
    }

    /** 单槽右边的名字/标题从这里起（槽 20 + 间隔 4）。 */
    private int slotTextX() { return slotsX() + SLOT + 4; }
    private int slotTextW() { return contentRight() - slotTextX(); }

    /** 奖励池接管时商品格右边的名字；长名字换行，不截断。 */
    private List<FormattedCharSequence> lockedGoodsLines() {
        return wrap("§f" + lockedGoodsDisplay().getHoverName().getString() + " §8(奖励池接管)", slotTextW());
    }

    /** 按当前状态算本页每行的高度与位置；每帧、每次点击/滚轮前都重算（免费开关、奖励模式会让行出现或消失）。 */
    private void layoutRows() {
        java.util.Arrays.fill(rowHs, 0);
        List<Row> rows = ShopEntryEditLayout.rowsOf(tab);
        int[] hs = new int[rows.size()];
        for (int i = 0; i < hs.length; i++) hs[i] = measureRow(rows.get(i));
        int[] ys = ShopEntryEditLayout.stack(hs, ROW_GAP);
        for (int i = 0; i < hs.length; i++) {
            rowHs[rows.get(i).ordinal()] = hs[i];
            rowYs[rows.get(i).ordinal()] = ys[i];
        }
        contentH = ShopEntryEditLayout.contentHeight(hs, ROW_GAP) + 4;
        setScroll(scroll());
    }

    /** 一行占多高；0 = 这行此刻不显示（如免费时的各成本排）。 */
    private int measureRow(Row r) {
        int fullW = contentRight() - fieldX();
        return switch (r) {
            case GOODS -> goodsLockedByReward() ? Math.max(SLOT, lockedGoodsLines().size() * LINE_H) : SLOT_ROW_H;
            case NAME, COUNT, DESC, USES, TRADE -> FIELD_H;
            case CATEGORY -> Math.max(FIELD_H, wrap(CAT_HINT, contentRight() - catHintX()).size() * LINE_H);
            case ICONS -> Math.max(SLOT, wrap(ICON_HINT, contentRight() - iconHintX()).size() * LINE_H);
            case FREE -> Math.max(FIELD_H, wrap(freeHint(), contentRight() - freeHintX()).size() * LINE_H);
            case SPARK -> freeGoods ? 0 : FIELD_H;
            case COIN, ITEM, FLUID -> freeGoods ? 0 : SLOT_ROW_H;
            case PRICE_HINT -> freeGoods ? 0 : wrap(PRICE_HINT, fullW).size() * LINE_H;
            case DISCOUNT -> FIELD_H + 2 + wrap(DISCOUNT_HINT, fullW).size() * LINE_H;
            case PERIOD -> FIELD_H + 2 + wrap(PERIOD_HINT, fullW).size() * LINE_H;
            case PREREQ -> Math.max(SLOT, prereqLines().size() * LINE_H);
            case SUBMISSION -> SLOT_ROW_H + wrap(submissionHint(), fullW).size() * LINE_H;
            case REWARD_MODE -> FIELD_H + 2 + wrap(REWARD_MODE_HINT, fullW).size() * LINE_H;
            case REWARD_POOL -> rewardMode == ShopEntry.RewardMode.FTBQ ? Math.max(SLOT, ftbqTableLines().size() * LINE_H)
                    : SLOT + 2 + wrap(REWARD_POOL_HINT, fullW).size() * LINE_H;
            case HIDDEN -> Math.max(FIELD_H, wrap(HIDDEN_HINT, contentRight() - hiddenHintX()).size() * LINE_H);
            case LINK_KEY -> Math.max(FIELD_H, wrap(LINK_KEY_HINT, contentRight() - linkHintX()).size() * LINE_H);
            case LINK_TO -> Math.max(FIELD_H, wrap(LINK_TO_HINT, contentRight() - linkHintX()).size() * LINE_H);
        };
    }

    private int[] tabWidths() {
        Tab[] tabs = Tab.values();
        int[] w = new int[tabs.length];
        for (int i = 0; i < tabs.length; i++) w[i] = Math.max(52, this.font.width(tabs[i].label) + 20);
        return w;
    }

    /** 点到哪个页签（同时给按下反馈）；没点到返回 null。 */
    private Tab tabHit(double mx, double my) {
        int[] w = tabWidths();
        int[] xs = ShopEntryEditLayout.tabXs(w, left + 8, TAB_GAP);
        for (int i = 0; i < xs.length; i++) {
            if (ui.hit(mx, my, xs[i], tabY(), w[i], TAB_H, true)) return Tab.values()[i];
        }
        return null;
    }

    private void drawTabs(GuiGraphics g, int mx, int my) {
        Tab[] tabs = Tab.values();
        int[] w = tabWidths();
        int[] xs = ShopEntryEditLayout.tabXs(w, left + 8, TAB_GAP);
        for (int i = 0; i < tabs.length; i++) {
            boolean active = tabs[i] == tab;
            ui.draw(g, this.font, xs[i], tabY(), w[i], TAB_H, (active ? "§6" : "§7") + tabs[i].label, mx, my, true);
            if (active) g.fill(xs[i] + 2, tabY() + TAB_H - 2, xs[i] + w[i] - 2, tabY() + TAB_H, GOLD);
        }
    }

    /** 切页：先把输入落回草稿，收起只属于旧页的下拉，别的页的输入框不能还接着打字。 */
    private void selectTab(Tab t) {
        if (t == null || t == tab) return;
        capture();
        catPickerOpen = false;
        setFocused(null);
        tab = t;
        layoutRows();
    }

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
        int avail = contentRight() - slotsX();
        return Math.max(3, Math.min(24, avail / PITCH));
    }

    private int iconMax() { return Math.min(MAX_DISPLAY_ICONS, rowMax()); }
    private int rewardMax() { return Math.min(MAX_REWARD_OPTIONS, rowMax()); }

    /** 横向滚动条 x：各槽位排都贴内容右界（显示名称已独占一行，商品排不再让位）。 */
    private int costRowScrollbarX(int row) {
        return contentRight() - 3;
    }

    private int costRowViewportW(int row) {
        return Math.max(PITCH * 3, costRowScrollbarX(row) - 3 - slotsX());
    }

    private int costRowVisibleSlots(int row) {
        return Math.max(1, costRowViewportW(row) / PITCH);
    }

    private int costRowMaxScroll(int row, int itemCount) {
        return Math.max(0, itemCount + 1 - costRowVisibleSlots(row));
    }

    private int clampCostScroll(int row, int scroll, int itemCount) {
        return Math.max(0, Math.min(scroll, costRowMaxScroll(row, itemCount)));
    }

    private int costScrollForRow(int row) {
        if (row == 1) return coinCostScroll;
        if (row == 2) return itemCostScroll;
        if (row == 3) return fluidCostScroll;
        if (row == 5) return submissionScroll;
        if (row == GOODS_ROW) return goodsRowScroll;
        return 0;
    }

    private void setCostScrollForRow(int row, int value) {
        if (row == 1) coinCostScroll = value;
        else if (row == 2) itemCostScroll = value;
        else if (row == 3) fluidCostScroll = value;
        else if (row == 5) submissionScroll = value;
        else if (row == GOODS_ROW) goodsRowScroll = value;
    }

    private int visibleCostRowStart(int row, int itemCount) {
        int s = clampCostScroll(row, costScrollForRow(row), itemCount);
        setCostScrollForRow(row, s);
        return s;
    }

    /** 在缩放坐标系下开启 scissor：enableScissor 吃物理像素，须手动换算。 */
    private void enableScaledScissor(GuiGraphics g, int x, int y, int x2, int y2) {
        g.enableScissor(
                (int) (x * guiScale) + offsetX, (int) (y * guiScale) + offsetY,
                (int) (x2 * guiScale) + offsetX, (int) (y2 * guiScale) + offsetY);
    }

    private void drawCostRowScrollbar(GuiGraphics g, int row, int y, int itemCount, int mx, int my) {
        int maxScroll = costRowMaxScroll(row, itemCount);
        int barX = costRowScrollbarX(row);
        int barH = SLOT + 9;
        g.fill(barX, y, barX + 3, y + barH, BTN_BG);
        if (maxScroll <= 0) return;
        int visible = costRowVisibleSlots(row);
        int content = Math.max(visible + maxScroll, 1);
        int handleH = Math.max(8, barH * visible / content);
        int scroll = clampCostScroll(row, costScrollForRow(row), itemCount);
        int handleY = y + (barH - handleH) * scroll / maxScroll;
        boolean hv = draggingCostRow == row || GuiRenderUtil.isHovering(mx, my, barX, handleY, 3, handleH);
        g.fill(barX, handleY, barX + 3, handleY + handleH, hv ? CYAN : GOLD);
    }

    private boolean costRowScrollbarClicked(int row, int y, int itemCount, double mx, double my) {
        if (costRowMaxScroll(row, itemCount) <= 0) return false;
        int barX = costRowScrollbarX(row);
        int barH = SLOT + 9;
        if (!GuiRenderUtil.isHovering(mx, my, barX, y, 3, barH)) return false;
        draggingCostRow = row;
        updateCostRowScrollFromDrag(row, y, itemCount, my);
        return true;
    }

    private void updateCostRowScrollFromDrag(int row, int y, int itemCount, double my) {
        int maxScroll = costRowMaxScroll(row, itemCount);
        if (maxScroll <= 0) { setCostScrollForRow(row, 0); return; }
        int barH = SLOT + 9;
        int visible = costRowVisibleSlots(row);
        int content = Math.max(visible + maxScroll, 1);
        int handleH = Math.max(8, barH * visible / content);
        double usable = Math.max(1, barH - handleH);
        double rel = (my - y - handleH / 2.0) / usable;
        setCostScrollForRow(row, (int) Math.round(Math.max(0.0, Math.min(1.0, rel)) * maxScroll));
    }

    /** 分类下拉按钮：分类框右侧。 */
    private static final int CAT_BOX_W = 140;
    private int catCycleX() { return fieldX() + CAT_BOX_W + 4; }

    /** 两层次数同一行：存档次数在前，服务端次数跟在后面。 */
    private static final int USES_BOX_W = 110;
    private int limitLabelX() { return fieldX() + USES_BOX_W + 12; }

    private int limitBoxX() { return limitLabelX() + this.font.width("服務端次數") + 6; }

    /** 显示名称独占一行，不再和商品排抢宽度。 */
    private static final int NAME_BOX_W = 200;

    // ===== 周期限购行（说明换行放在框下面）=====
    private static final int PERIOD_SECONDS_W = 46;
    private static final int PERIOD_CAP_W = 70;
    private int periodSecondsBoxX() { return fieldX(); }
    private int periodMidLabelX() { return periodSecondsBoxX() + PERIOD_SECONDS_W + 4; }   // "秒限"
    private int periodCapBoxX() { return periodMidLabelX() + 20; }

    // ===== 限时折扣行（说明换行放在框下面）=====
    private static final int DISCOUNT_PERCENT_W = 30;
    private static final int DISCOUNT_MINUTES_W = 50;
    private int discountPercentBoxX() { return fieldX(); }
    private int discountMidLabelX() { return discountPercentBoxX() + DISCOUNT_PERCENT_W + 4; }   // "%持续"
    private int discountMinutesBoxX() { return discountMidLabelX() + 30; }

    /** 输入框跟着所在行走；只在自己那页、整个框都落在可视区内才显示（半截露出会盖住页签或页脚）。 */
    private void placeEditorWidgets(boolean overlayOpen) {
        placeBox(nameBox, Row.NAME, overlayOpen);
        placeBox(countBox, Row.COUNT, overlayOpen);
        placeBox(catBox, Row.CATEGORY, overlayOpen);
        placeBox(descBox, Row.DESC, overlayOpen);
        placeBox(sparkBox, Row.SPARK, overlayOpen);
        placeBox(euBox, Row.SPARK, overlayOpen);
        placeBox(discountPercentBox, Row.DISCOUNT, overlayOpen);
        placeBox(discountMinutesBox, Row.DISCOUNT, overlayOpen);
        placeBox(saveUsesBox, Row.USES, overlayOpen);
        placeBox(limitBox, Row.USES, overlayOpen);
        placeBox(periodSecondsBox, Row.PERIOD, overlayOpen);
        placeBox(periodCapBox, Row.PERIOD, overlayOpen);
        placeBox(linkKeyBox, Row.LINK_KEY, overlayOpen);
        placeBox(linkToBox, Row.LINK_TO, overlayOpen);
    }

    private void placeBox(EditBox box, Row row, boolean overlayOpen) {
        if (box == null) return;
        int y = rowTop(row);
        box.setY(y);
        box.setVisible(!overlayOpen && rowShown(row) && y >= viewTop() && y + box.getHeight() <= viewBottom());
    }

    private int editorBarX() { return left + panelWidth - 9; }

    private int editorMaxScroll() { return ShopEntryEditLayout.maxScroll(contentH, viewH()); }

    private void drawEditorScrollbar(GuiGraphics g, int mx, int my) {
        ui.drawScrollbar(g, editorBarX(), viewTop(), viewH(), contentH, viewH(), scroll(), editorMaxScroll(), mx, my);
    }

    /** 按在整页滚动条轨道上：开始拖并立刻跳到那里。 */
    private boolean editorScrollbarClicked(double mx, double my) {
        int s = ui.beginDrag(mx, my, editorBarX(), viewTop(), viewH(), contentH, viewH(), editorMaxScroll());
        if (s < 0) return false;
        setScroll(s);
        return true;
    }

    @Override
    protected void initScaled() {
        // 从选择器回来、改窗口大小都会重跑这里：按住的按钮没收到松开，先放掉
        ui.cancel();
        ui.syncEnterHeld();
        left = Math.max(6, (vWidth - TARGET_W) / 2);
        top = Math.max(8, (vHeight - TARGET_H) / 2);
        panelWidth = Math.min(TARGET_W, vWidth - 12);
        panelHeight = Math.min(TARGET_H, vHeight - 16);
        layoutRows();

        int fx = fieldX();
        countBox = mkNumBox(fx, countY(), 60, Integer.toString(count), 1, 8192, v -> count = v);
        catBox = new EditBox(this.font, fx, catY(), CAT_BOX_W, 12, Component.literal("分类"));
        catBox.setMaxLength(32);
        catBox.setValue(category == null ? "" : category);
        catBox.setBordered(true);
        catBox.setTextColor(0xFFFFFF);
        catBox.setResponder(s -> category = s);
        limitBox = new EditBox(this.font, limitBoxX(), usesY(), USES_BOX_W, 12, Component.literal("服務端次數"));
        limitBox.setMaxLength(19);
        limitBox.setValue(limit < 0L ? "" : Long.toString(limit));
        limitBox.setBordered(true);
        limitBox.setTextColor(0xFFFFFF);
        limitBox.setHint(Component.literal("§8不限"));
        limitBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        limitBox.setResponder(s -> {
            long previous = limit;
            limit = parseLimit(s);
            if (isNew && saveUses == previous) {
                saveUses = limit;
                if (saveUsesBox != null) saveUsesBox.setValue(saveUses < 0L ? "" : Long.toString(saveUses));
            }
        });
        saveUsesBox = new EditBox(this.font, fieldX(), usesY(), USES_BOX_W, 12, Component.literal("存檔次數"));
        saveUsesBox.setMaxLength(19);
        saveUsesBox.setValue(saveUses < 0L ? "" : Long.toString(saveUses));
        saveUsesBox.setBordered(true);
        saveUsesBox.setTextColor(0xFFFFFF);
        saveUsesBox.setHint(Component.literal("§8不限"));
        saveUsesBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        saveUsesBox.setResponder(s -> saveUses = parseLimit(s));
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
        // 限时折扣行（第三套机制，独立于两套限购）：百分比 + 持续分钟两框都非空才生效，
        // submit() 按「从保存时起算」换算绝对起止时间戳，ShopEntry 构造器对半填/非法窗口归一成不启用
        discountPercentBox = new EditBox(this.font, discountPercentBoxX(), discountY(), DISCOUNT_PERCENT_W, 12, Component.literal("折扣百分比"));
        discountPercentBox.setMaxLength(2);
        discountPercentBox.setValue(discountPercent > 0 ? Integer.toString(discountPercent) : "");
        discountPercentBox.setBordered(true);
        discountPercentBox.setTextColor(0xFFFFFF);
        discountPercentBox.setHint(Component.literal("§8无"));
        discountPercentBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        discountPercentBox.setResponder(s -> discountPercent = parseDiscountPercent(s));
        discountMinutesBox = new EditBox(this.font, discountMinutesBoxX(), discountY(), DISCOUNT_MINUTES_W, 12, Component.literal("持续分钟"));
        discountMinutesBox.setMaxLength(9);
        discountMinutesBox.setValue(discountMinutes < 0L ? "" : Long.toString(discountMinutes));
        discountMinutesBox.setBordered(true);
        discountMinutesBox.setTextColor(0xFFFFFF);
        discountMinutesBox.setHint(Component.literal("§8不启用"));
        discountMinutesBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        discountMinutesBox.setResponder(s -> discountMinutes = parseLimit(s));
        sparkBox = new EditBox(this.font, slotsX(), sparkY(), 120, 12, Component.literal("星火"));
        sparkBox.setMaxLength(40); // 必须先设，否则 BigInteger 超 32 位数字会被 setValue 按默认长度截断
        sparkBox.setValue(spark.signum() > 0 ? spark.toString() : "");
        sparkBox.setBordered(true);
        sparkBox.setTextColor(0xFFFF55);
        sparkBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        sparkBox.setResponder(s -> spark = parseBig(s));
        euBox = new EditBox(this.font, euBoxX(), sparkY(), EU_BOX_W, 12, Component.literal("EU"));
        euBox.setMaxLength(40); // 同星火框，BigInteger 超 32 位数字需先设最大长度再 setValue
        euBox.setValue(eu.signum() > 0 ? eu.toString() : "");
        euBox.setBordered(true);
        euBox.setTextColor(0xFF55FF);
        euBox.setHint(Component.literal("§8留空=不用EU支付"));
        euBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        euBox.setResponder(s -> eu = parseBig(s));
        int descW = Math.max(80, expandDescX() - 6 - fieldX());
        descBox = new EditBox(this.font, fieldX(), descY(), descW, 14, Component.literal("描述"));
        // 必须先设最大长度再 setValue——setValue 按“当前” maxLength 截断，默认 32，晚设等于白设。
        // 256 太小：capture()/closeDescEditor() 都会把 descBox.getValue() 回写进权威的 description 字段，
        // 一旦大编辑区写的内容超过这个内联预览框的 maxLength，回写时就会被静默截断成 256 字——调大到 4096 兜底。
        descBox.setMaxLength(4096);
        descBox.setValue(description == null ? "" : description);
        descBox.setBordered(true);
        descBox.setTextColor(0xFFFFFF);
        descBox.setResponder(s -> description = s);
        descBox.setHint(Component.literal("§8详情页显示；长描述点「展开编写」"));
        int[] eb = descEditorBounds();
        int descAreaW = eb[2] - 16;
        // 保底可视高度：无论 eb[3]（大图层高度）算出来多小，编辑区至少留够 10 行的显示空间，
        // 避免 h 太小时 visibleLines() 被钳到 1，导致换行后的内容全部不可见（排查用：见行号提示）。
        // 底部要留够「行数提示」这一行（渲染在 y+h+1 处，约 10px 高）+ 确认保存按钮（18px + 6px 间隙）
        // 的空间，原先只留 50px 不够塞下两者，行数提示会被确认保存按钮盖住一截——留 64px 兜底。
        int descAreaH = Math.max(90, eb[3] - 64);
        // resize 会经 init() 重跑到这里：大编辑区开着时旧 descArea 里可能有未保存的输入，
        // 必须先取现场内容再重建，否则 resize 一下未保存的编辑就静默丢了；焦点也要跟着带过去
        String liveDesc = descEditorOpen && descArea != null ? descArea.getValue()
                : (description == null ? "" : description);
        descArea = new MultiLineTextArea(this.font, descAreaW);
        descArea.setBounds(eb[0] + 8, eb[1] + 20, descAreaW, descAreaH);
        descArea.setValue(liveDesc);
        descArea.setVisible(descEditorOpen);
        linkKeyBox = new EditBox(this.font, fieldX(), linkKeyY(), 140, 12, Component.literal("别名"));
        linkKeyBox.setMaxLength(32);
        linkKeyBox.setValue(linkKey == null ? "" : linkKey);
        linkKeyBox.setBordered(true);
        linkKeyBox.setTextColor(0xFFFFFF);
        linkKeyBox.setHint(Component.literal("§8留空=不可被跳转"));
        linkKeyBox.setResponder(s -> linkKey = s);
        linkToBox = new EditBox(this.font, fieldX(), linkToY(), 140, 12, Component.literal("跳转到"));
        linkToBox.setMaxLength(32);
        linkToBox.setValue(linkTo == null ? "" : linkTo);
        linkToBox.setBordered(true);
        linkToBox.setTextColor(0xFFFFFF);
        linkToBox.setHint(Component.literal("§8留空=不显示跳转"));
        linkToBox.setResponder(s -> linkTo = s);
        nameBox = new EditBox(this.font, fieldX(), nameY(), NAME_BOX_W, 12, Component.literal("显示名称"));
        nameBox.setMaxLength(48); // 必须先设，否则超 32 字的显示名称会被 setValue 按默认长度截断
        nameBox.setValue(displayName == null ? "" : displayName);
        nameBox.setBordered(true);
        nameBox.setTextColor(0xFFFFFF);
        nameBox.setHint(Component.literal("§8留空=用商品名称"));
        nameBox.setResponder(s -> displayName = s);
        int[] qb = qtyEditorBounds();
        // 同 descArea：数量小弹窗开着时 resize 重建，要把旧框里已输入的数字和焦点带过去
        String liveQty = qtyEditorOpen && qtyEditBox != null ? qtyEditBox.getValue() : null;
        qtyEditBox = new EditBox(this.font, qb[0] + 8, qb[1] + 20, qb[2] - 16, 14, Component.literal("数量"));
        qtyEditBox.setMaxLength(20);
        qtyEditBox.setBordered(true);
        qtyEditBox.setTextColor(0xFFFFFF);
        qtyEditBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        qtyEditBox.setVisible(qtyEditorOpen);
        if (liveQty != null) {
            qtyEditBox.setValue(liveQty);
            qtyEditBox.setFocused(true);
        }
        addRenderableWidget(countBox);
        addRenderableWidget(catBox);
        addRenderableWidget(limitBox);
        addRenderableWidget(saveUsesBox);
        addRenderableWidget(periodSecondsBox);
        addRenderableWidget(periodCapBox);
        addRenderableWidget(discountPercentBox);
        addRenderableWidget(discountMinutesBox);
        addRenderableWidget(sparkBox);
        addRenderableWidget(euBox);
        addRenderableWidget(descBox);
        addRenderableWidget(linkKeyBox);
        addRenderableWidget(linkToBox);
        addRenderableWidget(nameBox);
        addRenderableWidget(qtyEditBox);
        addWidget(descArea);
        if (descEditorOpen) setFocused(descArea);
        placeEditorWidgets(descEditorOpen || qtyEditorOpen);
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

    /** 空串 = 不限（-1）；否则解析为剩余次数，非法输入回退不限；
     *  纯数字但超 Long.MAX 的输入按 Long.MAX 封顶——不能回退 -1，否则「限购」会被静默反转成「不限」。 */
    private static long parseLimit(String s) {
        if (s == null || s.isBlank()) return -1L;
        try {
            long v = Long.parseLong(s.trim());
            return v < 0L ? -1L : v;
        } catch (NumberFormatException e) {
            return s.trim().matches("\\d+") ? Long.MAX_VALUE : -1L;
        }
    }

    /** 空串/非法 = 不打折（0）；否则解析折扣百分比并夹到 1-90（与 ShopEntry 构造器同口径）。 */
    private static int parseDiscountPercent(String s) {
        long v = parseLimit(s);
        return v <= 0L ? 0 : (int) Math.min(90L, v);
    }

    private void capture() {
        if (catBox != null) category = catBox.getValue();
        if (limitBox != null) limit = parseLimit(limitBox.getValue());
        if (saveUsesBox != null) saveUses = parseLimit(saveUsesBox.getValue());
        if (periodSecondsBox != null) periodSeconds = parseLimit(periodSecondsBox.getValue());
        if (periodCapBox != null) periodCap = parseLimit(periodCapBox.getValue());
        if (discountPercentBox != null) discountPercent = parseDiscountPercent(discountPercentBox.getValue());
        if (discountMinutesBox != null) discountMinutes = parseLimit(discountMinutesBox.getValue());
        if (sparkBox != null) spark = parseBig(sparkBox.getValue());
        if (euBox != null) eu = parseBig(euBox.getValue());
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
    private int catDropX() { return fieldX(); }            // 与 catBox 左对齐
    private int catDropY() { return catY() + 13; }         // catBox 正下方

    /** 下拉窗宽：放得下最长的分类名就不换行，最宽到面板右缘；再长才换行（不截断）。 */
    private int catDropW(List<String> cats) {
        int widest = 0;
        for (String c : cats) widest = Math.max(widest, this.font.width("✔ " + c));
        int maxW = left + panelWidth - 6 - catDropX();
        return Math.max(Math.min(150, maxW), Math.min(widest + 12, maxW));
    }

    /** 下拉窗可用高度：到面板底为止，留一行给翻页提示。 */
    private int catDropAvailH() {
        return Math.max(CAT_ROW_H * 3, (top + panelHeight - 6) - catDropY() - 2 - LINE_H);
    }

    private String catCurrent() {
        return catBox != null ? catBox.getValue() : category;
    }

    private List<FormattedCharSequence> catLines(String cat, String cur, int w) {
        return wrap((cat.equals(cur) ? "§a✔ " : "§f") + cat, w - 8);
    }

    /** 下拉每行高：分类名换行几行就多高。 */
    private int[] catRowHeights(List<String> cats, String cur, int w) {
        int[] hs = new int[cats.size()];
        for (int i = 0; i < hs.length; i++) hs[i] = Math.max(CAT_ROW_H, catLines(cats.get(i), cur, w).size() * LINE_H + 2);
        return hs;
    }

    private int catDropFirst(int[] hs) {
        return Math.max(0, Math.min(catPickerScroll, ShopDialogRows.maxFirst(hs, catDropAvailH())));
    }

    /** 命中下拉窗某行 → 选中该分类填入分类框并关窗，返回 true。 */
    private boolean handleCatPickerClick(double mx, double my) {
        List<String> cats = com.dishanhai.gt_shanhai.common.shop.ShopConfig.getCategories();
        if (cats.isEmpty()) return false;
        int x = catDropX(), w = catDropW(cats);
        int[] hs = catRowHeights(cats, catCurrent(), w);
        int first = catDropFirst(hs);
        int n = ShopDialogRows.fitFrom(hs, first, catDropAvailH());
        int ry = catDropY() + 1;
        for (int i = first; i < first + n; i++) {
            if (GuiRenderUtil.isHovering(mx, my, x, ry, w, hs[i])) {
                ui.select();
                String cat = cats.get(i);
                category = cat;
                if (catBox != null) catBox.setValue(cat);
                catPickerOpen = false;
                return true;
            }
            ry += hs[i];
        }
        return false;
    }

    // ===== 渲染 =====
    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        ui.frame(mx, my);
        hoverRewardOption = null;
        // 展开编写大图层/数量小弹窗/放弃确认框打开时，原本的字段输入框全部隐藏（同帧同步，避免闪烁；本方法在 super.render 画控件前执行）
        boolean editorOpen = descEditorOpen || qtyEditorOpen || discardConfirmOpen;
        layoutRows();
        placeEditorWidgets(editorOpen);
        if (qtyEditBox != null) qtyEditBox.setVisible(qtyEditorOpen);
        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        g.fill(left + 5, tabY() + TAB_H + 2, left + panelWidth - 5, top + panelHeight - 5, PANEL_INNER);

        g.drawString(this.font, "§6" + (isNew ? "新增商品" : "编辑商品"), left + 10, top + 5, GOLD, true);
        drawTabs(g, mx, my);

        // 内容限制在页签与页脚之间；滚动时下方的行不能盖住页签、页脚或面板外
        enableScaledScissor(g, left + 5, viewTop(), left + panelWidth - 5, viewBottom());
        for (Row r : ShopEntryEditLayout.rowsOf(tab)) {
            if (rowH(r) > 0) drawRow(g, r, rowTop(r), mx, my);
        }
        g.disableScissor();
        drawEditorScrollbar(g, mx, my);

        g.fill(left + 5, viewBottom(), left + panelWidth - 5, viewBottom() + 1, 0x40FFFFFF);
        drawLines(g, footerLines(), cx(), viewBottom() + 5);
        ui.draw(g, this.font, cancelX(), footerBtnY(), CANCEL_W, FOOTER_BTN_H, "§c取消", mx, my, true);
        boolean saveable = catalogSnapshotValid();
        ui.draw(g, this.font, confirmX(), footerBtnY(), CONFIRM_W, FOOTER_BTN_H,
                saveable ? "§a确认保存" : "§8请重新打开", mx, my, saveable);

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

    /** 画本页的一行（输入框本身由 super.render 绘制，这里只画标签、按钮、槽位与说明）。 */
    private void drawRow(GuiGraphics g, Row r, int y, int mx, int my) {
        int fullW = contentRight() - fieldX();
        switch (r) {
            case GOODS -> {
                // 奖励模式启用且池非空时，交付内容完全由奖励池决定：商品栏禁用手动选择、镜像池首项
                if (goodsLockedByReward()) {
                    g.drawString(this.font, "§7商品", cx(), y + 6, GRAY, true);
                    EditorWidgets.itemSlot(g, this.font, slotsX(), y, lockedGoodsDisplay(), false);
                    drawSideHint(g, lockedGoodsLines(), slotTextX(), y, rowH(r));
                } else {
                    drawGoodsRow(g, y, mx, my);
                }
            }
            case NAME -> label(g, "显示名称", y);
            case COUNT -> label(g, "每份数量", y);
            case CATEGORY -> {
                label(g, "分类", y);
                ui.draw(g, this.font, catCycleX(), y - 1, 18, 14, "§e▾", mx, my, true);
                drawSideHint(g, wrap(CAT_HINT, contentRight() - catHintX()), catHintX(), y, rowH(r));
            }
            case DESC -> {
                label(g, "描述", y);
                ui.draw(g, this.font, expandDescX(), expandDescY(), EXPAND_DESC_W, FIELD_H, "§b展开编写", mx, my, true);
            }
            case ICONS -> {
                drawIconRow(g, "§7显示图标", y, iconMax(), mx, my);
                drawSideHint(g, wrap(ICON_HINT, contentRight() - iconHintX()), iconHintX(), y, rowH(r));
            }
            case FREE -> {
                label(g, "免费", y);
                ui.draw(g, this.font, freeBtnX(), freeBtnY(), FREE_BTN_W, FREE_BTN_H, freeGoods ? "§a免费中" : "§e设为免费", mx, my, true);
                drawSideHint(g, wrap(freeHint(), contentRight() - freeHintX()), freeHintX(), y, rowH(r));
            }
            case SPARK -> {
                g.drawString(this.font, "§e★星火", cx(), y + 3, GOLD, true);
                // EU（无线电网，见 ShopWirelessEu）：星火框右侧同一行，纯钱包型通道，不需要单独占一排
                g.drawString(this.font, "§d⚡EU", euLabelX(), y + 3, GOLD, true);
            }
            case COIN -> drawCostIngredientRow(g, "§7币种", coins, coinCounts, y, 1, mx, my);
            case ITEM -> drawCostIngredientRow(g, "§7物品", items, itemCounts, y, 2, mx, my);
            case FLUID -> drawFluidRow(g, y, 3, mx, my);
            case PRICE_HINT -> drawLines(g, wrap(PRICE_HINT, fullW), fieldX(), y);
            case DISCOUNT -> {
                // 两框都填才生效，从保存时起算，只打折星火/币种/EU 不动实物成本
                label(g, "限时折扣", y);
                g.drawString(this.font, "§7%持续", discountMidLabelX(), y + 3, GRAY, true);
                g.drawString(this.font, "§7分钟", discountMinutesBoxX() + DISCOUNT_MINUTES_W + 4, y + 3, GRAY, true);
                drawLines(g, wrap(DISCOUNT_HINT, fullW), fieldX(), y + FIELD_H + 2);
            }
            case USES -> {
                g.drawString(this.font, "§7存檔次數", cx(), y + 3, GRAY, true);
                g.drawString(this.font, "§7服務端次數", limitLabelX(), y + 3, GRAY, true);
            }
            case PERIOD -> {
                // 两框都空=不启用，独立于永久总量，每玩家各自计数，到点自动刷新
                label(g, "周期限购", y);
                g.drawString(this.font, "§7秒限", periodMidLabelX(), y + 3, GRAY, true);
                g.drawString(this.font, "§7次", periodCapBoxX() + PERIOD_CAP_W + 4, y + 3, GRAY, true);
                drawLines(g, wrap(PERIOD_HINT, fullW), fieldX(), y + FIELD_H + 2);
            }
            case TRADE -> {
                label(g, "交易方向", y);
                ui.draw(g, this.font, fieldX(), y, 64, FIELD_H, tradeModeLabel(), mx, my, true);
            }
            case PREREQ -> drawPrerequisiteQuestSlot(g, y, mx, my);
            case SUBMISSION -> drawSubmissionRow(g, y, mx, my);
            case REWARD_MODE -> {
                // 仿 FTBQ 奖励表：不启用=普通固定商品；自选/随机/全部/FTBQ表
                label(g, "奖励模式", y);
                ui.draw(g, this.font, fieldX(), y, 56, FIELD_H, rewardModeLabel(), mx, my, true);
                if (rewardMode == ShopEntry.RewardMode.FTBQ) {
                    // FTBQ 表也能像本地奖励池一样走自选/随机/全部三种交付方式，只是奖励来源换成表内容
                    g.drawString(this.font, "§7抽取方式:", ftbqSubLabelX(), y + 3, GRAY, true);
                    ui.draw(g, this.font, ftbqSubBtnX(), y, 44, FIELD_H, ftbqSubModeLabel(), mx, my, true);
                }
                drawLines(g, wrap(REWARD_MODE_HINT, fullW), fieldX(), y + FIELD_H + 2);
            }
            case REWARD_POOL -> {
                drawRewardRow(g, y, rewardMax(), mx, my);
                if (rewardMode != ShopEntry.RewardMode.FTBQ) drawLines(g, wrap(REWARD_POOL_HINT, fullW), fieldX(), y + SLOT + 2);
            }
            case HIDDEN -> {
                // 仿 FTBQ 隐藏任务：隐藏商品不进分类网格，只能被别的条目「跳转到」直达
                label(g, "隐藏", y);
                ui.draw(g, this.font, fieldX(), y, 56, FIELD_H, hidden ? "§c隐藏中" : "§a未隐藏", mx, my, true);
                drawSideHint(g, wrap(HIDDEN_HINT, contentRight() - hiddenHintX()), hiddenHintX(), y, rowH(r));
            }
            case LINK_KEY -> {
                label(g, "别名", y);
                drawSideHint(g, wrap(LINK_KEY_HINT, contentRight() - linkHintX()), linkHintX(), y, rowH(r));
            }
            case LINK_TO -> {
                label(g, "跳转到", y);
                drawSideHint(g, wrap(LINK_TO_HINT, contentRight() - linkHintX()), linkHintX(), y, rowH(r));
            }
        }
    }

    private int ftbqSubLabelX() { return fieldX() + 64; }
    private int ftbqSubBtnX() { return ftbqSubLabelX() + this.font.width("抽取方式:") + 4; }

    /** 点本页的一行；命中按钮/槽位返回 true。 */
    private boolean clickRow(Row r, int y, double mx, double my, int btn) {
        switch (r) {
            case GOODS:
                // 奖励池接管时禁用，不响应点击
                return !goodsLockedByReward() && slotClick(goodsRowClicked(mx, my, btn));
            case CATEGORY:
                if (!ui.hit(mx, my, catCycleX(), y - 1, 18, 14, true)) return false;
                capture();
                catPickerScroll = 0;
                catPickerOpen = !catPickerOpen;
                return true;
            case DESC:
                if (!ui.hit(mx, my, expandDescX(), expandDescY(), EXPAND_DESC_W, FIELD_H, true)) return false;
                openDescEditor();
                return true;
            case ICONS:
                return slotClick(iconRowClicked(y, iconMax(), mx, my, btn));
            case FREE:
                if (!ui.hit(mx, my, freeBtnX(), freeBtnY(), FREE_BTN_W, FREE_BTN_H, true)) return false;
                setFreeGoods(!freeGoods);
                return true;
            case COIN:
                return slotClick(costIngredientRowClicked(coins, coinCounts, y, 1, mx, my, btn, this::openCurrencyPicker, true));
            case ITEM:
                return slotClick(costIngredientRowClicked(items, itemCounts, y, 2, mx, my, btn, () -> openMultiPicker(false), false));
            case FLUID:
                return slotClick(fluidRowClicked(y, 3, mx, my, btn));
            case TRADE:
                if (!ui.hit(mx, my, fieldX(), y, 64, FIELD_H, true)) return false;
                cycleTradeMode();
                return true;
            case PREREQ:
                return slotClick(prerequisiteQuestRowClicked(y, mx, my, btn));
            case SUBMISSION:
                return slotClick(submissionRowClicked(y, mx, my, btn));
            case REWARD_MODE:
                if (ui.hit(mx, my, fieldX(), y, 56, FIELD_H, true)) {
                    cycleRewardMode();
                    return true;
                }
                // FTBQ 表交付子模式（自选/随机/全部），只在 FTBQ 模式下显示/生效
                if (rewardMode == ShopEntry.RewardMode.FTBQ && ui.hit(mx, my, ftbqSubBtnX(), y, 44, FIELD_H, true)) {
                    cycleFtbqSubMode();
                    return true;
                }
                return false;
            case REWARD_POOL:
                return slotClick(rewardRowClicked(y, rewardMax(), mx, my, btn));
            case HIDDEN:
                if (!ui.hit(mx, my, fieldX(), y, 56, FIELD_H, true)) return false;
                hidden = !hidden;
                return true;
            default:
                return false;
        }
    }

    /** 槽位点中（选物品、删除、开数量框）也出一声，和按钮一样有回应。 */
    private boolean slotClick(boolean hit) {
        if (hit) ui.select();
        return hit;
    }

    /** 光标下可横向滚动的槽位排（溢出了才算）：1=币种 2=物品 3=流体 4=商品 5=提交物品，0=没有。 */
    private int slotRowAt(double mx, double my) {
        int[][] rows = {
                {GOODS_ROW, Row.GOODS.ordinal(), goodsList.size() + goodsFluids.size()},
                {1, Row.COIN.ordinal(), coins.size()},
                {2, Row.ITEM.ordinal(), items.size()},
                {3, Row.FLUID.ordinal(), fluids.size()},
                {5, Row.SUBMISSION.ordinal(), submissionItems.size()}};
        for (int[] e : rows) {
            Row r = Row.values()[e[1]];
            if (!rowShown(r) || (r == Row.GOODS && goodsLockedByReward())) continue;
            if (costRowMaxScroll(e[0], e[2]) <= 0) continue;
            if (GuiRenderUtil.isHovering(mx, my, slotsX(), rowTop(r) - 1, costRowViewportW(e[0]) + 6, SLOT + 10)) {
                return e[0];
            }
        }
        return 0;
    }

    private int slotRowCount(int row) {
        return switch (row) {
            case GOODS_ROW -> goodsList.size() + goodsFluids.size();
            case 1 -> coins.size();
            case 2 -> items.size();
            case 3 -> fluids.size();
            default -> submissionItems.size();
        };
    }

    /** 前景层（在所有控件之上绘制）：分类下拉选择窗 / 描述展开编写大图层，故能盖住成本行与输入框。 */
    @Override
    protected void renderScaledForeground(GuiGraphics g, int mx, int my, float pt) {
        if (discardConfirmOpen) {
            renderDiscardConfirm(g, mx, my);
            return;
        }
        if (descEditorOpen) {
            renderDescEditorForeground(g, mx, my);
            return;
        }
        if (qtyEditorOpen) {
            renderQtyEditorForeground(g, mx, my);
            return;
        }
        if (!catPickerOpen) return;
        List<String> cats = com.dishanhai.gt_shanhai.common.shop.ShopConfig.getCategories();
        int x = catDropX(), y = catDropY(), w = catDropW(cats);
        if (cats.isEmpty()) {
            List<FormattedCharSequence> tip = wrap("§8暂无已有分类，直接在框内输入", w - 8);
            int h = tip.size() * LINE_H + 4;
            g.fill(x - 1, y - 1, x + w + 1, y + h + 1, GOLD_DARK);
            g.fill(x, y, x + w, y + h, PANEL_BG);
            drawLines(g, tip, x + 4, y + 3);
            return;
        }
        String cur = catCurrent();
        int[] hs = catRowHeights(cats, cur, w);
        int first = catDropFirst(hs);
        catPickerScroll = first;
        int n = ShopDialogRows.fitFrom(hs, first, catDropAvailH());
        int h = 2;
        for (int i = first; i < first + n; i++) h += hs[i];
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, GOLD_DARK);
        g.fill(x, y, x + w, y + h, PANEL_BG);
        int ry = y + 1;
        for (int i = first; i < first + n; i++) {
            if (GuiRenderUtil.isHovering(mx, my, x, ry, w, hs[i])) g.fill(x, ry, x + w, ry + hs[i], BTN_HOVER);
            drawLines(g, catLines(cats.get(i), cur, w), x + 4, ry + 2);
            ry += hs[i];
        }
        if (ShopDialogRows.maxFirst(hs, catDropAvailH()) > 0) {
            g.drawString(this.font, "§8滚轮翻页 " + (first + 1) + "-" + (first + n) + "/" + cats.size(), x, y + h + 2, GRAY, false);
        }
    }

    /** 描述展开编写大图层前景：标题 + 关闭 X + 真多行编辑区（支持回车换行/方向键/选择/复制粘贴）+ 确认保存按钮。 */
    private void renderDescEditorForeground(GuiGraphics g, int mx, int my) {
        int[] r = descEditorBounds();
        int ox = r[0], oy = r[1], ow = r[2], oh = r[3];
        g.drawString(this.font, "§6编写描述 §8(回车换行，Ctrl+回车快速保存)", ox + 8, oy + 8, GOLD, true);
        int closeX = ox + ow - EDITOR_CLOSE_W - 4, closeY = oy + 4;
        ui.draw(g, this.font, closeX, closeY, EDITOR_CLOSE_W, EDITOR_CLOSE_H, "§cX", mx, my, true);

        if (descArea != null) descArea.render(g, mx, my);

        int confirmH = 18;
        int confirmX = ox + 8, confirmY = oy + oh - confirmH - 6, confirmW = ow - 16;
        ui.draw(g, this.font, confirmX, confirmY, confirmW, confirmH, "§a确认保存", mx, my, true);
    }

    private static final int EDITOR_CLOSE_W = 16;
    private static final int EDITOR_CLOSE_H = 12;

    // ===== 放弃修改确认框（居中于编辑面板，正文换行、框跟着长高） =====
    private int[] discardBounds() {
        int w = Math.min(panelWidth - 40, 240);
        int h = 8 + LINE_H + 4 + wrap(DISCARD_BODY, w - 16).size() * LINE_H + 8 + FOOTER_BTN_H + 8;
        return new int[]{left + (panelWidth - w) / 2, top + (panelHeight - h) / 2, w, h};
    }

    private int discardBtnY(int[] b) { return b[1] + b[3] - FOOTER_BTN_H - 8; }
    private int discardBtnW(int[] b) { return (b[2] - 24) / 2; }
    private int discardKeepX(int[] b) { return b[0] + 8; }
    private int discardDropX(int[] b) { return b[0] + 16 + discardBtnW(b); }

    private void renderDiscardConfirm(GuiGraphics g, int mx, int my) {
        int[] b = discardBounds();
        g.fill(0, 0, vWidth, vHeight, 0xC0000000);
        g.fill(b[0] - 1, b[1] - 1, b[0] + b[2] + 1, b[1] + b[3] + 1, GOLD_DARK);
        g.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], 0xFF101010);
        g.drawString(this.font, DISCARD_TITLE, b[0] + 8, b[1] + 8, GOLD, true);
        drawLines(g, wrap(DISCARD_BODY, b[2] - 16), b[0] + 8, b[1] + 8 + LINE_H + 4);
        int y = discardBtnY(b), w = discardBtnW(b);
        ui.draw(g, this.font, discardKeepX(b), y, w, FOOTER_BTN_H, "§a继续编辑", mx, my, true);
        ui.draw(g, this.font, discardDropX(b), y, w, FOOTER_BTN_H, "§c放弃修改", mx, my, true);
    }

    /** 确认框开着时接管所有点击：继续编辑 / 放弃修改，其余位置吞掉不漏给下层。 */
    private boolean handleDiscardClick(double mx, double my) {
        int[] b = discardBounds();
        int y = discardBtnY(b), w = discardBtnW(b);
        if (ui.hit(mx, my, discardKeepX(b), y, w, FOOTER_BTN_H, true)) discardConfirmOpen = false;
        else if (ui.hit(mx, my, discardDropX(b), y, w, FOOTER_BTN_H, true)) discardAndClose();
        return true;
    }

    /** 打开描述展开编写大图层：与分类下拉窗互斥，同步当前描述文本进编辑区。 */
    private void openDescEditor() {
        catPickerOpen = false;
        if (descArea != null) {
            descArea.setVisible(true);
            descArea.setValue(description == null ? "" : description);
            setFocused(descArea);
        }
        descEditorOpen = true;
    }

    /** 关闭大图层；save=true 才把编辑区内容写回 description（并同步内联 descBox），点关闭/点面板外都是丢弃改动。 */
    private void closeDescEditor(boolean save) {
        if (save && descArea != null) {
            description = descArea.getValue();
            if (descBox != null) descBox.setValue(description);
        }
        if (descArea != null) descArea.setVisible(false);
        descEditorOpen = false;
        setFocused(null);
    }

    /** 描述大图层打开时的点击拦截：点 X/面板外=丢弃改动关闭，点确认保存=写回，点编辑区内部转发去定位光标，其余原地吞掉。 */
    private boolean handleDescEditorClick(double mx, double my) {
        int[] r = descEditorBounds();
        int closeX = r[0] + r[2] - EDITOR_CLOSE_W - 4, closeY = r[1] + 4;
        if (ui.hit(mx, my, closeX, closeY, EDITOR_CLOSE_W, EDITOR_CLOSE_H, true)) {
            closeDescEditor(false);
            return true;
        }
        int confirmH = 18;
        int confirmX = r[0] + 8, confirmY = r[1] + r[3] - confirmH - 6, confirmW = r[2] - 16;
        if (ui.hit(mx, my, confirmX, confirmY, confirmW, confirmH, true)) {
            closeDescEditor(true);
            return true;
        }
        if (descArea != null) descArea.mouseClicked(mx, my, 0);
        if (GuiRenderUtil.isHovering(mx, my, r[0], r[1], r[2], r[3])) {
            setFocused(descArea);
            return true;
        }
        closeDescEditor(false);
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

    /** 关闭数量小弹窗；save=true 才把输入框内容解析写回对应的 counts 列表，非法输入回退 1，点关闭/点弹窗外都是丢弃改动。
     *  纯数字但超 Long.MAX 的输入按 Long.MAX 封顶——回退 1 会把「天价成本」静默改成 1，方向完全反了。 */
    private void closeQtyEditor(boolean save) {
        if (save && qtyEditBox != null) {
            String raw = qtyEditBox.getValue().trim();
            long v;
            try { v = Long.parseLong(raw); } catch (Exception e) { v = raw.matches("\\d+") ? Long.MAX_VALUE : 1L; }
            v = Math.max(1L, v);
            java.util.List<Long> target = qtyEditIsCoin ? coinCounts : itemCounts;
            if (qtyEditIndex >= 0 && qtyEditIndex < target.size()) target.set(qtyEditIndex, v);
        }
        if (qtyEditBox != null) qtyEditBox.setFocused(false);
        qtyEditorOpen = false;
        setFocused(null);
    }

    /**
     * 数量小弹窗前景：标题 + 关闭 X + 输入框 + 确认按钮。qtyEditBox 是真实控件，但只靠 addRenderableWidget
     * 走 super.render() 在 Z=0 画一次是不够的——这整个前景层（含遮罩+面板背景）是在 renderScaledForeground
     * 里画的，被顶到 Z=400（比 Z=0 高，LEQUAL 深度测试直接赢），遮罩+面板背景会整个盖住 Z=0 画好的输入框，
     * 导致弹窗打开后输入框看不见也点不到。必须在这里（同一 Z=400 层）显式再画一次 qtyEditBox，同
     * descArea 在 renderDescEditorForeground 里的处理方式一致（见其注释）。
     */
    private void renderQtyEditorForeground(GuiGraphics g, int mx, int my) {
        int[] r = qtyEditorBounds();
        int ox = r[0], oy = r[1], ow = r[2], oh = r[3];
        g.fill(0, 0, vWidth, vHeight, 0xC0000000);
        g.fill(ox - 1, oy - 1, ox + ow + 1, oy + oh + 1, GOLD_DARK);
        g.fill(ox, oy, ox + ow, oy + oh, PANEL_BG);
        g.drawString(this.font, "§6精确数量 §8(支持超大整数)", ox + 8, oy + 6, GOLD, true);
        int closeX = ox + ow - EDITOR_CLOSE_W - 4, closeY = oy + 4;
        ui.draw(g, this.font, closeX, closeY, EDITOR_CLOSE_W, EDITOR_CLOSE_H, "§cX", mx, my, true);
        if (qtyEditBox != null) qtyEditBox.render(g, mx, my, 0f);
        int confirmH = 16, confirmX = ox + 8, confirmY = oy + oh - confirmH - 6, confirmW = ow - 16;
        ui.draw(g, this.font, confirmX, confirmY, confirmW, confirmH, "§a确认", mx, my, true);
    }

    @Override
    protected boolean universalMouseScrolled(double mx, double my, double d) {
        if (discardConfirmOpen) return true;
        if (descEditorOpen) {
            if (descArea != null && descArea.isHovering(mx, my)) descArea.scroll(d);
            return true;
        }
        if (catPickerOpen) {
            List<String> cats = com.dishanhai.gt_shanhai.common.shop.ShopConfig.getCategories();
            int maxFirst = ShopDialogRows.maxFirst(catRowHeights(cats, catCurrent(), catDropW(cats)), catDropAvailH());
            if (maxFirst > 0) {
                int dir = d > 0 ? -1 : 1;
                catPickerScroll = Math.max(0, Math.min(catPickerScroll + dir, maxFirst));
                return true;
            }
        }
        layoutRows();
        if (inViewport(mx, my)) {
            // 光标在放不下的槽位排上：横向翻槽位；其余地方：整页上下滚
            int row = slotRowAt(mx, my);
            if (row != 0) {
                int dir = d > 0 ? -1 : 1;
                setCostScrollForRow(row, clampCostScroll(row, costScrollForRow(row) + dir, slotRowCount(row)));
                return true;
            }
            setScroll(scroll() - (int) Math.signum(d) * 18);
            return true;
        }
        return super.universalMouseScrolled(mx, my, d);
    }

    /**
     * 画商品排：物品槽（{@link #goodsList}）+ 流体槽（{@link #goodsFluids}，紧接物品槽后，共享同一排/同一点位
     * 预算 max）+「+」。流体槽下方显示 mB 数量（同成本流体排风格），流体商品交付强制走 AE，见 ShopPurchase。
     */
    private void drawGoodsRow(GuiGraphics g, int y, int mx, int my) {
        int c = cx();
        g.drawString(this.font, "§7商品", c, y + 6, GRAY, true);
        int sx = slotsX();
        int itemN = goodsList.size();
        int total = itemN + goodsFluids.size();
        int visible = costRowVisibleSlots(GOODS_ROW);
        int start = visibleCostRowStart(GOODS_ROW, total);
        int end = Math.min(total + 1, start + visible);
        // 左界放宽 6px：首槽的居中数量标签比槽宽，贴 sx 裁会把 "1K+" 裁掉半个字
        enableScaledScissor(g, sx - 6, y - 1, sx + costRowViewportW(GOODS_ROW), y + SLOT + 10);
        for (int idx = start; idx < end; idx++) {
            int x = sx + (idx - start) * PITCH;
            if (idx < itemN) {
                EditorWidgets.itemSlot(g, this.font, x, y, goodsList.get(idx), hover(mx, my, x, y));
            } else if (idx < total) {
                FluidStack fs = goodsFluids.get(idx - itemN);
                EditorWidgets.fluidSlot(g, this.font, x, y, fs, hover(mx, my, x, y));
                // 缩写数量：完整 "131072mB" 宽过 PITCH，相邻槽标签会叠成乱码，还会压到下一行「每份数量」
                g.drawCenteredString(this.font, compactQty(fs.getAmount()), x + 10, y + 21, CYAN);
            } else {
                EditorWidgets.plusSlot(g, this.font, x, y, hover(mx, my, x, y));
            }
        }
        g.disableScissor();
        drawCostRowScrollbar(g, GOODS_ROW, y, total, mx, my);
    }

    /**
     * 画币种/物品成本排：图标 + 槽下数量文字，数量取自并行的 counts 列表（long，不是 ItemStack.getCount()）。
     * 槽位本身 count 恒为 1，vanilla 角标不会画（count==1 时 renderItemDecorations 不画数字），只有槽下这行文字是真数量。
     */
    private void drawCostIngredientRow(GuiGraphics g, String label, List<ItemStack> list, List<Long> counts, int y, int row, int mx, int my) {
        int c = cx();
        g.drawString(this.font, label, c, y + 6, GRAY, true);
        int sx = slotsX();
        int visible = costRowVisibleSlots(row);
        int start = visibleCostRowStart(row, list.size());
        int end = Math.min(list.size() + 1, start + visible);
        enableScaledScissor(g, sx - 6, y - 1, sx + costRowViewportW(row), y + SLOT + 10);
        for (int idx = start; idx < end; idx++) {
            int x = sx + (idx - start) * PITCH;
            if (idx < list.size()) {
                EditorWidgets.itemSlot(g, this.font, x, y, list.get(idx), hover(mx, my, x, y));
                long cnt = idx < counts.size() ? counts.get(idx) : 1L;
                g.drawCenteredString(this.font, compactQty(cnt), x + 10, y + 21, CYAN);
            } else {
                EditorWidgets.plusSlot(g, this.font, x, y, hover(mx, my, x, y));
            }
        }
        g.disableScissor();
        drawCostRowScrollbar(g, row, y, list.size(), mx, my);
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
    private boolean costIngredientRowClicked(List<ItemStack> list, List<Long> counts, int y, int row,
                                             double mx, double my, int btn, Runnable onOpenAdd, boolean isCoin) {
        if (costRowScrollbarClicked(row, y, list.size(), mx, my)) return true;
        if (!GuiRenderUtil.isHovering(mx, my, slotsX(), y - 1, costRowViewportW(row), SLOT + 10)) return false;
        int sx = slotsX();
        int visible = costRowVisibleSlots(row);
        int start = visibleCostRowStart(row, list.size());
        int end = Math.min(list.size() + 1, start + visible);
        for (int idx = start; idx < end; idx++) {
            int x = sx + (idx - start) * PITCH;
            if (idx < list.size() && GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT)) {
                if (btn == 1) {
                    list.remove(idx);
                    if (idx < counts.size()) counts.remove(idx);
                    setCostScrollForRow(row, clampCostScroll(row, costScrollForRow(row), list.size()));
                    rebuild();
                } else {
                    final int editIdx = idx;
                    capture();
                    EditorWidgets.openItemPicker(list.get(editIdx), st -> {
                        if (st == null || st.isEmpty()) {
                            list.remove(editIdx);
                            if (editIdx < counts.size()) counts.remove(editIdx);
                        } else {
                            ItemStack template = st.copy();
                            template.setCount(1); // 身份/NBT 换了没关系，数量仍是 counts 里存的那份，不吃选择器给的数量
                            list.set(editIdx, template);
                        }
                    });
                }
                return true;
            }
            // 槽下数量文字命中框：跟图标同宽，紧贴图标下方一行
            if (idx < list.size() && GuiRenderUtil.isHovering(mx, my, x, y + 21, SLOT, 9)) {
                capture();
                openQtyEditor(isCoin, idx);
                return true;
            }
            if (idx == list.size() && GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT)) {
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
        if (table != null) EditorWidgets.itemSlot(g, this.font, sx, y, firstFtbqItemIcon(table), hv);
        else EditorWidgets.plusSlot(g, this.font, sx, y, hv);
        drawSideHint(g, ftbqTableLines(), slotTextX(), y, rowH(Row.REWARD_POOL));
    }

    /** FTBQ 表那一格右边的标题（+项数）；未选/未同步时是提示。长标题换行，不截断。 */
    private List<FormattedCharSequence> ftbqTableLines() {
        dev.ftb.mods.ftbquests.quest.loot.RewardTable table = resolveClientFtbqTable();
        String text;
        if (table != null) {
            String title = table.getRawTitle().isEmpty()
                    ? ("#" + dev.ftb.mods.ftbquests.quest.QuestObjectBase.getCodeString(table)) : table.getRawTitle();
            text = "§f" + title + " §8(" + table.getWeightedRewards().size() + "项)";
        } else {
            text = ftbqTableId.isEmpty() ? "§8点击选择" : "§c表未同步/不存在 #" + ftbqTableId;
        }
        return wrap(text, slotTextW());
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

    /** 「前置任务」行：单槽显示所选任务标题（+所属章节），点击打开选择器，右键清空。样式仿 {@link #drawFtbqTableRow}。 */
    private void drawPrerequisiteQuestSlot(GuiGraphics g, int y, int mx, int my) {
        int c = cx();
        g.drawString(this.font, "§7前置任务", c, y + 6, GRAY, true);
        int sx = slotsX();
        boolean hv = hover(mx, my, sx, y);
        if (resolveClientPrerequisiteQuest() != null) EditorWidgets.checkerSlot(g, sx, y, hv);
        else EditorWidgets.plusSlot(g, this.font, sx, y, hv);
        drawSideHint(g, prereqLines(), slotTextX(), y, rowH(Row.PREREQ));
    }

    /** 前置任务那一格右边的任务标题（+所属章节）；未选/未同步时是提示。长标题换行，不截断。 */
    private List<FormattedCharSequence> prereqLines() {
        dev.ftb.mods.ftbquests.quest.Quest quest = resolveClientPrerequisiteQuest();
        String text;
        if (quest != null) {
            String title = quest.getRawTitle().isEmpty()
                    ? ("#" + dev.ftb.mods.ftbquests.quest.QuestObjectBase.getCodeString(quest)) : quest.getRawTitle();
            dev.ftb.mods.ftbquests.quest.Chapter chapter = quest.getChapter();
            text = "§f" + title + (chapter == null ? "" : " §8[" + chapter.getRawTitle() + "]");
        } else {
            text = prerequisiteQuestId.isEmpty() ? "§8留空=不要求前置" : "§c任务未同步/不存在 #" + prerequisiteQuestId;
        }
        return wrap(text, slotTextW());
    }

    /** 商品一次性提交物品排；留空表示不限制，右键删除，点击加号选择物品。 */
    private void drawSubmissionRow(GuiGraphics g, int y, int mx, int my) {
        drawCostIngredientRow(g, "§7提交物品", submissionItems, submissionCounts, y, 5, mx, my);
        // 说明放槽位排下面：放右边会越过横向滚动条贴到面板边缘被裁
        drawLines(g, wrap(submissionHint(), contentRight() - fieldX()), fieldX(), y + SLOT_ROW_H);
    }

    private boolean submissionRowClicked(int y, double mx, double my, int btn) {
        if (costRowScrollbarClicked(5, y, submissionItems.size(), mx, my)) return true;
        if (!GuiRenderUtil.isHovering(mx, my, slotsX(), y - 1, costRowViewportW(5), SLOT + 10)) return false;
        int visible = costRowVisibleSlots(5);
        int start = visibleCostRowStart(5, submissionItems.size());
        int end = Math.min(submissionItems.size() + 1, start + visible);
        int sx = slotsX();
        for (int idx = start; idx < end; idx++) {
            int x = sx + (idx - start) * PITCH;
            if (idx < submissionItems.size() && GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT)) {
                if (btn == 1) {
                    submissionItems.remove(idx);
                    if (idx < submissionCounts.size()) submissionCounts.remove(idx);
                    rebuild();
                } else {
                    final int pickIndex = idx;
                    capture();
                    EditorWidgets.openItemPicker(submissionItems.get(idx), stack -> {
                        if (stack == null || stack.isEmpty()) {
                            submissionItems.remove(pickIndex);
                            if (pickIndex < submissionCounts.size()) submissionCounts.remove(pickIndex);
                        } else {
                            ItemStack copy = stack.copy();
                            copy.setCount(1);
                            submissionItems.set(pickIndex, copy);
                            if (pickIndex >= submissionCounts.size()) submissionCounts.add(Math.max(1L, stack.getCount()));
                        }
                    });
                }
                return true;
            }
            if (idx == submissionItems.size() && GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT)) {
                openSubmissionPicker();
                return true;
            }
        }
        return false; // 槽位之间的空隙不算点中，不出声
    }

    private void openSubmissionPicker() {
        capture();
        Minecraft.getInstance().setScreen(new MultiPickerScreen(this, false,
                (stack, count) -> {
                    if (stack == null || stack.isEmpty()) return;
                    ItemStack copy = stack.copy();
                    copy.setCount(1);
                    submissionItems.add(copy);
                    submissionCounts.add(Math.max(1L, count));
                }, fluid -> {}));
    }

    /** 「前置任务」行的单槽点击：左键打开任务选择器，右键清空已选任务。 */
    private boolean prerequisiteQuestRowClicked(int y, double mx, double my, int btn) {
        int sx = slotsX();
        if (GuiRenderUtil.isHovering(mx, my, sx, y, SLOT, SLOT)) {
            if (btn == 1) { prerequisiteQuestId = ""; return true; }
            capture();
            openPrerequisiteQuestPicker();
            return true;
        }
        return false;
    }

    /** 打开 FTBQ 任务选择器：选中即回填 {@link #prerequisiteQuestId}。 */
    private void openPrerequisiteQuestPicker() {
        capture();
        Minecraft.getInstance().setScreen(new FtbqQuestSelectScreen(this, id -> prerequisiteQuestId = id));
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

    private void drawFluidRow(GuiGraphics g, int y, int row, int mx, int my) {
        int c = cx();
        g.drawString(this.font, "§7流体", c, y + 6, GRAY, true);
        int sx = slotsX();
        int visible = costRowVisibleSlots(row);
        int start = visibleCostRowStart(row, fluids.size());
        int end = Math.min(fluids.size() + 1, start + visible);
        enableScaledScissor(g, sx - 6, y - 1, sx + costRowViewportW(row), y + SLOT + 10);
        for (int idx = start; idx < end; idx++) {
            int x = sx + (idx - start) * PITCH;
            if (idx < fluids.size()) {
                EditorWidgets.fluidSlot(g, this.font, x, y, fluids.get(idx), hover(mx, my, x, y));
                g.drawCenteredString(this.font, compactQty(fluids.get(idx).getAmount()), x + 10, y + 21, CYAN);
            } else {
                EditorWidgets.plusSlot(g, this.font, x, y, hover(mx, my, x, y));
            }
        }
        g.disableScissor();
        drawCostRowScrollbar(g, row, y, fluids.size(), mx, my);
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
        if (discardConfirmOpen) return handleDiscardClick(mx, my);
        // 精确数量小弹窗打开时优先接管：点关闭/点弹窗外=丢弃改动关闭，点确认=写回关闭，
        // 点弹窗内其余位置（数量输入框本身）放行给正常控件点击，好定位光标（qtyEditBox 是真实控件，不用手动转发）
        if (qtyEditorOpen) {
            int[] r = qtyEditorBounds();
            if (!GuiRenderUtil.isHovering(mx, my, r[0], r[1], r[2], r[3])) { closeQtyEditor(false); return true; }
            int closeX = r[0] + r[2] - EDITOR_CLOSE_W - 4, closeY = r[1] + 4;
            if (ui.hit(mx, my, closeX, closeY, EDITOR_CLOSE_W, EDITOR_CLOSE_H, true)) { closeQtyEditor(false); return true; }
            int confirmH = 16, confirmX = r[0] + 8, confirmY = r[1] + r[3] - confirmH - 6, confirmW = r[2] - 16;
            if (ui.hit(mx, my, confirmX, confirmY, confirmW, confirmH, true)) { closeQtyEditor(true); return true; }
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
        layoutRows();
        if (ui.hit(mx, my, cancelX(), footerBtnY(), CANCEL_W, FOOTER_BTN_H, true)) {
            requestClose();
            return true;
        }
        if (ui.hit(mx, my, confirmX(), footerBtnY(), CONFIRM_W, FOOTER_BTN_H, catalogSnapshotValid())) {
            trySave();
            return true;
        }
        Tab hitTab = tabHit(mx, my);
        if (hitTab != null) {
            selectTab(hitTab);
            return true;
        }
        if (editorScrollbarClicked(mx, my)) return true;
        // 只有本页、且在可视区里的行能被点到（滚出去的行被裁掉了，看不见也不该点得到）
        if (inViewport(mx, my)) {
            for (Row r : ShopEntryEditLayout.rowsOf(tab)) {
                if (rowH(r) > 0 && clickRow(r, rowTop(r), mx, my, btn)) return true;
            }
        }
        return super.universalMouseClicked(mx, my, btn);
    }

    /**
     * 键盘：Esc 一次只收一层（放弃确认框 → 数量框 → 描述大框 → 分类下拉 → 输入框焦点 → 回商店）；
     * Ctrl+Enter 保存，Ctrl+1~5 切页，Ctrl+Tab / Ctrl+Shift+Tab 轮换页签。
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean enter = keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
        if (discardConfirmOpen) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                discardConfirmOpen = false;
            } else if (enter && ui.takeEnter()) {
                int[] b = discardBounds();
                ui.press(discardDropX(b), discardBtnY(b), discardBtnW(b), FOOTER_BTN_H, true);
                discardAndClose();
            }
            return true;
        }
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
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) {
                if (descArea != null) setFocused(descArea);
                return true;
            }
            // Ctrl+回车快速保存关闭；普通回车交给 descArea 当换行处理（真多行编辑区，回车不再等于确认）
            if ((keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)
                    && net.minecraft.client.gui.screens.Screen.hasControlDown()) {
                closeDescEditor(true);
                return true;
            }
            // 交给已聚焦的 descArea。修饰键必须返回 false，输入法才能切中英文。
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            if (catPickerOpen) {
                catPickerOpen = false;
                return true;
            }
            // 正在输入框里打字时，Esc 先退出输入框，再按一次才回商店
            if (getFocused() instanceof EditBox) {
                setFocused(null);
                return true;
            }
            requestClose();
            return true;
        }
        if (net.minecraft.client.gui.screens.Screen.hasControlDown()) {
            if (enter) {
                if (ui.takeEnter()) {
                    ui.press(confirmX(), footerBtnY(), CONFIRM_W, FOOTER_BTN_H, catalogSnapshotValid());
                    trySave();
                }
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) {
                selectTab(ShopEntryEditLayout.cycle(tab, net.minecraft.client.gui.screens.Screen.hasShiftDown() ? -1 : 1));
                return true;
            }
            Tab hotkey = ShopEntryEditLayout.tabForHotkey(keyCode - org.lwjgl.glfw.GLFW.GLFW_KEY_0);
            if (hotkey != null) {
                selectTab(hotkey);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        ui.enterReleased(keyCode);
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    protected boolean universalMouseDragged(double mx, double my, int btn, double dx, double dy) {
        int dragged = ui.drag(my, viewTop(), viewH(), contentH, viewH(), editorMaxScroll());
        if (dragged >= 0) {
            setScroll(dragged);
            return true;
        }
        if (descEditorOpen) {
            if (descArea != null) descArea.dragSelect(mx, my);
            return true;
        }
        if (draggingCostRow != 0) {
            int y, count;
            if (draggingCostRow == GOODS_ROW) { y = goodsY(); count = goodsList.size() + goodsFluids.size(); }
            else if (draggingCostRow == 1) { y = coinY(); count = coins.size(); }
            else if (draggingCostRow == 2) { y = itemY(); count = items.size(); }
            else if (draggingCostRow == 3) { y = fluidY(); count = fluids.size(); }
            else { y = submissionY(); count = submissionItems.size(); }
            updateCostRowScrollFromDrag(draggingCostRow, y, count, my);
            return true;
        }
        return super.universalMouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    protected boolean universalMouseReleased(double mx, double my, int btn) {
        ui.release();
        if (draggingCostRow != 0) {
            draggingCostRow = 0;
            return true;
        }
        return super.universalMouseReleased(mx, my, btn);
    }

    /**
     * 商品排点击：物品槽/流体槽左键选/重开选择器改身份（流体额外可改 mB），右键删除（保底留 1 项，
     * 物品+流体总数收缩到 1 时最后一项不响应右键，防清空商品），点「+」开多选选择器加新项（物品/流体均可）。
     */
    private boolean goodsRowClicked(double mx, double my, int btn) {
        int y = goodsY();
        int itemN = goodsList.size();
        int total = itemN + goodsFluids.size();
        if (costRowScrollbarClicked(GOODS_ROW, y, total, mx, my)) return true;
        int sx = slotsX();
        // 命中判定必须与 drawGoodsRow 用同一套 start/visible 窗口，否则滚动后画的槽位和点的槽位对不上
        int visible = costRowVisibleSlots(GOODS_ROW);
        int start = visibleCostRowStart(GOODS_ROW, total);
        int end = Math.min(total + 1, start + visible);
        for (int idx = start; idx < end; idx++) {
            int x = sx + (idx - start) * PITCH;
            if (!GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT)) continue;
            if (idx < itemN) {
                if (btn == 1) {
                    if (total > 1) { goodsList.remove(idx); rebuild(); }
                } else {
                    final int i = idx;
                    capture();
                    EditorWidgets.openItemPicker(goodsList.get(i),
                            st -> { if (st != null && !st.isEmpty()) goodsList.set(i, st); });
                }
            } else if (idx < total) {
                final int jdx = idx - itemN;
                if (btn == 1) {
                    if (total > 1) { goodsFluids.remove(jdx); rebuild(); }
                } else {
                    capture();
                    EditorWidgets.openFluidPicker(goodsFluids.get(jdx),
                            fs -> { if (fs == null || fs.isEmpty()) goodsFluids.remove(jdx); else goodsFluids.set(jdx, fs); });
                }
            } else {
                openGoodsPicker();
            }
            return true;
        }
        return false;
    }

    /** 打开商品多选选择器：物品加入 goodsList 排、流体加入 goodsFluids 排（同一次会话可混选，见 MultiPickerScreen）。 */
    private void openGoodsPicker() {
        capture();
        // 商品持久层 GoodsStack.count 是 int，这里只用 stack 自带的 int 夹值，精确 long 参数不消费
        Minecraft.getInstance().setScreen(new MultiPickerScreen(this, false,
                (st, cnt) -> { if (st != null && !st.isEmpty()) goodsList.add(st); },
                fs -> { if (fs != null && !fs.isEmpty()) goodsFluids.add(fs); }));
    }

    /** 打开自建多选选择器：物品加入 items 排、流体加入 fluids 排（一次会话可混选）。 */
    private void openMultiPicker(boolean browseFluid) {
        capture();
        Minecraft.getInstance().setScreen(new MultiPickerScreen(this, browseFluid,
                (st, cnt) -> {
                    if (st == null || st.isEmpty()) return;
                    ItemStack template = st.copy();
                    template.setCount(1); // 真实数量走 itemCounts，stack 本身钉 1（避免 vanilla 角标跟真实值对不上）
                    items.add(template);
                    // 用回调的精确 long，不再从 st.getCount() 反推——stack 只能装 int，超上限会被夹成 2147483647
                    itemCounts.add(Math.max(1L, cnt));
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
                (st, cnt) -> { if (st != null && !st.isEmpty() && rewardPool.size() < MAX_REWARD_OPTIONS) rewardPool.add(ShopEntry.RewardOption.simple(st)); },
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
                (st, cnt) -> {
                    if (st == null || st.isEmpty()) return;
                    ItemStack template = st.copy();
                    template.setCount(1);
                    coins.add(template);
                    // 同 openMultiPicker：精确 long 走回调参数，避开 ItemStack 的 int 上限
                    coinCounts.add(Math.max(1L, cnt));
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
                (st, cnt) -> {
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

    private boolean fluidRowClicked(int y, int row, double mx, double my, int btn) {
        if (costRowScrollbarClicked(row, y, fluids.size(), mx, my)) return true;
        if (!GuiRenderUtil.isHovering(mx, my, slotsX(), y - 1, costRowViewportW(row), SLOT + 10)) return false;
        int sx = slotsX();
        int visible = costRowVisibleSlots(row);
        int start = visibleCostRowStart(row, fluids.size());
        int end = Math.min(fluids.size() + 1, start + visible);
        for (int idx = start; idx < end; idx++) {
            int x = sx + (idx - start) * PITCH;
            if (idx < fluids.size() && GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT)) {
                if (btn == 1) {
                    fluids.remove(idx);
                    setCostScrollForRow(row, clampCostScroll(row, costScrollForRow(row), fluids.size()));
                    rebuild();
                }
                else { final int jdx = idx; capture(); EditorWidgets.openFluidPicker(fluids.get(jdx),
                        fs -> { if (fs == null || fs.isEmpty()) fluids.remove(jdx); else fluids.set(jdx, fs); }); }
                return true;
            }
            if (idx == fluids.size() && GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT)) {
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
        return new ShopCost(spark, coinMap, physical, eu);
    }

    /** 打开免费会清空五通道，避免保存时还带着旧花费。关掉后要重新填成本。 */
    private void setFreeGoods(boolean free) {
        freeGoods = free;
        if (!free) return;
        spark = BigInteger.ZERO;
        eu = BigInteger.ZERO;
        coins.clear();
        coinCounts.clear();
        items.clear();
        itemCounts.clear();
        fluids.clear();
        if (sparkBox != null) sparkBox.setValue("");
        if (euBox != null) euBox.setValue("");
    }

    /** 保存按钮与 Ctrl+Enter 共用：目录已更新时只提示，不发包。 */
    private void trySave() {
        if (!catalogSnapshotValid()) {
            showCatalogSnapshotExpired();
            return;
        }
        submit();
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
        boolean goodsLocked = goodsLockedByReward();
        List<ItemStack> sourceStacks = goodsLocked ? java.util.List.of(lockedGoodsDisplay()) : goodsList;
        List<ShopEntry.GoodsStack> goodsForSubmit = new ArrayList<>();
        for (int i = 0; i < sourceStacks.size(); i++) {
            ItemStack st = sourceStacks.get(i);
            if (st == null || st.isEmpty()) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(st.getItem());
            if (id == null) continue;
            int cnt = (i == 0) ? Math.max(1, count) : Math.max(1, st.getCount());
            goodsForSubmit.add(ShopEntry.GoodsStack.of(id, cnt, st.getTag()));
        }
        if (!goodsLocked) { // 奖励池接管时商品身份纯占位，不含流体（流体商品只在普通固定商品模式生效）
            for (FluidStack fs : goodsFluids) {
                if (fs == null || fs.isEmpty()) continue;
                ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fs.getFluid());
                if (id == null) continue;
                long amt = Math.max(1L, fs.getAmount());
                goodsForSubmit.add(ShopEntry.GoodsStack.ofFluid(id, amt > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) amt));
            }
        }
        if (goodsForSubmit.isEmpty()) {
            var p = Minecraft.getInstance().player;
            if (p != null) p.displayClientMessage(Component.literal("§c[商店] 商品身份解析失败（FTBQ表里没有物品类奖励？），未保存"), false);
            return;
        }
        ShopCost cost = freeGoods ? new ShopCost(BigInteger.ZERO, null, null, BigInteger.ZERO) : buildCost();
        if (!freeGoods && cost.isEmpty()) {
            var p = Minecraft.getInstance().player;
            if (p != null) p.displayClientMessage(Component.literal("§c[商店] 成本不能为空。要免费请点「设为免费」，未保存"), false);
            return;
        }
        if (!cost.isValid()) {
            var p = Minecraft.getInstance().player;
            if (p != null) p.displayClientMessage(Component.literal("§c[商店] 成本里有不存在的物品或流体，未保存"), false);
            return;
        }
        String cat = (category == null || category.isBlank()) ? ShopEntry.DEFAULT_CATEGORY : category.trim();
        String desc = description == null ? "" : description.trim();
        // 周期限购的周期在 UI 层用现实秒，发包前换算成 tick；两框须都非空才生效（半填在 ShopEntry 构造器里会被归一成不启用）
        long periodTicksToSend = periodSeconds > 0L ? periodSeconds * TICKS_PER_SECOND : -1L;
        // 折扣「持续分钟数」在 UI 层是相对时长，发包前按「从现在开始」换算成绝对起止时间戳（ShopEntry 构造器按
        // discountPercent<=0 或窗口非法一律归一成不启用，这里未启用时随便填 -1/-1 也安全）
        long discountStartMs = discountPercent > 0 && discountMinutes > 0L ? System.currentTimeMillis() : -1L;
        long discountEndMs = discountStartMs > 0L ? discountStartMs + discountMinutes * 60_000L : -1L;
        List<ExchangeEntry.Ingredient> submissionsForSubmit = new ArrayList<>();
        for (int i = 0; i < submissionItems.size(); i++) {
            ItemStack stack = submissionItems.get(i);
            if (stack == null || stack.isEmpty()) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id == null) continue;
            long amount = i < submissionCounts.size() ? Math.max(1L, submissionCounts.get(i)) : Math.max(1, stack.getCount());
            submissionsForSubmit.add(new ExchangeEntry.Ingredient(id, false, amount, stack.getTag()));
        }
        ShopEditPacket pkt = new ShopEditPacket(
                isNew ? ShopEditPacket.Action.ADD : ShopEditPacket.Action.EDIT,
                goodsForSubmit, cat, desc, cost, oldGoods, oldCategory == null ? "" : oldCategory, -1, limit,
                displayIcons, rewardMode, rewardPool, hidden, linkKey, linkTo, displayName, ftbqTableId, ftbqSubMode, tradeMode,
                periodTicksToSend, periodCap, prerequisiteQuestId, catalogRevision, oldEntryKey,
                discountPercent, discountStartMs, discountEndMs, submissionsForSubmit);
        if (isNew ? saveUses != limit : saveUses != initialSaveUses) pkt.withSaveUses(saveUses);
        ShanhaiNetwork.CHANNEL.sendToServer(pkt);
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    protected void renderTooltips(GuiGraphics g, int smx, int smy, int mx, int my) {
        if (hoverRewardOption != null) {
            g.renderComponentTooltip(this.font, rewardTooltip(hoverRewardOption), mx, my);
        }
    }

    @Override
    public void removed() {
        ui.cancel();
        super.removed();
    }
}
