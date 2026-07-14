package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.AdvancedSearchUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.PinyinSearchBridge;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;

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
 * <p>三种浏览模式：<b>物品</b>（全注册表）、<b>物品栏</b>（玩家背包实物，<b>保留 NBT</b>，
 * 便捷取手上带 NBT 的物品）、<b>流体</b>（全注册表）。右侧「已选清单」每项独立数量。
 * 左键网格=加入/选中，右键网格=加入/移除切换。物品与流体同一会话可混选；确认按类型分发：
 * 物品→{@code onAddItem}（保 NBT），流体→{@code onAddFluid}。</p>
 *
 * <p>由 {@link ShopEntryEditScreen} 的「物品排 +」「流体排 +」打开，取代原 FTBLib 单选。
 * 关屏后返回编辑器（其 init 重新按草稿布局，显示新加入项）。</p>
 */
public class MultiPickerScreen extends ScaledScreen {

    private enum Mode { ITEM, INVENTORY, FLUID, TEXTURE }

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
    private static final int SEL_ROW_H = 20;
    private static final int SCROLLBAR_W = 4;
    // 数量框快速填写：跟 ShopScreen 购买页同款三排步进按钮（+1/+10/+100/+1k、-1/-10/-100/-1k、×10/×100/÷10/÷100），
    // 对当前激活（顶部数量框绑定）的已选项直接改 count，见反馈：补上购买页的快速数字填写
    private static final int COUNT_BOX_W = 140;
    private static final int COUNT_STEP_H = 12;
    private static final long[] COUNT_STEPS = {1, 10, 100, 1000};
    private static final long[] COUNT_SCALES = {10, 100, 10, 100};
    private static final String[] COUNT_SCALE_LABELS = {"§b×10", "§b×100", "§6÷10", "§6÷100"};

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
    private static final class Sel {
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
    }

    private final Screen parent;
    private final Consumer<ItemStack> onAddItem;
    private final Consumer<FluidStack> onAddFluid;
    private final Consumer<ResourceLocation> onAddTexture; // 贴图模式确认回调；非 allowTexture 场景为 null
    private final boolean restrictedMode;         // true = 仅限固定候选列表（如已配置货币），无模式切换/物品栏/流体
    private final boolean allowTexture;           // true = 浏览模式循环里加入「贴图」（仅显示图标选择器开启，物品/流体排选择器不受影响）

    private Mode mode;                            // 浏览模式：物品 / 物品栏 / 流体 / [贴图]（受限模式恒为 ITEM）
    private List<ItemStack> invStacks = new ArrayList<>();  // 物品栏模式：玩家背包实物（含 NBT）
    private List<String> invNames = new ArrayList<>();
    private List<String> invTags = new ArrayList<>();
    private List<Long> invCounts = new ArrayList<>();        // 与 invStacks 同下标：该物品+NBT 在背包+副手里的实际总数
    private List<ItemStack> restrictedStacks = new ArrayList<>(); // 受限模式：固定候选物品
    private List<String> restrictedNames = new ArrayList<>();
    private List<String> restrictedTags = new ArrayList<>();
    private final java.util.Map<Integer, String> tooltipCache = new java.util.HashMap<>(); // 物品栏/受限模式的 $ 搜索会话内缓存
    private final List<Sel> selected = new ArrayList<>();
    private Sel active;                           // 当前激活（顶部数量框编辑对象）
    private List<Integer> filtered = new ArrayList<>(); // 当前模式下过滤后的索引
    private int gridScroll;                       // 网格行偏移
    private int selScroll;                        // 已选清单行偏移
    private boolean suppressCount;                // 切换激活项时抑制数量框回调
    private boolean draggingScroll;                // 正在拖拽网格滚动条
    private boolean itemCacheAppliedOnce;          // 本次开屏后台物品缓存建完那一刻，只强制刷新一次过滤结果

    private EditBox searchBox, countBox;
    private int left, top, panelWidth, panelHeight;
    // 网格/清单几何（initScaled 计算）
    private int gridX, gridY, gridW, gridBottom, gridCols;
    private int selX, selY, selW;

    private ItemStack hoverItem;                  // 悬停网格物品（renderTooltips 消费）
    private List<Component> hoverFluidTip;        // 悬停网格流体名

