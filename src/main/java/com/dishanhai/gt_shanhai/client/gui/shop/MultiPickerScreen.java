package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.AdvancedSearchUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiMotion;
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.RepeatCurve;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.client.gui.scaled.Spring;
import com.dishanhai.gt_shanhai.client.gui.glass.GlassGeometry;
import com.dishanhai.gt_shanhai.client.gui.glass.GuiRect;
import com.dishanhai.gt_shanhai.client.gui.glass.PixelRect;
import com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings;
import com.dishanhai.gt_shanhai.jei.JeiBookmarkBridge;

import dev.architectury.fluid.FluidStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 多选资源选择器（山海署名，客户端，自建）。
 *
 * <p>浏览模式：<b>物品</b>（全注册表）、<b>物品栏</b>（玩家背包实物，<b>保留 NBT</b>，
 * 便捷取手上带 NBT 的物品）、<b>JEI书签</b>（当前 JEI 书签列表，物品+流体混在同一页不分流，
 * 见 {@link JeiBookmarkBridge}，未装 JEI 时为空）、<b>流体</b>（全注册表）。右侧「已选清单」每项独立数量。
 * 左键网格=加入/选中，右键网格=加入/移除切换，<b>Ctrl+Shift+左键</b>=直接把指向该资源的「无限胞」
 * （{@code expatternprovider:infinity_cell}，record NBT 绑定物品/流体 id，参考 JEI 内 Ctrl+左键"作弊获取无限胞"
 * 的目标构造方式）写入已选清单（见 {@link #addInfinityCellFromGrid}；不走 JEI 给予协议，纯本地构造）。
 * 物品与流体同一会话可混选；确认按类型分发：物品→{@code onAddItem}（保 NBT），流体→{@code onAddFluid}。</p>
 *
 * <p>由 {@link ShopEntryEditScreen} 的「物品排 +」「流体排 +」打开，取代原 FTBLib 单选。
 * 关屏后返回编辑器（其 init 重新按草稿布局，显示新加入项）。</p>
 */
public class MultiPickerScreen extends ScaledScreen {

    private enum Mode { ITEM, INVENTORY, BOOKMARK, FLUID, TEXTURE }

    // 配色（沿用编辑器面板风格）
    private static final int GOLD = -22016;
    private static final int GOLD_DARK = -7710208;
    private static final int PANEL_BG = -267382768;
    private static final int PANEL_INNER = -266724838;
    private static final int GRAY = -5592406;
    private static final int WHITE = -1;
    private static final int CYAN = -11141121;
    private static final int BTN_BG = -14935012;
    private static final int BTN_HOVER = -12303292;
    private static final int SEL_ACTIVE = -12285748;  // 已选清单当前激活行底色

    private static final int TARGET_W = 600;
    private static final int TARGET_H = 380;
    private static final int SLOT = 20;
    private static final int PITCH = 22;
    /** ExtendedAE 无限胞物品 id：Ctrl+Shift+左键网格时直接写入已选清单的目标物品，见 addInfinityCellFromGrid。 */
    private static final ResourceLocation INFINITY_CELL_ID = ResourceLocation.tryParse("expatternprovider:infinity_cell");
    private static final int SCROLLBAR_W = 4;

    /** 搜索框悬停提示：语法说明（同 JEI 习惯的 @/#/$/* 前缀）。 */
    private static final List<Component> SEARCH_HELP = List.of(
            Component.literal("§f按物品名搜索"),
            Component.literal("§8──────────────"),
            Component.literal("§7用 §e@ §7按模组搜索 §8(@ae2)"),
            Component.literal("§7用 §e# §7按标签搜索 §8(#ingot)"),
            Component.literal("§7用 §e$ §7在工具提示中搜索 §8($抢夺)"),
            Component.literal("§7用 §e* §7按物品id搜索 §8(*cell)"));

    // ===== 全注册表缓存（首次打开构建，之后复用）=====
    private static List<ItemStack> ALL_ITEMS;
    private static List<String> ALL_ITEM_NAMES;
    private static List<String> ALL_ITEM_TAGS;   // # 标签搜索用（小写标签全名拼接）
    private static String[] ALL_ITEM_TOOLTIP;    // $ 提示文本搜索用（懒加载，数量大不能预算）
    private static List<Fluid> ALL_FLUIDS;
    private static List<String> ALL_FLUID_NAMES;
    private static List<String> ALL_FLUID_TAGS;  // # 标签搜索用
    private static List<ResourceLocation> ALL_TEXTURES;      // 贴图模式：全资源包 PNG 路径（仅 allowTexture 才懒加载）
    private static List<String> ALL_TEXTURE_NAMES;

    /** 已选条目（物品/流体/贴图三选一，各自独立数量；贴图 count 恒为 1 无意义）。物品的 itemIcon 为原型（保留 NBT）。 */
    private static final class Sel implements PickerItem {
        final boolean isFluid;
        final boolean isTexture;
        final ResourceLocation id;
        final ItemStack itemIcon;   // 物品原型（含 NBT）；流体/贴图为空
        final Fluid fluid;          // 流体用；其余为 null
        final ResourceLocation textureId; // 贴图用；其余为 null
        long count;
        Sel(boolean isFluid, ResourceLocation id, ItemStack itemIcon, Fluid fluid, long count) {
            this(isFluid, false, id, itemIcon, fluid, null, count);
        }
        private Sel(boolean isFluid, boolean isTexture, ResourceLocation id, ItemStack itemIcon, Fluid fluid, ResourceLocation textureId, long count) {
            this.isFluid = isFluid; this.isTexture = isTexture; this.id = id; this.itemIcon = itemIcon;
            this.fluid = fluid; this.textureId = textureId; this.count = count;
        }
        static Sel texture(ResourceLocation tex) {
            return new Sel(false, true, tex, null, null, tex, 1L);
        }
        @Override
        public long getCount() { return count; }
        @Override
        public void setCount(long value) { count = isTexture ? 1L : PickerSelection.clampCount(value); }
    }

    private final Screen parent;
    // 物品确认回调带精确 long 数量：ItemStack.setCount 物理上限是 int，光靠 stack 传数量会把
    // 100000000000 这类输入悄悄夹成 2147483647（消费方再 getCount() 反推 long 等于白存）。
    // stack 照旧带 int 夹值（角标/兼容用），需要真实数量的消费方（成本排 List<Long>）读第二参。
    private final java.util.function.ObjLongConsumer<ItemStack> onAddItem;
    private final Consumer<FluidStack> onAddFluid;
    private final Consumer<ResourceLocation> onAddTexture; // 贴图模式确认回调；非 allowTexture 场景为 null
    private Runnable afterConfirm; // 全部回调之后、回到父界面之前。取消不走这里。
    private final boolean restrictedMode;         // true = 仅限固定候选列表（如已配置货币），无模式切换/物品栏/流体
    private final boolean allowTexture;           // true = 浏览模式循环里加入「贴图」（仅显示图标选择器开启，物品/流体排选择器不受影响）
    // 替换模式：编辑器点已有的格子（或商品空占位格）打开。已选清单预先放着原来的东西，确认后整份清单换掉那一格，
    // 清空再确认 = 删掉那一格（由编辑器的 afterConfirm 处理）。取代原先点格子弹出的 FTBLib 单选页。
    private boolean replacing;
    private boolean hadOriginal;                  // 替换模式下原格子有东西（空占位格没有）
    // 整排替换：编辑器把这一排所有格子依次 preselect 进来，再用 focusPreselected 指出点的是哪一格；确认后整排换成已选清单
    private final List<Sel> preselectOrder = new ArrayList<>(); // 每次 preselect 的结果，空格占一个 null，按调用序号找回
    private boolean rowMode;

    private Mode mode;                            // 浏览模式：物品 / 物品栏 / 流体 / [贴图]（受限模式恒为 ITEM）
    private List<ItemStack> invStacks = new ArrayList<>();  // 物品栏模式：玩家背包实物（含 NBT）
    private List<String> invNames = new ArrayList<>();
    private List<String> invTags = new ArrayList<>();
    private List<Long> invCounts = new ArrayList<>();        // 与 invStacks 同下标：该物品+NBT 在背包+副手里的实际总数
    // JEI书签模式：物品+流体混在同一页，不分流——索引空间按"先物品后流体"拼接：
    // [0, bookmarkStacks.size()) 是物品，[bookmarkStacks.size(), bookmarkStacks.size()+bookmarkFluids.size()) 是流体，
    // 见 buildBookmarks/isBookmarkFluidIndex/curFluid。bookmarkNames/bookmarkTags 是两段拼接后的合并列表。
    private List<ItemStack> bookmarkStacks = new ArrayList<>();
    private List<Fluid> bookmarkFluids = new ArrayList<>();
    private List<String> bookmarkNames = new ArrayList<>();
    private List<String> bookmarkTags = new ArrayList<>();
    private List<ItemStack> restrictedStacks = new ArrayList<>(); // 受限模式：固定候选物品
    private List<String> restrictedNames = new ArrayList<>();
    private List<String> restrictedTags = new ArrayList<>();
    private final java.util.Map<Integer, String> tooltipCache = new java.util.HashMap<>(); // 物品栏/受限模式的 $ 搜索会话内缓存
    private final PickerSelection<Sel> selection = new PickerSelection<>();
    private final List<Sel> selected = selection.getItems();
    private Sel active;                           // 当前激活（顶部数量框编辑对象）
    private List<Integer> filtered = new ArrayList<>(); // 当前模式下过滤后的索引
    private int gridScroll;                       // 网格行偏移
    private int selScroll;                        // 已选清单行偏移
    private boolean itemCacheAppliedOnce;          // 本次开屏后台物品缓存建完那一刻，只强制刷新一次过滤结果

    /** 按钮、滚动条、清单行 hover 全走和商店弹窗同一套反馈（山海署名）。 */
    private final ShopDialogUi ui = new ShopDialogUi(GOLD_DARK, GOLD, BTN_BG, BTN_HOVER);
    /** 刚加入清单的那一项：那一行和网格对应格子亮一下再淡掉，告诉玩家「加到这里了」。 */
    private Sel lastAdded;
    private long lastAddedAtMs;
    /** 切换浏览模式（物品/物品栏/书签/流体/贴图）的那一刻：网格整片重新淡入，不硬切。 */
    private long modeChangedAtMs;

    private EditBox searchBox, countBox;
    private int left, top, panelWidth, panelHeight;
    // 网格/清单几何（initScaled 计算）
    private int gridX, gridY, gridW, gridBottom, gridCols;
    private int selX, selY, selW;

    private PickerGeometry geometry;
    private List<PickerGridRow> gridModel = List.of();
    private List<PickerSortEntry> sortEntries = List.of();
    private List<String> sourceModules = List.of();
    private List<String> moduleOrder = List.of();
    private final java.util.Map<String, String> moduleNames = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> moduleCounts = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> filteredModuleCounts = new java.util.HashMap<>();
    private final java.util.Map<String, List<Integer>> sortOrders = new java.util.HashMap<>();
    private List<Integer> moduleHeights = List.of();
    private int[] moduleStarts = new int[0];
    private java.util.Map<String, Integer> headerHeights = java.util.Map.of();
    private final Set<String> collapsedModules = new HashSet<>();
    private final Set<Integer> dragVisited = new HashSet<>();
    private final java.util.Map<Integer, Spring> slotHover = new java.util.HashMap<>();
    private final Spring gridMotion = new Spring(Spring.SCROLL);
    private final Spring selectedMotion = new Spring(Spring.SCROLL);
    private final Spring opening = Spring.opening(Spring.PANEL);
    private String pickerSort = ClientShopUiSettings.pickerSort();
    private boolean groupByMod = ClientShopUiSettings.pickerGroupByMod();
    private String moduleFilter = "";
    private String sourceLanguage = "";
    private long sourceVersion;
    private long sortGeneration;
    private String pendingSortKey = "";
    private boolean sorting;
    private boolean sortOpen;
    private int shownGridScroll, shownSelScroll;
    private int sidebarX, sidebarW, sortY, groupY, modulesY, moduleScroll;
    private List<Integer> selectionHeights = List.of();
    private int[] selectionStarts = new int[0];
    private Sel editingQuantity, originalSelection, draggedSelection, heldQuantity;
    private int heldQuantityY;
    private boolean dragStarted;
    private boolean gridGesture;
    private int gestureButton = -1;
    private int gestureOrigin = -1;
    private double gestureX, gestureY;
    private int insertion;
    private int scrollbarOwner;
    private long lastFrameMs;
    private float gridVelocity;
    private boolean confirmed;

    private ItemStack hoverItem;                  // 悬停网格物品（renderTooltips 消费）
    private String hoverSelNameFull;              // 清单列名放不下时的完整名字（renderTooltips 消费）
    private List<Component> hoverFluidTip;        // 悬停网格流体名

    public MultiPickerScreen(Screen parent, boolean browseFluid, java.util.function.ObjLongConsumer<ItemStack> onAddItem, Consumer<FluidStack> onAddFluid) {
        super(Component.literal("多选资源"));
        this.parent = parent;
        this.restrictedMode = false;
        this.allowTexture = false;
        this.mode = browseFluid ? Mode.FLUID : Mode.ITEM;
        this.onAddItem = onAddItem;
        this.onAddFluid = onAddFluid;
        this.onAddTexture = null;
        this.targetWidth = TARGET_W;
        this.targetHeight = TARGET_H;
        this.useOffset = false;
        this.minScale = 0.1f;
        this.maxScale = Float.MAX_VALUE;
        ensureCache();
    }

    /**
     * 显示图标选择器：允许切到「贴图」浏览模式（读全资源包任意 PNG 路径，非物品图标）+ 物品模式（可选多个物品当图标）。
     * 与前两个构造器（物品排/流体排/币种排「+」）完全独立，不影响它们的行为。
     */
    public MultiPickerScreen(Screen parent, java.util.function.ObjLongConsumer<ItemStack> onAddItem, Consumer<ResourceLocation> onAddTexture) {
        super(Component.literal("多选显示图标"));
        this.parent = parent;
        this.restrictedMode = false;
        this.allowTexture = true;
        this.mode = Mode.ITEM;
        this.onAddItem = onAddItem;
        this.onAddFluid = null;
        this.onAddTexture = onAddTexture;
        this.targetWidth = TARGET_W;
        this.targetHeight = TARGET_H;
        this.useOffset = false;
        this.minScale = 0.1f;
        this.maxScale = Float.MAX_VALUE;
        ensureCache();
    }

    /**
     * 受限模式：仅从 allowedIds 里选（如商店已配置货币），无模式切换、无物品栏/流体浏览——
     * 避免在两万多条全注册表里大海捞针（见 {@link ShopEntryEditScreen} 币种排「+」）。
     */
    public MultiPickerScreen(Screen parent, java.util.Collection<ResourceLocation> allowedIds, java.util.function.ObjLongConsumer<ItemStack> onAddItem) {
        super(Component.literal("多选货币"));
        this.parent = parent;
        this.restrictedMode = true;
        this.allowTexture = false;
        this.mode = Mode.ITEM;
        this.onAddItem = onAddItem;
        this.onAddFluid = null;
        this.onAddTexture = null;
        this.targetWidth = TARGET_W;
        this.targetHeight = TARGET_H;
        this.useOffset = false;
        this.minScale = 0.1f;
        this.maxScale = Float.MAX_VALUE;
        if (allowedIds != null) {
            for (ResourceLocation id : allowedIds) {
                Item it = ForgeRegistries.ITEMS.getValue(id);
                if (it == null) continue;
                ItemStack st = new ItemStack(it);
                if (st.isEmpty()) continue;
                restrictedStacks.add(st);
                restrictedNames.add((st.getHoverName().getString() + " " + id).toLowerCase(Locale.ROOT));
                restrictedTags.add(tagStringOf(it));
            }
        }
    }

    // 全物品缓存构建状态：数量大（两万+条目，还要逐个算显示名/标签字符串），放主线程会在首次
    // 打开时卡一下，改到后台线程建（见 ensureCache/buildItemCacheAsync），画面在没建完前显示占位。
    private static volatile boolean itemCacheReady = false;
    private static volatile boolean itemCacheLoading = false;

    // ===== 注册表缓存构建 =====
    private static void ensureCache() {
        if (!itemCacheReady && !itemCacheLoading) {
            itemCacheLoading = true;
            net.minecraft.Util.backgroundExecutor().execute(MultiPickerScreen::buildItemCacheAsync);
        }
        if (ALL_FLUIDS == null) {
            List<Fluid> fluids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            List<String> tags = new ArrayList<>();
            for (Fluid f : ForgeRegistries.FLUIDS.getValues()) {
                if (f == Fluids.EMPTY) continue;
                if (!f.isSource(f.defaultFluidState())) continue; // 跳过 flowing 变体
                ResourceLocation id = ForgeRegistries.FLUIDS.getKey(f);
                fluids.add(f);
                String nm = FluidStack.create(f, 1000).getName().getString();
                names.add((nm + " " + id).toLowerCase(Locale.ROOT));
                tags.add(tagStringOf(f));
            }
            ALL_FLUIDS = fluids;
            ALL_FLUID_NAMES = names;
            ALL_FLUID_TAGS = tags;
        }
    }

    /**
     * 全物品缓存实际构建（后台线程执行，不阻塞打开界面那一下）。
     * 优先用 JEI 已排好序 + 已过滤隐藏黑名单 + 含本模组额外注册摄取物（如 SDA 包）的列表
     * （见 {@link JeiItemOrderHolder}，来自 {@code ShanhaiJEIPlugin#onRuntimeAvailable}）；
     * 没装 JEI / 还没就绪时该列表为 null，走原有创造栏聚合顺序 + 全注册表兜底（跟改动前一致）。
     * 单个物品名称/标签解析异常不该拖垮整批构建，逐项吞掉跳过即可。
     */
    private static void buildItemCacheAsync() {
        List<ItemStack> items = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        try {
            Set<Item> seen = new HashSet<>();
            List<ItemStack> jeiOrder = JeiItemOrderHolder.get();
            if (jeiOrder != null) {
                for (ItemStack proto : jeiOrder) {
                    addItemToCache(proto == null ? null : proto.getItem(), items, names, tags, seen);
                }
            }
            // 先按创造模式「搜索」栏的聚合顺序补漏（JEI 未装，或 JEI 列表里没有但仍在创造栏的物品）
            CreativeModeTab searchTab = BuiltInRegistries.CREATIVE_MODE_TAB.getOrThrow(CreativeModeTabs.SEARCH);
            for (ItemStack proto : searchTab.getSearchTabDisplayItems()) {
                addItemToCache(proto.getItem(), items, names, tags, seen);
            }
            // 兜底：没进任何创造栏/JEI 列表的物品（个别模组内部/隐藏物品）追加在最后，保证仍可选中
            for (Item it : ForgeRegistries.ITEMS.getValues()) {
                addItemToCache(it, items, names, tags, seen);
            }
        } catch (Throwable t) {
            dev.latvian.mods.kubejs.KubeJS.LOGGER.warn("[山海多选资源] 后台构建物品缓存异常，已降级为已收集到的部分列表", t);
        }
        ALL_ITEMS = items;
        ALL_ITEM_NAMES = names;
        ALL_ITEM_TAGS = tags;
        ALL_ITEM_TOOLTIP = new String[items.size()];
        itemCacheReady = true; // 必须在上面四行赋值之后置 true——MultiPickerScreen 靠这个 volatile 标记安全发布
        itemCacheLoading = false;
    }

    private static void addItemToCache(Item it, List<ItemStack> items, List<String> names, List<String> tags, Set<Item> seen) {
        if (it == null || !seen.add(it)) return;
        try {
            ItemStack st = new ItemStack(it);
            if (st.isEmpty()) return;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(it);
            items.add(st);
            names.add((st.getHoverName().getString() + " " + id).toLowerCase(Locale.ROOT));
            tags.add(tagStringOf(it));
        } catch (Throwable ignored) {
            // 单个物品名称/标签解析异常不该拖垮整批缓存构建，跳过这一项即可
        }
    }

    /**
     * 贴图缓存：只有真的切进「贴图」模式才扫描（避免只用物品/流体的场景白付这笔资源包遍历开销）。
     * 扫描全部已加载资源包（含各模组）里的 .png 路径，不限于 item/block——照搬 FTBLib 那套「任意贴图」范围。
     */
    private static void ensureTextureCache() {
        if (ALL_TEXTURES != null) return;
        List<ResourceLocation> list = new ArrayList<>();
        List<String> names = new ArrayList<>();
        var mgr = Minecraft.getInstance().getResourceManager();
        for (ResourceLocation loc : mgr.listResources("textures", rl -> rl.getPath().endsWith(".png")).keySet()) {
            list.add(loc);
            names.add(loc.toString().toLowerCase(Locale.ROOT));
        }
        ALL_TEXTURES = list;
        ALL_TEXTURE_NAMES = names;
    }

    /** 物品标签全名拼接（小写，空格分隔），供 # 搜索用。 */
    private static String tagStringOf(Item item) {
        StringBuilder sb = new StringBuilder();
        item.builtInRegistryHolder().tags().forEach(t -> sb.append(t.location().toString()).append(' '));
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** 流体标签全名拼接（同上）。 */
    private static String tagStringOf(Fluid fluid) {
        StringBuilder sb = new StringBuilder();
        fluid.builtInRegistryHolder().tags().forEach(t -> sb.append(t.location().toString()).append(' '));
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** 物品提示文本（tooltip 全部行拼接，小写），供 $ 搜索用。异常静默返回空串。 */
    private static String tooltipOf(ItemStack st) {
        try {
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.world.item.TooltipFlag flag = mc.options != null && mc.options.advancedItemTooltips
                    ? net.minecraft.world.item.TooltipFlag.Default.ADVANCED
                    : net.minecraft.world.item.TooltipFlag.Default.NORMAL;
            StringBuilder sb = new StringBuilder();
            for (Component line : st.getTooltipLines(mc.player, flag)) {
                sb.append(line.getString()).append(' ');
            }
            return sb.toString().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /** ALL_ITEMS 专用懒缓存：数量大（两万+），首次 $ 搜索时逐个算一次后固化，之后复用。 */
    private static String allItemTooltip(int idx) {
        String cached = ALL_ITEM_TOOLTIP[idx];
        if (cached != null) return cached;
        String t = tooltipOf(ALL_ITEMS.get(idx));
        ALL_ITEM_TOOLTIP[idx] = t;
        return t;
    }

    /**
     * 物品栏模式数据：玩家背包（主 + 副手）实物，按物品+NBT 去重，复制并保留 NBT，
     * 同时按同下标累计该物品+NBT 在背包+副手里的实际总数（{@link #invCounts}），
     * 供 {@link #addFromGrid} 把新加入项的初始数量自动填成玩家当前持有量，而不是恒为 1。
     */
    private void buildInventory() {
        invStacks = new ArrayList<>();
        invNames = new ArrayList<>();
        invTags = new ArrayList<>();
        invCounts = new ArrayList<>();
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        List<ItemStack> src = new ArrayList<>();
        src.addAll(player.getInventory().items);
        src.addAll(player.getInventory().offhand);
        for (ItemStack st : src) {
            if (st == null || st.isEmpty()) continue;
            int dupIdx = -1;
            for (int i = 0; i < invStacks.size(); i++) {
                if (ItemStack.isSameItemSameTags(invStacks.get(i), st)) { dupIdx = i; break; }
            }
            if (dupIdx >= 0) {
                invCounts.set(dupIdx, invCounts.get(dupIdx) + st.getCount());
                continue;
            }
            ItemStack copy = st.copy();
            copy.setCount(1);
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(st.getItem());
            invStacks.add(copy);
            invNames.add((st.getHoverName().getString() + " " + id).toLowerCase(Locale.ROOT));
            invTags.add(tagStringOf(st.getItem()));
            invCounts.add((long) st.getCount());
        }
    }

    /**
     * JEI书签模式数据：读取 JEI 当前书签列表里的物品类条目（{@link JeiBookmarkBridge}，纯反射，
     * 未装 JEI/书签为空时恒返回空列表）。跟物品栏模式一样只在开屏时读一次快照，不追踪会话内的
     * 书签增删——真要看最新书签，关屏重开即可，跟物品栏模式的取舍一致。
     */
    /**
     * JEI书签模式数据：物品+流体混在同一页，不分流。索引空间按"先物品后流体"拼接
     * （见字段注释/{@link #isBookmarkFluidIndex}/{@link #curFluid}），bookmarkNames/bookmarkTags
     * 是两段拼接后的合并列表，跟 bookmarkStacks+bookmarkFluids 顺序对齐。
     */
    private void buildBookmarks() {
        bookmarkStacks = new ArrayList<>();
        bookmarkFluids = new ArrayList<>();
        bookmarkNames = new ArrayList<>();
        bookmarkTags = new ArrayList<>();
        for (ItemStack st : JeiBookmarkBridge.getBookmarkedItemStacks()) {
            if (st == null || st.isEmpty()) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(st.getItem());
            bookmarkStacks.add(st);
            bookmarkNames.add((st.getHoverName().getString() + " " + id).toLowerCase(Locale.ROOT));
            bookmarkTags.add(tagStringOf(st.getItem()));
        }
        for (Fluid f : JeiBookmarkBridge.getBookmarkedFluids()) {
            if (f == null || f == Fluids.EMPTY) continue;
            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(f);
            String nm = FluidStack.create(f, 1000).getName().getString();
            bookmarkFluids.add(f);
            bookmarkNames.add((nm + " " + id).toLowerCase(Locale.ROOT));
            bookmarkTags.add(tagStringOf(f));
        }
    }

    /** BOOKMARK 模式下第 i 项是否落在流体段（书签列表"先物品后流体"拼接，见 buildBookmarks）。 */
    private boolean isBookmarkFluidIndex(int i) {
        return mode == Mode.BOOKMARK && i >= bookmarkStacks.size();
    }

    /** 当前索引对应的流体："流体"模式直接查全注册表；BOOKMARK 模式落在流体段时查 bookmarkFluids 对应偏移。 */
    private Fluid curFluid(int resIdx) {
        if (isBookmarkFluidIndex(resIdx)) return bookmarkFluids.get(resIdx - bookmarkStacks.size());
        return ALL_FLUIDS.get(resIdx);
    }

    @Override
    protected void initScaled() {
        commitQuantity();
        ui.cancel();
        ui.syncEnterHeld();
        left = Math.max(6, (vWidth - TARGET_W) / 2);
        top = Math.max(8, (vHeight - TARGET_H) / 2);
        panelWidth = Math.min(TARGET_W, vWidth - 12);
        panelHeight = Math.min(TARGET_H, vHeight - 16);

        String keepQuery = searchBox != null ? searchBox.getValue() : "";
        if (!restrictedMode) { buildInventory(); buildBookmarks(); }
        geometry = MultiPickerLayout.geometry(panelWidth, panelHeight, pickerHeaderExtraHeight());
        GuiRect grid = geometry.getGrid();
        GuiRect list = geometry.getSelection();
        GuiRect sidebar = geometry.getSidebar();
        GuiRect search = geometry.getSearch();
        gridX = left + Math.round(grid.getX());
        gridY = top + Math.round(grid.getY());
        gridW = Math.round(grid.getW()) - 6;
        gridBottom = gridY + Math.round(grid.getH());
        gridCols = Math.max(1, (gridW + 2) / PITCH);
        selX = left + Math.round(list.getX());
        selY = top + Math.round(list.getY());
        selW = Math.round(list.getW()) - 6;
        sidebarX = left + Math.round(sidebar.getX());
        sidebarW = Math.round(sidebar.getW());
        searchBox = new EditBox(this.font, left + Math.round(search.getX()), top + Math.round(search.getY()),
                Math.round(search.getW()) - 18, Math.round(search.getH()), Component.literal("搜索"));
        searchBox.setMaxLength(64);
        searchBox.setBordered(true);
        searchBox.setTextColor(0xFFFFFF);
        searchBox.setResponder(s -> rebuildFilter());
        searchBox.setHint(Component.literal("§8@模组 #标签 $提示 *id"));
        searchBox.setValue(keepQuery);
        searchBox.setFocused(true);
        setFocused(searchBox);

        countBox = new EditBox(this.font, selX + 40, selY + 24, 130, 16, Component.literal("数量"));
        countBox.setMaxLength(19);
        countBox.setBordered(true);
        countBox.setTextColor(0xFFFF55);
        countBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        countBox.visible = false;
        countBox.active = false;
        addRenderableWidget(searchBox);
        addRenderableWidget(countBox);

        rebuildSource();
        rebuildSelectionRows();
        ShopGaussBlur.warmUp();
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return s != null && s.matches("\\d+") ? Long.MAX_VALUE : 1L; }
    }

    private List<String> curNames() {
        if (restrictedMode) return restrictedNames;
        return switch (mode) {
            case ITEM -> itemCacheReady ? ALL_ITEM_NAMES : List.of(); // 后台还没建完时按"暂无条目"处理，见 buildItemCacheAsync
            case INVENTORY -> invNames;
            case BOOKMARK -> bookmarkNames; // 物品+流体合并列表，见 buildBookmarks
            case FLUID -> ALL_FLUID_NAMES;
            case TEXTURE -> ALL_TEXTURE_NAMES;
        };
    }

    private int pickerHeaderExtraHeight() {
        if (originalSelection == null || !replacing) return 0;
        int width = Math.max(1, Math.min(228, panelWidth - 270));
        int lines = this.font.split(Component.literal("§8原格：" + selectionName(originalSelection)
                + " ×" + originalSelection.count), width).size();
        return Math.max(0, lines * this.font.lineHeight + 12 - 44);
    }

    private void refreshSourceNames() {
        sourceLanguage = Minecraft.getInstance().getLanguageManager().getSelected();
        if (restrictedMode) {
            for (int index = 0; index < restrictedStacks.size(); index++)
                restrictedNames.set(index, namedId(restrictedStacks.get(index).getHoverName().getString(), curIdForStack(restrictedStacks.get(index))));
        }
        if (itemCacheReady && ALL_ITEMS != null) {
            List<String> names = new ArrayList<>(ALL_ITEMS.size());
            for (ItemStack stack : ALL_ITEMS) names.add(namedId(stack.getHoverName().getString(), curIdForStack(stack)));
            ALL_ITEM_NAMES = names;
            ALL_ITEM_TOOLTIP = new String[ALL_ITEMS.size()];
        }
        if (invStacks != null) {
            invNames = new ArrayList<>(invStacks.size());
            for (ItemStack stack : invStacks) invNames.add(namedId(stack.getHoverName().getString(), curIdForStack(stack)));
        }
        if (bookmarkStacks != null && bookmarkFluids != null) {
            bookmarkNames = new ArrayList<>(bookmarkStacks.size() + bookmarkFluids.size());
            for (ItemStack stack : bookmarkStacks) bookmarkNames.add(namedId(stack.getHoverName().getString(), curIdForStack(stack)));
            for (Fluid fluid : bookmarkFluids) bookmarkNames.add(namedId(fluidName(fluid), ForgeRegistries.FLUIDS.getKey(fluid)));
        }
        if (ALL_FLUIDS != null) {
            List<String> names = new ArrayList<>(ALL_FLUIDS.size());
            for (Fluid fluid : ALL_FLUIDS) names.add(namedId(fluidName(fluid), ForgeRegistries.FLUIDS.getKey(fluid)));
            ALL_FLUID_NAMES = names;
        }
        if (ALL_TEXTURES != null)
            ALL_TEXTURE_NAMES = ALL_TEXTURES.stream().map(id -> id.toString().toLowerCase(Locale.ROOT)).toList();
    }

    private static ResourceLocation curIdForStack(ItemStack stack) {
        return ForgeRegistries.ITEMS.getKey(stack.getItem());
    }

    private static String namedId(String name, ResourceLocation id) {
        return ((name == null ? "" : name) + " " + (id == null ? "" : id)).toLowerCase(Locale.ROOT);
    }

    /** 侧栏右侧数量列宽：取最宽的那个数量，所有行共用，名称在剩下的宽度里换行。 */
    private int moduleCountColumnW() {
        int widest = this.font.width(Integer.toString(curNames().size()));
        for (int count : moduleCounts.values()) widest = Math.max(widest, this.font.width(Integer.toString(count)));
        return widest;
    }

    private int moduleLabelWidth() {
        return Math.max(1, sidebarW - 2 - 4 - moduleCountColumnW() - 3);
    }

    private void rebuildModuleRows() {
        List<Integer> heights = new ArrayList<>(moduleOrder.size() + 1);
        heights.add(22);
        int labelWidth = moduleLabelWidth();
        for (String id : moduleOrder) {
            int lines = this.font.split(Component.literal(moduleNames.getOrDefault(id, id)), labelWidth).size();
            heights.add(Math.max(22, lines * this.font.lineHeight + 6));
        }
        moduleHeights = List.copyOf(heights);
        moduleStarts = MultiPickerLayout.rowStarts(moduleHeights);
        int contentHeight = moduleStarts.length == 0 ? 0
                : moduleStarts[moduleStarts.length - 1] + moduleHeights.get(moduleHeights.size() - 1);
        moduleScroll = Math.max(0, Math.min(moduleScroll, contentHeight - Math.max(0, gridBottom - modulesY)));

        java.util.Map<String, Integer> heightsByModule = new java.util.HashMap<>();
        for (String id : moduleOrder) {
            String label = (collapsedModules.contains(id) ? "▸ " : "▾ ")
                    + moduleNames.getOrDefault(id, id) + " (" + filteredModuleCounts.getOrDefault(id, 0) + ")";
            int lines = this.font.split(Component.literal(label), Math.max(1, gridW - 8)).size();
            heightsByModule.put(id, Math.max(18, lines * this.font.lineHeight + 8));
        }
        headerHeights = java.util.Map.copyOf(heightsByModule);
    }

    private void rebuildFilteredModuleCounts() {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (int index : filtered) counts.merge(sourceModules.get(index), 1, Integer::sum);
        filteredModuleCounts.clear();
        filteredModuleCounts.putAll(counts);
        rebuildModuleRows();
    }

    private int moduleContentHeight() {
        return moduleStarts.length == 0 ? 0
                : moduleStarts[moduleStarts.length - 1] + moduleHeights.get(moduleHeights.size() - 1);
    }

    private int moduleRowAt(double y) {
        return MultiPickerLayout.selectionRowAt(moduleStarts, moduleHeights,
                (int) y - modulesY + moduleScroll);
    }

    /** 当前模式下网格第 resIdx 项的物品原型（仅物品/物品栏/受限模式，或 BOOKMARK 模式落在物品段时有效）。 */
    private ItemStack gridItem(int resIdx) {
        if (restrictedMode) return restrictedStacks.get(resIdx);
        return switch (mode) {
            case INVENTORY -> invStacks.get(resIdx);
            case BOOKMARK -> bookmarkStacks.get(resIdx); // 调用方须先用 isBookmarkFluidIndex 排除流体段
            default -> ALL_ITEMS.get(resIdx);
        };
    }

    // 过滤结果缓存（会话内，按「浏览模式+查询串」为键）：同一查询反复出现（回退重打/来回切模式再切回）
    // 直接复用，不用重新扫一遍全量；LRU 封顶，避免长时间搜索会话无限增长。见反馈：补缓存键降低重复轮询卡顿。
    private final java.util.Map<String, List<Integer>> filterResultCache =
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, List<Integer>> eldest) {
                    return size() > 24;
                }
            };

    private void rebuildFilter() {
        if (geometry == null) return;
        String q = (searchBox != null ? searchBox.getValue().trim() : "").toLowerCase(Locale.ROOT);
        String cacheKey = sourceVersion + "|" + mode + "|" + pickerSort + "|" + moduleFilter + "|" + sourceLanguage + "|" + q;
        List<Integer> cached = filterResultCache.get(cacheKey);
        if (cached != null) {
            filtered = cached;
            rebuildFilteredModuleCounts();
            rebuildGridRows();
            return;
        }
        List<Integer> out = new ArrayList<>();
        for (int index : currentOrder()) {
            if ((moduleFilter.isEmpty() || moduleFilter.equals(sourceModules.get(index))) && matchIndex(index, q)) out.add(index);
        }
        filtered = out;
        filterResultCache.put(cacheKey, out);
        rebuildFilteredModuleCounts();
        rebuildGridRows();
    }

    private void rebuildSource() {
        sourceVersion++;
        sortGeneration++;
        sorting = false;
        pendingSortKey = "";
        refreshSourceNames();
        tooltipCache.clear();
        filterResultCache.clear();
        sortOrders.clear();
        slotHover.clear();
        List<PickerSortEntry> entries = new ArrayList<>();
        List<String> modules = new ArrayList<>();
        moduleNames.clear();
        moduleCounts.clear();
        List<String> names = curNames();
        for (int index = 0; index < names.size(); index++) {
            String id = curIdOnly(index);
            String namespace = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
            String display = moduleNames.computeIfAbsent(namespace, key -> net.minecraftforge.fml.ModList.get().getModContainerById(key)
                    .map(container -> container.getModInfo().getDisplayName()).orElse(key));
            String name = names.get(index);
            if (name.endsWith(" " + id)) name = name.substring(0, name.length() - id.length() - 1);
            long count = mode == Mode.INVENTORY && index < invCounts.size() ? invCounts.get(index) : 1L;
            entries.add(new PickerSortEntry(index, name, namespace, display, id, count));
            modules.add(namespace);
            moduleCounts.merge(namespace, 1, Integer::sum);
        }
        sortEntries = List.copyOf(entries);
        sourceModules = List.copyOf(modules);
        moduleOrder = PickerSort.modules(sortEntries, sourceLanguage);
        rebuildModuleRows();
        if (!moduleFilter.isEmpty() && !moduleCounts.containsKey(moduleFilter)) moduleFilter = "";
        moduleScroll = 0;
        rebuildFilter();
    }

    private List<Integer> currentOrder() {
        String key = mode + "|" + pickerSort + "|" + sourceVersion + "|" + sourceLanguage;
        List<Integer> cached = sortOrders.get(key);
        if (cached != null) return cached;
        List<Integer> fallback = sortEntries.stream().map(PickerSortEntry::getIndex).toList();
        if ("default".equals(pickerSort) || ("count".equals(pickerSort) && mode != Mode.INVENTORY)) return fallback;
        if (!key.equals(pendingSortKey)) {
            pendingSortKey = key;
            sorting = true;
            long version = sourceVersion;
            long generation = ++sortGeneration;
            Mode requestedMode = mode;
            String requestedSort = pickerSort;
            String language = sourceLanguage;
            List<PickerSortEntry> snapshot = List.copyOf(sortEntries);
            java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> PickerSort.order(snapshot, requestedSort, requestedMode == Mode.INVENTORY, language),
                    net.minecraft.Util.backgroundExecutor()).whenComplete((order, failure) -> Minecraft.getInstance().execute(() -> {
                        if (generation != sortGeneration || version != sourceVersion || mode != requestedMode
                                || !language.equals(sourceLanguage) || !requestedSort.equals(pickerSort)
                                || Minecraft.getInstance().screen != this) return;
                        sorting = false;
                        pendingSortKey = "";
                        sortOrders.put(key, failure == null ? List.copyOf(order) : fallback);
                        filterResultCache.clear();
                        rebuildFilter();
                    }));
        }
        return fallback;
    }

    private void rebuildGridRows() {
        gridModel = MultiPickerLayout.gridRows(filtered, sourceModules, moduleOrder, gridCols, groupByMod,
                collapsedModules, headerHeights);
        gridScroll = shownGridScroll = 0;
        gridMotion.snap(0);
        cancelHold();
    }

    /**
     * 按当前浏览模式对第 i 项做搜索匹配。前缀语法（同 JEI 习惯）：
     * {@code @ae2} 按模组、{@code #ingot} 按标签、{@code $抢夺} 按提示文本、{@code *cell} 仅按物品id；
     * 无前缀走名称+id 子串（含拼音兜底，见 {@link AdvancedSearchUtil}）。
     */
    private boolean matchIndex(int i, String q) {
        if (q.isEmpty()) return true;
        char p = q.charAt(0);
        String name = curNames().get(i); // 已是小写 "显示名 id"
        if (q.length() <= 1) {
            // 单独一个前缀符号（无内容）：视为通配，全部命中
            if (p == '@' || p == '#' || p == '$' || p == '*') return true;
            return AdvancedSearchUtil.match(name, q);
        }
        String rest = q.substring(1).toLowerCase(Locale.ROOT).trim();
        switch (p) {
            case '@': // 按模组（沿用原有近似匹配：hay 含 "ns:path"，contains 即可）
                return name.contains(rest);
            case '#': { // 按标签
                String tags = curTags(i);
                return tags != null && tags.contains(rest);
            }
            case '$': { // 提示文本（流体/贴图无 tooltip 概念，退化为按名）
                if (mode == Mode.FLUID || mode == Mode.TEXTURE || isBookmarkFluidIndex(i)) return name.contains(rest);
                String tip = curTooltip(i);
                return tip != null && tip.contains(rest);
            }
            case '*': { // 仅按物品/流体 id（不含显示名）
                String id = curIdOnly(i);
                return id != null && id.contains(rest);
            }
            default:
                return AdvancedSearchUtil.match(name, q);
        }
    }

    /** 第 i 项的标签全名拼接（小写）。 */
    private String curTags(int i) {
        if (restrictedMode) return restrictedTags.get(i);
        return switch (mode) {
            case ITEM -> ALL_ITEM_TAGS.get(i);
            case INVENTORY -> invTags.get(i);
            case BOOKMARK -> bookmarkTags.get(i); // 物品+流体合并列表，见 buildBookmarks
            case FLUID -> ALL_FLUID_TAGS.get(i);
            case TEXTURE -> "";
        };
    }

    /** 第 i 项的提示文本（小写，仅物品）。全注册表走静态懒缓存，物品栏/受限模式列表小，会话内缓存即可。 */
    private String curTooltip(int i) {
        if (mode == Mode.FLUID || mode == Mode.TEXTURE || isBookmarkFluidIndex(i)) return null;
        if (!restrictedMode && mode == Mode.ITEM) return allItemTooltip(i);
        return tooltipCache.computeIfAbsent(i, idx -> tooltipOf(gridItem(idx)));
    }

    /** 第 i 项的物品/流体/贴图 id（不含显示名，小写）。 */
    private String curIdOnly(int i) {
        if (mode == Mode.FLUID || isBookmarkFluidIndex(i)) {
            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(curFluid(i));
            return id == null ? "" : id.toString().toLowerCase(Locale.ROOT);
        }
        if (mode == Mode.TEXTURE) {
            return ALL_TEXTURES.get(i).toString().toLowerCase(Locale.ROOT);
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(gridItem(i).getItem());
        return id == null ? "" : id.toString().toLowerCase(Locale.ROOT);
    }

    private int gridContentHeight() {
        if (gridModel.isEmpty()) return 0;
        PickerGridRow row = gridModel.get(gridModel.size() - 1);
        return row.getY() + row.getHeight();
    }

    private int gridMaxScroll() { return Math.max(0, gridContentHeight() - (gridBottom - gridY)); }
    private int scrollBarX() { return gridX + gridW + 2; }
    private int listTop() { return selY + 34; }
    private int selectionContentHeight() {
        int size = selectionHeights.size();
        return size == 0 ? 0 : selectionStarts[size - 1] + selectionHeights.get(size - 1);
    }
    private int selectionMaxScroll() { return Math.max(0, selectionContentHeight() - (gridBottom - listTop())); }
    private int confirmX() { return left + panelWidth - 102; }
    private int cancelX() { return confirmX() - 62; }
    private int clearAllX() { return selX + selW - 36; }
    private int syncAllX() { return selX + 2; }
    private int quantityY(int index) {
        return listTop() + selectionStarts[index] - shownSelScroll + selectionHeights.get(index) - 20 - QUICK_BLOCK_H;
    }
    private int quantityWidth() { return 118; }

    private static final float GRID_HEADER_Z = 300f;

    // 快速填数：每条已选项下方两排（减 / 加）从 1 到 1M 的十进位步长，符号用颜色与行首标记表示
    private static final long[] QUICK_STEPS = {1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L};
    private static final String[] QUICK_LABELS = {"1", "10", "100", "1K", "10K", "100K", "1M"};
    private static final int QUICK_H = 12;
    private static final int QUICK_GAP = 2;
    private static final int QUICK_SIGN_W = 12;
    private static final int QUICK_BLOCK_H = QUICK_H * 2 + 1 + 3;
    private static final int QUANTITY_ROW_H = 22;

    private int quickChipsX() { return selX + 2 + QUICK_SIGN_W + QUICK_GAP; }
    private int quickChipsW() { return selW - 4 - QUICK_SIGN_W - QUICK_GAP; }
    private int quickChipBaseW() { return (quickChipsW() - (QUICK_STEPS.length - 1) * QUICK_GAP) / QUICK_STEPS.length; }
    private int quickChipRemainder() { return (quickChipsW() - (QUICK_STEPS.length - 1) * QUICK_GAP) % QUICK_STEPS.length; }
    private int quickChipX(int chip) { return quickChipsX() + chip * (quickChipBaseW() + QUICK_GAP) + Math.min(chip, quickChipRemainder()); }
    private int quickChipW(int chip) { return quickChipBaseW() + (chip < quickChipRemainder() ? 1 : 0); }
    /** row 0 = 减，row 1 = 加。 */
    private int quickRowY(int index, int row) { return quantityY(index) + 18 + row * (QUICK_H + 1); }

    private void rebuildSelectionRows() {
        List<Integer> heights = new ArrayList<>();
        for (Sel entry : selected) {
            int lines = this.font.split(Component.literal(selectionName(entry)), Math.max(20, selW - 50)).size();
            heights.add(Math.max(22, lines * this.font.lineHeight + 6) + (entry.isTexture ? 2 : QUANTITY_ROW_H + QUICK_BLOCK_H));
        }
        selectionHeights = heights;
        selectionStarts = MultiPickerLayout.rowStarts(heights);
        selScroll = Math.min(selScroll, selectionMaxScroll());
    }

    private String selectionName(Sel entry) {
        return entry.isFluid ? fluidName(entry.fluid) : entry.isTexture ? entry.textureId.toString()
                : entry.itemIcon.getHoverName().getString();
    }

    private void cancelHold() {
        if (heldQuantity != null) ui.cancel();
        heldQuantity = null;
    }

    private void updateMotion(int mx, int my) {
        long now = System.currentTimeMillis();
        gridScroll = Math.max(0, Math.min(gridScroll, gridMaxScroll()));
        selScroll = Math.max(0, Math.min(selScroll, selectionMaxScroll()));
        if (draggedSelection != null && dragStarted) {
            int distance = my < listTop() + 20 ? -1 : my > gridBottom - 20 ? 1 : 0;
            if (distance != 0) {
                long elapsed = lastFrameMs == 0 ? 16 : Math.min(50, now - lastFrameMs);
                selScroll = Math.max(0, Math.min(selectionMaxScroll(), selScroll + distance * (int) Math.max(1, elapsed / 3)));
            }
        }
        boolean reduced = ClientShopUiSettings.reduceMotion();
        if (reduced) {
            gridMotion.snap(gridScroll);
            selectedMotion.snap(selScroll);
            opening.snap(1);
        } else {
            gridMotion.setTarget(gridScroll, now);
            selectedMotion.setTarget(selScroll, now);
            gridMotion.update(now);
            selectedMotion.update(now);
            opening.update(now);
        }
        int previous = shownGridScroll;
        shownGridScroll = Math.max(0, Math.min(gridMaxScroll(), Math.round(gridMotion.value())));
        shownSelScroll = Math.max(0, Math.min(selectionMaxScroll(), Math.round(selectedMotion.value())));
        gridVelocity = lastFrameMs == 0 || now == lastFrameMs ? 0 : (shownGridScroll - previous) * 1000f / Math.max(1, now - lastFrameMs);
        lastFrameMs = now;
        if (heldQuantity != null) {
            int index = selected.indexOf(heldQuantity);
            if (index < 0 || heldQuantityY != quantityY(index)) cancelHold();
        }
        if (editingQuantity != null) {
            int index = selected.indexOf(editingQuantity);
            if (index < 0 || quantityY(index) < listTop() || quantityY(index) + 16 > gridBottom) commitQuantity();
            else countBox.setY(quantityY(index));
        }
        if (draggedSelection != null && dragStarted)
            insertion = MultiPickerLayout.insertionIndex(selectionStarts, selectionHeights, (int) my - listTop() + shownSelScroll);
    }

    @Override
    public void renderBackground(GuiGraphics g) {
        try {
            if (parent != null) {
                g.pose().pushPose();
                try {
                    g.pose().translate(0, 0, -500);
                    parent.render(g, -1, -1, 0);
                } finally {
                    g.pose().popPose();
                }
            }
            else super.renderBackground(g);
        } catch (Exception ignored) {
            super.renderBackground(g);
        }
        if (!ShopGaussBlur.isReady()) g.fill(0, 0, this.width, this.height, 0xB0101018);
    }

    @Override
    public void resize(Minecraft mc, int width, int height) {
        if (parent != null) parent.init(mc, width, height);
        super.resize(mc, width, height);
    }

    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        updateMotion(mx, my);
        ui.frame(mx, my);
        if ((itemCacheReady && !itemCacheAppliedOnce)
                || !sourceLanguage.equals(Minecraft.getInstance().getLanguageManager().getSelected())) {
            itemCacheAppliedOnce = itemCacheReady;
            rebuildSource();
        }
        hoverItem = null;
        hoverFluidTip = null;
        hoverSelNameFull = null;
        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        String title = replacing ? (rowMode ? "编辑这一排" : "替换这一格") : restrictedMode ? "多选货币" : allowTexture ? "多选显示图标" : "多选资源";
        g.drawString(this.font, "§6" + title, left + 10, top + 7, GOLD, true);
        g.drawString(this.font, "§8点选、拖过连选；右侧调整数量与顺序", left + 10, top + 21, GRAY, false);
        if (originalSelection != null && replacing) {
            List<net.minecraft.util.FormattedCharSequence> originalLines = this.font.split(
                    Component.literal("§8原格：" + selectionName(originalSelection) + " ×" + originalSelection.count), selW);
            for (int line = 0; line < originalLines.size(); line++)
                g.drawString(this.font, originalLines.get(line), selX, top + 7 + line * this.font.lineHeight, GRAY, false);
        }
        int clearX = searchBox.getX() + searchBox.getWidth() + 2;
        if (!searchBox.getValue().isEmpty()) ui.draw(g, this.font, clearX, searchBox.getY(), 16, 16, "×", mx, my, true);
        g.drawString(this.font, "§7匹配 " + filtered.size() + " / " + curNames().size(), selX, searchBox.getY() + 4, GRAY, false);
        drawSidebar(g, mx, my);
        drawGrid(g, mx, my);
        drawGridScrollbar(g, mx, my);
        drawSelectedList(g, mx, my);
        String hint = sorting ? "§e排序中…" : GuiRenderUtil.isHovering(mx, my, selX, listTop(), selW, gridBottom - listTop())
                ? "§8拖名字排序 · 数量滚轮/Shift/Ctrl" : "§8左拖加入 · 右拖移除 · Shift一组";
        g.drawString(this.font, hint, left + 10, top + panelHeight - 20, GRAY, false);
        ui.draw(g, this.font, confirmX(), top + panelHeight - 24, 90, 16, confirmLabel(), mx, my, true);
        ui.draw(g, this.font, cancelX(), top + panelHeight - 24, 56, 16, "§c取消", mx, my, true);
    }

    private List<Mode> availableModes() {
        if (restrictedMode) return List.of();
        return allowTexture ? List.of(Mode.ITEM, Mode.INVENTORY, Mode.BOOKMARK, Mode.FLUID, Mode.TEXTURE)
                : List.of(Mode.ITEM, Mode.INVENTORY, Mode.BOOKMARK, Mode.FLUID);
    }

    private String modeLabel(Mode value) {
        return switch (value) {
            case ITEM -> "物品";
            case INVENTORY -> "物品栏";
            case BOOKMARK -> "JEI书签";
            case FLUID -> "流体";
            case TEXTURE -> "贴图";
        };
    }

    private int modeCount(Mode value) {
        return switch (value) {
            case ITEM -> restrictedMode ? restrictedStacks.size() : itemCacheReady ? ALL_ITEMS.size() : 0;
            case INVENTORY -> invStacks.size();
            case BOOKMARK -> bookmarkNames.size();
            case FLUID -> ALL_FLUIDS == null ? 0 : ALL_FLUIDS.size();
            case TEXTURE -> ALL_TEXTURES == null ? 0 : ALL_TEXTURES.size();
        };
    }

    private List<String> sortOptions() {
        return mode == Mode.INVENTORY ? List.of("default", "name", "mod", "id", "count") : List.of("default", "name", "mod", "id");
    }

    private String sortLabel(String sort) {
        return switch (sort) {
            case "name" -> "名称";
            case "mod" -> "模组";
            case "id" -> "ID";
            case "count" -> "数量";
            default -> "默认";
        };
    }

    private void drawSidebar(GuiGraphics g, int mx, int my) {
        int y = gridY;
        for (Mode value : availableModes()) {
            int modeX = sidebarX;
            ui.draw(g, this.font, modeX, y, sidebarW, 18,
                    (mode == value ? "§e" : "§7") + modeLabel(value) + " " + modeCount(value), mx, my, true);
            y += 21;
        }
        sortY = y + 4;
        groupY = sortY + 24;
        modulesY = groupY + 26;
        ui.draw(g, this.font, sidebarX, sortY, sidebarW, 18, "排序: " + sortLabel(pickerSort) + " ▾", mx, my, true);
        ui.draw(g, this.font, sidebarX, groupY, sidebarW, 18, (groupByMod ? "§a☑ " : "§7☐ ") + "按模组分区", mx, my, true);
        clip(g, sidebarX, modulesY, sidebarW, gridBottom - modulesY);
        try {
            for (int index = 0; index < moduleHeights.size(); index++) {
                int ry = modulesY + moduleStarts[index] - moduleScroll;
                int height = moduleHeights.get(index);
                if (ry + height <= modulesY || ry >= gridBottom) continue;
                String id = index == 0 ? "" : moduleOrder.get(index - 1);
                String label = index == 0 ? "全部模组" : moduleNames.getOrDefault(id, id);
                int count = index == 0 ? curNames().size() : moduleCounts.getOrDefault(id, 0);
                if (id.equals(moduleFilter)) g.fill(sidebarX, ry, sidebarX + sidebarW, ry + height, SEL_ACTIVE);
                List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(Component.literal(label), moduleLabelWidth());
                for (int line = 0; line < lines.size(); line++)
                    g.drawString(this.font, lines.get(line), sidebarX + 2, ry + 3 + line * this.font.lineHeight, WHITE, false);
                String countText = String.valueOf(count);
                g.drawString(this.font, countText, sidebarX + sidebarW - 3 - this.font.width(countText), ry + 3, GRAY, false);
                if (GuiRenderUtil.isHovering(mx, my, sidebarX, ry, sidebarW, height))
                    hoverFluidTip = List.of(Component.literal(label + " (" + count + ")"));
            }
        } finally { g.disableScissor(); }
    }

    private void clip(GuiGraphics g, int x, int y, int width, int height) {
        g.enableScissor(Math.round(x * guiScale) + offsetX, Math.round(y * guiScale) + offsetY,
                Math.round((x + width) * guiScale) + offsetX, Math.round((y + height) * guiScale) + offsetY);
    }

    private void drawGrid(GuiGraphics g, int mx, int my) {
        if (!restrictedMode && mode == Mode.ITEM && !itemCacheReady) {
            g.drawCenteredString(this.font, "§e正在后台构建物品列表…", gridX + gridW / 2, gridY + 30, GOLD);
            return;
        }
        clip(g, gridX - 2, gridY, gridW + 3, gridBottom - gridY);
        try {
            for (PickerGridRow row : gridModel) {
                int y = gridY + row.getY() - shownGridScroll;
                if (y + row.getHeight() <= gridY || y >= gridBottom) continue;
                if (row.getHeader()) drawGridHeader(g, row, y);
                else for (int column = 0; column < row.getIndices().size(); column++)
                    drawGridSlot(g, row.getIndices().get(column), gridX + column * PITCH, y, mx, my);
            }
            int sticky = MultiPickerLayout.stickyHeader(gridModel, shownGridScroll);
            if (sticky >= 0) {
                int height = gridModel.get(sticky).getHeight();
                int y = gridY;
                for (int index = sticky + 1; index < gridModel.size(); index++) {
                    PickerGridRow next = gridModel.get(index);
                    if (next.getHeader()) {
                        y = Math.min(y, gridY + next.getY() - shownGridScroll - height);
                        break;
                    }
                }
                drawGridHeader(g, gridModel.get(sticky), y);
            }
        } finally { g.disableScissor(); }
    }

    private void drawGridHeader(GuiGraphics g, PickerGridRow row, int y) {
        // renderItem 把图标放在 z≈150，吸顶标题晚于格子绘制，不抬高 z 的话滚到标题下的图标会盖在字上
        g.pose().pushPose();
        try {
            g.pose().translate(0, 0, GRID_HEADER_Z);
            g.fill(gridX, y, gridX + gridW, y + row.getHeight(), 0xFF302C24);
            String id = row.getModule();
            String label = (collapsedModules.contains(id) ? "▸ " : "▾ ") + moduleNames.getOrDefault(id, id)
                    + " (" + filteredModuleCounts.getOrDefault(id, 0) + ")";
            List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(Component.literal(label), Math.max(1, gridW - 8));
            for (int line = 0; line < lines.size(); line++)
                g.drawString(this.font, lines.get(line), gridX + 2, y + 4 + line * this.font.lineHeight, GOLD, false);
        } finally { g.pose().popPose(); }
    }

    private int stickyHeaderHeight() {
        int index = MultiPickerLayout.stickyHeader(gridModel, shownGridScroll);
        return index < 0 ? 0 : gridModel.get(index).getHeight();
    }

    private void drawGridSlot(GuiGraphics g, int index, int x, int y, int mx, int my) {
        boolean hover = GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT) && my >= gridY && my < gridBottom
                && (!groupByMod || my >= gridY + stickyHeaderHeight());
        Spring spring = slotHover.computeIfAbsent(index, ignored -> new Spring(Spring.HOVER));
        long now = System.currentTimeMillis();
        if (ClientShopUiSettings.reduceMotion()) spring.snap(hover ? 1 : 0);
        else { spring.setTarget(hover ? 1 : 0, now); spring.update(now); }
        int selectedIndex = findSelected(index);
        Sel entry = selectedIndex >= 0 ? selected.get(selectedIndex) : null;
        float flash = addFlash(entry, now);
        if (flash > 0) g.fill(x - 1, y - 1, x + SLOT + 1, y + SLOT + 1, (Math.round(160 * flash) << 24) | 0xFFD040);
        g.pose().pushPose();
        try {
            float scale = 1 + spring.value() * 0.18f;
            g.pose().translate(x + 10, y + 10, hover ? 50 : 0);
            g.pose().scale(scale, scale, 1);
            g.pose().translate(-x - 10, -y - 10, 0);
            EditorWidgets.checkerSlot(g, x, y, hover);
            if (mode == Mode.FLUID || isBookmarkFluidIndex(index)) EditorWidgets.fluidIcon(g, x + 2, y + 2, 16, curFluid(index));
            else if (mode == Mode.TEXTURE) EditorWidgets.textureThumb(g, x + 2, y + 2, 16, ALL_TEXTURES.get(index));
            else {
                ItemStack stack = gridItem(index);
                g.renderItem(stack, x + 2, y + 2);
                if (stack.hasTag()) g.fill(x + 2, y + 15, x + 5, y + 18, 0xFFFFD000);
            }
            if (entry != null) {
                g.drawString(this.font, "§a✔", x + 12, y, WHITE, false);
                String compact = entry.count >= 1_000_000 ? entry.count / 1_000_000 + "M" : entry.count >= 1_000 ? entry.count / 1_000 + "k" : Long.toString(entry.count);
                g.pose().pushPose();
                g.pose().translate(x + 1, y + 14, 100);
                g.pose().scale(0.65f, 0.65f, 1);
                g.drawString(this.font, compact, 0, 0, CYAN, true);
                g.pose().popPose();
            }
        } finally { g.pose().popPose(); }
        if (hover) {
            if (mode == Mode.FLUID || isBookmarkFluidIndex(index)) hoverFluidTip = List.of(Component.literal(fluidName(curFluid(index))));
            else if (mode == Mode.TEXTURE) hoverFluidTip = List.of(Component.literal(ALL_TEXTURES.get(index).toString()));
            else hoverItem = gridItem(index);
        }
    }

    private void drawGridScrollbar(GuiGraphics g, int mx, int my) {
        ui.drawScrollbar(g, scrollBarX(), gridY, gridBottom - gridY, gridContentHeight(),
                gridBottom - gridY, shownGridScroll, gridMaxScroll(), mx, my);
    }

    private void drawSelectedList(GuiGraphics g, int mx, int my) {
        g.fill(selX - 2, selY - 2, selX + selW + 6, gridBottom + 1, PANEL_BG);
        g.drawString(this.font, "§6已选清单 (" + selected.size() + ")", selX + 2, selY + 1, GOLD, false);
        ui.draw(g, this.font, syncAllX(), selY + 14, selW - 42, 16,
                "§b全部设为 " + (active == null ? 1 : active.count), mx, my, active != null);
        ui.draw(g, this.font, clearAllX(), selY + 14, 36, 16, "§c清空", mx, my, !selected.isEmpty());
        clip(g, selX, listTop(), selW, gridBottom - listTop());
        try {
            for (int index = 0; index < selected.size(); index++) {
                Sel entry = selected.get(index);
                int y = listTop() + selectionStarts[index] - shownSelScroll;
                int height = selectionHeights.get(index);
                if (y + height <= listTop() || y >= gridBottom) continue;
                boolean hover = GuiRenderUtil.isHovering(mx, my, selX, Math.max(listTop(), y), selW, Math.min(height, gridBottom - y));
                float glow = ui.rowHover(index, hover);
                if (entry == active) g.fill(selX, y, selX + selW, y + height - 1, SEL_ACTIVE);
                else if (glow > 0) g.fill(selX, y, selX + selW, y + height - 1, GuiMotion.lerpColor(0, 0x33FFFFFF, glow));
                float flash = addFlash(entry, System.currentTimeMillis());
                if (flash > 0) g.fill(selX, y, selX + selW, y + height - 1, (Math.round(90 * flash) << 24) | 0xFFD040);
                drawSelectionIcon(g, entry, selX + 2, y + 3);
                drawListName(g, "§f" + selectionName(entry), selX + 24, y + 3, selW - 50, hover);
                if (entry == originalSelection) g.drawString(this.font, "§6原", selX + 2, y + 20, GOLD, false);
                ui.draw(g, this.font, selX + selW - 18, y + 2, 16, 14, "×", mx, my, true);
                if (!entry.isTexture) drawQuantityRow(g, entry, quantityY(index), mx, my);
                g.fill(selX, y + height - 1, selX + selW, y + height, 0x30606060);
            }
            if (selected.isEmpty()) {
                drawListName(g, "§8点左侧格子加入 · 拖过可连选 · 右键移除", selX + 16, listTop() + 24, selW - 32, false);
            }
            if (draggedSelection != null && dragStarted) {
                int y = listTop() + (insertion >= selectionStarts.length ? selectionContentHeight() : selectionStarts[insertion]) - shownSelScroll;
                g.fill(selX, y - 1, selX + selW, y + 1, 0xFFFFD040);
            }
        } finally { g.disableScissor(); }
        ui.drawScrollbar(g, selX + selW + 2, listTop(), gridBottom - listTop(), selectionContentHeight(),
                gridBottom - listTop(), shownSelScroll, selectionMaxScroll(), mx, my);
    }

    private void drawSelectionIcon(GuiGraphics g, Sel entry, int x, int y) {
        if (entry.isFluid) EditorWidgets.fluidIcon(g, x, y, 16, entry.fluid);
        else if (entry.isTexture) EditorWidgets.textureThumb(g, x, y, 16, entry.textureId);
        else g.renderItem(entry.itemIcon, x, y);
    }

    private void drawQuantityRow(GuiGraphics g, Sel entry, int y, int mx, int my) {
        int minusX = selX + 24;
        int plusX = minusX + 20 + quantityWidth();
        ui.draw(g, this.font, minusX, y, 16, 16, "−", mx, my, true);
        ui.draw(g, this.font, plusX, y, 16, 16, "+", mx, my, true);
        if (entry != editingQuantity) {
            g.fill(minusX + 19, y, plusX - 3, y + 16, 0x50202020);
            g.drawCenteredString(this.font, Long.toString(entry.count), (minusX + 19 + plusX - 3) / 2, y + 4, CYAN);
        }
        String unit = entry.isFluid ? "mB" : entry.itemIcon.hasTag() ? "NBT" : "个";
        g.drawString(this.font, unit, plusX + 19, y + 4, GRAY, false);
        for (int row = 0; row < 2; row++) {
            int chipY = y + 18 + row * (QUICK_H + 1);
            String color = row == 0 ? "§c" : "§a";
            g.drawCenteredString(this.font, color + (row == 0 ? "−" : "+"), selX + 2 + QUICK_SIGN_W / 2, chipY + (QUICK_H - 8) / 2, WHITE);
            for (int chip = 0; chip < QUICK_STEPS.length; chip++) {
                boolean enabled = row == 1 || entry.count > 1;
                ui.draw(g, this.font, quickChipX(chip), chipY, quickChipW(chip), QUICK_H,
                        (enabled ? color : "§8") + QUICK_LABELS[chip], mx, my, enabled);
            }
        }
    }

    private PixelRect toPixels(GuiRect rect) {
        Minecraft mc = Minecraft.getInstance();
        GuiRect affine = GlassGeometry.applyAffine(rect, guiScale, guiScale, offsetX, offsetY);
        return GlassGeometry.toPixels(affine, mc.getMainRenderTarget().width, mc.getMainRenderTarget().height, this.width, this.height);
    }

    @Override
    protected void renderTooltips(GuiGraphics g, int smx, int smy, int mx, int my) {
        ShopGaussBlur.clear();
        ShopGaussBlur.clearTopLayer();
        Minecraft mc = Minecraft.getInstance();
        if (ShopGaussBlur.isReady()) {
            PixelRect grid = toPixels(new GuiRect(gridX, gridY, gridW, gridBottom - gridY));
            float swap = Math.max(0, 1 - (System.currentTimeMillis() - modeChangedAtMs) / 180f);
            float strength = ClientShopUiSettings.reduceMotion() ? 0 : Math.min(0.8f, Math.max(Math.abs(gridVelocity) / 1800f - 0.08f, swap * 0.65f));
            if (strength > 0.01f) ShopGaussBlur.requestDirectional(grid.getX(), grid.getY(), grid.getW(), grid.getH(),
                    strength, 8 * guiScale, 0, 1, ShopGlassStyle.SWAP);
            PixelRect panel = toPixels(new GuiRect(left, top, panelWidth, panelHeight));
            ShopGaussBlur.requestExtra(0, 0, mc.getMainRenderTarget().width, mc.getMainRenderTarget().height,
                    Math.min(1, opening.value()) * 0.85f, 12 * guiScale,
                    panel.getX(), panel.getY(), panel.getW(), panel.getH(), ShopGlassStyle.OVERLAY);
        }
        ShopGaussBlur.deferTopLayer(this, topGraphics -> drawTopLayer(topGraphics, smx, smy, mx, my));
    }

    private void drawTopLayer(GuiGraphics g, int smx, int smy, int mx, int my) {
        g.pose().pushPose();
        try {
            g.pose().translate(offsetX, offsetY, 500);
            g.pose().scale(guiScale, guiScale, 1);
            if (sortOpen) {
                int y = sortY + 20;
                for (String option : sortOptions()) {
                    ui.draw(g, this.font, sidebarX, y, sidebarW, 18, sortLabel(option), smx, smy, true);
                    y += 20;
                }
            }
            if (draggedSelection != null && dragStarted) {
                g.fill(smx + 8, smy + 8, smx + 32, smy + 32, 0xD0303028);
                drawSelectionIcon(g, draggedSelection, smx + 12, smy + 12);
            }
        } finally { g.pose().popPose(); }
        if (sortOpen || draggedSelection != null && dragStarted) return;
        if (hoverItem != null && !hoverItem.isEmpty()) g.renderTooltip(this.font, hoverItem, mx, my);
        else if (hoverFluidTip != null) g.renderComponentTooltip(this.font, hoverFluidTip, mx, my);
        else if (GuiRenderUtil.isHovering(smx, smy, searchBox.getX(), searchBox.getY(), searchBox.getWidth(), searchBox.getHeight()))
            g.renderComponentTooltip(this.font, SEARCH_HELP, mx, my);
    }

    private static String fluidName(Fluid fluid) {
        return FluidStack.create(fluid, 1000).getName().getString();
    }

    private void changeMode(Mode next) {
        commitQuantity();
        cancelGestures();
        mode = next;
        if (mode == Mode.TEXTURE) ensureTextureCache();
        moduleFilter = "";
        collapsedModules.clear();
        modeChangedAtMs = System.currentTimeMillis();
        rebuildSource();
    }

    private void changePreferences() {
        commitQuantity();
        cancelGestures();
        sortGeneration++;
        pendingSortKey = "";
        sorting = false;
        filterResultCache.clear();
        ClientShopUiSettings.setPickerPreferences(pickerSort, groupByMod);
        modeChangedAtMs = System.currentTimeMillis();
        rebuildFilter();
    }

    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        if (btn != 0 && btn != 1) return super.universalMouseClicked(mx, my, btn);
        cancelHold();
        if (sortOpen) {
            if (btn == 0) {
                int index = (int) (my - sortY - 20) / 20;
                if (my >= sortY + 20 && mx >= sidebarX && mx < sidebarX + sidebarW && index < sortOptions().size()) {
                    pickerSort = sortOptions().get(index);
                    changePreferences();
                }
            }
            sortOpen = false;
            return true;
        }
        if (editingQuantity != null && !GuiRenderUtil.isHovering(mx, my, countBox.getX(), countBox.getY(), countBox.getWidth(), countBox.getHeight()))
            commitQuantity();
        if (btn == 0) {
            if (ui.hit(mx, my, confirmX(), top + panelHeight - 24, 90, 16, true)) { confirm(); return true; }
            if (ui.hit(mx, my, cancelX(), top + panelHeight - 24, 56, 16, true)) { onClose(); return true; }
            if (ui.hit(mx, my, searchBox.getX() + searchBox.getWidth() + 2, searchBox.getY(), 16, 16, !searchBox.getValue().isEmpty())) {
                searchBox.setValue("");
                setFocused(searchBox);
                return true;
            }
            int y = gridY;
            for (Mode value : availableModes()) {
                if (ui.hit(mx, my, sidebarX, y, sidebarW, 18, true)) { changeMode(value); return true; }
                y += 21;
            }
            if (ui.hit(mx, my, sidebarX, sortY, sidebarW, 18, true)) { cancelHold(); sortOpen = true; return true; }
            if (ui.hit(mx, my, sidebarX, groupY, sidebarW, 18, true)) { groupByMod = !groupByMod; changePreferences(); return true; }
            if (GuiRenderUtil.isHovering(mx, my, sidebarX, modulesY, sidebarW, gridBottom - modulesY)) {
                int index = moduleRowAt(my);
                if (index >= 0 && index < moduleHeights.size()) {
                    moduleFilter = index == 0 ? "" : moduleOrder.get(index - 1);
                    commitQuantity();
                    rebuildFilter();
                }
                return true;
            }
            if (ui.hit(mx, my, syncAllX(), selY + 14, selW - 42, 16, active != null)) { syncAllCounts(); return true; }
            if (ui.hit(mx, my, clearAllX(), selY + 14, 36, 16, !selected.isEmpty())) { clearAll(); return true; }
            if (gridScrollbarClicked(mx, my)) return true;
            int first = ui.beginDrag(mx, my, selX + selW + 2, listTop(), gridBottom - listTop(),
                    selectionContentHeight(), gridBottom - listTop(), selectionMaxScroll());
            if (first >= 0) { scrollbarOwner = 2; selScroll = first; return true; }
            if (selListClicked(mx, my, btn)) return true;
        }
        if (gridClicked(mx, my, btn)) return true;
        return super.universalMouseClicked(mx, my, btn);
    }

    private void clearAll() {
        commitQuantity();
        cancelGestures();
        selected.clear();
        setActive(null);
        selScroll = shownSelScroll = 0;
        selectedMotion.snap(0);
        rebuildSelectionRows();
    }

    private void syncAllCounts() {
        commitQuantity();
        cancelHold();
        if (active != null) selection.setAll(active.count);
    }

    private boolean gridScrollbarClicked(double mx, double my) {
        int first = ui.beginDrag(mx, my, scrollBarX(), gridY, gridBottom - gridY,
                gridContentHeight(), gridBottom - gridY, gridMaxScroll());
        if (first < 0) return false;
        scrollbarOwner = 1;
        gridScroll = first;
        return true;
    }

    private void cancelGestures() {
        cancelHold();
        scrollbarOwner = 0;
        gridGesture = false;
        gestureButton = -1;
        gestureOrigin = -1;
        draggedSelection = null;
        dragStarted = false;
        dragVisited.clear();
    }

    @Override
    protected boolean universalMouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (scrollbarOwner != 0) {
            int first = scrollbarOwner == 1
                    ? ui.drag(my, gridY, gridBottom - gridY, gridContentHeight(), gridBottom - gridY, gridMaxScroll())
                    : ui.drag(my, listTop(), gridBottom - listTop(), selectionContentHeight(), gridBottom - listTop(), selectionMaxScroll());
            if (first >= 0) {
                if (scrollbarOwner == 1) gridScroll = first; else selScroll = first;
                return true;
            }
        }
        if (btn == gestureButton && (gridGesture || draggedSelection != null)) {
            if (!dragStarted && Math.hypot(mx - gestureX, my - gestureY) > 3) {
                dragStarted = true;
                commitQuantity();
                cancelHold();
                if (gridGesture && btn == 1) {
                    int existing = findSelected(gestureOrigin);
                    if (existing >= 0) removeSelected(existing);
                }
            }
            if (dragStarted && gridGesture) {
                int index = gridIndexAt(mx, my);
                if (index >= 0 && dragVisited.add(index)) {
                    if (btn == 1) { int existing = findSelected(index); if (existing >= 0) removeSelected(existing); }
                    else if (findSelected(index) < 0) onGridPick(index, false);
                }
            }
            return true;
        }
        return super.universalMouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    protected boolean universalMouseReleased(double mx, double my, int btn) {
        ui.release();
        if (gridGesture && btn == gestureButton && btn == 1 && !dragStarted)
            onGridPick(gestureOrigin, true);
        if (draggedSelection != null && dragStarted) {
            int from = selected.indexOf(draggedSelection);
            int target = MultiPickerLayout.insertionIndex(selectionStarts, selectionHeights, (int) my - listTop() + shownSelScroll);
            selection.move(from, target > from ? target - 1 : target);
            rebuildSelectionRows();
            ensureSelectedVisible(draggedSelection);
        }
        cancelGestures();
        return super.universalMouseReleased(mx, my, btn);
    }

    private int gridRowAt(double my) {
        if (my < gridY || my >= gridBottom) return -1;
        if (groupByMod && my < gridY + stickyHeaderHeight()) return MultiPickerLayout.stickyHeader(gridModel, shownGridScroll);
        return MultiPickerLayout.rowAtY(gridModel, (int) my - gridY + shownGridScroll);
    }

    private int gridIndexAt(double mx, double my) {
        if (mx < gridX || mx >= gridX + gridW) return -1;
        int rowIndex = gridRowAt(my);
        if (rowIndex < 0) return -1;
        PickerGridRow row = gridModel.get(rowIndex);
        if (row.getHeader()) return -1;
        int x = (int) mx - gridX;
        int y = (int) my - gridY + shownGridScroll - row.getY();
        int column = x / PITCH;
        return x % PITCH < SLOT && y >= 0 && y < SLOT && column < row.getIndices().size() ? row.getIndices().get(column) : -1;
    }

    private boolean gridClicked(double mx, double my, int btn) {
        if (!GuiRenderUtil.isHovering(mx, my, gridX, gridY, gridW, gridBottom - gridY)) return false;
        int rowIndex = gridRowAt(my);
        if (rowIndex >= 0 && gridModel.get(rowIndex).getHeader()) {
            if (btn == 0) {
                String module = gridModel.get(rowIndex).getModule();
                if (!collapsedModules.add(module)) collapsedModules.remove(module);
                rebuildGridRows();
            }
            return true;
        }
        int index = gridIndexAt(mx, my);
        if (index < 0) return true;
        if (btn == 0 && Screen.hasControlDown() && Screen.hasShiftDown()) {
            addInfinityCellFromGrid(index);
            return true;
        }
        if (btn == 0) onGridPick(index, false);
        gestureX = mx;
        gestureY = my;
        gestureButton = btn;
        gestureOrigin = index;
        gridGesture = true;
        dragStarted = false;
        dragVisited.clear();
        dragVisited.add(index);
        return true;
    }

    private void onGridPick(int index, boolean rightClick) {
        int existing = findSelected(index);
        if (rightClick && existing >= 0) { removeSelected(existing); return; }
        if (existing < 0) addFromGrid(index);
        else { setActive(selected.get(existing)); markAdded(active); ensureSelectedVisible(active); }
        if (!rightClick && Screen.hasShiftDown() && active != null && !active.isTexture)
            active.setCount(active.isFluid ? 1000 : 64);
    }

    private static boolean sameSelection(Sel first, Sel second) {
        if (first.isFluid != second.isFluid || first.isTexture != second.isTexture) return false;
        if (first.isFluid || first.isTexture) return java.util.Objects.equals(first.id, second.id);
        return ItemStack.isSameItemSameTags(first.itemIcon, second.itemIcon);
    }

    private void addFromGrid(int index) {
        Sel entry;
        if (mode == Mode.FLUID || isBookmarkFluidIndex(index)) {
            Fluid fluid = curFluid(index);
            entry = new Sel(true, ForgeRegistries.FLUIDS.getKey(fluid), null, fluid, 1000L);
        } else if (mode == Mode.TEXTURE) entry = Sel.texture(ALL_TEXTURES.get(index));
        else {
            ItemStack prototype = gridItem(index).copy();
            prototype.setCount(1);
            long count = mode == Mode.INVENTORY && index < invCounts.size() ? Math.max(1, invCounts.get(index)) : 1;
            entry = new Sel(false, ForgeRegistries.ITEMS.getKey(prototype.getItem()), prototype, null, count);
        }
        entry = selection.add(entry, MultiPickerScreen::sameSelection);
        rebuildSelectionRows();
        setActive(entry);
        markAdded(entry);
        ShopUiSounds.click();
        ensureSelectedVisible(entry);
    }

    /** 記下剛加入的那一項，清單列和網格格子亮一下再淡掉。 */
    private void markAdded(Sel s) {
        lastAdded = s;
        lastAddedAtMs = System.currentTimeMillis();
    }

    /** 剛加入那一項的閃光強度 0..1；跟它無關的項一直是 0。 */
    private float addFlash(Sel s, long now) {
        if (s != lastAdded || lastAddedAtMs <= 0L) return 0f;
        if (ClientShopUiSettings.reduceMotion()) return 0f;
        float t = GuiMotion.clamp01((now - lastAddedAtMs) / 600f);
        return t >= 1f ? 0f : 1f - GuiRenderUtil.easeOut(t);
    }

    /**
     * Ctrl+Shift+左键网格：不走常规加入/选中，直接把「指向该资源的无限胞」（expatternprovider:infinity_cell，
     * record NBT 绑定物品/流体 id，构造方式对照 JEI 内 Ctrl+左键"作弊获取无限胞"，见 JeiCopyShortcutHelper#
     * buildInfinityCellTag）写入已选清单——不走 JEI 的作弊给予协议，纯本地构造一个带 NBT 的真实物品条目，
     * 跟"物品栏模式取实物"复用同一条已选清单管线。贴图模式没有对应资源 id，直接跳过；ExtendedAE 未装
     * （registry 查不到该物品）时提示一句，不写入。
     */
    private void addInfinityCellFromGrid(int resIdx) {
        if (mode == Mode.TEXTURE) return;
        Item cellItem = INFINITY_CELL_ID == null ? null : ForgeRegistries.ITEMS.getValue(INFINITY_CELL_ID);
        if (cellItem == null) {
            showClientMessage("§c[山海] 未找到无限胞物品（需要 ExtendedAE），无法写入已选清单");
            return;
        }

        net.minecraft.nbt.CompoundTag record = new net.minecraft.nbt.CompoundTag();
        String displayName;
        if (mode == Mode.FLUID || isBookmarkFluidIndex(resIdx)) {
            Fluid f = curFluid(resIdx);
            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(f);
            if (id == null) return;
            record.putString("#c", "ae2:f");
            record.putString("id", id.toString());
            displayName = fluidName(f);
        } else {
            ItemStack proto = gridItem(resIdx);
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(proto.getItem());
            if (id == null) return;
            record.putString("#c", "ae2:i");
            record.putString("id", id.toString());
            displayName = proto.getHoverName().getString();
        }
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.put("record", record);
        ItemStack cellStack = new ItemStack(cellItem);
        cellStack.setTag(tag);

        Sel s = new Sel(false, ForgeRegistries.ITEMS.getKey(cellItem), cellStack, null, 1L);
        s = selection.add(s, MultiPickerScreen::sameSelection);
        rebuildSelectionRows();
        setActive(s);
        markAdded(s);
        ensureSelectedVisible(s);
        showClientMessage("§a[山海] 已写入已选清单: §e" + cellStack.getHoverName().getString() + "§7（" + displayName + "）");
    }

    private static void showClientMessage(String message) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal(message), true);
    }

    private void drawListName(GuiGraphics g, String text, int x, int y, int width, boolean rowHover) {
        List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(Component.literal(text), width);
        for (int index = 0; index < lines.size(); index++)
            g.drawString(this.font, lines.get(index), x, y + index * this.font.lineHeight, WHITE, true);
    }

    private boolean selListClicked(double mx, double my, int btn) {
        if (!GuiRenderUtil.isHovering(mx, my, selX, listTop(), selW, gridBottom - listTop())) return false;
        int index = MultiPickerLayout.selectionRowAt(selectionStarts, selectionHeights, (int) my - listTop() + shownSelScroll);
        if (index < 0) return true;
        Sel entry = selected.get(index);
        int y = listTop() + selectionStarts[index] - shownSelScroll;
        if (ui.hit(mx, my, selX + selW - 18, y + 2, 16, 14, true)) { removeSelected(index); return true; }
        if (!entry.isTexture) {
            int quantityY = quantityY(index);
            int minusX = selX + 24;
            int plusX = minusX + 20 + quantityWidth();
            if (GuiRenderUtil.isHovering(mx, my, minusX, quantityY, 16, 16)) { startQuantityRepeat(entry, -1, quantityY); return true; }
            if (GuiRenderUtil.isHovering(mx, my, plusX, quantityY, 16, 16)) { startQuantityRepeat(entry, 1, quantityY); return true; }
            if (GuiRenderUtil.isHovering(mx, my, minusX + 19, quantityY, quantityWidth() - 3, 16)) {
                if (editingQuantity != entry) beginQuantity(entry, quantityY);
                else countBox.mouseClicked(mx, my, btn);
                return true;
            }
            for (int row = 0; row < 2; row++) {
                int chipY = quickRowY(index, row);
                for (int chip = 0; chip < QUICK_STEPS.length; chip++) {
                    boolean enabled = row == 1 || entry.count > 1;
                    if (!ui.hit(mx, my, quickChipX(chip), chipY, quickChipW(chip), QUICK_H, enabled)) continue;
                    if (enabled) quickStep(entry, row == 0 ? -QUICK_STEPS[chip] : QUICK_STEPS[chip]);
                    return true;
                }
            }
        }
        setActive(entry);
        ui.select();
        if (my < y + selectionHeights.get(index) - (entry.isTexture ? 0 : QUANTITY_ROW_H + QUICK_BLOCK_H)) {
            gestureX = mx;
            gestureY = my;
            gestureButton = btn;
            draggedSelection = entry;
            dragStarted = false;
        }
        return true;
    }

    private void stepQuantity(Sel entry, long delta, int tick) {
        selection.step(entry, delta);
        ShopUiSounds.step(tick);
    }

    /** 快速填数：先收掉正在编辑的输入框，再把这一项设为当前项并加减；减到 1 为止。 */
    private void quickStep(Sel entry, long delta) {
        commitQuantity();
        setActive(entry);
        stepQuantity(entry, delta, 0);
    }

    private long quantityStep() {
        return Screen.hasControlDown() ? Screen.hasShiftDown() ? 1000 : 100 : Screen.hasShiftDown() ? 10 : 1;
    }

    private void startQuantityRepeat(Sel entry, int sign, int y) {
        commitQuantity();
        setActive(entry);
        ui.press(selX + 24 + (sign > 0 ? 20 + quantityWidth() : 0), y, 16, 16, true);
        heldQuantity = entry;
        heldQuantityY = y;
        long delta = quantityStep() * sign;
        stepQuantity(entry, delta, 0);
        ui.holdRepeat(RepeatCurve.STEPPER, tick -> {
            int index = selected.indexOf(entry);
            if (heldQuantity == entry && index >= 0 && quantityY(index) == heldQuantityY) {
                stepQuantity(entry, delta, tick);
                return true;
            }
            return false;
        });
    }

    private void beginQuantity(Sel entry, int y) {
        commitQuantity();
        cancelHold();
        active = entry;
        editingQuantity = entry;
        countBox.setX(selX + 43);
        countBox.setY(y);
        countBox.setWidth(quantityWidth() - 3);
        countBox.setValue(Long.toString(entry.count));
        countBox.visible = true;
        countBox.active = true;
        setFocused(countBox);
        countBox.setFocused(true);
        countBox.setHighlightPos(0);
    }

    private void commitQuantity() {
        if (editingQuantity == null || countBox == null) return;
        if (selected.contains(editingQuantity)) editingQuantity.setCount(parseLong(countBox.getValue()));
        editingQuantity = null;
        countBox.visible = false;
        countBox.active = false;
        countBox.setFocused(false);
        if (getFocused() == countBox) setFocused(null);
    }

    private void ensureSelectedVisible(Sel entry) {
        int index = selected.indexOf(entry);
        if (index < 0 || index >= selectionStarts.length) return;
        int start = selectionStarts[index];
        int end = start + selectionHeights.get(index);
        if (start < selScroll) selScroll = start;
        else if (end > selScroll + gridBottom - listTop())
            selScroll = Math.min(start, end - (gridBottom - listTop()));
        selScroll = Math.max(0, Math.min(selectionMaxScroll(), selScroll));
        if (ClientShopUiSettings.reduceMotion()) {
            selectedMotion.snap(selScroll);
            shownSelScroll = selScroll;
        }
    }

    private void removeSelected(int index) {
        if (index < 0 || index >= selected.size()) return;
        commitQuantity();
        cancelHold();
        Sel entry = selected.get(index);
        selection.remove(entry);
        if (active == entry) setActive(selected.isEmpty() ? null : selected.get(Math.min(index, selected.size() - 1)));
        if (lastAdded == entry) lastAdded = null;
        rebuildSelectionRows();
    }

    private void setActive(Sel entry) {
        if (active != entry) { commitQuantity(); cancelHold(); }
        active = entry;
    }

    private int findSelected(int index) {
        if (mode == Mode.FLUID || isBookmarkFluidIndex(index)) {
            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(curFluid(index));
            for (int selectedIndex = 0; selectedIndex < selected.size(); selectedIndex++) {
                Sel entry = selected.get(selectedIndex);
                if (entry.isFluid && java.util.Objects.equals(entry.id, id)) return selectedIndex;
            }
        } else if (mode == Mode.TEXTURE) {
            ResourceLocation texture = ALL_TEXTURES.get(index);
            for (int selectedIndex = 0; selectedIndex < selected.size(); selectedIndex++) {
                Sel entry = selected.get(selectedIndex);
                if (entry.isTexture && texture.equals(entry.textureId)) return selectedIndex;
            }
        } else {
            ItemStack stack = gridItem(index);
            for (int selectedIndex = 0; selectedIndex < selected.size(); selectedIndex++) {
                Sel entry = selected.get(selectedIndex);
                if (!entry.isFluid && !entry.isTexture && ItemStack.isSameItemSameTags(entry.itemIcon, stack)) return selectedIndex;
            }
        }
        return -1;
    }

    @Override
    protected boolean universalMouseScrolled(double mx, double my, double delta) {
        if (sortOpen) return true;
        commitQuantity();
        cancelHold();
        if (GuiRenderUtil.isHovering(mx, my, sidebarX, modulesY, sidebarW, gridBottom - modulesY)) {
            int max = Math.max(0, moduleContentHeight() - (gridBottom - modulesY));
            moduleScroll = Math.max(0, Math.min(max, moduleScroll - (int) Math.signum(delta) * 44));
            return true;
        }
        if (GuiRenderUtil.isHovering(mx, my, selX, listTop(), selW + 6, gridBottom - listTop())) {
            int index = MultiPickerLayout.selectionRowAt(selectionStarts, selectionHeights, (int) my - listTop() + shownSelScroll);
            if (index >= 0 && !selected.get(index).isTexture && GuiRenderUtil.isHovering(mx, my,
                    selX + 43, quantityY(index), quantityWidth() - 3, 16)) {
                stepQuantity(selected.get(index), (long) Math.signum(delta) * quantityStep(), 0);
                return true;
            }
            selScroll = Math.max(0, Math.min(selectionMaxScroll(), selScroll - (int) Math.signum(delta) * 44));
            return true;
        }
        if (GuiRenderUtil.isHovering(mx, my, gridX, gridY, gridW + 6, gridBottom - gridY)) {
            gridScroll = Math.max(0, Math.min(gridMaxScroll(), gridScroll - (int) Math.signum(delta) * PITCH * 3));
            return true;
        }
        return super.universalMouseScrolled(mx, my, delta);
    }

    private void confirm() {
        if (confirmed) return;
        commitQuantity();
        cancelGestures();
        confirmed = true;
        for (Sel s : selected) {
            if (s.isFluid) {
                if (s.fluid != null && onAddFluid != null) onAddFluid.accept(FluidStack.create(s.fluid, Math.max(1L, s.count)));
            } else if (s.isTexture) {
                if (s.textureId != null && onAddTexture != null) onAddTexture.accept(s.textureId);
            } else if (s.itemIcon != null && !s.itemIcon.isEmpty() && onAddItem != null) {
                ItemStack st = s.itemIcon.copy(); // 保留 NBT
                long exact = Math.max(1L, s.count);
                st.setCount((int) Math.min(Integer.MAX_VALUE, exact)); // stack 只能装 int，精确值走第二参
                onAddItem.accept(st, exact);
            }
        }
        if (afterConfirm != null) afterConfirm.run();
        Minecraft.getInstance().setScreen(parent);
    }

    /** 确认加入的回调全部跑完后、回到父界面之前执行。取消和 Esc 不调用。 */
    public void setAfterConfirm(Runnable afterConfirm) {
        this.afterConfirm = afterConfirm;
    }

    /** 切到「替换这一格」：标题和确认按钮改说替换；清单清空时确认按钮写「移除这一格」。 */
    public MultiPickerScreen replacing() {
        this.replacing = true;
        return this;
    }

    /** 预先放进已选清单并设为激活（数量框直接显示它的数量），网格上对应的那格带对勾。保留 NBT。 */
    public MultiPickerScreen preselect(ItemStack stack, long count) {
        if (stack == null || stack.isEmpty()) { preselectOrder.add(null); return this; }
        ItemStack proto = stack.copy();
        proto.setCount(1);
        return addPreselected(new Sel(false, ForgeRegistries.ITEMS.getKey(proto.getItem()), proto, null, Math.max(1L, count)));
    }

    /** 同上，流体按 mB。 */
    public MultiPickerScreen preselect(FluidStack fluid) {
        if (fluid == null || fluid.isEmpty()) { preselectOrder.add(null); return this; }
        Fluid f = fluid.getFluid();
        return addPreselected(new Sel(true, ForgeRegistries.FLUIDS.getKey(f), null, f, Math.max(1L, fluid.getAmount())));
    }

    /** 同上，贴图图标（只有显示图标选择器用得到）。 */
    public MultiPickerScreen preselectTexture(ResourceLocation texture) {
        if (texture == null) { preselectOrder.add(null); return this; }
        return addPreselected(Sel.texture(texture));
    }

    private MultiPickerScreen addPreselected(Sel s) {
        s = selection.add(s, MultiPickerScreen::sameSelection);
        setActive(s);
        if (originalSelection == null) originalSelection = s;
        preselectOrder.add(s);
        hadOriginal = true;
        return this;
    }

    /**
     * 整排替换：这一排的格子都 preselect 进来之后，指出点中的是第几次 preselect 的那一格——它设为激活项并当「原格」，
     * 其余已在清单里。点中的是空占位格（没进清单）时只切到整排模式。确认后由编辑器把整排换成已选清单。
     */
    public MultiPickerScreen focusPreselected(int callIndex) {
        rowMode = true;
        Sel s = callIndex >= 0 && callIndex < preselectOrder.size() ? preselectOrder.get(callIndex) : null;
        originalSelection = s;
        if (s != null) setActive(s);
        return this;
    }

    private String confirmLabel() {
        if (!replacing) return "§a确认加入 (" + selected.size() + ")";
        if (selected.isEmpty()) return hadOriginal ? (rowMode ? "§c清空这一排" : "§c移除这一格") : "§7确认 (0)";
        return "§a确认替换 (" + selected.size() + ")";
    }

    @Override
    public void onClose() {
        commitQuantity();
        cancelGestures();
        ShopGaussBlur.clear();
        ShopGaussBlur.clearTopLayer();
        if (this.minecraft != null && parent != null) this.minecraft.setScreen(parent);
        else super.onClose();
    }

    @Override
    public void removed() {
        commitQuantity();
        cancelGestures();
        sortGeneration++;
        ui.cancel();
        ShopGaussBlur.clear();
        ShopGaussBlur.clearTopLayer();
        super.removed();
    }

    /** Enter 确认、Esc 先退出搜索框再关；其余按键照常交给搜索框。 */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (Screen.hasControlDown() && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_F) {
            commitQuantity();
            setFocused(searchBox);
            return true;
        }
        if (Screen.hasControlDown() && keyCode >= org.lwjgl.glfw.GLFW.GLFW_KEY_1 && keyCode <= org.lwjgl.glfw.GLFW.GLFW_KEY_5) {
            int index = keyCode - org.lwjgl.glfw.GLFW.GLFW_KEY_1;
            if (index < availableModes().size()) changeMode(availableModes().get(index));
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            if (ui.takeEnter()) {
                if (editingQuantity != null) { commitQuantity(); return true; }
                if (sortOpen) { sortOpen = false; return true; }
                ui.press(confirmX(), top + panelHeight - 24, 90, 16, true);
                confirm();
            }
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            if (sortOpen) { sortOpen = false; return true; }
            if (editingQuantity != null) { commitQuantity(); return true; }
            if (getFocused() instanceof EditBox) { setFocused(null); return true; }
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        ui.enterReleased(keyCode);
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        if (!sortOpen && editingQuantity == null && !Screen.hasControlDown() && !searchBox.isFocused()) setFocused(searchBox);
        return super.charTyped(character, modifiers);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
