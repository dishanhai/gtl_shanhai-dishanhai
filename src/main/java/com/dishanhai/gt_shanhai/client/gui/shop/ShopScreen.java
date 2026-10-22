package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.ButtonFeedback;
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiMotion;
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.RepeatCurve;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.client.gui.scaled.Spring;
import com.dishanhai.gt_shanhai.client.gui.scaled.SpringTable;
import com.dishanhai.gt_shanhai.client.shop.ClientCostPreview;
import com.dishanhai.gt_shanhai.client.shop.ClientShopActionResults;
import com.dishanhai.gt_shanhai.client.shop.ClientShopCart;
import com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog;
import com.dishanhai.gt_shanhai.client.shop.ClientShopFavorites;
import com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings;
import com.dishanhai.gt_shanhai.client.shop.ClientWalletAccount;
import com.dishanhai.gt_shanhai.common.shop.ExchangeEntry;
import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;
import com.dishanhai.gt_shanhai.common.shop.ShopConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopCost;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;
import com.dishanhai.gt_shanhai.common.shop.ShopGridViewport;
import com.dishanhai.gt_shanhai.common.shop.ShopLinkNames;
import com.dishanhai.gt_shanhai.common.shop.ShopMembership;
import com.dishanhai.gt_shanhai.common.shop.ShopPurchase;
import com.dishanhai.gt_shanhai.common.shop.WalletAccountAPI;
import com.dishanhai.gt_shanhai.common.item.WalletItem;
import com.dishanhai.gt_shanhai.jei.JeiBookmarkBridge;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopActionPacket;
import com.dishanhai.gt_shanhai.network.ShopCostPreviewRequestPacket;
import com.dishanhai.gt_shanhai.network.ShopStagePreviewRequestPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
    private static final int GRID_GAP = 1;          // 格间距（KE = 1）
    private static final int DEFAULT_GRID_COLS = 10; // 默认每行 10 格
    private static final int TARGET_GRID_ROWS = 12; // 低高度时按一屏约 12 行反推卡片高度上限
    private static final int MIN_EFFECTIVE_CARD_W = 48;
    private static final int MIN_EFFECTIVE_CARD_H = 28;
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

    // 购买/出售 tab + 主/子/子子/子子子分类页签（最多嵌套 4 层）：静态=跨界面实例保留，关闭商店再打开继承上次浏览的页/子页，不再每次回到默认页
    private static Mode mode = Mode.BUY;
    private static String selectedTop;
    private static String selectedSub = "";
    private static String selectedSub2 = "";
    private static String selectedSub3 = "";
    // 记住每个父路径下最后浏览的子分类（key=父路径拼接，见 pathKey）：切到别的主分类再切回来，
    // 恢复原来停留的子页而不是回到全部；见反馈：切页要能带回原子页，不要求全部塌陷
    private static final Map<String, String> lastChildByPath = new java.util.HashMap<>();

    // 分类页签拖拽排序（仅 canEdit 才能触发，见 beginTabAction）：按下页签先记起点，越过阈值才算
    // "长按脱离"进入真正的拖拽视觉态，松开时按落点算目标下标发包持久化，见反馈：要能拖到任意位置，
    // 不只是前移/后移。非拖拽（未越过阈值）松开 = 当普通点击处理，行为等同原来的立即切换。
    private int dragTabLevel = -1;         // 0=主分类,1=二级,2=三级,3=四级；-1=当前未在拖拽/按下追踪中
    private String dragTabCategory;        // 正在拖拽/追踪的页签名
    private double dragTabStartX, dragTabStartY; // 按下时坐标，用于判断是否越过拖拽阈值
    private boolean dragTabMoving;         // 是否已越过阈值，进入真正拖拽视觉态（页签跟随鼠标、原位置塌陷）
    private double dragTabMx, dragTabMy;   // 拖拽中的实时鼠标坐标
    private static final double DRAG_TAB_THRESHOLD = 5.0;
    private static final double DRAG_CARD_THRESHOLD = 14.0;

    // 商品卡片拖拽排序（仅 canEdit + 停在具体叶子分类视图 + 未搜索时可用，见 isLeafCategoryView）：
    // 跟页签拖拽同一套按下/阈值/松开判定，但网格是虚拟滚动视口，做"原位置塌陷+其余格子实时补位"的
    // 完整重排渲染成本较高，改成更轻量的视觉：原格子照常显示，拖拽中额外画一个跟随光标的浮动图标 +
    // 高亮当前悬停的目标格子，松开按目标格子换算成"去掉被拖商品后的本地插入下标"发包，
    // 见 sendCardReorder/ShopConfig#moveEntryToIndex。
    private long dragCardEntryKey = -1L;   // -1 = 当前未在拖拽/按下追踪中
    private ShopEntry dragCardEntry;
    private int dragCardOldIndex = -1;     // 按下时 entryIndexAt 算出的 visibleEntryKeys 下标（未去除自身前）
    private double dragCardStartX, dragCardStartY;
    private boolean dragCardMoving;
    private double dragCardMx, dragCardMy;

    private ShopEntry selected;
    private long selectedEntryKey = -1L;
    private long observedCatalogRevision = -1L;
    // 从 FTBQ 任务书「前往商店」跳过来的待定位商品（见 FtbViewQuestPanelShopButtonMixin）：开店是异步的
    // （客户端发 WalletOpenRequestPacket → 服务端回 ShopOpenPacket 才 new 出本界面），跨实例交接只能走静态位。
    // 主键用 stableId 而非 entryKey：这中间服务端可能重发新 revision，entryKey 是快照下标会集体错位（见 ShopEntry#stableId）。
    private static String pendingFocusStableId;
    private static long pendingFocusFallbackKey = -1L;
    // 记录时刻；开店请求可能被服务端拒（没带钱包），此时待定位会残留下来，超时作废免得污染下一次手动开店
    private static long pendingFocusAtMs;
    private static ResourceLocation pendingCurrency;
    private static long pendingCurrencyAtMs;
    private ResourceLocation queuedCurrency;
    private static final long PENDING_FOCUS_TTL_MS = 10_000L;
    // initScaled 已切好分类、但该商品所在 chunk 还没到（entriesByKey 里还没有实体）时挂在这里，
    // 由 renderScaledBackground 每帧重试，chunk 一到就选中。-1 = 没有待选中的商品。
    private long pendingSelectEntryKey = -1L;
    /** 目錄還沒就緒時掛起的「記住選取」stableId。就緒後能解析就選中，解析不到就放棄。 */
    private String pendingRestoreStableId;
    // 开场只缩放整块面板一次。切分类只横移网格。换商品只换详情文字，不再次缩放。
    private final Spring openMotion = Spring.opening(Spring.PANEL);
    private long gridSwitchAtMs;
    // 卡片错峰入场：开店、切分组、搜索 / 收藏筛选后，当前可见范围内的格子按 (列+行) 错开启动。
    private static final long STAGGER_STEP_MS = 16L;
    private static final long STAGGER_MAX_DELAY_MS = 240L;
    private static final long STAGGER_TAIL_MS = 450L;
    private final SpringTable<Long> staggerCells = new SpringTable<>(Spring.STAGGER);
    private boolean pendingStagger = true;   // 开店即触发一次
    private float staggerFrom = 0f;          // 本次错峰的起始进度（0 = 从透明浮入）
    private long staggerUntilMs;
    // hover 过渡：key 用卡片 entryKey / 按钮与页签的 (x, y, salt) 打包值；每帧 sweep 一次。
    private final SpringTable<Long> hoverCells = new SpringTable<>(Spring.HOVER);
    private final SpringTable<Long> hoverButtons = new SpringTable<>(Spring.HOVER);
    private final SpringTable<Long> hoverTabs = new SpringTable<>(Spring.HOVER);
    // 按键即时反馈：压下/回弹、连发、等待回执与结果闪色（见 ButtonFeedback）；hover 仍是上面的 hoverButtons。
    private final ButtonFeedback buttonFeedback = new ButtonFeedback(hoverButtons);
    private final ShopTooltipMotion tooltipMotion = new ShopTooltipMotion();
    private static final int DETAIL_GUIDE = 1, DETAIL_AUTO_CRAFT = 2, DETAIL_BOOKMARK = 3;
    private long detailTipKey;
    private long previewTipKey;
    private com.dishanhai.gt_shanhai.client.shop.ShopGuideLookup.GuideHit pendingGuideHit;
    private long pendingGuideAtMs, pendingGuideKey, replayGuideSuccessKey;
    private boolean replayGuideSuccess;
    // 本帧逻辑鼠标坐标：详情滚动区的按钮按内容坐标画，drawButton 靠它换回屏幕坐标，才对得上 buttonHit 记的 key
    private int frameMy;
    private boolean reduceMotionFrame;
    // 本次 Enter 已经触发过确认：按住时的系统重复一律吞掉，松开才复位
    private boolean enterHeld;

    private static long widgetKey(int x, int y, int salt) {
        return ((long) x * 73856093L) ^ ((long) y * 19349663L) ^ ((long) salt * 83492791L);
    }

    /** 按下态的 key：只由按钮几何决定（buttonHit 手上没有标签，标签还会变），和 hover 的 key 分开。 */
    private static long pressKey(int x, int y, int w, int h) {
        return widgetKey(x, y, (w << 12) ^ h ^ 0x5A5A0000);
    }

    private static final int TAB_STRIP_BUYSELL = 4;
    private final TabIndicator[] tabIndicators = {
            new TabIndicator(), new TabIndicator(), new TabIndicator(), new TabIndicator(), new TabIndicator()};

    /** 一排页签共用的一块滑动高亮：x、宽各一根弹簧。 */
    private static final class TabIndicator {
        final Spring x = new Spring(Spring.INDICATOR);
        final Spring w = new Spring(Spring.INDICATOR);
        int signature;
        boolean valid;

        /** 这排的页签列表变了（sig 不同）就直接到位，不从旧排的位置滑过来。 */
        void follow(float tx, float tw, int sig, long now) {
            if (!valid || sig != signature) {
                x.snap(tx);
                w.snap(tw);
                signature = sig;
                valid = true;
                return;
            }
            if (x.target() != tx) x.setTarget(tx, now);
            if (w.target() != tw) w.setTarget(tw, now);
            x.update(now);
            w.update(now);
        }

        void reset() { valid = false; }
    }
    /** 开场或切页的 150ms 里少解析，避免把缩放/横移卡掉。落位后把预算留给可视格子。 */
    private static final long LOAD_PUMP_MOTION_NS = 400_000L;
    private static final long LOAD_PUMP_SETTLED_NS = 2_500_000L;
    /** 绘制仍只超扫 1 行；预取再多 2 行，滚进去之前 chunk 已经在路上。 */
    private static final int GRID_LOAD_OVERSCAN = 3;
    /** 占位文字只横移这一段，格子和物品槽不动。 */
    private static final float LOAD_CELL_SLIDE_PX = 6f;
    private static final long LOAD_BLUR_PERIOD_MS = 900L;
    private static final int LOAD_BLUR_CAP = ShopGaussBlur.MAX_CELL_RECTS;
    private final Map<Long, Long> loadingCellAtMs = new HashMap<>();
    private final ArrayList<Long> frameLoadKeys = new ArrayList<>();
    private final int[] loadBlurVirtual = new int[LOAD_BLUR_CAP * 4];
    private final int[] loadBlurFb = new int[LOAD_BLUR_CAP * 4];
    private int loadBlurRuns;
    private int loadSeen;
    private int loadMissing;
    private int unionX0, unionY0, unionX1, unionY1;
    private boolean loadRunOpen;
    private int loadRunX, loadRunY, loadRunR, loadRunB, loadRunRow, loadRunPrevIdx;

    // ===== 商品卡片 → 右侧详情面板的共享元素飞行 =====
    // 点格子时，被点的卡片沿一条几何通道（位置 + 尺寸同时插值）长到详情面板大小；
    // 源卡片留在原位，用磨砂 + 偏移重绘近似高斯模糊，配合残影模拟失焦（不采样 framebuffer）。
    // 时钟是毫秒；弹簧只保留 value/velocity，落位即止。
    private final GuiRenderUtil.FluidSpring cardFlightSpring = new GuiRenderUtil.FluidSpring();
    private boolean cardFlightActive;
    private ShopEntry cardFlightEntry;
    private int cardFlightSrcX;
    private int cardFlightSrcY;
    private int cardFlightSrcW;
    private int cardFlightSrcH;
    private int cardFlightDstX;
    private int cardFlightDstY;
    private int cardFlightDstW;
    private int cardFlightDstH;
    // 最近几帧的进度，用来画残影（越旧越大、越淡），不需要保存整屏截图
    private final float[] cardFlightTrails = new float[3];
    // 右侧高斯模糊比位移晚一拍。弹簧从速度 0 起手，若 Progress 第一帧就是 1，
    // 玩家会先看到面板糊掉，卡片才离开原位。前 32ms 保持清晰，再用 48ms 淡入。
    private static final float PANEL_BLUR_DELAY_MS = 32f;
    private static final float PANEL_BLUR_IN_MS = 48f;
    private long cardFlightStartMs;
    private long amount = 1L;      // 购买/出售次数，支持 Long.MAX
    private AnimatableEditBox searchBox;
    private AnimatableEditBox amountBox;
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
        float fromSlot;           // 当前视觉槽位。改目标时从这里接着走，不跳回旧目标
        int toSlot;               // 目标槽位：0=最新（贴底），数值越大越靠上
        long slotChangedAtMs;
    }

    private static final java.util.List<FlashMsg> flashActive = new java.util.ArrayList<>();  // 当前占着槽位的（≤FLASH_MAX_VISIBLE 条）
    private static final java.util.List<FlashMsg> flashLeaving = new java.util.ArrayList<>();  // 正在滑出动画中的，播完即摘除
    private static final int FLASH_MAX_VISIBLE = 3;
    private static final long FLASH_DISPLAY_MS = 5000L;
    private static final long FLASH_SLIDE_MS = GuiRenderUtil.MOTION_MS;
    private static final int FLASH_ROW_H = 17;          // 每行高度（含 1px 行间隙）
    /** 进场起点槽位。约 20px，第一帧就有约 4px 露在面板底缘内。 */
    private static final float FLASH_ENTER_FROM = -1.18f;
    /**
     * 按键按下：液态玻璃波从点击点向外扩。
     * 光靠高斯糊不动纯色面板，所以这条动效由三层叠成：
     * ① 波峰处的径向折射位移（画面被推开）、② 圆盘内的透镜放大、③ 波峰高光。
     * 后两层在 shader 里做；③ 再叠一条 GUI 直绘的亮环兜底，shader 不可用时也看得见。
     */
    private static final float CLICK_WAVE_FROM_PX = 6f;
    private static final float CLICK_WAVE_TO_PX = 46f;
    /** 波峰厚度（虚拟像素）。太薄看不出来，太厚会糊成一团。 */
    private static final float CLICK_WAVE_BAND_PX = 9f;
    private static final long CLICK_WAVE_MS = 240L;
    private long clickWaveAtMs;
    private int clickWaveX;
    private int clickWaveY;

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
        m.fromSlot = FLASH_ENTER_FROM; // 从面板底缘升起，不从已经露出来的位置硬切
        m.toSlot = 0;
        m.slotChangedAtMs = now;
        flashActive.add(0, m);
        resyncFlashSlots(now);
    }

    /** 消息离开 active 队列（自然到期或被挤）：改往上滑出可见区域，进 leaving 表播完动画再摘除。 */
    private static void beginLeave(FlashMsg m, long now) {
        m.fromSlot = flashVisualSlot(m, now);
        m.toSlot = FLASH_MAX_VISIBLE;
        m.slotChangedAtMs = now;
        m.leaveDeadlineMs = now + FLASH_SLIDE_MS;
        flashLeaving.add(m);
    }

    /** 改目标槽位时留下当前视觉位置，不把半路的消息拉回旧终点。 */
    private static void resyncFlashSlots(long now) {
        for (int i = 0; i < flashActive.size(); i++) {
            FlashMsg m = flashActive.get(i);
            if (m.toSlot != i) {
                m.fromSlot = flashVisualSlot(m, now);
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

    private static float flashVisualSlot(FlashMsg m, long now) {
        float t = FLASH_SLIDE_MS <= 0 ? 1f : Math.min(1f, (now - m.slotChangedAtMs) / (float) FLASH_SLIDE_MS);
        return m.fromSlot + (m.toSlot - m.fromSlot) * GuiRenderUtil.easeOut(t);
    }

    // 删除误触防护：需在窗口期内对同一条目再点一次「删除此商品」才真正执行；实例级=只跟当前这次打开的界面走
    private ShopEntry pendingDeleteEntry;
    private long pendingDeleteArmedAtMs;
    private static final long DELETE_CONFIRM_WINDOW_MS = 3000L;
    /** Alt + 左鍵批量名單：只存 stableId，跨分類、搜尋、滾動與目錄 revision 仍能重新解析。 */
    private final LinkedHashSet<String> batchSelectionStableIds = new LinkedHashSet<>();
    /** 批量刪除二次確認快照；與單項 pendingDeleteEntry 分開，避免切換卡片時誤確認。 */
    private final LinkedHashSet<String> pendingDeleteBatchIds = new LinkedHashSet<>();
    private long pendingDeleteBatchArmedAtMs;
    // 撤销上一次删除：静态=跨界面实例保留（删完手滑关掉界面也还能点），服务端另有独立 30 秒兜底窗口（见 ShopConfig#undoLastRemove）
    private static String undoDeleteLabel;
    private static long undoDeleteUntilMs;
    private static final long UNDO_DELETE_UI_WINDOW_MS = 8000L;
    // 撤销上一次排序（前移/后移/置顶）：同上一套静态悬浮按钮写法，服务端另有独立 30 秒兜底窗口（见 ShopConfig#undoLastMove）。
    // 置顶没有对称的反向操作，不靠这个的话点错了只能一路点「后移」手动挪回去，见反馈。
    private static String undoReorderLabel;
    private static long undoReorderUntilMs;
    private static final long UNDO_REORDER_UI_WINDOW_MS = 8000L;
    private int scroll = 0; // 网格滚动像素（显示值：画、命中、滚动条都读它；目标见 scrollTarget）
    private int scrollTarget = 0; // 网格滚动目标：滚轮、跳转改它，弹簧追它
    private final Spring gridScrollSpring = new Spring(Spring.SCROLL);
    private boolean draggingGridScroll; // 正在拖拽网格右侧滚动条
    private int detailScroll = 0; // 详情页中段内容滚动像素（显示值）
    private int detailScrollTarget = 0; // 详情页滚动目标
    private final Spring detailScrollSpring = new Spring(Spring.SCROLL);
    private int detailScrollMax = 0; // drawDetail 每帧按真实内容高度回填
    private boolean draggingDetailScroll; // 正在拖拽详情页中段滚动条
    private final boolean canEdit; // 服务端下发的编辑权（OP 或白名单）：决定编辑条目/商店设置/排序等按钮显隐
    public boolean canEdit() { return canEdit; } // 供子页（如 CurrencyAtmScreen）跳转兑换中心时透传编辑权
    // 服务端下发：canEdit 基础上是否还开了 /山海 商店 编辑 编辑模式，决定新增/删除商品这类目录增删按钮显隐
    private final boolean catalogEditUnlocked;

    private ShopEntry visibleEntry(int index) {
        if (index < 0 || index >= visibleEntryKeys.size()) return null;
        return ClientShopCatalog.get(visibleEntryKeys.get(index));
    }

    private void selectEntry(long entryKey, ShopEntry entry) {
        // 从别处（跳转链接、右键菜单、恢复上次选中）改选时，正在飞的旧卡片要立刻收掉，
        // 避免残影落到一个已经不显示的条目上。点格子会先 beginCardFlight 再调本方法，不会误取消。
        if (cardFlightActive && entry != cardFlightEntry) {
            cardFlightActive = false;
            cardFlightEntry = null;
            ShopGaussBlur.clear();
        }
        pendingRestoreStableId = null;
        long newKey = entry == null ? -1L : entryKey;
        if (newKey != selectedEntryKey) {
            snapDetailScroll(0);
            detailScrollMax = 0;
        }
        selectedEntryKey = newKey;
        selected = entry;
    }

    private void clearSelection() {
        pendingRestoreStableId = null;
        selectedEntryKey = -1L;
        selected = null;
        snapDetailScroll(0);
        detailScrollMax = 0;
        cardFlightActive = false;
        cardFlightEntry = null;
        ShopGaussBlur.clear();
    }

    /**
     * 登记一次“卡片飞进详情面板”。调用方必须紧跟着 {@link #selectEntry}，让飞行层拿到新选中项；
     * 源矩形是点击时格子的绝对布局坐标（已经含滚动偏移），目标矩形是右侧详情列。
     */
    private void beginCardFlight(ShopEntry entry, int srcX, int srcY) {
        if (entry == null) return;
        this.cardFlightEntry = entry;
        this.cardFlightSrcX = srcX;
        this.cardFlightSrcY = srcY;
        this.cardFlightSrcW = cellW();
        this.cardFlightSrcH = cellH();
        this.cardFlightDstX = detailX();
        this.cardFlightDstY = contentTop();
        this.cardFlightDstW = DETAIL_W;
        this.cardFlightDstH = contentHeight();
        // 离详情面板越远，飞得越久：用起手时两块矩形的中心距离换算弹簧 response（0.16s ~ 0.32s）。
        double dx = (this.cardFlightDstX + this.cardFlightDstW / 2.0) - (this.cardFlightSrcX + this.cardFlightSrcW / 2.0);
        double dy = (this.cardFlightDstY + this.cardFlightDstH / 2.0) - (this.cardFlightSrcY + this.cardFlightSrcH / 2.0);
        double response = GuiRenderUtil.flightResponseForDistance(Math.hypot(dx, dy));
        long now = System.currentTimeMillis();
        this.cardFlightStartMs = now;
        this.cardFlightSpring.start(now, response);
        for (int i = 0; i < this.cardFlightTrails.length; i++) this.cardFlightTrails[i] = 0f;
        this.cardFlightActive = true;
    }

    private boolean isBatchSelected(ShopEntry entry) {
        return entry != null && batchSelectionStableIds.contains(entry.getStableId());
    }

    private List<String> batchIdsSnapshot() {
        return List.copyOf(batchSelectionStableIds);
    }

    private List<ShopEntry> batchEntries() {
        List<ShopEntry> result = new ArrayList<>();
        for (String stableId : batchSelectionStableIds) {
            long key = ClientShopCatalog.keyOfStableId(stableId);
            ShopEntry entry = key >= 0L ? ClientShopCatalog.get(key) : null;
            if (entry != null) result.add(entry);
        }
        return result;
    }

    private void toggleBatchSelection(ShopEntry entry) {
        if (entry == null || entry.getStableId() == null || entry.getStableId().isBlank()) return;
        if (!batchSelectionStableIds.add(entry.getStableId())) {
            batchSelectionStableIds.remove(entry.getStableId());
        }
        showMessage(Component.literal(batchSelectionStableIds.contains(entry.getStableId())
                ? "§b[山海商店] §a已加入批量名单: §f" + entry.goodsDisplayName()
                : "§b[山海商店] §7已移出批量名单: §f" + entry.goodsDisplayName()));
    }

    private boolean batchDeleteArmed() {
        return !pendingDeleteBatchIds.isEmpty()
                && System.currentTimeMillis() - pendingDeleteBatchArmedAtMs < DELETE_CONFIRM_WINDOW_MS;
    }

    private void clearBatchSelection() {
        batchSelectionStableIds.clear();
        pendingDeleteBatchIds.clear();
        pendingDeleteBatchArmedAtMs = 0L;
    }
    private String previewHoverName; // 详情页花费预览槽悬停名（drawDetail 暂存 → renderTooltips 消费）
    private String previewHoverExtra; // 悬停槽的"拥有/缺少"提示行（drawPreviewSlot 暂存 → renderTooltips 消费），无数据（未同步/加载中）为 null
    private String previewHoverOrder; // 悬停槽的「可下单/无样板」行，未核对时为 null
    private ItemStack previewHoverStack; // 获得/花费预览格悬停的真实物品（含 NBT），renderTooltips 画原版物品 tooltip
    private long previewRequestedKey = -1L;      // 花费预览最近一次发起请求时对应的商品；变了要重新请求
    private boolean previewRequestedAeMode = false; // 花费预览最近一次发起请求时的 AE 模式状态；切换要重新请求
    private long previewRequestedAtGameTime = Long.MIN_VALUE; // 最近一次发起请求的游戏刻，供周期性兜底刷新节流
    private static final long PREVIEW_REFRESH_TICKS = 40L; // 花费预览周期兜底刷新间隔（2秒）：存量会随玩家操作背包/AE网络实时变化
    private String stagePreviewRequestedPath;
    private boolean stagePreviewRequestedAeMode;
    private long stagePreviewRequestedAtGameTime = Long.MIN_VALUE;
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
    /** 「收藏缺失」：把花费里已经确定不够的物品/流体写入 JEI 书签，和 GTLCore 合成确认页同一用途。 */
    private boolean favoriteMissingBtnVisible;
    private int favoriteMissingBtnX, favoriteMissingBtnY, favoriteMissingBtnW, favoriteMissingBtnH;
    // 「购买原料」按钮：当前商品成本含商店可购买的物品原料时出现，服务端按实际缺口购买。
    private boolean buyMaterialsBtnVisible;
    private int buyMaterialsBtnX, buyMaterialsBtnY, buyMaterialsBtnW, buyMaterialsBtnH;
    // 「前置任务」跳转行：条目配了 prerequisiteQuestId 才出现，点击跳到 FTBQ 任务书对应任务，样式同「跳转」
    private boolean prereqLinkVisible;
    private dev.ftb.mods.ftbquests.quest.Quest prereqQuest;
    private boolean prereqQuestCompleted;
    private int prereqLinkX, prereqLinkY, prereqLinkW, prereqLinkH;
    private boolean submissionBtnVisible, stageSubmissionBtnVisible;
    private int submissionBtnX, submissionBtnY, submissionBtnW, submissionBtnH;
    private int stageSubmissionBtnX, stageSubmissionBtnY, stageSubmissionBtnW, stageSubmissionBtnH;
    private int submissionAutoCraftX, submissionAutoCraftY, submissionAutoCraftW, submissionAutoCraftH;
    private int stageSubmissionAutoCraftX, stageSubmissionAutoCraftY, stageSubmissionAutoCraftW, stageSubmissionAutoCraftH;
    private int stageLockAutoCraftX, stageLockAutoCraftY, stageLockAutoCraftW, stageLockAutoCraftH;
    private boolean submissionAutoCraftVisible, stageSubmissionAutoCraftVisible, stageLockAutoCraftVisible;
    private boolean stageLockActive;
    private String stageLockPath;
    private int stageLockSubmitX, stageLockSubmitY, stageLockSubmitW, stageLockSubmitH;
    // 网格卡片右键快捷菜单：导航跳转合集 + 管理效率（编辑权），条目变了/权限变了菜单项动态重算，不用固定坐标+布尔位那一套
    private boolean ctxMenuOpen;
    private long ctxMenuEntryKey = -1L;
    private int ctxMenuX, ctxMenuY, ctxMenuW;
    private List<ContextMenuItem> ctxMenuItems = List.of();
    private static final int CTX_MENU_ROW_H = 12;
    /** 一条右键菜单项：命中即执行 action 并收起菜单；separatorBefore=true 时上方先画一道分隔线（分组用）。 */
    private record ContextMenuItem(String label, boolean separatorBefore, Runnable action) {}

    // 快速分组（右键菜单「⇄ 快速分组」/ 卡片拖到某个分类页签上松开）：列出当前已知的完整分类树供选择，
    // 选中即把该商品的 category 换成那条路径。跟「编辑条目」走同一个 ShopEditPacket EDIT 协议，只改
    // category 一个字段（见 sendQuickRegroup），因此同样要求 catalogEditUnlocked（编辑模式），
    // 比单纯排序（前移/后移/置顶/网格内拖拽）门槛更高——这是结构性改动，不只是视觉顺序。
    private boolean groupPickerOpen;
    private ShopEntry groupPickerEntry;
    private long groupPickerEntryKey = -1L;
    private List<String> groupPickerBatchIds = List.of();
    private int groupPickerScroll;
    private List<String> groupPickerOptions = List.of();
    private static final int GROUP_PICKER_ROW_H = 14;

    // 购物车：顶栏微缩按键展开的独立大图层；Ctrl+左键网格卡片加入候选，按 stableId 引用条目（跨快照/跨重登有效，
    // 见 ClientShopCart），数量可调、可单独删除，结算=按候选清单逐项各自发起购买（异步、互不阻塞）。
    private boolean cartOverlayOpen;
    // 浮层开关弹簧：打开=目标 1，关闭=目标 0。布尔 *Open 仍是逻辑状态（输入立刻被拦），弹簧只决定画多少。
    private static final float OVERLAY_VISIBLE_EPS = 0.01f;
    private final Spring cartMotion = new Spring(Spring.PANEL);
    private final Spring descMotion = new Spring(Spring.PANEL);
    private final Spring guideMotion = new Spring(Spring.PANEL);
    private final Spring groupPickerMotion = new Spring(Spring.PANEL);
    private final Spring ctxMotion = new Spring(Spring.PANEL);
    private long stageLockShownAtMs;
    private int cartOverlayScroll;
    private final Map<Long, Long> cartPreviewRequestedAtGameTime = new java.util.HashMap<>();
    private boolean cartPreviewRequestedAeMode;
    // 结算批次：点「结算购物车」时把当次涉及的 stableId 快照进来，底部按钮区在批次未全部回执前
    // 变成进度条（X/总数），全部回执后改回按钮，但已回执的行状态（成功戳记覆盖控件/失败原因）
    // 一直留到关闭购物车面板才清（见反馈：进度跑完要能直接在面板里看到哪些成功哪些失败）。
    private final List<String> cartSettleBatch = new ArrayList<>();
    private long cartSettleBatchStartedAtMs;

    // ===== 动态面板尺寸（每次 initScaled 重算）=====
    private int left, top, panelWidth, panelHeight;

    // 计算态
    private List<String> categories = new ArrayList<>();     // 主分类页签
    private List<String> subCategories = new ArrayList<>();  // 当前主分类下的二级分类
    private List<String> subCategories2 = new ArrayList<>(); // 当前二级分类下的三级分类
    private List<String> subCategories3 = new ArrayList<>(); // 当前三级分类下的四级分类
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

    /** 二级分类页签行 Y（主分类有二级分类时才显示）。 */
    private int subTabsY() { return tabsY() + TAB_H + 2; }
    /** 三级分类页签行 Y（选中了具体二级分类、且其下还有三级分类时才显示，紧跟二级页签行）。 */
    private int subTabs2Y() { return subTabsY() + TAB_H + 2; }
    /** 四级分类页签行 Y（选中了具体三级分类、且其下还有四级分类时才显示，紧跟三级页签行）。 */
    private int subTabs3Y() { return subTabs2Y() + TAB_H + 2; }

    /** 当前主分类是否有二级分类页签。 */
    private boolean hasSubTabs() { return !subCategories.isEmpty(); }
    /** 当前二级分类选中了具体项（非「全部」）且其下是否还有三级分类页签。 */
    private boolean hasSubTabs2() { return !selectedSub.isEmpty() && !subCategories2.isEmpty(); }
    /** 当前三级分类选中了具体项（非「全部」）且其下是否还有四级分类页签。 */
    private boolean hasSubTabs3() { return !selectedSub2.isEmpty() && !subCategories3.isEmpty(); }

    /**
     * 当前视图是否精确对应唯一一个分类字符串（每一层要么没有子页签，要么选中了具体项而非「全部」）。
     * 只有叶子视图下 visibleEntryKeys 里的商品才保证全部共享同一个 {@code entry.getCategory()}，
     * 商品卡片拖拽排序（本地下标语义按该字符串的同分类子序列算）才有意义，见 beginCardDrag。
     */
    private boolean isLeafCategoryView() {
        if (hasSubTabs() && selectedSub.isEmpty()) return false;
        if (hasSubTabs2() && selectedSub2.isEmpty()) return false;
        if (hasSubTabs3() && selectedSub3.isEmpty()) return false;
        return true;
    }

    /** 当前所在商店子页的完整分类名（"主/子/子子/子子子"，选「全部」的层级及更深层不拼入）。供「新增商品」继承默认分类。 */
    private String currentViewCategory() {
        if (selectedTop == null || selectedTop.isEmpty()) return ShopEntry.DEFAULT_CATEGORY;
        StringBuilder sb = new StringBuilder(selectedTop);
        if (selectedSub.isEmpty()) return sb.toString();
        sb.append('/').append(selectedSub);
        if (selectedSub2.isEmpty()) return sb.toString();
        sb.append('/').append(selectedSub2);
        if (selectedSub3.isEmpty()) return sb.toString();
        sb.append('/').append(selectedSub3);
        return sb.toString();
    }

    private String missingStageSubmission(String category) {
        if (category == null || category.isBlank()) return null;
        String[] parts = category.split("/");
        StringBuilder path = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            if (path.length() > 0) path.append('/');
            path.append(part);
            String candidate = path.toString();
            if (com.dishanhai.gt_shanhai.client.shop.ClientShopUnlockState.hasStageRequirement(candidate)
                    && !com.dishanhai.gt_shanhai.client.shop.ClientShopUnlockState.hasStage(candidate)) return candidate;
        }
        return null;
    }

    /** 当前分类页是否被阶段提交限制锁定；打开商店编辑模式时保留管理视图。 */
    private String lockedStageForCurrentView() {
        if (mode != Mode.BUY || catalogEditUnlocked) return null;
        return missingStageSubmission(currentViewCategory());
    }

    private static String[] splitCardCategoryPath(String category) {
        String c = (category == null || category.isBlank()) ? ShopEntry.DEFAULT_CATEGORY : category.trim();
        String[] raw = c.split("/", 4);
        String[] parts = {"", "", "", ""};
        for (int i = 0; i < raw.length && i < parts.length; i++) {
            parts[i] = raw[i] == null ? "" : raw[i].trim();
        }
        return parts;
    }

    private static int currentCategoryDepth() {
        if (selectedTop == null || selectedTop.isEmpty()) return 0;
        if (selectedSub.isEmpty()) return 1;
        if (selectedSub2.isEmpty()) return 2;
        if (selectedSub3.isEmpty()) return 3;
        return 4;
    }

    private static boolean cardCategoryMatchesCurrentPrefix(String[] parts, int depth) {
        if (depth <= 0) return true;
        if (!parts[0].equals(selectedTop == null ? "" : selectedTop)) return false;
        if (depth >= 2 && !parts[1].equals(selectedSub)) return false;
        if (depth >= 3 && !parts[2].equals(selectedSub2)) return false;
        return depth < 4 || parts[3].equals(selectedSub3);
    }

    /**
     * 商品卡片上的小分类字段：当前页停在父分类时，显示该商品的下一层分类。
     * 例如当前页是「无限盘区/前期」，条目属于「无限盘区/前期/ulv」时显示「ulv」。
     */
    private static String cardCategoryBadge(ShopEntry entry) {
        if (entry == null) return "";
        String[] parts = splitCardCategoryPath(entry.getCategory());
        int depth = currentCategoryDepth();
        if (cardCategoryMatchesCurrentPrefix(parts, depth)) {
            for (int i = depth; i < parts.length; i++) {
                if (!parts[i].isEmpty()) return parts[i];
            }
            return "";
        }
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isEmpty()) return parts[i];
        }
        return "";
    }

    /** 网格/详情内容区顶部 Y（每显示一行子页签就再下移一行）。 */
    private int contentTop() {
        int rows = (hasSubTabs() ? 1 : 0) + (hasSubTabs2() ? 1 : 0) + (hasSubTabs3() ? 1 : 0);
        return tabsY() + TAB_H + 6 + rows * (TAB_H + 2);
    }

    // ===== 分类切换：切页 + 记忆恢复（见反馈：切走再切回要带回原子页，不要求全部塌陷）=====

    private static String pathKey(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(p == null ? "" : p).append('#');
        return sb.toString();
    }

    private static String rememberedChild(String parentKey) {
        String v = lastChildByPath.get(parentKey);
        return v == null ? "" : v;
    }

    private static void rememberChild(String parentKey, String child) {
        lastChildByPath.put(parentKey, child);
    }

    /** 切主分类：从记忆恢复该主分类下完整的子路径（sub/sub2/sub3），而不是重置为全部。 */
    private static void switchTop(String cat) {
        selectedTop = cat;
        selectedSub = rememberedChild(pathKey(selectedTop));
        selectedSub2 = selectedSub.isEmpty() ? "" : rememberedChild(pathKey(selectedTop, selectedSub));
        selectedSub3 = selectedSub2.isEmpty() ? "" : rememberedChild(pathKey(selectedTop, selectedSub, selectedSub2));
    }

    /** 切二级分类（sub=""=全部）：记住这次选择，并从记忆恢复/折叠更深层级。 */
    private static void switchSub(String sub) {
        selectedSub = sub;
        rememberChild(pathKey(selectedTop), sub);
        selectedSub2 = sub.isEmpty() ? "" : rememberedChild(pathKey(selectedTop, sub));
        selectedSub3 = selectedSub2.isEmpty() ? "" : rememberedChild(pathKey(selectedTop, sub, selectedSub2));
    }

    /** 切三级分类（sub2=""=全部）：记住这次选择，并从记忆恢复/折叠四级。 */
    private static void switchSub2(String sub2) {
        selectedSub2 = sub2;
        rememberChild(pathKey(selectedTop, selectedSub), sub2);
        selectedSub3 = sub2.isEmpty() ? "" : rememberedChild(pathKey(selectedTop, selectedSub, sub2));
    }

    /** 切四级分类（sub3=""=全部，最深一级，没有更深层要恢复/折叠）：只记住这次选择。 */
    private static void switchSub3(String sub3) {
        selectedSub3 = sub3;
        rememberChild(pathKey(selectedTop, selectedSub, selectedSub2), sub3);
    }

    // ===== 分类页签行的统一描述（level 0..3 = 主/二级/三级/四级）：渲染、点击、拖拽排序四层结构完全
    // 一致，只是数据来源不同，统一走这一套 rowXxx/switchAt/performTabSwitch，不再四份重复代码。=====

    private List<String> rowItems(int level) {
        return switch (level) {
            case 0 -> categories;
            case 1 -> subCategories;
            case 2 -> subCategories2;
            default -> subCategories3;
        };
    }

    private void applyRowItems(int level, List<String> items) {
        switch (level) {
            case 0 -> categories = items;
            case 1 -> subCategories = items;
            case 2 -> subCategories2 = items;
            default -> subCategories3 = items;
        }
    }

    private String rowSelected(int level) {
        return switch (level) {
            case 0 -> selectedTop;
            case 1 -> selectedSub;
            case 2 -> selectedSub2;
            default -> selectedSub3;
        };
    }

    private int rowY(int level) {
        return switch (level) {
            case 0 -> tabsY();
            case 1 -> subTabsY();
            case 2 -> subTabs2Y();
            default -> subTabs3Y();
        };
    }

    private boolean rowVisible(int level) {
        return switch (level) {
            case 0 -> true;
            case 1 -> hasSubTabs();
            case 2 -> hasSubTabs2();
            default -> hasSubTabs3();
        };
    }

    /** 第 level 层排序落地用的父路径（"/"拼接，跟 ShopConfig#discoveredCategoriesAt 的切分格式一致）。 */
    private String rowOrderParentPath(int level) {
        return switch (level) {
            case 0 -> "";
            case 1 -> selectedTop;
            case 2 -> selectedTop + "/" + selectedSub;
            default -> selectedTop + "/" + selectedSub + "/" + selectedSub2;
        };
    }

    private void switchAt(int level, String value) {
        switch (level) {
            case 0 -> switchTop(value);
            case 1 -> switchSub(value);
            case 2 -> switchSub2(value);
            default -> switchSub3(value);
        }
    }

    /**
     * 真正切页（不是拖拽排序）：普通点击「全部」或非编辑者点任意页签都走这条——保留原来的滑入方向
     * 判定 + 动画触发；level 3（四级，最深层）点击不影响更深层是否显示，沿用轻量 recomputeVisible，
     * 其余层级点击可能改变更深层页签是否显示，需要 this.init() 重排（同原有分层逻辑）。
     */
    private void performTabSwitch(int level, String value) {
        switchAt(level, value);
        clearSelection();
        snapGridScroll(0);
        loadingCellAtMs.clear();
        gridSwitchAtMs = System.currentTimeMillis();
        requestStagger(0f);
        if (level == 3) recomputeVisible();
        else this.init(Minecraft.getInstance(), this.width, this.height);
    }

    /**
     * 按下页签：value=""（「全部」伪页签，不可拖拽）或没有编辑权限时直接按原逻辑立即切页；
     * 否则先记录按下起点，进入「潜在拖拽」追踪，真正是点击还是拖拽要等 mouseReleased 才知道
     * （见 universalMouseDragged 的阈值判定、universalMouseReleased 的分支）。
     */
    private void beginTabAction(int level, String value, double mx, double my) {
        if (value.isEmpty() || !canEdit) {
            performTabSwitch(level, value);
            return;
        }
        dragTabLevel = level;
        dragTabCategory = value;
        dragTabStartX = mx;
        dragTabStartY = my;
        dragTabMx = mx;
        dragTabMy = my;
        dragTabMoving = false;
    }

    /** 命中检测某一层页签行；命中即消费掉这次点击并进入 beginTabAction。 */
    private boolean tabRowClicked(double mx, double my, int level, int btn) {
        if (!rowVisible(level)) return false;
        int y = rowY(level);
        int x = left + 14;
        int tabsRight = detailX() - 8;
        if (level > 0) {
            int aw = Math.max(30, this.font.width("全部") + 10);
            if (hit(mx, my, x, y, aw, TAB_H)) {
                armClickWave(mx, my);
                beginTabAction(level, "", mx, my);
                return true;
            }
            x += aw + 3;
        }
        for (String item : rowItems(level)) {
            int tw = Math.max(30, this.font.width(item) + 10);
            if (x + tw > tabsRight) break;
            if (hit(mx, my, x, y, tw, TAB_H)) {
                armClickWave(mx, my);
                if (btn == 1 && canEdit) {
                    String parentPath = rowOrderParentPath(level);
                    String stagePath = parentPath.isEmpty() ? item : parentPath + "/" + item;
                    Minecraft.getInstance().setScreen(new ShopStageEditScreen(this, stagePath));
                    return true;
                }
                beginTabAction(level, item, mx, my);
                return true;
            }
            x += tw + 3;
        }
        return false;
    }

    /**
     * 按当前鼠标 X 换算目标下标：跟服务端 {@link com.dishanhai.gt_shanhai.common.shop.ShopConfig#moveCategoryTo}
     * 的下标语义一致——是"去掉拖拽项之后的列表"里的插入位置，不含「全部」伪页签（它不参与排序）。
     * 逐项比较到鼠标落在某一项左半边为止，落在所有项右侧则挪到最后。
     */
    private int computeTabDropIndex(int level, double mx) {
        int x = left + 14;
        if (level > 0) {
            int aw = Math.max(30, this.font.width("全部") + 10);
            x += aw + 3;
        }
        int index = 0;
        for (String item : rowItems(level)) {
            if (item.equals(dragTabCategory)) continue;
            int tw = Math.max(30, this.font.width(item) + 10);
            double mid = x + tw / 2.0;
            if (mx < mid) return index;
            x += tw + 3;
            index++;
        }
        return index;
    }

    /**
     * 商品卡片拖拽松开时如果落在某个分类页签的具体格子上，返回该页签对应的完整分类路径，供
     * {@link #sendQuickRegroup} 用；没落在任何页签上返回 null（走网格内重排那条路）。落在「全部」
     * 伪页签上 = 挪到这一层的父路径本身、不细分这一层，跟点击「全部」看到的聚合范围语义一致
     * （例如拖到 前期＞全部 = 分类改成裸的 "前期"，仍会出现在 前期＞全部 视图里，只是不挂任何子页）。
     */
    private String cardDropCategoryPath(double mx, double my) {
        for (int level = 0; level < 4; level++) {
            if (!rowVisible(level)) continue;
            int y = rowY(level);
            if (my < y || my >= y + TAB_H) continue;
            int x = left + 14;
            int tabsRight = detailX() - 8;
            String parentPath = rowOrderParentPath(level);
            if (level > 0) {
                int aw = Math.max(30, this.font.width("全部") + 10);
                if (hit(mx, my, x, y, aw, TAB_H)) return parentPath;
                x += aw + 3;
            }
            for (String item : rowItems(level)) {
                int tw = Math.max(30, this.font.width(item) + 10);
                if (x + tw > tabsRight) break;
                if (hit(mx, my, x, y, tw, TAB_H)) {
                    return parentPath.isEmpty() ? item : parentPath + "/" + item;
                }
                x += tw + 3;
            }
            return null; // 落在这一行的 Y 范围内但没对上任何具体格子
        }
        return null;
    }

    /**
     * 发排序包 + 乐观本地重排：网络包到服务端广播回新 manifest 之间有延迟，先在本地把这层列表挪到
     * 目标位置，避免松手瞬间页签"弹回原位"又跳到新位置的观感；服务端广播的新 manifest 到达后
     * initScaled 会用权威顺序覆盖这份乐观结果，不会长期不一致。
     */
    private void sendTabReorder(int level, String category, int newIndex) {
        String parentPath = rowOrderParentPath(level);
        ShanhaiNetwork.CHANNEL.sendToServer(
                new com.dishanhai.gt_shanhai.network.ShopCategoryReorderPacket(parentPath, category, newIndex));
        List<String> items = new ArrayList<>(rowItems(level));
        items.remove(category);
        int clamped = Math.max(0, Math.min(newIndex, items.size()));
        items.add(clamped, category);
        applyRowItems(level, items);
    }

    /**
     * 一排页签三层：底盒（未选中样式 + hover 渐变）→ 滑动高亮块 → 文字（颜色按高亮块覆盖比例过渡到金色）。
     * selected=-1 表示这排没有选中项（例如选中的页签正被拖走）；sig 变了说明这排的页签列表换了，高亮块直接到位。
     */
    private void drawTabStrip(GuiGraphics g, int stripId, int y, int h, int[] xs, int[] ws, String[] labels,
                              int selected, int sig, int mx, int my) {
        long now = System.currentTimeMillis();
        TabIndicator ind = tabIndicators[stripId];
        float[] hv = new float[xs.length];
        for (int i = 0; i < xs.length; i++) {
            boolean hover = GuiRenderUtil.isHovering(mx, my, xs[i], y, ws[i], h);
            hv[i] = hoverTabs.follow(widgetKey(xs[i], y, stripId), hover ? 1f : 0f, now);
            renderBox(g, xs[i], y, ws[i], h, GuiMotion.lerpColor(NUMBER_BAR_BORDER, GOLD_DARK, hv[i]), BOX_BG);
        }
        float ix = 0f;
        float iw = 0f;
        if (selected >= 0) {
            ind.follow(xs[selected], ws[selected], sig, now);
            ix = ind.x.value();
            iw = ind.w.value();
            renderBox(g, Math.round(ix), y, Math.max(1, Math.round(iw)), h, GOLD, ROW_BG);
        } else {
            ind.reset();
        }
        for (int i = 0; i < xs.length; i++) {
            float overlap = selected >= 0
                    ? GuiMotion.clamp01((Math.min(ix + iw, xs[i] + ws[i]) - Math.max(ix, xs[i])) / ws[i])
                    : 0f;
            int base = GuiMotion.lerpColor(GRAY, WHITE, hv[i]);
            int col = GuiMotion.lerpColor(base, GOLD, overlap);
            g.drawCenteredString(this.font, GuiRenderUtil.trimText(this.font, labels[i], ws[i] - 4),
                    xs[i] + ws[i] / 2, y + (h - 8) / 2, col);
        }
    }

    /** 渲染某一层页签行：拖拽中的页签本身不占位（原位置塌陷），改画成跟随鼠标的浮动块（高亮态）。 */
    private void renderTabRow(GuiGraphics g, int level, int mx, int my) {
        int y = rowY(level);
        int x = left + 14;
        int tabsRight = detailX() - 8;
        boolean dragging = dragTabLevel == level && dragTabMoving;
        String selected = rowSelected(level);
        List<Integer> xs = new ArrayList<>();
        List<Integer> ws = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        int selIdx = -1;
        if (level > 0) {
            int aw = Math.max(30, this.font.width("全部") + 10);
            if (selected.isEmpty()) selIdx = 0;
            xs.add(x);
            ws.add(aw);
            labels.add("§7全部");
            x += aw + 3;
        }
        for (String item : rowItems(level)) {
            if (dragging && item.equals(dragTabCategory)) continue;
            int tw = Math.max(30, this.font.width(item) + 10);
            if (x + tw > tabsRight) break;
            if (item.equals(selected)) selIdx = xs.size();
            xs.add(x);
            ws.add(tw);
            labels.add(item);
            x += tw + 3;
        }
        drawTabStrip(g, level, y, TAB_H,
                xs.stream().mapToInt(Integer::intValue).toArray(),
                ws.stream().mapToInt(Integer::intValue).toArray(),
                labels.toArray(new String[0]), selIdx, java.util.Objects.hash(level, labels), mx, my);
        if (dragging) {
            int tw = Math.max(30, this.font.width(dragTabCategory) + 10);
            int fx = (int) dragTabMx - tw / 2;
            drawTab(g, fx, y, tw, TAB_H, dragTabCategory, true, mx, my);
        }
    }

    /** 内容区可见高度（撑到面板底部留边）。 */
    private int contentHeight() { return Math.max(60, (top + panelHeight - 8) - contentTop()); }

    private static final int GRID_HINT_H = 22;
    private static final long GRID_HINT_ROTATE_MS = 5000L;

    /** 网格卡片区高度。底部留两行给操作提示，详情列仍用 {@link #contentHeight()}。 */
    private int gridHeight() { return Math.max(48, contentHeight() - GRID_HINT_H); }

    private int listLeft() { return left + 5; }

    /** 卡片宽度只在固定购买面板左侧的网格空间内收缩，不反向改变详情列或上方控件。 */
    private int cardWidthLimitForTenColumns() {
        int available = (left + panelWidth - 2 - DETAIL_W - 6) - listLeft() - 2;
        return Math.max(MIN_EFFECTIVE_CARD_W, (available - (DEFAULT_GRID_COLS - 1) * GRID_GAP) / DEFAULT_GRID_COLS);
    }

    private int cardHeightLimitForTargetRows() {
        int available = Math.max(1, gridHeight() - TARGET_GRID_ROWS * GRID_GAP);
        return Math.max(MIN_EFFECTIVE_CARD_H, available / TARGET_GRID_ROWS);
    }

    private int cellW() {
        return Math.max(MIN_EFFECTIVE_CARD_W,
                Math.min(ClientShopUiSettings.cardWidth(), cardWidthLimitForTenColumns()));
    }

    private int cellH() {
        return Math.max(MIN_EFFECTIVE_CARD_H,
                Math.min(ClientShopUiSettings.cardHeight(), cardHeightLimitForTargetRows()));
    }
    private int rowStride() { return cellH() + GRID_GAP; }
    private int colStride() { return cellW() + GRID_GAP; }

    /** 网格宽度：铺满到详情列左侧，默认按 10 列封顶。 */
    private int listWidth() {
        int maxGridW = DEFAULT_GRID_COLS * (cellW() + GRID_GAP) + 2;
        int available = (left + panelWidth - 2 - DETAIL_W - 6) - listLeft();
        return Math.min(maxGridW, Math.max(cellW() + 2, available));
    }

    private int gridColumns() {
        int usable = Math.max(1, listWidth() - 2);
        return Math.max(1, Math.min(DEFAULT_GRID_COLS, (usable + GRID_GAP) / (cellW() + GRID_GAP)));
    }

    private int detailX() { return listLeft() + listWidth() + 6; }

    // 搜索框仿 KE 放右侧详情列正上方（顶栏不再塞它）
    private int searchBoxX() { return detailX() + 4; }
    private int searchBoxY() { return tabsY() + 1; }

    private int buyTabX() { return left + 14; }
    private int buyTabW() { return BUY_TAB_W; }
    private int sellTabX() { return left + 14 + buyTabW() + 2; }
    private int sellTabW() { return SELL_TAB_W; }
    // 「货币中心」按钮：出售页签右侧，始终可见（打开 ATM 子页）
    private static final int CURRENCY_BTN_W = 64;
    private int currencyBtnX() { return sellTabX() + sellTabW() + 6; }
    private int currencyBtnW() { return CURRENCY_BTN_W; }
    // 「兑换中心」按钮：货币中心右侧，始终可见（打开兑换子页）
    private static final int EXCHANGE_BTN_W = 64;
    private int exchangeBtnX() { return currencyBtnX() + currencyBtnW() + 4; }
    private int exchangeBtnW() { return EXCHANGE_BTN_W; }
    // 「银会中心」按钮：兑换中心右侧，始终可见（打开会员购买+银行子页，见 ShopMembershipScreen）
    private static final int MEMBER_BTN_W = 64;
    private int memberBtnX() { return exchangeBtnX() + exchangeBtnW() + 4; }
    private int memberBtnW() { return MEMBER_BTN_W; }
    // 「新增商品」按钮：会员中心右侧，仅编辑权玩家且开了编辑模式（catalogEditUnlocked）才可见
    private static final int ADD_BTN_W = 64;
    private int addBtnX() { return memberBtnX() + memberBtnW() + 4; }
    private int addBtnW() { return ADD_BTN_W; }
    // 「商店设置」按钮：所有玩家可见（没有编辑权的只看到本机显示偏好），不受编辑模式开关限制；新增商品隐藏时顶上来补位，不留空档
    private static final int SETTINGS_BTN_W = 64;
    private int settingsBtnX() { return catalogEditUnlocked ? addBtnX() + addBtnW() + 4 : addBtnX(); }
    private int settingsBtnW() { return SETTINGS_BTN_W; }

    /** 供 CurrencyAtmScreen 读取当前 AE 模式（同包访问）。 */
    static boolean isAeMode() { return aeMode; }
    private int closeBtnW() { return CLOSE_W; }
    private int closeBtnX() { return left + panelWidth - 8 - closeBtnW(); }
    private int rechargeBtnW() { return RECHARGE_W; }
    private int rechargeBtnX() { return closeBtnX() - 4 - rechargeBtnW(); }
    // 「AE模式」切换：充值按钮左侧
    private static final int AE_BTN_W = 58;
    private int aeBtnW() { return AE_BTN_W; }
    private int aeBtnX() { return rechargeBtnX() - 4 - aeBtnW(); }
    // 「精妙背包模式」切换：AE模式按钮左侧
    private static final int BACKPACK_BTN_W = 78;
    private int backpackBtnW() { return BACKPACK_BTN_W; }
    private int backpackBtnX() { return aeBtnX() - 4 - backpackBtnW(); }
    // 购物车微缩按键：精妙背包按钮左侧，比其余顶栏按钮窄，非空时标题带数量角标
    private static final int CART_BTN_W = 48;
    private int cartBtnW() { return CART_BTN_W; }
    private int cartBtnX() { return backpackBtnX() - 4 - cartBtnW(); }

    private int favBtnW() { return FAV_BTN_W; }
    private int favBtnX() { return cartBtnX() - 4 - favBtnW(); }

    // ===== 网格坐标单一真源（渲染/点击/tooltip 三处共用，杜绝错位）=====
    private static final int GRID_SCROLLBAR_W = 3;
    private static final int DETAIL_SCROLLBAR_W = 3;
    /**
     * 详情列滚动条与正文之间的留白。详情列正文按左右各 8px 内缩（cx = detailX()+8、宽 DETAIL_W-16），
     * 右缘因此落在 detailX()+DETAIL_W-8；而滚动条轨道是常驻绘制、且在 disableScissor 之后才画的，
     * 起点必须越过这个右缘——否则轨道底色会正好盖掉整列满宽按钮（跳转/指南详情/展开详情/补齐缺口……）
     * 最右那一列边框像素，表现为按钮被竖线切掉一条边；顺带也解决点击优先级：轨道命中判定排在按钮之前，
     * 重叠时按钮最右一列会变成拖拽滚动条的死区。
     */
    private static final int DETAIL_SCROLLBAR_GAP = 2;

    private int cellX(int col) { return listLeft() + col * colStride(); }
    private int cellY(int row) { return contentTop() + row * rowStride() - scroll; }

    private ShopGridViewport.Range visibleGridRange() {
        return gridRange(1, scroll);
    }

    /** 预载看目标：快速滚动时提前拉目标位置附近的数据，而不是跟着慢慢滑的显示值。 */
    private ShopGridViewport.Range preloadGridRange() {
        return gridRange(GRID_LOAD_OVERSCAN, scrollTarget);
    }

    private ShopGridViewport.Range gridRange(int overscan, int scrollPx) {
        return ShopGridViewport.visibleRange(
                visibleEntryKeys.size(), gridColumns(), scrollPx, gridHeight(), rowStride(), overscan);
    }

    private int entryIndexAt(double mouseX, double mouseY) {
        return ShopGridViewport.indexAt(
                visibleEntryKeys.size(), gridColumns(), colStride(), rowStride(), cellW(), cellH(),
                listLeft(), contentTop(), listWidth(), gridHeight(), scroll, mouseX, mouseY);
    }

    /** 最大滚动像素（内容总高 - 可见高，下限 0）。 */
    private int maxGridScroll() {
        int cols = gridColumns();
        int rows = (visibleEntryKeys.size() + cols - 1) / cols;
        return Math.max(0, rows * rowStride() - gridHeight());
    }

    /** 上下文变了（搜索、切分组、重算可见、拖滚动条）：目标和显示值一起到位，不滑。 */
    private void snapGridScroll(int value) {
        scroll = value;
        scrollTarget = value;
        gridScrollSpring.snap(value);
    }

    /** 每帧开头：目标夹进合法范围，弹簧追目标，显示值取整（整像素，和格子坐标同口径）。 */
    private void advanceGridScroll(long now) {
        int max = maxGridScroll();
        scrollTarget = Math.max(0, Math.min(max, scrollTarget));
        if (gridScrollSpring.target() != scrollTarget) gridScrollSpring.setTarget(scrollTarget, now);
        gridScrollSpring.update(now);
        scroll = Math.max(0, Math.min(max, Math.round(gridScrollSpring.value())));
    }

    private void snapDetailScroll(int value) {
        detailScroll = value;
        detailScrollTarget = value;
        detailScrollSpring.snap(value);
    }

    private void advanceDetailScroll(long now) {
        detailScrollTarget = clampDetailScroll(detailScrollTarget);
        if (detailScrollSpring.target() != detailScrollTarget) detailScrollSpring.setTarget(detailScrollTarget, now);
        detailScrollSpring.update(now);
        detailScroll = clampDetailScroll(Math.round(detailScrollSpring.value()));
    }

    /** 网格右侧滚动条轨道 x（紧贴列末端右侧，同 {@link #drawGrid} 原有算法）。 */
    private int gridScrollbarX() {
        int cols = gridColumns();
        return listLeft() + Math.min(listWidth() - 4, cols * colStride() - GRID_GAP) + 1;
    }

    /** 在缩放坐标系下开启 scissor：enableScissor 吃物理像素，须手动换算（KE 做法）。 */
    private void enableGridScissor(GuiGraphics g, int x, int y, int x2, int y2) {
        g.enableScissor(
                (int) (x * guiScale) + offsetX, (int) (y * guiScale) + offsetY,
                (int) (x2 * guiScale) + offsetX, (int) (y2 * guiScale) + offsetY);
    }

    @Override
    protected void initScaled() {
        tooltipMotion.reset();
        cancelPendingGuide();
        if (replayGuideSuccess) {
            buttonFeedback.flash(replayGuideSuccessKey, ButtonFeedback.Tone.SUCCESS, System.currentTimeMillis());
            replayGuideSuccess = false;
        }
        // 按着按钮时切到子界面，松开事件落在别处；回到本界面时把按下态归零
        buttonFeedback.cancel(System.currentTimeMillis());
        // 在弹窗里按 Enter 确认后回到这里时键还按着：松开前的系统重复不能再下一单
        enterHeld = ShopDialogUi.enterDown();
        // 预编译后处理链，避免第一次点商品时才加载 shader 造成卡顿；失败会自动回退局部磨砂。
        ShopGaussBlur.warmUp();
        boolean fresh = searchBox == null;
        String restoreStableId = null;
        if (observedCatalogRevision != ClientShopCatalog.revision()) {
            if (ClientShopUiSettings.rememberShopView() && selected != null) {
                restoreStableId = selected.getStableId();
            }
            observedCatalogRevision = ClientShopCatalog.revision();
            clearSelection();
            snapGridScroll(0);
        }
        // 任务书跳转：先把分类切到目标商品所在页，后面的「有效性收紧」和 recomputeVisible 才会算在对的页上。
        long focusKey = consumePendingFocus();
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
        // 以下只做「有效性收紧」，不做记忆恢复——记忆恢复只在 switchTop/switchSub/switchSub2 显式切换时触发一次
        // （见这几个方法），否则每次 initScaled 都会把用户刚点的「全部」重新拉回记忆值，永远选不中全部。
        subCategories = ClientShopCatalog.subCategories(selectedTop);
        if (!selectedSub.isEmpty() && !subCategories.contains(selectedSub)) {
            selectedSub = ""; // 子分类已不存在 → 回退全部
        }
        subCategories2 = selectedSub.isEmpty() ? List.of() : ClientShopCatalog.subCategories2(selectedTop, selectedSub);
        if (!selectedSub2.isEmpty() && !subCategories2.contains(selectedSub2)) {
            selectedSub2 = "";
        }
        subCategories3 = selectedSub2.isEmpty() ? List.of() : ClientShopCatalog.subCategories3(selectedTop, selectedSub, selectedSub2);
        if (!selectedSub3.isEmpty() && !subCategories3.contains(selectedSub3)) {
            selectedSub3 = "";
        }

        // 搜索框（右侧详情列正上方）。新开的界面在「记住选取与搜索」打开时带回上次的词。
        String prev = searchBox != null ? searchBox.getValue() : "";
        if (fresh && ClientShopUiSettings.rememberShopView()) {
            prev = ClientShopUiSettings.shopSearch();
        }
        searchBox = new AnimatableEditBox(this.font, searchBoxX(), searchBoxY(), SEARCH_W, 12, Component.literal("搜索"));
        searchBox.setValue(prev);
        searchBox.setResponder(s -> { snapGridScroll(0); recomputeVisible(); requestStagger(0.5f); });
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
        if (focusKey >= 0L) {
            focusOnEntry(focusKey);
        } else if (restoreStableId != null) {
            restoreRememberedSelection(restoreStableId);
        } else if (fresh && ClientShopUiSettings.rememberShopView()) {
            restoreRememberedSelection(ClientShopUiSettings.shopSelectedStableId());
        }
        openPendingCurrency();
    }

    /** 悬停 GT 币的开店请求：记下币种，下一个商店界面起来后进入货币中心。没钱包时请求会被拒，超时作废。 */
    public static void requestCurrency(ResourceLocation currency) {
        pendingCurrency = currency;
        pendingCurrencyAtMs = net.minecraft.Util.getMillis();
    }

    private void openPendingCurrency() {
        ResourceLocation currency = pendingCurrency;
        long requestedAtMs = pendingCurrencyAtMs;
        pendingCurrency = null;
        pendingCurrencyAtMs = 0L;
        if (currency == null) return;
        if (net.minecraft.Util.getMillis() - requestedAtMs > PENDING_FOCUS_TTL_MS) return;
        // 留到 tick 再换屏。initScaled 里直接 setScreen 会在商店 init 未结束时嵌套打开货币中心。
        this.queuedCurrency = currency;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.minecraft == null || this.minecraft.screen != this) return;
        if (queuedCurrency != null) {
            ResourceLocation currency = queuedCurrency;
            queuedCurrency = null;
            this.minecraft.setScreen(new CurrencyAtmScreen(this, currency));
            return;
        }
        long now = System.currentTimeMillis();
        if (pendingGuideHit == null || now < pendingGuideAtMs) return;
        var hit = pendingGuideHit;
        long key = pendingGuideKey;
        pendingGuideHit = null;
        if (key != detailActionKey(DETAIL_GUIDE)) {
            buttonFeedback.flash(key, ButtonFeedback.Tone.NEUTRAL, now);
            return;
        }
        try {
            boolean opened = com.dishanhai.gt_shanhai.client.shop.ShopGuideLookup.tryOpen(this.minecraft.player, hit);
            flashDetailAction(key, opened ? ButtonFeedback.Tone.SUCCESS : ButtonFeedback.Tone.FAILED);
            if (opened && this.minecraft.screen != this) {
                replayGuideSuccessKey = key;
                replayGuideSuccess = true;
            } else if (!opened) {
                showMessage(Component.literal("§c[山海商店] 指南打开失败"));
            }
        } catch (RuntimeException exception) {
            flashDetailAction(key, ButtonFeedback.Tone.FAILED);
            showMessage(Component.literal("§c[山海商店] 指南打开失败"));
            com.dishanhai.gt_shanhai.GTDishanhaiMod.LOGGER.warn("Shop guide could not open", exception);
        }
    }

    /** 按 stableId 找回上次選中的商品。目錄還沒到就先掛起，到了再選。 */
    private void restoreRememberedSelection(String stableId) {
        if (stableId == null || stableId.isBlank() || selected != null) return;
        long key = ClientShopCatalog.keyOfStableId(stableId);
        if (key >= 0L) focusOnEntry(key);
        else if (!ClientShopCatalog.ready()) pendingRestoreStableId = stableId;
    }

    /** 商店設置裡「記住選取與搜索」確認後調用：開著就收下當前畫面，關著就清掉已存的詞和商品。 */
    public void onRememberShopViewSaved(boolean remember) {
        if (remember) ClientShopUiSettings.setShopView(currentSearch(), currentStableId());
        else ClientShopUiSettings.clearShopView();
    }

    private String currentSearch() {
        return searchBox == null ? "" : searchBox.getValue();
    }

    private String currentStableId() {
        return selected == null ? "" : selected.getStableId();
    }

    /**
     * 任务书「前往商店」跳转入口：记下待定位商品，下一个开出来的商店界面会自动切到它所在分类页并选中详情。
     *
     * @param stableId       跨快照稳定身份（首选，见 {@link ShopEntry#getStableId}）
     * @param fallbackEntryKey 记录时刻的 entryKey，仅在 stableId 缺失/解析不到时兜底
     */
    public static void requestFocus(String stableId, long fallbackEntryKey) {
        pendingFocusStableId = stableId == null || stableId.isBlank() ? null : stableId;
        pendingFocusFallbackKey = fallbackEntryKey;
        pendingFocusAtMs = net.minecraft.Util.getMillis();
    }

    /** 取出并清空待定位商品，同时把分类页签切到它所在的那一页；返回 -1 = 没有待定位/已失效。 */
    private static long consumePendingFocus() {
        String stableId = pendingFocusStableId;
        long fallbackKey = pendingFocusFallbackKey;
        long requestedAtMs = pendingFocusAtMs;
        pendingFocusStableId = null;
        pendingFocusFallbackKey = -1L;
        pendingFocusAtMs = 0L;
        if (stableId == null && fallbackKey < 0L) return -1L;
        if (net.minecraft.Util.getMillis() - requestedAtMs > PENDING_FOCUS_TTL_MS) return -1L;
        long key = stableId == null ? -1L : ClientShopCatalog.keyOfStableId(stableId);
        if (key < 0L) key = fallbackKey;
        ShopCatalogManifest.Stub stub = key < 0L ? null : ClientShopCatalog.stub(key);
        if (stub == null) return -1L;
        // 顺序不能反：switchTop 会从记忆恢复子路径，后面三层再逐级覆盖成目标商品的真实路径。
        switchTop(stub.top());
        switchSub(stub.sub());
        switchSub2(stub.sub2());
        switchSub3(stub.sub3());
        return key;
    }

    /** 把网格滚到该商品所在行并选中它；商品实体还没加载就先挂起，等 chunk 到了再选（见 pendingSelectEntryKey）。 */
    private void focusOnEntry(long entryKey) {
        int index = visibleEntryKeys.indexOf(entryKey);
        if (index >= 0) {
            int row = index / gridColumns();
            int to = Math.max(0, Math.min(maxGridScroll(), row * rowStride()));
            // 开店时恢复上次位置直接到位；开店之后的跳转让网格滑过去
            if (!openMotion.settled()) snapGridScroll(to);
            else scrollTarget = to;
        }
        ShopEntry entry = ClientShopCatalog.get(entryKey);
        if (entry != null) {
            selectEntry(entryKey, entry);
            pendingSelectEntryKey = -1L;
        } else {
            pendingSelectEntryKey = entryKey;
        }
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
                ? ClientShopCatalog.keysOfGroup(selectedTop, selectedSub, selectedSub2, selectedSub3)
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
        stageLockPath = lockedStageForCurrentView();
        stageLockActive = stageLockPath != null;
        if (!stageLockActive) stageLockShownAtMs = 0L;
        if (stageLockActive) maybeRequestStagePreview(stageLockPath);
        // 阶段锁定只影响商店内容区，不能把游戏世界送进全局后处理。
        ShopStageBlur.setActive(false);
        long nowMs = System.currentTimeMillis();
        openMotion.update(nowMs);
        advanceGridScroll(nowMs);
        hoverCells.sweep(nowMs);
        hoverButtons.sweep(nowMs);
        hoverTabs.sweep(nowMs);
        frameMy = my;
        reduceMotionFrame = ClientShopUiSettings.reduceMotion();
        // 松开可能没送到这里（按着时弹了别的界面）；鼠标实际已松开就补一次松开
        if (buttonFeedback.holding() && this.minecraft != null
                && !this.minecraft.mouseHandler.isLeftPressed() && !this.minecraft.mouseHandler.isRightPressed()) {
            buttonFeedback.release(nowMs);
        }
        ClientShopActionResults.drain(nowMs, this::onActionResult);
        buttonFeedback.pointer(mx, my, nowMs);
        buttonFeedback.tick(nowMs);
        drainAutoCraftResults();
        boolean motionBusy = !openMotion.settled()
                || (gridSwitchAtMs > 0L && nowMs - gridSwitchAtMs < GuiRenderUtil.MOTION_MS);
        ClientShopCatalog.pumpMaterialization(motionBusy ? LOAD_PUMP_MOTION_NS : LOAD_PUMP_SETTLED_NS);
        ShopGridViewport.Range loadRange = preloadGridRange();
        frameLoadKeys.clear();
        for (int i = loadRange.fromInclusive(); i < loadRange.toExclusive(); i++) {
            frameLoadKeys.add(visibleEntryKeys.get(i));
        }
        if (selectedEntryKey >= 0L) frameLoadKeys.add(selectedEntryKey);
        // 任务书跳转挂起的商品：拉它所在 chunk，到货即选中（本帧 pump 刚好可能把它实体化出来）
        if (pendingSelectEntryKey >= 0L) {
            ShopEntry pending = ClientShopCatalog.get(pendingSelectEntryKey);
            if (pending != null) {
                selectEntry(pendingSelectEntryKey, pending);
                pendingSelectEntryKey = -1L;
            } else {
                frameLoadKeys.add(pendingSelectEntryKey);
            }
        }
        if (pendingRestoreStableId != null && selected == null) {
            long restoreKey = ClientShopCatalog.keyOfStableId(pendingRestoreStableId);
            if (restoreKey >= 0L) {
                pendingRestoreStableId = null;
                focusOnEntry(restoreKey);
                if (selectedEntryKey >= 0L) frameLoadKeys.add(selectedEntryKey);
            } else if (ClientShopCatalog.ready()) {
                pendingRestoreStableId = null;
            }
        }
        if (selected != null && selected.hasLinkTarget()) {
            long linked = ClientShopCatalog.resolveLink(selected.getLinkTo());
            if (linked >= 0L) frameLoadKeys.add(linked);
        }
        if (cartOverlayOpen) {
            for (String stableId : ClientShopCart.getAll().keySet()) {
                long k = ClientShopCatalog.keyOfStableId(stableId);
                if (k >= 0L) frameLoadKeys.add(k);
            }
        }
        ClientShopCatalog.ensureLoadedRange(frameLoadKeys, 0, frameLoadKeys.size());
        syncOpenMotionWidgets();
        float openScale = GuiMotion.panelScale(openMotion.value());
        boolean opening = openScale < 0.9995f;
        if (opening) {
            g.pose().pushPose();
            GuiRenderUtil.scaleAt(g, left + panelWidth / 2f, top + panelHeight / 2f, openScale);
        }
        try {
        // 整体面板（KE 风格 4 层嵌套：金边→金→暗背景→内层）
        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        g.fill(left + 6, top + TOP_BAR_H + 6, left + panelWidth - 6, top + panelHeight - 6, PANEL_INNER);

        // 顶部买/卖页签
        drawTabStrip(g, TAB_STRIP_BUYSELL, top + 6, TOP_BAR_H,
                new int[]{buyTabX(), sellTabX()}, new int[]{buyTabW(), sellTabW()},
                new String[]{"购买", "出售"}, mode == Mode.BUY ? 0 : 1, 0, mx, my);
        // 货币中心 + 兑换中心（始终可见）
        drawButton(g, currencyBtnX(), top + 6, currencyBtnW(), TOP_BAR_H, "§6货币中心", mx, my);
        drawButton(g, exchangeBtnX(), top + 6, exchangeBtnW(), TOP_BAR_H, "§d兑换中心", mx, my);
        drawButton(g, memberBtnX(), top + 6, memberBtnW(), TOP_BAR_H, "§b银会中心", mx, my);
        // 新增商品（编辑权 + 开了编辑模式）；商店设置所有人可见，没有编辑权的只看到本机显示偏好
        if (catalogEditUnlocked) {
            drawButton(g, addBtnX(), top + 6, addBtnW(), TOP_BAR_H, "§a新增商品", mx, my);
        }
        drawButton(g, settingsBtnX(), top + 6, settingsBtnW(), TOP_BAR_H, "§b商店设置", mx, my);

        // 顶栏右侧：购物车 → 精妙背包模式 → AE模式 → 充值全部 → 关闭
        int cartCount = ClientShopCart.size();
        drawButton(g, cartBtnX(), top + 6, cartBtnW(), TOP_BAR_H,
                cartCount > 0 ? "§e车§6(" + cartCount + ")" : "§e购物车", mx, my);
        drawButton(g, favBtnX(), top + 6, favBtnW(), TOP_BAR_H,
                favoritesOnly ? "§e★收藏" : "§7☆收藏", mx, my);
        drawButton(g, backpackBtnX(), top + 6, backpackBtnW(), TOP_BAR_H,
                backpackMode ? "§a精妙背包:开" : "§8精妙背包:关", mx, my);
        drawButton(g, aeBtnX(), top + 6, aeBtnW(), TOP_BAR_H, aeMode ? "§aAE开" : "§8AE关", mx, my);
        drawButton(g, rechargeBtnX(), top + 6, rechargeBtnW(), TOP_BAR_H, "§b充值全部", mx, my);
        drawButton(g, closeBtnX(), top + 6, closeBtnW(), TOP_BAR_H, "§c关闭", mx, my);

        // 标题（居中于整个面板，KE 做法）
        g.drawCenteredString(this.font, "§6钱包商店", titleX(), top + 9, GOLD);

        // 余额行（KE 货币栏：图标 + 紧凑数字 横排）
        int by = balanceY();
        int balW = panelWidth - 28;
        renderBox(g, left + 14, by, balW, balanceHeight(), GOLD_DARK, BOX_BG);
        drawCurrencyBar(g, left + 14 + 6, by, balW - 12, mx, my);

        // 分类页签（主/二级/三级/四级，见 rowXxx/renderTabRow 统一实现；拖拽排序时被拖的页签
        // 会画成跟随鼠标的浮动块，由 renderTabRow 内部处理，这里只要按层级挨个调用）
        for (int level = 0; level < 4; level++) {
            if (rowVisible(level)) renderTabRow(g, level, mx, my);
        }

        // 左侧网格背景。分组切页不再整块横移，改由 drawGrid 里的卡片错峰入场承担；
        // 切页期间对网格做平滑高斯，落位后收干净，避免宽间距核把商品名打成残影。
        int gx = listLeft(), gy = contentTop(), gw = listWidth(), gh = contentHeight();
        g.fill(gx, gy, gx + gw, gy + gh, BOX_BG);
        if (pendingStagger && !visibleEntryKeys.isEmpty()) {
            startStagger(nowMs);
            pendingStagger = false;
        }
        if (staggerUntilMs > 0L && nowMs >= staggerUntilMs) {
            staggerCells.clear();
            staggerUntilMs = 0L;
        }
        drawGrid(g, mx, my);
        // drawGrid 内部会发 LOADING 请求；同帧后一条主请求覆盖前一条，SWAP 要压过它，所以放在 drawGrid 之后。
        float gridT = GuiRenderUtil.popAnimProgress(gridSwitchAtMs, GuiRenderUtil.MOTION_MS);
        if (gridT < 1f) requestGroupSwitchGauss(1f - GuiRenderUtil.easeOut(gridT));
        drawGridHint(g);

        // 详情列一直在。换商品只换文字和数据，不再次缩放。
        drawDetail(g, mx, my);

        // 实时消息横幅、撤销按钮和 tooltip 要压在模糊之上，统一在帧末模糊跑完之后画（见 drawTopLayer）。
        // 撤销按钮只在模态层盖住时留在这里：它们被遮罩一起压暗，且点击本来就被模态层拦掉，跟其余背景控件一致。
        if (modalLayerOpen()) drawUndoButtons(g, mx, my);
        } finally {
            if (opening) g.pose().popPose();
        }
    }

    /** 右键菜单、各大图层、阶段锁定任一盖在商店内容区上。它们打开时底层控件被遮罩压暗，也不响应点击。 */
    private boolean modalLayerOpen() {
        return ctxMenuOpen || descOverlayOpen || guideOverlayOpen || groupPickerOpen || cartOverlayOpen
                || stageLockActive;
    }

    /** 打开浮层：已经完全静止在 0 时从 0 起（snap 免得残留旧速度），退场到一半被重新打开则从当前值接着走。 */
    private void openOverlay(Spring motion) {
        long now = System.currentTimeMillis();
        if (motion.settled() && motion.value() <= OVERLAY_VISIBLE_EPS) motion.snap(0f);
        motion.setTarget(1f, now);
    }

    private void closeOverlay(Spring motion) {
        motion.setTarget(0f, System.currentTimeMillis());
    }

    private void closeCartOverlay() { cartOverlayOpen = false; closeOverlay(cartMotion); }
    private void closeDescOverlay() { descOverlayOpen = false; closeOverlay(descMotion); }
    private void closeGuideOverlay() { guideOverlayOpen = false; closeOverlay(guideMotion); }
    private void closeGroupPicker() { groupPickerOpen = false; closeOverlay(groupPickerMotion); }

    /**
     * 撤销删除 / 撤销排序按钮：操作后 8 秒内悬浮显示，点一下把刚动过的条目挪回去（服务端另有独立 30 秒兜底窗口）。
     * 物品图标走 GuiGraphics 的缓冲渲染层，和 fill()/drawString() 这类立即绘制的 flat quad 不在同一批次，
     * 画之前先 flush，免得图标批次晚提交盖住文字。
     */
    private void drawUndoButtons(GuiGraphics g, int mx, int my) {
        if (undoDeleteLabel != null) {
            if (System.currentTimeMillis() < undoDeleteUntilMs) {
                g.flush();
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

    /** 搜索框和数量框画在面板姿态外面，开场时用同一锚点跟上整块面板的缩放。 */
    private void syncOpenMotionWidgets() {
        float cx = left + panelWidth / 2f;
        float cy = top + panelHeight / 2f;
        if (searchBox != null) searchBox.setAnim(this::openScale, cx, cy);
        if (amountBox != null) {
            amountBox.setVisible(selected != null);
            amountBox.setAnim(this::openScale, cx, cy);
        }
    }

    /** 开场缩放系数，和整块面板用同一个弹簧值。 */
    private float openScale() {
        return GuiMotion.panelScale(openMotion.value());
    }

    /** 提示从面板底缘升起。裁在面板内，动画中用小数 Y，落位后回到整数。 */
    private void drawFlashStack(GuiGraphics g, long now) {
        enableGridScissor(g, left + 2, top + 2, left + panelWidth - 2, top + panelHeight);
        try {
            int baseY = top + panelHeight - 24;
            for (FlashMsg m : flashLeaving) drawFlashRow(g, m, now, baseY);
            for (FlashMsg m : flashActive) drawFlashRow(g, m, now, baseY);
        } finally {
            g.disableScissor();
        }
    }

    /** 画一条消息横幅：按其动画槽位算出当前 Y，宽度随文字自适应，样式跟原先的单槽横幅一致。 */
    private void drawFlashRow(GuiGraphics g, FlashMsg m, long now, int baseY) {
        float yf = baseY - flashVisualSlot(m, now) * FLASH_ROW_H;
        float drawY = Math.abs(yf - Math.round(yf)) < 0.05f ? Math.round(yf) : yf;
        int w = this.font.width(m.text);
        int bannerW = Math.min(panelWidth - 12, w + 16);
        int bannerX = left + (panelWidth - bannerW) / 2;
        g.pose().pushPose();
        try {
            g.pose().translate(bannerX, drawY, 0);
            g.fill(0, 0, bannerW, 16, 0xE0101010);   // 半透明黑底
            g.fill(0, 0, bannerW, 1, 0xFF00C0C0);    // 顶部青线
            g.drawCenteredString(this.font, m.text, bannerW / 2, 4, 0xFFFFFF);
        } finally {
            g.pose().popPose();
        }
    }

    /**
     * 上层的 Z 偏移：高于前景大图层及其物品图标（前景 400 + 物品 150），低于 tooltip（本层 600 + 原版 400）。
     * 上层在 Post 事件里画，此时深度缓冲里还留着整帧的 GUI，Z 不够高会被 LEQUAL 深度测试挡掉。
     */
    private static final float TOP_LAYER_Z = 600.0f;

    /**
     * 帧末模糊后处理读的是整张主画面，画在模糊矩形上的东西会一起被糊（加载格、切页、飞行、大图层都会）。
     * 所以要压在模糊之上的内容——提示横幅、撤销按钮、拖拽中的卡片图标、tooltip——不在 Screen.render 里画，
     * 只在这里登记，由 {@link ShopGaussBlur#onScreenRenderPost} 在模糊跑完后调 {@link #drawTopLayer}。
     */
    @Override
    protected void renderTooltips(GuiGraphics g, int smx, int smy, int mx, int my) {
        // 先落盘：后处理读主画面，前面所有 GUI 批次必须已经画进去。
        g.flush();
        ShopGaussBlur.deferTopLayer(this, topG -> drawTopLayer(topG, smx, smy, mx, my));
    }

    private void drawTopLayer(GuiGraphics g, int smx, int smy, int mx, int my) {
        drawScaledTopLayer(g, smx, smy);
        drawTooltipLayer(g, smx, smy, mx, my);
    }

    /**
     * 提示横幅 + 撤销按钮 + 拖拽中的卡片图标，坐标系和背景一致（偏移 + 缩放），只是整体抬到 {@link #TOP_LAYER_Z}。
     * 横幅在阶段锁定和各大图层上也要看得见（提交失败、余额不足这类反馈不能被遮罩压暗），所以不看 modalLayerOpen。
     */
    private void drawScaledTopLayer(GuiGraphics g, int smx, int smy) {
        long now = System.currentTimeMillis();
        tickFlash(now);
        boolean banners = !flashActive.isEmpty() || !flashLeaving.isEmpty();
        ItemStack dragIcon = dragCardEntryKey != -1L && dragCardMoving && dragCardEntry != null
                ? dragCardEntry.displayGoodsStack() : ItemStack.EMPTY;
        g.pose().pushPose();
        try {
            g.pose().translate(this.offsetX, this.offsetY, TOP_LAYER_Z);
            g.pose().scale(this.guiScale, this.guiScale, 1.0f);
            if (banners) {
                // 物品图标走缓冲渲染层，和 fill()/drawString() 不在同一批次；先 flush，免得图标晚提交盖住横幅文字
                // （同 ShopEntryEditScreen 描述展开编写大图层的穿模成因，见其 renderScaledBackground 注释）。
                g.flush();
                drawFlashStack(g, now);
            }
            if (!modalLayerOpen()) drawUndoButtons(g, smx, smy);
            // 跟随鼠标的浮动图标画在 scissor 外，避免拖出网格边界被裁掉；放在上层，不会被加载格的模糊糊掉。
            if (!dragIcon.isEmpty()) g.renderItem(dragIcon, (int) dragCardMx - 8, (int) dragCardMy - 8);
        } finally {
            g.pose().popPose();
        }
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
        drawShopForeground(g, mx, my, pt);
        // 前景层全部画完再叠点击波：亮环要在最上面，后处理请求也必须是本帧最后一条。
        requestClickWave(g);
    }

    private enum Overlay { NONE, CTX, GUIDE, GROUP_PICKER, CART, DESC }

    /** 当前要画的浮层。逻辑上打开的按原优先级排前面；都没开时，选还在退场的（弹簧没归零）。 */
    private Overlay activeOverlay() {
        if (ctxMenuOpen) return Overlay.CTX;
        if (guideOverlayOpen && selected != null) return Overlay.GUIDE;
        if (groupPickerOpen && groupPickerEntry != null) return Overlay.GROUP_PICKER;
        if (cartOverlayOpen) return Overlay.CART;
        if (descOverlayOpen && selected != null) return Overlay.DESC;
        if (ctxMotion.value() > OVERLAY_VISIBLE_EPS && !ctxMenuItems.isEmpty()) return Overlay.CTX;
        if (guideMotion.value() > OVERLAY_VISIBLE_EPS && selected != null) return Overlay.GUIDE;
        if (groupPickerMotion.value() > OVERLAY_VISIBLE_EPS && groupPickerEntry != null) return Overlay.GROUP_PICKER;
        if (cartMotion.value() > OVERLAY_VISIBLE_EPS) return Overlay.CART;
        if (descMotion.value() > OVERLAY_VISIBLE_EPS && selected != null) return Overlay.DESC;
        return Overlay.NONE;
    }

    private void updateOverlayMotion(long now) {
        ctxMotion.update(now);
        guideMotion.update(now);
        groupPickerMotion.update(now);
        cartMotion.update(now);
        descMotion.update(now);
        // 右键菜单项退场期间还要画，弹簧归零后才清
        if (!ctxMenuOpen && ctxMotion.settled() && ctxMotion.value() <= OVERLAY_VISIBLE_EPS
                && !ctxMenuItems.isEmpty()) {
            ctxMenuItems = List.of();
            ctxMenuEntryKey = -1L;
        }
    }

    private void drawShopForeground(GuiGraphics g, int mx, int my, float pt) {
        // 卡片飞行层画在所有基础控件（搜索框/数量框）之后、各种大图层之前：
        // 这样遮罩能一起压住输入框，模拟附近界面失焦；购物车/详情大图层仍可盖在它上面。
        renderCardFlight(g);
        if (stageLockActive) {
            renderStageLockOverlay(g, mx, my);
            return;
        }
        updateOverlayMotion(System.currentTimeMillis());
        switch (activeOverlay()) {
            case CTX -> drawContextMenu(g, mx, my);
            case GUIDE -> {
                int[] r = guideOverlayBounds();
                openBlurredOverlay(g, guideMotion.value(), r[0], r[1], r[2], r[3], () -> renderGuideOverlay(g, mx, my));
            }
            case GROUP_PICKER -> {
                int[] r = groupPickerBounds();
                openBlurredOverlay(g, groupPickerMotion.value(), r[0], r[1], r[2], r[3], () -> renderGroupPicker(g, mx, my));
            }
            case CART -> {
                int[] cr = cartOverlayBounds();
                openSmoothTextOverlay(g, cartMotion.value(), cr[0], cr[1], cr[2], cr[3], () -> renderCartOverlay(g, mx, my));
            }
            case DESC -> drawDescOverlay(g, mx, my);
            case NONE -> { }
        }
    }

    /** 描述详情大图层本体（原 drawShopForeground 末段原样搬出，进度换成弹簧值）。 */
    private void drawDescOverlay(GuiGraphics g, int mx, int my) {
        int[] r = descOverlayBounds();
        int ox = r[0], oy = r[1], ow = r[2];

        openSmoothTextOverlay(g, descMotion.value(), ox, oy, ow, r[3], () -> {
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
        });
    }

    /** 浮层在自己的中心缩放并随弹簧值淡入淡出（开和关同一条曲线）。遮罩留在外面，不跟着缩。 */
    private void drawPopPanel(GuiGraphics g, float p, int x, int y, int w, int h, Runnable draw) {
        GuiRenderUtil.withPanelMotion(g, x + w / 2f, y + h / 2f, p, draw);
    }

    /**
     * 模糊挖洞矩形 {x, y, w, h}。面板按 {@link GuiMotion#panelScale} 缩放（{@link #drawPopPanel}），
     * 洞也要按同一个系数缩，否则面板四周会露出一圈没糊的内容。
     */
    private static int[] popHoleRect(float p, int x, int y, int w, int h) {
        return GuiRenderUtil.scaleRectAround(x, y, w, h, GuiMotion.panelScale(p));
    }

    /**
     * 大图层开关时，商品区的高斯强度、遮罩、面板缩放都读同一个弹簧值 p。浮层矩形挖洞保持清晰。
     * 商店外的世界只压暗，不进模糊矩形。
     */
    private void openBlurredOverlay(GuiGraphics g, float p, int x, int y, int w, int h, Runnable draw) {
        int[] content = shopContentRect();
        int[] hole = popHoleRect(p, x, y, w, h);
        requestContentGauss(GuiMotion.clamp01(p), hole[0], hole[1], hole[2], hole[3]);
        drawModalScrim(g, content[0], content[1], content[2], content[3], p);
        drawPopPanel(g, p, x, y, w, h, draw);
    }

    /**
     * 描述详情是长文本，只用密采样高斯。宽间距 5-tap 会把背后的商品名打成残影。
     * 浮层矩形仍然挖洞，面板本身保持清晰。
     */
    private void openSmoothTextOverlay(GuiGraphics g, float p, int x, int y, int w, int h, Runnable draw) {
        int[] content = shopContentRect();
        int[] hole = popHoleRect(p, x, y, w, h);
        requestContentGauss(GuiMotion.clamp01(p), hole[0], hole[1], hole[2], hole[3], true);
        drawModalScrim(g, content[0], content[1], content[2], content[3], p);
        drawPopPanel(g, p, x, y, w, h, draw);
    }

    /** 网格 + 详情列。 */
    private int[] shopContentRect() {
        return clampToShopInner(listLeft(), contentTop(),
                detailX() + DETAIL_W - listLeft(),
                contentBlurBottom() - contentTop());
    }

    /**
     * 模糊矩形的下沿。提示横幅在模糊之后才画（{@link #drawTopLayer}），不会被糊到，
     * 所以这里不再因为横幅出现/消失而把下沿抬上去又放下，那会让模糊矩形下边缘跳一下。
     */
    private int contentBlurBottom() {
        return contentTop() + contentHeight();
    }

    /** 夹到商店面板内缘，避免高斯采样到面板外的游戏世界。 */
    private int[] clampToShopInner(int x, int y, int w, int h) {
        int x0 = Math.max(x, left + 6);
        int y0 = Math.max(y, top + TOP_BAR_H + 8);
        int x1 = Math.min(x + w, left + panelWidth - 6);
        int y1 = Math.min(y + h, top + panelHeight - 8);
        if (x1 <= x0 || y1 <= y0) return new int[]{x0, y0, 0, 0};
        return new int[]{x0, y0, x1 - x0, y1 - y0};
    }

    private void requestContentGauss(float strength, int holeX, int holeY, int holeW, int holeH) {
        requestContentGauss(strength, holeX, holeY, holeW, holeH, false);
    }

    private void requestContentGauss(float strength, int holeX, int holeY, int holeW, int holeH, boolean smooth) {
        int[] content = shopContentRect();
        requestPanelGauss(content[0], content[1], content[2], content[3],
                strength, holeX, holeY, holeW, holeH, smooth);
    }

    /** 分组切页只糊网格。落位后强度归零，商品名恢复清晰。 */
    private void requestGroupSwitchGauss(float strength) {
        int topY = contentTop();
        int h = contentBlurBottom() - topY;
        requestPanelGauss(listLeft(), topY, listWidth(), h, strength, 0, 0, 0, 0, true, ShopGlassStyle.SWAP);
    }

    private void requestPanelGauss(int x, int y, int w, int h, float strength,
                                   int holeX, int holeY, int holeW, int holeH) {
        requestPanelGauss(x, y, w, h, strength, holeX, holeY, holeW, holeH, false);
    }

    private void requestPanelGauss(int x, int y, int w, int h, float strength,
                                   int holeX, int holeY, int holeW, int holeH, boolean smooth) {
        requestPanelGauss(x, y, w, h, strength, holeX, holeY, holeW, holeH, smooth, ShopGlassStyle.OVERLAY);
    }

    private void requestPanelGauss(int x, int y, int w, int h, float strength,
                                   int holeX, int holeY, int holeW, int holeH, boolean smooth,
                                   ShopGlassStyle glass) {
        if (strength <= 0.01f || w <= 0 || h <= 0) return;
        if (this.minecraft == null || this.minecraft.getMainRenderTarget() == null) return;
        int[] rect = clampToShopInner(x, y, w, h);
        if (rect[2] <= 1 || rect[3] <= 1) return;
        float pixelScale = (float) this.minecraft.getMainRenderTarget().width
                / (float) Math.max(1, this.width);
        float radiusPx = ShopGaussBlur.BLUR_RADIUS_VIRTUAL * this.guiScale * pixelScale;
        int pad = 2;
        boolean hole = holeW > 0 && holeH > 0;
        int hx = hole ? toFramebufferX(holeX - pad) : 0;
        int hy = hole ? toFramebufferY(holeY - pad) : 0;
        int hw = hole ? toFramebufferW(holeW + pad * 2) : 0;
        int hh = hole ? toFramebufferH(holeH + pad * 2) : 0;
        if (smooth) {
            ShopGaussBlur.requestSmooth(
                    toFramebufferX(rect[0]), toFramebufferY(rect[1]),
                    toFramebufferW(rect[2]), toFramebufferH(rect[3]),
                    strength, radiusPx, hx, hy, hw, hh, glass);
        } else {
            ShopGaussBlur.request(
                    toFramebufferX(rect[0]), toFramebufferY(rect[1]),
                    toFramebufferW(rect[2]), toFramebufferH(rect[3]),
                    strength, radiusPx, hx, hy, hw, hh, glass);
        }
    }

    /** 世界和顶栏维持深遮罩；商品区只铺一层淡罩，让帧末高斯还能看见内容。着色器不可用时整屏回到深遮罩。 */
    private void drawModalScrim(GuiGraphics g, int x, int y, int w, int h, float p) {
        // 遮罩随浮层弹簧淡入淡出：关闭时不再停在满强度、最后一帧才硬切掉。
        float k = GuiMotion.clamp01(p);
        int dim = ((int) (0xC0 * k)) << 24;
        int soft = ((int) (0x40 * k)) << 24;
        if (dim == 0) return;
        if (w <= 0 || h <= 0 || !ShopGaussBlur.isReady()) {
            g.fill(0, 0, vWidth, vHeight, dim);
            return;
        }
        int right = x + w;
        int bottom = y + h;
        if (y > 0) g.fill(0, 0, vWidth, y, dim);
        if (bottom < vHeight) g.fill(0, bottom, vWidth, vHeight, dim);
        if (x > 0) g.fill(0, y, x, bottom, dim);
        if (right < vWidth) g.fill(right, y, vWidth, bottom, dim);
        g.fill(x, y, right, bottom, soft);
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

    // ============ 快速分组大图层 ============

    /** 快速分组大图层边界：按选项条数动态算高度，封顶同「指南详情」。 */
    private int[] groupPickerBounds() {
        int ow = Math.min(vWidth - 40, 260);
        int desiredH = 32 + groupPickerOptions.size() * GROUP_PICKER_ROW_H;
        int oh = Math.min(vHeight - 40, Math.max(80, Math.min(desiredH, 300)));
        int ox = (vWidth - ow) / 2;
        int oy = (vHeight - oh) / 2;
        return new int[]{ox, oy, ow, oh};
    }

    private int groupPickerCloseX(int[] r) { return r[0] + r[2] - DESC_OVERLAY_CLOSE_W - 4; }
    private int groupPickerCloseY(int[] r) { return r[1] + 4; }
    private int groupPickerVisibleRows(int[] r) { return Math.max(1, (r[3] - 28) / GROUP_PICKER_ROW_H); }
    private int groupPickerMaxScroll(int[] r) { return Math.max(0, groupPickerOptions.size() - groupPickerVisibleRows(r)); }

    /** 打开快速分组：枚举整棵分类树（主/二/三/四级所有已知组合，含中间节点不止叶子）供选择。 */
    private void openGroupPicker(ShopEntry entry, long entryKey) {
        List<String> options = new ArrayList<>();
        for (String top : ClientShopCatalog.topCategories()) {
            options.add(top);
            for (String sub : ClientShopCatalog.subCategories(top)) {
                String subPath = top + "/" + sub;
                options.add(subPath);
                for (String sub2 : ClientShopCatalog.subCategories2(top, sub)) {
                    String sub2Path = subPath + "/" + sub2;
                    options.add(sub2Path);
                    for (String sub3 : ClientShopCatalog.subCategories3(top, sub, sub2)) {
                        options.add(sub2Path + "/" + sub3);
                    }
                }
            }
        }
        groupPickerOptions = options;
        groupPickerEntry = entry;
        groupPickerEntryKey = entryKey;
        groupPickerBatchIds = List.of();
        groupPickerScroll = 0;
        groupPickerOpen = true;
        openOverlay(groupPickerMotion);
    }

    private void openGroupPickerBatch() {
        List<ShopEntry> entries = batchEntries();
        if (entries.isEmpty()) return;
        List<String> options = new ArrayList<>();
        for (String top : ClientShopCatalog.topCategories()) {
            options.add(top);
            for (String sub : ClientShopCatalog.subCategories(top)) {
                String subPath = top + "/" + sub;
                options.add(subPath);
                for (String sub2 : ClientShopCatalog.subCategories2(top, sub)) {
                    String sub2Path = subPath + "/" + sub2;
                    options.add(sub2Path);
                    for (String sub3 : ClientShopCatalog.subCategories3(top, sub, sub2)) {
                        options.add(sub2Path + "/" + sub3);
                    }
                }
            }
        }
        groupPickerOptions = options;
        groupPickerEntry = entries.get(0);
        groupPickerEntryKey = ClientShopCatalog.keyOf(groupPickerEntry);
        groupPickerBatchIds = batchIdsSnapshot();
        groupPickerScroll = 0;
        groupPickerOpen = true;
        openOverlay(groupPickerMotion);
    }

    /** 快速分组大图层：树形列表（按 "/" 段数缩进），当前分组高亮，点一行直接分组并关闭图层。 */
    private void renderGroupPicker(GuiGraphics g, int mx, int my) {
        int[] r = groupPickerBounds();
        int ox = r[0], oy = r[1], ow = r[2];

        renderBox(g, ox, oy, ow, r[3], GOLD_DARK, PANEL_BG);

        String title = groupPickerBatchIds.isEmpty()
                ? groupPickerEntry.goodsDisplayName()
                : groupPickerBatchIds.size() + " 件商品";
        g.drawString(this.font, GuiRenderUtil.trimText(this.font,
                "§b" + title + " §7— " + (groupPickerBatchIds.isEmpty() ? "快速分组" : "批量快速分组"),
                ow - DESC_OVERLAY_CLOSE_W - 20),
                ox + 8, oy + 8, GOLD, true);
        int closeX = groupPickerCloseX(r), closeY = groupPickerCloseY(r);
        drawButton(g, closeX, closeY, DESC_OVERLAY_CLOSE_W, DESC_OVERLAY_CLOSE_H, "§cX", mx, my);

        int visible = groupPickerVisibleRows(r);
        int maxScroll = groupPickerMaxScroll(r);
        groupPickerScroll = Math.max(0, Math.min(maxScroll, groupPickerScroll));
        String current = groupPickerBatchIds.isEmpty() ? groupPickerEntry.getCategory() : "";
        int rowY0 = oy + 22;
        for (int i = 0; i < visible; i++) {
            int idx = groupPickerScroll + i;
            if (idx >= groupPickerOptions.size()) break;
            String path = groupPickerOptions.get(idx);
            int depth = 0;
            for (int c = 0; c < path.length(); c++) if (path.charAt(c) == '/') depth++;
            int ry = rowY0 + i * GROUP_PICKER_ROW_H;
            boolean hover = GuiRenderUtil.isHovering(mx, my, ox + 6, ry, ow - 12, GROUP_PICKER_ROW_H);
            boolean isCurrent = path.equals(current);
            if (hover) g.fill(ox + 6, ry, ox + ow - 6, ry + GROUP_PICKER_ROW_H, BUTTON_HOVER);
            String leaf = path.substring(path.lastIndexOf('/') + 1);
            String indent = "  ".repeat(depth);
            String prefix = isCurrent ? "§e● " : (hover ? "§b" : "§7");
            g.drawString(this.font, GuiRenderUtil.trimText(this.font, prefix + indent + leaf, ow - 20),
                    ox + 8, ry + 3, isCurrent ? GOLD : (hover ? CYAN : WHITE), true);
        }
        if (maxScroll > 0) {
            g.drawString(this.font, "§8滚轮翻页 " + (groupPickerScroll + 1) + "-" + Math.min(groupPickerOptions.size(), groupPickerScroll + visible)
                    + "/" + groupPickerOptions.size(), ox + 8, oy + r[3] - 10, GRAY, false);
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
        renderBox(g, ox, oy, ow, oh, GOLD_DARK, PANEL_BG);

        Map<String, Long> cartMap = ClientShopCart.getAll();
        // 必须跟 cartOverlayBounds/handleCartOverlayClick 用同一份清单（购物车 ∪ 结算批次）：
        // 全部成交的行会被移出购物车 map 但仍要留在面板里画成功戳记，行索引三处才对得上
        List<String> stableIds = cartDisplayIds();

        g.drawString(this.font, GuiRenderUtil.trimText(this.font,
                "§6购物车 §7(" + cartMap.size() + " 件候选)", ow - DESC_OVERLAY_CLOSE_W - 20), ox + 8, oy + 8, GOLD, true);
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
                // 购物车只买不卖，折扣按买价口径结算（限时折扣 + 会员折扣叠加，见 ShopMembership）
                ShopCost cost = entry.getEffectiveCost(ShopMembership.discountPercentForTier(ClientWalletAccount.getMemberTier()));
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
            if (hit(mx, my, closeX, closeY, DESC_OVERLAY_CLOSE_W, DESC_OVERLAY_CLOSE_H)) armClickWave(mx, my);
            closeCartOverlay();
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
                if (buttonHit(mx, my, minusX, ry, CART_STEP_W, CART_ROW_H - 2)) {
                    long cur = cartMap.getOrDefault(stableId, 1L);
                    ClientShopCart.setAmount(stableId, Math.max(1L, cur - step));
                    return true;
                }
                if (buttonHit(mx, my, plusX, ry, CART_STEP_W, CART_ROW_H - 2)) {
                    long cur = cartMap.getOrDefault(stableId, 1L);
                    ClientShopCart.setAmount(stableId, addClamp(cur, step));
                    return true;
                }
                if (buttonHit(mx, my, removeX, ry, CART_REMOVE_W, CART_ROW_H - 2)) {
                    ClientShopCart.remove(stableId);
                    return true;
                }
            }
        }
        int settleW = CART_SETTLE_BTN_W, settleX = ox + ow - 8 - settleW, settleY = oy + oh - CART_SETTLE_BTN_H - 2;
        int[] progress = cartBatchProgress();
        boolean settling = progress[1] > 0 && progress[0] < progress[1];
        if (!settling && buttonHit(mx, my, settleX, settleY, settleW, CART_SETTLE_BTN_H)) {
            List<String> warnLines = cartSingleUseWarnLines(cartMap);
            if (!warnLines.isEmpty()) {
                ShopUiSounds.warn();
                ShopSingleUseWarnScreen.open(this, warnLines, () -> settleCart(ClientShopCart.getAll()));
                return true;
            }
            settleCart(cartMap);
            return true;
        }
        return true; // 图层内其余点击原地吞掉，不下穿到网格/详情
    }

    /** 购物车里含 SDA/无限盘且数量达到警告线的商品，逐条列出；没有返回空列表。 */
    private List<String> cartSingleUseWarnLines(Map<String, Long> cartMap) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Long> e : cartMap.entrySet()) {
            long entryKey = ClientShopCatalog.keyOfStableId(e.getKey());
            ShopEntry entry = entryKey >= 0L ? ClientShopCatalog.get(entryKey) : null;
            long qty = e.getValue() == null ? 0L : e.getValue();
            if (entry == null || !entry.hasSingleUseStorage() || !ShopEntry.exceedsSingleUseAmount(qty)) continue;
            lines.add("§f" + entry.goodsDisplayName() + " §7× §e" + qty + " §7份");
        }
        if (!lines.isEmpty()) {
            lines.add("§eSDA 和无限盘一般只需要购买一次，数量多半是多余的。");
            lines.add("§7确定要这样结算吗？");
        }
        return lines;
    }

    /** 购物车逐项发起结算。 */
    private void settleCart(Map<String, Long> cartMap) {
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

    private void drawGridHint(GuiGraphics g) {
        List<String> hints = operationHints();
        if (hints.isEmpty()) return;
        int base = (int) ((System.currentTimeMillis() / GRID_HINT_ROTATE_MS) % hints.size());
        int y = contentTop() + gridHeight() + 1;
        String page = "§8" + (base + 1) + "/" + hints.size();
        int pageW = this.font.width(page);
        int maxW = Math.max(8, listWidth() - pageW - 10);
        int lines = Math.min(2, hints.size());
        for (int i = 0; i < lines; i++) {
            String text = GuiRenderUtil.trimText(this.font, "§8" + hints.get((base + i) % hints.size()), maxW);
            g.drawString(this.font, text, listLeft() + 4, y + i * 10, GRAY, false);
        }
        g.drawString(this.font, page, listLeft() + listWidth() - pageW - 2, y, GRAY, false);
    }

    /** 网格为空时的下一步，避免玩家对着空白分类不知道该换页、清搜索还是先收藏。 */
    private String emptyGridHint() {
        if (favoritesOnly) return "§7没有收藏。右键商品可以收藏";
        if (searchBox != null && !searchBox.getValue().isBlank()) return "§7没有匹配。换个词，或清空右侧搜索";
        return "§7这个分类是空的。点上面的分类页签换一组";
    }

    /**
     * 底部两行轮换的操作提示。普通玩家先看购买和购物车；编辑权才出现批量勾选和拖动排序，
     * 避免把用不上的管理快捷键塞进唯一一行里被截断。
     */
    private List<String> operationHints() {
        List<String> hints = new ArrayList<>();
        if (!batchSelectionStableIds.isEmpty()) {
            hints.add("已选 " + batchSelectionStableIds.size() + " 项：右键可批量操作");
            hints.add("空白处 Alt+右键清空批量名单");
        }
        hints.add("左键商品打开详情，改次数后点确认");
        hints.add("Ctrl+左键加入购物车，顶栏购物车一次结算");
        hints.add("右键菜单：收藏、指南、跳转");
        hints.add("详情上方搜索框可搜名称或物品 ID");
        hints.add("AE 开走绑定网络，精妙背包开走身上背包");
        hints.add("货币中心存币换汇，充值全部把背包货币入钱包");
        if (canEdit) {
            hints.add("Alt+左键勾选批量名单");
            hints.add("拖动商品卡片可调整同分类顺序");
        }
        if (catalogEditUnlocked) {
            hints.add("编辑模式开启：可新增、删除、改分组");
        }
        return hints;
    }

    /** 没选商品时占满详情列，把购买步骤和入口说明摊开，不再只写一句「请选择商品」。 */
    private void drawEmptyDetailGuide(GuiGraphics g, int dx, int dy, int dw, int dh) {
        int x = dx + 8;
        int maxW = Math.max(8, dw - 16);
        int y = dy + 8;
        int limit = dy + dh - 12;
        for (String line : emptyGuideLines()) {
            if (y > limit) break;
            if (line.isEmpty()) {
                y += 4;
                continue;
            }
            g.drawString(this.font, GuiRenderUtil.trimText(this.font, line, maxW), x, y, GRAY, false);
            y += 11;
        }
    }

    private List<String> emptyGuideLines() {
        List<String> lines = new ArrayList<>();
        lines.add("§6怎么买 / 卖");
        lines.add("§f1.§7 左键点左侧商品");
        lines.add("§f2.§7 改购买或出售次数");
        lines.add("§f3.§7 确认货币足够，点下方确认成交");
        lines.add("");
        lines.add("§6常用操作");
        lines.add("§eCtrl+左键");
        lines.add("§7加入购物车，再一次结算");
        lines.add("§e右键商品");
        lines.add("§7展开操作栏：收藏、查看指南、跳转...");
        lines.add("§e上方搜索框");
        lines.add("§7搜名称或物品 ID");
        lines.add("§e顶栏购买 / 出售");
        lines.add("§7切换交易方向");
        lines.add("§e☆收藏");
        lines.add("§7只显示已收藏的商品");
        lines.add("§eAE / 精妙背包");
        lines.add("§7决定从网络还是背包扣物，以及购买物应该发到哪");
        lines.add("§e货币中心");
        lines.add("§7存币，或按汇率兑换星火/币种");
        lines.add("§e兑换中心");
        lines.add("§7按条目换物品或星火(半废弃)");
        lines.add("§e银会中心");
        lines.add("§7购买会员享受折扣、银行存款和贷款");
        lines.add("§e充值全部");
        lines.add("§7背包货币存进钱包(仅玩家背包)");
        if (canEdit) {
            lines.add("");
            lines.add("§6编辑操作");
            lines.add("§eAlt+左键");
            lines.add("§7勾选或取消批量名单");
            lines.add("§e空白处 Alt+右键");
            lines.add("§7清空这次批量名单");
            lines.add("§e拖动卡片");
            lines.add("§7调整同分类里的顺序");
        }
        if (catalogEditUnlocked) {
            lines.add("§e编辑模式");
            lines.add("§7可新增、删除、改分组");
        }
        return lines;
    }

    /** 悬停顶栏按钮时的操作说明。坐标与绘制、点击共用同一套按钮矩形。 */
    private List<Component> topButtonTip(int smx, int smy) {
        int y = top + 6;
        int h = TOP_BAR_H;
        if (hit(smx, smy, buyTabX(), y, buyTabW(), h)) {
            return buttonTip("购买", "从商店买东西。先点左侧商品，改次数，再点确认。");
        }
        if (hit(smx, smy, sellTabX(), y, sellTabW(), h)) {
            return buttonTip("出售", "把商品卖回商店，按回收价入账。仅购买的商品不能卖。");
        }
        if (hit(smx, smy, currencyBtnX(), y, currencyBtnW(), h)) {
            return buttonTip("货币中心", "把背包里的货币存进钱包，或按汇率把一种币换成另一种。");
        }
        if (hit(smx, smy, exchangeBtnX(), y, exchangeBtnW(), h)) {
            return buttonTip("兑换中心", "按兑换条目付出物品、流体或星火，换取另一侧。");
        }
        if (hit(smx, smy, memberBtnX(), y, memberBtnW(), h)) {
            return buttonTip("银会中心", "购买会员折扣，或办理定期存款和贷款。");
        }
        if (catalogEditUnlocked && hit(smx, smy, addBtnX(), y, addBtnW(), h)) {
            return buttonTip("新增商品", "往当前商店目录加一条新商品。只在编辑模式开启时出现。");
        }
        if (hit(smx, smy, settingsBtnX(), y, settingsBtnW(), h)) {
            return buttonTip("商店设置", canEdit
                    ? "调整奖励抽取上限、SDA 打包阈值、卡片大小、获取预览模式、是否记住选取与搜索、减少动效与商店音效，以及 AE 注入开关。"
                    : "调整卡片大小、获取预览模式、是否记住选取与搜索、减少动效与商店音效，只存在这台客户端。");
        }
        if (hit(smx, smy, cartBtnX(), y, cartBtnW(), h)) {
            return buttonTip("购物车", "Ctrl+左键把商品加进来。打开后可改数量，再一次结算。");
        }
        if (hit(smx, smy, favBtnX(), y, favBtnW(), h)) {
            return buttonTip("只看收藏", "只显示已收藏的商品。右键商品可以收藏或取消。");
        }
        if (hit(smx, smy, backpackBtnX(), y, backpackBtnW(), h)) {
            return buttonTip("精妙背包", "开启后，扣款和交货优先走身上穿戴的精妙背包。");
        }
        if (hit(smx, smy, aeBtnX(), y, aeBtnW(), h)) {
            return buttonTip("AE 模式", "开启后，交易优先走已绑定的在线 AE 网络。没绑定终端时开不了。");
        }
        if (hit(smx, smy, rechargeBtnX(), y, rechargeBtnW(), h)) {
            return buttonTip("充值全部", "把背包里的货币一次性存进钱包余额。");
        }
        return null;
    }

    private static List<Component> buttonTip(String title, String body) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("§6" + title));
        lines.add(Component.literal("§7" + body));
        return lines;
    }

    private void drawGrid(GuiGraphics g, int mx, int my) {
        int cols = gridColumns();
        int gx = listLeft(), gy = contentTop(), gw = listWidth(), gh = gridHeight();

        // 像素级平滑滚动：内容整体按 -scroll 平移，用 scissor 裁到网格矩形内。
        enableGridScissor(g, gx, gy, gx + gw, gy + gh);
        if (visibleEntryKeys.isEmpty()) {
            g.drawCenteredString(this.font, emptyGridHint(), gx + gw / 2, gy + 18, GRAY);
        }
        ShopGridViewport.Range range = visibleGridRange();
        resetLoadBlurScan();
        int drawnCellW = cellW();
        int drawnCellH = cellH();
        long frameNow = System.currentTimeMillis();
        for (int idx = range.fromInclusive(); idx < range.toExclusive(); idx++) {
            int col = idx % cols;
            int row = idx / cols;
            int cy = cellY(row);
            if (cy + drawnCellH <= gy || cy >= gy + gh) continue; // 只画与网格相交的 cell
            ShopEntry entry = visibleEntry(idx);
            int cx = cellX(col);
            long entryKey = visibleEntryKeys.get(idx);
            // 错峰入场：没登记的格子（滚动新露出的）按已到位处理，滚动不触发错峰。
            float sv = staggerCells.valueOr(entryKey, 1f, frameNow);
            boolean staggered = sv < 0.999f;
            if (staggered) {
                g.flush();
                g.pose().pushPose();
                g.pose().translate(0f, LOAD_CELL_SLIDE_PX * (1f - GuiMotion.clamp01(sv)), 0f);
                g.setColor(1f, 1f, 1f, GuiMotion.clamp01(sv));
            }
            try {
                loadSeen++;
                if (entry == null) {
                    drawLoadingCell(g, entryKey, cx, cy);
                    noteLoadingCell(idx, row, cx, cy, drawnCellW, drawnCellH, gx, gy, gw, gh);
                } else {
                    loadingCellAtMs.remove(entryKey);
                    closeLoadBlurRun();
                    drawCell(g, entry, entryKey, cx, cy, mx, my);
                }
            } finally { if (staggered) { g.flush(); g.setColor(1f, 1f, 1f, 1f); g.pose().popPose(); } }
        }
        closeLoadBlurRun();
        requestLoadingGauss();
        // 商品卡片拖拽中：高亮当前悬停的目标格子（原格子照常显示，不做实时重排，见 dragCardEntryKey 注释）
        if (dragCardEntryKey != -1L && dragCardMoving) {
            int targetIdx = entryIndexAt(dragCardMx, dragCardMy);
            if (targetIdx >= 0) {
                int tcol = targetIdx % cols;
                int trow = targetIdx / cols;
                int tcy = cellY(trow);
                if (tcy + cellH() > gy && tcy < gy + gh) {
                    renderOutline(g, cellX(tcol) - 1, tcy - 1, cellW() + 2, cellH() + 2, GOLD);
                }
            }
        }
        g.disableScissor();

        // 跟随鼠标的浮动图标不在这里画：它要压在帧末模糊之上，见 drawScaledTopLayer。
        drawGridScrollbar(g, gx, gy, gw, gh, cols, mx, my);
    }

    /** 1px 空心高亮框（不覆盖内容，只描边），供拖拽目标格子指示用。 */
    private static void renderOutline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** 未收到完整 JSON 时只画轻量占位，不提前创建 ItemStack 或访问物品模型。
     *  格子和物品槽留在原位。名称只在面板已经落位后横移 6px 进来，不跟开场缩放或切页横移叠第二段。 */
    private void drawLoadingCell(GuiGraphics g, long entryKey, int cx, int cy) {
        int cellW = cellW();
        int cellH = cellH();
        renderBox(g, cx, cy, cellW, cellH, GREEN_DARK, ROW_BG);
        int slotX = cx + 3, slotY = cy + 3;
        renderItemCheckerSlot(g, slotX, slotY);
        com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest.Stub stub =
                ClientShopCatalog.stub(entryKey);
        String label = "加载中…";
        if (stub != null && !stub.displayName().isEmpty()) label = stub.displayName();
        else if (stub != null && !stub.goodsIds().isEmpty()) label = stub.goodsIds().get(0);
        if (stub != null && ClientShopFavorites.contains(stub.stableId())) {
            g.drawString(this.font, "§e★", cx + cellW - 10, cy + 14, GOLD, false);
        }
        if (stub != null && batchSelectionStableIds.contains(stub.stableId())) {
            renderBatchSelectionOutline(g, cx, cy, cellW, cellH);
        }
        renderNumberBar(g, cx + 2, cy + cellH - 13, 34);
        float dx = loadingLabelDx(entryKey);
        boolean slide = dx > 0.05f;
        if (slide) {
            g.pose().pushPose();
            g.pose().translate(dx, 0f, 0f);
        }
        try {
            g.drawString(this.font, GuiRenderUtil.trimText(this.font, label, cellW - 29),
                    slotX + 23, cy + 4, GRAY, true);
            g.drawString(this.font, "§8加载中", cx + 5, cy + cellH - 11, GRAY, false);
        } finally {
            if (slide) g.pose().popPose();
        }
    }

    /** 占位第一次出现才记时刻。开场和切页已经在动，这段横移等它们落位再播，避免叠两条通道。 */
    private float loadingLabelDx(long entryKey) {
        long now = System.currentTimeMillis();
        Long seen = loadingCellAtMs.get(entryKey);
        if (seen == null) {
            if (loadingCellAtMs.size() > 256) loadingCellAtMs.clear();
            seen = now;
            loadingCellAtMs.put(entryKey, seen);
        }
        if (!shopMotionSettled()) return 0f;
        float t = GuiRenderUtil.popAnimProgress(seen, GuiRenderUtil.MOTION_MS);
        if (t >= 1f) return 0f;
        return LOAD_CELL_SLIDE_PX * (1f - GuiRenderUtil.easeOut(t));
    }

    private boolean shopMotionSettled() {
        return openMotion.settled()
                && GuiRenderUtil.popAnimProgress(gridSwitchAtMs, GuiRenderUtil.MOTION_MS) >= 1f
                && !staggering(System.currentTimeMillis());
    }

    /**
     * 下一帧起一次错峰。from 是起始进度：0 = 整格从透明浮入（开店、切分组、收藏筛选），
     * 0.5 = 只轻轻起伏（搜索逐字输入，免得每个按键都让格子闪一下）。同帧多次请求取更强的那个。
     */
    private void requestStagger(float from) {
        staggerFrom = pendingStagger ? Math.min(staggerFrom, from) : from;
        pendingStagger = true;
    }

    /**
     * 在渲染里延后到布局就绪时再起（切分组会 init 重排，触发点那一刻列数和可见范围还没定）。
     * 对当前可见范围内的格子各建一根 STAGGER 弹簧：从 staggerFrom 起，按 (列+行) 错开延迟后走向 1。
     */
    private void startStagger(long now) {
        staggerCells.clear();
        int cols = gridColumns();
        ShopGridViewport.Range range = visibleGridRange();
        for (int idx = range.fromInclusive(); idx < range.toExclusive(); idx++) {
            long delay = Math.min(STAGGER_MAX_DELAY_MS, (long) (idx % cols + idx / cols) * STAGGER_STEP_MS);
            staggerCells.start(visibleEntryKeys.get(idx), staggerFrom, 1f, now, delay);
        }
        staggerUntilMs = now + STAGGER_MAX_DELAY_MS + STAGGER_TAIL_MS;
    }

    private boolean staggering(long now) {
        return staggerUntilMs > 0L && now < staggerUntilMs;
    }

    /** 加载没完成时强度在 0.42 和 0.85 之间来回，密采样，不扫光。落位后的商品不进这个矩形。 */
    private static float loadingBlurStrength(long now) {
        long phase = Math.floorMod(now, LOAD_BLUR_PERIOD_MS);
        float p = phase / (float) LOAD_BLUR_PERIOD_MS;
        float tri = p < 0.5f ? p * 2f : (1f - p) * 2f;
        float s = tri * tri * (3f - 2f * tri);
        return 0.42f + 0.43f * s;
    }

    private void resetLoadBlurScan() {
        loadBlurRuns = 0;
        loadSeen = 0;
        loadMissing = 0;
        unionX0 = Integer.MAX_VALUE;
        unionY0 = Integer.MAX_VALUE;
        unionX1 = Integer.MIN_VALUE;
        unionY1 = Integer.MIN_VALUE;
        loadRunOpen = false;
        loadRunPrevIdx = -2;
    }

    private void noteLoadingCell(int idx, int row, int x, int y, int w, int h,
                                 int gx, int gy, int gw, int gh) {
        int x0 = Math.max(x, gx);
        int y0 = Math.max(y, gy);
        int x1 = Math.min(x + w, gx + gw);
        int y1 = Math.min(y + h, Math.min(gy + gh, contentBlurBottom()));
        if (x1 - x0 < 2 || y1 - y0 < 2) {
            closeLoadBlurRun();
            return;
        }
        loadMissing++;
        if (x0 < unionX0) unionX0 = x0;
        if (y0 < unionY0) unionY0 = y0;
        if (x1 > unionX1) unionX1 = x1;
        if (y1 > unionY1) unionY1 = y1;
        boolean extend = loadRunOpen && idx == loadRunPrevIdx + 1 && row == loadRunRow;
        if (extend) {
            loadRunR = x1;
            if (y0 < loadRunY) loadRunY = y0;
            if (y1 > loadRunB) loadRunB = y1;
        } else {
            closeLoadBlurRun();
            loadRunOpen = true;
            loadRunX = x0;
            loadRunY = y0;
            loadRunR = x1;
            loadRunB = y1;
            loadRunRow = row;
        }
        loadRunPrevIdx = idx;
    }

    private void closeLoadBlurRun() {
        if (!loadRunOpen) return;
        loadRunOpen = false;
        if (loadBlurRuns >= LOAD_BLUR_CAP) return;
        int w = loadRunR - loadRunX;
        int h = loadRunB - loadRunY;
        if (w < 2 || h < 2) return;
        int o = loadBlurRuns * 4;
        loadBlurVirtual[o] = loadRunX;
        loadBlurVirtual[o + 1] = loadRunY;
        loadBlurVirtual[o + 2] = w;
        loadBlurVirtual[o + 3] = h;
        loadBlurRuns++;
    }

    /** 整页都在加载时糊一整块；掺着已加载商品时只糊连续的占位条，最多 8 条。开场和切页让给原有的那一趟。 */
    private void requestLoadingGauss() {
        if (loadMissing <= 0 || stageLockActive || !shopMotionSettled()) return;
        if (this.minecraft == null || this.minecraft.getMainRenderTarget() == null) return;
        float strength = loadingBlurStrength(System.currentTimeMillis());
        float pixelScale = (float) this.minecraft.getMainRenderTarget().width
                / (float) Math.max(1, this.width);
        float radiusPx = ShopGaussBlur.BLUR_RADIUS_VIRTUAL * this.guiScale * pixelScale;
        if (loadMissing == loadSeen && unionX1 > unionX0 && unionY1 > unionY0) {
            int[] clipped = clampToShopInner(unionX0, unionY0, unionX1 - unionX0, unionY1 - unionY0);
            if (clipped[2] <= 1 || clipped[3] <= 1) return;
            ShopGaussBlur.requestSmooth(
                    toFramebufferX(clipped[0]), toFramebufferY(clipped[1]),
                    toFramebufferW(clipped[2]), toFramebufferH(clipped[3]),
                    strength, radiusPx, 0, 0, 0, 0, ShopGlassStyle.LOADING);
            return;
        }
        int n = 0;
        for (int i = 0; i < loadBlurRuns && n < LOAD_BLUR_CAP; i++) {
            int o = i * 4;
            int[] clipped = clampToShopInner(
                    loadBlurVirtual[o], loadBlurVirtual[o + 1], loadBlurVirtual[o + 2], loadBlurVirtual[o + 3]);
            if (clipped[2] <= 1 || clipped[3] <= 1) continue;
            int d = n * 4;
            loadBlurFb[d] = toFramebufferX(clipped[0]);
            loadBlurFb[d + 1] = toFramebufferY(clipped[1]);
            loadBlurFb[d + 2] = toFramebufferW(clipped[2]);
            loadBlurFb[d + 3] = toFramebufferH(clipped[3]);
            n++;
        }
        if (n <= 0) return;
        if (n == 1) {
            ShopGaussBlur.requestSmooth(
                    loadBlurFb[0], loadBlurFb[1], loadBlurFb[2], loadBlurFb[3],
                    strength, radiusPx, 0, 0, 0, 0, ShopGlassStyle.LOADING);
        } else {
            ShopGaussBlur.requestSmoothCells(loadBlurFb, n, strength, radiusPx, ShopGlassStyle.LOADING);
        }
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
        int contentH = rows * rowStride();
        int barH = Math.max(10, gh * gh / contentH);
        int barY = gy + (gh - barH) * scroll / maxScroll;
        boolean hv = draggingGridScroll || GuiRenderUtil.isHovering(mx, my, barX, barY, GRID_SCROLLBAR_W, barH);
        g.fill(barX, barY, barX + GRID_SCROLLBAR_W, barY + barH, hv ? CYAN : GOLD);
    }

    /** 网格滚动条点击/拖拽起手：命中整条轨道即可（不用精确点在把手上），随即按该点位置跳转。 */
    private boolean gridScrollbarClicked(double mx, double my) {
        int gy = contentTop(), gh = gridHeight();
        int barX = gridScrollbarX();
        if (mx < barX || mx > barX + GRID_SCROLLBAR_W || my < gy || my > gy + gh) return false;
        draggingGridScroll = true;
        updateGridScrollFromDrag(my);
        return true;
    }

    /** 按拖拽点的 Y 坐标反算 scroll（把手中心跟随鼠标），无溢出时不生效。 */
    private void updateGridScrollFromDrag(double my) {
        int maxScroll = maxGridScroll();
        if (maxScroll <= 0) { snapGridScroll(0); return; }
        int gy = contentTop(), gh = gridHeight();
        int cols = gridColumns();
        int rows = (visibleEntryKeys.size() + cols - 1) / cols;
        int contentH = rows * rowStride();
        int barH = Math.max(10, gh * gh / contentH);
        double usable = Math.max(1, gh - barH);
        double rel = (my - gy - barH / 2.0) / usable;
        snapGridScroll((int) Math.round(Math.max(0.0, Math.min(1.0, rel)) * maxScroll));
    }

    private int detailViewportTop() { return contentTop() + 126; }

    private int detailViewportBottom() {
        int btnY = contentTop() + contentHeight() - 24;
        return Math.max(detailViewportTop() + 24, catalogEditUnlocked ? btnY - 48 : btnY - 4);
    }

    private int detailViewportHeight() { return detailViewportBottom() - detailViewportTop(); }

    /** 详情列滚动条 X：从正文右缘 detailX()+DETAIL_W-8 再让出 DETAIL_SCROLLBAR_GAP，整条落在右侧 8px 内缩带里。 */
    private int detailScrollbarX() { return detailX() + DETAIL_W - 8 + DETAIL_SCROLLBAR_GAP; }

    private int clampDetailScroll(int value) {
        return Math.max(0, Math.min(detailScrollMax, value));
    }

    private void drawDetailScrollbar(GuiGraphics g, int mx, int my) {
        int top = detailViewportTop();
        int h = detailViewportHeight();
        if (h <= 0) return;
        int barX = detailScrollbarX();
        g.fill(barX, top, barX + DETAIL_SCROLLBAR_W, top + h, NUMBER_BAR_BG);
        if (detailScrollMax <= 0) return;
        int contentH = h + detailScrollMax;
        int barH = Math.max(10, h * h / Math.max(1, contentH));
        int barY = top + (h - barH) * detailScroll / Math.max(1, detailScrollMax);
        boolean hv = draggingDetailScroll || GuiRenderUtil.isHovering(mx, my, barX, barY, DETAIL_SCROLLBAR_W, barH);
        g.fill(barX, barY, barX + DETAIL_SCROLLBAR_W, barY + barH, hv ? CYAN : GOLD);
    }

    private boolean detailScrollbarClicked(double mx, double my) {
        int top = detailViewportTop();
        int h = detailViewportHeight();
        int barX = detailScrollbarX();
        if (detailScrollMax <= 0 || mx < barX || mx > barX + DETAIL_SCROLLBAR_W || my < top || my > top + h) return false;
        draggingDetailScroll = true;
        updateDetailScrollFromDrag(my);
        return true;
    }

    private void updateDetailScrollFromDrag(double my) {
        if (detailScrollMax <= 0) { snapDetailScroll(0); return; }
        int top = detailViewportTop();
        int h = detailViewportHeight();
        int contentH = h + detailScrollMax;
        int barH = Math.max(10, h * h / Math.max(1, contentH));
        double usable = Math.max(1, h - barH);
        double rel = (my - top - barH / 2.0) / usable;
        snapDetailScroll((int) Math.round(Math.max(0.0, Math.min(1.0, rel)) * detailScrollMax));
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
        final int cellWidth;
        final ItemStack goodsStack; // 无自定义显示图标、主商品非流体时用；否则为 null（流体走 goodsFluid，组合/自定义走 renderIconComposite）
        final net.minecraft.world.level.material.Fluid goodsFluid; // 主商品是流体时非空（同 renderFluidIcon 配套）
        final String trimmedName;
        final String amtText;
        final ItemStack costIcon;
        final boolean sparkPrimary;
        final boolean euPrimary;
        final boolean fluidPrimary;
        final int extra;

        CellCache(int cellWidth, ItemStack goodsStack, net.minecraft.world.level.material.Fluid goodsFluid, String trimmedName,
                  String amtText, ItemStack costIcon, boolean sparkPrimary, boolean euPrimary, boolean fluidPrimary, int extra) {
            this.cellWidth = cellWidth;
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
        int cellW = cellW();
        if (cached != null && cached.cellWidth == cellW) return cached;
        CellCache built = buildCellCache(entry);
        CELL_CACHE.put(entry, built);
        return built;
    }

    /** 截断宽度推导：rightX-textX = (cx+cellW()-3)-(cx+3+23) = cellW()-29，与格子的 cx 无关。 */
    private CellCache buildCellCache(ShopEntry entry) {
        int cellW = cellW();
        boolean noCustomIcons = entry.effectiveIcons().isEmpty();
        ItemStack goodsStack = noCustomIcons && !entry.isPrimaryGoodsFluid() ? entry.displayGoodsStack() : null;
        net.minecraft.world.level.material.Fluid goodsFluid = noCustomIcons && entry.isPrimaryGoodsFluid() ? entry.primaryGoodsFluid() : null;
        String trimmedName = GuiRenderUtil.trimText(this.font, entry.goodsDisplayName(), cellW - 29);
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
        return new CellCache(cellW, goodsStack, goodsFluid, trimmedName, amtText, costIcon, sparkPrimary, euPrimary, fluidPrimary, extra);
    }

    /** KE 风格横向格：左棋盘物品槽 + 右侧名称/状态 + 底部价格数字条 + 货币小图标。 */
    private void drawCell(GuiGraphics g, ShopEntry entry, long entryKey, int cx, int cy, int mx, int my) {
        boolean sel = selected == entry;
        int cellW = cellW();
        int cellH = cellH();
        boolean hover = GuiRenderUtil.isHovering(mx, my, cx, cy, cellW, cellH);
        float hv = hoverCells.follow(entryKey, hover ? 1f : 0f, System.currentTimeMillis());
        // 限时折扣：格子太小塞不下额外文字（见 CurrencyAtmScreen 教训，不硬挤新元素），改用边框/价格变色示意，
        // 具体折扣%和倒计时留给悬浮 tooltip（buildTooltip）和详情页（drawDetail）精确显示
        boolean onSale = entry.isDiscountActive();
        int border = sel ? GOLD : (onSale ? RED_DARK : GREEN_DARK);
        int fill = sel ? SELECT_BG : GuiMotion.lerpColor(ROW_BG, ROW_HOVER, hv);
        renderBox(g, cx, cy, cellW, cellH, border, fill);
        if (sel) renderSelectionOutline(g, cx, cy, cellW, cellH);
        if (isBatchSelected(entry)) renderBatchSelectionOutline(g, cx, cy, cellW, cellH);

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

        String categoryBadge = cardCategoryBadge(entry);
        if (!categoryBadge.isEmpty()) {
            int badgeMaxW = cellW - 29 - (ClientShopFavorites.contains(entry.getStableId()) ? 12 : 0);
            g.drawString(this.font, GuiRenderUtil.trimText(this.font, categoryBadge, badgeMaxW),
                    textX, cy + 15, CYAN, false);
        }

        // 收藏角标（名称行与底部价格条之间的空白带，右对齐，不挤占任何已有元素）
        if (ClientShopFavorites.contains(entry.getStableId())) {
            g.drawString(this.font, "§e★", cx + cellW - 10, cy + 14, GOLD, false);
        }

        // 底部成本数字条 + 主成分图标 +「+K」（多元成本取主成分：币种 > 星火 > 实物；全部缓存好）
        int numX = cx + 2;
        int numY = cy + cellH - 13;
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
        renderButton(g, x, y, w, h, hover, text, border, textColor, pressKey(x, y, w, h));
    }

    /**
     * 同上，另带反馈：压下按几何 key，等待流光与结果闪色按 {@code actionKey}
     * （确认购买按钮混入了商品与买卖模式，换商品后旧结果不会闪到新商品上）。
     */
    private void renderButton(GuiGraphics g, int x, int y, int w, int h, boolean hover, String text,
                              int border, int textColor, long actionKey) {
        ButtonFeedback.Visual v = buttonFeedback.visual(pressKey(x, y, w, h), actionKey, true,
                System.currentTimeMillis(), reduceMotionFrame);
        int edge = ButtonFeedback.tint(border, v);
        int fill = ButtonFeedback.shade(hover ? BUTTON_HOVER : BUTTON_BG, v);
        int ink = ButtonFeedback.tint(textColor, v);
        GuiRenderUtil.withButtonMotion(g, x, y, w, h, v.scale(), v.shakeX(), () -> {
            g.fill(x, y, x + w, y + h, edge);
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
            if (v.pending() && !reduceMotionFrame) {
                GuiRenderUtil.drawPendingSweep(g, x + 1, y + 1, w - 2, h - 2, v.pendingPhase(), 0xE0FFFFFF);
            }
            g.drawCenteredString(this.font, GuiRenderUtil.trimText(this.font, text, w - 4), x + w / 2, y + (h - 8) / 2, ink);
        });
    }

    /** 选中格的青色四边描边。 */
    private static void renderSelectionOutline(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 1, y - 1, x + w + 1, y, CYAN);
        g.fill(x - 1, y + h, x + w + 1, y + h + 1, CYAN);
        g.fill(x - 1, y, x, y + h, CYAN);
        g.fill(x + w, y, x + w + 1, y + h, CYAN);
    }

    /** 批量选中态：金色双层外框，包住原卡片边缘并与详情的青色单层框区分。 */
    private static void renderBatchSelectionOutline(GuiGraphics g, int x, int y, int w, int h) {
        renderOutline(g, x - 2, y - 2, w + 4, h + 4, GOLD_DARK);
        renderOutline(g, x - 1, y - 1, w + 2, h + 2, GOLD);
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
        previewHoverOrder = null;
        previewHoverStack = null; // 每帧先清，命中物品预览格再置
        favoriteMissingBtnVisible = false;
        detailHoverStack = null; // 每帧先清，命中大图标再置
        descExpandVisible = false; // 每帧先清，描述非空才置
        submissionBtnVisible = false;
        stageSubmissionBtnVisible = false;
        stageSubmissionAutoCraftVisible = false;
        int dx = detailX();
        int dw = DETAIL_W;
        int detailInnerW = dw - 16;
        int dy = contentTop();
        int dh = contentHeight();
        // 面板背景（KE 风格描边盒）
        renderBox(g, dx, dy, dw, dh, GOLD_DARK, BOX_BG);

        if (selected == null) {
            drawEmptyDetailGuide(g, dx, dy, dw, dh);
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
        g.drawString(this.font, GuiRenderUtil.trimText(this.font, selected.goodsDisplayName(), dx + dw - 6 - textX),
                textX, dy + 10, WHITE, true);
        String countLine = selected.hasMultipleGoods()
                ? "§7组合商品 §f" + selected.getGoodsList().size() + " §7种"
                : "§7每份 §f" + selected.getGoodsCount() + " §7个";
        if (selected.isLimited()) {
            countLine += " §7剩§d" + formatBig(java.math.BigInteger.valueOf(selected.getRemainingUses())) + "§7次";
        }
        g.drawString(this.font, GuiRenderUtil.trimText(this.font, countLine, dx + dw - 6 - textX), textX, dy + 24, GRAY, true);

        // 成本（多元，青）——超宽截断，全量见 tooltip / 预计行。限时折扣 + 会员折扣（见 ShopMembership）生效时
        // 原价→折后价+各自标注挤进同一行，不另起一行（详情页下面一串固定 Y 坐标的内容，插一行要连带改一串
        // 偏移，风险不值得，见 CurrencyAtmScreen 的教训）
        int memberTier = ClientWalletAccount.getMemberTier();
        int memberPct = ShopMembership.discountPercentForTier(memberTier);
        boolean anyDiscount = selected.isDiscountActive() || memberPct > 0;
        String saleTag = selected.isDiscountActive()
                ? " §6(限时-" + selected.getDiscountPercent() + "% 剩" + formatDuration(selected.discountRemainingMs() / 1000L) + ")" : "";
        String memberTag = memberPct > 0 ? " §d[会员" + ShopMembership.tierNameForTier(memberTier) + "-" + memberPct + "%]" : "";
        String costLabel = anyDiscount
                ? "§7成本: §8" + costInline(selected.getCost()) + " §a→ " + costInline(selected.getEffectiveCost(memberPct)) + saleTag + memberTag
                : "§7成本: " + costInline(selected.getCost());
        g.drawString(this.font, GuiRenderUtil.trimText(this.font, costLabel,
                dx + dw - 6 - cx), cx, dy + 42, CYAN, true);

        // 数量标签 + AE 风格步进按钮（文本框 amountBox 由 initScaled 定位、super.render 绘制）
        g.drawString(this.font, (mode == Mode.BUY ? "§7购买次数（可输入）:" : "§7出售次数（可输入）:"), cx, dy + 56, WHITE, true);
        // 三排步进：+1 +10 +100 +1000 / -1 -10 -100 -1000 / ×10 ×100 ÷10 ÷100
        int stepY1 = dy + 82, stepY2 = dy + 96, stepY3 = dy + 110;
        long[] steps = {1, 10, 100, 1000};
        int bw = (detailInnerW - 9) / 4; // 4 按钮 + 3 间隙
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
        // 买价：限时折扣 + 会员折扣叠加（见 ShopMembership）；出售：按价差折价拿回（见 ShopPurchase#sellRatioPercent），
        // 出售不是「反向打折」，走独立的口径，不受买价折扣影响
        ShopCost dcost = mode == Mode.BUY
                ? selected.getEffectiveCost(ShopMembership.discountPercentForTier(ClientWalletAccount.getMemberTier()))
                : selected.getCost().scaledTo(ShopPurchase.sellRatioPercent());
        int py = dy + 128;
        int costTrimW = dx + dw - 10 - cx;
        String key = WalletAccountAPI.purchaseKey(selected);
        long bought = ClientWalletAccount.getPurchaseCount(key);
        int btnY = dy + dh - 24;
        // GuideME 集成：查一次商品清单是否有指南页命中，selected 没变就不重复扫描所有已装指南
        if (guideHitsFor != selected) {
            guideHitsFor = selected;
            guideHits = com.dishanhai.gt_shanhai.client.shop.ShopGuideLookup.findGuideHits(selected.getGoodsList());
        }
        // 山海跳转入口：能解析到目标时显示；长称呼换行，行高随实际内容增长。
        linkVisible = false;
        linkTargetKey = selected.hasLinkTarget()
                ? ClientShopCatalog.resolveLink(selected.getLinkTo()) : -1L;
        linkTarget = linkTargetKey >= 0L ? ClientShopCatalog.get(linkTargetKey) : null;
        guideLinkVisible = false;
        guideDetailBtnVisible = false;
        // 「补齐全部缺口」：只在确定（非"加载中"）有不够的项、且开着 AE 模式时出现——AE 自动合成只对着 AE 网络补，
        // 关了 AE 模式点这个没意义（缺口判定本身也不含 AE 那部分，见 hasCostShortfall）。
        boolean costShortfall = hasCostShortfall(dcost, amount, selectedEntryKey, aeMode);
        // 「补齐全部缺口」只在 AE 模式有确定缺口时出现。收藏缺失不依赖 AE，缺口本身就能写进 JEI。
        boolean showAutoCraftBtn = mode == Mode.BUY && aeMode && costShortfall;
        boolean showFavoriteMissingBtn = mode == Mode.BUY && costShortfall;
        buyMaterialsBtnVisible = false;
        boolean showBuyMaterialsBtn = mode == Mode.BUY && hasShopPurchasableMaterialCostCached(selected, dcost);
        // 前置任务跳转行：条目配了前置任务就占一行（无论能否解析到，未同步/已删都要给玩家一个可见提示）
        prereqQuest = selected.hasPrerequisiteQuest()
                ? com.dishanhai.gt_shanhai.client.shop.ShopFtbqPrereqLookup.resolve(selected.getPrerequisiteQuestId()) : null;
        prereqQuestCompleted = com.dishanhai.gt_shanhai.client.shop.ShopFtbqPrereqLookup.isCompleted(prereqQuest);
        int viewportTop = detailViewportTop();
        int viewportBottom = detailViewportBottom();
        int viewportH = Math.max(1, viewportBottom - viewportTop);
        advanceDetailScroll(System.currentTimeMillis());
        int hoverMy = my + detailScroll;
        int contentEndY = py;

        enableGridScissor(g, dx + 2, viewportTop, dx + dw - 2, viewportBottom);
        g.pose().pushPose();
        g.pose().translate(0.0f, -detailScroll, 0.0f);

        String txLine = "§7交易次数: §f" + formatBig(amt) + "  §7已购买: §6" + formatBig(java.math.BigInteger.valueOf(bought));
        String tradeModeTip = switch (selected.getTradeMode()) {
            case BUY_ONLY -> "  §b[仅购买]";
            case SELL_ONLY -> "  §e[仅出售]";
            default -> "";
        };
        g.drawString(this.font, GuiRenderUtil.trimText(this.font, txLine + tradeModeTip, costTrimW), cx, py, WHITE, true);
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
        boolean previewBar = ClientShopUiSettings.rewardPreviewBar();
        boolean normalGoods = selected.getRewardMode() == ShopEntry.RewardMode.NONE;
        // 方格 + 组合商品走原来的图标格。条形、以及奖励表的两种预览，底边记在 oddsBottom。
        boolean comboGrid = mode == Mode.BUY && normalGoods && !previewBar && selected.hasMultipleGoods();
        int rewardEndY = contentY + 66; // 单行组合预览的旧终点；多行时由 drawRewardPreview 回写
        int oddsBottom = -1; // 条形或奖励表方格的底边；<0 表示这次没画这块
        if (mode == Mode.BUY) {
            g.drawString(this.font, GuiRenderUtil.trimText(this.font, "§7预计消耗: " + costInlineTimes(dcost, amount), costTrimW), cx, contentY + 12, CYAN, true);
            ShopEntry.RewardMode rm = selected.getRewardMode();
            int physicalHintY;
            if (comboGrid) {
                rewardEndY = drawRewardPreview(g, cx, contentY + 24, selected.getGoodsList(), amount, detailInnerW, mx, hoverMy);
                physicalHintY = rewardEndY;
            } else if (normalGoods && previewBar) {
                g.drawString(this.font, "§6预计获得:", cx, contentY + 24, GOLD, true);
                oddsBottom = drawGoodsBars(g, cx, contentY + 36, selected.getGoodsList(), amount, detailInnerW, mx, hoverMy);
                physicalHintY = oddsBottom;
            } else if (normalGoods) {
                g.drawString(this.font, GuiRenderUtil.trimText(this.font, "§7预计获得: §e" + formatBig(total) + " §7个", costTrimW), cx, contentY + 24, GREEN, true);
                physicalHintY = contentY + 36;
            } else {
                String getLine = switch (rm) {
                    case CHOICE -> "§7预计获得: §d自选 1 项";
                    case RANDOM -> "§7预计获得: §d按下方概率随机 1 项";
                    case ALL -> "§7预计获得: §d以下全部都给";
                    case FTBQ -> switch (selected.getFtbqSubMode()) {
                        case CHOICE -> "§7预计获得: §9FTBQ 自选 1 项";
                        case ALL -> "§7预计获得: §9FTBQ 全部物品";
                        default -> "§7预计获得: §9FTBQ 按下方概率随机";
                    };
                    default -> "§7预计获得: §e" + formatBig(total) + " §7个";
                };
                g.drawString(this.font, GuiRenderUtil.trimText(this.font, getLine, costTrimW), cx, contentY + 24, GREEN, true);
                oddsBottom = previewBar
                        ? drawRewardOdds(g, cx, contentY + 36, selected, detailInnerW, mx, hoverMy)
                        : drawRewardOddsGrid(g, cx, contentY + 36, selected, detailInnerW, mx, hoverMy);
                physicalHintY = oddsBottom;
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

        maybeRequestCostPreview(dcost);
        int costPreviewY = comboGrid ? rewardEndY + 14
                : oddsBottom >= 0 ? (dcost.hasPhysical() ? oddsBottom + 14 : oddsBottom + 4)
                : contentY + 50;
        int cursorY = drawCostPreview(g, cx, costPreviewY, dcost, amount, detailInnerW, py + viewportH + 2048, mx, hoverMy);
        contentEndY = Math.max(contentEndY, cursorY);
        int actionY = cursorY + 2;
        if (linkTarget != null) {
            linkVisible = true;
            String linkLabel = "→ 跳转: " + ShopLinkNames.label(linkTarget.getLinkKey(), linkTarget.goodsDisplayName());
            List<net.minecraft.util.FormattedCharSequence> linkText = this.font.split(Component.literal(linkLabel), detailInnerW);
            linkX = cx; linkY = actionY - detailScroll; linkW = detailInnerW; linkH = Math.max(1, linkText.size()) * 10;
            boolean linkHover = GuiRenderUtil.isHovering(mx, hoverMy, cx, actionY, linkW, linkH);
            linkText = this.font.split(Component.literal((linkHover ? "§b§n" : "§9§n") + linkLabel), detailInnerW);
            for (int i = 0; i < linkText.size(); i++) {
                g.drawString(this.font, linkText.get(i), cx, actionY + i * 10, linkHover ? CYAN : GOLD, true);
            }
            actionY += linkH + 2;
        }
        if (guideHits.size() == 1) {
            com.dishanhai.gt_shanhai.client.shop.ShopGuideLookup.GuideHit hit = guideHits.get(0);
            guideLinkVisible = true;
            guideLinkHit = hit;
            guideLinkX = cx; guideLinkY = actionY - detailScroll; guideLinkW = detailInnerW; guideLinkH = 10;
            String guideLabel = "→ 指南: " + hit.item().getHoverName().getString();
            drawDetailLink(g, cx, actionY, guideLinkW, guideLinkH, guideLabel, mx, hoverMy);
            actionY += 12;
        } else if (guideHits.size() > 1) {
            guideDetailBtnVisible = true;
            guideDetailBtnX = cx; guideDetailBtnY = actionY - detailScroll; guideDetailBtnW = detailInnerW; guideDetailBtnH = 10;
            drawButton(g, cx, actionY, guideDetailBtnW, guideDetailBtnH, "§d指南详情(" + guideHits.size() + ")", mx, hoverMy);
            actionY += 12;
        }
        autoCraftBtnVisible = showAutoCraftBtn;
        if (showAutoCraftBtn) {
            autoCraftBtnX = cx; autoCraftBtnY = actionY - detailScroll; autoCraftBtnW = detailInnerW; autoCraftBtnH = 10;
            drawButton(g, cx, actionY, autoCraftBtnW, autoCraftBtnH,
                    detailActionLabel(DETAIL_AUTO_CRAFT, "§b⚙ 补齐全部缺口（AE自动合成）"),
                    mx, hoverMy, detailActionKey(DETAIL_AUTO_CRAFT));
            actionY += 12;
        }
        favoriteMissingBtnVisible = showFavoriteMissingBtn;
        if (showFavoriteMissingBtn) {
            favoriteMissingBtnX = cx; favoriteMissingBtnY = actionY - detailScroll;
            favoriteMissingBtnW = detailInnerW; favoriteMissingBtnH = 10;
            drawButton(g, cx, actionY, favoriteMissingBtnW, favoriteMissingBtnH,
                    detailActionLabel(DETAIL_BOOKMARK, "§e收藏缺失"), mx, hoverMy, detailActionKey(DETAIL_BOOKMARK));
            actionY += 12;
        }
        buyMaterialsBtnVisible = showBuyMaterialsBtn;
        if (showBuyMaterialsBtn) {
            buyMaterialsBtnX = cx; buyMaterialsBtnY = actionY - detailScroll; buyMaterialsBtnW = detailInnerW; buyMaterialsBtnH = 10;
            drawButton(g, cx, actionY, buyMaterialsBtnW, buyMaterialsBtnH, "§a购买原料", mx, hoverMy);
            actionY += 12;
        }
        prereqLinkVisible = selected.hasPrerequisiteQuest();
        if (prereqLinkVisible) {
            prereqLinkX = cx; prereqLinkY = actionY - detailScroll; prereqLinkW = detailInnerW; prereqLinkH = 10;
            boolean prereqHover = GuiRenderUtil.isHovering(mx, hoverMy, cx, actionY, prereqLinkW, prereqLinkH);
            String prereqLabel = prereqQuest == null
                    ? "→ 前置任务: §c未找到/未同步 #" + selected.getPrerequisiteQuestId()
                    : "→ 前置任务: " + (prereqQuestCompleted ? "§a[已完成] " : "§c[未完成] ") + prereqQuest.getRawTitle();
            int prereqColor = prereqQuest == null ? DEEP_RED : (prereqQuestCompleted ? (prereqHover ? CYAN : GREEN) : (prereqHover ? CYAN : GOLD));
            g.drawString(this.font, (prereqHover ? "§n" : "") + GuiRenderUtil.trimText(this.font, prereqLabel, prereqLinkW),
                    cx, actionY, prereqColor, true);
            actionY += 12;
        }
        submissionBtnVisible = selected.hasSubmissionRequirement()
                && !com.dishanhai.gt_shanhai.client.shop.ClientShopUnlockState.hasEntry(selected.getStableId());
        if (submissionBtnVisible) {
            g.drawString(this.font, "§6提交商品固定物品（一次性解锁）:", cx, actionY, GOLD, true);
            actionY += 12;
            actionY = drawSubmissionRequirementPreview(g, cx, actionY, selected.getSubmissionItems(), detailInnerW, mx, hoverMy);
            submissionAutoCraftVisible = aeMode;
            if (submissionAutoCraftVisible) {
                submissionAutoCraftX = cx; submissionAutoCraftY = actionY - detailScroll;
                submissionAutoCraftW = detailInnerW; submissionAutoCraftH = 18;
                drawButton(g, cx, actionY, detailInnerW, 18, "§b⚙ AE下单补齐商品前置", mx, hoverMy);
                actionY += 21;
            }
            submissionBtnX = cx; submissionBtnY = actionY - detailScroll; submissionBtnW = detailInnerW; submissionBtnH = 18;
            drawButton(g, cx, actionY, detailInnerW, 18, "§e提交商品固定物品", mx, hoverMy);
            actionY += 21;
        }
        String missingStage = missingStageSubmission(selected.getCategory());
        stageSubmissionBtnVisible = missingStage != null;
        if (stageSubmissionBtnVisible) {
            // 编辑模式会取消全屏阶段遮挡，AE 下单不能只挂在遮挡层上。
            var stageReqs = com.dishanhai.gt_shanhai.client.shop.ClientShopUnlockState.stageRequirements(missingStage);
            maybeRequestStagePreview(missingStage);
            g.drawString(this.font, "§6提交阶段固定物品（一次性解锁）:", cx, actionY, GOLD, true);
            actionY += 12;
            actionY = drawSubmissionRequirementPreview(g, cx, actionY, stageReqs, detailInnerW, mx, hoverMy, missingStage);
            stageSubmissionAutoCraftVisible = aeMode && !stageReqs.isEmpty();
            if (stageSubmissionAutoCraftVisible) {
                stageSubmissionAutoCraftX = cx; stageSubmissionAutoCraftY = actionY - detailScroll;
                stageSubmissionAutoCraftW = detailInnerW; stageSubmissionAutoCraftH = 18;
                drawButton(g, cx, actionY, detailInnerW, 18, "§b⚙ AE下单补齐阶段前置", mx, hoverMy);
                actionY += 21;
            }
            stageSubmissionBtnX = cx; stageSubmissionBtnY = actionY - detailScroll; stageSubmissionBtnW = detailInnerW; stageSubmissionBtnH = 18;
            drawButton(g, cx, actionY, detailInnerW, 18, "§e提交阶段物品: " + missingStage, mx, hoverMy);
            actionY += 21;
        }

        String desc = selected.getDescription();
        if (desc != null && !desc.isEmpty()) {
            int descY = actionY + 2;
            g.drawString(this.font, "§6描述:", cx, descY, GOLD, true);
            int expandW = 52;
            descExpandVisible = true;
            descExpandX = cx + detailInnerW - expandW;
            descExpandY = descY - 3 - detailScroll;
            descExpandW = expandW;
            descExpandH = 10;
            drawButton(g, descExpandX, descY - 3, descExpandW, descExpandH, "§b展开详情", mx, hoverMy);
            int ly = descY + 11;
            for (net.minecraft.util.FormattedCharSequence line
                    : this.font.split(Component.literal("§7" + GuiRenderUtil.translateAmpCodes(desc)), detailInnerW)) {
                g.drawString(this.font, line, cx, ly, GRAY, true);
                ly += 9;
            }
            actionY = ly;
        }
        contentEndY = Math.max(contentEndY, actionY + 2);

        g.pose().popPose();
        g.disableScissor();
        detailScrollMax = Math.max(0, contentEndY - viewportBottom);
        detailScroll = clampDetailScroll(detailScroll);
        detailScrollTarget = clampDetailScroll(detailScrollTarget);
        drawDetailScrollbar(g, mx, my);

        // 确认按钮（KE 风格 border+fill，颜色随可交易性）
        // 前置任务未配置视为满足；配置了则须客户端已解析到且已完成——跟服务端 doBuy 的门槛口径一致（见反馈：
        // 花费预览格全绿时按钮不能还显示红，前置任务同理，不能光看成本够不够）
        boolean prereqSatisfiedClient = !selected.hasPrerequisiteQuest() || prereqQuestCompleted;
        boolean submissionsSatisfiedClient = !submissionBtnVisible && !stageSubmissionBtnVisible;
        boolean canTrade = mode == Mode.BUY
                ? (canAffordClient(dcost, amount) && prereqSatisfiedClient && submissionsSatisfiedClient) // dcost 已含限时折扣+会员折扣（BUY 分支同一个值）
                : (!selected.getCost().isEmpty() && !selected.getCost().hasPhysical() && !selected.hasMultipleGoods()
                    && ShopPurchase.countItem(Minecraft.getInstance().player, selected.getGoodsItem()) >= selected.getGoodsCount());
        boolean btnHover = hit(mx, my, cx, btnY, detailInnerW, 20);
        long confirmKey = confirmActionKey();
        renderButton(g, cx, btnY, detailInnerW, 20, btnHover,
                confirmLabel(confirmKey, System.currentTimeMillis()),
                canTrade ? GREEN_DARK : DEEP_RED, canTrade ? (mode == Mode.BUY ? GREEN : GOLD) : DEEP_RED, confirmKey);

        // 编辑/删除按钮：都折进了编辑模式（catalogEditUnlocked），没开时两行一起隐藏
        if (catalogEditUnlocked) {
            boolean editHover = hit(mx, my, cx, btnY - 44, detailInnerW, 18);
            renderButton(g, cx, btnY - 44, detailInnerW, 18, editHover, "§b编辑条目", GOLD_DARK, CYAN);
            // 误触防护：第一次点「删除此商品」只进入 3 秒确认窗口（按钮变金边+闪烁警示色），窗口内对同一条目
            // 再点一次才真正发删除包；换条目/超时都会自动退回未确认态，见 universalMouseClicked 里的对应逻辑
            boolean delArmed = pendingDeleteEntry == selected
                    && System.currentTimeMillis() - pendingDeleteArmedAtMs < DELETE_CONFIRM_WINDOW_MS;
            boolean delHover = hit(mx, my, cx, btnY - 22, detailInnerW, 18);
            String delLabel = delArmed ? "§c⚠再点一次确认删除！" : "§c删除此商品";
            int delBorder = delArmed ? GOLD : RED_DARK;
            int delText = delArmed && (System.currentTimeMillis() / 300L % 2 == 0) ? WHITE : RED;
            renderButton(g, cx, btnY - 22, detailInnerW, 18, delHover, delLabel, delBorder, delText);
        }
    }

    /**
     * 绘制「商品卡片 → 详情面板」的飞行层。
     *
     * <p>只做一件事：把被点卡片的矩形沿弹簧进度 p 连续插值到详情列矩形。位置和尺寸是同一条
     * 几何通道，不额外叠平移/缩放两套动画；图标与名称按同一 p 从卡片头部滑到详情头部，
     * 落地时正好压在详情面板的同名元素上，再随遮罩一起淡出，避免换内容的硬切。</p>
     *
     * <p>源卡片留在原位，磨砂叠层之外再交给 {@link ShopGaussBlur} 做第二块高斯；
     * 右侧详情面板是第一块。两块都挖掉正在飞行的卡片。后处理在界面帧末对
     * {@code minecraft:main} 做局部采样，不另分配渲染目标。</p>
     */
    private void renderCardFlight(GuiGraphics g) {
        if (!cardFlightActive || cardFlightEntry == null) return;

        cardFlightSpring.update(System.currentTimeMillis());
        float p = cardFlightSpring.value();
        if (cardFlightSpring.settled()) {
            cardFlightActive = false;
            cardFlightEntry = null;
            ShopGaussBlur.clear();
            return;
        }

        // 记录最近几帧的进度（0=刚起手，越旧越靠后），残影直接复用这些值
        for (int i = cardFlightTrails.length - 1; i > 0; i--) {
            cardFlightTrails[i] = cardFlightTrails[i - 1];
        }
        cardFlightTrails[0] = p;

        // 最后 7% 让飞行层和源卡片磨砂一起溶解，露出下面已经画好的详情面板；弹簧归位前就完成交接。
        float fade = 1f - smoothstep(0.93f, 1f, p);
        if (fade <= 0.01f) {
            ShopGaussBlur.clear();
            return;
        }

        int rx = flightValue(cardFlightSrcX, cardFlightDstX, p);
        int ry = flightValue(cardFlightSrcY, cardFlightDstY, p);
        int rw = flightValue(cardFlightSrcW, cardFlightDstW, p);
        int rh = flightValue(cardFlightSrcH, cardFlightDstH, p);

        g.flush(); // 网格/详情的物品图标先落盘，磨砂和飞行层才能稳定压在上面
        g.pose().pushPose();
        try {
            // 用着色器颜色统一淡出飞行层（含物品图标），避免最后一下硬切
            g.setColor(1f, 1f, 1f, fade);

            // 右侧详情面板：真正的两趟可分离高斯后处理（ScreenEvent.Render.Post）。
            // Rect 只覆盖详情列，Hole 把正在飞的卡片挖掉保持清晰。
            // 模糊晚于位移：前 32ms 清晰，32→80ms 淡入，落位仍在 p 0.90→1 淡出。
            float elapsed = (float) (System.currentTimeMillis() - this.cardFlightStartMs);
            float blurIn = smoothstep(PANEL_BLUR_DELAY_MS, PANEL_BLUR_DELAY_MS + PANEL_BLUR_IN_MS, elapsed);
            float panelBlur = blurIn * (1f - smoothstep(0.90f, 1f, p));
            if (panelBlur > 0.01f) {
                int detailLeft = detailX();
                int detailTop = contentTop();
                int detailW = DETAIL_W;
                int detailH = contentHeight();
                // 模糊半径按“虚拟单位 → 物理像素”的真实比例换算，避免 4K/高 GUI scale 下看起来不糊。
                float pixelScale = (float) this.minecraft.getMainRenderTarget().width
                        / (float) this.width;
                float radiusPx = ShopGaussBlur.BLUR_RADIUS_VIRTUAL * this.guiScale * pixelScale;
                ShopGaussBlur.request(
                        toFramebufferX(detailLeft), toFramebufferY(detailTop),
                        toFramebufferW(detailW), toFramebufferH(detailH),
                        panelBlur, radiusPx,
                        toFramebufferX(rx - 2), toFramebufferY(ry - 2),
                        toFramebufferW(rw + 4), toFramebufferH(rh + 4),
                        ShopGlassStyle.FLIGHT);
                // 源卡片同一条进度再糊一块。主请求会清掉旧的第二矩形，所以必须紧跟在 request 后面。
                ShopGaussBlur.requestExtra(
                        toFramebufferX(cardFlightSrcX), toFramebufferY(cardFlightSrcY),
                        toFramebufferW(cardFlightSrcW), toFramebufferH(cardFlightSrcH),
                        panelBlur, radiusPx,
                        toFramebufferX(rx - 2), toFramebufferY(ry - 2),
                        toFramebufferW(rw + 4), toFramebufferH(rh + 4),
                        ShopGlassStyle.FLIGHT);
                if (!ShopGaussBlur.isReady()) {
                    // 后处理资源加载失败时保留局部磨砂兜底；仍然只覆盖右侧面板，不会全屏刷白。
                    int frostA = (int) (0x3A * panelBlur);
                    int frostB = (int) (0x1C * panelBlur);
                    g.fill(detailLeft, detailTop, detailLeft + detailW, detailTop + detailH,
                            (frostA << 24) | 0x8E98A4);
                    g.fill(detailLeft, detailTop, detailLeft + detailW, detailTop + detailH,
                            (frostB << 24) | 0xB4BEC8);
                }
            } else {
                ShopGaussBlur.clear();
            }

            // 源卡片保留在原位，只盖一层磨砂 + 低透明度偏移重绘，模拟高斯模糊；不再把卡片抠掉留空。
            drawSourceCardBlur(g, fade);

            // 卡片表面：与详情面板同款描边盒，落地时尺寸和底色都对齐
            renderBox(g, rx, ry, rw, rh, GOLD, BOX_BG);
            // 上沿一道随落位拉长的亮线，给“流体”一点方向感（不做循环扫光）
            int sheenA = (int) (0x66 * (1f - clamp01(p)));
            if (sheenA > 0 && rw > 4) {
                g.fill(rx + 1, ry + 1, rx + rw - 1, ry + 2, (sheenA << 24) | 0xFFD98A);
            }

            // 残影：越旧的进度落后越多，轮廓沿当前卡片外扩一圈，叠在表面之上形成低频涟漪
            for (int i = 0; i < cardFlightTrails.length; i++) {
                float t = cardFlightTrails[i];
                if (t <= 0.02f || t >= p - 0.003f) continue;
                int ga = 0x30 - i * 0x0B;
                if (ga <= 0) continue;
                int pad = (i + 1) * 2 + Math.round(Math.min(10f, (p - t) * 30f));
                renderOutline(g, rx - pad, ry - pad, rw + pad * 2, rh + pad * 2,
                        (ga << 24) | 0xE8C070);
            }

            // 头部内容：图标与名称走同一条 p，起手贴在卡片原有位置，落地落在详情头部
            int iconX = Math.round(cardFlightSrcX + 5f + (cardFlightDstX + 10f - (cardFlightSrcX + 5f)) * p);
            int iconY = Math.round(cardFlightSrcY + 5f + (cardFlightDstY + 12f - (cardFlightSrcY + 5f)) * p);
            drawFlightIcon(g, cardFlightEntry, iconX, iconY);

            int nameX = Math.round(cardFlightSrcX + 26f + (cardFlightDstX + 32f - (cardFlightSrcX + 26f)) * p);
            int nameY = Math.round(cardFlightSrcY + 4f + (cardFlightDstY + 10f - (cardFlightSrcY + 4f)) * p);
            int nameW = Math.max(12, rw - 38);
            g.drawString(this.font, GuiRenderUtil.trimText(this.font, cardFlightEntry.goodsDisplayName(), nameW),
                    nameX, nameY, WHITE, true);
        } finally {
            g.setColor(1f, 1f, 1f, 1f);
            g.pose().popPose();
        }
    }

    /**
     * 虚拟逻辑坐标 → framebuffer 像素。ScaledScreen 已经按自己的 guiScale
     * 把虚拟画布缩到 Screen.width/height；再用 mainRenderTarget/Screen 的比例
     * 换算到真实像素，避免依赖窗口 GUI scale 的具体类型。
     */
    private int toFramebufferX(float virtualX) {
        int fbW = this.minecraft.getMainRenderTarget().width;
        float screenX = this.offsetX + virtualX * this.guiScale;
        return (int) Math.round((double) screenX * fbW / (double) this.width);
    }

    private int toFramebufferY(float virtualY) {
        int fbH = this.minecraft.getMainRenderTarget().height;
        float screenY = this.offsetY + virtualY * this.guiScale;
        return (int) Math.round((double) screenY * fbH / (double) this.height);
    }

    private int toFramebufferW(float virtualWidth) {
        int fbW = this.minecraft.getMainRenderTarget().width;
        return Math.max(1, (int) Math.round((double) virtualWidth * this.guiScale
                * fbW / (double) this.width));
    }

    private int toFramebufferH(float virtualHeight) {
        int fbH = this.minecraft.getMainRenderTarget().height;
        return Math.max(1, (int) Math.round((double) virtualHeight * this.guiScale
                * fbH / (double) this.height));
    }

    /**
     * 源卡片不抠掉，留在网格原位；用“磨砂底 + 多个偏移图标重绘”近似高斯模糊盖住它。
     * 不采样 framebuffer，也不影响邻居布局，只在这块卡片矩形内叠加。
     */
    private void drawSourceCardBlur(GuiGraphics g, float fade) {
        int x = cardFlightSrcX;
        int y = cardFlightSrcY;
        int w = cardFlightSrcW;
        int h = cardFlightSrcH;

        // 外圈柔光：让卡片边界先化开，避免磨砂矩形像一块硬色块
        renderOutline(g, x - 2, y - 2, w + 4, h + 4, 0x18FFFFFF);
        renderOutline(g, x - 1, y - 1, w + 2, h + 2, 0x28FFFFFF);

        // 第一层磨砂：压掉卡片原本锐利的文字和 1px 描边
        g.fill(x, y, x + w, y + h, 0x88C6D0DA);

        // 高斯核近似：把图标在几个偏移位置低透明度重绘，叠出模糊拖影
        float smear = 0.24f * fade;
        if (smear > 0.01f && cardFlightEntry != null) {
            g.setColor(1f, 1f, 1f, smear);
            int ix = x + 5;
            int iy = y + 5;
            drawFlightIcon(g, cardFlightEntry, ix - 2, iy);
            drawFlightIcon(g, cardFlightEntry, ix + 2, iy);
            drawFlightIcon(g, cardFlightEntry, ix, iy - 2);
            drawFlightIcon(g, cardFlightEntry, ix, iy + 2);
            drawFlightIcon(g, cardFlightEntry, ix - 2, iy - 2);
            drawFlightIcon(g, cardFlightEntry, ix + 2, iy + 2);
            g.setColor(1f, 1f, 1f, fade);
        }

        // 第二层更淡的磨砂：统一柔化重绘出来的锐利边缘，卡片仍能隐约看见
        g.fill(x, y, x + w, y + h, 0x50D8E2EA);
    }

    /** 飞行层头部图标：与网格卡片/详情面板保持同一套优先级（自定义组合 > 流体 > 物品）。 */
    private void drawFlightIcon(GuiGraphics g, ShopEntry entry, int x, int y) {
        List<ShopEntry.DisplayIcon> icons = entry.effectiveIcons();
        if (!icons.isEmpty()) {
            renderIconComposite(g, icons, x - 2, y - 2);
        } else if (entry.isPrimaryGoodsFluid()) {
            renderFluidIcon(g, x, y, 16, entry.primaryGoodsFluid());
        } else {
            ItemStack stack = entry.displayGoodsStack();
            g.renderItem(stack, x, y);
            g.renderItemDecorations(this.font, stack, x, y);
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** 同一条几何通道使用的进度插值：静止时四舍五入回整数坐标。 */
    private static int flightValue(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }

    private static float smoothstep(float edge0, float edge1, float v) {
        if (edge1 <= edge0) return v < edge0 ? 0f : 1f;
        float t = clamp01((v - edge0) / (edge1 - edge0));
        return t * t * (3f - 2f * t);
    }

    /** 阶段/商品一次性提交材料预览，视觉上与花费预览共用物品槽和数量标签。 */
    private int drawSubmissionRequirementPreview(GuiGraphics g, int x, int y,
                                                  List<ExchangeEntry.Ingredient> requirements,
                                                  int maxW, int mx, int my) {
        return drawSubmissionRequirementPreview(g, x, y, requirements, maxW, mx, my, null);
    }

    private int drawSubmissionRequirementPreview(GuiGraphics g, int x, int y,
                                                  List<ExchangeEntry.Ingredient> requirements,
                                                  int maxW, int mx, int my, String stagePath) {
        if (requirements == null || requirements.isEmpty()) {
            g.drawString(this.font, "§8无（保存为空等于取消限制）", x, y, GRAY, true);
            return y + 12;
        }
        int pitchX = 24;
        int pitchY = 32;
        int perRow = Math.max(1, (maxW + 2) / pitchX);
        int endY = y;
        for (int i = 0; i < requirements.size(); i++) {
            ExchangeEntry.Ingredient requirement = requirements.get(i);
            int col = i % perRow;
            int row = i / perRow;
            int sx = x + col * pitchX + 1;
            int sy = y + row * pitchY + 1;
            boolean hover = GuiRenderUtil.isHovering(mx, my, sx, sy, 20, 20);
            EditorWidgets.checkerSlot(g, sx, sy, hover);
            if (requirement.isFluid) {
                net.minecraft.world.level.material.Fluid fluid = net.minecraftforge.registries.ForgeRegistries.FLUIDS.getValue(requirement.id);
                renderFluidIcon(g, sx + 2, sy + 2, 16, fluid);
            } else {
                ItemStack stack = requirement.makeUnitStack();
                if (stack.isEmpty()) stack = ShopEntry.missingItemStack(requirement.id, 1);
                g.renderItem(stack, sx + 2, sy + 2);
            }
            java.math.BigInteger required = java.math.BigInteger.valueOf(requirement.count);
            Long ownedLong = stagePath == null || requirement.isFluid
                    ? null : ClientCostPreview.stageItemHave(stagePath, aeMode, i);
            java.math.BigInteger owned = ownedLong == null ? null : java.math.BigInteger.valueOf(ownedLong);
            String amount = formatBig(required) + (requirement.isFluid ? "mB" : "");
            String prefix = owned == null ? "§f" : (owned.compareTo(required) >= 0 ? "§a" : "§c");
            g.drawCenteredString(this.font, prefix + amount, sx + 10, sy + 22, WHITE);
            if (hover) {
                previewTipKey = widgetKey(sx, sy, 3);
                previewHoverOrder = null;
                previewHoverName = "§f" + ingredientName(requirement) + " §7×" + amount;
                if (owned == null) {
                    previewHoverExtra = "§7库存核对中… §7提交数量: §f×" + amount;
                } else if (owned.compareTo(required) >= 0) {
                    previewHoverExtra = "§a拥有: " + groupBig(owned) + " §7提交数量: §f×" + amount;
                } else {
                    previewHoverExtra = "§c拥有: " + groupBig(owned) + " §7(缺 "
                            + groupBig(required.subtract(owned)) + ") §7提交数量: §f×" + amount;
                }
            }
            endY = sy + pitchY;
        }
        return endY;
    }

    /** 阶段尚未提交时覆盖内容区：商品列表/详情不可见，只保留解锁条件、说明和提交按钮。 */
    private void renderStageLockOverlay(GuiGraphics g, int mx, int my) {
        if (!stageLockActive || stageLockPath == null) return;
        int areaX = listLeft();
        int areaY = contentTop();
        int areaW = detailX() + DETAIL_W - areaX;
        int areaH = contentHeight();
        if (stageLockShownAtMs <= 0L) stageLockShownAtMs = System.currentTimeMillis();
        // 背景里的详情列已经被锁定层盖住，它悬停时暂存的 tooltip 不该透出来；只留锁定卡片自己的预览格。
        previewHoverName = null;
        previewHoverExtra = null;
        previewHoverOrder = null;
        previewHoverStack = null;
        detailHoverStack = null;

        List<ExchangeEntry.Ingredient> requirements =
                com.dishanhai.gt_shanhai.client.shop.ClientShopUnlockState.stageRequirements(stageLockPath);
        int ow = Math.min(Math.max(250, areaW - 18), 360);
        int oh = Math.min(Math.max(150, areaH - 18), 190);
        int ox = areaX + (areaW - ow) / 2;
        int oy = areaY + (areaH - oh) / 2;
        // 阶段锁定由状态推出而非点击，沿用时间进度；换算成和浮层弹簧同一条曲线上的 p，洞与面板缩放才一致。
        float stageP = GuiRenderUtil.easeOut(
                GuiRenderUtil.popAnimProgress(stageLockShownAtMs, GuiRenderUtil.MOTION_MS));
        float strength = stageP;
        int[] hole = popHoleRect(stageP, ox, oy, ow, oh);
        requestContentGauss(strength, hole[0], hole[1], hole[2], hole[3]);
        renderStageLockBlur(g, areaX, areaY, areaW, areaH, strength);
        drawPopPanel(g, stageP, ox, oy, ow, oh, () -> drawStageLockCard(g, mx, my, ox, oy, ow, oh, requirements));
    }

    private void drawStageLockCard(GuiGraphics g, int mx, int my, int ox, int oy, int ow, int oh,
            List<ExchangeEntry.Ingredient> requirements) {
        renderBox(g, ox, oy, ow, oh, GOLD_DARK, PANEL_BG);
        g.drawCenteredString(this.font, "§c阶段未解锁", ox + ow / 2, oy + 10, RED);
        g.drawCenteredString(this.font, GuiRenderUtil.trimText(this.font,
                "§7商品内容将在解锁后显示：" + stageLockPath, ow - 18), ox + ow / 2, oy + 25, WHITE);
        g.drawString(this.font, "§6解锁条件（一次性提交）:", ox + 9, oy + 42, GOLD, true);
        int previewY = oy + 54;
        int previewBottom = drawSubmissionRequirementPreview(g, ox + 9, previewY, requirements, ow - 18, mx, my, stageLockPath);
        g.drawString(this.font, "§7解锁信息：提交后永久解锁本阶段及其商品。", ox + 9, Math.min(oy + oh - 42, previewBottom + 2), GRAY, true);
        stageLockSubmitW = ow - 18;
        stageLockSubmitH = 18;
        stageLockSubmitX = ox + 9;
        stageLockSubmitY = oy + oh - 27;
        stageLockAutoCraftVisible = aeMode && !requirements.isEmpty();
        stageLockAutoCraftW = ow - 18; stageLockAutoCraftH = 18;
        stageLockAutoCraftX = ox + 9; stageLockAutoCraftY = stageLockSubmitY - 21;
        if (stageLockAutoCraftVisible) drawButton(g, stageLockAutoCraftX, stageLockAutoCraftY,
                stageLockAutoCraftW, stageLockAutoCraftH, "§b⚙ AE下单补齐阶段前置", mx, my);
        drawButton(g, stageLockSubmitX, stageLockSubmitY, stageLockSubmitW, stageLockSubmitH,
                "§a提交阶段固定物品", mx, my);
    }

    /** 阶段锁定压暗商品区。罩色随弹入加深；真正的糊由帧末高斯负责，这里不采样画面。 */
    private void renderStageLockBlur(GuiGraphics g, int x, int y, int w, int h, float strength) {
        int alpha = 0x78 + Math.round((0xC8 - 0x78) * clamp01(strength));
        g.fill(x, y, x + w, y + h, (alpha << 24) | 0x181818);
    }

    @Override
    public void onClose() {
        clickWaveAtMs = 0L;
        ShopStageBlur.clear();
        ShopGaussBlur.clear();
        ShopGaussBlur.clearTopLayer();
        super.onClose();
    }

    @Override
    public void removed() {
        tooltipMotion.reset();
        cancelPendingGuide();
        if (ClientShopUiSettings.rememberShopView()) {
            ClientShopUiSettings.setShopView(currentSearch(), currentStableId());
        } else {
            ClientShopUiSettings.clearShopView();
        }
        ShopStageBlur.clear();
        ShopGaussBlur.clear();
        ShopGaussBlur.clearTopLayer();
        buttonFeedback.cancel(System.currentTimeMillis());
        super.removed();
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
        if (stageLockActive && hit(mx, my, listLeft(), contentTop(), detailX() + DETAIL_W - listLeft(), contentHeight())) {
            if (stageLockAutoCraftVisible && buttonHit(mx, my, stageLockAutoCraftX, stageLockAutoCraftY,
                    stageLockAutoCraftW, stageLockAutoCraftH) && stageLockPath != null) {
                ShanhaiNetwork.CHANNEL.sendToServer(new com.dishanhai.gt_shanhai.network.ShopAutoCraftRequestPacket(
                        com.dishanhai.gt_shanhai.network.ShopAutoCraftRequestPacket.Target.STAGE,
                        0L, -1L, 1L, stageLockPath, aeMode));
                return true;
            }
            if (buttonHit(mx, my, stageLockSubmitX, stageLockSubmitY, stageLockSubmitW, stageLockSubmitH)
                    && stageLockPath != null) {
                ShanhaiNetwork.CHANNEL.sendToServer(
                        new com.dishanhai.gt_shanhai.network.ShopSubmitPacket(stageLockPath, aeMode, backpackMode));
            }
            return true;
        }
        // 描述详情大图层打开时拦截全部点击：点关闭按钮或图层外任意处都关闭，图层内右侧滚动条起手拖拽，其余点击原地吞掉
        if (descOverlayOpen) {
            int[] r = descOverlayBounds();
            if (!hit(mx, my, r[0], r[1], r[2], r[3]) || hit(mx, my, descOverlayCloseX(r), descOverlayCloseY(r), DESC_OVERLAY_CLOSE_W, DESC_OVERLAY_CLOSE_H)) {
                if (hit(mx, my, descOverlayCloseX(r), descOverlayCloseY(r), DESC_OVERLAY_CLOSE_W, DESC_OVERLAY_CLOSE_H)) {
                    armClickWave(mx, my);
                }
                closeDescOverlay();
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
                if (hit(mx, my, guideOverlayCloseX(r), guideOverlayCloseY(r), DESC_OVERLAY_CLOSE_W, DESC_OVERLAY_CLOSE_H)) {
                    armClickWave(mx, my);
                }
                closeGuideOverlay();
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
                    closeGuideOverlay();
                    return true;
                }
            }
            return true;
        }
        // 快速分组大图层打开时同样拦截全部点击：点某一行直接分组并关闭图层
        if (groupPickerOpen) {
            int[] r = groupPickerBounds();
            if (!hit(mx, my, r[0], r[1], r[2], r[3]) || hit(mx, my, groupPickerCloseX(r), groupPickerCloseY(r), DESC_OVERLAY_CLOSE_W, DESC_OVERLAY_CLOSE_H)) {
                if (hit(mx, my, groupPickerCloseX(r), groupPickerCloseY(r), DESC_OVERLAY_CLOSE_W, DESC_OVERLAY_CLOSE_H)) {
                    armClickWave(mx, my);
                }
                closeGroupPicker();
                return true;
            }
            int visible = groupPickerVisibleRows(r);
            int rowY0 = r[1] + 22;
            for (int i = 0; i < visible; i++) {
                int idx = groupPickerScroll + i;
                if (idx >= groupPickerOptions.size()) break;
                int ry = rowY0 + i * GROUP_PICKER_ROW_H;
                if (hit(mx, my, r[0] + 6, ry, r[2] - 12, GROUP_PICKER_ROW_H)) {
                    if (groupPickerBatchIds.isEmpty()) {
                        sendQuickRegroup(groupPickerEntry, groupPickerEntryKey, groupPickerOptions.get(idx));
                    } else {
                        sendBatchManage(com.dishanhai.gt_shanhai.network.ShopBatchManagePacket.Action.REGROUP,
                                groupPickerOptions.get(idx));
                    }
                    closeGroupPicker();
                    groupPickerBatchIds = List.of();
                    return true;
                }
            }
            return true;
        }
        // 撤销删除按钮（悬浮在面板上，优先于其他点击判定）
        if (undoDeleteLabel != null && System.currentTimeMillis() < undoDeleteUntilMs) {
            int[] b = undoDeleteBtnBounds();
            if (buttonHit(mx, my, b[0], b[1], b[2], b[3])) {
                send(ShopActionPacket.Action.UNDO_DELETE, null, 1L);
                undoDeleteLabel = null;
                return true;
            }
        }
        // 撤销排序按钮（悬浮在面板上，优先于其他点击判定）
        if (undoReorderLabel != null && System.currentTimeMillis() < undoReorderUntilMs) {
            int[] b = undoReorderBtnBounds();
            if (buttonHit(mx, my, b[0], b[1], b[2], b[3])) {
                ShanhaiNetwork.CHANNEL.sendToServer(new com.dishanhai.gt_shanhai.network.ShopReorderPacket(
                        com.dishanhai.gt_shanhai.network.ShopReorderPacket.Action.UNDO, ClientShopCatalog.revision(), -1L));
                undoReorderLabel = null;
                return true;
            }
        }

        // 顶部买/卖页签：切换时打一条本地横幅提示当前模式，光靠页签颜色区分不够醒目，容易买卖模式点混
        if (buttonHit(mx, my, buyTabX(), top + 6, buyTabW(), TOP_BAR_H)) {
            if (mode != Mode.BUY) showMessage(Component.literal("§b[山海商店] §a已切换至【购买】模式"));
            mode = Mode.BUY; clearSelection(); return true;
        }
        if (buttonHit(mx, my, sellTabX(), top + 6, sellTabW(), TOP_BAR_H)) {
            if (mode != Mode.SELL) showMessage(Component.literal("§e[山海商店] §a已切换至【出售】模式"));
            mode = Mode.SELL; clearSelection(); return true;
        }

        // 关闭
        if (buttonHit(mx, my, closeBtnX(), top + 6, closeBtnW(), TOP_BAR_H)) {
            onClose();
            return true;
        }
        // 购物车：点开/收起大图层
        if (buttonHit(mx, my, cartBtnX(), top + 6, cartBtnW(), TOP_BAR_H)) {
            cartOverlayOpen = !cartOverlayOpen;
            if (cartOverlayOpen) openOverlay(cartMotion);
            else closeOverlay(cartMotion);
            // 结算结果不因收起面板清掉，见 handleCartOverlayClick 关闭分支同款说明
            cartOverlayScroll = 0;
            return true;
        }
        // 只看收藏筛选：跟主/子分类、搜索一起收窄网格，切换后立即重算可见列表
        if (buttonHit(mx, my, favBtnX(), top + 6, favBtnW(), TOP_BAR_H)) {
            favoritesOnly = !favoritesOnly;
            snapGridScroll(0);
            recomputeVisible();
            requestStagger(0f);
            showMessage(Component.literal(favoritesOnly
                    ? "§b[山海商店] §a已切换至【只看收藏】"
                    : "§b[山海商店] §7已取消只看收藏筛选"));
            return true;
        }
        // AE 模式切换：光靠按钮文字变色不够醒目，切换时打一条横幅明确告知当前状态
        if (buttonHit(mx, my, aeBtnX(), top + 6, aeBtnW(), TOP_BAR_H)) {
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
        if (buttonHit(mx, my, backpackBtnX(), top + 6, backpackBtnW(), TOP_BAR_H)) {
            backpackMode = !backpackMode;
            showMessage(Component.literal(backpackMode
                    ? "§b[山海商店] §a精妙背包模式已开启，交易优先从精妙背包扣款/交付"
                    : "§b[山海商店] §7精妙背包模式已关闭，交易优先走随身背包"));
            return true;
        }
        // 充值全部
        if (buttonHit(mx, my, rechargeBtnX(), top + 6, rechargeBtnW(), TOP_BAR_H)) {
            send(ShopActionPacket.Action.DEPOSIT, null, 1L);
            return true;
        }
        // 货币中心（打开 ATM 子页）
        if (buttonHit(mx, my, currencyBtnX(), top + 6, currencyBtnW(), TOP_BAR_H)) {
            Minecraft.getInstance().setScreen(new CurrencyAtmScreen(this));
            return true;
        }
        // 兑换中心（打开兑换子页）
        if (buttonHit(mx, my, exchangeBtnX(), top + 6, exchangeBtnW(), TOP_BAR_H)) {
            Minecraft.getInstance().setScreen(new ExchangeScreen(this, canEdit));
            return true;
        }
        // 银会中心（打开会员购买+银行子页）
        if (buttonHit(mx, my, memberBtnX(), top + 6, memberBtnW(), TOP_BAR_H)) {
            Minecraft.getInstance().setScreen(new ShopMembershipScreen(this));
            return true;
        }
        // 新增商品（编辑权 + 开了编辑模式）：分类默认继承当前所在子页（如「无限盘区/前期」）
        if (catalogEditUnlocked && buttonHit(mx, my, addBtnX(), top + 6, addBtnW(), TOP_BAR_H)) {
            ShopEntryEditor.openNew(this, currentViewCategory());
            return true;
        }
        // 商店设置（所有人可开，不受编辑模式限制）：编辑权玩家另有奖励抽取次数上限等服务端运行期设置
        if (buttonHit(mx, my, settingsBtnX(), top + 6, settingsBtnW(), TOP_BAR_H)) {
            Minecraft.getInstance().setScreen(new ShopSettingsScreen(this, canEdit));
            return true;
        }

        // 分类页签（主/二级/三级/四级）：命中即消费，具体是切页还是进入拖拽追踪由 beginTabAction 判定
        // （见 rowXxx/tabRowClicked 统一实现，取代原来四份重复的页签点击处理）
        for (int level = 0; level < 4; level++) {
            if (tabRowClicked(mx, my, level, btn)) return true;
        }

        // 详情页中段滚动条（交易次数/预计/花费预览/描述/跳转），优先于详情内链接命中。
        if (detailScrollbarClicked(mx, my)) return true;

        // 网格右侧滚动条（拖拽跳转，先于格子命中判定）
        if (gridScrollbarClicked(mx, my)) return true;

        // 网格格子：坐标直接反算索引，不再线性扫描当前分类全部商品。左键=选中打开详情页，右键=快捷菜单。
        int entryIndex = entryIndexAt(mx, my);
        if (entryIndex >= 0) {
            ShopEntry entry = visibleEntry(entryIndex);
            if (entry != null) {
                armClickWave(mx, my);
                if (btn == 1) {
                    openContextMenu(entry, visibleEntryKeys.get(entryIndex), (int) mx, (int) my);
                } else if (btn == 0 && net.minecraft.client.gui.screens.Screen.hasAltDown()) {
                    // Alt + 左键只切换批量名单，不触发详情、购物车或商品拖拽。
                    toggleBatchSelection(entry);
                } else if (btn == 0 && net.minecraft.client.gui.screens.Screen.hasControlDown()) {
                    // Ctrl+左键：加入购物车候选（不影响详情页选中），已在购物车里则忽略，去购物车面板调数量
                    boolean already = ClientShopCart.contains(entry.getStableId());
                    ClientShopCart.add(entry.getStableId(), 1L);
                    showMessage(Component.literal(already
                            ? "§b[山海商店] §7已在购物车里: §f" + entry.goodsDisplayName()
                            : "§b[山海商店] §a已加入购物车: §f" + entry.goodsDisplayName()));
                } else if (btn == 0 && canEdit
                        && (searchBox == null || searchBox.getValue().isBlank())) {
                    // 开始潜在拖拽追踪；是否真正进入拖拽由 universalMouseDragged 阈值判定（见 dragCardEntryKey 注释）。
                    // 非叶子（聚合"全部"）视图下也允许起手：落到页签上走跨分类分组不需要叶子视图，
                    // 只有落回网格格子的「同分类内重排」才在下面按 isLeafCategoryView() 收窄。
                    dragCardEntryKey = visibleEntryKeys.get(entryIndex);
                    dragCardEntry = entry;
                    dragCardOldIndex = entryIndex;
                    dragCardStartX = mx; dragCardStartY = my;
                    dragCardMx = mx; dragCardMy = my;
                    dragCardMoving = false;
                } else {
                    long entryKey = visibleEntryKeys.get(entryIndex);
                    int flightCol = entryIndex % gridColumns();
                    int flightRow = entryIndex / gridColumns();
                    beginCardFlight(entry, cellX(flightCol), cellY(flightRow));
                    selectEntry(entryKey, entry);
                }
            }
            return true;
        }

        // 跳转入口（drawDetail 渲染时暂存的目标条目 + 命中框，点击直接切换详情页选中项）
        boolean inDetailViewport = hit(mx, my, detailX(), detailViewportTop(), DETAIL_W, detailViewportHeight());
        if (inDetailViewport && linkVisible && linkTarget != null && hit(mx, my, linkX, linkY, linkW, linkH)) {
            selectEntry(linkTargetKey, linkTarget);
            return true;
        }
        // GuideME 指南跳转（单条命中直接开指南；多条命中开「指南详情」大图层）
        if (inDetailViewport && guideLinkVisible && guideLinkHit != null && buttonHit(mx, my, guideLinkX, guideLinkY, guideLinkW, guideLinkH)) {
            openDetailGuide();
            return true;
        }
        if (inDetailViewport && guideDetailBtnVisible && buttonHit(mx, my, guideDetailBtnX, guideDetailBtnY, guideDetailBtnW, guideDetailBtnH)) {
            guideOverlayOpen = true;
            openOverlay(guideMotion);
            guideOverlayScroll = 0;
            return true;
        }
        // 「补齐全部缺口」：向服务端起一轮 AE 自动合成计算，算完服务端会推确认框
        if (inDetailViewport && autoCraftBtnVisible && selected != null && buttonHit(mx, my, autoCraftBtnX, autoCraftBtnY, autoCraftBtnW, autoCraftBtnH)) {
            requestCostAutoCraft();
            return true;
        }
        if (inDetailViewport && favoriteMissingBtnVisible && selected != null
                && buttonHit(mx, my, favoriteMissingBtnX, favoriteMissingBtnY, favoriteMissingBtnW, favoriteMissingBtnH)) {
            bookmarkMissingCosts();
            return true;
        }
        if (inDetailViewport && buyMaterialsBtnVisible && selected != null
                && buttonHit(mx, my, buyMaterialsBtnX, buyMaterialsBtnY, buyMaterialsBtnW, buyMaterialsBtnH)) {
            send(ShopActionPacket.Action.BUY_MATERIALS, selected, amount);
            return true;
        }
        // 前置任务跳转：打开 FTBQ 任务书并定位到该任务（未解析到时点了也没用，命中框本身照样给，方便玩家复制ID反馈）
        if (inDetailViewport && prereqLinkVisible && selected != null && hit(mx, my, prereqLinkX, prereqLinkY, prereqLinkW, prereqLinkH)) {
            com.dishanhai.gt_shanhai.client.shop.ShopFtbqPrereqLookup.open(selected.getPrerequisiteQuestId());
            return true;
        }
        if (inDetailViewport && submissionBtnVisible && selected != null
                && buttonHit(mx, my, submissionBtnX, submissionBtnY, submissionBtnW, submissionBtnH)) {
            ShanhaiNetwork.CHANNEL.sendToServer(new com.dishanhai.gt_shanhai.network.ShopSubmitPacket(
                    ClientShopCatalog.revision(), ClientShopCatalog.keyOf(selected), aeMode, backpackMode));
            return true;
        }
        if (inDetailViewport && stageSubmissionAutoCraftVisible && selected != null
                && buttonHit(mx, my, stageSubmissionAutoCraftX, stageSubmissionAutoCraftY,
                    stageSubmissionAutoCraftW, stageSubmissionAutoCraftH)) {
            String stage = missingStageSubmission(selected.getCategory());
            if (stage != null) ShanhaiNetwork.CHANNEL.sendToServer(
                    new com.dishanhai.gt_shanhai.network.ShopAutoCraftRequestPacket(
                            com.dishanhai.gt_shanhai.network.ShopAutoCraftRequestPacket.Target.STAGE,
                            0L, -1L, 1L, stage, aeMode));
            return true;
        }
        if (inDetailViewport && stageSubmissionBtnVisible && selected != null
                && buttonHit(mx, my, stageSubmissionBtnX, stageSubmissionBtnY, stageSubmissionBtnW, stageSubmissionBtnH)) {
            String stage = missingStageSubmission(selected.getCategory());
            if (stage != null) ShanhaiNetwork.CHANNEL.sendToServer(
                    new com.dishanhai.gt_shanhai.network.ShopSubmitPacket(stage, aeMode, backpackMode));
            return true;
        }
        // 展开描述详情（drawDetail 渲染时暂存的按钮坐标，命中即开大图层）
        if (inDetailViewport && descExpandVisible && buttonHit(mx, my, descExpandX, descExpandY, descExpandW, descExpandH)) {
            descOverlayOpen = true;
            openOverlay(descMotion);
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
            // 确认购买/出售（与 Enter 共用 performConfirmTrade）
            if (buttonHit(mx, my, cx, btnY, btnW, 20)) {
                performConfirmTrade(false);
                return true;
            }
            // 删除（编辑权 + 开了编辑模式，二次确认防误触：3 秒内对同一条目再点一次「删除此商品」才真正执行）
            if (catalogEditUnlocked && buttonHit(mx, my, cx, btnY - 22, btnW, 18)) {
                boolean armed = pendingDeleteEntry == selected
                        && System.currentTimeMillis() - pendingDeleteArmedAtMs < DELETE_CONFIRM_WINDOW_MS;
                if (!armed) {
                    pendingDeleteEntry = selected;
                    pendingDeleteArmedAtMs = System.currentTimeMillis();
                    ShopUiSounds.warn();
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
            if (catalogEditUnlocked && buttonHit(mx, my, cx, btnY - 44, btnW, 18)) {
                ShopEntryEditor.openEdit(this, selected);
                return true;
            }
            // AE 风格步进按钮（两排 +1/+10/+100/+1000 与 -1/-10/-100/-1000，第三排 ×10/×100/÷10/÷100）
            // ± 两排左键按住连发；×÷ 一排只单击，连发几下就会冲到 Long.MAX
            long[] steps = {1, 10, 100, 1000};
            long[] scales = {10, 100, 10, 100};
            int bw = (btnW - 9) / 4;
            for (int i = 0; i < 4; i++) {
                int bx = cx + i * (bw + 3);
                if (buttonHit(mx, my, bx, dy + 82, bw, 12)) { beginStepHold(btn, pressKey(bx, dy + 82, bw, 12), steps[i]); return true; }
                if (buttonHit(mx, my, bx, dy + 96, bw, 12)) { beginStepHold(btn, pressKey(bx, dy + 96, bw, 12), -steps[i]); return true; }
                if (buttonHit(mx, my, bx, dy + 110, bw, 12)) {
                    long next = i < 2 ? mulClamp(amount, scales[i]) : divClamp(amount, scales[i]);
                    if (next == amount) {
                        ShopUiSounds.limit();
                        buttonFeedback.nudge(pressKey(bx, dy + 110, bw, 12), System.currentTimeMillis());
                    }
                    amount = next;
                    syncAmountBox();
                    return true;
                }
            }
        }

        // 空白区 Alt + 右键：不命中商品卡片或其他控件时，清空本次批量操作名单。
        if (net.minecraft.client.gui.screens.Screen.hasAltDown() && btn == 1) {
            boolean hadSelection = !batchSelectionStableIds.isEmpty();
            clearBatchSelection();
            if (hadSelection) {
                showMessage(Component.literal("§b[山海商店] §7批量名单已清空"));
            }
            return true;
        }
        if (inDetailViewport && submissionAutoCraftVisible && selected != null
                && buttonHit(mx, my, submissionAutoCraftX, submissionAutoCraftY, submissionAutoCraftW, submissionAutoCraftH)) {
            ShanhaiNetwork.CHANNEL.sendToServer(new com.dishanhai.gt_shanhai.network.ShopAutoCraftRequestPacket(
                    com.dishanhai.gt_shanhai.network.ShopAutoCraftRequestPacket.Target.ENTRY_SUBMISSION,
                    ClientShopCatalog.revision(), ClientShopCatalog.keyOf(selected), 1L, "", aeMode));
            return true;
        }

        return super.universalMouseClicked(mx, my, btn);
    }

    /** 数量加减夹取到 [1, Long.MAX]，防加法溢出。 */
    /**
     * 详情列确认与 ± 步进按钮的悬停说明，按 180px 换行。确认按钮正在等待或闪结果时不弹（别盖住结果标签），
     * 步进按住连发时也不弹。没悬停在这些按钮上返回 null。
     */
    private List<net.minecraft.util.FormattedCharSequence> detailButtonTip(int smx, int smy) {
        if (selected == null || modalLayerOpen()) return null;
        int cx = detailX() + 8;
        int dy = contentTop();
        int btnW = DETAIL_W - 16;
        String text = null;
        boolean inViewport = hit(smx, smy, detailX(), detailViewportTop(), DETAIL_W, detailViewportHeight());
        if (inViewport && guideLinkVisible && hit(smx, smy, guideLinkX, guideLinkY, guideLinkW, guideLinkH)) {
            text = "§7打开此物品对应的指南页面";
            detailTipKey = detailActionKey(DETAIL_GUIDE);
        } else if (inViewport && autoCraftBtnVisible && hit(smx, smy, autoCraftBtnX, autoCraftBtnY, autoCraftBtnW, autoCraftBtnH)) {
            text = "§7计算尚未下单的缺口；预览用料后确认提交到 AE 网络";
            detailTipKey = detailActionKey(DETAIL_AUTO_CRAFT);
        } else if (inViewport && favoriteMissingBtnVisible && hit(smx, smy, favoriteMissingBtnX, favoriteMissingBtnY, favoriteMissingBtnW, favoriteMissingBtnH)) {
            text = "§7把当前确定缺少的物品和流体加入 JEI 收藏，已有收藏不重复添加";
            detailTipKey = detailActionKey(DETAIL_BOOKMARK);
        } else if (hit(smx, smy, cx, dy + contentHeight() - 24, btnW, 20)) {
            detailTipKey = confirmActionKey();
            if (buttonFeedback.phase(confirmActionKey(), System.currentTimeMillis()) == ButtonFeedback.Phase.IDLE) {
                text = "§7左键或 Enter 确认；处理中再点不会重复下单";
            }
        } else if (!buttonFeedback.holding()) {
            int bw = (btnW - 9) / 4;
            for (int i = 0; i < 4 && text == null; i++) {
                int bx = cx + i * (bw + 3);
                boolean plus = hit(smx, smy, bx, dy + 82, bw, 12);
                if (plus || hit(smx, smy, bx, dy + 96, bw, 12)) {
                    detailTipKey = pressKey(bx, dy + (plus ? 82 : 96), bw, 12);
                    text = "§7按住连续调整，越按越快";
                }
            }
        }
        if (text == null) return null;
        return this.font.split(Component.literal(text), 180);
    }

    /** 详情列确认购买/出售按钮的压下 key（与 drawDetail、universalMouseClicked 同一块几何）。 */
    private long confirmPressKey() {
        return pressKey(detailX() + 8, contentTop() + contentHeight() - 24, DETAIL_W - 16, 20);
    }

    /** 确认按钮的动作 key：混入当前商品与买卖模式，换商品、换模式后旧的等待与闪色不挡住也不闪到新商品上。 */
    private long confirmActionKey() {
        return confirmPressKey() ^ (selectedEntryKey * 0x9E3779B97F4A7C15L) ^ ((long) mode.ordinal() << 61);
    }

    /** 山海：結果按動作、商品與數量識別，與詳情捲動位置無關。 */
    private long detailActionKey(int action) {
        return 0x61C8864680B583EBL ^ selectedEntryKey * 0x9E3779B97F4A7C15L
                ^ ClientShopCatalog.revision() * 0x165667B19E3779F9L
                ^ amount * 0xC2B2AE3D27D4EB4FL ^ ((long) action << 54) ^ ((long) mode.ordinal() << 61);
    }

    private String detailActionLabel(int action, String idle) {
        long now = System.currentTimeMillis();
        long key = detailActionKey(action);
        if (buttonFeedback.phase(key, now) == ButtonFeedback.Phase.PENDING) {
            return action == DETAIL_GUIDE ? "§b正在打开指南…" : "§b正在计算合成方案…";
        }
        if (buttonFeedback.phase(key, now) != ButtonFeedback.Phase.FLASH) return idle;
        return switch (buttonFeedback.flashTone(key, now)) {
            case SUCCESS -> switch (action) {
                case DETAIL_GUIDE -> "§a✓ 已打开指南";
                case DETAIL_BOOKMARK -> "§a✓ 缺失项已收藏";
                default -> "§a✓ 方案已就绪";
            };
            case PARTIAL -> action == DETAIL_BOOKMARK ? "§6~ 没有新增收藏" : "§6~ 部分可合成";
            case FAILED -> switch (action) {
                case DETAIL_GUIDE -> "§c✗ 指南打开失败";
                case DETAIL_BOOKMARK -> "§c✗ 收藏失败";
                default -> "§c✗ 无可用合成方案";
            };
            default -> idle;
        };
    }

    private void flashDetailAction(long key, ButtonFeedback.Tone tone) {
        buttonFeedback.flash(key, tone, System.currentTimeMillis());
        switch (tone) {
            case SUCCESS -> ShopUiSounds.success(false);
            case PARTIAL -> ShopUiSounds.partial();
            case FAILED -> ShopUiSounds.fail();
            default -> { }
        }
    }

    private void openDetailGuide() {
        long now = System.currentTimeMillis();
        long key = detailActionKey(DETAIL_GUIDE);
        if (!buttonFeedback.tryBegin(key, now)) {
            ShopUiSounds.busy();
            return;
        }
        if (Minecraft.getInstance().player == null) {
            flashDetailAction(key, ButtonFeedback.Tone.FAILED);
            return;
        }
        pendingGuideHit = guideLinkHit;
        pendingGuideKey = key;
        pendingGuideAtMs = now + (ClientShopUiSettings.reduceMotion() ? 0L : 120L);
        buttonFeedback.pending(key, ClientShopActionResults.nextRequestId(), now);
    }

    private void cancelPendingGuide() {
        if (pendingGuideHit == null) return;
        pendingGuideHit = null;
        buttonFeedback.flash(pendingGuideKey, ButtonFeedback.Tone.NEUTRAL, System.currentTimeMillis());
    }

    private void requestCostAutoCraft() {
        long now = System.currentTimeMillis();
        long key = detailActionKey(DETAIL_AUTO_CRAFT);
        if (!buttonFeedback.tryBegin(key, now)) {
            buttonFeedback.nudge(pressKey(autoCraftBtnX, autoCraftBtnY, autoCraftBtnW, autoCraftBtnH), now);
            ShopUiSounds.busy();
            return;
        }
        int requestId = ClientShopActionResults.nextRequestId();
        // 多目標逐項計算由服務端逐項逾時回執結束，不能以短的整輪牆鐘期限截斷合法方案。
        buttonFeedback.pending(key, requestId, now, Long.MAX_VALUE);
        ShanhaiNetwork.CHANNEL.sendToServer(new com.dishanhai.gt_shanhai.network.ShopAutoCraftRequestPacket(
                ClientShopCatalog.revision(), ClientShopCatalog.keyOf(selected), amount, aeMode, requestId));
    }

    /** 即時錯誤立即消化；帶確認窗的結果保留到返回商店才開始閃色。 */
    public void drainAutoCraftResults() {
        ClientShopActionResults.drainAutoCraft(System.currentTimeMillis(), result -> {
            ButtonFeedback.Tone tone = result.planned() <= 0 ? ButtonFeedback.Tone.FAILED
                    : result.planned() < result.total() ? ButtonFeedback.Tone.PARTIAL : ButtonFeedback.Tone.SUCCESS;
            if (buttonFeedback.resolve(result.requestId(), tone, System.currentTimeMillis())) {
                switch (tone) {
                    case SUCCESS -> ShopUiSounds.success(false);
                    case PARTIAL -> ShopUiSounds.partial();
                    default -> ShopUiSounds.fail();
                }
            }
        });
    }

    /**
     * 确认购买/出售，点击与 Enter 共用。
     *
     * <p>四个确定性的本地预检过不去就地红闪；自选奖励（CHOICE，本地池或 FTBQ 表自选子模式）先弹选择界面，
     * 选完再发购买包；其余去抖后带请求号发包，按钮进入等待，回执到了再闪结果。
     * 红框（可交易性预判）不拦：花费预览可能还在加载、AE 库存与前置状态客户端看不全，以服务端为准。</p>
     */
    private void performConfirmTrade(boolean viaKeyboard) {
        performConfirmTrade(viaKeyboard, false);
    }

    /**
     * {@code singleUseWarned=true}：SDA/无限盘「买太多」警告已被玩家确认过，不再弹。警告在本地预检之后、
     * 自选奖励界面与发包之前：数量 ≥ {@link ShopEntry#SINGLE_USE_WARN_AMOUNT} 且商品含 SDA/无限盘才弹，
     * 玩家点「仍然购买」后回到这里重走一遍（商品或数量在弹窗期间变了就不继续，以免买到别的东西）。
     */
    private void performConfirmTrade(boolean viaKeyboard, boolean singleUseWarned) {
        if (selected == null) return;
        long now = System.currentTimeMillis();
        long key = confirmActionKey();
        if (viaKeyboard) {
            // 键盘没有鼠标按住，压一下即可：下一帧鼠标兜底会松开，正好是按一下再弹回
            int x = detailX() + 8;
            int y = contentTop() + contentHeight() - 24;
            buttonFeedback.press(confirmPressKey(), x, y, DETAIL_W - 16, 20, true, now);
            ShopUiSounds.click();
        }
        String precheck = null;
        if (mode == Mode.BUY && selected.hasMissingItems()) {
            precheck = "§c[山海商店] 该商品引用的物品缺失（对应模组可能未安装），无法购买";
        } else if (mode == Mode.BUY && !selected.allowsBuy()) {
            precheck = "§c[山海商店] 该商品仅允许出售，不能购买";
        } else if (mode == Mode.SELL && selected.getCost().isEmpty()) {
            precheck = "§c[山海商店] 免费商品不能出售";
        } else if (mode == Mode.SELL && !selected.allowsSell()) {
            precheck = "§c[山海商店] 该商品仅允许购买，不能出售";
        }
        if (precheck != null) {
            showMessage(Component.literal(precheck));
            buttonFeedback.flash(key, ButtonFeedback.Tone.FAILED, now);
            ShopUiSounds.fail();
            return;
        }
        if (mode == Mode.BUY && !singleUseWarned && selected.hasSingleUseStorage()
                && ShopEntry.exceedsSingleUseAmount(amount)) {
            ShopEntry warnEntry = selected;
            long warnAmount = amount;
            ShopUiSounds.warn();
            ShopSingleUseWarnScreen.open(this, List.of(
                    "§f" + warnEntry.goodsDisplayName() + " §7× §e" + warnAmount + " §7份",
                    "§eSDA 和无限盘一般只需要购买一次，买 " + warnAmount + " 份多半是多余的。",
                    "§7确定要买这么多吗？"), () -> {
                        if (selected == warnEntry && amount == warnAmount) performConfirmTrade(false, true);
                    });
            return;
        }
        boolean localChoice = selected.getRewardMode() == ShopEntry.RewardMode.CHOICE;
        boolean ftbqChoice = selected.getRewardMode() == ShopEntry.RewardMode.FTBQ
                && selected.getFtbqSubMode() == ShopEntry.RewardMode.CHOICE;
        if (mode == Mode.BUY && localChoice) {
            Minecraft.getInstance().setScreen(new RewardChoiceScreen(this, selected, amount, aeMode, backpackMode));
            return;
        }
        if (mode == Mode.BUY && ftbqChoice) {
            Minecraft.getInstance().setScreen(new FtbqRewardChoiceScreen(this, selected, amount, aeMode, backpackMode));
            return;
        }
        if (!buttonFeedback.tryBegin(key, now)) {
            buttonFeedback.nudge(confirmPressKey(), now);
            ShopUiSounds.busy();
            return;
        }
        int requestId = ClientShopActionResults.nextRequestId();
        send(mode == Mode.BUY ? ShopActionPacket.Action.BUY : ShopActionPacket.Action.SELL, selected, amount, requestId);
        buttonFeedback.pending(key, requestId, now);
    }

    /**
     * 自选奖励界面确认购买：带请求号发包，确认按钮进入等待，回到商店后照常闪成交结果。
     * 返回 false 表示上一笔还在等回执（或 300ms 内重复），选择界面留着并提示忙。
     */
    boolean submitRewardChoice(long catalogRevision, long entryKey, long times,
                               boolean ae, boolean bp, int chosenRewardIndex) {
        long now = System.currentTimeMillis();
        long key = confirmActionKey();
        if (!buttonFeedback.tryBegin(key, now)) return false;
        int requestId = ClientShopActionResults.nextRequestId();
        ShanhaiNetwork.CHANNEL.sendToServer(new ShopActionPacket(ShopActionPacket.Action.BUY,
                catalogRevision, entryKey, times, ae, bp, chosenRewardIndex, false, requestId));
        buttonFeedback.pending(key, requestId, now);
        return true;
    }

    /** 回执对上还在等的请求才闪色出声；对不上（已超时、界面已重建）就丢掉，横幅照常由聊天镜像显示。 */
    private void onActionResult(ClientShopActionResults.Result result) {
        ButtonFeedback.Tone tone = switch (result.outcome()) {
            case SUCCESS -> ButtonFeedback.Tone.SUCCESS;
            case PARTIAL -> ButtonFeedback.Tone.PARTIAL;
            case FAILED -> ButtonFeedback.Tone.FAILED;
        };
        if (!buttonFeedback.resolve(result.requestId(), tone, System.currentTimeMillis())) return;
        switch (tone) {
            case SUCCESS -> ShopUiSounds.success(result.action() == ShopActionPacket.Action.SELL);
            case PARTIAL -> ShopUiSounds.partial();
            default -> ShopUiSounds.fail();
        }
    }

    /** 确认按钮标签随反馈阶段换：等待中、成交结果各一句短固定文字，按钮宽度内放得下。 */
    private String confirmLabel(long actionKey, long nowMs) {
        ButtonFeedback.Phase phase = buttonFeedback.phase(actionKey, nowMs);
        if (phase == ButtonFeedback.Phase.PENDING) return "§7处理中…";
        if (phase == ButtonFeedback.Phase.FLASH) {
            switch (buttonFeedback.flashTone(actionKey, nowMs)) {
                case SUCCESS -> { return mode == Mode.BUY ? "§a✓ 购买成功" : "§e✓ 出售成功"; }
                case PARTIAL -> { return "§6~ 部分成交"; }
                case FAILED -> { return "§c✗ 未成交"; }
                default -> { }
            }
        }
        return mode == Mode.BUY ? "§a确认购买" : "§e确认出售";
    }

    /** 详情数量步进一次；已经到 1 或 Long.MAX、数量没变时返回 false。 */
    private boolean stepAmount(long delta) {
        long next = addClamp(amount, delta);
        if (next == amount) return false;
        amount = next;
        syncAmountBox();
        return true;
    }

    /**
     * 按下 ± 步进按钮：先步进一次；左键按住再挂连发（{@link RepeatCurve#STEPPER}，越按越快，滴答声音高渐升）。
     * 到了边界只低响一声、轻抖一下，连发随即停下。
     */
    private void beginStepHold(int btn, long stepPressKey, long delta) {
        long now = System.currentTimeMillis();
        if (!stepAmount(delta)) {
            ShopUiSounds.limit();
            buttonFeedback.nudge(stepPressKey, now);
            return;
        }
        if (btn != 0) return;
        buttonFeedback.holdRepeat(RepeatCurve.STEPPER, i -> {
            if (stepAmount(delta)) {
                ShopUiSounds.step(i);
                return true;
            }
            ShopUiSounds.limit();
            buttonFeedback.nudge(stepPressKey, System.currentTimeMillis());
            return false;
        }, now);
    }

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
        if (stageLockActive) return true;
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
        if (groupPickerOpen) {
            int[] r = groupPickerBounds();
            int maxScroll = groupPickerMaxScroll(r);
            groupPickerScroll = Math.max(0, Math.min(maxScroll, groupPickerScroll - (int) d));
            return true;
        }
        if (cartOverlayOpen) {
            int[] r = cartOverlayBounds();
            int maxScroll = cartOverlayMaxScroll(r, cartDisplayIds().size()); // 跟渲染/点击同一份清单口径
            cartOverlayScroll = Math.max(0, Math.min(maxScroll, cartOverlayScroll - (int) d));
            return true;
        }
        if (selected != null && GuiRenderUtil.isHovering(mx, my, detailX(), detailViewportTop(), DETAIL_W, detailViewportHeight())) {
            detailScrollTarget = clampDetailScroll(detailScrollTarget - (int) d * 18);
            return true;
        }
        int gx = listLeft(), gy = contentTop(), gh = contentHeight();
        if (GuiRenderUtil.isHovering(mx, my, gx, gy, listWidth(), gh)) {
            int maxScroll = maxGridScroll();
            scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget - (int) d * rowStride()));
            return true;
        }
        return super.universalMouseScrolled(mx, my, d);
    }

    @Override
    protected boolean universalMouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (dragTabLevel != -1) {
            dragTabMx = mx;
            dragTabMy = my;
            if (!dragTabMoving && Math.hypot(mx - dragTabStartX, my - dragTabStartY) >= DRAG_TAB_THRESHOLD) {
                dragTabMoving = true;
            }
            return true;
        }
        if (dragCardEntryKey != -1L) {
            dragCardMx = mx;
            dragCardMy = my;
            if (!dragCardMoving && Math.hypot(mx - dragCardStartX, my - dragCardStartY) >= DRAG_CARD_THRESHOLD) {
                dragCardMoving = true;
            }
            return true;
        }
        if (draggingDescOverlayScroll) { updateDescOverlayScrollFromDrag(my); return true; }
        if (draggingDetailScroll) { updateDetailScrollFromDrag(my); return true; }
        if (draggingGridScroll) { updateGridScrollFromDrag(my); return true; }
        return super.universalMouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    protected boolean universalMouseReleased(double mx, double my, int btn) {
        // 先结束按下态与连发；不 return，下面的拖拽、滚动条照常收尾
        buttonFeedback.release(System.currentTimeMillis());
        if (dragTabLevel != -1) {
            int level = dragTabLevel;
            String category = dragTabCategory;
            boolean moved = dragTabMoving;
            dragTabLevel = -1;
            dragTabCategory = null;
            dragTabMoving = false;
            if (moved) sendTabReorder(level, category, computeTabDropIndex(level, mx));
            else performTabSwitch(level, category); // 没越过拖拽阈值 = 普通点击，按原逻辑切页
            return true;
        }
        if (dragCardEntryKey != -1L) {
            long entryKey = dragCardEntryKey;
            ShopEntry entry = dragCardEntry;
            int oldIndex = dragCardOldIndex;
            boolean moved = dragCardMoving;
            dragCardEntryKey = -1L;
            dragCardEntry = null;
            dragCardOldIndex = -1;
            dragCardMoving = false;
            if (entry == null) {
                // 拖拽期间目录可能刷新导致条目失效，静默丢弃这次操作
            } else if (moved) {
                // 落在某个分类页签上 = 跨分类快速分组；落在网格格子上 = 同分类内重排；两者互斥，见 cardDropCategoryPath
                String dropCategory = cardDropCategoryPath(mx, my);
                if (dropCategory != null) {
                    if (!catalogEditUnlocked) {
                        showMessage(Component.literal("§c[山海商店] 拖到页签快速分组需要先开启编辑模式（/山海 商店 编辑）"));
                    } else {
                        sendQuickRegroup(entry, entryKey, dropCategory);
                    }
                } else if (isLeafCategoryView()) {
                    // 同分类内重排要求叶子视图：聚合「全部」视图里 visibleEntryKeys 混着多个真实分类，
                    // 网格下标不对应任何单一分类下的本地序号，落回网格格子在聚合视图下不触发重排。
                    int targetIndex = entryIndexAt(mx, my);
                    if (targetIndex >= 0) {
                        // 落点下标是"未去除自身"的 visibleEntryKeys 下标，落在原位置之后要 -1 才是
                        // ShopConfig#moveEntryToIndex 期望的"去掉自身之后"的本地下标语义
                        int localIndex = targetIndex > oldIndex ? targetIndex - 1 : targetIndex;
                        sendCardReorder(entry, entryKey, localIndex);
                    }
                }
            } else {
                // 没越过拖拽阈值 = 普通点击，按原逻辑打开详情；编辑权限下也走同一段飞行动画
                int flightCol = oldIndex % gridColumns();
                int flightRow = oldIndex / gridColumns();
                beginCardFlight(entry, cellX(flightCol), cellY(flightRow));
                selectEntry(entryKey, entry);
            }
            return true;
        }
        if (draggingDescOverlayScroll) { draggingDescOverlayScroll = false; return true; }
        if (draggingDetailScroll) { draggingDetailScroll = false; return true; }
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
        send(action, entry, times, 0);
    }

    /** {@code requestId!=0}：服务端处理完回 ShopActionResultPacket，供详情页确认按钮显示等待与成交结果。 */
    private void send(ShopActionPacket.Action action, ShopEntry entry, long times, int requestId) {
        long entryKey = entry != null ? ClientShopCatalog.keyOf(entry) : -1L;
        // aeMode 仅购买用（AE 模式优先注入网络，出售货款走虚拟钱包账户不涉及实物投放）；
        // backpackMode 购买/出售都要带（出售时决定优先扣随身背包还是精妙背包，见 ShopPurchase#sellBulk）
        boolean ae = (action == ShopActionPacket.Action.BUY || action == ShopActionPacket.Action.BUY_MATERIALS) && aeMode;
        boolean bp = backpackMode;
        ShanhaiNetwork.CHANNEL.sendToServer(new ShopActionPacket(
                action, ClientShopCatalog.revision(), entryKey, times, ae, bp, -1, false, requestId));
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
        List<ContextMenuItem> items = buildContextMenuItems(entry, entryKey, isBatchSelected(entry));
        if (items.isEmpty()) return;
        ctxMenuItems = items;
        ctxMenuEntryKey = entryKey;
        ctxMenuX = x;
        ctxMenuY = y;
        ctxMenuW = 130;
        ctxMenuOpen = true;
        openOverlay(ctxMotion);
    }

    private void closeContextMenu() {
        ctxMenuOpen = false;
        closeOverlay(ctxMotion);
        // 菜单项留给退场动画画，弹簧归零后由 updateOverlayMotion 清掉（连同 ctxMenuEntryKey）
    }

    /**
     * Esc 分层关闭：跟「点图层外关闭」同语义，先关最上层的右键菜单/大图层，再退出搜索框/数量框，全都没有才关整个商店。
     * Enter（含小键盘）：选中商品时等同点「确认购买/出售」；在搜索框里、或浮层/阶段锁盖着时不下单。
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            if (ctxMenuOpen) { closeContextMenu(); return true; }
            if (groupPickerOpen) { closeGroupPicker(); return true; }
            if (cartOverlayOpen) { closeCartOverlay(); return true; } // 结算批次不清，见 handleCartOverlayClick 关闭分支
            if (descOverlayOpen) { closeDescOverlay(); return true; }
            if (guideOverlayOpen) { closeGuideOverlay(); return true; }
            if (clearTextFocus()) return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            if (enterHeld) return true;
            if (selected != null && !modalLayerOpen() && (searchBox == null || !searchBox.isFocused())) {
                enterHeld = true;
                performConfirmTrade(true);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Enter 松开才允许下一次确认。其余按键（含修饰键）一律交还原版，输入法切换中英要靠它。 */
    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            enterHeld = false;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    /** 搜索框或数量框有焦点时退出焦点并返回 true。 */
    private boolean clearTextFocus() {
        boolean searchFocused = searchBox != null && searchBox.isFocused();
        boolean amountFocused = amountBox != null && amountBox.isFocused();
        if (!searchFocused && !amountFocused) return false;
        setFocused(null);
        if (searchBox != null) searchBox.setFocused(false);
        if (amountBox != null) amountBox.setFocused(false);
        return true;
    }

    /**
     * 菜单项按「导航跳转合集」+「管理效率」（仅 canEdit）两组现算：条目没配的跳转类项直接不出现，
     * 管理类整组只有编辑权玩家才追加。同 {@link #drawDetail} 里的跳转判定逻辑保持口径一致（复用同一批
     * {@link ClientShopCatalog}/{@link com.dishanhai.gt_shanhai.client.shop.ShopGuideLookup} 查询）。
     */
    private List<ContextMenuItem> buildContextMenuItems(ShopEntry entry, long entryKey, boolean batchMode) {
        List<ContextMenuItem> items = new ArrayList<>();
        items.add(new ContextMenuItem("§f查看详情", false, () -> selectEntry(entryKey, entry)));

        if (entry.hasLinkTarget()) {
            long targetKey = ClientShopCatalog.resolveLink(entry.getLinkTo());
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
                openOverlay(guideMotion);
                guideOverlayScroll = 0;
            }));
        }

        // 收藏对所有玩家开放（不受 canEdit 限制）：纯个人标记，不影响商店目录本身。
        if (batchMode) {
            List<ShopEntry> selectedEntries = batchEntries();
            boolean allFavored = !selectedEntries.isEmpty();
            for (ShopEntry selectedEntry : selectedEntries) {
                if (!ClientShopFavorites.contains(selectedEntry.getStableId())) {
                    allFavored = false;
                    break;
                }
            }
            items.add(new ContextMenuItem(allFavored ? "§e★批量取消收藏" : "§7☆批量收藏", false,
                    this::toggleBatchFavorites));
        } else {
            boolean favored = ClientShopFavorites.contains(entry.getStableId());
            items.add(new ContextMenuItem(favored ? "§e★取消收藏" : "§7☆收藏", false, () -> {
                ClientShopFavorites.toggle(entry.getStableId());
                if (favoritesOnly) recomputeVisible();
            }));
        }

        if (canEdit) {
            // 批量模式隐藏编辑/复制，避免把单项编辑表单语义误套到整批商品。
            if (!batchMode && catalogEditUnlocked) {
                items.add(new ContextMenuItem("§b编辑条目", true, () -> ShopEntryEditor.openEdit(this, entry)));
                items.add(new ContextMenuItem("§a复制为新条目", false, () -> ShopEntryEditor.openDuplicate(this, entry)));
                items.add(new ContextMenuItem("§d⇄ 快速分组", false, () -> openGroupPicker(entry, entryKey)));
            }
            if (batchMode) {
                List<ShopEntry> selectedEntries = batchEntries();
                boolean allHidden = !selectedEntries.isEmpty();
                for (ShopEntry selectedEntry : selectedEntries) {
                    if (!selectedEntry.isHidden()) {
                        allHidden = false;
                        break;
                    }
                }
                final boolean batchHide = allHidden;
                items.add(new ContextMenuItem(batchHide ? "§a批量取消隐藏" : "§c批量设为隐藏", false,
                        () -> sendBatchManage(batchHide
                                ? com.dishanhai.gt_shanhai.network.ShopBatchManagePacket.Action.UNHIDE
                                : com.dishanhai.gt_shanhai.network.ShopBatchManagePacket.Action.HIDE, null)));
                items.add(new ContextMenuItem("§e▲ 批量前移", false, () -> sendBatchManage(
                        com.dishanhai.gt_shanhai.network.ShopBatchManagePacket.Action.UP, null)));
                items.add(new ContextMenuItem("§e▼ 批量后移", false, () -> sendBatchManage(
                        com.dishanhai.gt_shanhai.network.ShopBatchManagePacket.Action.DOWN, null)));
                items.add(new ContextMenuItem("§6⤒ 批量置顶", false, () -> sendBatchManage(
                        com.dishanhai.gt_shanhai.network.ShopBatchManagePacket.Action.TOP, null)));
                if (catalogEditUnlocked) {
                    items.add(new ContextMenuItem("§d⇄ 批量快速分组", false, () -> openGroupPickerBatch()));
                    items.add(new ContextMenuItem("§b批量设为仅购买", false, () -> sendBatchManage(
                            com.dishanhai.gt_shanhai.network.ShopBatchManagePacket.Action.SET_TRADE_MODE,
                            null, ShopEntry.TradeMode.BUY_ONLY)));
                    items.add(new ContextMenuItem("§e批量设为仅出售", false, () -> sendBatchManage(
                            com.dishanhai.gt_shanhai.network.ShopBatchManagePacket.Action.SET_TRADE_MODE,
                            null, ShopEntry.TradeMode.SELL_ONLY)));
                    items.add(new ContextMenuItem("§a批量设为不限", false, () -> sendBatchManage(
                            com.dishanhai.gt_shanhai.network.ShopBatchManagePacket.Action.SET_TRADE_MODE,
                            null, ShopEntry.TradeMode.BOTH)));
                    boolean sameDeleteBatch = batchDeleteArmed()
                            && pendingDeleteBatchIds.equals(new LinkedHashSet<>(batchSelectionStableIds));
                    items.add(new ContextMenuItem(sameDeleteBatch ? "§c⚠再点一次确认批量删除" : "§c批量删除", true,
                            this::armOrDeleteBatch));
                }
            } else {
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

    /** 商品卡片拖拽排序：TO_INDEX 变体，见 {@link #sendReorder} 同一套「撤销排序」悬浮按钮乐观 UI。 */
    private void sendCardReorder(ShopEntry entry, long entryKey, int localIndex) {
        ShanhaiNetwork.CHANNEL.sendToServer(new com.dishanhai.gt_shanhai.network.ShopReorderPacket(
                com.dishanhai.gt_shanhai.network.ShopReorderPacket.Action.TO_INDEX,
                ClientShopCatalog.revision(), entryKey, localIndex));
        undoReorderLabel = entry.goodsDisplayName();
        undoReorderUntilMs = System.currentTimeMillis() + UNDO_REORDER_UI_WINDOW_MS;
    }

    /**
     * 快速分组：只改 category 一个字段，其余字段原样带回去，走跟「编辑条目」提交同一个 ShopEditPacket
     * EDIT 协议（见 {@link ShopEntryEditScreen} 提交逻辑），不新开一套网络包。目标分类跟当前分类相同时
     * 不发包（无变化）。stableId/限购剩余次数由服务端按 oldEntryKey 解析原条目续用，这里不用管。
     */
    private void sendQuickRegroup(ShopEntry entry, long entryKey, String newCategory) {
        if (entry == null || newCategory == null || newCategory.equals(entry.getCategory())) return;
        com.dishanhai.gt_shanhai.network.ShopEditPacket pkt = new com.dishanhai.gt_shanhai.network.ShopEditPacket(
                com.dishanhai.gt_shanhai.network.ShopEditPacket.Action.EDIT,
                entry.getGoodsList(), newCategory, entry.getDescription(), entry.getCost(),
                entry.getGoodsId(), entry.getCategory(), -1, entry.getRemainingUses(),
                entry.getDisplayIcons(), entry.getRewardMode(), entry.getRewardPool(), entry.isHidden(),
                entry.getLinkKey(), entry.getLinkTo(), entry.getDisplayName(), entry.getFtbqTableId(), entry.getFtbqSubMode(),
                entry.getTradeMode(), entry.getPeriodTicks(), entry.getPeriodLimit(), entry.getPrerequisiteQuestId(),
                ClientShopCatalog.revision(), entryKey,
                entry.getDiscountPercent(), entry.getDiscountStartMs(), entry.getDiscountEndMs());
        ShanhaiNetwork.CHANNEL.sendToServer(pkt);
        showMessage(Component.literal("§b[山海商店] §a已把 §f" + entry.goodsDisplayName() + " §a分组到 §e" + newCategory));
    }

    private void toggleBatchFavorites() {
        List<ShopEntry> entries = batchEntries();
        if (entries.isEmpty()) return;
        boolean allFavored = true;
        for (ShopEntry entry : entries) {
            if (!ClientShopFavorites.contains(entry.getStableId())) {
                allFavored = false;
                break;
            }
        }
        for (ShopEntry entry : entries) {
            if (allFavored) ClientShopFavorites.remove(entry.getStableId());
            else ClientShopFavorites.add(entry.getStableId());
        }
        if (favoritesOnly) recomputeVisible();
        showMessage(Component.literal(allFavored
                ? "§b[山海商店] §7已批量取消收藏 " + entries.size() + " 项"
                : "§b[山海商店] §a已批量收藏 " + entries.size() + " 项"));
    }

    private void sendBatchManage(com.dishanhai.gt_shanhai.network.ShopBatchManagePacket.Action action,
                                 String category) {
        sendBatchManage(action, category, null);
    }

    private void sendBatchManage(com.dishanhai.gt_shanhai.network.ShopBatchManagePacket.Action action,
                                 String category, ShopEntry.TradeMode tradeMode) {
        List<String> ids = batchIdsSnapshot();
        if (ids.isEmpty()) return;
        ShanhaiNetwork.CHANNEL.sendToServer(new com.dishanhai.gt_shanhai.network.ShopBatchManagePacket(
                action, ClientShopCatalog.revision(), ids, category, tradeMode));
        if (action == com.dishanhai.gt_shanhai.network.ShopBatchManagePacket.Action.DELETE) {
            clearBatchSelection();
            undoDeleteLabel = ids.size() + " 件商品";
            undoDeleteUntilMs = System.currentTimeMillis() + UNDO_DELETE_UI_WINDOW_MS;
            clearSelection();
        } else if (action == com.dishanhai.gt_shanhai.network.ShopBatchManagePacket.Action.HIDE
                || action == com.dishanhai.gt_shanhai.network.ShopBatchManagePacket.Action.UNHIDE
                || action == com.dishanhai.gt_shanhai.network.ShopBatchManagePacket.Action.REGROUP) {
            showMessage(Component.literal("§b[山海商店] §a已发起批量管理操作，共 §f" + ids.size() + " §a项"));
        } else if (action == com.dishanhai.gt_shanhai.network.ShopBatchManagePacket.Action.SET_TRADE_MODE) {
            String label = switch (tradeMode == null ? ShopEntry.TradeMode.BOTH : tradeMode) {
                case BUY_ONLY -> "仅购买";
                case SELL_ONLY -> "仅出售";
                default -> "不限";
            };
            showMessage(Component.literal("§b[山海商店] §a已将 " + ids.size() + " 项商品交易方向设为【" + label + "】"));
        } else {
            undoReorderLabel = ids.size() + " 件商品";
            undoReorderUntilMs = System.currentTimeMillis() + UNDO_REORDER_UI_WINDOW_MS;
        }
    }

    private void armOrDeleteBatch() {
        List<String> ids = batchIdsSnapshot();
        if (ids.isEmpty()) return;
        if (batchDeleteArmed() && pendingDeleteBatchIds.equals(new LinkedHashSet<>(ids))) {
            sendBatchManage(com.dishanhai.gt_shanhai.network.ShopBatchManagePacket.Action.DELETE, null);
            pendingDeleteBatchIds.clear();
            pendingDeleteBatchArmedAtMs = 0L;
            showMessage(Component.literal("§b[山海商店] §a已批量删除 §f" + ids.size() + " §a项"));
            return;
        }
        pendingDeleteBatchIds.clear();
        pendingDeleteBatchIds.addAll(ids);
        pendingDeleteBatchArmedAtMs = System.currentTimeMillis();
        showMessage(Component.literal("§e[山海商店] 已选中批量删除，再点一次确认，3 秒内有效"));
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
                armClickWave(mx, my);
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
        int menuX = ctxMenuX, menuY = ctxMenuY, menuH = totalH;
        float p = ctxMotion.value();
        float menuBlur = GuiMotion.clamp01(p);
        int pad = 16;
        int[] hole = popHoleRect(p, menuX, menuY, ctxMenuW, menuH);
        requestPanelGauss(menuX - pad, menuY - pad, ctxMenuW + pad * 2, menuH + pad * 2,
                menuBlur, hole[0], hole[1], hole[2], hole[3], true);
        drawPopPanel(g, p, menuX, menuY, ctxMenuW, menuH, () -> drawContextMenuBody(g, mx, my, menuH));
    }

    private void drawContextMenuBody(GuiGraphics g, int mx, int my, int totalH) {
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
        g.drawCenteredString(this.font, GuiRenderUtil.trimText(this.font, label, w - 4), x + w / 2, y + (h - 8) / 2, col);
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my, long actionKey) {
        boolean hover = GuiRenderUtil.isHovering(mx, my, x, y, w, h);
        long now = System.currentTimeMillis();
        float hv = hoverButtons.follow(widgetKey(x, y, label.hashCode()), hover ? 1f : 0f, now);
        // 详情滚动区传进来的 my 已加过滚动量，按钮也按内容坐标画；减回去才是 buttonHit 记下的屏幕坐标
        long pk = pressKey(x, y - (my - frameMy), w, h);
        ButtonFeedback.Visual v = buttonFeedback.visual(pk, actionKey, true, now, reduceMotionFrame);
        int border = ButtonFeedback.tint(GuiMotion.lerpColor(GOLD_DARK, GOLD, hv), v);
        int fill = ButtonFeedback.shade(GuiMotion.lerpColor(BUTTON_BG, BUTTON_HOVER, hv), v);
        GuiRenderUtil.withButtonMotion(g, x, y, w, h, v.scale(), v.shakeX(), () -> {
            renderBox(g, x, y, w, h, border, fill);
            if (v.pending() && !reduceMotionFrame) {
                GuiRenderUtil.drawPendingSweep(g, x + 1, y + 1, w - 2, h - 2, v.pendingPhase(), 0xE0FFFFFF);
            }
            g.drawCenteredString(this.font, GuiRenderUtil.trimText(this.font, label, w - 4), x + w / 2, y + (h - 8) / 2, WHITE);
        });
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my) {
        drawButton(g, x, y, w, h, label, mx, my, pressKey(x, y - (my - frameMy), w, h));
    }

    /** 指南保留底線連結外觀，點擊的縮放、結果閃色與失敗抖動沿用同一套。 */
    private void drawDetailLink(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my) {
        boolean hover = hit(mx, my, x, y, w, h);
        long now = System.currentTimeMillis();
        long pk = pressKey(x, y - (my - frameMy), w, h);
        float hv = hoverButtons.follow(pk, hover ? 1f : 0f, now);
        ButtonFeedback.Visual visual = buttonFeedback.visual(pk, detailActionKey(DETAIL_GUIDE), true, now, reduceMotionFrame);
        int color = ButtonFeedback.tint(GuiMotion.lerpColor(0xFF5555FF, CYAN, hv), visual);
        String text = "§n" + GuiRenderUtil.trimText(this.font, detailActionLabel(DETAIL_GUIDE, label), w);
        GuiRenderUtil.withButtonMotion(g, x, y, w, h, visual.scale(), visual.shakeX(),
                () -> g.drawString(this.font, text, x, y, color, true));
    }

    private boolean hit(double mx, double my, int x, int y, int w, int h) {
        return GuiRenderUtil.isHovering(mx, my, x, y, w, h);
    }

    /** 按键命中：记下点击波并把按钮压下（松开在 universalMouseReleased）。按下即触发，不吞掉这次点击。 */
    private boolean buttonHit(double mx, double my, int x, int y, int w, int h) {
        if (!hit(mx, my, x, y, w, h)) return false;
        armClickWave(mx, my);
        buttonFeedback.press(pressKey(x, y, w, h), x, y, w, h, true, System.currentTimeMillis());
        return true;
    }

    private void armClickWave(double mx, double my) {
        ShopUiSounds.click();
        // 减少动效：点击声照常，液态波纹不画
        if (ClientShopUiSettings.reduceMotion()) return;
        clickWaveAtMs = System.currentTimeMillis();
        clickWaveX = (int) Math.round(mx);
        clickWaveY = (int) Math.round(my);
    }

    /** 当前点击波的进度 [0,1]；没有进行中的波返回 -1。 */
    private float clickWaveProgress() {
        if (clickWaveAtMs <= 0L) return -1f;
        float t = GuiRenderUtil.popAnimProgress(clickWaveAtMs, CLICK_WAVE_MS);
        if (t >= 1f) {
            clickWaveAtMs = 0L;
            return -1f;
        }
        return t;
    }

    /**
     * 点击后 {@link #CLICK_WAVE_MS} 内：一圈液态玻璃从点击点向外推开。
     *
     * <p>只用高斯是看不见的——商店面板大面积是纯色，糊完还是一样。所以波由三层组成：
     * shader 侧的径向折射位移 + 圆盘透镜放大 + 波峰高光；外加 {@link #renderClickWaveRing}
     * 直绘的亮环，后处理不可用（{@link ShopGaussBlur#isReady()} 为假）时也照样有反馈。</p>
     *
     * <p>波走独立槽位（{@link ShopGaussBlur#requestWave}），不和飞行/浮层的模糊抢位置；
     * 点商品格必然同时开飞行，共用一个槽位就会整段让路，效果等于没有。</p>
     */
    private void requestClickWave(GuiGraphics g) {
        float t = clickWaveProgress();
        if (t < 0f) return;
        renderClickWaveRing(g, t);
        if (this.minecraft == null || this.minecraft.getMainRenderTarget() == null) return;
        float ease = GuiRenderUtil.easeOut(t);
        float strength = 0.95f * (1f - smoothstep(0.55f, 1f, t));
        if (strength <= 0.01f) return;
        float radiusVirtual = CLICK_WAVE_FROM_PX + (CLICK_WAVE_TO_PX - CLICK_WAVE_FROM_PX) * ease;
        int reach = (int) Math.ceil(radiusVirtual) + 2;
        int[] rect = clampClickWave(clickWaveX - reach, clickWaveY - reach, reach * 2, reach * 2);
        if (rect[2] <= 1 || rect[3] <= 1) return;
        int fbW = this.minecraft.getMainRenderTarget().width;
        int fbH = this.minecraft.getMainRenderTarget().height;
        if (fbW <= 0 || fbH <= 0) return;
        float pixelScale = (float) fbW / (float) Math.max(1, this.width);
        float toPx = this.guiScale * pixelScale;
        // 圆盘内部只做轻糊：主要表现靠折射和透镜，糊太狠会变成一团糊掉的方块。
        float kernelPx = 4.5f * toPx;
        float wavePx = radiusVirtual * toPx;
        float bandPx = CLICK_WAVE_BAND_PX * toPx;
        // 折射/高光先强后弱：收尾时归零，避免定格时画面还歪着。
        float puff = 1f - smoothstep(0.30f, 1f, t);
        float refractPx = 7.5f * toPx * puff;
        float glow = 0.85f * puff;
        ShopGaussBlur.requestWave(
                toFramebufferX(rect[0]), toFramebufferY(rect[1]),
                toFramebufferW(rect[2]), toFramebufferH(rect[3]),
                strength, kernelPx, wavePx, bandPx, refractPx, glow);
        float fx = toFramebufferX(clickWaveX);
        float fy = toFramebufferY(clickWaveY);
        ShopGaussBlur.setWaveOrigin((fx + 0.5f) / (float) fbW, 1f - (fy + 0.5f) / (float) fbH);
    }

    /**
     * 直绘的液态玻璃亮环：不依赖后处理，shader 挂掉、或这一帧被别的模糊占用时仍然看得见。
     * 环上按光向左上衰减，做出折射高光的明暗差；再叠一条内圈表示透镜边缘。
     */
    private void renderClickWaveRing(GuiGraphics g, float t) {
        float ease = GuiRenderUtil.easeOut(t);
        float radius = CLICK_WAVE_FROM_PX + (CLICK_WAVE_TO_PX - CLICK_WAVE_FROM_PX) * ease;
        float fade = 1f - smoothstep(0.28f, 1f, t);
        if (fade <= 0.01f || radius < 1f) return;
        int cx = clickWaveX;
        int cy = clickWaveY;
        int segments = Math.max(28, (int) (radius * 2.4f));
        // 起点先亮一下：点击瞬间的玻璃被压下去，环还没铺开
        int coreA = (int) (fade * 92f);
        if (coreA > 0) {
            int cr = 3 + (int) (ease * 5f);
            g.fill(cx - cr, cy - cr, cx + cr, cy + cr, (coreA << 24) | 0xDCEFFF);
        }
        drawRingArc(g, cx, cy, radius, segments, fade, 2.4f, 0xC8E8FF, 1f);
        drawRingArc(g, cx, cy, Math.max(1f, radius * 0.78f), segments, fade * 0.45f, 1.6f, 0x9FD4FF, 1f);
    }

    /**
     * 沿圆周画一圈小方块。{@code lightScale} 控制整体亮度，环上的亮度按光方向衰减，
     * 让环看起来是被折射出来的高光而不是描边。
     */
    private void drawRingArc(GuiGraphics g, int cx, int cy, float radius, int segments,
                             float fade, float thickness, int rgb, float lightScale) {
        int a0 = (int) (fade * 150f * lightScale);
        if (a0 <= 2) return;
        int th = Math.max(1, (int) Math.round(thickness));
        for (int i = 0; i < segments; i++) {
            double ang = (Math.PI * 2.0 * i) / segments;
            // 光从左上来：dot(dir, (-0.65, 0.76))，屏幕 y 向下所以取反
            double lit = 0.35 + 0.65 * (-0.65 * Math.cos(ang) - 0.76 * Math.sin(ang));
            int a = (int) (a0 * Math.max(0.12, lit));
            if (a <= 2) continue;
            int px = cx + (int) Math.round(Math.cos(ang) * radius);
            int py = cy + (int) Math.round(Math.sin(ang) * radius);
            g.fill(px, py, px + th, py + th, (a << 24) | rgb);
        }
    }

    /** 波纹可以盖到顶栏按键，但停在面板内侧，不采到面板外的游戏世界。 */
    private int[] clampClickWave(int x, int y, int w, int h) {
        int x0 = Math.max(x, left + 4);
        int y0 = Math.max(y, top + 4);
        int x1 = Math.min(x + w, left + panelWidth - 4);
        int y1 = Math.min(y + h, top + panelHeight - 4);
        if (x1 <= x0 || y1 <= y0) return new int[]{x0, y0, 0, 0};
        return new int[]{x0, y0, x1 - x0, y1 - y0};
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
        final int orderState;                                   // 0 未核对，1 有样板，2 无样板，3 未绑定 AE
        PreviewCell(ItemStack item, net.minecraft.world.level.material.Fluid fluid, boolean spark, String amount, String exact,
                    String name, java.math.BigInteger need, java.math.BigInteger have, int orderState) {
            this.item = item; this.fluid = fluid; this.spark = spark; this.amount = amount; this.exact = exact;
            this.name = name; this.need = need; this.have = have; this.orderState = orderState;
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
                    "★星火（数字余额）", need, ClientWalletAccount.getDigital(), 0));
        }
        for (Map.Entry<ResourceLocation, java.math.BigInteger> c : cost.coins.entrySet()) {
            java.math.BigInteger need = c.getValue().multiply(t);
            java.math.BigInteger have = ClientCostPreview.coinHave(entryKeyForPreview, aeModeForPreview, c.getKey());
            cells.add(new PreviewCell(currencyStack(c.getKey()), null, false,
                    formatBig(need), groupBig(need), ShopPurchase.coinName(c.getKey()), need, have,
                    ClientCostPreview.coinOrder(entryKeyForPreview, aeModeForPreview, c.getKey())));
        }
        java.util.List<ExchangeEntry.Ingredient> itemIns = cost.items();
        for (int i = 0; i < itemIns.size(); i++) {
            ExchangeEntry.Ingredient in = itemIns.get(i);
            java.math.BigInteger need = java.math.BigInteger.valueOf(in.count).multiply(t);
            Long haveL = ClientCostPreview.itemHave(entryKeyForPreview, aeModeForPreview, i);
            java.math.BigInteger have = haveL != null ? java.math.BigInteger.valueOf(haveL) : null;
            cells.add(new PreviewCell(in.makeUnitStack(), null, false,
                    formatBig(need), groupBig(need), ingredientName(in), need, have,
                    ClientCostPreview.itemOrder(entryKeyForPreview, aeModeForPreview, i)));
        }
        java.util.List<ExchangeEntry.Ingredient> fluidIns = cost.fluids();
        for (int i = 0; i < fluidIns.size(); i++) {
            ExchangeEntry.Ingredient in = fluidIns.get(i);
            net.minecraft.world.level.material.Fluid f = net.minecraftforge.registries.ForgeRegistries.FLUIDS.getValue(in.id);
            java.math.BigInteger need = java.math.BigInteger.valueOf(in.count).multiply(t);
            Long haveL = ClientCostPreview.fluidHave(entryKeyForPreview, aeModeForPreview, i);
            java.math.BigInteger have = haveL != null ? java.math.BigInteger.valueOf(haveL) : null;
            cells.add(new PreviewCell(ItemStack.EMPTY, f, false,
                    formatBig(need) + "mB", groupBig(need) + "mB", ingredientName(in), need, have,
                    ClientCostPreview.fluidOrder(entryKeyForPreview, aeModeForPreview, i)));
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
            // 外层可能正在淡入淡出（错峰入场、浮层开关都靠着色器颜色的 alpha），乘上去再还原，不能强制重置成 1。
            // getShaderColor 返回的是内部数组本身，必须先拷贝。
            float[] ambient = com.mojang.blaze3d.systems.RenderSystem.getShaderColor().clone();
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(
                    r * ambient[0], gg * ambient[1], b * ambient[2], a * ambient[3]);
            g.blit(x, y, 0, size, size, sprite);
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(ambient[0], ambient[1], ambient[2], ambient[3]);
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
        g.drawCenteredString(this.font, costAmountColor(cell) + cell.amount, x + 10, y + 22, WHITE);
    }

    /** 够=绿，不够=红，拥有量还没同步回来=白。方格和条形共用。 */
    private static String costAmountColor(PreviewCell cell) {
        if (cell.have == null) return "§f";
        return cell.have.compareTo(cell.need) >= 0 ? "§a" : "§c";
    }

    private void applyCostHover(PreviewCell cell) {
        if (cell.item != null && !cell.item.isEmpty()) {
            previewHoverStack = cell.item;
            previewHoverName = "§7需要: §f" + cell.exact;
        } else {
            previewHoverName = "§f" + cell.name + " §7×" + cell.exact;
        }
        if (cell.have == null) {
            previewHoverExtra = "§7库存核对中…";
        } else if (cell.have.compareTo(cell.need) >= 0) {
            previewHoverExtra = "§a拥有: " + groupBig(cell.have);
        } else {
            previewHoverExtra = "§c拥有: " + groupBig(cell.have) + " §7(缺 " + groupBig(cell.need.subtract(cell.have)) + ")";
        }
        previewHoverOrder = orderHoverLine(cell.orderState);
    }

    /** 0 不顯示。1 網絡裏有樣板，2 沒有樣板，3 開了 AE 模式但沒綁定網絡。 */
    private static String orderHoverLine(int state) {
        return switch (state) {
            case 1 -> "§a可下单: 网络里有样板";
            case 2 -> "§c不可下单: 网络里没有样板";
            case 3 -> "§7可下单: 未绑定 AE 网络";
            default -> null;
        };
    }

    private static String orderBarTag(int state) {
        return switch (state) {
            case 1 -> "§a可下单";
            case 2 -> "§c无样板";
            case 3 -> "§7未绑定";
            default -> "";
        };
    }

    /**
     * 图形化花费预览。方格是图标槽 + 槽下数量；条形是名称列表 + 右侧数量。两者都跟「获取预览模式」走。
     * 数量按购买次数展开；绿/红/白标出是否够（未同步为白）。超界省略。
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
        if (ClientShopUiSettings.rewardPreviewBar()) {
            return drawCostBars(g, x, sy, cells, maxW, maxBottom, mx, my);
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
                previewTipKey = widgetKey(sx, syy, 1);
                applyCostHover(cell);
            }
            endY = syy + pitchY;
        }
        return endY;
    }

    /**
     * 花费预览的条形。版式跟获得预览的条形一样（图标 + 名称 + 右侧数量），
     * 数量颜色仍表示够不够。花费没有概率。
     */
    private int drawCostBars(GuiGraphics g, int x, int y, java.util.List<PreviewCell> cells, int maxW, int maxBottom, int mx, int my) {
        int rowH = 20;
        int end = y;
        for (int i = 0; i < cells.size(); i++) {
            int ry = y + i * rowH;
            if (ry + rowH > maxBottom) {
                g.drawString(this.font, "§7… 还有 " + (cells.size() - i) + " 项", x, ry + 2, GRAY, true);
                return ry + 12;
            }
            PreviewCell cell = cells.get(i);
            boolean hv = GuiRenderUtil.isHovering(mx, my, x, ry, maxW, rowH);
            if (hv) g.fill(x, ry, x + maxW, ry + rowH - 1, 0x33FFFFFF);
            if (cell.spark) {
                g.drawCenteredString(this.font, "§e★", x + 10, ry + 6, WHITE);
            } else if (cell.fluid != null) {
                renderFluidIcon(g, x + 2, ry + 2, 16, cell.fluid);
            } else if (cell.item != null && !cell.item.isEmpty()) {
                g.renderItem(cell.item, x + 2, ry + 2);
            }
            boolean fluid = cell.fluid != null;
            String qty = costAmountColor(cell) + (fluid ? cell.amount : "×" + cell.amount);
            String orderTag = orderBarTag(cell.orderState);
            int qtyW = this.font.width(qty);
            int tagW = orderTag.isEmpty() ? 0 : this.font.width(orderTag) + 4;
            int nameMax = Math.max(8, maxW - 22 - qtyW - tagW - 2);
            g.drawString(this.font, "§f" + GuiRenderUtil.trimText(this.font, cell.name, nameMax), x + 22, ry + 6, WHITE, true);
            if (!orderTag.isEmpty()) {
                g.drawString(this.font, orderTag, x + maxW - qtyW - tagW, ry + 6, WHITE, true);
            }
            g.drawString(this.font, qty, x + maxW - qtyW, ry + 6, WHITE, true);
            if (hv) {
                previewTipKey = widgetKey(x, ry, 2);
                applyCostHover(cell);
            }
            end = ry + rowH;
        }
        return end;
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

    /** 階段鎖定頁的材料庫存預覽，與商品花費預覽使用相同的服務端統計口徑。 */
    private void maybeRequestStagePreview(String stagePath) {
        if (stagePath == null || stagePath.isBlank()) return;
        net.minecraft.client.multiplayer.ClientLevel lvl = Minecraft.getInstance().level;
        long gameTime = lvl != null ? lvl.getGameTime() : 0L;
        boolean stale = !stagePath.equals(stagePreviewRequestedPath)
                || stagePreviewRequestedAeMode != aeMode
                || gameTime - stagePreviewRequestedAtGameTime >= PREVIEW_REFRESH_TICKS;
        if (!stale) return;
        stagePreviewRequestedPath = stagePath;
        stagePreviewRequestedAeMode = aeMode;
        stagePreviewRequestedAtGameTime = gameTime;
        ShanhaiNetwork.CHANNEL.sendToServer(new ShopStagePreviewRequestPacket(stagePath, aeMode));
    }

    // ============ 图形化获得预览（组合商品，图标 + 数量）============

    /** 获得预览格：完整展示图标（无限盘=内容物主图标+盘本身角标，见 {@link ShopEntry#goodsSlotIcons}）+ 数量，纯展示（不比对拥有量，跟花费预览的核心区别）。tip 是悬停用的真实物品（流体为空）。 */
    private record RewardCell(List<ShopEntry.DisplayIcon> icons, ItemStack tip, String amount, String exact, String name) {}

    /** 把组合商品清单拆成获得预览格，数量按 times 展开（各项独立数量 × times）。 */
    private static java.util.List<RewardCell> goodsRewardCells(List<ShopEntry.GoodsStack> goods, long times) {
        java.math.BigInteger t = java.math.BigInteger.valueOf(Math.max(1L, times));
        java.util.List<RewardCell> cells = new java.util.ArrayList<>();
        for (ShopEntry.GoodsStack gs : goods) {
            java.math.BigInteger need = java.math.BigInteger.valueOf(gs.count()).multiply(t);
            String unit = gs.isFluid() ? "mB" : "";
            ItemStack tip = ItemStack.EMPTY;
            if (!gs.isFluid()) {
                tip = gs.makeStack();
                if (tip.isEmpty()) tip = ShopEntry.missingItemStack(gs.id(), 1);
                else if (tip.getCount() != 1) tip.setCount(1);
            }
            cells.add(new RewardCell(ShopEntry.goodsSlotIcons(gs), tip, formatBig(need) + unit, groupBig(need) + unit,
                    ShopEntry.goodsSlotDisplayName(gs)));
        }
        return cells;
    }

    /**
     * 图形化获得预览：组合商品用，跟「花费预览」同款槽位样式（真图标/流体贴图 + 数量）。
     * 详情列已可滚动，超出一行就换行，不再把第 7 项起收成被裁掉的「+N」。
     * 单行时返回值与旧布局相同（sy+30），调用方用返回值把实物提示和花费预览整体下移。
     * @return 预览区结束后的 y（供调用方续接布局）。物品格悬停写入 {@link #previewHoverStack}。
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
        int pitchY = 32;
        int perRow = Math.max(1, (maxW + 2) / pitchX);
        int endY = sy + 30;
        for (int i = 0; i < cells.size(); i++) {
            int col = i % perRow;
            int row = i / perRow;
            int sx = x + col * pitchX + 1;
            int syy = sy + row * pitchY;
            RewardCell cell = cells.get(i);
            boolean hv = GuiRenderUtil.isHovering(mx, my, sx, syy, 20, 20);
            EditorWidgets.checkerSlot(g, sx, syy, hv);
            renderIconComposite(g, cell.icons(), sx, syy);
            g.drawCenteredString(this.font, "§f" + cell.amount(), sx + 10, syy + 22, WHITE);
            if (hv) {
                previewTipKey = widgetKey(sx, syy, 4);
                if (cell.tip() != null && !cell.tip().isEmpty()) {
                    previewHoverStack = cell.tip();
                    previewHoverName = "§7数量: §f" + cell.exact();
                } else {
                    previewHoverName = "§f" + cell.name() + " §7×" + cell.exact();
                }
            }
            endY = syy + 30;
        }
        return endY;
    }

    /**
     * 普通商品的条形预览。版式跟奖励表清单一样（图标 + 名称 + 右侧数字），
     * 右侧是获得数量（已乘购买次数），不是概率。
     * @return 清单结束后的 y
     */
    private int drawGoodsBars(GuiGraphics g, int x, int y, List<ShopEntry.GoodsStack> goods, long times, int maxW, int mx, int my) {
        java.util.List<RewardCell> cells = goodsRewardCells(goods, times);
        if (cells.isEmpty()) {
            g.drawString(this.font, "§8无", x, y, GRAY, true);
            return y + 12;
        }
        int rowH = 20;
        int end = y;
        for (int i = 0; i < cells.size(); i++) {
            RewardCell cell = cells.get(i);
            int ry = y + i * rowH;
            boolean hv = GuiRenderUtil.isHovering(mx, my, x, ry, maxW, rowH);
            if (hv) g.fill(x, ry, x + maxW, ry + rowH - 1, 0x33FFFFFF);
            if (cell.icons() != null && !cell.icons().isEmpty()) {
                renderIconComposite(g, cell.icons(), x, ry);
            }
            boolean fluid = cell.amount().endsWith("mB");
            String qty = "§e" + (fluid ? cell.amount() : "×" + cell.amount());
            int qtyW = this.font.width(qty);
            int nameMax = Math.max(8, maxW - 22 - qtyW - 2);
            g.drawString(this.font, "§f" + GuiRenderUtil.trimText(this.font, cell.name(), nameMax), x + 22, ry + 6, WHITE, true);
            g.drawString(this.font, qty, x + maxW - qtyW, ry + 6, WHITE, true);
            if (hv) {
                previewTipKey = widgetKey(x, ry, 5);
                if (cell.tip() != null && !cell.tip().isEmpty()) {
                    previewHoverStack = cell.tip();
                    previewHoverName = "§7数量: §f" + cell.exact();
                } else {
                    previewHoverName = "§f" + cell.name();
                    previewHoverExtra = "§7数量: §f" + cell.exact();
                }
            }
            end = ry + rowH;
        }
        return end;
    }

    /**
     * 奖励表的方格预览：图标格，格子下方写概率（全部模式写「必得」）。
     * 自选的百分比仍是表内权重，格子上方注明。
     * @return 格子区结束后的 y
     */
    private int drawRewardOddsGrid(GuiGraphics g, int x, int y, ShopEntry entry, int maxW, int mx, int my) {
        List<RewardOddsLine> lines = rewardOddsLines(entry);
        if (lines == null) {
            g.drawString(this.font, "§c奖励表未同步或不存在", x, y, DEEP_RED, true);
            return y + 12;
        }
        if (lines.isEmpty()) {
            g.drawString(this.font, "§8奖励表是空的", x, y, GRAY, true);
            return y + 12;
        }
        if (rewardOddsChoice(entry)) {
            g.drawString(this.font, "§8百分比为表内权重", x, y, GRAY, true);
            y += 10;
        }
        int pitchX = 40;
        int pitchY = 32;
        int perRow = Math.max(1, (maxW + 2) / pitchX);
        int endY = y;
        for (int i = 0; i < lines.size(); i++) {
            int col = i % perRow;
            int row = i / perRow;
            int sx = x + col * pitchX + 1;
            int syy = y + row * pitchY;
            RewardOddsLine line = lines.get(i);
            boolean hv = GuiRenderUtil.isHovering(mx, my, sx, syy, 20, 30);
            EditorWidgets.checkerSlot(g, sx, syy, hv);
            boolean hasIcon = line.icon() != null && !line.icon().isEmpty();
            if (hasIcon) {
                g.renderItem(line.icon(), sx + 2, syy + 2);
                g.renderItemDecorations(this.font, line.icon(), sx + 2, syy + 2);
            } else {
                g.drawCenteredString(this.font, "§8?", sx + 10, syy + 6, GRAY);
            }
            String chance = (line.deliverable() ? "§e" : "§8") + line.chance();
            g.drawCenteredString(this.font, GuiRenderUtil.trimText(this.font, chance, pitchX - 2), sx + 10, syy + 22, WHITE);
            if (hv) {
                previewTipKey = widgetKey(sx, syy, 6);
                String odds = "§7概率: §e" + line.chance();
                if (!line.deliverable()) odds += " §c不发放";
                String qty = line.count().isEmpty() ? "" : "§7数量: §f" + line.count();
                if (hasIcon) {
                    previewHoverStack = line.icon();
                    previewHoverName = qty.isEmpty() ? odds : qty;
                    previewHoverExtra = qty.isEmpty() ? (line.deliverable() ? null : "§c商店不发放此项") : odds;
                } else {
                    previewHoverName = "§f" + line.name();
                    previewHoverExtra = odds;
                }
            }
            endY = syy + pitchY;
        }
        return endY;
    }

    /**
     * 奖励表一行：图标 + 名称 + 数量区间 + 玩家可见的概率文案。
     * {@code chance} 不含颜色码。{@code deliverable}=false 表示 FTBQ 表里的非物品奖励，商店抽到后不发放。
     */
    private record RewardOddsLine(ItemStack icon, String name, String count, String chance, boolean deliverable) {}

    /**
     * 详情页奖励表清单。本地池与 FTBQ 表都在这里把「有什么、多少、概率多少」画出来。
     * 随机模式的百分比跟 FTBQ 奖励表悬浮一致（{@code WeightedReward.chanceString}，空权重不计入）。
     * 全部模式每一项都是必得；自选模式百分比只是表内权重占比，真正给哪一项由玩家点选。
     * @return 清单结束后的 y
     */
    private int drawRewardOdds(GuiGraphics g, int x, int y, ShopEntry entry, int maxW, int mx, int my) {
        List<RewardOddsLine> lines = rewardOddsLines(entry);
        if (lines == null) {
            g.drawString(this.font, "§c奖励表未同步或不存在", x, y, DEEP_RED, true);
            return y + 12;
        }
        if (lines.isEmpty()) {
            g.drawString(this.font, "§8奖励表是空的", x, y, GRAY, true);
            return y + 12;
        }
        boolean choice = rewardOddsChoice(entry);
        if (choice) {
            g.drawString(this.font, "§8百分比为表内权重", x, y, GRAY, true);
            y += 10;
        } else if (!rewardOddsAll(entry)) {
            g.drawString(this.font, "§8百分比为单次抽取", x, y, GRAY, true);
            y += 10;
        }
        int rowH = 18;
        int end = y;
        for (int i = 0; i < lines.size(); i++) {
            RewardOddsLine line = lines.get(i);
            int ry = y + i * rowH;
            boolean hv = GuiRenderUtil.isHovering(mx, my, x, ry, maxW, rowH);
            if (hv) g.fill(x, ry, x + maxW, ry + rowH - 1, 0x33FFFFFF);
            boolean hasIcon = line.icon() != null && !line.icon().isEmpty();
            if (hasIcon) {
                g.renderItem(line.icon(), x, ry);
                g.renderItemDecorations(this.font, line.icon(), x, ry);
            }
            String chance = (line.deliverable() ? "§e" : "§8") + line.chance();
            String count = line.count().isEmpty() ? "" : "§7" + line.count();
            int chanceW = this.font.width(chance);
            int countW = count.isEmpty() ? 0 : this.font.width(count) + 2;
            int iconW = hasIcon ? 18 : 0;
            int nameMax = Math.max(8, maxW - iconW - countW - chanceW - 2);
            String name = (line.deliverable() ? "§f" : "§8") + GuiRenderUtil.trimText(this.font, line.name(), nameMax);
            int nameW = this.font.width(name);
            g.drawString(this.font, name, x + iconW, ry + 4, WHITE, true);
            if (!count.isEmpty()) g.drawString(this.font, count, x + iconW + nameW + 2, ry + 4, GRAY, true);
            g.drawString(this.font, chance, x + maxW - chanceW, ry + 4, WHITE, true);
            if (hv) {
                previewTipKey = widgetKey(x, ry, 7);
                String qty = line.count().isEmpty() ? "" : "§7数量: §f" + line.count();
                String odds = "§7概率: §e" + line.chance();
                if (!line.deliverable()) odds += " §c不发放";
                if (hasIcon) {
                    previewHoverStack = line.icon();
                    previewHoverName = qty.isEmpty() ? odds : qty;
                    previewHoverExtra = qty.isEmpty() ? (line.deliverable() ? null : "§c商店不发放此项") : odds;
                } else {
                    previewHoverName = "§f" + line.name();
                    previewHoverExtra = odds;
                }
            }
            end = ry + rowH;
        }
        return end;
    }

    /** 自选（本地池或 FTBQ 子模式）时，百分比不是抽取概率。 */
    private static boolean rewardOddsChoice(ShopEntry entry) {
        if (entry.getRewardMode() == ShopEntry.RewardMode.CHOICE) return true;
        return entry.getRewardMode() == ShopEntry.RewardMode.FTBQ
                && entry.getFtbqSubMode() == ShopEntry.RewardMode.CHOICE;
    }

    private static boolean rewardOddsAll(ShopEntry entry) {
        if (entry.getRewardMode() == ShopEntry.RewardMode.ALL) return true;
        return entry.getRewardMode() == ShopEntry.RewardMode.FTBQ
                && entry.getFtbqSubMode() == ShopEntry.RewardMode.ALL;
    }

    /**
     * 奖励表清单。FTBQ 表在客户端还没同步到时返回 null（跟「表是空的」区分开）。
     */
    private List<RewardOddsLine> rewardOddsLines(ShopEntry entry) {
        if (entry.getRewardMode() == ShopEntry.RewardMode.FTBQ) return ftbqOddsLines(entry);
        return localOddsLines(entry);
    }

    private static List<RewardOddsLine> localOddsLines(ShopEntry entry) {
        List<ShopEntry.RewardOption> pool = entry.getRewardPool();
        float total = 0f;
        for (int i = 0; i < pool.size(); i++) total += pool.get(i).weight();
        boolean all = entry.getRewardMode() == ShopEntry.RewardMode.ALL;
        List<RewardOddsLine> lines = new ArrayList<>(pool.size());
        for (int i = 0; i < pool.size(); i++) {
            ShopEntry.RewardOption opt = pool.get(i);
            ItemStack icon = opt.item().copy();
            if (icon.getCount() != 1) icon.setCount(1);
            String count = opt.minCount() == opt.maxCount() ? "×" + opt.minCount() : "×" + opt.minCount() + "-" + opt.maxCount();
            String chance = all ? "必得" : ftbqChance(opt.weight(), total);
            lines.add(new RewardOddsLine(icon, icon.getHoverName().getString(), count, chance, true));
        }
        return lines;
    }

    /** @return null 表示表还没同步到客户端，或 ID 对不上 */
    private List<RewardOddsLine> ftbqOddsLines(ShopEntry entry) {
        dev.ftb.mods.ftbquests.quest.loot.RewardTable table = clientFtbqTable(entry);
        if (table == null) return null;
        float total = table.getTotalWeight(false);
        boolean all = rewardOddsAll(entry);
        java.util.List<dev.ftb.mods.ftbquests.quest.loot.WeightedReward> rewards = table.getWeightedRewards();
        List<RewardOddsLine> lines = new ArrayList<>(rewards.size());
        for (int i = 0; i < rewards.size(); i++) {
            dev.ftb.mods.ftbquests.quest.loot.WeightedReward wr = rewards.get(i);
            dev.ftb.mods.ftbquests.quest.reward.Reward reward = wr.getReward();
            boolean item = reward instanceof dev.ftb.mods.ftbquests.quest.reward.ItemReward;
            ItemStack icon = ItemStack.EMPTY;
            String name = reward.getTitle().getString();
            String count = "";
            if (item) {
                dev.ftb.mods.ftbquests.quest.reward.ItemReward ir = (dev.ftb.mods.ftbquests.quest.reward.ItemReward) reward;
                ItemStack raw = ir.getItem();
                if (raw != null && !raw.isEmpty()) {
                    icon = raw.copy();
                    if (icon.getCount() != 1) icon.setCount(1);
                    name = icon.getHoverName().getString();
                }
                count = "×" + ir.getButtonText();
            }
            String chance = !item ? ftbqChance(wr.getWeight(), total) : (all ? "必得" : ftbqChance(wr.getWeight(), total));
            lines.add(new RewardOddsLine(icon, name, count, chance, item));
        }
        return lines;
    }

    private static dev.ftb.mods.ftbquests.quest.loot.RewardTable clientFtbqTable(ShopEntry entry) {
        String id = entry.getFtbqTableId();
        if (id == null || id.isEmpty()) return null;
        dev.ftb.mods.ftbquests.client.ClientQuestFile file = dev.ftb.mods.ftbquests.client.ClientQuestFile.INSTANCE;
        if (file == null) return null;
        long code = dev.ftb.mods.ftbquests.quest.QuestObjectBase.parseCodeString(id);
        if (code == 0L) return null;
        return file.getRewardTable(code);
    }

    /** 与 FTBQ 奖励表悬浮同一套百分比（单次抽取、不含空权重）。 */
    private static String ftbqChance(float weight, float total) {
        return dev.ftb.mods.ftbquests.quest.loot.WeightedReward.chanceString(weight, total);
    }

    /** 网格悬停：把奖励表内容和概率附在商品 tooltip 上，详情页仍是完整清单。 */
    private void appendRewardOddsTooltip(List<Component> lines, ShopEntry entry) {
        if (entry.getRewardMode() == ShopEntry.RewardMode.NONE) return;
        List<RewardOddsLine> odds = rewardOddsLines(entry);
        lines.add(Component.literal("§7奖励内容:"));
        if (odds == null) {
            lines.add(Component.literal("§c奖励表未同步"));
            return;
        }
        if (odds.isEmpty()) {
            lines.add(Component.literal("§8空"));
            return;
        }
        if (rewardOddsChoice(entry)) lines.add(Component.literal("§8百分比为表内权重，购买时自选"));
        int limit = 10;
        int n = Math.min(limit, odds.size());
        for (int i = 0; i < n; i++) {
            RewardOddsLine row = odds.get(i);
            String count = row.count().isEmpty() ? "" : " §7" + row.count();
            String mark = row.deliverable() ? "" : " §8不发放";
            lines.add(Component.literal("§f" + row.name() + count + " §e" + row.chance() + mark));
        }
        if (odds.size() > limit) {
            lines.add(Component.literal("§8… 还有 " + (odds.size() - limit) + " 项，详情页可看全部"));
        }
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

    /**
     * 把當前花費裡已經確定不夠的幣種、物品、流體寫進 JEI 收藏。
     * 和 GTLCore 合成確認頁的「收藏缺失」一樣：只收缺失項，已在收藏裡的不重複計。
     */
    private void bookmarkMissingCosts() {
        if (selected == null) return;
        long key = detailActionKey(DETAIL_BOOKMARK);
        if (!buttonFeedback.tryBegin(key, System.currentTimeMillis())) {
            ShopUiSounds.busy();
            return;
        }
        ShopCost cost = mode == Mode.BUY
                ? selected.getEffectiveCost(ShopMembership.discountPercentForTier(ClientWalletAccount.getMemberTier()))
                : selected.getCost();
        java.math.BigInteger times = java.math.BigInteger.valueOf(Math.max(1L, amount));
        java.util.List<ItemStack> items = new ArrayList<>();
        java.util.List<FluidStack> fluids = new ArrayList<>();
        for (Map.Entry<ResourceLocation, java.math.BigInteger> coin : cost.coins.entrySet()) {
            java.math.BigInteger have = ClientCostPreview.coinHave(selectedEntryKey, aeMode, coin.getKey());
            if (have == null || have.compareTo(coin.getValue().multiply(times)) >= 0) continue;
            var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(coin.getKey());
            if (item != null) items.add(new ItemStack(item));
        }
        java.util.List<ExchangeEntry.Ingredient> itemIns = cost.items();
        for (int i = 0; i < itemIns.size(); i++) {
            ExchangeEntry.Ingredient in = itemIns.get(i);
            Long have = ClientCostPreview.itemHave(selectedEntryKey, aeMode, i);
            if (have == null || java.math.BigInteger.valueOf(have).compareTo(java.math.BigInteger.valueOf(in.count).multiply(times)) >= 0) continue;
            ItemStack stack = in.makeUnitStack();
            if (!stack.isEmpty()) items.add(stack);
        }
        java.util.List<ExchangeEntry.Ingredient> fluidIns = cost.fluids();
        for (int i = 0; i < fluidIns.size(); i++) {
            ExchangeEntry.Ingredient in = fluidIns.get(i);
            Long have = ClientCostPreview.fluidHave(selectedEntryKey, aeMode, i);
            if (have == null || java.math.BigInteger.valueOf(have).compareTo(java.math.BigInteger.valueOf(in.count).multiply(times)) >= 0) continue;
            net.minecraft.world.level.material.Fluid fluid = net.minecraftforge.registries.ForgeRegistries.FLUIDS.getValue(in.id);
            if (fluid != null && fluid != net.minecraft.world.level.material.Fluids.EMPTY) {
                fluids.add(in.nbt() == null ? new FluidStack(fluid, 1000) : new FluidStack(fluid, 1000, in.nbt()));
            }
        }
        JeiBookmarkBridge.MissingBookmarkResult result = JeiBookmarkBridge.addMissing(items, fluids);
        ButtonFeedback.Tone tone = switch (result.status()) {
            case ADDED, ALREADY -> ButtonFeedback.Tone.SUCCESS;
            case NOTHING -> ButtonFeedback.Tone.PARTIAL;
            case UNAVAILABLE, FAILED -> ButtonFeedback.Tone.FAILED;
        };
        flashDetailAction(key, tone);
        String text = switch (result.status()) {
            case ADDED -> "§a[山海商店] 已收藏缺失 " + result.added() + " 项";
            case ALREADY -> "§7[山海商店] 缺失项都已在 JEI 收藏里";
            case NOTHING -> "§7[山海商店] 没有可收藏的缺失";
            case UNAVAILABLE -> "§c[山海商店] JEI 收藏不可用";
            case FAILED -> "§c[山海商店] 收藏缺失失败";
        };
        showMessage(Component.literal(text));
    }

    // 详情页每帧都要问「成本里有没有可在商店买到的实物」，而 hasShopGoodsId 是对全部 stub 的线性扫描
    // （O(实物原料数×商品总数)）——按选中条目+目录 revision 缓存一格，选中不变、目录不变就不再重扫
    private ShopEntry buyMatsCachedFor;
    private long buyMatsCachedRevision = -1L;
    private boolean buyMatsCachedResult;

    private boolean hasShopPurchasableMaterialCostCached(ShopEntry entry, ShopCost cost) {
        long rev = ClientShopCatalog.revision();
        if (buyMatsCachedFor != entry || buyMatsCachedRevision != rev) {
            buyMatsCachedFor = entry;
            buyMatsCachedRevision = rev;
            buyMatsCachedResult = hasShopPurchasableMaterialCost(cost);
        }
        return buyMatsCachedResult;
    }

    private static boolean hasShopPurchasableMaterialCost(ShopCost cost) {
        if (cost == null || cost.items().isEmpty()) return false;
        for (ExchangeEntry.Ingredient ingredient : cost.items()) {
            if (ingredient != null && hasShopGoodsId(ingredient.id)) return true;
        }
        return false;
    }

    private static boolean hasShopGoodsId(ResourceLocation id) {
        if (id == null) return false;
        String needle = id.toString();
        for (com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest.Stub stub : ClientShopCatalog.stubs()) {
            if (stub.goodsIds().size() == 1 && stub.goodsIds().contains(needle)) return true;
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

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 真正画 tooltip：由 {@link #drawTopLayer} 在模糊之后调，smx/smy 是虚拟坐标，mx/my 是窗口坐标。 */
    private void drawTooltipLayer(GuiGraphics g, int smx, int smy, int mx, int my) {
        g.flush();
        g.pose().pushPose();
        g.pose().translate(0.0f, 0.0f, 600.0f);
        try {
            if (ctxMenuOpen || descOverlayOpen || guideOverlayOpen || groupPickerOpen || cartOverlayOpen) return; // 大图层/右键菜单盖住时，底层格子/图标的 tooltip 不该透出来
            List<Component> topTip = topButtonTip(smx, smy);
            if (topTip != null) {
                animateTooltip(g, List.of("top", topTip.get(0).getString()), mx, my,
                        () -> g.renderComponentTooltip(this.font, topTip, mx, my));
                return;
            }
            List<net.minecraft.util.FormattedCharSequence> detailTip = detailButtonTip(smx, smy);
            if (detailTip != null) {
                animateTooltip(g, List.of("detail", detailTipKey), mx, my,
                        () -> g.renderTooltip(this.font, detailTip, mx, my));
                return;
            }
            // 悬停货币栏：显示货币全名 + 精确余额
            ResourceLocation cur = hoveredCurrency(smx, smy);
            if (cur != null) {
                java.math.BigInteger bal = WalletAccountAPI.isSpark(cur)
                        ? ClientWalletAccount.getDigital()
                        : ClientWalletAccount.getCurrency(cur);
                List<Component> lines = new ArrayList<>();
                lines.add(Component.literal("§6" + ShopPurchase.coinName(cur)));
                lines.add(Component.literal("§7余额: §f" + bal.toString()));
                animateTooltip(g, List.of("currency", cur), mx, my,
                        () -> g.renderComponentTooltip(this.font, lines, mx, my));
                return;
            }
            // 悬停详情页商品大图标：渲染真实物品 tooltip（含 appendHoverText/能力提示，如 SDA 实时解析内容）
            if (detailHoverStack != null && !detailHoverStack.isEmpty()) {
                animateTooltip(g, List.of("icon", selectedEntryKey), mx, my,
                        () -> g.renderTooltip(this.font, detailHoverStack, mx, my));
                return;
            }
            // 悬停获得/花费预览的物品格：原版物品 tooltip，再附上数量或拥有/缺少
            if (previewHoverStack != null && !previewHoverStack.isEmpty()) {
                List<Component> lines = new ArrayList<>(getTooltipFromItem(Minecraft.getInstance(), previewHoverStack));
                if (previewHoverName != null) lines.add(Component.literal(previewHoverName));
                if (previewHoverExtra != null) lines.add(Component.literal(previewHoverExtra));
                if (previewHoverOrder != null) lines.add(Component.literal(previewHoverOrder));
                animateTooltip(g, previewTooltipTarget(), mx, my,
                        () -> g.renderTooltip(this.font, lines, previewHoverStack.getTooltipImage(), mx, my));
                return;
            }
            // 悬停详情页花费/提交预览格：显示本地化名 + 数量，外加拥有/缺少或提交说明
            if (previewHoverName != null) {
                if (previewHoverExtra != null) {
                    List<Component> lines = new ArrayList<>();
                    lines.add(Component.literal(previewHoverName));
                    lines.add(Component.literal(previewHoverExtra));
                    if (previewHoverOrder != null) lines.add(Component.literal(previewHoverOrder));
                    animateTooltip(g, previewTooltipTarget(), mx, my,
                            () -> g.renderComponentTooltip(this.font, lines, mx, my));
                } else {
                    animateTooltip(g, previewTooltipTarget(), mx, my,
                            () -> g.renderTooltip(this.font, Component.literal(previewHoverName), mx, my));
                }
                return;
            }
            // 阶段锁定盖着商品区：下面的格子看不见也点不到，不该再弹它们的 tooltip。
            if (stageLockActive) return;
            // 悬停商品格：坐标直接反算唯一索引，不再逐格扫描。
            int idx = entryIndexAt(smx, smy);
            if (idx < 0) return;
            ShopEntry e = visibleEntry(idx);
            if (e == null) {
                animateTooltip(g, List.of("grid", visibleEntryKeys.get(idx), ClientShopCatalog.revision()), mx, my,
                        () -> g.renderTooltip(this.font, Component.literal("§8商品数据加载中…"), mx, my));
                return;
            }
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("§f" + e.goodsDisplayName()));
            if (e.hasMultipleGoods()) {
                StringBuilder gc = new StringBuilder("§7成分: §f");
                for (ShopEntry.GoodsStack gs : e.getGoodsList()) {
                    gc.append(gs.count()).append('×').append(ShopEntry.goodsSlotDisplayName(gs)).append(' ');
                }
                lines.add(Component.literal(gc.toString().trim()));
            } else {
                lines.add(Component.literal("§7每份 " + e.getGoodsCount() + " 个"));
            }
            String tradeLabel = switch (e.getTradeMode()) {
                case BUY_ONLY -> "仅购买";
                case SELL_ONLY -> "仅出售";
                default -> "不限";
            };
            lines.add(Component.literal("§7交易方向: §f" + tradeLabel));
            int memberPct = ShopMembership.discountPercentForTier(ClientWalletAccount.getMemberTier());
            if (e.isDiscountActive()) {
                lines.add(Component.literal("§7基础成本 §8" + costInline(e.getCost())
                        + " §a→ 当前生效成本 " + costInline(e.getEffectiveCost(memberPct))));
                lines.add(Component.literal("§6限时特惠 -" + e.getDiscountPercent() + "% §7剩" + formatDuration(e.discountRemainingMs() / 1000L)));
            } else if (memberPct > 0) {
                lines.add(Component.literal("§7基础成本 §8" + costInline(e.getCost())
                        + " §a→ 当前生效成本 " + costInline(e.getEffectiveCost(memberPct))
                        + " §d(会员-" + memberPct + "%)"));
            } else {
                lines.add(Component.literal("§7基础成本 " + costInline(e.getCost())));
            }
            if (!e.allowsSell()) {
                lines.add(Component.literal("§7出售回收: §c不可出售"));
            } else if (e.getCost().isEmpty()) {
                lines.add(Component.literal("§7出售回收: §c免费商品不可出售"));
            } else if (e.getRewardMode() != ShopEntry.RewardMode.NONE) {
                lines.add(Component.literal("§7出售回收: §c奖励模式商品不可出售"));
            } else if (e.hasMultipleGoods() || e.getCost().hasPhysical()) {
                lines.add(Component.literal("§7出售回收: §c组合/实物成本商品不可出售"));
            } else {
                lines.add(Component.literal("§7出售回收: §e" + costInline(e.getCost().scaledTo(ShopPurchase.sellRatioPercent()))));
            }
            if (e.isLimited()) lines.add(Component.literal("§7限购剩余 §d" + formatBig(java.math.BigInteger.valueOf(e.getRemainingUses())) + " §7次"));
            if (e.isPeriodLimited()) {
                lines.add(Component.literal("§7周期限购: 每 " + formatDuration(e.getPeriodTicks() / 20L)
                        + " 秒最多 " + formatBig(java.math.BigInteger.valueOf(e.getPeriodLimit())) + " 次"));
            }
            String rewardLabel = switch (e.getRewardMode()) {
                case CHOICE -> "自选奖励";
                case RANDOM -> "随机奖励";
                case ALL -> "全部奖励";
                case FTBQ -> "FTBQ表·" + switch (e.getFtbqSubMode()) {
                    case CHOICE -> "自选";
                    case ALL -> "全部";
                    default -> "随机";
                };
                default -> "固定商品";
            };
            lines.add(Component.literal("§7奖励模式: §d" + rewardLabel));
            appendRewardOddsTooltip(lines, e);
            if (e.getCost().hasPhysical()) {
                int itemCount = e.getCost().items().size();
                int fluidCount = e.getCost().fluids().size();
                lines.add(Component.literal("§8实物成本: 物品 " + itemCount + " 项，流体 " + fluidCount + " 项"
                        + (fluidCount > 0 ? "（流体需绑定 AE）" : "（物品从背包扣除）")));
            }
            if (e.hasSubmissionRequirement()) {
                lines.add(Component.literal("§6固定提交: " + e.getSubmissionItems().size() + " 项，购买前需一次性提交"));
            }
            if (!e.getDisplayIcons().isEmpty()) {
                StringBuilder ic = new StringBuilder("§7图标: §f");
                for (ShopEntry.DisplayIcon d : e.getDisplayIcons()) ic.append(d.displayName()).append(' ');
                lines.add(Component.literal(ic.toString().trim()));
            }
            if (e.getDescription() != null && !e.getDescription().isEmpty()) {
                lines.add(Component.literal("§7" + GuiRenderUtil.translateAmpCodes(e.getDescription())));
            }
            lines.add(Component.literal("§8左键打开详情 · Ctrl+左键加入购物车 · 右键菜单"));
            if (canEdit) {
                lines.add(Component.literal("§8Alt+左键勾选批量 · 空白处 Alt+右键清空"));
            }
            animateTooltip(g, List.of("grid", visibleEntryKeys.get(idx), ClientShopCatalog.revision()), mx, my,
                    () -> g.renderComponentTooltip(this.font, lines, mx, my));
        } finally {
            tooltipMotion.endFrame();
            g.pose().popPose();
        }
    }

    private Object previewTooltipTarget() {
        return List.of("preview", selectedEntryKey, previewTipKey, stageLockPath == null ? "" : stageLockPath);
    }

    /** 在原版 tooltip 繪製外套現有浮層動效，仍於模糊後繪製並保留原版螢幕邊界定位。 */
    private void animateTooltip(GuiGraphics g, Object target, int mx, int my, Runnable draw) {
        float progress = tooltipMotion.show(target, System.currentTimeMillis(), ClientShopUiSettings.reduceMotion());
        GuiRenderUtil.withPanelMotion(g, mx, my, progress, draw);
    }
}