    public MultiPickerScreen(Screen parent, boolean browseFluid, Consumer<ItemStack> onAddItem, Consumer<FluidStack> onAddFluid) {
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
    public MultiPickerScreen(Screen parent, Consumer<ItemStack> onAddItem, Consumer<ResourceLocation> onAddTexture) {
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
    public MultiPickerScreen(Screen parent, java.util.Collection<ResourceLocation> allowedIds, Consumer<ItemStack> onAddItem) {
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

    @Override
    protected void initScaled() {
        left = Math.max(6, (vWidth - TARGET_W) / 2);
        top = Math.max(8, (vHeight - TARGET_H) / 2);
        panelWidth = Math.min(TARGET_W, vWidth - 12);
        panelHeight = Math.min(TARGET_H, vHeight - 16);

        if (!restrictedMode) buildInventory();

        // 顶部：搜索框 + 数量框
        searchBox = new EditBox(this.font, left + 12, top + 22, 200, 14, Component.literal("搜索"));
        searchBox.setMaxLength(64);
        searchBox.setBordered(true);
        searchBox.setTextColor(0xFFFFFF);
        searchBox.setResponder(s -> rebuildFilter());
        searchBox.setFocused(true);
        setFocused(searchBox);

        int cbW = COUNT_BOX_W;
        countBox = new EditBox(this.font, left + panelWidth - 12 - cbW, top + 22, cbW, 14, Component.literal("数量"));
        countBox.setMaxLength(12);
        countBox.setBordered(true);
        countBox.setTextColor(0xFFFF55);
        countBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        countBox.setValue(active != null ? Long.toString(active.count) : "");
        countBox.setResponder(s -> {
            if (suppressCount || active == null) return;
            long v = s.isEmpty() ? 1L : parseLong(s);
            active.count = Math.max(1L, v);
        });
        addRenderableWidget(searchBox);
        addRenderableWidget(countBox);

        // 几何：左网格 / 右清单。已选清单列顶部要给数量框下面的三排快速步进按钮让位，网格不用（见 countStepX/Y）
        gridX = left + 12;
        gridY = top + 44;
        gridBottom = top + panelHeight - 28;
        selW = 200;
        gridW = panelWidth - 24 - selW - 8;
        gridCols = Math.max(1, (gridW + 2) / PITCH);
        selX = gridX + gridW + 8;
        selY = countStepY(0) + 3 * (COUNT_STEP_H + 2) + 4;

        rebuildFilter();
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 1L; }
    }

    private List<String> curNames() {
        if (restrictedMode) return restrictedNames;
        return switch (mode) {
            case ITEM -> itemCacheReady ? ALL_ITEM_NAMES : List.of(); // 后台还没建完时按"暂无条目"处理，见 buildItemCacheAsync
            case INVENTORY -> invNames;
            case FLUID -> ALL_FLUID_NAMES;
            case TEXTURE -> ALL_TEXTURE_NAMES;
        };
    }

    /** 当前模式下网格第 resIdx 项的物品原型（仅物品/物品栏/受限模式有效）。 */
    private ItemStack gridItem(int resIdx) {
        if (restrictedMode) return restrictedStacks.get(resIdx);
        return (mode == Mode.INVENTORY ? invStacks : ALL_ITEMS).get(resIdx);
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
    private String lastFilterQuery;
    private List<Integer> lastFilterResult;

    private void rebuildFilter() {
        String q = (searchBox != null ? searchBox.getValue().trim() : "").toLowerCase(Locale.ROOT);
        String cacheKey = mode.name() + " " + q;
        List<Integer> cached = filterResultCache.get(cacheKey);
        if (cached != null) {
            filtered = cached;
            gridScroll = 0;
            return;
        }
        List<String> names = curNames();
        int n = names.size();
        List<Integer> out = new ArrayList<>();
        // 增量收窄：新查询是在上次查询末尾接着打字打出来的（同模式、纯前缀扩展），且没启用拼音兜底
        // （contains 语义下严格单调收窄：能匹配更长的串，必然也匹配它的前缀，见 AdvancedSearchUtil），
        // 直接在上次结果集里再筛一遍，不用从全量重新扫描；拼音匹配不保证这个单调性，禁用时才走这条捷径。
        boolean canNarrow = lastFilterResult != null && lastFilterQuery != null && !q.isEmpty()
                && q.startsWith(lastFilterQuery) && q.length() > lastFilterQuery.length()
                && !PinyinSearchBridge.available();
        if (canNarrow) {
            for (int i : lastFilterResult) if (matchIndex(i, q)) out.add(i);
        } else {
            for (int i = 0; i < n; i++) if (matchIndex(i, q)) out.add(i);
        }
        filtered = out;
        filterResultCache.put(cacheKey, out);
        lastFilterQuery = q;
        lastFilterResult = out;
        gridScroll = 0;
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
                if (mode == Mode.FLUID || mode == Mode.TEXTURE) return name.contains(rest);
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
            case FLUID -> ALL_FLUID_TAGS.get(i);
            case TEXTURE -> "";
        };
    }

    /** 第 i 项的提示文本（小写，仅物品）。全注册表走静态懒缓存，物品栏/受限模式列表小，会话内缓存即可。 */
    private String curTooltip(int i) {
        if (mode == Mode.FLUID || mode == Mode.TEXTURE) return null;
        if (!restrictedMode && mode == Mode.ITEM) return allItemTooltip(i);
        return tooltipCache.computeIfAbsent(i, idx -> tooltipOf(gridItem(idx)));
    }

    /** 第 i 项的物品/流体/贴图 id（不含显示名，小写）。 */
    private String curIdOnly(int i) {
        if (mode == Mode.FLUID) {
            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(ALL_FLUIDS.get(i));
            return id == null ? "" : id.toString().toLowerCase(Locale.ROOT);
        }
        if (mode == Mode.TEXTURE) {
            return ALL_TEXTURES.get(i).toString().toLowerCase(Locale.ROOT);
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(gridItem(i).getItem());
        return id == null ? "" : id.toString().toLowerCase(Locale.ROOT);
    }

    private int gridRows() {
        return Math.max(1, (gridBottom - gridY) / PITCH);
    }

    private int gridTotalRows() {
        return (int) Math.ceil(filtered.size() / (double) gridCols);
    }

    private int gridMaxScroll() {
        return Math.max(0, gridTotalRows() - gridRows());
    }

    private int scrollBarX() { return gridX + gridW + 2; }

    private int selVisibleRows() {
        return Math.max(1, (gridBottom - (selY + 14)) / SEL_ROW_H);
    }

    // ===== 渲染 =====
    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        // 后台物品缓存刚建完的那一帧：之前按"暂无条目"缓存的过滤结果已经过期，必须清掉重算一次，
        // 否则加载完成后如果搜索框内容没变，会一直卡在建缓存前算出来的空结果上（见反馈修复）。
        if (itemCacheReady && !itemCacheAppliedOnce) {
            itemCacheAppliedOnce = true;
            filterResultCache.clear();
            lastFilterQuery = null;
            lastFilterResult = null;
            rebuildFilter();
        }
        hoverItem = null;
        hoverFluidTip = null;
        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        g.fill(left + 5, top + 18, left + panelWidth - 5, top + panelHeight - 5, PANEL_INNER);

        String headerHint = restrictedMode
                ? "§6多选货币  §7左键=加入/选中 · 右键=加入/移除 · 仅列出已配置货币"
                : "§6多选资源  §7左键=加入/选中 · 右键=加入/移除 · 物品栏模式取实物(带NBT)";
        g.drawString(this.font, headerHint, left + 10, top + 5, GOLD, true);

        // 浏览模式切换按钮（搜索框右侧，点一下循环：物品→物品栏→流体；受限模式无此按钮）
        int modeX = left + 12 + 200 + 8;
        if (!restrictedMode) drawBtn(g, modeX, top + 21, 70, 16, modeLabel(), mx, my);
        // 数量框标签
        g.drawString(this.font, active != null && active.isFluid ? "§7mB:" : "§7数量:", left + panelWidth - 12 - COUNT_BOX_W - 30, top + 25, GRAY, true);
        drawCountSteps(g, mx, my);

        drawGrid(g, mx, my);
        drawGridScrollbar(g, mx, my);
        drawSelectedList(g, mx, my);

        // 底部按钮
        drawBtn(g, confirmX(), top + panelHeight - 24, 90, 16, "§a确认加入 (" + selected.size() + ")", mx, my);
        drawBtn(g, cancelX(), top + panelHeight - 24, 56, 16, "§c取消", mx, my);
    }

    private String modeLabel() {
        return switch (mode) {
            case ITEM -> "§a物品▾";
            case INVENTORY -> "§e物品栏▾";
            case FLUID -> "§b流体▾";
            case TEXTURE -> "§d贴图▾";
        };
    }

    private int countStepX() { return left + panelWidth - 12 - COUNT_BOX_W; }
    private int countStepY(int row) { return top + 38 + row * (COUNT_STEP_H + 2); }
    private int countStepW() { return (COUNT_BOX_W - 9) / 4; }

    /** 数量快速填写：三排步进按钮（+1/+10/+100/+1k、-1/-10/-100/-1k、×10/×100/÷10/÷100），
     * 对当前激活的已选项直接改 count；没有激活项/激活项是贴图（无数量概念）时不画。 */
    private void drawCountSteps(GuiGraphics g, int mx, int my) {
        if (active == null || active.isTexture) return;
        int bx0 = countStepX(), bw = countStepW();
        for (int i = 0; i < 4; i++) {
            int bx = bx0 + i * (bw + 3);
            drawBtn(g, bx, countStepY(0), bw, COUNT_STEP_H, "§a+" + compactStep(COUNT_STEPS[i]), mx, my);
            drawBtn(g, bx, countStepY(1), bw, COUNT_STEP_H, "§c-" + compactStep(COUNT_STEPS[i]), mx, my);
            drawBtn(g, bx, countStepY(2), bw, COUNT_STEP_H, COUNT_SCALE_LABELS[i], mx, my);
        }
    }

    /** 数量快速填写按钮点击；命中即改 active.count + 回写数量框，未命中返回 false。 */
    private boolean countStepsClicked(double mx, double my) {
        if (active == null || active.isTexture) return false;
        int bx0 = countStepX(), bw = countStepW();
        for (int i = 0; i < 4; i++) {
            int bx = bx0 + i * (bw + 3);
            if (GuiRenderUtil.isHovering(mx, my, bx, countStepY(0), bw, COUNT_STEP_H)) {
                active.count = addClamp(active.count, COUNT_STEPS[i]);
                syncCountBox();
                return true;
            }
            if (GuiRenderUtil.isHovering(mx, my, bx, countStepY(1), bw, COUNT_STEP_H)) {
                active.count = addClamp(active.count, -COUNT_STEPS[i]);
                syncCountBox();
                return true;
            }
            if (GuiRenderUtil.isHovering(mx, my, bx, countStepY(2), bw, COUNT_STEP_H)) {
                active.count = i < 2 ? mulClamp(active.count, COUNT_SCALES[i]) : divClamp(active.count, COUNT_SCALES[i]);
                syncCountBox();
                return true;
            }
        }
        return false;
    }

    private void syncCountBox() {
        if (countBox == null || active == null) return;
        suppressCount = true;
        countBox.setValue(Long.toString(active.count));
        suppressCount = false;
    }

    private static String compactStep(long step) {
        return step >= 1000 ? (step / 1000) + "k" : String.valueOf(step);
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

    private int confirmX() { return left + panelWidth - 12 - 90; }
    private int cancelX() { return confirmX() - 6 - 56; }
    private int clearAllX() { return selX + selW - 32; }
    private int syncAllX() { return clearAllX() - 32 - 2; }

    private void drawGrid(GuiGraphics g, int mx, int my) {
        if (mode == Mode.ITEM && !itemCacheReady) {
            // 物品模式且后台缓存还没建完：给个明确的"加载中"占位，别让玩家误以为搜索没结果
            g.drawCenteredString(this.font, "§e正在后台构建物品列表…", gridX + gridW / 2, gridY + (gridBottom - gridY) / 2 - 4, GRAY);
            g.drawString(this.font, "§8物品列表加载中，请稍候…", gridX, gridBottom + 2, GRAY, true);
            return;
        }
        int cols = gridCols;
        int rows = gridRows();
        int total = filtered.size();
        int maxScroll = Math.max(0, (int) Math.ceil(total / (double) cols) - rows);
        if (gridScroll > maxScroll) gridScroll = maxScroll;
        int start = gridScroll * cols;
        boolean fluidMode = mode == Mode.FLUID;
        boolean textureMode = mode == Mode.TEXTURE;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = start + r * cols + c;
                if (idx >= total) break;
                int x = gridX + c * PITCH;
                int y = gridY + r * PITCH;
                int resIdx = filtered.get(idx);
                boolean hv = GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT);
                boolean chosen = findSelected(resIdx) >= 0;
                EditorWidgets.checkerSlot(g, x, y, hv);
                if (fluidMode) {
                    EditorWidgets.fluidIcon(g, x + 2, y + 2, 16, ALL_FLUIDS.get(resIdx));
                } else if (textureMode) {
                    EditorWidgets.textureThumb(g, x + 2, y + 2, 16, ALL_TEXTURES.get(resIdx));
                } else {
                    ItemStack st = gridItem(resIdx);
                    g.renderItem(st, x + 2, y + 2);
                    if (st.hasTag()) { // 带 NBT 的实物 → 左下角小黄点提示
                        g.fill(x + 2, y + 15, x + 5, y + 18, 0xFFFFD000);
                    }
                }
                if (chosen) { // 已在清单里 → 绿色对勾角标
                    g.fill(x + 13, y + 1, x + 19, y + 7, 0xCC1E7A2E);
                    g.drawString(this.font, "§a✔", x + 13, y + 1, WHITE, false);
                }
                if (hv) {
                    if (fluidMode) hoverFluidTip = List.of(Component.literal(fluidName(ALL_FLUIDS.get(resIdx))));
                    else if (textureMode) hoverFluidTip = List.of(Component.literal(ALL_TEXTURES.get(resIdx).toString()));
                    else hoverItem = gridItem(resIdx);
                }
            }
        }
        // 滚动/计数提示
        g.drawString(this.font, "§8匹配 " + total + " 项 · 滚轮翻页/拖动右侧滚动条", gridX, gridBottom + 2, GRAY, true);
    }

