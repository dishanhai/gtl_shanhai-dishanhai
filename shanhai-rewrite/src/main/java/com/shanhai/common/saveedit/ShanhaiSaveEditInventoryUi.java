package com.shanhai.common.saveedit;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * 第 ① 页「物品栏 41 格 + 末影箱」的<b>纯 JDK 逻辑层</b>（离线可判；控件那一层只负责画）。
 *
 * <h2>一、为什么逻辑必须单独一层</h2>
 * 与第 ④⑤⑥ 页同一条理由：{@code ShanhaiSaveEditPanel} / {@code ShanhaiSaveEditInventoryOverlay}
 * 都继承 LDLib 的 {@code WidgetGroup}，<b>离线加载不了</b>（{@code IConfigurableWidget.<clinit>}
 * 要一个本机 mapped forge jar 里没有的 SRG 方法）。所以"格号 → 槽位"的映射、数量校验、
 * 补丁构造、名字回退、选物器过滤这些必须能在一个纯 JVM 里被断言。
 *
 * <h2>二、🔴 格号与槽位<b>只有一张表</b></h2>
 * 41 格的槽号顺序<b>不在这里写第二遍</b>：全部转发数据层
 * {@link ShanhaiPlayerDataEdit#inventoryGrid()} / {@link ShanhaiPlayerDataEdit#enderGrid()}。
 * 理由：数据层那张表已经写着"副手 = <b>-106</b>（不是 150）"，UI 再写一份就一定会有一天分叉。
 * 本类的 {@link #slotAt(boolean, int)} 只是转发 + 越界保护。
 *
 * <h2>三、🔴 背包与末影箱是<b>同一套格子</b></h2>
 * 用户原话：「末影箱可以做成左下角一个开关，切换末影箱的内容和玩家本体物品栏，
 * 这样可以少画一套格子」。所以这里只有一个 {@link Grid} 类型、一个 {@link Model}，
 * 差别只有三处，全部收在 {@link #cellCount(boolean)} / {@link #slotAt(boolean, int)} /
 * {@link #read(Path, boolean)} 里 —— <b>数据源换了，画格子的代码一行都不用动</b>。
 *
 * <h2>四、名字：中文在前，两种回退都非空</h2>
 * {@link #itemLabel(String, Namer)} = {@code 钻石镐（minecraft:diamond_pickaxe）}；
 * 语言表查不到 ⇒ {@code id〔无译名〕}；注册表里没有这个 id ⇒ {@code id〔未注册〕}。
 * <b>绝不返回空串</b>（返回空串在界面上就是"看起来这一格没有东西"，那是骗人的）。
 * 中文名只有<b>客户端</b>查得到（服务端没有语言表），所以服务端只推 id，名字由客户端现拼
 * —— 与第 ④⑤⑥ 页同一条口径。
 */
public final class ShanhaiSaveEditInventoryUi {

    // ================================================================== 记号 / 常量

    /** 「有注册、但语言表没有译文」的记号（与第 ⑤⑥ 页同一个记号：少一个记号就多一种说法）。 */
    public static final String MARK_NO_NAME = "〔无译名〕";
    /** 「注册表里根本没有这个 id」的记号。 */
    public static final String MARK_UNREGISTERED = "〔未注册〕";
    /** 空格子在字里显示成什么（不是空串 —— 空串等于"这一格不存在"）。 */
    public static final String EMPTY_TEXT = "空";

    /** 开关的两个档位（就是按钮上的字）。 */
    public static final String MODE_BACKPACK = "背包";
    public static final String MODE_ENDER = "末影箱";

    /** 选物器一页几列（8 列沿 {@code ShanhaiCreativeIconPicker} 的习惯）。 */
    public static final int PICK_COLS = 8;
    /** 选物器一页几行（本面板只有 232 px 高，6 行是"放得下 48 格 + 一行页码"的上限）。 */
    public static final int PICK_ROWS = 6;
    /** 选物器一页几格。 */
    public static final int PICK_PER_PAGE = PICK_COLS * PICK_ROWS;

    private ShanhaiSaveEditInventoryUi() {}

    // ================================================================== 1. 格号 ↔ 槽位（转发数据层，不另写表）

    /** 这一侧一共有几格：背包 <b>41</b>（27+9+4+1）/ 末影箱 <b>27</b>。 */
    public static int cellCount(boolean ender) {
        return ender ? ShanhaiPlayerDataEdit.ENDER_SLOTS : ShanhaiPlayerDataEdit.INVENTORY_GRID_SLOTS;
    }

    /**
     * 第 {@code index} 个格子是哪个 {@code Slot}（越界 ⇒ -1）。
     *
     * <p>🔴 全部转发数据层：背包第 40 格就是 {@link ShanhaiPlayerDataEdit#OFFHAND_SLOT}（-106），
     * <b>不是</b> {@code 150}（150 只是兼容别名，{@link ShanhaiPlayerDataEdit#canonicalSlot(int)}
     * 会把它归一回来）。
     */
    public static int slotAt(boolean ender, int index) {
        return ender ? ShanhaiPlayerDataEdit.enderSlotAt(index)
                : ShanhaiPlayerDataEdit.inventorySlotAt(index);
    }

    /** 这个槽号是第几格（不在这一侧的格表里 ⇒ -1；{@code 150} 归一后能查到副手那格）。 */
    public static int indexOf(boolean ender, int slot) {
        return ender ? ShanhaiPlayerDataEdit.enderGridIndex(slot)
                : ShanhaiPlayerDataEdit.inventoryGridIndex(slot);
    }

    /** 这一侧的槽号表（拷一份，防外面改）。 */
    public static int[] slots(boolean ender) {
        return ender ? ShanhaiPlayerDataEdit.enderGrid() : ShanhaiPlayerDataEdit.inventoryGrid();
    }

    /** 第 {@code index} 格的中文名（{@code 背包12} / {@code 靴子} / {@code 副手} / {@code 末影箱3}）。 */
    public static String cellName(boolean ender, int index) {
        // 🔴 判"越界"只能看**下标**，绝不能看 slot 的正负：副手那一格的 slot 就是 -106
        //    （负数合法！）。这里踩过一次：用 `slot < 0` 判非法 ⇒ 副手显示成 "格?40"。
        if (index < 0 || index >= cellCount(ender)) {
            return "格?" + index;
        }
        final int slot = slotAt(ender, index);
        return ender ? ShanhaiPlayerDataEdit.enderSlotName(slot) : ShanhaiPlayerDataEdit.slotLabel(slot);
    }

    /** 槽号的中文名（补丁描述 / 日志用）。 */
    public static String slotName(boolean ender, int slot) {
        return ender ? ShanhaiPlayerDataEdit.enderSlotName(slot) : ShanhaiPlayerDataEdit.slotName(slot);
    }

    /**
     * 41 格的<b>四段</b>（快捷栏 9 / 主背包 27 / 护甲 4 / 副手 1）。
     *
     * <p>段界<b>不是写死的数字</b>：从数据层那张 41 格表<b>扫出来</b>（见 {@link #rangeOf}），
     * 所以"数据层把副手挪个位置"时这里会跟着变，而不是留一段过期常量。
     */
    public static final Segment[] SEGMENTS = buildSegments();

    /** 一段连续格号（含首尾）。 */
    public record Segment(String key, String name, int from, int to) {
        /** 这一段有几格。 */
        public int count() {
            return this.to < this.from ? 0 : this.to - this.from + 1;
        }

        /** 第 {@code index} 格在不在这里面。 */
        public boolean has(int index) {
            return index >= this.from && index <= this.to;
        }
    }

    private static Segment[] buildSegments() {
        final List<Segment> out = new ArrayList<>();
        // 顺序 = 游戏里物品栏的视觉顺序（快捷栏最后一行、护甲+副手在侧边）：
        //   背包 27 → 快捷栏 9 → 护甲 4 → 副手 1（这里只是"分段名"，屏幕位置在 Layout 那张表里）
        out.add(rangeOf("backpack", "背包", slot ->
                slot >= ShanhaiPlayerDataEdit.HOTBAR_SLOTS && slot < ShanhaiPlayerDataEdit.MAIN_SLOTS));
        out.add(rangeOf("hotbar", "快捷栏", slot ->
                slot >= 0 && slot < ShanhaiPlayerDataEdit.HOTBAR_SLOTS));
        out.add(rangeOf("armor", "护甲", slot ->
                slot >= ShanhaiPlayerDataEdit.ARMOR_BASE
                        && slot < ShanhaiPlayerDataEdit.ARMOR_BASE + ShanhaiPlayerDataEdit.ARMOR_SLOTS));
        out.add(rangeOf("offhand", "副手", slot -> slot == ShanhaiPlayerDataEdit.OFFHAND_SLOT));
        return out.toArray(new Segment[0]);
    }

    private static Segment rangeOf(String key, String name, IntPredicate which) {
        int from = -1;
        int to = -1;
        final int n = ShanhaiPlayerDataEdit.INVENTORY_GRID_SLOTS;
        for (int i = 0; i < n; i++) {
            // 🔴 不要写 `slot >= 0`：副手是 -106，负数是合法槽号（这里踩过一次 ——
            //    副手段会退化成一格，c2/pass 全靠运气）。
            final int slot = ShanhaiPlayerDataEdit.inventorySlotAt(i);
            if (which.test(slot)) {
                if (from < 0) {
                    from = i;
                }
                to = i;
            }
        }
        return new Segment(key, name, from, to);
    }

    /** 第 {@code index} 格属于哪一段（越界 ⇒ {@code null}）。 */
    public static Segment segmentOf(int index) {
        for (Segment s : SEGMENTS) {
            if (s.has(index)) {
                return s;
            }
        }
        return null;
    }

    // ================================================================== 2. 一格 / 一份数据源

    /** 一格的内容（纯 JDK：id + 数量；{@link #empty()} = 这一格没有条目）。 */
    public record Cell(int slot, String id, int count) {

        /** 空格子（不是 {@code Count=0}，是"这一格根本没有条目"）。 */
        public static Cell empty(int slot) {
            return new Cell(slot, "", 0);
        }

        public boolean empty() {
            return this.id == null || this.id.isEmpty();
        }

        /** {@code minecraft:stone×3}（日志/判据用；界面上的字走 {@code itemLabel}）。 */
        public String line() {
            return empty() ? "[" + EMPTY_TEXT + "]" : this.id + "×" + this.count;
        }
    }

    /**
     * 一份「格子快照」（背包 41 格与末影箱 27 格<b>同形</b>）。
     *
     * <p>{@code cells} 的顺序与 {@link #slotAt(boolean, int)} 一一对应（第 i 个元素就是第 i 格）。
     * {@code present} = 文件里那个键在不在；{@code rangeVerified} 只有末影箱会是 false
     * （本机 16/16 份真实档案的 {@code EnderItems} 都是空的 ⇒ 0–26 未实测，见数据层注释）。
     */
    public record Grid(boolean ender, List<Cell> cells, boolean present, String error,
                       boolean rangeVerified) {

        /** 一个"什么都还没读到"的网格（服务端读失败时就用它，界面上照旧画满格子）。 */
        public static Grid empty(boolean ender) {
            final int n = cellCount(ender);
            final List<Cell> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(Cell.empty(slotAt(ender, i)));
            }
            return new Grid(ender, List.copyOf(list), false, "",
                    ender ? ShanhaiPlayerDataEdit.ENDER_RANGE_VERIFIED : true);
        }

        public int size() {
            return this.cells == null ? 0 : this.cells.size();
        }

        /** 第 {@code index} 格（越界 ⇒ {@code null}）。 */
        public Cell atGrid(int index) {
            return index >= 0 && index < size() ? this.cells.get(index) : null;
        }

        /** 这个槽号的那一格（没有 ⇒ {@code null}）。 */
        public Cell atSlot(int slot) {
            final int index = indexOf(this.ender, slot);
            return index < 0 ? null : atGrid(index);
        }

        /** 有几格有东西。 */
        public int used() {
            int n = 0;
            for (Cell c : this.cells) {
                if (c != null && !c.empty()) {
                    n++;
                }
            }
            return n;
        }

        /** 概况一行（服务端算好，推给客户端显示）。 */
        public String line() {
            final String name = this.ender ? MODE_ENDER : MODE_BACKPACK;
            return name + " " + size() + " 格 · 有东西 " + used() + " 格"
                    + (this.present ? "" : " · 键不在")
                    + (this.rangeVerified ? "" : " · 槽号范围 0–26 未实测")
                    + (this.error == null || this.error.isEmpty() ? "" : " · " + this.error);
        }

        /**
         * 压成一行字（服务端 → 客户端）。
         *
         * <p>格式：{@code ender|格数|present|slot:id×count|slot:id×count|…}（只写有东西的格）。
         * 为什么不用 {@code writeItem}：客户端那一份<b>不需要</b> {@code ItemStack} 在网络上的
         * 完整性（它只用来画个图标 + 数数），而 {@code id} 是磁盘上的原值 —— 用 {@code writeItem}
         * 反而会过一遍注册表、把"这个模组没装"的 id 悄悄变成空气。
         */
        public String encode() {
            final StringBuilder sb = new StringBuilder(256);
            sb.append(this.ender ? 1 : 0).append('|').append(size()).append('|')
                    .append(this.present ? 1 : 0);
            for (Cell c : this.cells) {
                if (c == null || c.empty()) {
                    continue;
                }
                sb.append('|').append(c.slot()).append(':').append(c.id()).append('×').append(c.count());
            }
            return sb.toString();
        }

        /** {@link #encode()} 的逆运算（坏数据 ⇒ 一个空网格 + error，绝不抛到界面线程上）。 */
        public static Grid decode(String raw) {
            final String s = raw == null ? "" : raw;
            final int bar = s.indexOf('|');
            boolean ender = false;
            try {
                ender = !s.isEmpty() && s.charAt(0) == '1';
                if (bar < 0) {
                    final Grid bad = empty(ender);
                    return new Grid(bad.ender, bad.cells, false, "网格数据是空的", bad.rangeVerified);
                }
                final String[] parts = s.substring(bar + 1).split("\\|", -1);
                final boolean present = parts.length > 1 && "1".equals(parts[1].trim());
                final Map<Integer, Cell> bySlot = new LinkedHashMap<>();
                for (int i = 2; i < parts.length; i++) {
                    final String one = parts[i];
                    final int colon = one.indexOf(':');
                    final int times = one.lastIndexOf('×');
                    if (colon <= 0 || times <= colon) {
                        continue;
                    }
                    final int slot = Integer.parseInt(one.substring(0, colon).trim());
                    final String id = one.substring(colon + 1, times).trim();
                    final int count = Integer.parseInt(one.substring(times + 1).trim());
                    bySlot.put(slot, new Cell(slot, id, count));
                }
                final int n = cellCount(ender);
                final List<Cell> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    final int slot = slotAt(ender, i);
                    final Cell c = bySlot.get(slot);
                    list.add(c == null ? Cell.empty(slot) : c);
                }
                return new Grid(ender, List.copyOf(list), present, "",
                        ender ? ShanhaiPlayerDataEdit.ENDER_RANGE_VERIFIED : true);
            } catch (Throwable t) {
                final Grid bad = empty(ender);
                return new Grid(bad.ender, bad.cells, false, "网格解码失败：" + t, bad.rangeVerified);
            }
        }

        /** 数据层的 {@code SlotGrid} ⇒ 本类的 {@link Grid}（唯一的一处转换）。 */
        public static Grid of(ShanhaiPlayerDataEditor.SlotGrid snap) {
            if (snap == null) {
                return empty(false);
            }
            final int n = cellCount(snap.ender());
            final List<Cell> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                // 🔴 下标已经保证合法 ⇒ 这里<b>不许</b>再拿 slot 的正负当"这格不存在"
                //    （副手 = -106 是负数，那样会把副手那格当成空格子，41 格只剩 40 格）。
                final int slot = slotAt(snap.ender(), i);
                final ShanhaiPlayerDataEditor.SlotEntry e = snap.at(slot);
                list.add(e == null ? Cell.empty(slot) : new Cell(slot, e.id(), e.count()));
            }
            return new Grid(snap.ender(), List.copyOf(list), snap.present(),
                    snap.error() == null ? "" : snap.error(), snap.rangeVerified());
        }
    }

    /**
     * 按<b>当前模式</b>读一份格子快照（服务端才调；一个字节都不写）。
     *
     * <p>🔴 这就是"末影箱开关"的落点：{@code ender=true} 时读的是
     * {@link ShanhaiPlayerDataEditor#readEnderItems(Path)}，<b>不是</b>再把 {@code Inventory} 读一遍。
     */
    public static Grid read(Path playerDataFile, boolean ender) {
        return Grid.of(ender ? ShanhaiPlayerDataEditor.readEnderItems(playerDataFile)
                : ShanhaiPlayerDataEditor.readInventory(playerDataFile));
    }

    // ================================================================== 3. 名字（中文在前 / 两种回退）

    /**
     * "把一个物品 id 翻成当前语言的名字"这件事（由 UI 层注入）。
     *
     * <p>为什么是个接口：中文名只有客户端查得到，离线判据要能喂假的名字表
     * （与第 ⑤⑥ 页 {@code ShanhaiSaveEditStatsUi.Namer} 同一条设计）。
     */
    public interface Namer {
        /** 注册表里有没有这个 id（{@code false} ⇒ 回退成 {@code id〔未注册〕}）。 */
        boolean registered(String id);

        /** 当前语言下的名字；查不到 ⇒ {@code null}（回退成 {@code id〔无译名〕}）。 */
        String itemName(String id);
    }

    /** 面板用的真实现（客户端现查注册表 + 语言表）。 */
    public static final Namer ITEM_NAMER = new Namer() {
        @Override
        public boolean registered(String id) {
            try {
                final ResourceLocation rl = ResourceLocation.tryParse(id == null ? "" : id.trim());
                return rl != null && BuiltInRegistries.ITEM.containsKey(rl);
            } catch (Throwable t) {
                return false;               // 注册表没起来（离线探针）/ 其它异常：一律当"没有"，不崩
            }
        }

        @Override
        public String itemName(String id) {
            try {
                final ResourceLocation rl = ResourceLocation.tryParse(id == null ? "" : id.trim());
                if (rl == null) {
                    return null;
                }
                final Item item = BuiltInRegistries.ITEM.get(rl);
                if (item == null || item == Items.AIR) {
                    return null;
                }
                final ItemStack stack = new ItemStack(item);
                final String s = stack.getHoverName().getString();
                if (s == null || s.isEmpty()) {
                    return null;
                }
                // 🔴 语言表没译文时 getString() 把<b>翻译键原样返回</b>（第 ⑤⑥ 页实测过同一件事）。
                //    两个坑都要挡：
                //    ① 方块的物品描述键是 {@code block.minecraft.stone}（不是 item.…）
                //       —— 只挡 "item." 会把 "block.minecraft.dirt" 当成名字显示出去；
                //    ② 最可靠的判据是"返回的串是不是就等于它自己的描述键"（MC 就是这么翻的）。
                final String key = stack.getDescriptionId();
                if (s.equals(key) || s.equals(id) || s.startsWith("item.") || s.startsWith("block.")) {
                    return null;
                }
                return s;
            } catch (Throwable t) {
                return null;
            }
        }
    };

    /**
     * <b>中文在前的物品名</b>：{@code 钻石镐（minecraft:diamond_pickaxe）}。
     *
     * <p>回退（<b>绝不空白、绝不崩</b>）：注册表里没有 ⇒ {@code id〔未注册〕}；
     * 有注册但语言表没译文 ⇒ {@code id〔无译名〕}。
     */
    public static String itemLabel(String id, Namer namer) {
        final String k = id == null ? "" : id.trim();
        if (k.isEmpty()) {
            return "（" + EMPTY_TEXT + "）";
        }
        if (namer == null || !namer.registered(k)) {
            return k + MARK_UNREGISTERED;
        }
        final String name = namer.itemName(k);
        if (name == null || name.isEmpty()) {
            return k + MARK_NO_NAME;
        }
        return name + "（" + k + "）";
    }

    /** 只要名字那半（格子 tooltip / 窄标签用）；回退形态与 {@link #itemLabel} 逐字相同。 */
    public static String itemShort(String id, Namer namer) {
        final String k = id == null ? "" : id.trim();
        if (k.isEmpty()) {
            return "（" + EMPTY_TEXT + "）";
        }
        if (namer == null || !namer.registered(k)) {
            return k + MARK_UNREGISTERED;
        }
        final String name = namer.itemName(k);
        if (name == null || name.isEmpty()) {
            return k + MARK_NO_NAME;
        }
        return name;
    }

    /** 一格的读数一行：{@code 背包12 · 钻石镐（…）×3} / {@code 背包12 · 空}。 */
    public static String cellLine(boolean ender, int index, Grid grid, Namer namer) {
        if (index < 0) {
            return "还没选格子";
        }
        final String where = cellName(ender, index);
        final Cell c = grid == null ? null : grid.atGrid(index);
        if (c == null || c.empty()) {
            return where + " · " + EMPTY_TEXT;
        }
        return where + " · " + itemLabel(c.id(), namer) + "×" + c.count();
    }

    // ================================================================== 4. 数量校验

    /**
     * 数量文本的校验（合法 ⇒ {@code null}；不合法 ⇒ 一句中文理由）。
     *
     * <p>口径就是数据层的 {@link ShanhaiPlayerDataEdit#COUNT_MIN}..{@link ShanhaiPlayerDataEdit#COUNT_MAX}
     * （1..64）—— 本类<b>不再写一份 1/64</b>，免得"界面上能填 65、写盘时被拒"。
     */
    public static String countProblem(String text) {
        final String s = text == null ? "" : text.trim();
        if (s.isEmpty()) {
            return "数量留空：填 " + ShanhaiPlayerDataEdit.COUNT_MIN + ".."
                    + ShanhaiPlayerDataEdit.COUNT_MAX + " 之间的整数";
        }
        final int v;
        try {
            v = Integer.parseInt(s);
        } catch (NumberFormatException nfe) {
            return "数量要是整数（现在是 \"" + s + "\"）";
        }
        if (v < ShanhaiPlayerDataEdit.COUNT_MIN || v > ShanhaiPlayerDataEdit.COUNT_MAX) {
            return "数量要在 " + ShanhaiPlayerDataEdit.COUNT_MIN + ".."
                    + ShanhaiPlayerDataEdit.COUNT_MAX + " 之间（现在是 " + v + "）";
        }
        return null;
    }

    /** 数量文本 ⇒ 整数（不合法 ⇒ {@code fallback}）。 */
    public static int parseCount(String text, int fallback) {
        if (countProblem(text) != null) {
            return fallback;
        }
        return Integer.parseInt(text.trim());
    }

    // ================================================================== 5. 逐格暂存（走数据层的三个入口）

    /** 删掉这一格（暂存，不落盘）。 */
    public static boolean stageRemove(ShanhaiPlayerDataEdit edit, boolean ender, int slot) {
        return edit != null && (ender ? edit.removeEnderSlot(slot) : edit.removeSlot(slot));
    }

    /** 只改这一格的数量（暂存，不落盘）。 */
    public static boolean stageSetCount(ShanhaiPlayerDataEdit edit, boolean ender, int slot, int count) {
        return edit != null && (ender ? edit.setEnderSlotCount(slot, count) : edit.setSlotCount(slot, count));
    }

    /** 把这一格整条换掉（暂存，不落盘）。{@code tagNbt} 可空。 */
    public static boolean stageReplace(ShanhaiPlayerDataEdit edit, boolean ender, int slot, String id,
                                       int count, byte[] tagNbt) {
        return edit != null && (ender ? edit.replaceEnderSlot(slot, id, count, tagNbt)
                : edit.replaceSlot(slot, id, count, tagNbt));
    }

    /** 这一侧暂存的补丁表（{@code null} 安全）。 */
    public static Map<Integer, ShanhaiPlayerDataEdit.SlotPatch> patches(ShanhaiPlayerDataEdit edit,
                                                                       boolean ender) {
        if (edit == null) {
            return Map.of();
        }
        final Map<Integer, ShanhaiPlayerDataEdit.SlotPatch> map =
                ender ? edit.enderPatches : edit.inventoryPatches;
        return map == null ? Map.of() : map;
    }

    /** 这一侧暂存了几个槽（界面上的小红点用）。 */
    public static int[] stagedSlots(ShanhaiPlayerDataEdit edit, boolean ender) {
        final Map<Integer, ShanhaiPlayerDataEdit.SlotPatch> map = patches(edit, ender);
        final int[] out = new int[map.size()];
        int i = 0;
        for (Integer slot : map.keySet()) {
            out[i++] = slot;
        }
        return out;
    }

    /**
     * 这一侧暂存了几处（<b>= {@link #stagedSlots} 的长度 = 格子上红点的个数</b>）。
     *
     * <p>为什么单独给一个方法：界面上那一行是<b>截断</b>的（只列前 4 条 + "…还有 N 处"），
     * 日志/关闭确认层要的是<b>没有截断</b>的数 ⇒ 两边口径必须能各自独立取到，否则
     * "日志写 12 处、界面红点 12 个、而那一行字说 4+8" 这种对不上会反复出现。
     */
    public static int stagedCount(ShanhaiPlayerDataEdit edit, boolean ender) {
        return patches(edit, ender).size();
    }

    /** 两侧加起来几处（关闭确认层那句"已暂存 N 处"里的物品栏部分）。 */
    public static int stagedCountAll(ShanhaiPlayerDataEdit edit) {
        return stagedCount(edit, false) + stagedCount(edit, true);
    }

    /**
     * 这一侧"已暂存 N 处：…"那一行（<b>界面用</b>：最多列 4 条，多的折成"…还有 N 处"）。
     *
     * <p>⚠️ 这条是<b>给人看的</b>，会被 {@code fit(...)} 按显示宽度再截一次 ⇒ <b>不许</b>拿它的
     * 字面去数条数（要数用 {@link #stagedCount} / {@link #stagedSlots}；要全量文本用
     * {@link #stagedLineAll}）。首句的 {@code 已暂存 N 处} 永远是<b>全量</b> N。
     */
    public static String stagedLine(ShanhaiPlayerDataEdit edit, boolean ender) {
        final Map<Integer, ShanhaiPlayerDataEdit.SlotPatch> map = patches(edit, ender);
        if (map.isEmpty()) {
            return (ender ? MODE_ENDER : MODE_BACKPACK) + "这一侧还没暂存任何一格";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("已暂存 ").append(map.size()).append(" 处：");
        int i = 0;
        for (ShanhaiPlayerDataEdit.SlotPatch p : map.values()) {
            if (i >= 4) {
                sb.append("；…还有 ").append(map.size() - i).append(" 处");
                break;
            }
            sb.append(i > 0 ? "；" : "").append(slotName(ender, p.slot)).append(' ').append(p.describe());
            i++;
        }
        return sb.toString();
    }

    /**
     * 这一侧暂存的<b>全量</b>一行（<b>不截断</b>；日志 / 判据 / 关闭确认层用）。
     *
     * <p>与 {@link #stagedLine} 的区别只有"列几条"：这条会把这侧所有补丁逐条列出来，
     * 条数恒等于 {@link #stagedCount}。累积到面板关闭之后，一行可能很长 ——
     * 那是它该有的样子（日志里就是要能数全）。
     */
    public static String stagedLineAll(ShanhaiPlayerDataEdit edit, boolean ender) {
        final Map<Integer, ShanhaiPlayerDataEdit.SlotPatch> map = patches(edit, ender);
        if (map.isEmpty()) {
            return (ender ? MODE_ENDER : MODE_BACKPACK) + "这一侧还没暂存任何一格";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("已暂存 ").append(map.size()).append(" 处：");
        int i = 0;
        for (ShanhaiPlayerDataEdit.SlotPatch p : map.values()) {
            sb.append(i > 0 ? "；" : "").append(slotName(ender, p.slot)).append(' ').append(p.describe())
                    .append(" §8[Slot ").append(p.slot).append(']');
            i++;
        }
        return sb.toString();
    }

    /** 两侧的全量一行（日志用；{@code 背包… ／ 末影箱…}）。 */
    public static String stagedLineAllBoth(ShanhaiPlayerDataEdit edit) {
        return stagedLineAll(edit, false) + " ／ " + stagedLineAll(edit, true);
    }

    // ================================================================== 6. 选物器（纯过滤 / 分页）

    /**
     * 过滤（中文名或 id 都能搜；清空即回到全量）。
     *
     * <p>与 {@code ShanhaiCreativeIconPicker#refresh} 同一条口径，只是这里<b>不建控件</b>
     * —— 于是它能在离线判据里被逐条断言（"搜 钻 能搜到钻石"这种句式在这里是可判的）。
     */
    public static List<String> pickFilter(List<String> all, String query, Namer namer) {
        final List<String> out = new ArrayList<>();
        if (all == null) {
            return out;
        }
        final String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (String id : all) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            if (q.isEmpty() || id.toLowerCase(Locale.ROOT).contains(q)) {
                out.add(id);
                continue;
            }
            final String name = namer == null ? null : namer.itemName(id);
            if (name != null && name.toLowerCase(Locale.ROOT).contains(q)) {
                out.add(id);
            }
        }
        return out;
    }

    /** 过滤后一共几页（至少 1 页）。 */
    public static int pickPageCount(int matched) {
        return Math.max(1, (Math.max(0, matched) + PICK_PER_PAGE - 1) / PICK_PER_PAGE);
    }

    /** 把页号夹进 {@code [0, pageCount-1]}。 */
    public static int pickClampPage(int page, int matched) {
        return Math.max(0, Math.min(pickPageCount(matched) - 1, page));
    }

    /** 第 {@code page} 页的第一条在整份过滤表里的下标。 */
    public static int pickFrom(int page, int matched) {
        return pickClampPage(page, matched) * PICK_PER_PAGE;
    }

    /** 第 {@code page} 页那 48 格（不足就短）。 */
    public static List<String> pickPage(List<String> filtered, int page) {
        if (filtered == null || filtered.isEmpty()) {
            return List.of();
        }
        final int from = pickFrom(page, filtered.size());
        return List.copyOf(filtered.subList(from, Math.min(filtered.size(), from + PICK_PER_PAGE)));
    }

    /** 本页第 {@code cellOnPage} 格在整份过滤表里的下标（越界 ⇒ -1）。 */
    public static int pickIndexAt(int page, int cellOnPage, int matched) {
        if (cellOnPage < 0 || cellOnPage >= PICK_PER_PAGE) {
            return -1;
        }
        final int idx = pickFrom(page, matched) + cellOnPage;
        return idx < matched ? idx : -1;
    }

    // ================================================================== 7. 状态机（两侧各一份）

    /**
     * 覆盖层的状态（<b>纯 JDK</b>：客户端那份负责"看起来对不对"，服务端那份负责"动作能不能做"）。
     *
     * <p>为什么两侧各持一份而不是"全推"：LDLib 的控件树两侧必须<b>结构一致</b>，但状态的字面串
     * 只有服务端算得准（读盘在服务端）。所以"格子里有什么"由服务端推（{@link #grid}），
     * "现在选中了哪一格 / 选物器翻到第几页"两侧各自记（点一下就会两边都动）。
     */
    public static final class Model {

        /** 覆盖层开着吗（切页/关闭都要看它）。 */
        private boolean opened;
        /** 当前数据源：{@code false} = 背包 41 格 / {@code true} = 末影箱 27 格。 */
        private boolean ender;
        /** 选中的格号（-1 = 没选）。 */
        private int selected = -1;
        /** 当前数据源的格子内容（永不为 {@code null}）。 */
        private Grid grid = Grid.empty(false);
        /** 概况那一行（服务端算好推来）。 */
        private String gridLine = "";
        /** "已暂存 …"那一行（服务端算好推来）。 */
        private String stagedLine = "";
        /** 这一侧已暂存的槽号（界面上给格子打点用）。 */
        private int[] stagedSlots = new int[0];
        /** 上一次动作的结果（服务端算好推来）。 */
        private String feedback = "";
        /** 选物器开着吗（只影响客户端）。 */
        private boolean picking;
        /** 选物器的搜索串（客户端现读输入框）。 */
        private String pickQuery = "";
        /** 选物器第几页。 */
        private int pickPage;
        /**
         * 选物器里选中的<b>物品 id</b>（空串 = 没选）。
         *
         * <p>🔴 存 id 而不是"过滤表里的下标"：过滤要看中文名，而服务端<b>没有语言表</b>
         * （同一条过滤在两侧会给出不同的表）⇒ 用下标跨端对账就会"客户端选钻石、服务端换成泥土"。
         * 存 id 之后两侧对的是同一个东西。
         */
        private String pendingPickId = "";

        /** 打开（两侧各调一次）：清掉上一次的选中 / 选物器状态。 */
        public void open() {
            this.opened = true;
            this.selected = -1;
            this.feedback = "";
            this.picking = false;
            this.pickQuery = "";
            this.pickPage = 0;
            this.pendingPickId = "";
        }

        /** 关掉（两侧各调一次）：连格子内容一起清（下次打开必须重新读盘）。 */
        public void close() {
            this.opened = false;
            this.selected = -1;
            this.picking = false;
            this.pickQuery = "";
            this.pickPage = 0;
            this.pendingPickId = "";
            this.feedback = "";
            this.gridLine = "";
            this.stagedLine = "";
            this.stagedSlots = new int[0];
            this.grid = Grid.empty(this.ender);
        }

        public boolean opened() {
            return this.opened;
        }

        public boolean ender() {
            return this.ender;
        }

        /** 当前档位的名字（{@code 背包} / {@code 末影箱}）。 */
        public String modeName() {
            return this.ender ? MODE_ENDER : MODE_BACKPACK;
        }

        /** 直接设定档位（读盘结果回来时用）。 */
        public void setEnder(boolean value) {
            if (this.ender != value) {
                this.ender = value;
                this.grid = Grid.empty(value);
                this.selected = -1;
                this.picking = false;
                this.pendingPickId = "";
                this.pickPage = 0;
            }
        }

        /**
         * 开关翻一档。
         *
         * @return 新的档位（{@code true} = 末影箱）
         */
        public boolean toggleMode() {
            setEnder(!this.ender);
            return this.ender;
        }

        public Grid grid() {
            return this.grid;
        }

        /** 灌一份格子内容（服务端读盘 / 客户端解码后都走这里）。 */
        public void setGrid(Grid value) {
            if (value != null) {
                this.ender = value.ender();
                this.grid = value;
            }
        }

        /** 这一侧几格（41 / 27）。 */
        public int cellCount() {
            return this.grid.size() > 0 ? this.grid.size() : ShanhaiSaveEditInventoryUi.cellCount(this.ender);
        }

        /** 第 {@code index} 格（越界 ⇒ {@code null}）。 */
        public Cell cell(int index) {
            return this.grid.atGrid(index);
        }

        public int selected() {
            return this.selected;
        }

        /** 选一格（越界 ⇒ 当成"取消选中"）。 */
        public void select(int index) {
            this.selected = index >= 0 && index < cellCount() ? index : -1;
        }

        public void clearSelection() {
            this.selected = -1;
        }

        /** 选中那格的槽号（没选 ⇒ -1）。 */
        public int selectedSlot() {
            return this.selected < 0 ? -1 : slotAt(this.ender, this.selected);
        }

        public String gridLine() {
            return this.gridLine;
        }

        public void setGridLine(String value) {
            this.gridLine = value == null ? "" : value;
        }

        public String stagedLine() {
            return this.stagedLine;
        }

        public void setStagedLine(String value) {
            this.stagedLine = value == null ? "" : value;
        }

        public int[] stagedSlots() {
            return this.stagedSlots;
        }

        public void setStagedSlots(int[] value) {
            this.stagedSlots = value == null ? new int[0] : value.clone();
        }

        /** 这个槽号有没有暂存（给格子打点）。 */
        public boolean stagedSlot(int slot) {
            final int canon = ShanhaiPlayerDataEdit.canonicalSlot(slot);
            for (int s : this.stagedSlots) {
                if (ShanhaiPlayerDataEdit.canonicalSlot(s) == canon) {
                    return true;
                }
            }
            return false;
        }

        public String feedback() {
            return this.feedback;
        }

        public void setFeedback(String value) {
            this.feedback = value == null ? "" : value;
        }

        public boolean picking() {
            return this.picking;
        }

        /** 打开选物器（清搜索 / 回第 1 页 / 没选中）。 */
        public void openPicker() {
            this.picking = true;
            this.pickQuery = "";
            this.pickPage = 0;
            this.pendingPickId = "";
        }

        public void closePicker() {
            this.picking = false;
            this.pickQuery = "";
            this.pickPage = 0;
            this.pendingPickId = "";
        }

        public String pickQuery() {
            return this.pickQuery;
        }

        public void setPickQuery(String value) {
            final String q = value == null ? "" : value;
            if (!q.equals(this.pickQuery)) {
                this.pickQuery = q;
                this.pickPage = 0;
                this.pendingPickId = "";
            }
        }

        public int pickPage() {
            return this.pickPage;
        }

        public void setPickPage(int value, int matched) {
            this.pickPage = pickClampPage(value, matched);
        }

        /** 选物器里选中的 id（没选 ⇒ 空串）。 */
        public String pendingPickId() {
            return this.pendingPickId;
        }

        /** 记下选物器里选中的 id（空/空白 = 取消选中）。 */
        public void setPendingPickId(String value) {
            this.pendingPickId = value == null ? "" : value.trim();
        }
    }

    // ================================================================== 8. 一行读数

    /** 给日志/判据用的一行（不依赖任何实例状态）。 */
    public static String layoutLine() {
        return "inventory_overlay cells=" + cellCount(false) + "/" + cellCount(true)
                + " ender_verified=" + ShanhaiPlayerDataEdit.ENDER_RANGE_VERIFIED
                + " segments=" + SEGMENTS.length
                + " pick=" + PICK_COLS + "x" + PICK_ROWS + "(" + PICK_PER_PAGE + "/page)"
                + " offhand=" + ShanhaiPlayerDataEdit.OFFHAND_SLOT
                + " alias=" + ShanhaiPlayerDataEdit.OFFHAND_SLOT_UNSIGNED_LEGACY;
    }

    /** 一段的真值表一行（判据里逐段打印；也进日志）。 */
    public static String segmentsLine() {
        final StringBuilder sb = new StringBuilder();
        for (Segment s : SEGMENTS) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(s.key()).append("[").append(s.from()).append("..").append(s.to())
                    .append(" x").append(s.count()).append(']');
        }
        return sb.toString();
    }

    /** 41 格的槽号表一行（判据/日志：一眼能看出副手是 -106）。 */
    public static String gridLine(boolean ender) {
        final StringBuilder sb = new StringBuilder();
        final int n = cellCount(ender);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(slotAt(ender, i));
        }
        return sb.toString();
    }
}
