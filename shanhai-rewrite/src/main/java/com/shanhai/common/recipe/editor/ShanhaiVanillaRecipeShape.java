package com.shanhai.common.recipe.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shanhai.ShanhaiMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>非 GT 配方「输入侧」的统一可编辑中间表示（IR）</b> —— 阶段 2 的核心数据结构。
 *
 * <h2>1. 🔴 为什么需要一层 IR：每种类型的字段形态都不一样</h2>
 * <pre>
 *   序列化器 id                  原版字段                    本 IR 的 Mode
 *   ─────────────────────────────────────────────────────────────────────
 *   minecraft:crafting_shaped    pattern[] + key{}           GRID   （3×3 网格）
 *   minecraft:crafting_shapeless ingredients[]               LIST   （无序列表）
 *   minecraft:smelting           ingredient + result +       SINGLE （单格输入）
 *   minecraft:blasting             experience + cookingtime  SINGLE
 *   minecraft:smoking                                        SINGLE
 *   minecraft:campfire_cooking                               SINGLE
 *   minecraft:stonecutting       ingredient + result         SINGLE （数量恒为 1）
 *   其余 55 个类型（各 1–173 条） 各自私有（framedblocks / ae2 / avaritia …）
 *                                                            NONE   （本刀不支持 ⇒ 只读 + 可见提示）
 * </pre>
 * 面板、台账、重建、落盘四条路<b>全部只跟这一层打交道</b>，不再各自去认原版的具体类
 * （那是 {@link ShanhaiVanillaRecipeView} 的活）。
 *
 * <h2>2. 🔴 三种 Mode 的语义</h2>
 * <table border="1">
 *   <tr><th>Mode</th><th>可编辑的格子</th><th>增</th><th>删</th></tr>
 *   <tr><td>{@code GRID}</td>
 *       <td>固定 3×3 的 9 个"物理格子"，其中右上角的 {@code w×h} 子矩形是"这个配方实际占的形状"
 *           （{@code w,h ∈ 1..3}，所以 2×3 / 1×1 都表达得出来）</td>
 *       <td>往空格子里放东西（必要时先放大 {@code w/h}）</td>
 *       <td>右键清空那一格（变成空格）；缩小 {@code w/h} 也能删，但外侧还有东西时会被拒</td></tr>
 *   <tr><td>{@code LIST}</td>
 *       <td>{@code 1..9} 个槽（顺序无关；上限 9 = 原版合成容器就是 3×3）</td>
 *       <td>「加一格」追加一个空槽，再往里拖物品</td>
 *       <td>右键删掉那一格（只剩 1 格时拒删）</td></tr>
 *   <tr><td>{@code SINGLE}</td>
 *       <td>恰好 1 格</td>
 *       <td>不适用（已给出可见提示）</td>
 *       <td>不适用（给可见提示，不允许把输入清空）</td></tr>
 * </table>
 *
 * <h2>3. 🔴 每一格记着「底本那一格的 Ingredient」—— 这一条是"不毁数据"的关键</h2>
 * 原版配方的输入常常是<b>标签</b>（{@code {"tag":"forge:ingots/iron"}}）或<b>多候选</b>
 * （{@code [{"item":"a"},{"item":"b"}]}），而界面上只能显示一个代表性物品。
 * 如果重建时一律"按界面上显示的那个物品"造 {@code Ingredient}，
 * 那每保存一次就会把 8000+ 条标签配方<b>静默退化成单物品</b>。
 * ⇒ 本 IR 的每一格都同时持两份：
 * <pre>
 *   base   = 底本那一格的 Ingredient（原对象；新增的格子为 null）
 *   shown  = 界面上显示 / 将要写入的那个 ItemStack
 *   dirty  = 用户动过没有
 * 重建规则：dirty == false ⇒ 【原样放回 base】（一个字节都不动）
 *           dirty == true  ⇒ Ingredient.of(shown)
 * </pre>
 * 这条纪律与 GT 那条线 {@code ShanhaiIoTable.Cell#dirty} 的语义逐字同构。
 *
 * <h2>4. 🔴 有形状合成：同一个物品出现在多格时必须复用同一个字符</h2>
 * {@code key} 是「字符 → 物品」的映射。如果每个格子各给一个字符，一条 9 格的配方会长出 9 个键；
 * 原版的写法是"同一种材料只写一个字符"。
 * ⇒ 本 IR 按<b>该格最终 Ingredient 的规范化 JSON 串</b>去重（{@link #keysOf}），
 * 同一份 JSON 只分配一个字符。这样"标签配方"与"手放的同一种物品"都会正确合并，
 * 而"形状本身"（哪格空、哪格是什么）一个像素都不变。
 *
 * <h2>5. 规范化（trim）：把外圈整行/整列的空格去掉</h2>
 * 原版从 JSON 读进来的 {@code pattern} 本来就是<b>去过外圈空格</b>的
 * （{@code ShapedRecipe.Serializer} 的 {@code firstNonSpace/lastNonSpace}）。
 * 用户在 3×3 界面上把物品摆在右下角时，如果原样写出去，那条配方就变成
 * "必须把材料摆在右下角才认"——那不是他的意思。
 * ⇒ 保存时按<b>去外圈空行/空列</b>规范化（{@link #trimmedBounds()}），并把这件事打进日志。
 */
public final class ShanhaiVanillaRecipeShape {

    public static final String PREFIX = "[SHANHAI-EDIT] editor";

    /** 网格边长上限（原版合成容器就是 3×3）。 */
    public static final int MAX_SIDE = 3;
    /** 网格格子总数。 */
    public static final int GRID_CELLS = MAX_SIDE * MAX_SIDE;
    /** 无序列表的槽数上限（= 原版合成容器的 9 格）。 */
    public static final int MAX_LIST = 9;

    /** 这一份输入用哪种形态编辑。 */
    public enum Mode {
        /** 有形状合成：3×3 网格。 */
        GRID,
        /** 无形状合成：无序列表。 */
        LIST,
        /** 烧炼那一族 ＋ 切石机：单格输入。 */
        SINGLE,
        /** 本刀不支持编辑的类型（列表里看得见、面板给只读提示）。 */
        NONE
    }

    /** 一格。 */
    public static final class Slot {
        /** 底本那一格的 Ingredient（{@code null} = 这一格是用户新加的，底本里没有）。 */
        public final Ingredient base;
        /** 界面上显示 / 将要写入的那个栈（{@code EMPTY} = 空格）。 */
        public final ItemStack shown;
        /** 用户动过没有（{@code false} ⇒ 重建时原样放回 {@link #base}，保住标签与多候选）。 */
        public final boolean dirty;

        Slot(Ingredient base, ItemStack shown, boolean dirty) {
            this.base = base;
            this.shown = shown == null ? ItemStack.EMPTY : shown;
            this.dirty = dirty;
        }

        boolean sameAs(Slot o) {
            if (o == null || dirty != o.dirty) {
                return false;
            }
            return sameStack(shown, o.shown);
        }
    }

    // ---------------------------------------------------------------- 状态

    private final Mode mode;
    private final ShanhaiVanillaRecipeView.Kind kind;
    private final ResourceLocation id;
    private final ResourceLocation typeId;

    /** {@link Mode#GRID}：9 格（下标 = {@code y*3 + x}）。其它 Mode 恒为 null。 */
    private Slot[] grid;
    /** {@link Mode#LIST} / {@link Mode#SINGLE}：列表（{@link Mode#SINGLE} 恒为 1 个）。 */
    private List<Slot> list;

    private int w;
    private int h;

    /** {@link Mode#GRID} 的"未被裁剪"的外接尺寸（用户按的宽/高；保存时会 trim）。 */
    private int declaredW;
    private int declaredH;

    private ShanhaiVanillaRecipeShape(Mode mode, ShanhaiVanillaRecipeView.Kind kind,
                                      ResourceLocation id, ResourceLocation typeId) {
        this.mode = mode;
        this.kind = kind;
        this.id = id;
        this.typeId = typeId;
    }

    // ---------------------------------------------------------------- 构造

    /** 按<b>具体类</b>决定用哪种编辑形态（与 {@link ShanhaiVanillaRecipeView.Kind} 一一对应）。 */
    public static Mode modeOf(ShanhaiVanillaRecipeView.Kind kind) {
        if (kind == null) {
            return Mode.NONE;
        }
        return switch (kind) {
            case SHAPED -> Mode.GRID;
            case SHAPELESS -> Mode.LIST;
            case COOKING, STONECUTTING -> Mode.SINGLE;
            default -> Mode.NONE;
        };
    }

    /**
     * 从一条（活或底本）配方造出 IR。
     *
     * @return 永不为 null（不支持的类型给 {@link Mode#NONE}，仍然带 id/typeId 供提示用）；
     *         传 null 才返回 null。
     */
    public static ShanhaiVanillaRecipeShape of(Recipe<?> r) {
        if (r == null) {
            return null;
        }
        final ShanhaiVanillaRecipeView v = ShanhaiVanillaRecipeView.of(r);
        if (v == null) {
            return null;
        }
        final ShanhaiVanillaRecipeShape s =
                new ShanhaiVanillaRecipeShape(modeOf(v.kind), v.kind, v.id, v.typeId);
        s.loadFrom(v);
        return s;
    }

    private void loadFrom(ShanhaiVanillaRecipeView v) {
        switch (mode) {
            case GRID -> {
                // 🔴 底本已经是被原版规范化过的（pattern 外圈没有空行/空列）⇒ 直接按 w×h 落在左上角。
                //    如果底本里出现了"外圈有空行"的怪形状（别的 mod 直接 new 出来的），
                //    这里也照实读进来（declaredW/H 用底本自己的 w/h），不擅自改。
                final int bw = Math.max(1, Math.min(MAX_SIDE, v.width <= 0 ? 1 : v.width));
                final int bh = Math.max(1, Math.min(MAX_SIDE, v.height <= 0 ? 1 : v.height));
                this.declaredW = bw;
                this.declaredH = bh;
                this.w = bw;
                this.h = bh;
                this.grid = new Slot[GRID_CELLS];
                for (int i = 0; i < GRID_CELLS; i++) {
                    final int x = i % MAX_SIDE;
                    final int y = i / MAX_SIDE;
                    // 🔴🔴 第 12 刀修的真 bug（用户截图：2×2 的配方只画出 3 个木板）：
                    //    这里原来写的是 `v.inputs.get(i)` —— 而 `i` 是【3 宽的网格下标】、
                    //    `v.inputs` 是【按配方宽度 bw 打包】的（ShapedRecipe.getIngredients() 的
                    //    下标是 y*w + x，w = 这条配方自己的宽）。
                    //    w == 3 时两者恰好相同（所以 3×3 的配方一直是对的）；
                    //    w == 2 时：格子 (1,1) 去取 inputs[4] —— 越界 ⇒ 那一格凭空变空
                    //      （读数：crafting_table 是 2×2，view 说 in=4，网格上只有 3 个）；
                    //    w == 1 时：(0,1) 去取 inputs[3] ⇒ 取到不存在或【错位】的那一件。
                    //    ⇒ 正确下标是 y*bw + x。配上"整张 3×3 都可放"之后，两边口径一致。
                    final Ingredient base = (x < bw && y < bh && (y * bw + x) < v.inputs.size())
                            ? v.inputs.get(y * bw + x) : null;
                    final ItemStack shown = ShanhaiVanillaRecipeView.representative(base);
                    grid[i] = new Slot(base, shown, false);
                }
            }
            case LIST -> {
                this.list = new ArrayList<>();
                for (Ingredient in : v.inputs) {
                    list.add(new Slot(in, ShanhaiVanillaRecipeView.representative(in), false));
                }
                if (list.isEmpty()) {
                    // 理论上不会发生（无形状合成至少有 1 个输入）—— 真发生了就如实留一个空槽，
                    // 保存时会被"空槽拒收"那条拦下，不会静默产出一条空配方。
                    list.add(new Slot(null, ItemStack.EMPTY, false));
                }
            }
            case SINGLE -> {
                this.list = new ArrayList<>();
                final Ingredient base = v.inputs.isEmpty() ? null : v.inputs.get(0);
                list.add(new Slot(base, ShanhaiVanillaRecipeView.representative(base), false));
            }
            default -> {
                // NONE：不建任何格子
            }
        }
    }

    // ---------------------------------------------------------------- 读数

    public Mode mode() {
        return mode;
    }

    public ShanhaiVanillaRecipeView.Kind kind() {
        return kind;
    }

    public ResourceLocation id() {
        return id;
    }

    public ResourceLocation typeId() {
        return typeId;
    }

    /**
     * 网格宽（只对 {@link Mode#GRID} 有意义）。
     *
     * <p>🆕 第 12 刀口径：= <b>这条配方此刻真正占的形状</b>（裁剪掉外圈空行空列之后），
     * 而不是"用户按出来的那个外接框"。理由：第 11 刀把「宽 +/高 +」四颗按钮删了
     * （用户点名删的、「工作台是固定的 3×3、不会变」）⇒ 外接框不再是一个用户概念，
     * 屏幕上与日志里该出现的就只剩"它现在占几格"。
     */
    public int width() {
        if (mode != Mode.GRID) {
            return -1;
        }
        final int[] b = trimmedBounds();
        return b == null ? Math.max(1, Math.min(MAX_SIDE, w)) : (b[2] - b[0] + 1);
    }

    /** 网格高（只对 {@link Mode#GRID} 有意义）。🆕 第 12 刀：同 {@link #width()}。 */
    public int height() {
        if (mode != Mode.GRID) {
            return -1;
        }
        final int[] b = trimmedBounds();
        return b == null ? Math.max(1, Math.min(MAX_SIDE, h)) : (b[3] - b[1] + 1);
    }

    /**
     * 这一份输入"用了几格"。
     *
     * <p>🆕 第 12 刀口径：GRID = <b>裁剪之后那张形状的面积</b>（= 保存时会写进 pattern 的格数）；
     * LIST = 材料样数；SINGLE = 1。面板那两行读数与自检都以它为准。
     */
    public int slotCount() {
        return switch (mode) {
            case GRID -> {
                final int[] b = trimmedBounds();
                yield b == null ? 0 : (b[2] - b[0] + 1) * (b[3] - b[1] + 1);
            }
            case LIST -> list.size();
            case SINGLE -> 1;
            default -> 0;
        };
    }

    /** 面板那条"格子区"总共有几个物理位置（🆕 第 12 刀起：GRID 与 LIST 都是 9 格 —— 同一套网格）。 */
    public int layoutCount() {
        return switch (mode) {
            case GRID, LIST -> GRID_CELLS;
            case SINGLE -> 1;
            default -> 0;
        };
    }

    /**
     * 第 {@code i} 个物理位置此刻算不算"这一份输入的一部分"。
     *
     * <h4>🔴 第 12 刀：GRID 与 LIST <b>都</b>是"整张 3×3 都能放"</h4>
     * 用户拍板（原话）：
     * <blockquote>「如果我想要2个红蘑菇，那我直接在网格里面再补一个不就行了吗，
     * 而且它没网格你仍然可以把物品列举出来，只需要有一个切换是否为无序合成的按钮不就行了吗」<br>
     * 「主要是这样玩家好操作你懂吧，不然太麻烦了」</blockquote>
     * ⇒ 有序与无序<b>用同一套网格、同一套操作</b>（拖进去／右键删／中键改数量），
     * 玩家不用学两套；两屏的差别只剩「保存时写什么」（有形状 = pattern+key；无形状 = ingredients）
     * 与那一句提示（"位置有意义"／"位置无所谓"）。
     *
     * <p>顺带修掉一个自相矛盾：第 11 刀把「宽 +/高 +」四颗按钮删了（用户点名删的），
     * 但 GRID 的"格子外"那几格仍然 {@code active=false} ⇒ 往右上角拖会被拒、提示的却是一颗
     * <b>已经不存在的按钮</b>。现在整张 3×3 都能放，"要不要缩"由保存时的裁剪（{@link #trimmedBounds}）
     * 自动决定 —— 与"网格只是展示，位置代表什么由配方类型决定"完全一致。
     */
    public boolean active(int i) {
        if (i < 0) {
            return false;
        }
        return switch (mode) {
            case GRID, LIST -> i < GRID_CELLS;
            case SINGLE -> i == 0;
            default -> false;
        };
    }

    /**
     * 第 {@code i} 格的那一份槽（{@code null} = 这一格现在是空的）。
     *
     * <p>⚠️ LIST 的"空"与 GRID 的"空"不同：GRID 的空格是一个<b>真实存在的槽</b>（{@code Slot}），
     * 而 LIST 的第 {@code i}（{@code i >= list.size()}）格<b>压根不是槽</b> ——
     * 往里拖东西 = <b>追加一样新材料</b>（见 {@link #setAt}）。
     */
    public Slot slot(int i) {
        if (i < 0) {
            return null;
        }
        return switch (mode) {
            case GRID -> i < GRID_CELLS ? grid[i] : null;
            case LIST -> i < list.size() ? list.get(i) : null;
            case SINGLE -> i == 0 && !list.isEmpty() ? list.get(0) : null;
            default -> null;
        };
    }

    public ItemStack shown(int i) {
        final Slot s = slot(i);
        return s == null ? ItemStack.EMPTY : s.shown;
    }

    /** 空格子算不算"这一格没东西"。 */
    public boolean isEmptyAt(int i) {
        if (i < 0 || !active(i)) {
            return true;
        }
        final Slot s = slot(i);
        return s == null || s.shown.isEmpty();
    }

    // ---------------------------------------------------------------- 改：增 / 删 / 改

    /**
     * 把第 {@code i} 格换成 {@code stack}（＝「改」；往空格里放就是「增」）。
     *
     * <h4>🆕 第 12 刀：LIST 的"增"</h4>
     * 无序合成的第 {@code i} 格如果<b>还没有槽</b>（{@code i >= list.size()}），
     * 就往列表末尾<b>追加一样新材料</b> —— 这正是用户那句「我想要 2 个红蘑菇，
     * 那我直接在网格里面再补一个不就行了吗」。追加位置取"第一个空位"，
     * 因为无序列表里<b>位置没有意义</b>（面板上那一行会明说）。
     *
     * @return true = 收下了
     */
    public boolean setAt(int i, ItemStack stack) {
        if (!active(i)) {
            return false;
        }
        final ItemStack v = stack == null ? ItemStack.EMPTY : stack.copy();
        switch (mode) {
            case GRID -> {
                final Slot old = grid[i];
                grid[i] = new Slot(old == null ? null : old.base, v, true);
                return true;
            }
            case SINGLE -> {
                final Slot old = list.get(0);
                list.set(0, new Slot(old.base, v, true));
                return true;
            }
            case LIST -> {
                if (i < list.size()) {
                    final Slot old = list.get(i);
                    list.set(i, new Slot(old.base, v, true));
                } else {
                    if (list.size() >= MAX_LIST) {
                        return false;
                    }
                    list.add(new Slot(null, v, true));
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * 清空第 {@code i} 格（右键删）。
     *
     * <ul>
     *   <li>{@link Mode#GRID}：允许 —— 变成一个空格（"这个位置不要东西"是合法形状）；</li>
     *   <li>{@link Mode#LIST}：<b>不</b>在这里做 —— 列表的"删"是 {@link #removeAt}（要挪位），
     *       直接清空会留下一个空槽（那是保存时会被拒的形状）；</li>
     *   <li>{@link Mode#SINGLE}：<b>拒绝</b> —— 单格输入清掉就等于造一条没有输入的配方。</li>
     * </ul>
     *
     * @return true = 收下了
     */
    public boolean clearAt(int i) {
        if (!active(i)) {
            return false;
        }
        if (mode != Mode.GRID) {
            return false;
        }
        final Slot old = grid[i];
        grid[i] = new Slot(old.base, ItemStack.EMPTY, true);
        return true;
    }

    /** 这一格能不能"右键清空"（面板据此给不同的提示）。 */
    public boolean canClearAt(int i) {
        return mode == Mode.GRID && active(i);
    }

    /** 这一格能不能"右键删掉"（列表专属；剩 1 格时不给删）。 */
    public boolean canRemoveAt(int i) {
        return mode == Mode.LIST && active(i) && list.size() > 1;
    }

    /** {@link Mode#LIST} 专属：右键删掉第 {@code i} 格（后面的往前挪）。 */
    public boolean removeAt(int i) {
        if (!canRemoveAt(i)) {
            return false;
        }
        list.remove(i);
        return true;
    }

    /** {@link Mode#LIST} 专属：追加一个空槽（上限 {@link #MAX_LIST}）。 */
    public boolean addEntry() {
        if (mode != Mode.LIST || list.size() >= MAX_LIST) {
            return false;
        }
        list.add(new Slot(null, ItemStack.EMPTY, true));
        return true;
    }

    /**
     * {@link Mode#GRID} 专属：改宽/高（1..{@link #MAX_SIDE}）。
     *
     * <p>🔴 缩小之前要检查被切掉的那一圈<b>是不是空的</b>：里面有东西就<b>拒绝</b>
     * （返回 false 并打一条 WARN）—— 静默丢掉用户摆好的材料是本工程最不能接受的那类错。
     */
    public boolean resize(int newW, int newH) {
        if (mode != Mode.GRID) {
            return false;
        }
        final int nw = Math.max(1, Math.min(MAX_SIDE, newW));
        final int nh = Math.max(1, Math.min(MAX_SIDE, newH));
        if (nw == w && nh == h) {
            return false;
        }
        int lost = 0;
        for (int i = 0; i < GRID_CELLS; i++) {
            final int x = i % MAX_SIDE;
            final int y = i / MAX_SIDE;
            final boolean inside = x < nw && y < nh;
            if (!inside && !grid[i].shown.isEmpty()) {
                lost++;
            }
        }
        if (lost > 0) {
            ShanhaiMod.LOGGER.warn("{} vanilla_shape_resize_refused id={} {}x{} -> {}x{} "
                            + "reason=外侧还有 {} 格材料（先删掉它们，或先把它们挪进新的范围）",
                    PREFIX, id, w, h, nw, nh, lost);
            return false;
        }
        w = nw;
        h = nh;
        declaredW = nw;
        declaredH = nh;
        return true;
    }

    /** 网格"外接尺寸"（不含 trim）。 */
    public int declaredWidth() {
        return mode == Mode.GRID ? declaredW : -1;
    }

    public int declaredHeight() {
        return mode == Mode.GRID ? declaredH : -1;
    }

    // ---------------------------------------------------------------- 脏判定

    /**
     * 深拷贝一份（台账里存的是<b>拷贝</b>，这样"撤销/回滚"能真正回到上一拍，
     * 而"用户继续编辑"也不会隔着台账改到已经记下的那一份）。
     */
    public ShanhaiVanillaRecipeShape copy() {
        final ShanhaiVanillaRecipeShape c =
                new ShanhaiVanillaRecipeShape(mode, kind, id, typeId);
        c.w = w;
        c.h = h;
        c.declaredW = declaredW;
        c.declaredH = declaredH;
        if (grid != null) {
            c.grid = new Slot[GRID_CELLS];
            for (int i = 0; i < GRID_CELLS; i++) {
                final Slot s = grid[i];
                c.grid[i] = s == null ? null : new Slot(s.base, s.shown.copy(), s.dirty);
            }
        }
        if (list != null) {
            c.list = new ArrayList<>(list.size());
            for (Slot s : list) {
                c.list.add(new Slot(s.base, s.shown.copy(), s.dirty));
            }
        }
        return c;
    }

    /** 用户有没有动过输入。 */
    public boolean isDirty() {
        return switch (mode) {
            case GRID -> {
                for (int i = 0; i < GRID_CELLS; i++) {
                    if (grid[i].dirty) {
                        yield true;
                    }
                }
                yield false;
            }
            case LIST, SINGLE -> {
                boolean any = false;
                for (Slot s : list) {
                    if (s.dirty) {
                        any = true;
                        break;
                    }
                }
                yield any;
            }
            default -> false;
        };
    }

    /** 这一份输入此刻能不能被重建（保存前的最后一道闸）。 */
    public boolean valid() {
        return switch (mode) {
            case GRID -> {
                // 至少要有一格有东西（全是空格的形状不是配方）
                boolean any = false;
                for (int i = 0; i < GRID_CELLS; i++) {
                    if (active(i) && !grid[i].shown.isEmpty()) {
                        any = true;
                        break;
                    }
                }
                yield any;
            }
            case LIST -> {
                // 🆕 第 12 刀：无序列表也画成 3×3，但 list 里【只有真材料】（空格是"还没放的位子"，
                //    根本不进 list）⇒ 这里只要"至少有一样材料"。
                boolean any = false;
                for (Slot s : list) {
                    if (s.shown.isEmpty()) {
                        yield false;         // 有空槽 ⇒ 拒收（宁可什么都不写，也不写半条）
                    }
                    any = true;
                }
                yield any;
            }
            case SINGLE -> !list.isEmpty() && !list.get(0).shown.isEmpty();
            default -> false;
        };
    }

    /** 不合法时给用户看的那一句话（为空 = 合法）。 */
    public String invalidReason() {
        if (valid()) {
            return "";
        }
        if (mode == Mode.NONE) {
            return "这个配方类型本版还不支持编辑输入。";
        }
        if (mode == Mode.GRID) {
            return "有形状合成至少要放一样材料（现在整张网格都是空的）。";
        }
        if (mode == Mode.LIST) {
            for (Slot s : list) {
                if (s.shown.isEmpty()) {
                    return "无序列表里还有空格子：请往里拖一个物品，或右键把那一格删掉。";
                }
            }
            return "无序列表里至少要留一样材料。";
        }
        return "单格输入不能为空。";
    }

    // ---------------------------------------------------------------- 给重建用

    /**
     * 第 {@code i} 格最终该用哪个 {@link Ingredient}。
     *
     * <p>{@code dirty == false} ⇒ <b>原样返回底本那个对象</b>（标签、多候选、NBT 全保住）。
     */
    public Ingredient ingredientAt(int i) {
        return ingredientOf(slot(i));
    }

    /**
     * 一个 {@link Ingredient} 的<b>规范化 JSON 串</b>（去重用；也用于写 {@code key} 表）。
     * <p>失败时返回 {@code "__unreadable__<序号>"} 这种一次性串 ⇒ 那一格单独占一个字符，
     * <b>不会</b>与别的格子错误地合并。
     */
    private static String canonicalJson(Ingredient in, int seq) {
        if (in == null) {
            return "__null__" + seq;
        }
        try {
            final JsonElement el = in.toJson();
            return el == null ? "__nulljson__" + seq
                    : ShanhaiRecipeFingerprint.canonicalize(el).toString();
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} vanilla_shape_ingredient_json_failed err={}", PREFIX, t.toString());
            return "__unreadable__" + seq;
        }
    }

    /**
     * 有形状合成：把"哪一格是什么"摊成 {@code pattern} ＋ {@code key}。
     *
     * <h4>规范化的两件事</h4>
     * <ol>
     *   <li><b>去外圈空行/空列</b>（原版从 JSON 读进来时就是这么做的）；
     *       结果宽高可能比用户按的 {@code w/h} 小 —— 这正是"形状可以缩小"的表达方式；</li>
     *   <li><b>同一种材料复用同一个字符</b>：按 {@link #canonicalJson} 去重，字符从 {@code 'a'} 起。</li>
     * </ol>
     *
     * @return {@code {pattern:[…], key:{…}, width, height}}；这份输入不合法时返回 {@code null}
     */
    public JsonObject shapedFields() {
        if (mode != Mode.GRID || !valid()) {
            return null;
        }
        final int[] b = trimmedBounds();
        if (b == null) {
            return null;
        }
        final int minX = b[0];
        final int minY = b[1];
        final int maxX = b[2];
        final int maxY = b[3];
        final Map<String, Character> charOf = new LinkedHashMap<>();
        final JsonObject key = new JsonObject();
        final JsonArray pattern = new JsonArray();
        int seq = 0;
        for (int y = minY; y <= maxY; y++) {
            final StringBuilder row = new StringBuilder();
            for (int x = minX; x <= maxX; x++) {
                final int i = y * MAX_SIDE + x;
                if (grid[i].shown.isEmpty()) {
                    row.append(' ');
                    continue;
                }
                final Ingredient in = ingredientAt(i);
                final String j = canonicalJson(in, seq++);
                Character ch = charOf.get(j);
                if (ch == null) {
                    ch = (char) ('a' + charOf.size());
                    charOf.put(j, ch);
                    key.add(String.valueOf(ch), ingredientJson(in));
                }
                row.append(ch);
            }
            pattern.add(row.toString());
        }
        final JsonObject out = new JsonObject();
        out.add("pattern", pattern);
        out.add("key", key);
        out.addProperty("width", maxX - minX + 1);
        out.addProperty("height", maxY - minY + 1);
        return out;
    }

    /** {@code Ingredient} → 写进 {@code key} 表的那份 JSON（失败给 {@code {"item":"minecraft:air"}} 之外的空对象，调用方会判 valid）。 */
    private static JsonElement ingredientJson(Ingredient in) {
        try {
            final JsonElement el = in.toJson();
            if (el != null) {
                return el;
            }
        } catch (Throwable ignored) {
            // 落到下面
        }
        return new JsonObject();
    }

    /**
     * 有形状合成的"外接矩形"（去掉外圈整行/整列的空格）。全空 ⇒ {@code null}。
     *
     * @return {@code {minX, minY, maxX, maxY}}（闭区间，坐标系是 3×3 的格坐标）
     */
    public int[] trimmedBounds() {
        if (mode != Mode.GRID) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int i = 0; i < GRID_CELLS; i++) {
            if (grid[i].shown.isEmpty()) {
                continue;
            }
            final int x = i % MAX_SIDE;
            final int y = i / MAX_SIDE;
            if (x < minX) {
                minX = x;
            }
            if (y < minY) {
                minY = y;
            }
            if (x > maxX) {
                maxX = x;
            }
            if (y > maxY) {
                maxY = y;
            }
        }
        return maxX < 0 ? null : new int[]{minX, minY, maxX, maxY};
    }

    /**
     * 无序列表 / 单格：最终的 {@code Ingredient} 序列（顺序 = 界面顺序）。
     *
     * <p>🆕 第 12 刀：<b>跳过空槽</b>。无序那一屏现在也画 3×3，"空位"是给玩家补材料的
     * （见 {@link #setAt}），它们<b>不是</b>配料 —— 写进 {@code ingredients} 会造出一条
     * 原版读不出来的配方。⚠️ 这不是"裁剪形状"（无序合成没有形状这个概念）：
     * 有序那一屏的裁剪口径一个字没动（见 {@link #trimmedBounds}）。
     */
    public List<Ingredient> ingredientList() {
        if (mode != Mode.LIST && mode != Mode.SINGLE) {
            return List.of();
        }
        final List<Ingredient> out = new ArrayList<>(list.size());
        for (Slot s : list) {
            if (s == null || s.shown.isEmpty()) {
                continue;
            }
            out.add(ingredientOf(s));
        }
        return out;
    }

    /** 一个槽最终该用哪个 {@link Ingredient}（{@code dirty == false} ⇒ 原样返回底本那个对象）。 */
    private static Ingredient ingredientOf(Slot s) {
        if (s == null) {
            return Ingredient.EMPTY;
        }
        if (!s.dirty && s.base != null) {
            return s.base;
        }
        if (s.shown.isEmpty()) {
            return Ingredient.EMPTY;
        }
        return Ingredient.of(s.shown);
    }

    // ---------------------------------------------------------------- 覆盖层要写的字段

    /**
     * 落盘用的 {@code fields}（<b>只带这次真的要写的键</b>）。
     *
     * <pre>
     *   GRID   : {"pattern":[…],"key":{…}}
     *   LIST   : {"ingredients":[…]}
     *   SINGLE : {"ingredient":{…}}
     *   NONE   : {}
     * </pre>
     * ⚠️ {@code width/height} <b>不写</b>：原版 {@code ShapedRecipe.Serializer} 是从
     * {@code pattern} 自己算宽高的，多写一个 {@code width} 键反而会让别的读取方困惑。
     * 它们只作为诊断读数（{@link #statsLine}）。
     */
    public JsonObject fieldsJson() {
        final JsonObject out = new JsonObject();
        if (!isDirty()) {
            return out;
        }
        switch (mode) {
            case GRID -> {
                final JsonObject f = shapedFields();
                if (f == null) {
                    return out;
                }
                out.add("pattern", f.get("pattern"));
                out.add("key", f.get("key"));
            }
            case LIST -> {
                final JsonArray arr = new JsonArray();
                for (Ingredient in : ingredientList()) {
                    arr.add(ingredientJson(in));
                }
                out.add("ingredients", arr);
            }
            case SINGLE -> {
                final List<Ingredient> l = ingredientList();
                if (!l.isEmpty()) {
                    out.add("ingredient", ingredientJson(l.get(0)));
                }
            }
            default -> {
                // 什么都不写
            }
        }
        return out;
    }

    /** 覆盖层可能会看到的键名（自检／{@code fieldsAppliedTo} 共用一份，避免两处口径不一致）。 */
    public static boolean isShapeKey(String k) {
        return "pattern".equals(k) || "key".equals(k)
                || "ingredients".equals(k) || "ingredient".equals(k);
    }

    /**
     * <b>覆盖文件里那一段输入字段，描述的是不是"底本此刻的输入"</b>
     * —— 即覆盖层这一局<b>确实套上了</b>。
     *
     * <h4>🔴 为什么必须单独立这一条判据</h4>
     * {@code ShanhaiVanillaRecipeOps.resolveBaseFp} 用它决定"沿用文件里那份 base_fp 还是用缓存那份"：
     * <pre>
     *   套上了   ⇒ 文件里那份才是【源声明长什么样】，必须沿用；
     *   没套上   ⇒ 缓存那份才是。
     * </pre>
     * 这里判错的后果不是"少写一个字段"，而是<b>写下一个永远对不上的指纹</b>
     * ⇒ 下一局覆盖层判 STALE、不套用 ⇒ 用户看到的正是「重启之后我的编辑没了」。
     *
     * @param fields 覆盖文件那一条的 {@code fields}
     * @param base   底本（第一次见到的那个原对象）
     * @return true = 这一段输入字段与底本一致（没有输入字段时也算一致 —— 它不参与判定）
     */
    public static boolean shapeFieldsMatchBase(JsonObject fields, Recipe<?> base) {
        if (fields == null || base == null) {
            return false;
        }
        final boolean hasGrid = fields.has("pattern") || fields.has("key");
        final boolean hasList = fields.has("ingredients");
        final boolean hasSingle = fields.has("ingredient");
        if (!hasGrid && !hasList && !hasSingle) {
            return true;
        }
        final ShanhaiVanillaRecipeShape ref = of(base);
        if (ref == null) {
            return false;
        }
        if (hasGrid) {
            if (ref.mode != Mode.GRID || !fields.has("pattern") || !fields.has("key")) {
                return false;
            }
            final JsonObject want = ref.shapedFields();
            if (want == null) {
                return false;
            }
            return canon(fields.get("pattern")).equals(canon(want.get("pattern")))
                    && canon(fields.get("key")).equals(canon(want.get("key")));
        }
        if (hasList) {
            if (ref.mode != Mode.LIST || !fields.get("ingredients").isJsonArray()) {
                return false;
            }
            final JsonArray a = fields.getAsJsonArray("ingredients");
            final List<Ingredient> l = ref.ingredientList();
            if (a.size() != l.size()) {
                return false;
            }
            for (int i = 0; i < l.size(); i++) {
                if (!canon(a.get(i)).equals(canon(ingredientJson(l.get(i))))) {
                    return false;
                }
            }
            return true;
        }
        if (ref.mode != Mode.SINGLE) {
            return false;
        }
        final List<Ingredient> l = ref.ingredientList();
        if (l.isEmpty()) {
            return false;
        }
        return canon(fields.get("ingredient")).equals(canon(ingredientJson(l.get(0))));
    }

    /** 规范化之后序列化成串（键序无关；两侧都走同一份 Java 代码 ⇒ 构造上可比）。 */
    private static String canon(JsonElement el) {
        if (el == null) {
            return "(null)";
        }
        try {
            final JsonElement c = ShanhaiRecipeFingerprint.canonicalize(el);
            return c == null ? "(null)" : c.toString();
        } catch (Throwable t) {
            return el.toString();
        }
    }

    // ---------------------------------------------------------------- 面板文字

    /** 编辑屏"格子区"标题下方那一行读数。 */
    public String titleText() {
        return switch (mode) {
            case GRID -> {
                final int[] b = trimmedBounds();
                final String shape = b == null ? "§c(空)"
                        : "§f" + (b[2] - b[0] + 1) + "×" + (b[3] - b[1] + 1);
                yield "§7形状 " + shape + " §8（网格 3×3 · 位置有意义）";
            }
            case LIST -> "§7材料 §f" + list.size() + " §7样 §8（网格 3×3 · 位置无所谓）";
            case SINGLE -> "§7单格输入";
            default -> "§c本类型不支持编辑输入";
        };
    }

    /**
     * 面板上那一行操作说明（<b>必须短</b>：它画在 {@code x=90} 起、右边只剩约 250px
     * ⇒ 可见字符不超过 ~40 个；长解释留在类注释与「指到哪一格」那一行）。
     *
     * <p>🆕 第 12 刀：有序与无序<b>手势逐字相同</b>（用户的判据是"玩家好操作、不用学两套"）
     * —— 两行的差别只有"位置有没有意义"这件事本身。
     */
    public String noteText() {
        return switch (mode) {
            case GRID -> "§7拖入＝改/增 · 右键＝清空那格";
            case LIST -> "§7拖入＝再加一样 · 右键＝删掉那一样";
            case SINGLE -> "§7单格输入：拖入＝换材料";
            default -> "§e本类型不支持编辑输入";
        };
    }

    /** 面板上第二行说明（"这个形态怎么增删 / 保存时会做什么"）。 */
    public String hintText() {
        return switch (mode) {
            case GRID -> "§8保存时自动去掉外圈空行空列";
            case LIST -> "§8位置无所谓 · 最多 9 样材料";
            case SINGLE -> "§8没有「增 / 删」这两个动作";
            default -> "§8列表里看得见 · 第三屏只读";
        };
    }

    /** 一行诊断读数（日志/自检共用）。 */
    public String statsLine() {
        final StringBuilder sb = new StringBuilder();
        sb.append("id=").append(id).append(" type=").append(typeId).append(" mode=").append(mode);
        if (mode == Mode.GRID) {
            final int[] b = trimmedBounds();
            sb.append(" grid=").append(w).append("x").append(h)
                    .append(" active=").append(GRID_CELLS)
                    .append(" 占=").append(slotCount())
                    .append(" trimmed=").append(b == null ? "(empty)"
                            : (b[2] - b[0] + 1) + "x" + (b[3] - b[1] + 1));
        } else if (mode == Mode.LIST || mode == Mode.SINGLE) {
            sb.append(" slots=").append(list.size()).append('/').append(layoutCount());
        }
        sb.append(" dirty=").append(isDirty()).append(" valid=").append(valid());
        return sb.toString();
    }

    /** 内容摘要（"哪些格有东西"），用于日志一眼看出形状。 */
    public String describe() {
        if (mode == Mode.GRID) {
            final StringBuilder sb = new StringBuilder("[");
            for (int y = 0; y < MAX_SIDE; y++) {
                if (y > 0) {
                    sb.append('/');
                }
                for (int x = 0; x < MAX_SIDE; x++) {
                    final Slot s = grid[y * MAX_SIDE + x];
                    sb.append(s.shown.isEmpty() ? '.' : name(s.shown));
                }
            }
            return sb.append(']').toString();
        }
        final StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(list.get(i).shown.isEmpty() ? "(empty)" : name(list.get(i).shown));
        }
        return sb.append(']').toString();
    }

    private static String name(ItemStack s) {
        final ResourceLocation k = BuiltInRegistries.ITEM.getKey(s.getItem());
        return (k == null ? "?" : k.getPath()) + (s.getCount() > 1 ? "x" + s.getCount() : "");
    }

    static boolean sameStack(ItemStack a, ItemStack b) {
        final ItemStack x = a == null ? ItemStack.EMPTY : a;
        final ItemStack y = b == null ? ItemStack.EMPTY : b;
        if (x.isEmpty() || y.isEmpty()) {
            return x.isEmpty() && y.isEmpty();
        }
        return ItemStack.isSameItemSameTags(x, y) && x.getCount() == y.getCount();
    }
}