    /** 网格右侧竖向滚动条（拖拽用），画在网格与已选清单之间的缝隙里。 */
    private void drawGridScrollbar(GuiGraphics g, int mx, int my) {
        int trackX = scrollBarX();
        int trackY = gridY, trackH = gridBottom - gridY;
        g.fill(trackX, trackY, trackX + SCROLLBAR_W, trackY + trackH, PANEL_BG);
        int maxScroll = gridMaxScroll();
        int rows = gridRows(), totalRows = gridTotalRows();
        if (maxScroll <= 0 || totalRows <= 0) {
            g.fill(trackX, trackY, trackX + SCROLLBAR_W, trackY + trackH, BTN_BG);
            return;
        }
        int thumbH = Math.max(10, trackH * rows / totalRows);
        int thumbY = trackY + (trackH - thumbH) * gridScroll / maxScroll;
        boolean hv = draggingScroll || GuiRenderUtil.isHovering(mx, my, trackX, thumbY, SCROLLBAR_W, thumbH);
        g.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, hv ? BTN_HOVER : GOLD_DARK);
    }

    private void drawSelectedList(GuiGraphics g, int mx, int my) {
        g.fill(selX - 2, selY - 2, selX + selW + 2, gridBottom + 2, GOLD_DARK);
        g.fill(selX - 1, selY - 1, selX + selW + 1, gridBottom + 1, PANEL_BG);
        g.drawString(this.font, "§6已选清单 §7(" + selected.size() + ")", selX + 2, selY, GOLD, true);
        if (!selected.isEmpty()) {
            drawBtn(g, syncAllX(), selY - 1, 32, 10, "§b同步", mx, my);
            drawBtn(g, clearAllX(), selY - 1, 32, 10, "§c清空", mx, my);
        }
        int visible = selVisibleRows();
        int maxScroll = Math.max(0, selected.size() - visible);
        if (selScroll > maxScroll) selScroll = maxScroll;
        int baseY = selY + 14;
        for (int i = 0; i < visible; i++) {
            int idx = selScroll + i;
            if (idx >= selected.size()) break;
            Sel s = selected.get(idx);
            int ry = baseY + i * SEL_ROW_H;
            boolean rowHover = GuiRenderUtil.isHovering(mx, my, selX, ry, selW, SEL_ROW_H);
            if (s == active) g.fill(selX, ry, selX + selW, ry + SEL_ROW_H, SEL_ACTIVE);
            else if (rowHover) g.fill(selX, ry, selX + selW, ry + SEL_ROW_H, 0x33FFFFFF);
            // 图标
            if (s.isFluid) EditorWidgets.fluidIcon(g, selX + 1, ry + 1, 16, s.fluid);
            else if (s.isTexture) EditorWidgets.textureThumb(g, selX + 1, ry + 1, 16, s.textureId);
            else g.renderItem(s.itemIcon, selX, ry);
            // 名称（带 NBT 的物品名前加 §e*；贴图显示资源路径）
            String nm = s.isFluid ? fluidName(s.fluid)
                    : s.isTexture ? s.textureId.toString()
                    : (s.itemIcon.hasTag() ? "*" : "") + s.itemIcon.getHoverName().getString();
            g.drawString(this.font, "§f" + GuiRenderUtil.trimText(this.font, nm, selW - 20 - 62), selX + 20, ry + 2, WHITE, true);
            // 数量 + 单位（贴图无数量概念，不显示）
            if (!s.isTexture) {
                String amt = groupLong(s.count) + (s.isFluid ? "mB" : "");
                g.drawString(this.font, (s == active ? "§e" : "§b") + amt, selX + selW - 60, ry + 2, CYAN, true);
            }
            // ✕ 移除
            int rmX = selX + selW - 12;
            boolean rmHv = GuiRenderUtil.isHovering(mx, my, rmX, ry + 1, 10, 10);
            g.drawString(this.font, rmHv ? "§c§l✕" : "§7✕", rmX, ry + 11, WHITE, false);
        }
        if (selected.isEmpty()) {
            g.drawString(this.font, "§8点左侧网格加入…", selX + 4, baseY + 2, GRAY, true);
        }
        if (selected.size() > visible) {
            g.drawString(this.font, "§8" + (selScroll + 1) + "-" + Math.min(selScroll + visible, selected.size()) + "/" + selected.size(), selX + 2, gridBottom - 8, GRAY, true);
        }
    }

    @Override
    protected void renderTooltips(GuiGraphics g, int smx, int smy, int mx, int my) {
        if (hoverItem != null && !hoverItem.isEmpty()) {
            g.renderTooltip(this.font, hoverItem, mx, my);
        } else if (hoverFluidTip != null) {
            g.renderComponentTooltip(this.font, hoverFluidTip, mx, my);
        } else if (GuiRenderUtil.isHovering(smx, smy, left + 12, top + 22, 200, 14)) {
            g.renderComponentTooltip(this.font, SEARCH_HELP, mx, my);
        }
    }

    private static String fluidName(Fluid f) {
        return FluidStack.create(f, 1000).getName().getString();
    }

    /** 精确数量带千分位。 */
    private static String groupLong(long v) {
        return String.format(Locale.ROOT, "%,d", Math.max(0L, v));
    }

    // ===== 交互 =====
    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        // 底部按钮
        if (GuiRenderUtil.isHovering(mx, my, confirmX(), top + panelHeight - 24, 90, 16)) { confirm(); return true; }
        if (GuiRenderUtil.isHovering(mx, my, cancelX(), top + panelHeight - 24, 56, 16)) { Minecraft.getInstance().setScreen(parent); return true; }
        // 浏览模式切换（循环：物品→物品栏→流体→[贴图，仅 allowTexture]；受限模式无此按钮）
        int modeX = left + 12 + 200 + 8;
        if (!restrictedMode && GuiRenderUtil.isHovering(mx, my, modeX, top + 21, 70, 16)) {
            mode = switch (mode) {
                case ITEM -> Mode.INVENTORY;
                case INVENTORY -> Mode.FLUID;
                case FLUID -> allowTexture ? Mode.TEXTURE : Mode.ITEM;
                case TEXTURE -> Mode.ITEM;
            };
            if (mode == Mode.TEXTURE) ensureTextureCache(); // 懒加载：只有真的切进贴图模式才扫描资源包
            rebuildFilter();
            return true;
        }
        // 数量快速填写三排步进按钮
        if (countStepsClicked(mx, my)) return true;
        // 已选清单「同步」：把顶部数量框当前值套用到全部已选项目
        if (!selected.isEmpty() && GuiRenderUtil.isHovering(mx, my, syncAllX(), selY - 1, 32, 10)) {
            syncAllCounts();
            return true;
        }
        // 已选清单「清空」
        if (!selected.isEmpty() && GuiRenderUtil.isHovering(mx, my, clearAllX(), selY - 1, 32, 10)) {
            clearAll();
            return true;
        }
        // 网格滚动条（拖拽）
        if (gridScrollbarClicked(mx, my)) return true;
        // 网格
        if (gridClicked(mx, my, btn)) return true;
        // 已选清单
        if (selListClicked(mx, my, btn)) return true;
        return super.universalMouseClicked(mx, my, btn);
    }

    private void clearAll() {
        selected.clear();
        setActive(null);
        selScroll = 0;
    }

    /** 同步数值：把顶部数量框当前输入的值套用到已选清单里的全部项目（贴图无数量概念，跳过）。 */
    private void syncAllCounts() {
        if (countBox == null) return;
        String text = countBox.getValue();
        long v = Math.max(1L, text.isEmpty() ? 1L : parseLong(text));
        for (Sel s : selected) {
            if (!s.isTexture) s.count = v;
        }
    }

    private boolean gridScrollbarClicked(double mx, double my) {
        int trackX = scrollBarX();
        if (mx < trackX || mx > trackX + SCROLLBAR_W || my < gridY || my > gridBottom) return false;
        draggingScroll = true;
        updateScrollFromDrag(my);
        return true;
    }

    /** 按拖拽点的 Y 坐标反算 gridScroll（拇指块中心跟随鼠标）。 */
    private void updateScrollFromDrag(double my) {
        int maxScroll = gridMaxScroll();
        if (maxScroll <= 0) { gridScroll = 0; return; }
        int rows = gridRows(), totalRows = gridTotalRows();
        int trackH = gridBottom - gridY;
        int thumbH = Math.max(10, trackH * rows / totalRows);
        double usable = Math.max(1, trackH - thumbH);
        double rel = (my - gridY - thumbH / 2.0) / usable;
        gridScroll = (int) Math.round(Math.max(0.0, Math.min(1.0, rel)) * maxScroll);
    }

    @Override
    protected boolean universalMouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (draggingScroll) { updateScrollFromDrag(my); return true; }
        return super.universalMouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    protected boolean universalMouseReleased(double mx, double my, int btn) {
        if (draggingScroll) { draggingScroll = false; return true; }
        return super.universalMouseReleased(mx, my, btn);
    }

    private boolean gridClicked(double mx, double my, int btn) {
        int cols = gridCols, rows = gridRows(), total = filtered.size();
        int start = gridScroll * cols;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = start + r * cols + c;
                if (idx >= total) return false;
                int x = gridX + c * PITCH, y = gridY + r * PITCH;
                if (GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT)) {
                    onGridPick(filtered.get(idx), btn == 1);
                    return true;
                }
            }
        }
        return false;
    }

    /** 网格点击：左键加入/选中，右键加入/移除切换。 */
    private void onGridPick(int resIdx, boolean rightClick) {
        int existing = findSelected(resIdx);
        if (rightClick) {
            if (existing >= 0) { removeSelected(existing); return; }
            addFromGrid(resIdx);
        } else {
            if (existing >= 0) { setActive(selected.get(existing)); return; }
            addFromGrid(resIdx);
        }
    }

    private void addFromGrid(int resIdx) {
        Sel s;
        if (mode == Mode.FLUID) {
            Fluid f = ALL_FLUIDS.get(resIdx);
            s = new Sel(true, ForgeRegistries.FLUIDS.getKey(f), null, f, 1000L);
        } else if (mode == Mode.TEXTURE) {
            s = Sel.texture(ALL_TEXTURES.get(resIdx));
        } else {
            ItemStack proto = gridItem(resIdx).copy(); // 保留 NBT，独立于缓存/背包
            proto.setCount(1);
            // 物品栏模式：初始数量自动填玩家当前实际持有量，而不是恒为 1（其余模式无"持有量"概念，仍默认 1）
            long initCount = mode == Mode.INVENTORY && resIdx < invCounts.size()
                    ? Math.max(1L, invCounts.get(resIdx)) : 1L;
            s = new Sel(false, ForgeRegistries.ITEMS.getKey(proto.getItem()), proto, null, initCount);
        }
        selected.add(s);
        setActive(s);
        // 新增项滚到可见底部
        int visible = selVisibleRows();
        if (selected.size() > visible) selScroll = selected.size() - visible;
    }

    private boolean selListClicked(double mx, double my, int btn) {
        int visible = selVisibleRows();
        int baseY = selY + 14;
        for (int i = 0; i < visible; i++) {
            int idx = selScroll + i;
            if (idx >= selected.size()) break;
            int ry = baseY + i * SEL_ROW_H;
            int rmX = selX + selW - 12;
            if (GuiRenderUtil.isHovering(mx, my, rmX, ry + 1, 12, 12)) { removeSelected(idx); return true; }
            if (GuiRenderUtil.isHovering(mx, my, selX, ry, selW, SEL_ROW_H)) { setActive(selected.get(idx)); return true; }
        }
        return false;
    }

    private void removeSelected(int idx) {
        if (idx < 0 || idx >= selected.size()) return;
        Sel s = selected.remove(idx);
        if (active == s) setActive(selected.isEmpty() ? null : selected.get(Math.min(idx, selected.size() - 1)));
    }

    private void setActive(Sel s) {
        active = s;
        suppressCount = true;
        if (countBox != null) countBox.setValue(s != null ? Long.toString(s.count) : "");
        suppressCount = false;
    }

    /** 在已选清单里找网格第 resIdx 项：物品按物品+NBT，流体按流体 id，贴图按贴图路径。 */
    private int findSelected(int resIdx) {
        if (mode == Mode.FLUID) {
            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(ALL_FLUIDS.get(resIdx));
            for (int i = 0; i < selected.size(); i++) {
                Sel s = selected.get(i);
                if (s.isFluid && s.id != null && s.id.equals(id)) return i;
            }
        } else if (mode == Mode.TEXTURE) {
            ResourceLocation tex = ALL_TEXTURES.get(resIdx);
            for (int i = 0; i < selected.size(); i++) {
                Sel s = selected.get(i);
                if (s.isTexture && tex.equals(s.textureId)) return i;
            }
        } else {
            ItemStack grid = gridItem(resIdx);
            for (int i = 0; i < selected.size(); i++) {
                Sel s = selected.get(i);
                if (!s.isFluid && !s.isTexture && ItemStack.isSameItemSameTags(s.itemIcon, grid)) return i;
            }
        }
        return -1;
    }

    @Override
    protected boolean universalMouseScrolled(double mx, double my, double d) {
        // 已选清单区域滚动
        if (GuiRenderUtil.isHovering(mx, my, selX, selY, selW, gridBottom - selY)) {
            int visible = selVisibleRows();
            if (selected.size() > visible) {
                selScroll = Math.max(0, Math.min(selScroll - (int) Math.signum(d), selected.size() - visible));
            }
            return true;
        }
        // 网格区域滚动
        if (GuiRenderUtil.isHovering(mx, my, gridX, gridY, gridW, gridBottom - gridY)) {
            int cols = gridCols, rows = gridRows();
            int maxScroll = Math.max(0, (int) Math.ceil(filtered.size() / (double) cols) - rows);
            gridScroll = Math.max(0, Math.min(gridScroll - (int) Math.signum(d), maxScroll));
            return true;
        }
        return super.universalMouseScrolled(mx, my, d);
    }

    private void confirm() {
        for (Sel s : selected) {
            if (s.isFluid) {
                if (s.fluid != null && onAddFluid != null) onAddFluid.accept(FluidStack.create(s.fluid, Math.max(1L, s.count)));
            } else if (s.isTexture) {
                if (s.textureId != null && onAddTexture != null) onAddTexture.accept(s.textureId);
            } else if (s.itemIcon != null && !s.itemIcon.isEmpty() && onAddItem != null) {
                ItemStack st = s.itemIcon.copy(); // 保留 NBT
                st.setCount((int) Math.max(1L, Math.min(Integer.MAX_VALUE, s.count)));
                onAddItem.accept(st);
            }
        }
        Minecraft.getInstance().setScreen(parent);
    }

    private void drawBtn(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my) {
        boolean hv = GuiRenderUtil.isHovering(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, GOLD_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hv ? BTN_HOVER : BTN_BG);
        g.drawCenteredString(this.font, label, x + w / 2, y + (h - 8) / 2, WHITE);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
