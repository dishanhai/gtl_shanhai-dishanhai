package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.AdvancedSearchUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.client.shop.ClientCostPreview;
import com.dishanhai.gt_shanhai.client.shop.ClientShopCart;
import com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog;
import com.dishanhai.gt_shanhai.client.shop.ClientShopFavorites;
import com.dishanhai.gt_shanhai.client.shop.ClientWalletAccount;
import com.dishanhai.gt_shanhai.common.shop.ExchangeEntry;
import com.dishanhai.gt_shanhai.common.shop.ShopConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopCost;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;
import com.dishanhai.gt_shanhai.common.shop.ShopGridViewport;
import com.dishanhai.gt_shanhai.common.shop.ShopPurchase;
import com.dishanhai.gt_shanhai.common.shop.WalletAccountAPI;
import com.dishanhai.gt_shanhai.common.item.WalletItem;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopActionPacket;
import com.dishanhai.gt_shanhai.network.ShopCostPreviewRequestPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 山海商店界面（ScaledScreen 版，仿 kineticaddons「钱包商店」观感）。
 *
 * <p>布局引擎完全对齐 kineticaddons {@code CurrencyWalletShopScreen}：逻辑画布
 * {@link #TARGET_W}×{@link #TARGET_H}，但面板本身<b>动态铺满窗口</b>
 * （{@code panelWidth = max(700, vWidth-8)}），所有坐标相对 {@link #left}/{@link #top}
 * 计算，商品格随宽度横向排满（最多 10 列）。不再用固定小画布 + 居中黑边，
 * 以此消除顶栏空白、网格靠左、标题偏位等对齐问题。</p>
 *
 * <p>顶栏：买/卖页签（左）+ 居中标题 + 充值全部/关闭（右）。搜索框仿 KE 放在
 * 右侧详情列正上方。左侧深色网格商品；右侧详情面板（品名/单价/数量滑块/预计/确认）。</p>
 *
 * <p>纯客户端界面：余额读手持钱包 NBT（客户端有副本），一切交易发
 * {@link ShopActionPacket} 给服务端结算，服务端改动通过 Forge 自动同步回手持物品。</p>
 */
public class ShopScreen extends ScaledScreen {

    // 逻辑画布（仿 KE 900×430；面板实际按窗口动态铺满，此值仅决定缩放基准）
    private static final int TARGET_W = 900;
    private static final int TARGET_H = 430;

    // ===== 布局常量（对齐 KE）=====
    private static final int TOP_BAR_H = 18;       // 顶栏按钮高
    private static final int DETAIL_W = 158;       // 右侧详情列宽（KE = 158）
    private static final int CELL_W = 70;          // 商品格宽（KE）
    private static final int CELL_H = 36;           // 商品格高（KE）
    private static final int GRID_GAP = 1;          // 格间距（KE = 1）
    private static final int GRID_MAX_COLS = 10;    // 最大列数（KE）
    private static final int SEARCH_W = 150;        // 搜索框宽（KE）
    private static final int TAB_H = 14;            // 分类页签高

    private static final int BUY_TAB_W = 58;
    private static final int SELL_TAB_W = 58;
    private static final int RECHARGE_W = 64;
    private static final int CLOSE_W = 60;
    private static final int FAV_BTN_W = 52;

    // 配色（取自 kineticaddons CurrencyWalletShopScreen）
    private static final int GOLD = -22016;
    private static final int GOLD_DARK = -7710208;
    private static final int PANEL_BG = -267382768;
    private static final int PANEL_INNER = -266724838;
    private static final int BOX_BG = -300674028;
    private static final int ROW_BG = -300476649;
    private static final int ROW_HOVER = -299555547;
    private static final int SELECT_BG = -299421158;
    private static final int GREEN = -11141291;
    private static final int GREEN_DARK = -13661393;
    private static final int RED = -43691;
    private static final int RED_DARK = -7655633;
    private static final int DEEP_RED = -8775913;
    private static final int CYAN = -11141121;
    private static final int GRAY = -5592406;
    private static final int WHITE = -1;
    private static final int NUMBER_BAR_BORDER = -15967888;
    private static final int NUMBER_BAR_BG = -14935012;
    private static final int NUMBER_BAR_TEXT = -1;
    private static final int BUTTON_BG = -14935012;
    private static final int BUTTON_HOVER = -12303292;

    private enum Mode { BUY, SELL }

    // 购买/出售 tab + 主/子分类页签：静态=跨界面实例保留，关闭商店再打开继承上次浏览的页/子页，不再每次回到默认页
    private static Mode mode = Mode.BUY;
    private static String selectedTop;
    private static String selectedSub = "";
    private ShopEntry selected;
    private long selectedEntryKey = -1L;
    private long observedCatalogRevision = -1L;
    // 切页/切卡片切换动画：记下"数据集刚换过"的时刻，渲染时按经过时间算一个插值，
    // 纯几何变换（PoseStack translate/scale，不碰颜色/透明度），对物品图标渲染同样生效，不用担心改样式。
    private long gridSwitchAtMs;   // 主/子分类切页（网格数据集变了）触发；0=从未触发，视为动画已完成
    private long cardSwitchAtMs;   // 选中商品切换（详情面板内容变了）触发；0=从未触发，视为动画已完成
    private int gridSlideDir = 1;  // 切页左右滑入方向：+1=新页在右侧滑入（切到的页签在原页签右边），-1=左侧滑入
    private static final long TAB_SWITCH_ANIM_MS = 150L;
    private static final long CARD_SWITCH_ANIM_MS = 150L;
    private static final float TAB_SLIDE_DIST = 32f; // 切页横切滑入距离（约半个商品格宽，彰显"切"的方向感）
    private long amount = 1L;      // 购买/出售次数，支持 Long.MAX
    private EditBox searchBox;
    private AnimatableEditBox amountBox; // AE 风格数量输入框，可被切卡片动画进度控制
    // AE 模式：交付优先注入绑定的 AE 网络。静态=跨界面实例保留（重开钱包不重置），仅本机会话级
    private static boolean aeMode = false;
    // 精妙背包模式：扣款/交付优先走随身穿戴的精妙背包（而不是随身背包），语义/生命周期对齐 aeMode
    private static boolean backpackMode = false;
    // 只看收藏筛选：开启后网格只显示已收藏商品（跟主/子分类、搜索一起收窄 visibleEntryKeys）。
    // 静态=跨界面实例保留，语义/生命周期对齐 aeMode/backpackMode。
    private static boolean favoritesOnly = false;
    /**
     * 实时消息横幅队列：商店交互反馈镜像（来源见 ShopChatMirror）。静态=界面实例无关，仅本机会话级。
     * 原先是单槽位，连续快速操作（比如连续两次购买）新消息会瞬间顶掉上一条，容易漏看（见反馈）。
     * 改成最多同时显示 {@link #FLASH_MAX_VISIBLE} 条的队列：新消息从底部滑入，槽位不够时挤开最旧的一条
     * （不是瞬间消失，也走同一套滑出动画再摘除，见 tickFlash）；自然到期同样先滑出再摘除。
     */
    private static final class FlashMsg {
        String text;
        long expireAtMs;          // 队列里正常展示的到期时刻（leaving 阶段不再看这个，看 leaveDeadlineMs）
        long leaveDeadlineMs;     // 滑出动画播完、可以彻底摘除的时刻，仅 leaving 阶段有意义
        int fromSlot, toSlot;     // 动画起止槽位：0=最新（贴底），数值越大越靠上；-1=从底部滑入前的起点
        long slotChangedAtMs;     // 上次目标槽位变化的时刻，供滑动插值用
    }

    private static final java.util.List<FlashMsg> flashActive = new java.util.ArrayList<>();  // 当前占着槽位的（≤FLASH_MAX_VISIBLE 条）
    private static final java.util.List<FlashMsg> flashLeaving = new java.util.ArrayList<>();  // 正在滑出动画中的，播完即摘除
    private static final int FLASH_MAX_VISIBLE = 3;
    private static final long FLASH_DISPLAY_MS = 5000L;
    private static final long FLASH_SLIDE_MS = 180L;   // 滑动过渡时长
    private static final int FLASH_ROW_H = 17;          // 每行高度（含 1px 行间隙）

    /** 由 {@link ShopChatMirror} 调用：推入一条商店消息，最多同时显示 {@link #FLASH_MAX_VISIBLE} 条。 */
    public static void showMessage(net.minecraft.network.chat.Component msg) {
        if (msg == null) return;
        long now = System.currentTimeMillis();
        tickFlash(now); // 先处理一遍自然到期，避免同一帧"到期"和"被挤"重复触发滑出
        if (flashActive.size() >= FLASH_MAX_VISIBLE) {
            beginLeave(flashActive.remove(flashActive.size() - 1), now); // 挤开槽位最靠上（最旧）的一条
        }
        FlashMsg m = new FlashMsg();
        m.text = msg.getString();
        m.expireAtMs = now + FLASH_DISPLAY_MS;
        m.fromSlot = -1; // 从底部（比槽位0更低一层）滑入
        m.toSlot = 0;
        m.slotChangedAtMs = now;
        flashActive.add(0, m);
        resyncFlashSlots(now);
    }

    /** 消息离开 active 队列（自然到期或被挤）：改往上滑出可见区域，进 leaving 表播完动画再摘除。 */
    private static void beginLeave(FlashMsg m, long now) {
        m.fromSlot = m.toSlot;
        m.toSlot = FLASH_MAX_VISIBLE;
        m.slotChangedAtMs = now;
        m.leaveDeadlineMs = now + FLASH_SLIDE_MS;
        flashLeaving.add(m);
    }

    /** active 队列结构变化后，把每条消息的目标槽位（=当前下标）同步到最新结构；槽位没变的不打断其动画。 */
    private static void resyncFlashSlots(long now) {
        for (int i = 0; i < flashActive.size(); i++) {
            FlashMsg m = flashActive.get(i);
            if (m.toSlot != i) {
                m.fromSlot = m.toSlot;
                m.toSlot = i;
                m.slotChangedAtMs = now;
            }
        }
    }

    /** 每帧调用：把自然到期的消息从 active 挪进 leaving（触发滑出动画），并摘除已经播完滑出动画的。 */
    private static void tickFlash(long now) {
        boolean changed = false;
        java.util.Iterator<FlashMsg> it = flashActive.iterator();
        while (it.hasNext()) {
            FlashMsg m = it.next();
            if (now > m.expireAtMs) {
                it.remove();
                beginLeave(m, now);
                changed = true;
            }
        }
        if (changed) resyncFlashSlots(now);
        flashLeaving.removeIf(m -> now > m.leaveDeadlineMs);
    }

    /** 按动画起止槽位做 ease-out 插值，算出这条消息当前该画在哪一行（世界坐标 Y）。 */
    private static int flashRowY(FlashMsg m, long now, int baseY) {
        float t = FLASH_SLIDE_MS <= 0 ? 1f : Math.min(1f, (now - m.slotChangedAtMs) / (float) FLASH_SLIDE_MS);
        float eased = 1f - (1f - t) * (1f - t);
        float slot = m.fromSlot + (m.toSlot - m.fromSlot) * eased;
        return (int) (baseY - slot * FLASH_ROW_H);
    }
    // 删除误触防护：需在窗口期内对同一条目再点一次「删除此商品」才真正执行；实例级=只跟当前这次打开的界面走
    private ShopEntry pendingDeleteEntry;
    private long pendingDeleteArmedAtMs;
    private static final long DELETE_CONFIRM_WINDOW_MS = 3000L;
    // 撤销上一次删除：静态=跨界面实例保留（删完手滑关掉界面也还能点），服务端另有独立 30 秒兜底窗口（见 ShopConfig#undoLastRemove）
    private static String undoDeleteLabel;
    private static long undoDeleteUntilMs;
    private static final long UNDO_DELETE_UI_WINDOW_MS = 8000L;
    // 撤销上一次排序（前移/后移/置顶）：同上一套静态悬浮按钮写法，服务端另有独立 30 秒兜底窗口（见 ShopConfig#undoLastMove）。
    // 置顶没有对称的反向操作，不靠这个的话点错了只能一路点「后移」手动挪回去，见反馈。
    private static String undoReorderLabel;
    private static long undoReorderUntilMs;
    private static final long UNDO_REORDER_UI_WINDOW_MS = 8000L;
    private int scroll = 0; // 网格滚动像素
    private boolean draggingGridScroll; // 正在拖拽网格右侧滚动条
    private final boolean canEdit; // 服务端下发的编辑权（OP 或白名单）：决定编辑条目/商店设置/排序等按钮显隐
    public boolean canEdit() { return canEdit; } // 供子页（如 CurrencyAtmScreen）跳转兑换中心时透传编辑权
    // 服务端下发：canEdit 基础上是否还开了 /山海 商店 编辑 编辑模式，决定新增/删除商品这类目录增删按钮显隐
    private final boolean catalogEditUnlocked;

    private ShopEntry visibleEntry(int index) {
        if (index < 0 || index >= visibleEntryKeys.size()) return null;
        return ClientShopCatalog.get(visibleEntryKeys.get(index));
    }

    private void selectEntry(long entryKey, ShopEntry entry) {
        long newKey = entry == null ? -1L : entryKey;
        if (newKey != selectedEntryKey) cardSwitchAtMs = System.currentTimeMillis(); // 真换了商品才弹一下，重复点同一张卡不重播
        selectedEntryKey = newKey;
        selected = entry;
    }

    private void clearSelection() {
        selectedEntryKey = -1L;
        selected = null;
    }
    private String previewHoverName; // 详情页花费预览槽悬停名（drawDetail 暂存 → renderTooltips 消费）
    private String previewHoverExtra; // 悬停槽的"拥有/缺少"提示行（drawPreviewSlot 暂存 → renderTooltips 消费），无数据（未同步/加载中）为 null
    private long previewRequestedKey = -1L;      // 花费预览最近一次发起请求时对应的商品；变了要重新请求
    private boolean previewRequestedAeMode = false; // 花费预览最近一次发起请求时的 AE 模式状态；切换要重新请求
    private long previewRequestedAtGameTime = Long.MIN_VALUE; // 最近一次发起请求的游戏刻，供周期性兜底刷新节流
    private static final long PREVIEW_REFRESH_TICKS = 40L; // 花费预览周期兜底刷新间隔（2秒）：存量会随玩家操作背包/AE网络实时变化
    private ItemStack detailHoverStack; // 详情页商品大图标悬停时暂存的真实 ItemStack（含 SDA 等实时解析 tooltip，drawDetail 暂存 → renderTooltips 消费）
    private boolean descOverlayOpen; // 描述详情大图层（FTBQ 风格）开关
    private int descOverlayScroll;   // 描述详情大图层滚动行数（超出面板高度时用）
    private boolean draggingDescOverlayScroll; // 正在拖拽描述详情大图层右侧滚动条
    // 「展开详情」按钮命中框：cursorY 依赖 drawCostPreview 的实际绘制结果（数据相关），故按 previewHoverName 同款约定，
    // drawDetail 渲染时暂存坐标，universalMouseClicked 消费，避免在点击处理里重新跑一遍绘制逻辑求同一个 y。
    private boolean descExpandVisible;
    private int descExpandX, descExpandY, descExpandW, descExpandH;
    // 「跳转」入口命中框（仿 FTBQ 隐藏任务跳转）：同上，drawDetail 渲染时暂存坐标，universalMouseClicked 消费
    private boolean linkVisible;
    private ShopEntry linkTarget;
    private long linkTargetKey = -1L;
    private int linkX, linkY, linkW, linkH;
    // GuideME 指南集成：商品清单里有物品登记了指南页就自动出现跳转入口，不需要编辑者手动配置。
    // guideHitsFor 缓存"上次是给哪个条目查的"，selected 没变就不用每帧重新扫描所有已装指南（见反馈：避免无谓开销）。
    private ShopEntry guideHitsFor;
    private java.util.List<com.dishanhai.gt_shanhai.client.shop.ShopGuideLookup.GuideHit> guideHits = java.util.List.of();
    private boolean guideLinkVisible; // 单条命中：直接给一行跳转，样式同上面的「跳转」
    private com.dishanhai.gt_shanhai.client.shop.ShopGuideLookup.GuideHit guideLinkHit;
    private int guideLinkX, guideLinkY, guideLinkW, guideLinkH;
    private boolean guideDetailBtnVisible; // 2条以上命中：给「指南详情」按钮，点开大图层列出所有命中（仿描述详情大图层）
    private int guideDetailBtnX, guideDetailBtnY, guideDetailBtnW, guideDetailBtnH;
    private boolean guideOverlayOpen;
    private int guideOverlayScroll;
    // 「补齐全部缺口」按钮：花费预览格有确定不够的项、且开着 AE 模式才出现，跟链接/指南同一套暂存坐标+点击消费的路数
    private boolean autoCraftBtnVisible;
    private int autoCraftBtnX, autoCraftBtnY, autoCraftBtnW, autoCraftBtnH;
    // 「前置任务」跳转行：条目配了 prerequisiteQuestId 才出现，点击跳到 FTBQ 任务书对应任务，样式同「跳转」
    private boolean prereqLinkVisible;
    private dev.ftb.mods.ftbquests.quest.Quest prereqQuest;
    private boolean prereqQuestCompleted;
    private int prereqLinkX, prereqLinkY, prereqLinkW, prereqLinkH;
    // 网格卡片右键快捷菜单：导航跳转合集 + 管理效率（编辑权），条目变了/权限变了菜单项动态重算，不用固定坐标+布尔位那一套
    private boolean ctxMenuOpen;
    private long ctxMenuEntryKey = -1L;
    private int ctxMenuX, ctxMenuY, ctxMenuW;
    private List<ContextMenuItem> ctxMenuItems = List.of();
    private static final int CTX_MENU_ROW_H = 12;
    /** 一条右键菜单项：命中即执行 action 并收起菜单；separatorBefore=true 时上方先画一道分隔线（分组用）。 */
    private record ContextMenuItem(String label, boolean separatorBefore, Runnable action) {}

    // 购物车：顶栏微缩按键展开的独立大图层；Ctrl+左键网格卡片加入候选，按 stableId 引用条目（跨快照/跨重登有效，
    // 见 ClientShopCart），数量可调、可单独删除，结算=按候选清单逐项各自发起购买（异步、互不阻塞）。
    private boolean cartOverlayOpen;
    private long cartOverlayOpenAtMs; // 打开瞬间的时间戳，驱动展开弹入动画（见 renderScaledForeground）
    private int cartOverlayScroll;
    private final Map<Long, Long> cartPreviewRequestedAtGameTime = new java.util.HashMap<>();
    private boolean cartPreviewRequestedAeMode;
    private static final long OVERLAY_POP_ANIM_MS = 150L;
    // 结算批次：点「结算购物车」时把当次涉及的 stableId 快照进来，底部按钮区在批次未全部回执前
    // 变成进度条（X/总数），全部回执后改回按钮，但已回执的行状态（成功戳记覆盖控件/失败原因）
    // 一直留到关闭购物车面板才清（见反馈：进度跑完要能直接在面板里看到哪些成功哪些失败）。
    private final List<String> cartSettleBatch = new ArrayList<>();
    private long cartSettleBatchStartedAtMs;

    // ===== 动态面板尺寸（每次 initScaled 重算）=====
    private int left, top, panelWidth, panelHeight;

    // 计算态
    private List<String> categories = new ArrayList<>();     // 主分类页签
    private List<String> subCategories = new ArrayList<>();  // 当前主分类下的子分类
    private List<Long> visibleEntryKeys = new ArrayList<>();

    public ShopScreen() {
        this(false, false);
    }

    public ShopScreen(boolean canEdit, boolean catalogEditUnlocked) {
        super(Component.literal("山海商店"));
        this.canEdit = canEdit;
        this.catalogEditUnlocked = catalogEditUnlocked;
        this.targetWidth = TARGET_W;
        this.targetHeight = TARGET_H;
        this.useOffset = false; // KE 风格：不居中黑边，面板动态铺满
        this.scaleMultiplier = 1.0f;
        // minScale 必须 <1（KE 默认 0.1）。若卡成 1.0，高 GUI Scale/小窗口下逻辑宽 width<900 时
        // guiScale 无法缩小 → vWidth=width<900 却 panelWidth≥700，面板右缘超出 vWidth 造成溢出。
        this.minScale = 0.1f;
        // maxScale 不封顶（KE 默认 Float.MAX_VALUE）。封顶会让高分宽屏下 guiScale 被压小、
        // vWidth 虚高、panelWidth 撑出屏幕右侧。两个边界都须放开，才满足 vWidth×guiScale≡width。
        this.maxScale = Float.MAX_VALUE;
    }

    // ============ 动态布局坐标（全部相对 left/top、panelWidth/panelHeight）============

    private int titleX() { return left + panelWidth / 2; }

    private int balanceY() { return top + 30; }
    private int balanceHeight() { return 18; }

    /** 主分类页签行 Y。 */
    private int tabsY() { return balanceY() + balanceHeight() + 4; }

    /** 子分类页签行 Y（主分类有子分类时才显示）。 */
    private int subTabsY() { return tabsY() + TAB_H + 2; }

    /** 当前主分类是否有子分类页签。 */
    private boolean hasSubTabs() { return !subCategories.isEmpty(); }

    /** 当前所在商店子页的完整分类名（"主/子"；子页选「全部」或无子分类则只有主）。供「新增商品」继承默认分类。 */
    private String currentViewCategory() {
        if (selectedTop == null || selectedTop.isEmpty()) return ShopEntry.DEFAULT_CATEGORY;
        return selectedSub.isEmpty() ? selectedTop : selectedTop + "/" + selectedSub;
    }

    /** 网格/详情内容区顶部 Y（有子页签行则再下移一行）。 */
    private int contentTop() { return tabsY() + TAB_H + 6 + (hasSubTabs() ? TAB_H + 2 : 0); }

    /** 内容区可见高度（撑到面板底部留边）。 */
    private int contentHeight() { return Math.max(60, (top + panelHeight - 8) - contentTop()); }

    private int listLeft() { return left + 5; }

    /** 网格宽度：铺满到详情列左侧，封顶 10 列。 */
    private int listWidth() {
        int maxGridW = GRID_MAX_COLS * (CELL_W + GRID_GAP) + 2;
        int available = (left + panelWidth - 2 - DETAIL_W - 6) - listLeft();
        return Math.min(maxGridW, Math.max(CELL_W + 2, available));
    }

    private int gridColumns() {
        int usable = Math.max(1, listWidth() - 2);
        return Math.max(1, Math.min(GRID_MAX_COLS, (usable + GRID_GAP) / (CELL_W + GRID_GAP)));
    }

    private int detailX() { return listLeft() + listWidth() + 6; }
    private int detailContentX() { return detailX() + 8; }

    // 搜索框仿 KE 放右侧详情列正上方（顶栏不再塞它）
    private int searchBoxX() { return detailX() + 4; }
    private int searchBoxY() { return tabsY() + 1; }

    private int buyTabX() { return left + 14; }
    private int sellTabX() { return left + 14 + BUY_TAB_W + 2; }
    // 「货币中心」按钮：出售页签右侧，始终可见（打开 ATM 子页）
    private static final int CURRENCY_BTN_W = 64;
    private int currencyBtnX() { return sellTabX() + SELL_TAB_W + 6; }
    // 「兑换中心」按钮：货币中心右侧，始终可见（打开兑换子页）
    private static final int EXCHANGE_BTN_W = 64;
    private int exchangeBtnX() { return currencyBtnX() + CURRENCY_BTN_W + 4; }
    // 「新增商品」按钮：兑换中心右侧，仅编辑权玩家且开了编辑模式（catalogEditUnlocked）才可见
    private static final int ADD_BTN_W = 64;
    private int addBtnX() { return exchangeBtnX() + EXCHANGE_BTN_W + 4; }
    // 「商店设置」按钮：仅编辑权玩家可见，不受编辑模式开关限制；新增商品隐藏时顶上来补位，不留空档
    private static final int SETTINGS_BTN_W = 64;
    private int settingsBtnX() { return catalogEditUnlocked ? addBtnX() + ADD_BTN_W + 4 : addBtnX(); }

    /** 供 CurrencyAtmScreen 读取当前 AE 模式（同包访问）。 */
    static boolean isAeMode() { return aeMode; }
    private int closeBtnX() { return left + panelWidth - 8 - CLOSE_W; }
    private int rechargeBtnX() { return closeBtnX() - 4 - RECHARGE_W; }
    // 「AE模式」切换：充值按钮左侧
    private static final int AE_BTN_W = 58;
    private int aeBtnX() { return rechargeBtnX() - 4 - AE_BTN_W; }
    // 「精妙背包模式」切换：AE模式按钮左侧
    private static final int BACKPACK_BTN_W = 78;
    private int backpackBtnX() { return aeBtnX() - 4 - BACKPACK_BTN_W; }
    // 购物车微缩按键：精妙背包按钮左侧，比其余顶栏按钮窄，非空时标题带数量角标
    private static final int CART_BTN_W = 48;
    private int cartBtnX() { return backpackBtnX() - 4 - CART_BTN_W; }

    private int favBtnX() { return cartBtnX() - 4 - FAV_BTN_W; }

    // ===== 网格坐标单一真源（渲染/点击/tooltip 三处共用，杜绝错位）=====
    private static final int ROW_STRIDE = CELL_H + GRID_GAP;
    private static final int COL_STRIDE = CELL_W + GRID_GAP;
    private static final int GRID_SCROLLBAR_W = 3;

    private int cellX(int col) { return listLeft() + col * COL_STRIDE; }
    private int cellY(int row) { return contentTop() + row * ROW_STRIDE - scroll; }

    private ShopGridViewport.Range visibleGridRange() {
        return ShopGridViewport.visibleRange(
                visibleEntryKeys.size(), gridColumns(), scroll, contentHeight(), ROW_STRIDE, 1);
    }

    private int entryIndexAt(double mouseX, double mouseY) {
        return ShopGridViewport.indexAt(
                visibleEntryKeys.size(), gridColumns(), COL_STRIDE, ROW_STRIDE, CELL_W, CELL_H,
                listLeft(), contentTop(), listWidth(), contentHeight(), scroll, mouseX, mouseY);
    }

    /** 最大滚动像素（内容总高 - 可见高，下限 0）。 */
    private int maxGridScroll() {
        int cols = gridColumns();
        int rows = (visibleEntryKeys.size() + cols - 1) / cols;
        return Math.max(0, rows * ROW_STRIDE - contentHeight());
    }

    /** 网格右侧滚动条轨道 x（紧贴列末端右侧，同 {@link #drawGrid} 原有算法）。 */
    private int gridScrollbarX() {
        int cols = gridColumns();
        return listLeft() + Math.min(listWidth() - 4, cols * COL_STRIDE - GRID_GAP) + 1;
    }

    /** 在缩放坐标系下开启 scissor：enableScissor 吃物理像素，须手动换算（KE 做法）。 */
    private void enableGridScissor(GuiGraphics g, int x, int y, int x2, int y2) {
        g.enableScissor(
                (int) (x * guiScale) + offsetX, (int) (y * guiScale) + offsetY,
                (int) (x2 * guiScale) + offsetX, (int) (y2 * guiScale) + offsetY);
    }

    @Override
    protected void initScaled() {
        if (observedCatalogRevision != ClientShopCatalog.revision()) {
            observedCatalogRevision = ClientShopCatalog.revision();
            clearSelection();
            scroll = 0;
        }
        // 动态面板铺满可用逻辑区（KE 原式）。maxScale=Float.MAX_VALUE 保证 vWidth×guiScale≡width，
        // 面板 panelWidth=vWidth-8 铺满恰好不溢出——这是 KE 的缩放不变式，勿再封顶 maxScale。
        left = 4;
        top = 8;
        panelWidth = Math.max(700, vWidth - 8);
        panelHeight = Math.max(360, vHeight - 16);

        categories = ClientShopCatalog.topCategories();
        if (selectedTop == null || !categories.contains(selectedTop)) {
            selectedTop = categories.isEmpty() ? ShopEntry.DEFAULT_CATEGORY : categories.get(0);
        }
        subCategories = ClientShopCatalog.subCategories(selectedTop);
        if (!selectedSub.isEmpty() && !subCategories.contains(selectedSub)) {
            selectedSub = ""; // 子分类已不存在 → 回退全部
        }

        // 搜索框（右侧详情列正上方）
        String prev = searchBox != null ? searchBox.getValue() : "";
        searchBox = new EditBox(this.font, searchBoxX(), searchBoxY(), SEARCH_W, 12, Component.literal("搜索"));
        searchBox.setValue(prev);
        searchBox.setResponder(s -> { scroll = 0; recomputeVisible(); });
        searchBox.setBordered(true);
        searchBox.setHint(Component.literal("搜索名称/ID"));
        searchBox.setTextColor(0xFFFFFF);
        addRenderableWidget(searchBox);

        // 数量输入框（AE 风格，详情区数量位；仅选中商品时可见）
        amountBox = new AnimatableEditBox(this.font, detailX() + 8, contentTop() + 66, DETAIL_W - 16, 12, Component.literal("数量"));
        amountBox.setValue(Long.toString(Math.max(1L, amount)));
        amountBox.setBordered(true);
        amountBox.setTextColor(0xFFFFFF);
        amountBox.setMaxLength(19); // Long.MAX 有 19 位
        amountBox.setFilter(s -> s.isEmpty() || s.matches("\\d+")); // 只允许数字
        amountBox.setResponder(s -> {
            long v;
            try {
                v = s.isEmpty() ? 1L : Long.parseLong(s);
            } catch (NumberFormatException ex) {
                v = Long.MAX_VALUE; // 超过 Long.MAX 位数 → 封顶
            }
            amount = Math.max(1L, v);
        });
        amountBox.setVisible(selected != null);
        addRenderableWidget(amountBox);

        recomputeVisible();
    }

    /** 同步数量到文本框（步进按钮改 amount 后调用，避免触发 responder 递归）。 */
    private void syncAmountBox() {
        if (amountBox != null) {
            amountBox.setValue(Long.toString(amount));
        }
    }

    private void recomputeVisible() {
        String q = searchBox != null ? searchBox.getValue() : "";
        List<Long> keys = (q == null || q.isBlank())
                ? ClientShopCatalog.keysOfGroup(selectedTop, selectedSub)
                : ClientShopCatalog.searchKeys(q);
        // 只看收藏：在分类/搜索结果之上再收窄一层，按 stub 里的 stableId 判断（不需要等完整 ShopEntry 加载）。
        if (favoritesOnly) {
            List<Long> filtered = new ArrayList<>(keys.size());
            for (Long key : keys) {
                var stub = ClientShopCatalog.stub(key);
                if (stub != null && ClientShopFavorites.contains(stub.stableId())) filtered.add(key);
            }
            keys = filtered;
        }
        visibleEntryKeys = new ArrayList<>(keys);
        // 新 revision 已不含旧身份时清空详情，不能凭对象引用继续交易。
        if (selectedEntryKey >= 0L && ClientShopCatalog.stub(selectedEntryKey) == null) clearSelection();
    }

    // ============ 渲染 ============

    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        ClientShopCatalog.pumpMaterialization(1_500_000L);
        ShopGridViewport.Range loadRange = visibleGridRange();
        List<Long> neededKeys = new ArrayList<>();
        for (int i = loadRange.fromInclusive(); i < loadRange.toExclusive(); i++) {
            neededKeys.add(visibleEntryKeys.get(i));
        }
        if (selectedEntryKey >= 0L) neededKeys.add(selectedEntryKey);
        if (selected != null && selected.hasLinkTarget()) {
            long linked = ClientShopCatalog.linkedEntryKey(selected.getLinkTo());
            if (linked >= 0L) neededKeys.add(linked);
        }
        if (cartOverlayOpen) {
            for (String stableId : ClientShopCart.getAll().keySet()) {
                long k = ClientShopCatalog.keyOfStableId(stableId);
                if (k >= 0L) neededKeys.add(k);
            }
        }
        ClientShopCatalog.ensureLoadedRange(neededKeys, 0, neededKeys.size());
        // 数量框仅在选中商品时可见（本方法在 super.render 画控件之前执行，故此处同步安全）。
        // amountBox 现在是 AnimatableEditBox：自己在 renderWidget 里套用跟 drawDetail 同一套
        // 锚点/缓动公式做 PoseStack 缩放，这里只需每帧把动画进度源和锚点告诉它即可，不用再
        // 手动 setX/setY/setWidth 去追坐标（见反馈：改成可被控制的组件，而不是动画期间藏起来）。
        float cardT = GuiRenderUtil.popAnimProgress(cardSwitchAtMs, CARD_SWITCH_ANIM_MS);
        if (amountBox != null) {
            amountBox.setVisible(selected != null);
            amountBox.setAnim(this::cardAnimProgress, detailX() + DETAIL_W / 2f, contentTop() + contentHeight() / 2f);
        }
        // 整体面板（KE 风格 4 层嵌套：金边→金→暗背景→内层）
        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        g.fill(left + 6, top + TOP_BAR_H + 6, left + panelWidth - 6, top + panelHeight - 6, PANEL_INNER);

        // 顶部买/卖页签
        drawTab(g, buyTabX(), top + 6, BUY_TAB_W, TOP_BAR_H, "购买", mode == Mode.BUY, mx, my);
        drawTab(g, sellTabX(), top + 6, SELL_TAB_W, TOP_BAR_H, "出售", mode == Mode.SELL, mx, my);
        // 货币中心 + 兑换中心（始终可见）
        drawButton(g, currencyBtnX(), top + 6, CURRENCY_BTN_W, TOP_BAR_H, "§6货币中心", mx, my);
        drawButton(g, exchangeBtnX(), top + 6, EXCHANGE_BTN_W, TOP_BAR_H, "§d兑换中心", mx, my);
        // 新增商品（编辑权 + 开了编辑模式）、商店设置（仅编辑权，不受编辑模式限制）
        if (catalogEditUnlocked) {
            drawButton(g, addBtnX(), top + 6, ADD_BTN_W, TOP_BAR_H, "§a新增商品", mx, my);
        }
        if (canEdit) {
            drawButton(g, settingsBtnX(), top + 6, SETTINGS_BTN_W, TOP_BAR_H, "§b商店设置", mx, my);
        }

        // 顶栏右侧：购物车 → 精妙背包模式 → AE模式 → 充值全部 → 关闭
        int cartCount = ClientShopCart.size();
        drawButton(g, cartBtnX(), top + 6, CART_BTN_W, TOP_BAR_H,
                cartCount > 0 ? "§e购物车§6(" + cartCount + ")" : "§e购物车", mx, my);
        drawButton(g, favBtnX(), top + 6, FAV_BTN_W, TOP_BAR_H,
                favoritesOnly ? "§e★收藏" : "§7☆收藏", mx, my);
        drawButton(g, backpackBtnX(), top + 6, BACKPACK_BTN_W, TOP_BAR_H, backpackMode ? "§a精妙背包:开" : "§8精妙背包:关", mx, my);
        drawButton(g, aeBtnX(), top + 6, AE_BTN_W, TOP_BAR_H, aeMode ? "§aAE模式:开" : "§8AE模式:关", mx, my);
        drawButton(g, rechargeBtnX(), top + 6, RECHARGE_W, TOP_BAR_H, "§b充值全部", mx, my);
        drawButton(g, closeBtnX(), top + 6, CLOSE_W, TOP_BAR_H, "§c关闭", mx, my);

        // 标题（居中于整个面板，KE 做法）
        g.drawCenteredString(this.font, "§6钱包商店", titleX(), top + 9, GOLD);

        // 余额行（KE 货币栏：图标 + 紧凑数字 横排）
        int by = balanceY();
        int balW = panelWidth - 28;
        renderBox(g, left + 14, by, balW, balanceHeight(), GOLD_DARK, BOX_BG);
        drawCurrencyBar(g, left + 14 + 6, by, balW - 12, mx, my);

        // 主分类页签行
        int ty = tabsY();
        int tx = left + 14;
        int tabsRight = detailX() - 8; // 不越过搜索框列
        for (String cat : categories) {
            int tw = Math.max(30, this.font.width(cat) + 10);
            if (tx + tw > tabsRight) break; // 简单截断
            drawTab(g, tx, ty, tw, TAB_H, cat, cat.equals(selectedTop), mx, my);
            tx += tw + 3;
        }
        // 子分类页签行（当前主分类有子分类时）：先「全部」再各子分类
        if (hasSubTabs()) {
            int sty = subTabsY();
            int stx = left + 14;
            int aw = Math.max(30, this.font.width("全部") + 10);
            drawTab(g, stx, sty, aw, TAB_H, "§7全部", selectedSub.isEmpty(), mx, my);
            stx += aw + 3;
            for (String sub : subCategories) {
                int tw = Math.max(30, this.font.width(sub) + 10);
                if (stx + tw > tabsRight) break;
                drawTab(g, stx, sty, tw, TAB_H, sub, sub.equals(selectedSub), mx, my);
                stx += tw + 3;
            }
        }

        // 左侧网格背景（切页横切滑入：纯 PoseStack 平移，方向按页签左右顺序走，彰显"切"的方向感，
        // 见反馈：切页不该用缩放弹入，要左右横切；drawGrid 内部自带 scissor 裁到 gx..gx+gw，
        // 滑入途中溢出的部分会被天然裁掉，不会盖到旁边的页签/详情栏）
        int gx = listLeft(), gy = contentTop(), gw = listWidth(), gh = contentHeight();
        g.fill(gx, gy, gx + gw, gy + gh, BOX_BG);
        float gridT = GuiRenderUtil.popAnimProgress(gridSwitchAtMs, TAB_SWITCH_ANIM_MS);
        if (gridT < 1f) {
            g.pose().pushPose();
            slideInX(g, gridSlideDir, gridT);
            drawGrid(g, mx, my);
            g.pose().popPose();
        } else {
            drawGrid(g, mx, my);
        }

        // 右侧详情面板（同上，锚点换成详情列自己的中心；cardT 已在上面算过，amountBox 同步过了）
        if (cardT < 1f) {
            g.pose().pushPose();
            GuiRenderUtil.popScaleAt(g, detailX() + DETAIL_W / 2f, gy + gh / 2f, cardT);
            drawDetail(g, mx, my);
            g.pose().popPose();
        } else {
            drawDetail(g, mx, my);
        }

        // 实时消息横幅队列（商店交互反馈，5 秒后自动滑出消失）：面板底部居中，覆盖在最上层，
        // 最多同时显示 FLASH_MAX_VISIBLE 条，新消息从底部滑入，挤开的/到期的都走同一套滑出动画再摘除
        {
            long now = System.currentTimeMillis();
            tickFlash(now);
            if (!flashActive.isEmpty() || !flashLeaving.isEmpty()) {
                // 物品图标走 GuiGraphics 的缓冲渲染层（renderItem，drawGrid/drawDetail 里画的），和 fill()/drawString()
                // 这类立即绘制的 flat quad 不在同一批次；不强制 flush 会导致图标在批次刷新时"跳"到横幅上层，
                // 盖住文字（同 ShopEntryEditScreen 描述展开编写大图层的穿模成因，见其 renderScaledBackground 注释）。
                g.flush();
                int baseY = top + panelHeight - 24;
                for (FlashMsg m : flashLeaving) drawFlashRow(g, m, now, baseY);
                for (FlashMsg m : flashActive) drawFlashRow(g, m, now, baseY);
            }
        }

        // 撤销删除按钮：删除后 8 秒内悬浮显示，点一下把刚删的条目加回来（服务端另有独立 30 秒兜底窗口）
        if (undoDeleteLabel != null) {
            if (System.currentTimeMillis() < undoDeleteUntilMs) {
                g.flush(); // 同上，物品图标批次会晚于本次 fill/drawString 提交，先落盘防止盖字
                int[] b = undoDeleteBtnBounds();
                boolean hv = hit(mx, my, b[0], b[1], b[2], b[3]);
                g.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], hv ? 0xFF2A6E2A : 0xE0163016);
                g.fill(b[0], b[1], b[0] + b[2], b[1] + 1, 0xFF33CC33);
                g.drawCenteredString(this.font, "§a↩ 撤销删除【" + GuiRenderUtil.trimText(this.font, undoDeleteLabel, 70) + "】",
                        b[0] + b[2] / 2, b[1] + 3, 0xFFFFFF);
            } else {
                undoDeleteLabel = null;
            }
        }

        // 撤销排序按钮：前移/后移/置顶后 8 秒内悬浮显示，点一下把该条目挪回移动前的位置（服务端另有独立 30 秒兜底窗口）
        if (undoReorderLabel != null) {
            if (System.currentTimeMillis() < undoReorderUntilMs) {
                g.flush();
                int[] b = undoReorderBtnBounds();
                boolean hv = hit(mx, my, b[0], b[1], b[2], b[3]);
                g.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], hv ? 0xFF2A6E2A : 0xE0163016);
                g.fill(b[0], b[1], b[0] + b[2], b[1] + 1, 0xFF33CC33);
                g.drawCenteredString(this.font, "§a↩ 撤销排序【" + GuiRenderUtil.trimText(this.font, undoReorderLabel, 70) + "】",
                        b[0] + b[2] / 2, b[1] + 3, 0xFFFFFF);
            } else {
                undoReorderLabel = null;
            }
        }
    }

    /** 弹入动画进度 [0,1]（1=已播完）；atMs<=0（从未触发过）视为已播完，不做任何变换。 */
    /**
     * 切页横切滑入（dir&gt;0 从右侧滑入 / dir&lt;0 从左侧滑入，ease-out）：单纯水平平移，不缩放，
     * 彰显"切"到下一页的方向感（而非弹入的"内容出现"感）。调用方须自行 pushPose/popPose 包住。
     */
    private static void slideInX(GuiGraphics g, int dir, float t) {
        float eased = 1f - (1f - t) * (1f - t);
        float offset = dir * TAB_SLIDE_DIST * (1f - eased);
        g.pose().translate(offset, 0, 0);
    }

    /** 详情面板切卡片弹入动画进度 [0,1]；供 amountBox（AnimatableEditBox）作为动画驱动源读取。 */
    private float cardAnimProgress() {
        return GuiRenderUtil.popAnimProgress(cardSwitchAtMs, CARD_SWITCH_ANIM_MS);
    }

    /** 画一条消息横幅：按其动画槽位算出当前 Y，宽度随文字自适应，样式跟原先的单槽横幅一致。 */
    private void drawFlashRow(GuiGraphics g, FlashMsg m, long now, int baseY) {
        int y = flashRowY(m, now, baseY);
        int w = this.font.width(m.text);
        int bannerW = Math.min(panelWidth - 12, w + 16);
        int bannerX = left + (panelWidth - bannerW) / 2;
        g.fill(bannerX, y, bannerX + bannerW, y + 16, 0xE0101010);   // 半透明黑底
        g.fill(bannerX, y, bannerX + bannerW, y + 1, 0xFF00C0C0);    // 顶部青线
        g.drawCenteredString(this.font, m.text, left + panelWidth / 2, y + 4, 0xFFFFFF);
    }

    private static final int DESC_OVERLAY_CLOSE_W = 16;
    private static final int DESC_OVERLAY_CLOSE_H = 12;
    private static final int DESC_OVERLAY_LINE_H = 10;
    private static final int DESC_OVERLAY_SCROLLBAR_W = 3;

    /** 描述详情大图层边界 {x, y, w, h}：居中于虚拟画布，四周留 20px 边距，超大画布下封顶 420×260。 */
    private int[] descOverlayBounds() {
        int ow = Math.min(vWidth - 40, 420);
        int oh = Math.min(vHeight - 40, 260);
        int ox = (vWidth - ow) / 2;
        int oy = (vHeight - oh) / 2;
        return new int[]{ox, oy, ow, oh};
    }

    private int descOverlayCloseX(int[] r) { return r[0] + r[2] - DESC_OVERLAY_CLOSE_W - 4; }
    private int descOverlayCloseY(int[] r) { return r[1] + 4; }

    /** 描述详情大图层：正文文本区域 {x, y, w, textLineH 可用行数}（供渲染/滚动/滚动条共用同一份换行结果）。 */
    private List<net.minecraft.util.FormattedCharSequence> descOverlayLines(int[] r) {
        if (selected == null) return java.util.List.of();
        String desc = selected.getDescription();
        if (desc == null) desc = "";
        int wrapW = r[2] - 16 - DESC_OVERLAY_SCROLLBAR_W - 3;
        return this.font.split(Component.literal("§f" + GuiRenderUtil.translateAmpCodes(desc)), wrapW);
    }

    private int descOverlayVisibleLines(int[] r) {
        return Math.max(1, (r[3] - 32) / DESC_OVERLAY_LINE_H);
    }

    private int descOverlayMaxScroll(int[] r, int lineCount) {
        return Math.max(0, lineCount - descOverlayVisibleLines(r));
    }

    /** 前景层（盖住所有控件）：描述详情大图层，FTBQ 风格全屏遮罩 + 居中面板 + 自动换行长文本，超出可视高度可滚动。 */
    @Override
    protected void renderScaledForeground(GuiGraphics g, int mx, int my, float pt) {
        if (ctxMenuOpen) {
            drawContextMenu(g, mx, my);
            return;
        }
        if (guideOverlayOpen && selected != null) {
            renderGuideOverlay(g, mx, my);
            return;
        }
        if (cartOverlayOpen) {
            // 展开弹入：跟切页/切卡片同一套锚点缩放公式，锚点换成购物车面板自己的中心（见反馈：需要动画补充）
            int[] cr = cartOverlayBounds();
            float cartT = GuiRenderUtil.popAnimProgress(cartOverlayOpenAtMs, OVERLAY_POP_ANIM_MS);
            if (cartT < 1f) {
                g.pose().pushPose();
                GuiRenderUtil.popScaleAt(g, cr[0] + cr[2] / 2f, cr[1] + cr[3] / 2f, cartT);
                renderCartOverlay(g, mx, my);
                g.pose().popPose();
            } else {
                renderCartOverlay(g, mx, my);
            }
            return;
        }
        if (!descOverlayOpen || selected == null) return;
        int[] r = descOverlayBounds();
        int ox = r[0], oy = r[1], ow = r[2];

        g.fill(0, 0, vWidth, vHeight, 0xC0000000); // 全屏半透明遮罩
        renderBox(g, ox, oy, ow, r[3], GOLD_DARK, PANEL_BG);

        g.drawString(this.font, GuiRenderUtil.trimText(this.font,
                "§6" + selected.goodsDisplayName() + " §7— 描述详情", ow - DESC_OVERLAY_CLOSE_W - 20),
                ox + 8, oy + 8, GOLD, true);
        int closeX = descOverlayCloseX(r), closeY = descOverlayCloseY(r);
        drawButton(g, closeX, closeY, DESC_OVERLAY_CLOSE_W, DESC_OVERLAY_CLOSE_H, "§cX", mx, my);

        List<net.minecraft.util.FormattedCharSequence> lines = descOverlayLines(r);
        int visible = descOverlayVisibleLines(r);
        int maxScroll = descOverlayMaxScroll(r, lines.size());
        descOverlayScroll = Math.max(0, Math.min(maxScroll, descOverlayScroll));

        int ty = oy + 24;
        for (int i = descOverlayScroll; i < Math.min(lines.size(), descOverlayScroll + visible); i++) {
            g.drawString(this.font, lines.get(i), ox + 8, ty, WHITE, true);
            ty += DESC_OVERLAY_LINE_H;
        }
        drawDescOverlayScrollbar(g, r, maxScroll, mx, my);
    }

    /** 描述详情大图层右侧滚动条：轨道常驻绘制，超出可视行数时叠一段可拖拽把手（同 {@link #drawGridScrollbar} 风格）。 */
    private void drawDescOverlayScrollbar(GuiGraphics g, int[] r, int maxScroll, int mx, int my) {
        int barX = r[0] + r[2] - 6 - DESC_OVERLAY_SCROLLBAR_W;
        int barY = r[1] + 22, barH = r[3] - 30;
        g.fill(barX, barY, barX + DESC_OVERLAY_SCROLLBAR_W, barY + barH, NUMBER_BAR_BG);
        if (maxScroll <= 0) return;
        int visible = descOverlayVisibleLines(r);
        int lineCount = visible + maxScroll;
        int thumbH = Math.max(10, barH * visible / lineCount);
        int thumbY = barY + (barH - thumbH) * descOverlayScroll / maxScroll;
        boolean hv = draggingDescOverlayScroll || GuiRenderUtil.isHovering(mx, my, barX, thumbY, DESC_OVERLAY_SCROLLBAR_W, thumbH);
        g.fill(barX, thumbY, barX + DESC_OVERLAY_SCROLLBAR_W, thumbY + thumbH, hv ? CYAN : GOLD);
    }

    private static final int GUIDE_OVERLAY_ROW_H = 20;

    /** GuideME「指南详情」大图层边界：按命中条数动态算高度（比描述详情矮很多，通常没几条），封顶同描述详情。 */
    private int[] guideOverlayBounds() {
        int ow = Math.min(vWidth - 40, 300);
        int desiredH = 32 + guideHits.size() * GUIDE_OVERLAY_ROW_H;
        int oh = Math.min(vHeight - 40, Math.max(60, Math.min(desiredH, 260)));
        int ox = (vWidth - ow) / 2;
        int oy = (vHeight - oh) / 2;
        return new int[]{ox, oy, ow, oh};
    }

    private int guideOverlayCloseX(int[] r) { return r[0] + r[2] - DESC_OVERLAY_CLOSE_W - 4; }
    private int guideOverlayCloseY(int[] r) { return r[1] + 4; }

    private int guideOverlayVisibleRows(int[] r) {
        return Math.max(1, (r[3] - 28) / GUIDE_OVERLAY_ROW_H);
    }

    private int guideOverlayMaxScroll(int[] r) {
        return Math.max(0, guideHits.size() - guideOverlayVisibleRows(r));
    }

    /**
     * GuideME「指南详情」大图层：一套多物品的商品若有 2 项以上各自登记了指南页（可能是同一份指南的不同页），
     * 展开列出每一项，图标+物品名一行，点击直接打开该页并关闭图层（仿描述详情大图层的全屏遮罩+居中面板风格，
     * 但内容是可点击的跳转行列表而非纯文本，所以不需要描述详情那套换行/拖拽滚动条，滚轮翻页即可）。
     */
    private void renderGuideOverlay(GuiGraphics g, int mx, int my) {
        int[] r = guideOverlayBounds();
        int ox = r[0], oy = r[1], ow = r[2];

        g.fill(0, 0, vWidth, vHeight, 0xC0000000); // 全屏半透明遮罩
        renderBox(g, ox, oy, ow, r[3], GOLD_DARK, PANEL_BG);

        g.drawString(this.font, GuiRenderUtil.trimText(this.font,
                "§d" + selected.goodsDisplayName() + " §7— 指南详情", ow - DESC_OVERLAY_CLOSE_W - 20),
                ox + 8, oy + 8, GOLD, true);
        int closeX = guideOverlayCloseX(r), closeY = guideOverlayCloseY(r);
        drawButton(g, closeX, closeY, DESC_OVERLAY_CLOSE_W, DESC_OVERLAY_CLOSE_H, "§cX", mx, my);

        int visible = guideOverlayVisibleRows(r);
        int maxScroll = guideOverlayMaxScroll(r);
        guideOverlayScroll = Math.max(0, Math.min(maxScroll, guideOverlayScroll));
        int rowY0 = oy + 22;
        for (int i = 0; i < visible; i++) {
            int idx = guideOverlayScroll + i;
            if (idx >= guideHits.size()) break;
            com.dishanhai.gt_shanhai.client.shop.ShopGuideLookup.GuideHit hit = guideHits.get(idx);
            int ry = rowY0 + i * GUIDE_OVERLAY_ROW_H;
            boolean hover = GuiRenderUtil.isHovering(mx, my, ox + 6, ry, ow - 12, GUIDE_OVERLAY_ROW_H);
            if (hover) g.fill(ox + 6, ry, ox + ow - 6, ry + GUIDE_OVERLAY_ROW_H, BUTTON_HOVER);
            g.renderItem(hit.item(), ox + 8, ry + 2);
            String name = GuiRenderUtil.trimText(this.font, hit.item().getHoverName().getString(), ow - 36);
            g.drawString(this.font, (hover ? "§b§n" : "§9§n") + name, ox + 28, ry + 6, hover ? CYAN : GOLD, true);
        }
        if (maxScroll > 0) {
            g.drawString(this.font, "§8滚轮翻页 " + (guideOverlayScroll + 1) + "-" + Math.min(guideHits.size(), guideOverlayScroll + visible)
                    + "/" + guideHits.size(), ox + 8, oy + r[3] - 10, GRAY, false);
        }
    }

    // ============ 购物车大图层 ============

    private static final int CART_ROW_H = 26;
    private static final int CART_STEP_W = 16;
    private static final int CART_QTY_W = 42;
    private static final int CART_REMOVE_W = 20;
    private static final int CART_CTRL_BLOCK_W = CART_STEP_W + 2 + CART_QTY_W + 2 + CART_STEP_W + 4 + CART_REMOVE_W;
    private static final int CART_SETTLE_BTN_H = 22;
    private static final int CART_SETTLE_BTN_W = 100;

    /** 购物车面板展示清单：当前购物车实际内容 ∪ 最近一次结算批次里已回执成功而被移出购物车的项——
     * 结算批次没被关闭面板清掉之前，成功戳记的行还要能继续显示（见需求：进度跑完直接在面板里看成功/失败）。 */
    private List<String> cartDisplayIds() {
        List<String> ids = new ArrayList<>(ClientShopCart.getAll().keySet());
        for (String id : cartSettleBatch) if (!ids.contains(id)) ids.add(id);
        return ids;
    }

    /** 该 stableId 是否是本结算批次里已回执「全部成交」的行——这类行不画数量步进/删除，改画成功戳记覆盖那块区域。 */
    private boolean cartRowSettledSuccess(String stableId) {
        if (!cartSettleBatch.contains(stableId)) return false;
        ClientShopCart.Result r = ClientShopCart.getResult(stableId);
        return r != null && r.atMs >= cartSettleBatchStartedAtMs && r.ok();
    }

    /** 本结算批次里已回执（成功/部分/失败）的行——用于给行状态着色，不再是客户端猜的缺口符号。 */
    private ClientShopCart.Result cartRowSettledResult(String stableId) {
        if (!cartSettleBatch.contains(stableId)) return null;
        ClientShopCart.Result r = ClientShopCart.getResult(stableId);
        return (r != null && r.atMs >= cartSettleBatchStartedAtMs) ? r : null;
    }

    /** 当前结算批次进度：[已回执件数, 批次总件数]；无批次时返回 [0,0]。 */
    private int[] cartBatchProgress() {
        if (cartSettleBatch.isEmpty()) return new int[]{0, 0};
        int resolved = 0;
        for (String id : cartSettleBatch) {
            ClientShopCart.Result r = ClientShopCart.getResult(id);
            if (r != null && r.atMs >= cartSettleBatchStartedAtMs) resolved++;
        }
        return new int[]{resolved, cartSettleBatch.size()};
    }

    /** 购物车大图层边界：比描述/指南详情宽（要放数量步进+删除按钮），高度按候选条数动态算，封顶同其余大图层。 */
    private int[] cartOverlayBounds() {
        int ow = Math.min(vWidth - 40, 620);
        int count = cartDisplayIds().size();
        int desiredH = 56 + count * CART_ROW_H;
        int oh = Math.min(vHeight - 40, Math.max(110, Math.min(desiredH, 420)));
        int ox = (vWidth - ow) / 2;
        int oy = (vHeight - oh) / 2;
        return new int[]{ox, oy, ow, oh};
    }

    private int cartOverlayVisibleRows(int[] r) {
        return Math.max(1, (r[3] - 22 - (CART_SETTLE_BTN_H + 10)) / CART_ROW_H);
    }

    private int cartOverlayMaxScroll(int[] r, int count) {
        return Math.max(0, count - cartOverlayVisibleRows(r));
    }

    private int cartCtrlBlockX(int[] r) {
        return r[0] + r[2] - 8 - CART_CTRL_BLOCK_W;
    }

    /** 购物车条目行的花费预览请求节流：跟详情页那套（previewRequestedKey 单槽）各自独立，
     * 允许购物车里的多件商品同时各自发起请求（见 ClientCostPreview 已扩成按 entryKey 多槽缓存）。 */
    private void maybeRequestCartCostPreview(long entryKey, ShopCost cost) {
        if (entryKey < 0L || cost == null || (cost.coins.isEmpty() && cost.physical.isEmpty())) return;
        net.minecraft.client.multiplayer.ClientLevel lvl = Minecraft.getInstance().level;
        long gameTime = lvl != null ? lvl.getGameTime() : 0L;
        Long last = cartPreviewRequestedAtGameTime.get(entryKey);
        boolean stale = last == null || cartPreviewRequestedAeMode != aeMode || gameTime - last >= PREVIEW_REFRESH_TICKS;
        if (!stale) return;
        cartPreviewRequestedAtGameTime.put(entryKey, gameTime);
        cartPreviewRequestedAeMode = aeMode;
        ShanhaiNetwork.CHANNEL.sendToServer(new com.dishanhai.gt_shanhai.network.ShopCostPreviewRequestPacket(
                ClientShopCatalog.revision(), entryKey, aeMode));
    }

    /** null=未同步（数据还没回来，按"未知"处理）；否则=按当前已知余量确定的够/不够（含 AE/精妙背包口径）。 */
    private Boolean cartRowShortfall(long entryKey, ShopCost cost, long times) {
        if (entryKey < 0L || !ClientCostPreview.matches(entryKey, aeMode)) return null;
        return hasCostShortfall(cost, times, entryKey, aeMode);
    }

    /** 购物车大图层：候选清单（图标+名称+缺口状态+数量步进+删除）+ 底部汇总消耗与结算按钮。 */
    private void renderCartOverlay(GuiGraphics g, int mx, int my) {
        int[] r = cartOverlayBounds();
        int ox = r[0], oy = r[1], ow = r[2], oh = r[3];
        g.fill(0, 0, vWidth, vHeight, 0xC0000000); // 全屏半透明遮罩
        renderBox(g, ox, oy, ow, oh, GOLD_DARK, PANEL_BG);

        Map<String, Long> cartMap = ClientShopCart.getAll();
        List<String> stableIds = new ArrayList<>(cartMap.keySet());

        g.drawString(this.font, GuiRenderUtil.trimText(this.font,
                "§6购物车 §7(" + stableIds.size() + " 件候选)", ow - DESC_OVERLAY_CLOSE_W - 20), ox + 8, oy + 8, GOLD, true);
        int closeX = ox + ow - DESC_OVERLAY_CLOSE_W - 4, closeY = oy + 4;
        drawButton(g, closeX, closeY, DESC_OVERLAY_CLOSE_W, DESC_OVERLAY_CLOSE_H, "§cX", mx, my);

        if (stableIds.isEmpty()) {
            g.drawCenteredString(this.font, "§7购物车是空的——Ctrl+左键网格商品加入候选", ox + ow / 2, oy + oh / 2 - 4, GRAY);
            return;
        }

        int visible = cartOverlayVisibleRows(r);
        int maxScroll = cartOverlayMaxScroll(r, stableIds.size());
        cartOverlayScroll = Math.max(0, Math.min(maxScroll, cartOverlayScroll));

        int rowY0 = oy + 22;
        boolean anyConfirmedShortfall = false;
        java.math.BigInteger totalSpark = java.math.BigInteger.ZERO;
        java.math.BigInteger totalEu = java.math.BigInteger.ZERO;
        Map<ResourceLocation, java.math.BigInteger> totalCoins = new java.util.LinkedHashMap<>();
        int ctrlX = cartCtrlBlockX(r);

        for (int i = 0; i < visible; i++) {
            int idx = cartOverlayScroll + i;
            if (idx >= stableIds.size()) break;
            String stableId = stableIds.get(idx);
            long qty = cartMap.getOrDefault(stableId, 0L);
            int ry = rowY0 + i * CART_ROW_H;
            int textY = ry + (CART_ROW_H - 2 - 8) / 2;
            int iconY = ry + (CART_ROW_H - 2 - 16) / 2;
            boolean rowHover = GuiRenderUtil.isHovering(mx, my, ox + 6, ry, ow - 12, CART_ROW_H - 2);
            if (rowHover) g.fill(ox + 6, ry, ox + ow - 6, ry + CART_ROW_H - 2, BUTTON_HOVER);

            long entryKey = ClientShopCatalog.keyOfStableId(stableId);
            ShopEntry entry = entryKey >= 0L ? ClientShopCatalog.get(entryKey) : null;
            int nameMaxW = Math.max(10, ctrlX - (ox + 26) - 6);
            ClientShopCart.Result settled = cartRowSettledResult(stableId);
            boolean settledSuccess = settled != null && settled.ok();
            if (entry == null) {
                g.drawString(this.font, "§8[已下架/加载中] " + stableId.substring(0, Math.min(8, stableId.length())),
                        ox + 8, textY, GRAY, true);
            } else {
                if (entry.isPrimaryGoodsFluid()) {
                    renderFluidIcon(g, ox + 8, iconY, 16, entry.primaryGoodsFluid());
                } else {
                    g.renderItem(entry.displayGoodsStack(), ox + 8, iconY);
                }
                ShopCost cost = entry.getEffectiveCost(); // 购物车只买不卖，折扣按买价口径结算
                String label;
                if (settled != null) {
                    // 本结算批次已回执：直接按服务端结果着色+附原因，不再用客户端猜的缺口符号（见反馈：结算后要看清成败）
                    String tag = settled.ok() ? "§a✓ " : (settled.partial() ? "§e~ " : "§c✗ ");
                    label = tag + "§f" + entry.goodsDisplayName() + " §7(" + settled.reason + ")";
                } else {
                    maybeRequestCartCostPreview(entryKey, cost);
                    Boolean shortfall = cartRowShortfall(entryKey, cost, qty);
                    String status = shortfall == null ? "§7? " : (shortfall ? "§c✗ " : "§a✓ ");
                    if (Boolean.TRUE.equals(shortfall)) anyConfirmedShortfall = true;
                    label = status + "§f" + entry.goodsDisplayName();
                }
                g.drawString(this.font, GuiRenderUtil.trimText(this.font, label, nameMaxW), ox + 26, textY, WHITE, true);
                totalSpark = totalSpark.add(cost.spark.multiply(java.math.BigInteger.valueOf(qty)));
                totalEu = totalEu.add(cost.eu.multiply(java.math.BigInteger.valueOf(qty)));
                for (Map.Entry<ResourceLocation, java.math.BigInteger> c : cost.coins.entrySet()) {
                    totalCoins.merge(c.getKey(), c.getValue().multiply(java.math.BigInteger.valueOf(qty)), java.math.BigInteger::add);
                }
            }

            // 数量步进：− qty +（默认±1，Shift±10，Ctrl±100，点击时判定），再右边删除；本批次已全部成交回执的行
            // 改画成功戳记覆盖这块区域（复用同一块地方，不再可调），见需求：成功行用控件区域盖成戳记
            int minusX = ctrlX, qtyTextX = minusX + CART_STEP_W + 2, plusX = qtyTextX + CART_QTY_W + 2,
                    removeX = plusX + CART_STEP_W + 4;
            if (settledSuccess) {
                String stamp = "§a✓ 已购买 " + formatBig(java.math.BigInteger.valueOf(settled.done)) + " 次";
                g.drawString(this.font, GuiRenderUtil.trimText(this.font, stamp, CART_CTRL_BLOCK_W), ctrlX, textY, WHITE, true);
            } else {
                drawButton(g, minusX, ry, CART_STEP_W, CART_ROW_H - 2, "§c-", mx, my);
                g.drawCenteredString(this.font, "§f" + formatBig(java.math.BigInteger.valueOf(qty)),
                        qtyTextX + CART_QTY_W / 2, ry + (CART_ROW_H - 2 - 8) / 2, WHITE);
                drawButton(g, plusX, ry, CART_STEP_W, CART_ROW_H - 2, "§a+", mx, my);
                drawButton(g, removeX, ry, CART_REMOVE_W, CART_ROW_H - 2, "§c×", mx, my);
            }
        }
        if (maxScroll > 0) {
            g.drawString(this.font, "§8滚轮翻页 " + (cartOverlayScroll + 1) + "-"
                    + Math.min(stableIds.size(), cartOverlayScroll + visible) + "/" + stableIds.size(),
                    ox + 8, oy + oh - 24, GRAY, false);
        }

        // 底部：汇总预计消耗（星火/EU/币种，客户端已知数据直接加总，无需额外往返）+ 结算按钮/结算进度条
        ShopCost aggCost = new ShopCost(totalSpark, totalCoins, List.of(), totalEu);
        g.drawString(this.font, GuiRenderUtil.trimText(this.font, "§7合计预计消耗: " + costInline(aggCost), ow - CART_SETTLE_BTN_W - 16),
                ox + 8, oy + oh - 14, CYAN, true);
        int settleW = CART_SETTLE_BTN_W, settleX = ox + ow - 8 - settleW, settleY = oy + oh - CART_SETTLE_BTN_H - 2;
        int[] progress = cartBatchProgress();
        boolean settling = progress[1] > 0 && progress[0] < progress[1];
        if (settling) {
            // 结算批次还没全部回执：这块区域改画进度条（浮在面板最底下），全部回执前不再接受新的结算点击
            renderBox(g, settleX, settleY, settleW, CART_SETTLE_BTN_H, GOLD_DARK, PANEL_BG);
            float prog = (float) progress[0] / progress[1];
            int fillW = Math.max(0, Math.round((settleW - 2) * prog));
            if (fillW > 0) g.fill(settleX + 1, settleY + 1, settleX + 1 + fillW, settleY + CART_SETTLE_BTN_H - 1, CYAN);
            g.drawCenteredString(this.font, "§f结算中 " + progress[0] + "/" + progress[1],
                    settleX + settleW / 2, settleY + (CART_SETTLE_BTN_H - 8) / 2, WHITE);
        } else {
            boolean settleHover = hit(mx, my, settleX, settleY, settleW, CART_SETTLE_BTN_H);
            renderButton(g, settleX, settleY, settleW, CART_SETTLE_BTN_H, settleHover, "§a结算购物车",
                    anyConfirmedShortfall ? DEEP_RED : GREEN_DARK, anyConfirmedShortfall ? DEEP_RED : GREEN);
        }
    }

    /** 购物车大图层点击：关闭 / 图层外点击关闭 / 数量步进 / 删除 / 结算，命中任意一处都吞掉点击不下穿。 */
    private boolean handleCartOverlayClick(double mx, double my) {
        int[] r = cartOverlayBounds();
        int ox = r[0], oy = r[1], ow = r[2], oh = r[3];
        int closeX = ox + ow - DESC_OVERLAY_CLOSE_W - 4, closeY = oy + 4;
        if (!hit(mx, my, ox, oy, ow, oh) || hit(mx, my, closeX, closeY, DESC_OVERLAY_CLOSE_W, DESC_OVERLAY_CLOSE_H)) {
            cartOverlayOpen = false;
            // 结算结果不因关闭面板清掉——玩家结算后经常会先切出去看聊天栏/背包再切回来看结果，
            // 批次要撑到下一次点「结算购物车」才被替换（见反馈：关了一下再打开就看不到回执了）
            return true;
        }
        Map<String, Long> cartMap = ClientShopCart.getAll();
        List<String> stableIds = cartDisplayIds(); // 必须跟 renderCartOverlay 用同一份清单，行索引才对得上
        if (!stableIds.isEmpty()) {
            int visible = cartOverlayVisibleRows(r);
            int rowY0 = oy + 22;
            int ctrlX = cartCtrlBlockX(r);
            int minusX = ctrlX, qtyTextX = minusX + CART_STEP_W + 2, plusX = qtyTextX + CART_QTY_W + 2,
                    removeX = plusX + CART_STEP_W + 4;
            long step = net.minecraft.client.gui.screens.Screen.hasControlDown() ? 100L
                    : (net.minecraft.client.gui.screens.Screen.hasShiftDown() ? 10L : 1L);
            for (int i = 0; i < visible; i++) {
                int idx = cartOverlayScroll + i;
                if (idx >= stableIds.size()) break;
                String stableId = stableIds.get(idx);
                if (cartRowSettledSuccess(stableId)) continue; // 该行画的是成功戳记，不是数量步进/删除，不接受这块点击
                int ry = rowY0 + i * CART_ROW_H;
                if (hit(mx, my, minusX, ry, CART_STEP_W, CART_ROW_H - 2)) {
                    long cur = cartMap.getOrDefault(stableId, 1L);
                    ClientShopCart.setAmount(stableId, Math.max(1L, cur - step));
                    return true;
                }
                if (hit(mx, my, plusX, ry, CART_STEP_W, CART_ROW_H - 2)) {
                    long cur = cartMap.getOrDefault(stableId, 1L);
                    ClientShopCart.setAmount(stableId, addClamp(cur, step));
                    return true;
                }
                if (hit(mx, my, removeX, ry, CART_REMOVE_W, CART_ROW_H - 2)) {
                    ClientShopCart.remove(stableId);
                    return true;
                }
            }
        }
        int settleW = CART_SETTLE_BTN_W, settleX = ox + ow - 8 - settleW, settleY = oy + oh - CART_SETTLE_BTN_H - 2;
        int[] progress = cartBatchProgress();
        boolean settling = progress[1] > 0 && progress[0] < progress[1];
        if (!settling && hit(mx, my, settleX, settleY, settleW, CART_SETTLE_BTN_H)) {
            cartSettleBatch.clear();
            cartSettleBatchStartedAtMs = System.currentTimeMillis();
            int fired = 0;
            for (Map.Entry<String, Long> e : cartMap.entrySet()) {
                String stableId = e.getKey();
                long entryKey = ClientShopCatalog.keyOfStableId(stableId);
                ShopEntry entry = entryKey >= 0L ? ClientShopCatalog.get(entryKey) : null;
                if (entry == null) continue; // 已下架/尚未加载完成，留在购物车里，不动它
                long qty = e.getValue() == null ? 0L : e.getValue();
                if (qty <= 0L) continue;
                sendCartBuy(entry, qty); // 逐项各自发起购买，异步、互不阻塞；成交结果由 ShopCartPurchaseResultPacket 回执驱动行状态，见反馈
                cartSettleBatch.add(stableId);
                fired++;
            }
            showMessage(Component.literal(fired > 0
                    ? "§b[山海商店] §a已对 " + fired + " 件候选逐项发起结算，稍候在购物车面板查看每项结果"
                    : "§c[山海商店] 购物车里的商品都还没加载完，稍后再试"));
            return true;
        }
        return true; // 图层内其余点击原地吞掉，不下穿到网格/详情
    }

    /** 描述详情大图层滚动条点击/拖拽起手：命中整条轨道即可（不用精确点在把手上），随即按该点位置跳转。 */
    private boolean descOverlayScrollbarClicked(double mx, double my, int[] r) {
        int barX = r[0] + r[2] - 6 - DESC_OVERLAY_SCROLLBAR_W;
        int barY = r[1] + 22, barH = r[3] - 30;
        if (mx < barX || mx > barX + DESC_OVERLAY_SCROLLBAR_W || my < barY || my > barY + barH) return false;
        draggingDescOverlayScroll = true;
        updateDescOverlayScrollFromDrag(my);
        return true;
    }

    /** 按拖拽点 Y 坐标反算描述详情大图层的滚动行数（把手中心跟随鼠标），无溢出时不生效。 */
    private void updateDescOverlayScrollFromDrag(double my) {
        int[] r = descOverlayBounds();
        List<net.minecraft.util.FormattedCharSequence> lines = descOverlayLines(r);
        int maxScroll = descOverlayMaxScroll(r, lines.size());
        if (maxScroll <= 0) { descOverlayScroll = 0; return; }
        int visible = descOverlayVisibleLines(r);
        int barY = r[1] + 22, barH = r[3] - 30;
        int thumbH = Math.max(10, barH * visible / lines.size());
        double usable = Math.max(1, barH - thumbH);
        double rel = (my - barY - thumbH / 2.0) / usable;
        descOverlayScroll = (int) Math.round(Math.max(0.0, Math.min(1.0, rel)) * maxScroll);
    }

    private void drawGrid(GuiGraphics g, int mx, int my) {
        int cols = gridColumns();
        int gx = listLeft(), gy = contentTop(), gw = listWidth(), gh = contentHeight();

        // 像素级平滑滚动：内容整体按 -scroll 平移，用 scissor 裁到网格矩形内。
        enableGridScissor(g, gx, gy, gx + gw, gy + gh);
        ShopGridViewport.Range range = visibleGridRange();
        for (int idx = range.fromInclusive(); idx < range.toExclusive(); idx++) {
            int col = idx % cols;
            int row = idx / cols;
            int cy = cellY(row);
            if (cy + CELL_H <= gy || cy >= gy + gh) continue; // 只画与网格相交的 cell
            ShopEntry entry = visibleEntry(idx);
            if (entry == null) drawLoadingCell(g, visibleEntryKeys.get(idx), cellX(col), cy);
            else drawCell(g, entry, cellX(col), cy, mx, my);
        }
        g.disableScissor();

        drawGridScrollbar(g, gx, gy, gw, gh, cols, mx, my);
    }

    /** 未收到完整 JSON 时只画轻量占位，不提前创建 ItemStack 或访问物品模型。 */
    private void drawLoadingCell(GuiGraphics g, long entryKey, int cx, int cy) {
        renderBox(g, cx, cy, CELL_W, CELL_H, GREEN_DARK, ROW_BG);
        int slotX = cx + 3, slotY = cy + 3;
        renderItemCheckerSlot(g, slotX, slotY);
        com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest.Stub stub =
                ClientShopCatalog.stub(entryKey);
        String label = "加载中…";
        if (stub != null && !stub.displayName().isEmpty()) label = stub.displayName();
        else if (stub != null && !stub.goodsIds().isEmpty()) label = stub.goodsIds().get(0);
        g.drawString(this.font, GuiRenderUtil.trimText(this.font, label, CELL_W - 29),
                slotX + 23, cy + 4, GRAY, true);
        if (stub != null && ClientShopFavorites.contains(stub.stableId())) {
            g.drawString(this.font, "§e★", cx + CELL_W - 10, cy + 14, GOLD, false);
        }
        renderNumberBar(g, cx + 2, cy + CELL_H - 13, 34);
        g.drawString(this.font, "§8加载中", cx + 5, cy + CELL_H - 11, GRAY, false);
    }

    /**
     * 网格右侧滚动条：轨道常驻绘制（哪怕当前条目还没溢出），为后续商品变多做好准备；
     * 有溢出时轨道上叠一段可拖拽把手（按内容比例算高度），支持点击/拖拽跳转，不再只能滚轮翻页。
     */
    private void drawGridScrollbar(GuiGraphics g, int gx, int gy, int gw, int gh, int cols, int mx, int my) {
        int barX = gridScrollbarX();
        g.fill(barX, gy, barX + GRID_SCROLLBAR_W, gy + gh, NUMBER_BAR_BG);
        int maxScroll = maxGridScroll();
        if (maxScroll <= 0) return; // 未溢出：只留轨道底色占位，不画把手
        int rows = (visibleEntryKeys.size() + cols - 1) / cols;
        int contentH = rows * ROW_STRIDE;
        int barH = Math.max(10, gh * gh / contentH);
        int barY = gy + (gh - barH) * scroll / maxScroll;
        boolean hv = draggingGridScroll || GuiRenderUtil.isHovering(mx, my, barX, barY, GRID_SCROLLBAR_W, barH);
        g.fill(barX, barY, barX + GRID_SCROLLBAR_W, barY + barH, hv ? CYAN : GOLD);
    }

    /** 网格滚动条点击/拖拽起手：命中整条轨道即可（不用精确点在把手上），随即按该点位置跳转。 */
    private boolean gridScrollbarClicked(double mx, double my) {
        int gy = contentTop(), gh = contentHeight();
        int barX = gridScrollbarX();
        if (mx < barX || mx > barX + GRID_SCROLLBAR_W || my < gy || my > gy + gh) return false;
        draggingGridScroll = true;
        updateGridScrollFromDrag(my);
        return true;
    }

    /** 按拖拽点的 Y 坐标反算 scroll（把手中心跟随鼠标），无溢出时不生效。 */
    private void updateGridScrollFromDrag(double my) {
        int maxScroll = maxGridScroll();
        if (maxScroll <= 0) { scroll = 0; return; }
        int gy = contentTop(), gh = contentHeight();
        int cols = gridColumns();
        int rows = (visibleEntryKeys.size() + cols - 1) / cols;
        int contentH = rows * ROW_STRIDE;
        int barH = Math.max(10, gh * gh / contentH);
        double usable = Math.max(1, gh - barH);
        double rel = (my - gy - barH / 2.0) / usable;
        scroll = (int) Math.round(Math.max(0.0, Math.min(1.0, rel)) * maxScroll);
    }

    /**
     * 网格格渲染缓存：{@link ShopEntry#goodsDisplayName}/{@link ShopEntry#makeGoodsStack}/成本主成分
     * 文本+图标——这些每帧都在算但对同一个 ShopEntry 对象结果恒定，商品一多（几百上千条）逐帧重算就是
     * 打开/滚动商店卡顿的元凶。按条目对象身份缓存一次即可；用 WeakHashMap 是因为编辑/新增商品在
     * {@link ShopConfig} 里永远是造一个全新 ShopEntry 对象替换旧的（见 replaceEntry/addEntry），
     * 新对象天然缓存未命中会自动重算，旧对象没了强引用后随 GC 自动清掉，不用手动失效。
     * 静态字段=跨开关屏幕复用，不止省一帧。截断宽度对任意格恒定（cx 无关，见 {@link #buildCellCache}
     * 推导），故可以脱离具体格坐标缓存。
     */
    private static final class CellCache {
        final ItemStack goodsStack; // 无自定义显示图标、主商品非流体时用；否则为 null（流体走 goodsFluid，组合/自定义走 renderIconComposite）
        final net.minecraft.world.level.material.Fluid goodsFluid; // 主商品是流体时非空（同 renderFluidIcon 配套）
        final String trimmedName;
        final String amtText;
        final ItemStack costIcon;
        final boolean sparkPrimary;
        final boolean euPrimary;
        final boolean fluidPrimary;
        final int extra;

        CellCache(ItemStack goodsStack, net.minecraft.world.level.material.Fluid goodsFluid, String trimmedName,
                  String amtText, ItemStack costIcon, boolean sparkPrimary, boolean euPrimary, boolean fluidPrimary, int extra) {
            this.goodsStack = goodsStack;
            this.goodsFluid = goodsFluid;
            this.trimmedName = trimmedName;
            this.amtText = amtText;
            this.costIcon = costIcon;
            this.sparkPrimary = sparkPrimary;
            this.euPrimary = euPrimary;
            this.fluidPrimary = fluidPrimary;
            this.extra = extra;
        }
    }

    private static final Map<ShopEntry, CellCache> CELL_CACHE = new java.util.WeakHashMap<>();

    private CellCache cellCacheFor(ShopEntry entry) {
        CellCache cached = CELL_CACHE.get(entry);
        if (cached != null) return cached;
        CellCache built = buildCellCache(entry);
        CELL_CACHE.put(entry, built);
        return built;
    }

    /** 截断宽度推导：rightX-textX = (cx+CELL_W-3)-(cx+3+23) = CELL_W-29，与格子的 cx 无关。 */
    private CellCache buildCellCache(ShopEntry entry) {
        boolean noCustomIcons = entry.effectiveIcons().isEmpty();
        ItemStack goodsStack = noCustomIcons && !entry.isPrimaryGoodsFluid() ? entry.displayGoodsStack() : null;
        net.minecraft.world.level.material.Fluid goodsFluid = noCustomIcons && entry.isPrimaryGoodsFluid() ? entry.primaryGoodsFluid() : null;
        String trimmedName = GuiRenderUtil.trimText(this.font, entry.goodsDisplayName(), CELL_W - 29);
        ShopCost cost = entry.getCost();
        String amtText;
        ItemStack costIcon = null;
        boolean sparkPrimary = false, euPrimary = false, fluidPrimary = false;
        if (!cost.coins.isEmpty()) {
            Map.Entry<ResourceLocation, java.math.BigInteger> c0 = cost.coins.entrySet().iterator().next();
            amtText = formatBig(c0.getValue());
            costIcon = currencyStack(c0.getKey());
        } else if (cost.spark.signum() > 0) {
            amtText = formatBig(cost.spark);
            sparkPrimary = true;
        } else if (cost.eu.signum() > 0) {
            amtText = formatBig(cost.eu);
            euPrimary = true;
        } else if (!cost.physical.isEmpty()) {
            ExchangeEntry.Ingredient in0 = cost.physical.get(0);
            amtText = String.valueOf(in0.count);
            if (in0.isFluid) {
                fluidPrimary = true;
            } else {
                ItemStack unit0 = in0.makeUnitStack();
                if (!unit0.isEmpty()) costIcon = unit0;
            }
        } else {
            amtText = "免";
        }
        int extra = cost.componentCount() - 1;
        return new CellCache(goodsStack, goodsFluid, trimmedName, amtText, costIcon, sparkPrimary, euPrimary, fluidPrimary, extra);
    }

    /** KE 风格横向格：左棋盘物品槽 + 右侧名称/状态 + 底部价格数字条 + 货币小图标。 */
    private void drawCell(GuiGraphics g, ShopEntry entry, int cx, int cy, int mx, int my) {
        boolean sel = selected == entry;
        boolean hover = GuiRenderUtil.isHovering(mx, my, cx, cy, CELL_W, CELL_H);
        // 限时折扣：格子太小塞不下额外文字（见 CurrencyAtmScreen 教训，不硬挤新元素），改用边框/价格变色示意，
        // 具体折扣%和倒计时留给悬浮 tooltip（buildTooltip）和详情页（drawDetail）精确显示
        boolean onSale = entry.isDiscountActive();
        int border = sel ? GOLD : (onSale ? RED_DARK : GREEN_DARK);
        int fill = sel ? SELECT_BG : (hover ? ROW_HOVER : ROW_BG);
        renderBox(g, cx, cy, CELL_W, CELL_H, border, fill);
        if (sel) renderSelectionOutline(g, cx, cy, CELL_W, CELL_H);

        CellCache cc = cellCacheFor(entry);

        // 棋盘物品槽（20x20）：有自定义显示图标走组合渲染（1主+最多4附属角标），否则用缓存好的商品图标
        int slotX = cx + 3, slotY = cy + 3;
        renderItemCheckerSlot(g, slotX, slotY);
        List<ShopEntry.DisplayIcon> cellIcons = entry.effectiveIcons();
        if (!cellIcons.isEmpty()) {
            renderIconComposite(g, cellIcons, slotX, slotY);
        } else if (cc.goodsFluid != null) {
            renderFluidIcon(g, slotX + 2, slotY + 2, 16, cc.goodsFluid);
        } else {
            g.renderItem(cc.goodsStack, slotX + 2, slotY + 2);
            g.renderItemDecorations(this.font, cc.goodsStack, slotX + 2, slotY + 2);
        }

        // 商品名（右上，截断宽度对所有格恒定，缓存好的结果直接画）
        int textX = slotX + 23;
        g.drawString(this.font, cc.trimmedName, textX, cy + 4, WHITE, true);

        // 收藏角标（名称行与底部价格条之间的空白带，右对齐，不挤占任何已有元素）
        if (ClientShopFavorites.contains(entry.getStableId())) {
            g.drawString(this.font, "§e★", cx + CELL_W - 10, cy + 14, GOLD, false);
        }

        // 底部成本数字条 + 主成分图标 +「+K」（多元成本取主成分：币种 > 星火 > 实物；全部缓存好）
        int numX = cx + 2;
        int numY = cy + CELL_H - 13;
        int numW = Math.min(48, Math.max(22, this.font.width(cc.amtText) + 8));
        renderNumberBar(g, numX, numY, numW);
        g.drawString(this.font, cc.amtText, numX + 4, numY + 2, onSale ? RED : NUMBER_BAR_TEXT, false);
        int iconX = numX + numW + 2;
        if (cc.sparkPrimary) {
            g.drawString(this.font, "§e★", iconX + 1, numY + 2, GOLD, false);
        } else if (cc.euPrimary) {
            g.drawString(this.font, "§d⚡", iconX + 1, numY + 2, GOLD, false);
        } else if (cc.fluidPrimary) {
            g.drawString(this.font, "§b≈", iconX + 1, numY + 2, CYAN, false); // 流体主成分标记
        } else if (cc.costIcon != null) {
            renderCurrencyItem(g, cc.costIcon, iconX, numY - 2);
        }
        if (cc.extra > 0) {
            g.drawString(this.font, "§7+" + cc.extra, iconX + 15, numY + 2, GRAY, false);
        }
    }

    // ============ KE 风格原子绘制单元 ============

    /** 带边框的填充盒（外 1px border + 内 fill）。 */
    private static void renderBox(GuiGraphics g, int x, int y, int w, int h, int border, int fill) {
        g.fill(x, y, x + w, y + h, border);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
    }

    /** 20x20 淡色棋盘物品槽（仿 KE renderItemCheckerSlot）。 */
    private static void renderItemCheckerSlot(GuiGraphics g, int x, int y) {
        g.fill(x - 1, y - 1, x + 21, y + 21, -7697782);
        g.fill(x, y, x + 20, y + 20, -1644826);
        for (int yy = 0; yy < 20; yy += 5) {
            for (int xx = 0; xx < 20; xx += 5) {
                int color = (((xx / 5) + (yy / 5)) & 1) == 0 ? -723724 : -2302756;
                g.fill(x + xx, y + yy, Math.min(x + xx + 5, x + 20), Math.min(y + yy + 5, y + 20), color);
            }
        }
        // 立体边
        g.fill(x, y, x + 20, y + 1, -1);
        g.fill(x, y + 19, x + 20, y + 20, -6645094);
        g.fill(x, y, x + 1, y + 20, -1);
        g.fill(x + 19, y, x + 20, y + 20, -6645094);
    }

    /** 价格数字条底框。 */
    private static void renderNumberBar(GuiGraphics g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 11, NUMBER_BAR_BORDER);
        g.fill(x + 1, y + 1, x + w - 1, y + 10, NUMBER_BAR_BG);
    }

    /** 四角角标位置（相对 20x20 图标槽左上角）：左上/右上/左下/右下。 */
    private static final int[][] ICON_BADGE_CORNERS = {{0, 0}, {12, 0}, {0, 12}, {12, 12}};

    /**
     * 自定义显示图标组合渲染：1 主图标（正常大小居中）+ 最多 4 个附属角标（0.5x 缩放，叠在四角）。
     * 用于「多元组合商品」（如生产核废料的无限盘）一眼看出成分，不必读容易被截断的长名字。
     * 物品/贴图图标可混搭：物品走真实物品渲染，贴图走 letterbox blit。
     * @param slotX/slotY 20x20 棋盘槽左上角坐标（同 {@link #renderItemCheckerSlot}）。
     */
    private void renderIconComposite(GuiGraphics g, List<ShopEntry.DisplayIcon> icons, int slotX, int slotY) {
        ShopEntry.DisplayIcon main = icons.get(0);
        renderIconAt(g, main, slotX + 2, slotY + 2, 16, true);
        if (icons.size() == 1) return;
        int badges = Math.min(icons.size() - 1, ICON_BADGE_CORNERS.length);
        for (int i = 0; i < badges; i++) {
            ShopEntry.DisplayIcon b = icons.get(1 + i);
            renderIconAt(g, b, slotX + ICON_BADGE_CORNERS[i][0], slotY + ICON_BADGE_CORNERS[i][1], 8, false);
        }
    }

    /** 单个显示图标渲染（物品/流体/贴图三选一）。size 为目标像素边长，decorations 只对物品主图标生效（数量/耐久角标）。 */
    private void renderIconAt(GuiGraphics g, ShopEntry.DisplayIcon icon, int x, int y, int size, boolean decorations) {
        if (icon.isTexture()) {
            EditorWidgets.textureThumb(g, x, y, size, icon.texture());
            return;
        }
        if (icon.isFluid()) {
            renderFluidIcon(g, x, y, size, icon.fluid());
            return;
        }
        ItemStack stack = icon.item();
        if (size == 16) {
            g.renderItem(stack, x, y);
            if (decorations) g.renderItemDecorations(this.font, stack, x, y);
            return;
        }
        float scale = size / 16f;
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1f);
        g.renderItem(stack, 0, 0);
        g.pose().popPose();
    }

    /** 货币小图标（0.75x 缩放）。 */
    private void renderCurrencyItem(GuiGraphics g, ItemStack stack, int x, int y) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75f, 0.75f, 1f);
        g.renderItem(stack, 0, 0);
        g.pose().popPose();
    }

    /** KE 风格按钮：外框 + 内填充（hover 变色）+ 居中文字。 */
    private void renderButton(GuiGraphics g, int x, int y, int w, int h, boolean hover, String text, int border, int textColor) {
        g.fill(x, y, x + w, y + h, border);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hover ? BUTTON_HOVER : BUTTON_BG);
        g.drawCenteredString(this.font, text, x + w / 2, y + (h - 8) / 2, textColor);
    }

    /** 选中格的青色四边描边。 */
    private static void renderSelectionOutline(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 1, y - 1, x + w + 1, y, CYAN);
        g.fill(x - 1, y + h, x + w + 1, y + h + 1, CYAN);
        g.fill(x - 1, y, x, y + h, CYAN);
        g.fill(x + w, y, x + w + 1, y + h, CYAN);
    }

    /** KE 风格紧凑数字：1K+/1M+/1B+/1T+/1Qa+/1Qi+。 */
    private static String formatCompact(long value) {
        long v = Math.max(0L, value);
        if (v >= 1_000_000_000_000_000_000L) return (v / 1_000_000_000_000_000_000L) + "Qi+";
        if (v >= 1_000_000_000_000_000L) return (v / 1_000_000_000_000_000L) + "Qa+";
        if (v >= 1_000_000_000_000L) return (v / 1_000_000_000_000L) + "T+";
        if (v >= 1_000_000_000L) return (v / 1_000_000_000L) + "B+";
        if (v >= 1_000_000L) return (v / 1_000_000L) + "M+";
        if (v >= 1_000L) return (v / 1_000L) + "K+";
        return String.valueOf(v);
    }

    /** 步进按钮上的数字（1000 → "1k" 省空间）。 */
    private static String compactStep(long step) {
        return step >= 1000 ? (step / 1000) + "k" : String.valueOf(step);
    }

    /** BigInteger 紧凑显示：能装进 long 走 formatCompact；否则用科学计数近似。 */
    private static String formatBig(java.math.BigInteger v) {
        if (v.signum() < 0) return "0";
        if (v.bitLength() < 63) return formatCompact(v.longValue());
        String s = v.toString();
        return s.charAt(0) + "." + s.substring(1, Math.min(3, s.length())) + "e" + (s.length() - 1);
    }

    /** 精确数量（带千分位，如 10,000）——供悬停 tooltip 显示具体数字，不做缩写。 */
    private static String groupBig(java.math.BigInteger v) {
        if (v == null || v.signum() < 0) return "0";
        return String.format("%,d", v);
    }

    private void drawDetail(GuiGraphics g, int mx, int my) {
        previewHoverName = null; // 每帧先清，命中预览格再置
        previewHoverExtra = null; // 每帧先清，命中预览格再置
        detailHoverStack = null; // 每帧先清，命中大图标再置
        descExpandVisible = false; // 每帧先清，描述非空才置
        int dx = detailX();
        int dy = contentTop();
        int dh = contentHeight();
        // 面板背景（KE 风格描边盒）
        renderBox(g, dx, dy, DETAIL_W, dh, GOLD_DARK, BOX_BG);

        if (selected == null) {
            g.drawCenteredString(this.font, "§7← 请选择商品", dx + DETAIL_W / 2, dy + 18, GRAY);
            return;
        }

        int cx = dx + 8;
        // 棋盘物品槽 + 图标（有自定义显示图标走组合渲染；流体主商品无 ItemStack 悬停 tooltip，见下方）
        renderItemCheckerSlot(g, cx, dy + 10);
        ItemStack goodsIcon = selected.displayGoodsStack();
        List<ShopEntry.DisplayIcon> detailIcons = selected.effectiveIcons();
        if (!detailIcons.isEmpty()) {
            renderIconComposite(g, detailIcons, cx, dy + 10);
        } else if (selected.isPrimaryGoodsFluid()) {
            renderFluidIcon(g, cx + 2, dy + 12, 16, selected.primaryGoodsFluid());
        } else {
            g.renderItem(goodsIcon, cx + 2, dy + 12);
            g.renderItemDecorations(this.font, goodsIcon, cx + 2, dy + 12);
        }
        if (GuiRenderUtil.isHovering(mx, my, cx, dy + 10, 20, 20)) detailHoverStack = goodsIcon;
        // 名称
        int textX = cx + 24;
        g.drawString(this.font, GuiRenderUtil.trimText(this.font, selected.goodsDisplayName(), dx + DETAIL_W - 6 - textX),
                textX, dy + 10, WHITE, true);
        String countLine = selected.hasMultipleGoods()
                ? "§7组合商品 §f" + selected.getGoodsList().size() + " §7种"
                : "§7每份 §f" + selected.getGoodsCount() + " §7个";
        if (selected.isLimited()) {
            countLine += " §7剩§d" + formatBig(java.math.BigInteger.valueOf(selected.getRemainingUses())) + "§7次";
        }
        g.drawString(this.font, GuiRenderUtil.trimText(this.font, countLine, dx + DETAIL_W - 6 - textX), textX, dy + 24, GRAY, true);

        // 成本（多元，青）——超宽截断，全量见 tooltip / 预计行。限时折扣生效时原价→折后价+倒计时挤进同一行，
        // 不另起一行（详情页下面一串固定 Y 坐标的内容，插一行要连带改一串偏移，风险不值得，见 CurrencyAtmScreen 的教训）
        String costLabel = selected.isDiscountActive()
                ? "§7成本: §8" + costInline(selected.getCost()) + " §a→ " + costInline(selected.getEffectiveCost())
                        + " §6(-" + selected.getDiscountPercent() + "% 剩" + formatDuration(selected.discountRemainingMs() / 1000L) + ")"
                : "§7成本: " + costInline(selected.getCost());
        g.drawString(this.font, GuiRenderUtil.trimText(this.font, costLabel,
                dx + DETAIL_W - 6 - cx), cx, dy + 42, CYAN, true);

        // 数量标签 + AE 风格步进按钮（文本框 amountBox 由 initScaled 定位、super.render 绘制）
        g.drawString(this.font, (mode == Mode.BUY ? "§7购买次数（可输入）:" : "§7出售次数（可输入）:"), cx, dy + 56, WHITE, true);
        // 三排步进：+1 +10 +100 +1000 / -1 -10 -100 -1000 / ×10 ×100 ÷10 ÷100
        int stepY1 = dy + 82, stepY2 = dy + 96, stepY3 = dy + 110;
        long[] steps = {1, 10, 100, 1000};
        int bw = (DETAIL_W - 16 - 9) / 4; // 4 按钮 + 3 间隙
        for (int i = 0; i < 4; i++) {
            int bx = cx + i * (bw + 3);
            drawButton(g, bx, stepY1, bw, 12, "§a+" + compactStep(steps[i]), mx, my);
            drawButton(g, bx, stepY2, bw, 12, "§c-" + compactStep(steps[i]), mx, my);
        }
        String[] scaleLabels = {"§b×10", "§b×100", "§6÷10", "§6÷100"};
        for (int i = 0; i < 4; i++) {
            int bx = cx + i * (bw + 3);
            drawButton(g, bx, stepY3, bw, 12, scaleLabels[i], mx, my);
        }

        // 预计（BigInteger 防溢出，紧凑显示）；成本多元 → 显示整条成本 ×次数
        java.math.BigInteger amt = java.math.BigInteger.valueOf(amount);
        java.math.BigInteger total = java.math.BigInteger.valueOf(selected.getGoodsCount()).multiply(amt);
        // 折扣只影响买价预览，不影响出售能拿回多少（出售不是「反向打折」，见 ShopPurchase#affordAndDeduct 同一口径）
        ShopCost dcost = mode == Mode.BUY ? selected.getEffectiveCost() : selected.getCost();
        int py = dy + 128;
        int costTrimW = dx + DETAIL_W - 6 - cx;
        String key = WalletAccountAPI.purchaseKey(selected.getGoodsId(), selected.getCategory());
        long bought = ClientWalletAccount.getPurchaseCount(key);
        String txLine = "§7交易次数: §f" + formatBig(amt) + "  §7已购买: §6" + formatBig(java.math.BigInteger.valueOf(bought));
        String tradeModeTip = switch (selected.getTradeMode()) {
            case BUY_ONLY -> "  §b[仅购买]";
            case SELL_ONLY -> "  §e[仅出售]";
            default -> "";
        };
        g.drawString(this.font, GuiRenderUtil.trimText(this.font, txLine + tradeModeTip, costTrimW), cx, py, WHITE, true);
        // 周期限购占两行：配置一行 + 倒计时一行。倒计时读的是服务端同步下来的「开窗锚点」（见 ShopPeriodLimiter），
        // 锚点=-1（从没消费过/上一窗口已过期）就是"限额充足无需刷新"，不瞎算无意义的假倒计时。
        int contentY = py;
        if (selected.isPeriodLimited()) {
            long periodTicks = selected.getPeriodTicks();
            String periodCfgLine = "§d周期限购: §f" + (periodTicks / 20L) + "秒限" + formatBig(java.math.BigInteger.valueOf(selected.getPeriodLimit())) + "次";
            g.drawString(this.font, GuiRenderUtil.trimText(this.font, periodCfgLine, costTrimW), cx, py + 12, WHITE, true);
            long anchor = ClientWalletAccount.getPeriodAnchor(key);
            net.minecraft.client.multiplayer.ClientLevel lvl = Minecraft.getInstance().level;
            long gameTime = lvl != null ? lvl.getGameTime() : 0L;
            long remainingTicks = anchor >= 0L ? anchor + periodTicks - gameTime : -1L;
            if (remainingTicks > 0L) {
                g.drawString(this.font, "§7剩余刷新: §f" + (remainingTicks / 20L) + "秒", cx, py + 24, WHITE, true);
            } else {
                g.drawString(this.font, "§a限额充足无需刷新", cx, py + 24, GREEN, true);
            }
            contentY = py + 24;
        }
        // 组合商品（非奖励表模式）用图形化「获得预览」替代原来一行会截断的文字列表（见反馈）；
        // 固定占一行的高度（drawRewardPreview 自身保证），后面 hasPhysical 提示行/花费预览起始 Y 按此整体下移
        boolean comboRewardPreview = mode == Mode.BUY && selected.getRewardMode() == ShopEntry.RewardMode.NONE
                && selected.hasMultipleGoods();
        if (mode == Mode.BUY) {
            g.drawString(this.font, GuiRenderUtil.trimText(this.font, "§7预计消耗: " + costInlineTimes(dcost, amount), costTrimW), cx, contentY + 12, CYAN, true);
            ShopEntry.RewardMode rm = selected.getRewardMode();
            int physicalHintY;
            if (comboRewardPreview) {
                drawRewardPreview(g, cx, contentY + 24, selected.getGoodsList(), amount, DETAIL_W - 16, mx, my);
                physicalHintY = contentY + 66;
            } else {
                String getLine = switch (rm) {
                    case CHOICE -> "§7预计获得: §d自选奖励 §7（购买前先选 1 项）";
                    case RANDOM -> "§7预计获得: §d随机奖励 §7（从 " + selected.getRewardPool().size() + " 项中按权重抽 1）";
                    case ALL -> "§7预计获得: §d全部奖励 §7（一次交付 " + selected.getRewardPool().size() + " 项全部）";
                    case FTBQ -> switch (selected.getFtbqSubMode()) {
                        case CHOICE -> "§7预计获得: §9FTBQ表·自选 §7（购买前先选 1 项）";
                        case ALL -> "§7预计获得: §9FTBQ表·全部 §7（一次交付表内所有物品奖励）";
                        default -> "§7预计获得: §9FTBQ表·随机 §7（按表内权重随机抽取）";
                    };
                    default -> "§7预计获得: §e" + formatBig(total) + " §7个"; // hasMultipleGoods() 已被上面 comboRewardPreview 分流
                };
                g.drawString(this.font, GuiRenderUtil.trimText(this.font, getLine, costTrimW), cx, contentY + 24, GREEN, true);
                physicalHintY = contentY + 36;
            }
            if (dcost.hasPhysical()) g.drawString(this.font, "§8含实物成本：物品需在背包 / 流体需绑定 AE", cx, physicalHintY, GRAY, true);
        } else if (selected.hasMultipleGoods()) {
            g.drawString(this.font, "§c组合商品，不支持出售", cx, contentY + 12, DEEP_RED, true);
        } else {
            g.drawString(this.font, "§7预计消耗: §e" + formatBig(total) + " §7个商品", cx, contentY + 12, CYAN, true);
            if (dcost.hasPhysical()) {
                g.drawString(this.font, "§c含实物成本，不支持出售", cx, contentY + 24, DEEP_RED, true);
            } else {
                g.drawString(this.font, GuiRenderUtil.trimText(this.font, "§7预计获得: " + costInlineTimes(dcost, amount), costTrimW), cx, contentY + 24, GREEN, true);
            }
            int held = ShopPurchase.countItem(Minecraft.getInstance().player, selected.getGoodsItem());
            g.drawString(this.font, "§7背包持有: " + (held > 0 ? "§a" : "§c") + held, cx, contentY + 36, held > 0 ? GREEN : DEEP_RED, true);
        }

        // 确认按钮（KE 风格 border+fill，颜色随可交易性）
        int btnY = dy + dh - 24;
        // GuideME 集成：查一次商品清单是否有指南页命中，selected 没变就不重复扫描所有已装指南
        if (guideHitsFor != selected) {
            guideHitsFor = selected;
            guideHits = com.dishanhai.gt_shanhai.client.shop.ShopGuideLookup.findGuideHits(selected.getGoodsList());
        }
        int guideRowH = guideHits.isEmpty() ? 0 : 12;
        // 跳转入口（仿 FTBQ 隐藏任务）：条目配了 linkTo 且能解析到目标商品才显示，占一行固定空间
        linkVisible = false;
        linkTargetKey = selected.hasLinkTarget()
                ? ClientShopCatalog.linkedEntryKey(selected.getLinkTo()) : -1L;
        linkTarget = linkTargetKey >= 0L ? ClientShopCatalog.get(linkTargetKey) : null;
        int linkRowH = linkTarget != null ? 12 : 0;
        // 「补齐全部缺口」：只在确定（非"加载中"）有不够的项、且开着 AE 模式时出现——AE 自动合成只对着 AE 网络补，
        // 关了 AE 模式点这个没意义（缺口判定本身也不含 AE 那部分，见 hasCostShortfall）。
        boolean showAutoCraftBtn = mode == Mode.BUY && aeMode && hasCostShortfall(dcost, amount, selectedEntryKey, aeMode);
        int autoCraftRowH = showAutoCraftBtn ? 12 : 0;
        // 前置任务跳转行：条目配了前置任务就占一行（无论能否解析到，未同步/已删都要给玩家一个可见提示）
        prereqQuest = selected.hasPrerequisiteQuest()
                ? com.dishanhai.gt_shanhai.client.shop.ShopFtbqPrereqLookup.resolve(selected.getPrerequisiteQuestId()) : null;
        prereqQuestCompleted = com.dishanhai.gt_shanhai.client.shop.ShopFtbqPrereqLookup.isCompleted(prereqQuest);
        int prereqRowH = selected.hasPrerequisiteQuest() ? 12 : 0;
        // 编辑/删除按钮行数：编辑条目、删除此商品都折进了编辑模式，catalogEditUnlocked 时一起出 2 行
        int editRowsOffset = catalogEditUnlocked ? 48 : 6;
        int lowerBottom = btnY - editRowsOffset - linkRowH - guideRowH - autoCraftRowH - prereqRowH; // 底部按钮上沿（跳转/指南/补齐缺口/前置任务各让一行）

        // 图形化花费预览（图标 + 数量）占步进/预计与底部按钮之间的空白区。
        // 起点须跟着 contentY 走（原来硬编码 py+50，周期限购把上面内容顶下去后两者间距只剩 2px，字重叠成一坨，见反馈）；
        // 组合商品用了图形化「获得预览」多占一整行高度，起点要再往下让一行（见 comboRewardPreview）
        maybeRequestCostPreview(dcost);
        int costPreviewY = contentY + (comboRewardPreview ? 80 : 50);
        int cursorY = drawCostPreview(g, cx, costPreviewY, dcost, amount, DETAIL_W - 16, lowerBottom, mx, my);

        if (linkTarget != null) {
            linkVisible = true;
            linkX = cx; linkY = lowerBottom + 2; linkW = DETAIL_W - 16; linkH = 10;
            boolean linkHover = GuiRenderUtil.isHovering(mx, my, linkX, linkY, linkW, linkH);
            g.drawString(this.font, (linkHover ? "§b§n" : "§9§n") + "→ 跳转: " + linkTarget.goodsDisplayName(), linkX, linkY, linkHover ? CYAN : GOLD, true);
        }

        // GuideME 跳转入口：1 条命中直接给跳转行（同「跳转」样式），2 条以上给「指南详情」按钮开大图层列出全部
        guideLinkVisible = false;
        guideDetailBtnVisible = false;
        if (guideHits.size() == 1) {
            com.dishanhai.gt_shanhai.client.shop.ShopGuideLookup.GuideHit hit = guideHits.get(0);
            guideLinkVisible = true;
            guideLinkHit = hit;
            guideLinkX = cx; guideLinkY = lowerBottom + 2 + linkRowH; guideLinkW = DETAIL_W - 16; guideLinkH = 10;
            boolean guideHover = GuiRenderUtil.isHovering(mx, my, guideLinkX, guideLinkY, guideLinkW, guideLinkH);
            String guideLabel = "→ 指南: " + hit.item().getHoverName().getString();
            g.drawString(this.font, (guideHover ? "§b§n" : "§9§n") + GuiRenderUtil.trimText(this.font, guideLabel, guideLinkW),
                    guideLinkX, guideLinkY, guideHover ? CYAN : GOLD, true);
        } else if (guideHits.size() > 1) {
            guideDetailBtnVisible = true;
            guideDetailBtnX = cx; guideDetailBtnY = lowerBottom + 2 + linkRowH; guideDetailBtnW = DETAIL_W - 16; guideDetailBtnH = 10;
            drawButton(g, guideDetailBtnX, guideDetailBtnY, guideDetailBtnW, guideDetailBtnH,
                    "§d指南详情(" + guideHits.size() + ")", mx, my);
        }

        // 「补齐全部缺口」：花费预览格有确定缺口时出现，点了向服务端起一轮 AE 自动合成计算
        autoCraftBtnVisible = showAutoCraftBtn;
        if (showAutoCraftBtn) {
            autoCraftBtnX = cx; autoCraftBtnY = lowerBottom + 2 + linkRowH + guideRowH; autoCraftBtnW = DETAIL_W - 16; autoCraftBtnH = 10;
            drawButton(g, autoCraftBtnX, autoCraftBtnY, autoCraftBtnW, autoCraftBtnH, "§b⚙ 补齐全部缺口（AE自动合成）", mx, my);
        }

        // 前置任务：条目配了前置任务才显示，点击跳到 FTBQ 任务书对应任务；已完成显示绿字，未完成/未解析到显示红字
        prereqLinkVisible = selected.hasPrerequisiteQuest();
        if (prereqLinkVisible) {
            prereqLinkX = cx; prereqLinkY = lowerBottom + 2 + linkRowH + guideRowH + autoCraftRowH; prereqLinkW = DETAIL_W - 16; prereqLinkH = 10;
            boolean prereqHover = GuiRenderUtil.isHovering(mx, my, prereqLinkX, prereqLinkY, prereqLinkW, prereqLinkH);
            String prereqLabel = prereqQuest == null
                    ? "→ 前置任务: §c未找到/未同步 #" + selected.getPrerequisiteQuestId()
                    : "→ 前置任务: " + (prereqQuestCompleted ? "§a[已完成] " : "§c[未完成] ") + prereqQuest.getRawTitle();
            int prereqColor = prereqQuest == null ? DEEP_RED : (prereqQuestCompleted ? (prereqHover ? CYAN : GREEN) : (prereqHover ? CYAN : GOLD));
            g.drawString(this.font, (prereqHover ? "§n" : "") + GuiRenderUtil.trimText(this.font, prereqLabel, prereqLinkW),
                    prereqLinkX, prereqLinkY, prereqColor, true);
        }

        // 描述（玩家自定义，自动换行；接在预览下方）
        String desc = selected.getDescription();
        if (desc != null && !desc.isEmpty() && cursorY + 12 < lowerBottom) {
            g.drawString(this.font, "§6描述:", cx, cursorY + 2, GOLD, true);
            // 展开详情：FTBQ 风格大图层，描述较长被本地窄栏截断时用得上
            int expandW = 52;
            descExpandVisible = true;
            descExpandX = cx + DETAIL_W - 16 - expandW;
            descExpandY = cursorY - 1;
            descExpandW = expandW;
            descExpandH = 10;
            drawButton(g, descExpandX, descExpandY, descExpandW, descExpandH, "§b展开详情", mx, my);
            int ly = cursorY + 13;
            for (net.minecraft.util.FormattedCharSequence line
                    : this.font.split(Component.literal("§7" + GuiRenderUtil.translateAmpCodes(desc)), DETAIL_W - 16)) {
                if (ly + 9 > lowerBottom) break;
                g.drawString(this.font, line, cx, ly, GRAY, true);
                ly += 9;
            }
        }
        // 前置任务未配置视为满足；配置了则须客户端已解析到且已完成——跟服务端 doBuy 的门槛口径一致（见反馈：
        // 花费预览格全绿时按钮不能还显示红，前置任务同理，不能光看成本够不够）
        boolean prereqSatisfiedClient = !selected.hasPrerequisiteQuest() || prereqQuestCompleted;
        boolean canTrade = mode == Mode.BUY
                ? (canAffordClient(selected.getEffectiveCost(), amount) && prereqSatisfiedClient)
                : (!selected.getCost().hasPhysical() && !selected.hasMultipleGoods()
                    && ShopPurchase.countItem(Minecraft.getInstance().player, selected.getGoodsItem()) >= selected.getGoodsCount());
        boolean btnHover = hit(mx, my, cx, btnY, DETAIL_W - 16, 20);
        renderButton(g, cx, btnY, DETAIL_W - 16, 20, btnHover,
                mode == Mode.BUY ? "§a确认购买" : "§e确认出售",
                canTrade ? GREEN_DARK : DEEP_RED, canTrade ? (mode == Mode.BUY ? GREEN : GOLD) : DEEP_RED);

        // 编辑/删除按钮：都折进了编辑模式（catalogEditUnlocked），没开时两行一起隐藏
        if (catalogEditUnlocked) {
            boolean editHover = hit(mx, my, cx, btnY - 44, DETAIL_W - 16, 18);
            renderButton(g, cx, btnY - 44, DETAIL_W - 16, 18, editHover, "§b编辑条目", GOLD_DARK, CYAN);
            // 误触防护：第一次点「删除此商品」只进入 3 秒确认窗口（按钮变金边+闪烁警示色），窗口内对同一条目
            // 再点一次才真正发删除包；换条目/超时都会自动退回未确认态，见 universalMouseClicked 里的对应逻辑
            boolean delArmed = pendingDeleteEntry == selected
                    && System.currentTimeMillis() - pendingDeleteArmedAtMs < DELETE_CONFIRM_WINDOW_MS;
            boolean delHover = hit(mx, my, cx, btnY - 22, DETAIL_W - 16, 18);
            String delLabel = delArmed ? "§c⚠再点一次确认删除！" : "§c删除此商品";
            int delBorder = delArmed ? GOLD : RED_DARK;
            int delText = delArmed && (System.currentTimeMillis() / 300L % 2 == 0) ? WHITE : RED;
            renderButton(g, cx, btnY - 22, DETAIL_W - 16, 18, delHover, delLabel, delBorder, delText);
        }
    }

    // ============ 交互 ============

    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        // 右键快捷菜单打开时拦截全部点击：点某一行执行该动作并关闭，点菜单外任意处只关闭，不冒泡给下层控件
        if (ctxMenuOpen) {
            return handleContextMenuClick(mx, my);
        }
        // 购物车大图层打开时拦截全部点击：关闭/数量步进/删除/结算，其余点击原地吞掉，不下穿到网格/详情
        if (cartOverlayOpen) {
            return handleCartOverlayClick(mx, my);
        }
        // 描述详情大图层打开时拦截全部点击：点关闭按钮或图层外任意处都关闭，图层内右侧滚动条起手拖拽，其余点击原地吞掉
        if (descOverlayOpen) {
            int[] r = descOverlayBounds();
            if (!hit(mx, my, r[0], r[1], r[2], r[3]) || hit(mx, my, descOverlayCloseX(r), descOverlayCloseY(r), DESC_OVERLAY_CLOSE_W, DESC_OVERLAY_CLOSE_H)) {
                descOverlayOpen = false;
                return true;
            }
            if (descOverlayScrollbarClicked(mx, my, r)) return true;
            return true;
        }
        // GuideME「指南详情」大图层打开时同样拦截全部点击：点某一行直接打开该指南并关闭图层，
        // 点关闭按钮或图层外关闭图层，其余点击原地吞掉
        if (guideOverlayOpen) {
            int[] r = guideOverlayBounds();
            if (!hit(mx, my, r[0], r[1], r[2], r[3]) || hit(mx, my, guideOverlayCloseX(r), guideOverlayCloseY(r), DESC_OVERLAY_CLOSE_W, DESC_OVERLAY_CLOSE_H)) {
                guideOverlayOpen = false;
                return true;
            }
            int visible = guideOverlayVisibleRows(r);
            int rowY0 = r[1] + 22;
            for (int i = 0; i < visible; i++) {
                int idx = guideOverlayScroll + i;
                if (idx >= guideHits.size()) break;
                int ry = rowY0 + i * GUIDE_OVERLAY_ROW_H;
                if (hit(mx, my, r[0] + 6, ry, r[2] - 12, GUIDE_OVERLAY_ROW_H)) {
                    com.dishanhai.gt_shanhai.client.shop.ShopGuideLookup.open(Minecraft.getInstance().player, guideHits.get(idx));
                    guideOverlayOpen = false;
                    return true;
                }
            }
            return true;
        }
        // 撤销删除按钮（悬浮在面板上，优先于其他点击判定）
        if (undoDeleteLabel != null && System.currentTimeMillis() < undoDeleteUntilMs) {
            int[] b = undoDeleteBtnBounds();
            if (hit(mx, my, b[0], b[1], b[2], b[3])) {
                send(ShopActionPacket.Action.UNDO_DELETE, null, 1L);
                undoDeleteLabel = null;
                return true;
            }
        }
        // 撤销排序按钮（悬浮在面板上，优先于其他点击判定）
        if (undoReorderLabel != null && System.currentTimeMillis() < undoReorderUntilMs) {
            int[] b = undoReorderBtnBounds();
            if (hit(mx, my, b[0], b[1], b[2], b[3])) {
                ShanhaiNetwork.CHANNEL.sendToServer(new com.dishanhai.gt_shanhai.network.ShopReorderPacket(
                        com.dishanhai.gt_shanhai.network.ShopReorderPacket.Action.UNDO, ClientShopCatalog.revision(), -1L));
                undoReorderLabel = null;
                return true;
            }
        }

        // 顶部买/卖页签：切换时打一条本地横幅提示当前模式，光靠页签颜色区分不够醒目，容易买卖模式点混
        if (hit(mx, my, buyTabX(), top + 6, BUY_TAB_W, TOP_BAR_H)) {
            if (mode != Mode.BUY) showMessage(Component.literal("§b[山海商店] §a已切换至【购买】模式"));
            mode = Mode.BUY; clearSelection(); return true;
        }
        if (hit(mx, my, sellTabX(), top + 6, SELL_TAB_W, TOP_BAR_H)) {
            if (mode != Mode.SELL) showMessage(Component.literal("§e[山海商店] §a已切换至【出售】模式"));
            mode = Mode.SELL; clearSelection(); return true;
        }

        // 关闭
        if (hit(mx, my, closeBtnX(), top + 6, CLOSE_W, TOP_BAR_H)) {
            onClose();
            return true;
        }
        // 购物车：点开/收起大图层
        if (hit(mx, my, cartBtnX(), top + 6, CART_BTN_W, TOP_BAR_H)) {
            cartOverlayOpen = !cartOverlayOpen;
            if (cartOverlayOpen) cartOverlayOpenAtMs = System.currentTimeMillis();
            // 结算结果不因收起面板清掉，见 handleCartOverlayClick 关闭分支同款说明
            cartOverlayScroll = 0;
            return true;
        }
        // 只看收藏筛选：跟主/子分类、搜索一起收窄网格，切换后立即重算可见列表
        if (hit(mx, my, favBtnX(), top + 6, FAV_BTN_W, TOP_BAR_H)) {
            favoritesOnly = !favoritesOnly;
            scroll = 0;
            recomputeVisible();
            showMessage(Component.literal(favoritesOnly
                    ? "§b[山海商店] §a已切换至【只看收藏】"
                    : "§b[山海商店] §7已取消只看收藏筛选"));
            return true;
        }
        // AE 模式切换：光靠按钮文字变色不够醒目，切换时打一条横幅明确告知当前状态
        if (hit(mx, my, aeBtnX(), top + 6, AE_BTN_W, TOP_BAR_H)) {
            // 关闭方向永远放行；开启方向须先有绑定的在线 AE 网络（商店终端/FTBQ提交器），
            // 否则玩家会误以为交易走了 AE，实际却被静默降级到背包/SDA（见反馈）。
            if (!aeMode && !ClientWalletAccount.hasBoundAeNetwork()) {
                showMessage(Component.literal(
                        "§c[山海商店] 未检测到已绑定的在线 AE 网络（需先放置并绑定商店终端或 FTBQ 提交器），无法开启 AE 模式"));
                return true;
            }
            aeMode = !aeMode;
            showMessage(Component.literal(aeMode
                    ? "§b[山海商店] §aAE 模式已开启，交易优先注入绑定的在线 AE 网络"
                    : "§b[山海商店] §7AE 模式已关闭，交易走背包/超级磁盘阵列"));
            return true;
        }
        // 精妙背包模式切换：开启后扣款/交付优先走随身穿戴的精妙背包（关闭则优先随身背包，跟原来一样）
        if (hit(mx, my, backpackBtnX(), top + 6, BACKPACK_BTN_W, TOP_BAR_H)) {
            backpackMode = !backpackMode;
            showMessage(Component.literal(backpackMode
                    ? "§b[山海商店] §a精妙背包模式已开启，交易优先从精妙背包扣款/交付"
                    : "§b[山海商店] §7精妙背包模式已关闭，交易优先走随身背包"));
            return true;
        }
        // 充值全部
        if (hit(mx, my, rechargeBtnX(), top + 6, RECHARGE_W, TOP_BAR_H)) {
            send(ShopActionPacket.Action.DEPOSIT, null, 1L);
            return true;
        }
        // 货币中心（打开 ATM 子页）
        if (hit(mx, my, currencyBtnX(), top + 6, CURRENCY_BTN_W, TOP_BAR_H)) {
            Minecraft.getInstance().setScreen(new CurrencyAtmScreen(this));
            return true;
        }
        // 兑换中心（打开兑换子页）
        if (hit(mx, my, exchangeBtnX(), top + 6, EXCHANGE_BTN_W, TOP_BAR_H)) {
            Minecraft.getInstance().setScreen(new ExchangeScreen(this, canEdit));
            return true;
        }
        // 新增商品（编辑权 + 开了编辑模式）：分类默认继承当前所在子页（如「无限盘区/前期」）
        if (catalogEditUnlocked && hit(mx, my, addBtnX(), top + 6, ADD_BTN_W, TOP_BAR_H)) {
            ShopEntryEditor.openNew(this, currentViewCategory());
            return true;
        }
        // 商店设置（仅编辑权，不受编辑模式限制）：奖励抽取次数上限等运行期可调行为
        if (canEdit && hit(mx, my, settingsBtnX(), top + 6, SETTINGS_BTN_W, TOP_BAR_H)) {
            Minecraft.getInstance().setScreen(new ShopSettingsScreen(this));
            return true;
        }

        // 主分类页签：切主分类 → 重置子分类为全部 → 重排（子页签行影响 contentTop）
        int ty = tabsY();
        int tx = left + 14;
        int tabsRight = detailX() - 8;
        for (String cat : categories) {
            int tw = Math.max(30, this.font.width(cat) + 10);
            if (tx + tw > tabsRight) break;
            if (hit(mx, my, tx, ty, tw, TAB_H)) {
                // 滑入方向按页签左右顺序走：切到右边的主分类从右侧滑入，切到左边的从左侧滑入
                gridSlideDir = categories.indexOf(cat) >= categories.indexOf(selectedTop) ? 1 : -1;
                selectedTop = cat; selectedSub = ""; clearSelection(); scroll = 0;
                gridSwitchAtMs = System.currentTimeMillis();
                this.init(Minecraft.getInstance(), this.width, this.height);
                return true;
            }
            tx += tw + 3;
        }
        // 子分类页签（有子分类时）：先「全部」再各子分类
        if (hasSubTabs()) {
            int sty = subTabsY();
            int stx = left + 14;
            int aw = Math.max(30, this.font.width("全部") + 10);
            if (hit(mx, my, stx, sty, aw, TAB_H)) {
                // 「全部」固定在最左（index 0），从当前子页签切过来只会是往左切（或原地，方向无所谓）
                int oldSubIdx = selectedSub.isEmpty() ? 0 : 1 + subCategories.indexOf(selectedSub);
                gridSlideDir = oldSubIdx <= 0 ? 1 : -1;
                selectedSub = ""; clearSelection(); scroll = 0;
                gridSwitchAtMs = System.currentTimeMillis();
                recomputeVisible();
                return true;
            }
            stx += aw + 3;
            for (String sub : subCategories) {
                int tw = Math.max(30, this.font.width(sub) + 10);
                if (stx + tw > tabsRight) break;
                if (hit(mx, my, stx, sty, tw, TAB_H)) {
                    int oldSubIdx = selectedSub.isEmpty() ? 0 : 1 + subCategories.indexOf(selectedSub);
                    int newSubIdx = 1 + subCategories.indexOf(sub);
                    gridSlideDir = newSubIdx >= oldSubIdx ? 1 : -1;
                    selectedSub = sub; clearSelection(); scroll = 0;
                    gridSwitchAtMs = System.currentTimeMillis();
                    recomputeVisible();
                    return true;
                }
                stx += tw + 3;
            }
        }

        // 网格右侧滚动条（拖拽跳转，先于格子命中判定）
        if (gridScrollbarClicked(mx, my)) return true;

        // 网格格子：坐标直接反算索引，不再线性扫描当前分类全部商品。左键=选中打开详情页，右键=快捷菜单。
        int entryIndex = entryIndexAt(mx, my);
        if (entryIndex >= 0) {
            ShopEntry entry = visibleEntry(entryIndex);
            if (entry != null) {
                if (btn == 1) {
                    openContextMenu(entry, visibleEntryKeys.get(entryIndex), (int) mx, (int) my);
                } else if (btn == 0 && net.minecraft.client.gui.screens.Screen.hasControlDown()) {
                    // Ctrl+左键：加入购物车候选（不影响详情页选中），已在购物车里则忽略，去购物车面板调数量
                    boolean already = ClientShopCart.contains(entry.getStableId());
                    ClientShopCart.add(entry.getStableId(), 1L);
                    showMessage(Component.literal(already
                            ? "§b[山海商店] §7已在购物车里: §f" + entry.goodsDisplayName()
                            : "§b[山海商店] §a已加入购物车: §f" + entry.goodsDisplayName()));
                } else {
                    selectEntry(visibleEntryKeys.get(entryIndex), entry);
                }
            }
            return true;
        }

        // 跳转入口（drawDetail 渲染时暂存的目标条目 + 命中框，点击直接切换详情页选中项）
        if (linkVisible && linkTarget != null && hit(mx, my, linkX, linkY, linkW, linkH)) {
            selectEntry(linkTargetKey, linkTarget);
            return true;
        }
        // GuideME 指南跳转（单条命中直接开指南；多条命中开「指南详情」大图层）
        if (guideLinkVisible && guideLinkHit != null && hit(mx, my, guideLinkX, guideLinkY, guideLinkW, guideLinkH)) {
            com.dishanhai.gt_shanhai.client.shop.ShopGuideLookup.open(Minecraft.getInstance().player, guideLinkHit);
            return true;
        }
        if (guideDetailBtnVisible && hit(mx, my, guideDetailBtnX, guideDetailBtnY, guideDetailBtnW, guideDetailBtnH)) {
            guideOverlayOpen = true;
            guideOverlayScroll = 0;
            return true;
        }
        // 「补齐全部缺口」：向服务端起一轮 AE 自动合成计算，算完服务端会推确认框
        if (autoCraftBtnVisible && selected != null && hit(mx, my, autoCraftBtnX, autoCraftBtnY, autoCraftBtnW, autoCraftBtnH)) {
            long entryKey = ClientShopCatalog.keyOf(selected);
            ShanhaiNetwork.CHANNEL.sendToServer(new com.dishanhai.gt_shanhai.network.ShopAutoCraftRequestPacket(
                    ClientShopCatalog.revision(), entryKey, amount, aeMode));
            return true;
        }
        // 前置任务跳转：打开 FTBQ 任务书并定位到该任务（未解析到时点了也没用，命中框本身照样给，方便玩家复制ID反馈）
        if (prereqLinkVisible && selected != null && hit(mx, my, prereqLinkX, prereqLinkY, prereqLinkW, prereqLinkH)) {
            com.dishanhai.gt_shanhai.client.shop.ShopFtbqPrereqLookup.open(selected.getPrerequisiteQuestId());
            return true;
        }
        // 展开描述详情（drawDetail 渲染时暂存的按钮坐标，命中即开大图层）
        if (descExpandVisible && hit(mx, my, descExpandX, descExpandY, descExpandW, descExpandH)) {
            descOverlayOpen = true;
            descOverlayScroll = 0;
            return true;
        }

        // 详情面板按钮
        if (selected != null) {
            int dx = detailX();
            int cx = dx + 8;
            int dy = contentTop();
            int dh = contentHeight();
            int btnW = DETAIL_W - 16;
            int btnY = dy + dh - 24;
            // 确认购买/出售：自选奖励（CHOICE，本地池或 FTBQ 表自选子模式）商品先弹选择界面，选完再发购买包；
            // 随机/全部/普通商品直接买
            if (hit(mx, my, cx, btnY, btnW, 20)) {
                if (mode == Mode.BUY && selected.hasMissingItems()) {
                    showMessage(Component.literal("§c[山海商店] 该商品引用的物品缺失（对应模组可能未安装），无法购买"));
                    return true;
                }
                if (mode == Mode.BUY && !selected.allowsBuy()) {
                    showMessage(Component.literal("§c[山海商店] 该商品仅允许出售，不能购买"));
                    return true;
                }
                if (mode == Mode.SELL && !selected.allowsSell()) {
                    showMessage(Component.literal("§c[山海商店] 该商品仅允许购买，不能出售"));
                    return true;
                }
                boolean localChoice = selected.getRewardMode() == ShopEntry.RewardMode.CHOICE;
                boolean ftbqChoice = selected.getRewardMode() == ShopEntry.RewardMode.FTBQ
                        && selected.getFtbqSubMode() == ShopEntry.RewardMode.CHOICE;
                if (mode == Mode.BUY && localChoice) {
                    Minecraft.getInstance().setScreen(new RewardChoiceScreen(this, selected, amount, aeMode, backpackMode));
                } else if (mode == Mode.BUY && ftbqChoice) {
                    Minecraft.getInstance().setScreen(new FtbqRewardChoiceScreen(this, selected, amount, aeMode, backpackMode));
                } else {
                    send(mode == Mode.BUY ? ShopActionPacket.Action.BUY : ShopActionPacket.Action.SELL, selected, amount);
                }
                return true;
            }
            // 删除（编辑权 + 开了编辑模式，二次确认防误触：3 秒内对同一条目再点一次「删除此商品」才真正执行）
            if (catalogEditUnlocked && hit(mx, my, cx, btnY - 22, btnW, 18)) {
                boolean armed = pendingDeleteEntry == selected
                        && System.currentTimeMillis() - pendingDeleteArmedAtMs < DELETE_CONFIRM_WINDOW_MS;
                if (!armed) {
                    pendingDeleteEntry = selected;
                    pendingDeleteArmedAtMs = System.currentTimeMillis();
                    showMessage(Component.literal("§e[山海商店] 再次点击「删除此商品」确认删除，3 秒内有效"));
                    return true;
                }
                String delName = selected.goodsDisplayName();
                send(ShopActionPacket.Action.DELETE, selected, 1L);
                clearSelection();
                pendingDeleteEntry = null;
                showMessage(Component.literal("§b[山海商店] §a已删除：§f" + delName));
                undoDeleteLabel = delName;
                undoDeleteUntilMs = System.currentTimeMillis() + UNDO_DELETE_UI_WINDOW_MS;
                return true;
            }
            // 编辑条目（编辑权 + 开了编辑模式）
            if (catalogEditUnlocked && hit(mx, my, cx, btnY - 44, btnW, 18)) {
                ShopEntryEditor.openEdit(this, selected);
                return true;
            }
            // AE 风格步进按钮（两排 +1/+10/+100/+1000 与 -1/-10/-100/-1000，第三排 ×10/×100/÷10/÷100）
            long[] steps = {1, 10, 100, 1000};
            long[] scales = {10, 100, 10, 100};
            int bw = (DETAIL_W - 16 - 9) / 4;
            for (int i = 0; i < 4; i++) {
                int bx = cx + i * (bw + 3);
                if (hit(mx, my, bx, dy + 82, bw, 12)) { amount = addClamp(amount, steps[i]); syncAmountBox(); return true; }
                if (hit(mx, my, bx, dy + 96, bw, 12)) { amount = addClamp(amount, -steps[i]); syncAmountBox(); return true; }
                if (hit(mx, my, bx, dy + 110, bw, 12)) {
                    amount = i < 2 ? mulClamp(amount, scales[i]) : divClamp(amount, scales[i]);
                    syncAmountBox();
                    return true;
                }
            }
        }

        return super.universalMouseClicked(mx, my, btn);
    }

    /** 数量加减夹取到 [1, Long.MAX]，防加法溢出。 */
    private static long addClamp(long a, long delta) {
        if (delta > 0 && a > Long.MAX_VALUE - delta) return Long.MAX_VALUE;
        return Math.max(1L, a + delta);
    }

    /** 数量乘法夹取到 [1, Long.MAX]，防乘法溢出。 */
    private static long mulClamp(long a, long factor) {
        if (factor <= 0) return a;
        if (a > Long.MAX_VALUE / factor) return Long.MAX_VALUE;
        return Math.max(1L, a * factor);
    }

    /** 数量除法夹取到 [1, Long.MAX]（整除向下取整，最小 1）。 */
    private static long divClamp(long a, long factor) {
        if (factor <= 0) return a;
        return Math.max(1L, a / factor);
    }

    @Override
    protected boolean universalMouseScrolled(double mx, double my, double d) {
        if (descOverlayOpen) {
            int[] r = descOverlayBounds();
            int maxScroll = descOverlayMaxScroll(r, descOverlayLines(r).size());
            descOverlayScroll = Math.max(0, Math.min(maxScroll, descOverlayScroll - (int) d));
            return true;
        }
        if (guideOverlayOpen) {
            int[] r = guideOverlayBounds();
            int maxScroll = guideOverlayMaxScroll(r);
            guideOverlayScroll = Math.max(0, Math.min(maxScroll, guideOverlayScroll - (int) d));
            return true;
        }
        if (cartOverlayOpen) {
            int[] r = cartOverlayBounds();
            int maxScroll = cartOverlayMaxScroll(r, ClientShopCart.size());
            cartOverlayScroll = Math.max(0, Math.min(maxScroll, cartOverlayScroll - (int) d));
            return true;
        }
        int gx = listLeft(), gy = contentTop(), gh = contentHeight();
        if (GuiRenderUtil.isHovering(mx, my, gx, gy, listWidth(), gh)) {
            int maxScroll = maxGridScroll();
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) d * ROW_STRIDE));
            return true;
        }
        return super.universalMouseScrolled(mx, my, d);
    }

    @Override
    protected boolean universalMouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (draggingDescOverlayScroll) { updateDescOverlayScrollFromDrag(my); return true; }
        if (draggingGridScroll) { updateGridScrollFromDrag(my); return true; }
        return super.universalMouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    protected boolean universalMouseReleased(double mx, double my, int btn) {
        if (draggingDescOverlayScroll) { draggingDescOverlayScroll = false; return true; }
        if (draggingGridScroll) { draggingGridScroll = false; return true; }
        return super.universalMouseReleased(mx, my, btn);
    }

    // ============ 工具 ============

    /**
     * 消息横幅队列固定预留的最大高度（哪怕当前没占满 3 条也按满员留白，避免撤销按钮跟着队列条数上下跳动）。
     * 撤销删除/撤销排序两个悬浮按钮都锚定在这块预留区域的正上方。
     */
    private int flashStackTopY() {
        return top + panelHeight - 24 - FLASH_ROW_H * FLASH_MAX_VISIBLE;
    }

    /** 撤销删除按钮的世界坐标（缩放后坐标系）：渲染和点击各调一次，保证坐标算法唯一，不重复算两遍。 */
    private int[] undoDeleteBtnBounds() {
        String label = "§a↩ 撤销删除【" + GuiRenderUtil.trimText(this.font, undoDeleteLabel, 70) + "】";
        int w = this.font.width(label) + 12;
        int h = 14;
        int x = left + (panelWidth - w) / 2;
        int y = flashStackTopY() - h - 3; // 位于消息横幅队列预留区正上方，避免遮挡
        return new int[]{x, y, w, h};
    }

    /** 撤销排序按钮的世界坐标：跟撤销删除同一条基准线，两者同时悬浮时本按钮让到上面一行，不叠字。 */
    private int[] undoReorderBtnBounds() {
        String label = "§a↩ 撤销排序【" + GuiRenderUtil.trimText(this.font, undoReorderLabel, 70) + "】";
        int w = this.font.width(label) + 12;
        int h = 14;
        int x = left + (panelWidth - w) / 2;
        boolean deleteBannerActive = undoDeleteLabel != null && System.currentTimeMillis() < undoDeleteUntilMs;
        int y = flashStackTopY() - h - 3 - (deleteBannerActive ? (h + 3) : 0);
        return new int[]{x, y, w, h};
    }

    private void send(ShopActionPacket.Action action, ShopEntry entry, long times) {
        long entryKey = entry != null ? ClientShopCatalog.keyOf(entry) : -1L;
        // aeMode 仅购买用（AE 模式优先注入网络，出售货款走虚拟钱包账户不涉及实物投放）；
        // backpackMode 购买/出售都要带（出售时决定优先扣随身背包还是精妙背包，见 ShopPurchase#sellBulk）
        boolean ae = action == ShopActionPacket.Action.BUY && aeMode;
        boolean bp = backpackMode;
        ShanhaiNetwork.CHANNEL.sendToServer(new ShopActionPacket(
                action, ClientShopCatalog.revision(), entryKey, times, ae, bp));
    }

    /** 购物车结算专用发包：标记 fromCart=true，服务端处理完会额外回一个 ShopCartPurchaseResultPacket
     * 结构化结果，供购物车面板逐行显示成功/部分/失败（见 send，跟普通详情页购买共用同一个 ShopActionPacket）。 */
    private void sendCartBuy(ShopEntry entry, long times) {
        long entryKey = entry != null ? ClientShopCatalog.keyOf(entry) : -1L;
        ShanhaiNetwork.CHANNEL.sendToServer(new ShopActionPacket(
                ShopActionPacket.Action.BUY, ClientShopCatalog.revision(), entryKey, times,
                aeMode, backpackMode, -1, true));
    }

    // ============ 网格卡片右键快捷菜单 ============

    /** 打开右键菜单：按该条目内容 + 当前权限现算菜单项，空菜单（无任何可用项）不弹出。 */
    private void openContextMenu(ShopEntry entry, long entryKey, int x, int y) {
        if (entry == null) return;
        List<ContextMenuItem> items = buildContextMenuItems(entry, entryKey);
        if (items.isEmpty()) return;
        ctxMenuItems = items;
        ctxMenuEntryKey = entryKey;
        ctxMenuX = x;
        ctxMenuY = y;
        ctxMenuW = 130;
        ctxMenuOpen = true;
    }

    private void closeContextMenu() {
        ctxMenuOpen = false;
        ctxMenuItems = List.of();
        ctxMenuEntryKey = -1L;
    }

    /**
     * 菜单项按「导航跳转合集」+「管理效率」（仅 canEdit）两组现算：条目没配的跳转类项直接不出现，
     * 管理类整组只有编辑权玩家才追加。同 {@link #drawDetail} 里的跳转判定逻辑保持口径一致（复用同一批
     * {@link ClientShopCatalog}/{@link com.dishanhai.gt_shanhai.client.shop.ShopGuideLookup} 查询）。
     */
    private List<ContextMenuItem> buildContextMenuItems(ShopEntry entry, long entryKey) {
        List<ContextMenuItem> items = new ArrayList<>();
        items.add(new ContextMenuItem("§f查看详情", false, () -> selectEntry(entryKey, entry)));
        // 收藏对所有玩家开放（不受 canEdit 限制）：纯个人标记，不影响商店目录本身，见 ClientShopFavorites。
        boolean favored = ClientShopFavorites.contains(entry.getStableId());
        items.add(new ContextMenuItem(favored ? "§e★取消收藏" : "§7☆收藏", false, () -> {
            ClientShopFavorites.toggle(entry.getStableId());
            if (favoritesOnly) recomputeVisible(); // 筛选中时取消收藏要立刻从网格消失，不用等下次切页/搜索
        }));

        if (entry.hasLinkTarget()) {
            long targetKey = ClientShopCatalog.linkedEntryKey(entry.getLinkTo());
            ShopEntry target = targetKey >= 0L ? ClientShopCatalog.get(targetKey) : null;
            if (target != null) {
                items.add(new ContextMenuItem("§9→ 跳转商品", false, () -> selectEntry(targetKey, target)));
            }
        }
        if (entry.hasPrerequisiteQuest()) {
            items.add(new ContextMenuItem("§9→ 前置任务", false, () ->
                    com.dishanhai.gt_shanhai.client.shop.ShopFtbqPrereqLookup.open(entry.getPrerequisiteQuestId())));
        }
        List<com.dishanhai.gt_shanhai.client.shop.ShopGuideLookup.GuideHit> hits =
                com.dishanhai.gt_shanhai.client.shop.ShopGuideLookup.findGuideHits(entry.getGoodsList());
        if (hits.size() == 1) {
            com.dishanhai.gt_shanhai.client.shop.ShopGuideLookup.GuideHit hit = hits.get(0);
            items.add(new ContextMenuItem("§9→ 查看指南", false, () ->
                    com.dishanhai.gt_shanhai.client.shop.ShopGuideLookup.open(Minecraft.getInstance().player, hit)));
        } else if (hits.size() > 1) {
            items.add(new ContextMenuItem("§d指南详情(" + hits.size() + ")", false, () -> {
                selectEntry(entryKey, entry);
                guideHitsFor = entry;
                guideHits = hits;
                guideOverlayOpen = true;
                guideOverlayScroll = 0;
            }));
        }

        if (canEdit) {
            // 编辑条目/复制为新条目/删除都折进了编辑模式；隐藏/排序不受编辑模式限制，仍只看 canEdit
            if (catalogEditUnlocked) {
                items.add(new ContextMenuItem("§b编辑条目", true, () -> ShopEntryEditor.openEdit(this, entry)));
                items.add(new ContextMenuItem("§a复制为新条目", false, () -> ShopEntryEditor.openDuplicate(this, entry)));
            }
            items.add(new ContextMenuItem(entry.isHidden() ? "§a取消隐藏" : "§c设为隐藏", false, () -> toggleHidden(entry)));
            items.add(new ContextMenuItem("§e▲ 前移", false, () ->
                    sendReorder(entry, com.dishanhai.gt_shanhai.network.ShopReorderPacket.Action.UP)));
            items.add(new ContextMenuItem("§e▼ 后移", false, () ->
                    sendReorder(entry, com.dishanhai.gt_shanhai.network.ShopReorderPacket.Action.DOWN)));
            items.add(new ContextMenuItem("§6⤒ 置顶", false, () ->
                    sendReorder(entry, com.dishanhai.gt_shanhai.network.ShopReorderPacket.Action.TOP)));
            if (catalogEditUnlocked) {
                items.add(new ContextMenuItem("§c删除此商品", true, () -> {
                    selectEntry(entryKey, entry);
                    pendingDeleteEntry = entry;
                    pendingDeleteArmedAtMs = System.currentTimeMillis();
                    showMessage(Component.literal("§e[山海商店] 已选中，再点一次详情页「删除此商品」确认删除，3 秒内有效"));
                }));
            }
        }
        return items;
    }

    /**
     * 排序：全服唯一展示顺序，只跟同分类最近相邻条目交换位置/挪最前，见 {@link ShopConfig#moveEntry}。
     * 发完顺手挂一个「撤销排序」悬浮按钮（乐观 UI，不等服务端确认——即便这次已经顶到边界没挪动，
     * 悬浮按钮空点一次顶多收到服务端"没有可撤销"的提示，不会误操作，成本可以忽略，见反馈：置顶点错了
     * 没法退回原位，必须给撤销出口）。
     */
    private void sendReorder(ShopEntry entry, com.dishanhai.gt_shanhai.network.ShopReorderPacket.Action action) {
        long entryKey = ClientShopCatalog.keyOf(entry);
        ShanhaiNetwork.CHANNEL.sendToServer(new com.dishanhai.gt_shanhai.network.ShopReorderPacket(
                action, ClientShopCatalog.revision(), entryKey));
        undoReorderLabel = entry.goodsDisplayName();
        undoReorderUntilMs = System.currentTimeMillis() + UNDO_REORDER_UI_WINDOW_MS;
    }

    /** 一键切换隐藏：其余字段原样带回去，走跟编辑器提交同一条 EDIT 通路，不新开一套网络包。 */
    private void toggleHidden(ShopEntry entry) {
        long entryKey = ClientShopCatalog.keyOf(entry);
        ShanhaiNetwork.CHANNEL.sendToServer(new com.dishanhai.gt_shanhai.network.ShopToggleHiddenPacket(
                ClientShopCatalog.revision(), entryKey));
    }

    /** 菜单命中判定：逐行按渲染时同一套坐标累加复算，命中就关菜单+执行动作；菜单外任意点击只关菜单。 */
    private boolean handleContextMenuClick(double mx, double my) {
        int ry = ctxMenuY + 2;
        for (ContextMenuItem item : ctxMenuItems) {
            if (item.separatorBefore()) ry += 3;
            if (hit(mx, my, ctxMenuX, ry, ctxMenuW, CTX_MENU_ROW_H)) {
                Runnable action = item.action();
                closeContextMenu();
                action.run();
                return true;
            }
            ry += CTX_MENU_ROW_H;
        }
        closeContextMenu();
        return true;
    }

    /** 菜单渲染：贴右/下边界时自动往左/往上收，避免弹到窗口外。 */
    private void drawContextMenu(GuiGraphics g, int mx, int my) {
        if (ctxMenuItems.isEmpty()) return;
        int totalH = 4;
        for (ContextMenuItem item : ctxMenuItems) totalH += item.separatorBefore() ? CTX_MENU_ROW_H + 3 : CTX_MENU_ROW_H;
        if (ctxMenuX + ctxMenuW > vWidth - 4) ctxMenuX = Math.max(4, vWidth - 4 - ctxMenuW);
        if (ctxMenuY + totalH > vHeight - 4) ctxMenuY = Math.max(4, vHeight - 4 - totalH);
        g.fill(ctxMenuX - 1, ctxMenuY - 1, ctxMenuX + ctxMenuW + 1, ctxMenuY + totalH + 1, GOLD_DARK);
        g.fill(ctxMenuX, ctxMenuY, ctxMenuX + ctxMenuW, ctxMenuY + totalH, PANEL_BG);
        int ry = ctxMenuY + 2;
        for (ContextMenuItem item : ctxMenuItems) {
            if (item.separatorBefore()) { g.fill(ctxMenuX + 4, ry + 1, ctxMenuX + ctxMenuW - 4, ry + 2, GOLD_DARK); ry += 3; }
            boolean hv = GuiRenderUtil.isHovering(mx, my, ctxMenuX, ry, ctxMenuW, CTX_MENU_ROW_H);
            if (hv) g.fill(ctxMenuX + 1, ry, ctxMenuX + ctxMenuW - 1, ry + CTX_MENU_ROW_H, ROW_BG);
            g.drawString(this.font, GuiRenderUtil.trimText(this.font, item.label(), ctxMenuW - 8), ctxMenuX + 4, ry + 2, WHITE, true);
            ry += CTX_MENU_ROW_H;
        }
    }

    private void drawTab(GuiGraphics g, int x, int y, int w, int h, String label, boolean active, int mx, int my) {
        boolean hover = GuiRenderUtil.isHovering(mx, my, x, y, w, h);
        int border = active ? GOLD : (hover ? GOLD_DARK : NUMBER_BAR_BORDER);
        renderBox(g, x, y, w, h, border, active ? ROW_BG : BOX_BG);
        int col = active ? GOLD : (hover ? WHITE : GRAY);
        g.drawCenteredString(this.font, label, x + w / 2, y + (h - 8) / 2, col);
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my) {
        boolean hover = GuiRenderUtil.isHovering(mx, my, x, y, w, h);
        renderBox(g, x, y, w, h, hover ? GOLD : GOLD_DARK, hover ? BUTTON_HOVER : BUTTON_BG);
        g.drawCenteredString(this.font, label, x + w / 2, y + (h - 8) / 2, WHITE);
    }

    private boolean hit(double mx, double my, int x, int y, int w, int h) {
        return GuiRenderUtil.isHovering(mx, my, x, y, w, h);
    }

    /**
     * 货币栏显示列表：各币种余额降序排，星火（数字余额，余额&gt;0 时）恒置顶。
     * drawCurrencyBar 与 hoveredCurrency 共用此方法以保证格位一一对应。
     */
    private java.util.List<Map.Entry<ResourceLocation, java.math.BigInteger>> currencyBarList() {
        Map<ResourceLocation, java.math.BigInteger> all = ClientWalletAccount.getAll();
        java.util.List<Map.Entry<ResourceLocation, java.math.BigInteger>> list = new java.util.ArrayList<>(all.entrySet());
        list.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        java.math.BigInteger spark = ClientWalletAccount.getDigital();
        if (spark.signum() > 0) {
            list.add(0, new java.util.AbstractMap.SimpleImmutableEntry<>(WalletAccountAPI.SPARK, spark));
        }
        return list;
    }

    /** KE 货币栏：「余额」标签 + 每种货币一格（物品图标/星火★ + 紧凑数字）。悬停显示货币全名。 */
    private void drawCurrencyBar(GuiGraphics g, int x, int y, int width, int mx, int my) {
        // 「余额」标签
        String label = "余额:";
        g.drawString(this.font, label, x, y + 4, CYAN, true);
        int cellStart = x + this.font.width(label) + 8;

        java.util.List<Map.Entry<ResourceLocation, java.math.BigInteger>> list = currencyBarList();
        if (list.isEmpty()) {
            g.drawString(this.font, "§80（点右侧「充值全部」存入背包货币）", cellStart, y + 4, GRAY, true);
            return;
        }

        int cellW = 66;
        int maxCells = Math.max(1, (width - (cellStart - x)) / cellW);
        for (int i = 0; i < list.size() && i < maxCells; i++) {
            Map.Entry<ResourceLocation, java.math.BigInteger> e = list.get(i);
            int cx = cellStart + i * cellW;
            if (WalletAccountAPI.isSpark(e.getKey())) {
                g.drawString(this.font, "§e★", cx + 4, y + 4, GOLD, true); // 星火无物品图标，用金色★
            } else {
                g.renderItem(currencyStack(e.getKey()), cx, y - 1); // 图标（居中于 16px）
            }
            // 紧凑数字
            g.drawString(this.font, formatBig(e.getValue()), cx + 18, y + 4, GOLD, true);
        }
        if (list.size() > maxCells) {
            g.drawString(this.font, "§7+" + (list.size() - maxCells), cellStart + maxCells * cellW - 4, y + 4, GRAY, true);
        }
    }

    /** 由货币 ID 构造展示用 ItemStack；对应模组缺失时用 FTBQ 风格占位代替（不再显示成空气）。 */
    private static ItemStack currencyStack(ResourceLocation id) {
        var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(id);
        return item != null ? new ItemStack(item) : ShopEntry.missingItemStack(id, 1);
    }

    /** 实物成分的本地化显示名（物品→物品名、流体→流体名；缺失回退注册 path，杜绝英文键裸露）。 */
    private static String ingredientName(ExchangeEntry.Ingredient in) {
        if (in == null || in.id == null) return "?";
        if (in.isFluid) {
            net.minecraft.world.level.material.Fluid f = net.minecraftforge.registries.ForgeRegistries.FLUIDS.getValue(in.id);
            if (f != null) {
                try {
                    return new net.minecraftforge.fluids.FluidStack(f, 1).getDisplayName().getString();
                } catch (Exception ignored) {}
            }
            return in.id.getPath();
        }
        ItemStack unit = in.makeUnitStack();
        return !unit.isEmpty() ? unit.getHoverName().getString() : in.id.getPath();
    }

    // ============ 图形化花费预览（图标 + 数量）============

    /** 预览格：物品/货币（真图标）、流体（贴图）或星火（★），带数量、本地化名与「拥有量」（够不够判定用）。 */
    private static final class PreviewCell {
        final ItemStack item;                                   // 物品/货币图标（非物品为 EMPTY）
        final net.minecraft.world.level.material.Fluid fluid;   // 流体（非流体为 null）
        final boolean spark;                                    // 星火（数字余额）
        final String amount;                                    // 数量文本（缩写，槽位小标签；流体带 mB）
        final String exact;                                     // 精确数量文本（带千分位，悬停 tooltip 用；流体带 mB）
        final String name;                                      // 本地化名（悬停）
        final java.math.BigInteger need;                        // 需要量（已 ×购买次数）
        final java.math.BigInteger have;                        // 当前拥有量（null=未同步/加载中，不参与判定）
        PreviewCell(ItemStack item, net.minecraft.world.level.material.Fluid fluid, boolean spark, String amount, String exact,
                    String name, java.math.BigInteger need, java.math.BigInteger have) {
            this.item = item; this.fluid = fluid; this.spark = spark; this.amount = amount; this.exact = exact;
            this.name = name; this.need = need; this.have = have;
        }
    }

    /**
     * 把成本拆成预览格：星火 → 币种 → 物品 → 流体（保序），数量按 times 展开。
     * have 来源：星火直接读 {@link ClientWalletAccount} 数字余额（本地已同步，无需往返）；
     * 币种/物品/流体走 {@link ClientCostPreview}（服务端 previewHave 往返，含背包+精妙背包+[AE模式]绑定AE），
     * 未同步或对不上当前商品/AE模式时为 null——渲染端按"加载中"处理，不误判成"不够"。
     */
    private static java.util.List<PreviewCell> costCells(ShopCost cost, long times, long entryKeyForPreview, boolean aeModeForPreview) {
        java.math.BigInteger t = java.math.BigInteger.valueOf(Math.max(1L, times));
        java.util.List<PreviewCell> cells = new java.util.ArrayList<>();
        if (cost.spark.signum() > 0) {
            java.math.BigInteger need = cost.spark.multiply(t);
            cells.add(new PreviewCell(ItemStack.EMPTY, null, true, formatBig(need), groupBig(need),
                    "★星火（数字余额）", need, ClientWalletAccount.getDigital()));
        }
        for (Map.Entry<ResourceLocation, java.math.BigInteger> c : cost.coins.entrySet()) {
            java.math.BigInteger need = c.getValue().multiply(t);
            java.math.BigInteger have = ClientCostPreview.coinHave(entryKeyForPreview, aeModeForPreview, c.getKey());
            cells.add(new PreviewCell(currencyStack(c.getKey()), null, false,
                    formatBig(need), groupBig(need), ShopPurchase.coinName(c.getKey()), need, have));
        }
        java.util.List<ExchangeEntry.Ingredient> itemIns = cost.items();
        for (int i = 0; i < itemIns.size(); i++) {
            ExchangeEntry.Ingredient in = itemIns.get(i);
            java.math.BigInteger need = java.math.BigInteger.valueOf(in.count).multiply(t);
            Long haveL = ClientCostPreview.itemHave(entryKeyForPreview, aeModeForPreview, i);
            java.math.BigInteger have = haveL != null ? java.math.BigInteger.valueOf(haveL) : null;
            cells.add(new PreviewCell(in.makeUnitStack(), null, false,
                    formatBig(need), groupBig(need), ingredientName(in), need, have));
        }
        java.util.List<ExchangeEntry.Ingredient> fluidIns = cost.fluids();
        for (int i = 0; i < fluidIns.size(); i++) {
            ExchangeEntry.Ingredient in = fluidIns.get(i);
            net.minecraft.world.level.material.Fluid f = net.minecraftforge.registries.ForgeRegistries.FLUIDS.getValue(in.id);
            java.math.BigInteger need = java.math.BigInteger.valueOf(in.count).multiply(t);
            Long haveL = ClientCostPreview.fluidHave(entryKeyForPreview, aeModeForPreview, i);
            java.math.BigInteger have = haveL != null ? java.math.BigInteger.valueOf(haveL) : null;
            cells.add(new PreviewCell(ItemStack.EMPTY, f, false,
                    formatBig(need) + "mB", groupBig(need) + "mB", ingredientName(in), need, have));
        }
        return cells;
    }

    /** 渲染流体静态贴图（Forge 图集 sprite + tint），失败静默。 */
    private static void renderFluidIcon(GuiGraphics g, int x, int y, int size, net.minecraft.world.level.material.Fluid fluid) {
        if (fluid == null) return;
        try {
            var ext = net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.of(fluid);
            ResourceLocation still = ext.getStillTexture();
            if (still == null) return;
            net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = Minecraft.getInstance()
                    .getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS).apply(still);
            int tint = ext.getTintColor();
            float r = ((tint >> 16) & 0xFF) / 255f, gg = ((tint >> 8) & 0xFF) / 255f, b = (tint & 0xFF) / 255f;
            float a = ((tint >>> 24) & 0xFF) / 255f;
            if (a <= 0f) a = 1f; // 无 alpha 的 tint 视为不透明
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(r, gg, b, a);
            g.blit(x, y, 0, size, size, sprite);
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        } catch (Exception ignored) {}
    }

    /** 单个预览格：棋盘槽底 + 图标 + 槽下数量（够=绿，不够=红，未同步/加载中=白）。 */
    private void drawPreviewSlot(GuiGraphics g, int x, int y, PreviewCell cell, boolean hover) {
        EditorWidgets.checkerSlot(g, x, y, hover);
        if (cell.spark) {
            g.drawCenteredString(this.font, "§e★", x + 10, y + 6, -1);
        } else if (cell.fluid != null) {
            renderFluidIcon(g, x + 2, y + 2, 16, cell.fluid);
        } else if (cell.item != null && !cell.item.isEmpty()) {
            g.renderItem(cell.item, x + 2, y + 2);
        }
        String prefix = cell.have == null ? "§f" : (cell.have.compareTo(cell.need) >= 0 ? "§a" : "§c");
        g.drawCenteredString(this.font, prefix + cell.amount, x + 10, y + 22, WHITE);
    }

    /**
     * 图形化花费预览：一排图标槽（货币/物品真图标、流体贴图、星火★）+ 数量，超宽换行、超界省略。
     * 数量按 amount（购买次数）展开；格子颜色标出是否够（绿/红），未知（加载中）为白。
     * @return 预览区结束后的 y（供描述接续）。悬停格把本地化名 + 拥有/缺少写入 {@link #previewHoverName}/{@link #previewHoverExtra}。
     */
    private int drawCostPreview(GuiGraphics g, int x, int y, ShopCost cost, long times, int maxW, int maxBottom, int mx, int my) {
        g.drawString(this.font, "§6花费预览:", x, y, GOLD, true);
        int sy = y + 12;
        java.util.List<PreviewCell> cells = costCells(cost, times, selectedEntryKey, aeMode);
        if (cells.isEmpty()) {
            g.drawString(this.font, "§8免费", x, sy, GRAY, true);
            return sy + 12;
        }
        int pitchX = 24, pitchY = 32;
        int perRow = Math.max(1, (maxW + 2) / pitchX);
        int endY = sy;
        for (int i = 0; i < cells.size(); i++) {
            int col = i % perRow, row = i / perRow;
            int sx = x + col * pitchX + 1;
            int syy = sy + row * pitchY + 1;
            if (syy + 20 > maxBottom) { // 空间不足 → 省略剩余
                g.drawString(this.font, "§7… 还有 " + (cells.size() - i) + " 项", x, syy + 2, GRAY, true);
                endY = syy + 12;
                break;
            }
            PreviewCell cell = cells.get(i);
            boolean hv = GuiRenderUtil.isHovering(mx, my, sx, syy, 20, 20);
            drawPreviewSlot(g, sx, syy, cell, hv);
            if (hv) {
                previewHoverName = "§f" + cell.name + " §7×" + cell.exact;
                if (cell.have == null) {
                    previewHoverExtra = "§7库存核对中…";
                } else if (cell.have.compareTo(cell.need) >= 0) {
                    previewHoverExtra = "§a拥有: " + groupBig(cell.have);
                } else {
                    previewHoverExtra = "§c拥有: " + groupBig(cell.have) + " §7(缺 " + groupBig(cell.need.subtract(cell.have)) + ")";
                }
            }
            endY = syy + pitchY;
        }
        return endY;
    }

    /**
     * 花费预览「拥有量」的服务端往返节流：选中商品切换 / AE 模式切换立即重新请求，
     * 之外每 {@link #PREVIEW_REFRESH_TICKS} 刻兜底刷新一次（玩家挖到/存入物品、AE网络存量变化不会主动推送）。
     * 纯星火成本（无币种/无实物）不用往返——{@link ClientWalletAccount} 本地已同步，见 {@link #costCells}。
     */
    private void maybeRequestCostPreview(ShopCost cost) {
        if (cost.coins.isEmpty() && cost.physical.isEmpty()) return;
        net.minecraft.client.multiplayer.ClientLevel lvl = Minecraft.getInstance().level;
        long gameTime = lvl != null ? lvl.getGameTime() : 0L;
        boolean stale = selectedEntryKey != previewRequestedKey || aeMode != previewRequestedAeMode
                || gameTime - previewRequestedAtGameTime >= PREVIEW_REFRESH_TICKS;
        if (!stale) return;
        previewRequestedKey = selectedEntryKey;
        previewRequestedAeMode = aeMode;
        previewRequestedAtGameTime = gameTime;
        ShanhaiNetwork.CHANNEL.sendToServer(
                new ShopCostPreviewRequestPacket(ClientShopCatalog.revision(), selectedEntryKey, aeMode));
    }

    // ============ 图形化获得预览（组合商品，图标 + 数量）============

    /** 获得预览格：物品/流体图标 + 数量，纯展示（不比对拥有量，跟花费预览的核心区别）。 */
    private record RewardCell(ItemStack item, net.minecraft.world.level.material.Fluid fluid, String amount, String exact, String name) {}

    /** 把组合商品清单拆成获得预览格，数量按 times 展开（各项独立数量 × times）。 */
    private static java.util.List<RewardCell> goodsRewardCells(List<ShopEntry.GoodsStack> goods, long times) {
        java.math.BigInteger t = java.math.BigInteger.valueOf(Math.max(1L, times));
        java.util.List<RewardCell> cells = new java.util.ArrayList<>();
        for (ShopEntry.GoodsStack gs : goods) {
            java.math.BigInteger need = java.math.BigInteger.valueOf(gs.count()).multiply(t);
            if (gs.isFluid()) {
                net.minecraft.world.level.material.Fluid f = gs.fluid();
                String name = "?";
                try { name = new net.minecraftforge.fluids.FluidStack(f, 1).getDisplayName().getString(); } catch (Exception ignored) {}
                cells.add(new RewardCell(ItemStack.EMPTY, f, formatBig(need) + "mB", groupBig(need) + "mB", name));
            } else {
                ItemStack unit = gs.makeStack();
                cells.add(new RewardCell(unit, null, formatBig(need), groupBig(need), unit.getHoverName().getString()));
            }
        }
        return cells;
    }

    /**
     * 图形化获得预览：组合商品用，跟「花费预览」同款槽位样式（真图标/流体贴图 + 数量），
     * 但固定只占一行——组合商品清单通常就几种（见「等N种」命名），超出一行的按「+N」收尾，
     * 不像花费预览那样按剩余高度动态换行/截断（这里在 getLine 那行原地展开，后面 cost 预览
     * 的起始 Y 只需整体下移固定量，不用像 CurrencyAtmScreen 教训里那样牵连一串后续坐标）。
     * @return 预览区结束后的 y（供调用方续接布局）。悬停格把名+精确数量写入 {@link #previewHoverName}。
     */
    private int drawRewardPreview(GuiGraphics g, int x, int y, List<ShopEntry.GoodsStack> goods, long times, int maxW, int mx, int my) {
        g.drawString(this.font, "§6预计获得:", x, y, GOLD, true);
        int sy = y + 12;
        java.util.List<RewardCell> cells = goodsRewardCells(goods, times);
        if (cells.isEmpty()) {
            g.drawString(this.font, "§8无", x, sy, GRAY, true);
            return sy + 12;
        }
        int pitchX = 24;
        int perRow = Math.max(1, (maxW + 2) / pitchX);
        int shown = Math.min(cells.size(), perRow); // 固定一行，多余的收成「+N」，不动态换行
        for (int i = 0; i < shown; i++) {
            int sx = x + i * pitchX + 1;
            RewardCell cell = cells.get(i);
            boolean hv = GuiRenderUtil.isHovering(mx, my, sx, sy, 20, 20);
            EditorWidgets.checkerSlot(g, sx, sy, hv);
            if (cell.fluid() != null) renderFluidIcon(g, sx + 2, sy + 2, 16, cell.fluid());
            else if (cell.item() != null && !cell.item().isEmpty()) g.renderItem(cell.item(), sx + 2, sy + 2);
            g.drawCenteredString(this.font, "§f" + cell.amount(), sx + 10, sy + 22, WHITE);
            if (hv) previewHoverName = "§f" + cell.name() + " §7×" + cell.exact();
        }
        if (cells.size() > shown) {
            g.drawString(this.font, "§7+" + (cells.size() - shown), x + shown * pitchX + 3, sy + 6, GRAY, true);
        }
        return sy + 30;
    }

    /** 多元成本紧凑单行（星火/币种/物品/流体）。 */
    private static String costInline(ShopCost cost) {
        StringBuilder sb = new StringBuilder();
        if (cost.spark.signum() > 0) sb.append("§e★").append(formatBig(cost.spark));
        for (Map.Entry<ResourceLocation, java.math.BigInteger> c : cost.coins.entrySet()) {
            if (sb.length() > 0) sb.append("§7, ");
            sb.append("§6").append(formatBig(c.getValue())).append(ShopPurchase.coinName(c.getKey()));
        }
        for (ExchangeEntry.Ingredient in : cost.physical) {
            if (sb.length() > 0) sb.append("§7, ");
            String nm = ingredientName(in);
            if (in.isFluid) sb.append("§b").append(in.count).append("mB ").append(nm);
            else sb.append("§f").append(in.count).append("×").append(nm);
        }
        return sb.length() == 0 ? "§8免费" : sb.toString();
    }

    /** 秒数转紧凑中文时长（限时折扣倒计时用），按量级只取最粗的两档，不做满位 HH:MM:SS。 */
    private static String formatDuration(long totalSeconds) {
        if (totalSeconds < 60L) return totalSeconds + "秒";
        long totalMinutes = totalSeconds / 60L;
        if (totalMinutes < 60L) return totalMinutes + "分" + (totalSeconds % 60L) + "秒";
        long totalHours = totalMinutes / 60L;
        if (totalHours < 24L) return totalHours + "时" + (totalMinutes % 60L) + "分";
        long days = totalHours / 24L;
        return days + "天" + (totalHours % 24L) + "时";
    }

    /** 多元成本 × times 的紧凑单行。 */
    private static String costInlineTimes(ShopCost cost, long times) {
        java.math.BigInteger t = java.math.BigInteger.valueOf(Math.max(1L, times));
        StringBuilder sb = new StringBuilder();
        if (cost.spark.signum() > 0) sb.append("§e★").append(formatBig(cost.spark.multiply(t)));
        for (Map.Entry<ResourceLocation, java.math.BigInteger> c : cost.coins.entrySet()) {
            if (sb.length() > 0) sb.append("§7, ");
            sb.append("§6").append(formatBig(c.getValue().multiply(t))).append(ShopPurchase.coinName(c.getKey()));
        }
        for (ExchangeEntry.Ingredient in : cost.physical) {
            if (sb.length() > 0) sb.append("§7, ");
            String nm = ingredientName(in);
            java.math.BigInteger cnt = java.math.BigInteger.valueOf(in.count).multiply(t);
            if (in.isFluid) sb.append("§b").append(formatBig(cnt)).append("mB ").append(nm);
            else sb.append("§f").append(formatBig(cnt)).append("×").append(nm);
        }
        return sb.length() == 0 ? "§8免费" : sb.toString();
    }

    /**
     * 客户端可购判定（按钮变色的视觉提示，不是硬门槛——点击不看这个，服务端 {@code affordAndDeduct} 才是权威裁决，
     * 见 universalMouseClicked 里确认购买直接 send，不查 canTrade）。
     * 口径必须和「花费预览」格子一致：星火走 {@link ClientWalletAccount} 数字余额；币种/物品/流体都走
     * {@link ClientCostPreview}（服务端 previewHave 往返，含背包+精妙背包+[AE模式]绑定AE）——否则会出现预览格
     * 全绿、按钮却仍显示红的观感割裂（见反馈）。往返数据还没同步回来时保守按"不够"处理，数据到位自动转绿。
     */
    private boolean canAffordClient(ShopCost cost, long times) {
        java.math.BigInteger t = java.math.BigInteger.valueOf(Math.max(1L, times));
        if (cost.spark.signum() > 0 && ClientWalletAccount.getDigital().compareTo(cost.spark.multiply(t)) < 0) return false;
        for (Map.Entry<ResourceLocation, java.math.BigInteger> c : cost.coins.entrySet()) {
            java.math.BigInteger need = c.getValue().multiply(t);
            java.math.BigInteger have = ClientCostPreview.coinHave(selectedEntryKey, aeMode, c.getKey());
            if (have == null || have.compareTo(need) < 0) return false;
        }
        java.util.List<ExchangeEntry.Ingredient> itemIns = cost.items();
        for (int i = 0; i < itemIns.size(); i++) {
            java.math.BigInteger need = java.math.BigInteger.valueOf(itemIns.get(i).count).multiply(t);
            Long have = ClientCostPreview.itemHave(selectedEntryKey, aeMode, i);
            if (have == null || java.math.BigInteger.valueOf(have).compareTo(need) < 0) return false;
        }
        java.util.List<ExchangeEntry.Ingredient> fluidIns = cost.fluids();
        for (int i = 0; i < fluidIns.size(); i++) {
            java.math.BigInteger need = java.math.BigInteger.valueOf(fluidIns.get(i).count).multiply(t);
            Long have = ClientCostPreview.fluidHave(selectedEntryKey, aeMode, i);
            if (have == null || java.math.BigInteger.valueOf(have).compareTo(need) < 0) return false;
        }
        return true;
    }

    /**
     * 是否存在「确定」不够的成本项（have 已同步且 < need）——只用来决定「补齐全部缺口」按钮出不出现，
     * 跟 {@link #canAffordClient} 的区别是：未同步（have==null）这里不算缺口，避免刚切商品/切AE模式那一瞬间
     * 按钮闪现又消失。
     */
    private static boolean hasCostShortfall(ShopCost cost, long times, long entryKeyForPreview, boolean aeModeForPreview) {
        java.math.BigInteger t = java.math.BigInteger.valueOf(Math.max(1L, times));
        for (Map.Entry<ResourceLocation, java.math.BigInteger> c : cost.coins.entrySet()) {
            java.math.BigInteger need = c.getValue().multiply(t);
            java.math.BigInteger have = ClientCostPreview.coinHave(entryKeyForPreview, aeModeForPreview, c.getKey());
            if (have != null && have.compareTo(need) < 0) return true;
        }
        java.util.List<ExchangeEntry.Ingredient> itemIns = cost.items();
        for (int i = 0; i < itemIns.size(); i++) {
            java.math.BigInteger need = java.math.BigInteger.valueOf(itemIns.get(i).count).multiply(t);
            Long have = ClientCostPreview.itemHave(entryKeyForPreview, aeModeForPreview, i);
            if (have != null && java.math.BigInteger.valueOf(have).compareTo(need) < 0) return true;
        }
        java.util.List<ExchangeEntry.Ingredient> fluidIns = cost.fluids();
        for (int i = 0; i < fluidIns.size(); i++) {
            java.math.BigInteger need = java.math.BigInteger.valueOf(fluidIns.get(i).count).multiply(t);
            Long have = ClientCostPreview.fluidHave(entryKeyForPreview, aeModeForPreview, i);
            if (have != null && java.math.BigInteger.valueOf(have).compareTo(need) < 0) return true;
        }
        return false;
    }

    /** 悬停货币格返回其全名（供 tooltip）。坐标须与 drawCurrencyBar 完全一致。 */
    private ResourceLocation hoveredCurrency(int smx, int smy) {
        int x = left + 14 + 6;
        int by = balanceY();
        String label = "余额:";
        int cellStart = x + this.font.width(label) + 8;
        java.util.List<Map.Entry<ResourceLocation, java.math.BigInteger>> list = currencyBarList();
        int cellW = 66;
        // 与 drawCurrencyBar 同一 width（panelWidth-40）算可见格数，超出的格没画出来（用 +N 省略），不响应悬停
        int width = panelWidth - 40;
        int maxCells = Math.max(1, (width - (cellStart - x)) / cellW);
        for (int i = 0; i < list.size() && i < maxCells; i++) {
            int cx = cellStart + i * cellW;
            if (GuiRenderUtil.isHovering(smx, smy, cx, by, cellW - 4, balanceHeight())) {
                return list.get(i).getKey();
            }
        }
        return null;
    }

    /** 客户端手持钱包（主手优先）。 */
    private ItemStack wallet() {
        var p = Minecraft.getInstance().player;
        if (p == null) return ItemStack.EMPTY;
        if (p.getMainHandItem().getItem() instanceof WalletItem) return p.getMainHandItem();
        if (p.getOffhandItem().getItem() instanceof WalletItem) return p.getOffhandItem();
        return ItemStack.EMPTY;
    }

    private boolean isOp() {
        var p = Minecraft.getInstance().player;
        return p != null && p.hasPermissions(2);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void renderTooltips(GuiGraphics g, int smx, int smy, int mx, int my) {
        if (ctxMenuOpen || descOverlayOpen || guideOverlayOpen || cartOverlayOpen) return; // 大图层/右键菜单盖住时，底层格子/图标的 tooltip 不该透出来
        // 悬停货币栏：显示货币全名 + 精确余额
        ResourceLocation cur = hoveredCurrency(smx, smy);
        if (cur != null) {
            java.math.BigInteger bal = WalletAccountAPI.isSpark(cur)
                    ? ClientWalletAccount.getDigital()
                    : ClientWalletAccount.getCurrency(cur);
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("§6" + ShopPurchase.coinName(cur)));
            lines.add(Component.literal("§7余额: §f" + bal.toString()));
            g.renderComponentTooltip(this.font, lines, mx, my);
            return;
        }
        // 悬停详情页商品大图标：渲染真实物品 tooltip（含 appendHoverText/能力提示，如 SDA 实时解析内容）
        if (detailHoverStack != null && !detailHoverStack.isEmpty()) {
            g.renderTooltip(this.font, detailHoverStack, mx, my);
            return;
        }
        // 悬停详情页花费预览格：显示本地化名 + 数量，外加拥有/缺少（drawCostPreview 每帧暂存）
        if (previewHoverName != null) {
            if (previewHoverExtra != null) {
                List<Component> lines = new ArrayList<>();
                lines.add(Component.literal(previewHoverName));
                lines.add(Component.literal(previewHoverExtra));
                g.renderComponentTooltip(this.font, lines, mx, my);
            } else {
                g.renderTooltip(this.font, Component.literal(previewHoverName), mx, my);
            }
            return;
        }
        // 悬停商品格：坐标直接反算唯一索引，不再逐格扫描。
        int idx = entryIndexAt(smx, smy);
        if (idx < 0) return;
        ShopEntry e = visibleEntry(idx);
        if (e == null) {
            g.renderTooltip(this.font, Component.literal("§8商品数据加载中…"), mx, my);
            return;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("§f" + e.goodsDisplayName()));
        if (e.hasMultipleGoods()) {
            StringBuilder gc = new StringBuilder("§7成分: §f");
            for (ShopEntry.GoodsStack gs : e.getGoodsList()) {
                gc.append(gs.count()).append('×').append(new ItemStack(gs.item()).getHoverName().getString()).append(' ');
            }
            lines.add(Component.literal(gc.toString().trim()));
        } else {
            lines.add(Component.literal("§7每份 " + e.getGoodsCount() + " 个"));
        }
        if (e.isDiscountActive()) {
            lines.add(Component.literal("§7成本 §8" + costInline(e.getCost()) + " §a→ " + costInline(e.getEffectiveCost())));
            lines.add(Component.literal("§6限时特惠 -" + e.getDiscountPercent() + "% §7剩" + formatDuration(e.discountRemainingMs() / 1000L)));
        } else {
            lines.add(Component.literal("§7成本 " + costInline(e.getCost())));
        }
        if (e.isLimited()) lines.add(Component.literal("§7限购剩余 §d" + formatBig(java.math.BigInteger.valueOf(e.getRemainingUses())) + " §7次"));
        if (e.getCost().hasPhysical()) lines.add(Component.literal("§8含实物：物品在背包 / 流体绑定 AE"));
        if (!e.getDisplayIcons().isEmpty()) {
            StringBuilder ic = new StringBuilder("§7图标: §f");
            for (ShopEntry.DisplayIcon d : e.getDisplayIcons()) ic.append(d.displayName()).append(' ');
            lines.add(Component.literal(ic.toString().trim()));
        }
        if (e.getDescription() != null && !e.getDescription().isEmpty()) {
            lines.add(Component.literal("§7" + GuiRenderUtil.translateAmpCodes(e.getDescription())));
        }
        g.renderComponentTooltip(this.font, lines, mx, my);
    }
}
