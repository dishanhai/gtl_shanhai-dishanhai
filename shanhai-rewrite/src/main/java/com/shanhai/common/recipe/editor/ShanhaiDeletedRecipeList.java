package com.shanhai.common.recipe.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 山海重构 · <b>「已删除配方」列表的纯逻辑核</b> —— <b>零 MC / Forge 依赖</b>
 * （只 import Gson 与 {@code java.*}）。
 *
 * <h2>1. 它为什么必须单独一个类</h2>
 * 用户点单（逐字）：
 * <blockquote>「我现在没法在配方编辑器恢复删除掉的配方……点击之后如图2中红框这样的列表显示
 * <b>该配方的主产物以及配方id</b>，点击该配方可以直接进入编辑页面」</blockquote>
 * 这条功能里有四件事是<b>可以离线判</b>的，而它们恰好是最容易写错的地方：
 * <ol>
 *   <li><b>id → 主产物</b>的映射（主产物拿不到时必须降级、且不许崩）；</li>
 *   <li><b>分页边界</b>（12 行一页，页号夹回合法区间，越界取行返回 null 而不是抛异常）；</li>
 *   <li><b>详情页底部按钮集合</b>必须<b>恰好</b>是 {恢复这条配方, 返回}；</li>
 *   <li><b>恢复之后那条从列表消失</b>（{@link #without(String)}）。</li>
 * </ol>
 * ⇒ 把四件事收进本类（纯函数），{@code temp\editor-restore\selfcheck\DeletedListSelfcheck.java}
 * 就能<b>不开游戏</b>逐条断言；生产路径（{@link ShanhaiDeletedRecipeIndex} / 工作区）调的
 * <b>就是这里同一段代码</b>，不是它的复制品 —— 所以"自检绿"与"游戏里那一屏"是同一件事。
 *
 * <h2>2. 🔴 主产物从哪抠（形状是<b>实测</b>的，不是猜的）</h2>
 * 快照/指纹里那份 JSON 是 KubeJS {@code RecipeJS.json}（= GT 配方序列化形状）。
 * 本机真实样本（{@code temp\cond-land\baseline-run1.log:1507}，逐字摘一段）：
 * <pre>
 * "outputs":{"item":[{"content":{"type":"gtceu:sized","count":1,
 *                               "ingredient":{"item":"gtceu:zpm_256a_laser_source_hatch"}},
 *                     "chance":10000,"maxChance":10000,"tierChanceBoost":0}]}
 * </pre>
 * ⇒ 物品产出的形状是 {@code content{type,count,ingredient{item|tag}}}；
 * 另外三种在别处也出现过（故一并认，且都只是"多认一种形态"，不改上面这条主形状）：
 * <ul>
 *   <li>{@code content} 是<b>数组</b>（一个产物有多种可接受形态）⇒ 取第一个认得出 item 的；</li>
 *   <li>{@code content} 直接就是 {@code {"item":…}}（没有 sized 包装）⇒ 认；</li>
 *   <li>{@code content={"type":"gtceu:circuit","configuration":N}}（编程电路）⇒ 认成
 *       "编程电路 #N"，<b>没有物品 id</b>（这不是降级，那就是它的真值）。</li>
 * </ul>
 * 认不出物品 id（只有 tag / 形状陌生）⇒ 返回 {@code null}，
 * 由 {@link #rowLabel} 显示 {@code §8(主产物未知)} —— <b>绝不编一个物品出来</b>
 * （本工程红线：宁可缺，不可假）。
 *
 * <h2>3. 🆕 纯流体产出（用户原话：「<b>流体产出没有标注流体的名字</b>」）</h2>
 * 流体那一支<b>不再</b>写成死板一句 {@code "流体产出"}：本类只负责把 {@code outputs.fluid[0]}
 * 里的<b>流体 id</b> 抠出来（{@link #fluidIdOf}，形状是实测的，见那里的注释），
 * 落到 {@link Main#fluidId()}；<b>显示名</b>由 MC 那一侧的
 * {@code ShanhaiDeletedRecipeIndex#mainNameOf} 用 {@code FluidStack#getDisplayName()} 换
 * （客户端 zh_cn ⇒ 中文名）。阶梯 <b>显示名 → 流体 id → "流体产出"</b>：
 * 拿不到名字就显示 id，连 id 都认不出才是原来那句 —— <b>绝不编一个名字</b>。
 */
public final class ShanhaiDeletedRecipeList {

    /** 一页几行（= 第一屏那个列表的控件行数，见 {@code ShanhaiRecipeEditorWorkspace#ROWS}）。 */
    public static final int ROWS_PER_PAGE = 12;

    /** 详情页底部那颗「恢复」（文案与面板上的一模一样）。 */
    public static final String BTN_RESTORE = "恢复这条配方";

    /** 详情页底部那颗「返回」。 */
    public static final String BTN_BACK = "返回";

    /**
     * 🔴 <b>已删除配方详情页的按钮集合 —— 恰好这两个，多一个少一个都算错。</b>
     *
     * <p>用户原话：「但是下面的按钮只剩下<b>恢复这条配方+返回</b>按钮」。
     * 面板<b>就是拿这一份</b>去建按钮的（{@code ShanhaiRecipeEditorPanel#buildDeletedPreviewStage}），
     * ⇒ 自检断言的数组与界面上真正画出来的按钮是同一处真值。
     */
    public static final String[] DETAIL_BUTTONS = {BTN_RESTORE, BTN_BACK};

    /** 详情页按钮集合（只读视图；顺序即界面上的从左到右）。 */
    public static List<String> detailButtons() {
        return List.of(DETAIL_BUTTONS);
    }

    // ================================================================== 主产物

    /**
     * 一条主产物。
     *
     * @param itemId  物品 id（拿不到就是 {@code null}；tag / 流体那一支都落在这里）
     * @param tag     {@code itemId == null} 时能写出来的一句人话（例如 {@code "tag:forge:rubber"}、
     *                {@code "编程电路 #1"}、{@code "流体 gtceu:sulfuric_acid"}）；再不行就 {@code null}
     * @param count   数量（≥1；认不出来按 1。流体那一支是<b>毫桶（mB）</b>）
     * @param fluidId 🆕 <b>主产物是流体时的流体 id</b>（{@code gtceu:sulfuric_acid}）；
     *                物品 / tag / 编程电路一律 {@code null}。
     *
     *                <p>🔴 为什么要单独一个字段而不是从 {@code tag} 里抠字符串：
     *                显示名只有 {@code FluidStack} 认得（服务端/单机客户端那一侧），
     *                而本类是零 MC 依赖的纯核 ⇒ 只能把"是哪个流体"这件事<b>结构化</b>地带出去，
     *                由 {@code ShanhaiDeletedRecipeIndex#mainNameOf} 换成显示名。
     *                从 {@code "流体 xxx"} 里反解析是自找麻烦（流体 id 里带空格/中文时立刻错）。
     */
    public record Main(String itemId, String tag, int count, String fluidId) {

        /** 老调用点（物品 / tag / 编程电路）：没有流体 id。 */
        public Main(String itemId, String tag, int count) {
            this(itemId, tag, count, null);
        }

        public boolean hasItem() {
            return itemId != null && !itemId.isEmpty();
        }

        /** 这条主产物是<b>流体</b>（有 id 才算；只有 tag 的流体走 tag 那一支）。 */
        public boolean hasFluid() {
            return fluidId != null && !fluidId.isEmpty();
        }

        /** 给人看的那半句（不含配方 id）。 */
        public String text() {
            if (hasItem()) {
                return itemId + (count > 1 ? " x" + count : "");
            }
            return tag == null ? "" : tag;
        }
    }

    /**
     * <b>从一份配方 JSON 里抠出主产物</b>（纯函数，永不抛）。
     *
     * <p>口径 = {@code outputs.item} 的<b>第一条</b>（GT/JEI 里"主产物"就是它）。
     * 拿不到 ⇒ {@code null}（调用方据此走"缺快照 / 主产物未知"那两条降级路，不许崩）。
     *
     * <p>🆕 2026-11-07：多认一条<b>原版</b>形状 {@code {"result": {...}}}（工作台 / 熔炉 / 切石机）。
     * 这一支不是降级 —— 结果就写在那份 JSON 里，读出来的就是真值。GT 那一支仍然是第一优先
     * （{@code outputs} 存在时永远先看它，绝不因为原版那一支而改变 GT 的判定）。
     */
    public static Main mainProductOf(JsonObject recipeJson) {
        if (recipeJson == null) {
            return null;
        }
        try {
            final JsonObject outputs = obj(recipeJson.get("outputs"));
            if (outputs != null) {
                final JsonArray items = arr(outputs.get("item"));
                if (items != null && !items.isEmpty()) {
                    for (JsonElement e : items) {
                        final Main m = fromItemEntry(e, 0);
                        if (m != null) {
                            return m;
                        }
                    }
                }
                // 没有物品产出：主产物就是流体 —— 🔴 **必须把是哪个流体写出来**。
                //    用户原话：「**流体产出没有标注流体的名字**」。
                //    老代码在这里只写死一句 "流体产出"（本轮之前 :129），那一行标签等于什么都没说。
                //    阶梯（照任务书）：FluidStack 显示名（由 ShanhaiDeletedRecipeIndex 换）
                //    → 拿不到名字就用流体 id → 连 id 都认不出才是 "流体产出"。
                //    ⚠️ 本类不碰 MC，所以这里只到 id；显示名由 MC 那一侧补，不许在这里编一个名字。
                final JsonArray fluids = arr(outputs.get("fluid"));
                if (fluids != null && !fluids.isEmpty()) {
                    final JsonElement first = fluids.get(0);
                    final String id = fluidIdOf(first);
                    final String tagId = id == null || id.isEmpty() ? fluidTagOf(first) : null;
                    final String label;
                    if (id != null && !id.isEmpty()) {
                        label = "流体 " + id;
                    } else if (tagId != null && !tagId.isEmpty()) {
                        label = "流体标签 " + tagId;      // 流体标签：没有唯一名字可显示，如实写标签
                    } else {
                        label = "流体产出";                // 连 id/标签都认不出 —— 这就是原来那句
                    }
                    return new Main(null, label, countOf(first, 1), id);
                }
            }
        } catch (Throwable ignored) {
            // 掉到下面那条"原版 result"再试一次（形状坏掉时也不许抛）
        }
        try {
            // 非 GT（工作台 / 熔炉 / 切石机…）：KubeJS 与数据包都用 {"result": {...}} 或 {"result": "..."}
            final JsonElement result = recipeJson.get("result");
            if (result != null && !result.isJsonNull()) {
                return fromContent(result, 1);
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** {@code outputs.item} 里的一条 ⇒ 主产物（认不出返回 null）。 */
    private static Main fromItemEntry(JsonElement entry, int depth) {
        if (entry == null || depth > 4) {
            return null;
        }
        if (entry.isJsonPrimitive()) {
            // 极端形态：直接写了个物品 id 字符串
            final String s = entry.getAsString();
            return s == null || s.isEmpty() ? null : new Main(s, null, 1);
        }
        if (entry.isJsonArray()) {
            for (JsonElement e : entry.getAsJsonArray()) {
                final Main m = fromItemEntry(e, depth + 1);
                if (m != null) {
                    return m;
                }
            }
            return null;
        }
        final JsonObject o = obj(entry);
        if (o == null) {
            return null;
        }
        final int outerCount = number(o, "count", 1);
        // ① 标准形状：{"content": {...}, "chance": ...}
        if (o.has("content")) {
            final JsonElement c = o.get("content");
            final Main m = fromContent(c, depth + 1);
            return m == null ? null : new Main(m.itemId(), m.tag(),
                    m.count() > 1 ? m.count() : Math.max(1, outerCount));
        }
        // ② 没有 content 包装（老数据/手写数据）：本身就是 ingredient 或 sized
        final Main m = fromContent(o, depth + 1);
        return m == null ? null : new Main(m.itemId(), m.tag(), Math.max(1, outerCount));
    }

    /** ingredient / sized / circuit / 数组那一层。 */
    private static Main fromContent(JsonElement content, int depth) {
        if (content == null || depth > 4) {
            return null;
        }
        if (content.isJsonPrimitive()) {
            final String s = content.getAsString();
            return s == null || s.isEmpty() ? null : new Main(s, null, 1);
        }
        if (content.isJsonArray()) {
            for (JsonElement e : content.getAsJsonArray()) {
                final Main m = fromContent(e, depth + 1);
                if (m != null) {
                    return m;
                }
            }
            return null;
        }
        final JsonObject o = obj(content);
        if (o == null) {
            return null;
        }
        // 编程电路：没有物品 id，但那 <b>就是</b>它的真值
        final String type = str(o, "type");
        if (type != null && type.endsWith(":circuit")) {
            final int conf = number(o, "configuration", 0);
            return new Main(null, "编程电路 #" + conf, 1);
        }
        final int count = Math.max(1, number(o, "count", 1));
        // sized / long 之类：真正的配料在 ingredient 里（本机实测的常规形态）
        if (o.has("ingredient")) {
            final Main inner = fromContent(o.get("ingredient"), depth + 1);
            if (inner == null) {
                return null;
            }
            return new Main(inner.itemId(), inner.tag(), Math.max(count, inner.count()));
        }
        final String item = str(o, "item");
        if (item != null && !item.isEmpty()) {
            return new Main(item, null, count);
        }
        final String tag = str(o, "tag");
        if (tag != null && !tag.isEmpty()) {
            return new Main(null, "tag:" + tag, count);
        }
        final String fluid = str(o, "fluid");
        if (fluid != null && !fluid.isEmpty()) {
            return new Main(null, "流体 " + fluid, count, fluid);
        }
        return null;
    }

    // ================================================================== 流体（🆕 名字）

    /**
     * <b>一条流体产物里是哪个流体</b>（认不出返回 {@code null}；永不抛）。
     *
     * <h4>形状是实测的，不是猜的</h4>
     * 本机真实快照（用户实例 {@code config\shanhai\removed_recipe_snapshots.json}，逐字）：
     * <pre>
     * "fluid":[{"content":{"amount":48000,"value":[{"fluid":"gtceu:nitrogen_dioxide"}]},
     *           "chance":10000,"maxChance":10000,"tierChanceBoost":0}]
     * </pre>
     * ⇒ GT 1.4.4 把流体包在 {@code content.value[]} 里（<b>不是</b> {@code content.fluid}）。
     * 其它几种在别处也出现过，一并认（都只是"多认一种形态"，不改上面这条主形状）：
     * <ul>
     *   <li>{@code content} 直接是 {@code {"fluid":"…"}}（老数据/手写数据）；</li>
     *   <li>{@code content} 是数组 ⇒ 取第一个认得出的；</li>
     *   <li>整个条目就是 {@code {"fluid":"…"}}（没有 content 包装）；</li>
     *   <li>外面还有一层 {@code ingredient}（sized 形式）。</li>
     * </ul>
     *
     * @param entry {@code outputs.fluid} 里的一个元素
     */
    public static String fluidIdOf(JsonElement entry) {
        return fluidKeyOf(entry, "fluid", 0);
    }

    /** 同上，但认的是<b>流体标签</b>（{@code {"tag":"forge:sulfuric_acid"}}）；没有返回 {@code null}。 */
    public static String fluidTagOf(JsonElement entry) {
        return fluidKeyOf(entry, "tag", 0);
    }

    private static String fluidKeyOf(JsonElement entry, String key, int depth) {
        if (entry == null || depth > 6) {
            return null;
        }
        try {
            if (entry.isJsonPrimitive()) {
                // 极端形态：直接写了个 "gtceu:sulfuric_acid"
                final String s = entry.getAsString();
                return "fluid".equals(key) ? s : null;
            }
            if (entry.isJsonArray()) {
                for (JsonElement e : entry.getAsJsonArray()) {
                    final String s = fluidKeyOf(e, key, depth + 1);
                    if (s != null && !s.isEmpty()) {
                        return s;
                    }
                }
                return null;
            }
            final JsonObject o = obj(entry);
            if (o == null) {
                return null;
            }
            final String direct = str(o, key);
            if (direct != null && !direct.isEmpty()) {
                return direct;
            }
            // content{…} / value[] / ingredient{…} 三种包装各下一层
            for (String wrap : new String[]{"content", "value", "ingredient"}) {
                if (o.has(wrap)) {
                    final String s = fluidKeyOf(o.get(wrap), key, depth + 1);
                    if (s != null && !s.isEmpty()) {
                        return s;
                    }
                }
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * <b>产物名字清单</b>（物品名 ＋ 流体名；纯函数，两个 name 解析器可为 {@code null}）。
     *
     * <h4>它为什么必须存在</h4>
     * 详情页那行读数原来只有数量（{@code ioOf}: {@code 物品 3 入 / 1 出 · 流体 0 入 / 0 出}）——
     * 用户看的就是那里，而"流体 0 入 / 1 出"<b>一个字都没说产出的是什么</b>。
     * ⇒ 这里按顺序把产出物列出来（物品/流体各用调用方给的解析器换名字，拿不到名字就用 id），
     * 超过 {@code max} 条就写 {@code …等 N 项}。
     *
     * @param json      一份配方 JSON（{@code null} ⇒ 返回 {@code ""}）
     * @param itemName  物品 id ⇒ 显示名（{@code null} / 返回空 ⇒ 用 id）
     * @param fluidName 流体 id ⇒ 显示名（{@code null} / 返回空 ⇒ 用 id）
     * @param max       最多列几条（{@code <=0} ⇒ 用 6）
     * @return 例如 {@code "大型组装机外壳、硫酸"} / {@code "编程电路 #1"} / {@code ""}（认不出任何产物）
     */
    public static String productsLine(JsonObject json, java.util.function.Function<String, String> itemName,
                                      java.util.function.Function<String, String> fluidName, int max) {
        if (json == null) {
            return "";
        }
        final int cap = max <= 0 ? 6 : max;
        // 先收全量（去重、不截断），最后再按 cap 截 —— 这样 "…等 N 项" 里的 N 是**真的产物种数**，
        // 不是"条目数"（同一种产物出现两次时条目数会骗人）。
        final List<String> all = new ArrayList<>();
        try {
            final JsonObject outputs = obj(json.get("outputs"));
            final JsonArray items = outputs == null ? null : arr(outputs.get("item"));
            if (items != null) {
                for (JsonElement e : items) {
                    final Main m = fromItemEntry(e, 0);
                    if (m != null) {
                        addName(all, displayOf(m, itemName, fluidName));
                    }
                }
            }
            final JsonArray fluids = outputs == null ? null : arr(outputs.get("fluid"));
            if (fluids != null) {
                for (JsonElement e : fluids) {
                    final String fid = fluidIdOf(e);
                    final String ftag = fid == null || fid.isEmpty() ? fluidTagOf(e) : null;
                    if (fid == null && ftag == null) {
                        continue;
                    }
                    final String shown = fid != null ? apply(fluidName, fid) : null;
                    addName(all, shown == null || shown.isEmpty() ? (fid != null ? fid : ftag) : shown);
                }
            }
            if (all.isEmpty()) {
                // 非 GT（工作台 / 熔炉…）：result 那一支
                final JsonElement result = json.get("result");
                if (result != null && !result.isJsonNull()) {
                    final Main m = fromContent(result, 1);
                    if (m != null) {
                        addName(all, displayOf(m, itemName, fluidName));
                    }
                }
            }
            if (all.isEmpty()) {
                return "";
            }
            final StringBuilder sb = new StringBuilder();
            final int shown = Math.min(cap, all.size());
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    sb.append("、");
                }
                sb.append(all.get(i));
            }
            if (all.size() > shown) {
                sb.append(" …等 ").append(all.size()).append(" 项");
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 一条产物的人话名（物品名 → 编程电路/tag 那一句 → id）。 */
    private static String displayOf(Main m, java.util.function.Function<String, String> itemName,
                                    java.util.function.Function<String, String> fluidName) {
        if (m == null) {
            return "";
        }
        if (m.hasFluid()) {
            final String shown = apply(fluidName, m.fluidId() == null ? "" : m.fluidId());
            return shown == null || shown.isEmpty() ? m.fluidId() : shown;
        }
        if (m.hasItem()) {
            final String shown = apply(itemName, m.itemId() == null ? "" : m.itemId());
            return shown == null || shown.isEmpty() ? m.itemId() : shown;
        }
        return m.tag() == null ? "" : m.tag();
    }

    private static String apply(java.util.function.Function<String, String> fn, String id) {
        if (fn == null || id == null || id.isEmpty()) {
            return null;
        }
        try {
            final String s = fn.apply(id);
            return s == null || s.isEmpty() ? null : s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void addName(List<String> names, String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        if (names.contains(name)) {
            return;                                  // 同一产物列两遍没有信息量
        }
        names.add(name);
    }

    // ================================================================== 快照可用性（2026-11-07 那一刀）

    /**
     * 一份 JSON 里"说明它真的带配方内容"的那些键（GT 的 + 原版的）。
     *
     * <p>为什么需要这道闸：KubeJS 的 {@code RecipeJS.json} 在配方事件窗口里对
     * <b>脚本新增/被删</b>的配方只有一个 18 字符的空壳 {@code {"type":"unknown"}}
     * （实测出处见 {@code temp\editor-restore\修复报告.md}）。把空壳当快照写进去
     * = 把"缺快照"伪装成"有快照但认不出产物"，而且 {@code putIfAbsent} 之后永远
     * 不会再来修它 ⇒ 必须在这里拒收。
     */
    public static final String[] CONTENT_KEYS = {
            "outputs", "inputs", "tickInputs", "tickOutputs",
            "result", "ingredients", "ingredient", "key", "pattern"};

    /** json 里那个 {@code type} 是不是一个<b>真</b>类型（不是 KubeJS 的空壳 {@code unknown}）。 */
    public static boolean hasKnownType(JsonObject json) {
        if (json == null) {
            return false;
        }
        try {
            final String type = str(json, "type");
            if (type == null || type.isEmpty()) {
                return false;
            }
            if ("unknown".equals(type) || type.endsWith(":unknown")) {
                return false;
            }
            return type.indexOf(':') > 0 && type.indexOf(':') < type.length() - 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** json 里到底有没有配方内容（空壳 {@code {"type":"unknown"}} / 只有 type 的都<b>不算</b>）。 */
    public static boolean hasRecipeContent(JsonObject json) {
        if (json == null) {
            return false;
        }
        try {
            for (String k : CONTENT_KEYS) {
                if (json.has(k) && !json.get(k).isJsonNull()) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 这一份 JSON 能不能当"配方快照"用：<b>认得出类型</b> 且 <b>真的有内容</b>。
     *
     * <p>判据是"宁可缺，不可假"：空壳、只有 type、type=unknown、缺字段的一律 {@code false}
     * ⇒ 调用方走"缺快照"那条降级，而不是把它当"有快照但产物未知"。
     *
     * <p>⚠️ 这是<b>宽</b>判据（type ＋ 任意内容键）。补份那条路要用<b>严</b>判据
     * {@link #isComplete(JsonObject)}（type ＋ <b>产出键</b>），因为
     * {@code RecipeJS.serialize()} 只写 {@code shouldWrite()} 为真的组件
     * （字节码：{@code RecipeComponentValue.shouldWrite()} 就是 {@code return this.write;}）
     * ⇒ 它写出来的 json <b>可能是部分组件</b>。两者都保留：
     * 宽判据管"盘上这份能不能显示"，严判据管"补份这份敢不敢落盘"。
     */
    public static boolean isUsableRecipeJson(JsonObject json) {
        return hasKnownType(json) && hasRecipeContent(json);
    }

    // ================================================================== 产出键 / 完整性（严判据）

    /**
     * 原版（非 GT）配方的<b>产出键</b>。
     *
     * <p>GT 的产出键是 {@code outputs} —— 出处不是猜的：
     * <ul>
     *   <li>{@code GTRecipeSchema} 的字符串常量全集 = {@code inputs / outputs / tickInputs / tickOutputs /
     *       duration / data / isFuel / recipeConditions / +4 个 ChanceLogics}
     *       （{@code temp\editor-restore\ev\GTRecipeSchema.javap.txt}）；</li>
     *   <li>实例里唯一那条真快照就是范本：{@code "outputs":{"item":[{"content":{"type":"gtceu:sized",
     *       "count":2,"ingredient":{"item":"gtceu:large_scale_assembler_casing"}}…}]}}。</li>
     * </ul>
     */
    public static final String[] OUTPUT_KEYS_VANILLA = {"result", "ingredients", "key", "pattern"};

    /** 这份 json 里到底有没有"产出键"（GT 看 {@code outputs}／{@code tickOutputs}，原版看 result 那一套）。 */
    public static boolean hasOutputKey(JsonObject json) {
        if (json == null) {
            return false;
        }
        try {
            // GT：主产出通道。存在即认（空 {} 也认 —— 那表示"这条真的没有产出"，是真值）
            if (obj(json.get("outputs")) != null) {
                return true;
            }
            // GT：发电类配方的产出在 tickOutputs（空的 {} 不算 —— 真快照里那个 tickOutputs 就是空对象）
            final JsonObject tickOut = obj(json.get("tickOutputs"));
            if (tickOut != null && tickOut.size() > 0) {
                return true;
            }
            for (String k : OUTPUT_KEYS_VANILLA) {
                if (json.has(k) && !json.get(k).isJsonNull()) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 🔴 <b>严判据（补份专用）</b>：{@code type} 认得出 ＋ 有<b>产出键</b>。
     *
     * <p>为什么要"产出键"这一条：{@code serialize()} 只写 {@code shouldWrite()} 为真的组件
     * ⇒ 可能出现"只有 duration/inputs、没有 outputs"的半成品。半成品落盘 =
     * 列表显示"有快照但认不出主产物"，而它其实只是**没写完** ⇒ 误导。
     * 这里一次判死：不完整就 {@code unusable}，<b>不写</b>。
     */
    public static boolean isComplete(JsonObject json) {
        return hasKnownType(json) && hasOutputKey(json);
    }

    /** {@code VirtualMachineError}（含 {@code OutOfMemoryError}）不许被吞：那是"整批别跑下去了"。 */
    public static void rethrowIfFatal(Throwable t) {
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
    }

    // ================================================================== 补份的三段修复链（纯）

    /**
     * 一条待修配方能做的三件事 —— <b>KubeJS 侧实现它</b>，纯核只按顺序问。
     *
     * <p>为什么做成接口：{@code serialize()} / {@code createRecipe()} 都在 KubeJS 的类型上，
     * 离线自检加载不了；把它们包成接口之后，自检可以用<b>假对象</b>驱动
     * {@link #repairRecipeJson} / {@link #flipAndCreate} 这两段<b>生产逻辑本体</b>，
     * 包括"抛异常时 removed 有没有还原"这种只有跑起来才看得出的性质。
     */
    public interface RepairSource {

        /** 现在 {@code RecipeJS.json} 长什么样（空壳也原样返回）。 */
        JsonObject peekJson();

        /** 调一次 {@code RecipeJS.serialize()} 之后重新读 json（可能抛 {@code RecipeExceptionJS}）。 */
        JsonObject serializeJson();

        /** 临时翻 {@code removed=false} → {@code createRecipe()} → 读 json → <b>finally 还原</b>。 */
        JsonObject flipAndCreateJson();
    }

    /**
     * 一次修复的结果。
     *
     * @param json 修好的 json（失败 ⇒ {@code null}）
     * @param via  {@code "raw"} = 现有那份本来就够；{@code "serialize"} = 靠 {@code serialize()}；
     *             {@code "flip-createRecipe"} = 靠翻 {@code removed} 调 {@code createRecipe()}；
     *             {@code "none"} = 全失败
     * @param why  失败/降级的理由（打日志用，一行）
     */
    public record Repair(JsonObject json, String via, String why) {

        public boolean ok() {
            return json != null;
        }
    }

    /**
     * 🔴 <b>临时把 {@code removed} 翻成 false、调一次 {@code createRecipe()}、再在 {@code finally} 里翻回去</b>。
     *
     * <h4>为什么需要这一步（字节码实证）</h4>
     * {@code RecipeJS.createRecipe()} 在 {@code newRecipe} 为真时会把
     * {@code json.addProperty("type", getSerializationTypeFunction().idString)} 写进去
     * （javap 偏移 135-156），而 {@code serialize()} <b>不写 type</b>（GT 的 schema 里压根没有 type 这个 key）。
     * 但 {@code createRecipe()} 第一句就是 {@code if (removed) return null;}（偏移 0-8）
     * ⇒ 已被删除的配方永远走不到那段。临时翻一下标志，就能借它把 {@code type} 写出来。
     *
     * <h4>安全边界（三条硬约束）</h4>
     * <ol>
     *   <li>返回的 {@code Recipe} 对象<b>直接丢弃</b>，不注册、不进任何索引、不进配方表；</li>
     *   <li>{@code newRecipe} <b>一个字节都不动</b>（只有 {@code removed} 被临时翻，且必还原）；</li>
     *   <li>还原在 {@code finally} 里 —— 中途抛异常也必须翻回 {@code originalRemoved}。</li>
     * </ol>
     *
     * <p>字段读写用回调传进来（{@code setRemoved}/{@code peek}/{@code createRecipe}）：
     * 这样"异常时到底还原了没有"能被离线自检直接断言，而不是靠"我觉得 finally 一定跑"。
     *
     * @param originalRemoved 原来的值（还原成它）
     * @param setRemoved      写字段（{@code v -> js.removed = v}）
     * @param createRecipe    调一次（{@code () -> js.createRecipe()}），返回值丢弃
     * @param peek            读 json（{@code () -> js.json}）
     */
    public static JsonObject flipAndCreate(boolean originalRemoved,
                                           java.util.function.Consumer<Boolean> setRemoved,
                                           Runnable createRecipe,
                                           java.util.function.Supplier<JsonObject> peek) {
        JsonObject out = null;
        try {
            if (setRemoved != null) {
                setRemoved.accept(Boolean.FALSE);
            }
            if (createRecipe != null) {
                try {
                    createRecipe.run();          // 返回值（Recipe<?>）直接丢弃，不注册
                } catch (Throwable t) {
                    rethrowIfFatal(t);
                    // createRecipe 抛了也还可能已经写进去一部分 ⇒ 下面照样 peek 一次（判据在后面）
                }
            }
            out = peek == null ? null : peek.get();
        } catch (Throwable t) {
            rethrowIfFatal(t);
            out = null;
        } finally {
            try {
                if (setRemoved != null) {
                    setRemoved.accept(originalRemoved);   // 🔴 必还原
                }
            } catch (Throwable ignored) {
                // 还原都失败就没什么可做的了（字段是 final 类的 public boolean，正常不会到这里）
            }
        }
        return out;
    }

    /**
     * 🔴 <b>补份的三段修复链</b>（生产路径 {@code ShanhaiRecipeFingerprintCapture#backfillRemovedSnapshots}
     * 对每一条 id 调的就是它）：
     * <ol>
     *   <li><b>raw</b>：现有 json（补 type 后）过了 {@link #isComplete} ⇒ 直接用，<b>不动</b> KubeJS 的对象；</li>
     *   <li><b>serialize</b>：{@code serialize()} 再读 —— 抛异常只降级（{@link #rethrowIfFatal} 除外）；</li>
     *   <li><b>flip-createRecipe</b>：翻 {@code removed} 借 {@code createRecipe()} 把 type/组件补齐；</li>
     *   <li>都不过 ⇒ {@code json == null}（调用方记 {@code unusable}，<b>不写半成品/空壳</b>）。</li>
     * </ol>
     *
     * <p>本方法<b>不抛</b>（除 {@code VirtualMachineError}）—— 这是"一条坏配方不能中断整批补份"的机制：
     * 每条 id 都只经过这里，单条失败只会得到一个 {@code json == null}。
     */
    public static Repair repairRecipeJson(JsonObject current, String typeId, RepairSource src) {
        final StringBuilder why = new StringBuilder();
        JsonObject j = withTypeFrom(current, typeId);
        if (isComplete(j)) {
            return new Repair(j, "raw", why.toString());
        }
        if (src == null) {
            return new Repair(null, "none", "current_incomplete; no_source");
        }
        // ① serialize()
        JsonObject s = null;
        try {
            s = src.serializeJson();
        } catch (Throwable t) {
            rethrowIfFatal(t);
            why.append("serialize_threw:").append(t.getClass().getSimpleName()).append("; ");
        }
        j = withTypeFrom(s, typeId);
        if (isComplete(j)) {
            return new Repair(j, "serialize", why.toString());
        }
        why.append("serialize_incomplete; ");
        // ② 翻 removed 借 createRecipe()（把 type/组件补齐）
        JsonObject f = null;
        try {
            f = src.flipAndCreateJson();
        } catch (Throwable t) {
            rethrowIfFatal(t);
            why.append("flip_threw:").append(t.getClass().getSimpleName()).append("; ");
        }
        j = withTypeFrom(f, typeId);
        if (isComplete(j)) {
            return new Repair(j, "flip-createRecipe", why.toString());
        }
        why.append("flip_incomplete");
        return new Repair(null, "none", why.toString());
    }

    /**
     * 把"已知的 type"补进一份 JSON（只在 {@code type} 缺失/为空壳时动手，返回<b>副本</b>）。
     *
     * <p>为什么允许补：{@code type} 这个值的真值来源是 {@code RecipeJS.kjs$getType()}
     * （KubeJS 手里的配方类型 id），不是我们猜的；而 KubeJS 的 {@code json} 因为
     * {@code createRecipe()} 对被删配方直接 {@code return null}（字节码：{@code removed} 就返 null，
     * 走不到 {@code serialize()}）⇒ 那份 json 里 {@code type} 一直停在空壳值。
     * 补它 = 把 KubeJS 自己知道的真值放回去，不是编。
     *
     * @param typeId 已知类型（{@code null}/空 ⇒ 原样返回）
     */
    public static JsonObject withTypeFrom(JsonObject json, String typeId) {
        if (json == null) {
            return null;
        }
        if (typeId == null || typeId.isEmpty() || typeId.indexOf(':') <= 0) {
            return json;
        }
        if (hasKnownType(json)) {
            return json;
        }
        final JsonObject copy = json.deepCopy();
        copy.addProperty("type", typeId);
        return copy;
    }

    /**
     * 老入口（{@code current ＋ 一次 repair}）：保留给既有调用点/自检，<b>内部就是</b>
     * {@link #repairRecipeJson} 的两段版（没有 flip 那一段）。
     *
     * <p>判据与生产一致（{@link #isComplete}）：不完整 ⇒ {@code null}，绝不返回半成品。
     */
    public static JsonObject pickUsable(final JsonObject current,
                                        final java.util.function.Supplier<JsonObject> repair,
                                        String typeId) {
        return repairRecipeJson(current, typeId, new RepairSource() {
            @Override
            public JsonObject peekJson() {
                return current;
            }

            @Override
            public JsonObject serializeJson() {
                return repair == null ? null : repair.get();
            }

            @Override
            public JsonObject flipAndCreateJson() {
                return null;                      // 两段版：没有 flip
            }
        }).json();
    }

    // ================================================================== 输入输出读数（纯）

    /**
     * 一份配方 JSON 的输入输出<b>数量读数</b>（纯，不用 GT 类型）。
     *
     * <p>用途：详情页在"快照解析失败"时必须能说清<b>快照里到底有没有东西</b>
     * （"有 3 入 / 1 出但解析失败" 与 "快照里根本没有输入输出" 是两种完全不同的病）。
     * 它<b>不</b>代替 GT 那套 IO 表格，只是给日志/提示一句可核对的话。
     */
    public record Io(int itemIn, int itemOut, int fluidIn, int fluidOut, long euIn, long euOut) {

        public boolean isEmpty() {
            return itemIn + itemOut + fluidIn + fluidOut == 0 && euIn == 0 && euOut == 0;
        }

        /** 一句人话（中文）：{@code 物品 3 入 / 1 出 · 流体 0 入 / 0 出 · 电 16 EU/t}。 */
        public String text() {
            if (isEmpty()) {
                return "没有输入输出";
            }
            final StringBuilder sb = new StringBuilder();
            sb.append("物品 ").append(itemIn).append(" 入 / ").append(itemOut).append(" 出");
            if (fluidIn + fluidOut > 0) {
                sb.append(" · 流体 ").append(fluidIn).append(" 入 / ").append(fluidOut).append(" 出");
            }
            if (euIn + euOut > 0) {
                sb.append(" · 电 ").append(euIn).append(" 入 / ").append(euOut).append(" 出");
            }
            return sb.toString();
        }
    }

    /** 从一份配方 JSON 数出输入输出（拿不到就是全 0，永不抛）。 */
    public static Io ioOf(JsonObject recipeJson) {
        int ii = 0;
        int io = 0;
        int fi = 0;
        int fo = 0;
        long eu = 0;
        long euOut = 0;
        try {
            final JsonObject in = obj(recipeJson.get("inputs"));
            final JsonObject out = obj(recipeJson.get("outputs"));
            final JsonObject tin = obj(recipeJson.get("tickInputs"));
            final JsonObject tout = obj(recipeJson.get("tickOutputs"));
            ii = sizeOf(in, "item");
            io = sizeOf(out, "item");
            fi = sizeOf(in, "fluid");
            fo = sizeOf(out, "fluid");
            eu = sumContent(tin, "eu");
            euOut = sumContent(tout, "eu");
        } catch (Throwable ignored) {
            // 全 0（调用方会把"全 0"读成"快照里没有输入输出"）
        }
        return new Io(ii, io, fi, fo, eu, euOut);
    }

    private static int sizeOf(JsonObject holder, String key) {
        if (holder == null) {
            return 0;
        }
        final JsonArray a = arr(holder.get(key));
        return a == null ? 0 : a.size();
    }

    /** {@code tickInputs.eu} 那种 {@code [{content:16,...}]} 的 content 求和。 */
    private static long sumContent(JsonObject holder, String key) {
        if (holder == null) {
            return 0;
        }
        final JsonArray a = arr(holder.get(key));
        if (a == null) {
            return 0;
        }
        long sum = 0;
        for (JsonElement e : a) {
            final JsonObject o = obj(e);
            if (o == null) {
                continue;
            }
            if (o.has("content") && o.get("content").isJsonPrimitive()) {
                try {
                    sum += o.get("content").getAsLong();
                } catch (Throwable ignored) {
                    // 这一条读数坏了就当 0
                }
            }
        }
        return sum;
    }

    // ================================================================== 一行

    /**
     * 已删除配方列表里的一行。
     *
     * @param id       配方 id（原样，含命名空间）
     * @param typeId   配方类型 id（拿去重建索引 / 显示都靠它；可能为 {@code null}）
     * @param main     主产物（可能 {@code null} = 拿不到）
     * @param ts       删除时间戳（覆盖文件里的 {@code ts}；读不出来就是 {@code ""}）
     * @param mainName 主产物的<b>显示名</b>（服务端由物品栈取得；客户端那份为空 ——
     *                 它只影响"推下去之后画哪几个字"，而那一行字本来就是服务端推的）
     * @param hasSnapshot 🆕 <b>盘上到底有没有这条的可用快照</b>。
     *                 它把"<b>缺快照</b>"（这条配方的原样从来没留下来）和
     *                 "<b>有快照但认不出主产物</b>"（形状陌生 / 没有物品产出）分成两种显示
     *                 —— 用户看到的必须是这两件事里<b>真的那一件</b>，不许混成一句"未知"。
     */
    public record Row(String id, String typeId, Main main, String ts, String mainName, boolean hasSnapshot) {

        /** 老调用点少写一个字段（显示名 = 用物品 id 顶上）。 */
        public Row(String id, String typeId, Main main, String ts) {
            this(id, typeId, main, ts, null);
        }

        /**
         * 少写 {@code hasSnapshot}：以"主产物算出来了"为准。
         *
         * <p>口径：主产物只能从一份<b>真</b>配方 JSON 里算出来 ⇒ {@code main != null} 就意味着
         * 当时确实有一份可用快照。反过来 {@code main == null} 时<b>不许</b>假设有快照
         * （那正是 2026-11-07 那个"把缺快照说成主产物未知"的病）。
         */
        public Row(String id, String typeId, Main main, String ts, String mainName) {
            this(id, typeId, main, ts, mainName, main != null);
        }

        /** 拿它当"有没有主产物"的判据（面板据此决定要不要画图标）。 */
        public boolean hasMain() {
            return main != null && main.hasItem();
        }

        public String mainItemId() {
            return main == null ? null : main.itemId();
        }

        public int mainCount() {
            return main == null ? 1 : Math.max(1, main.count());
        }

        /** 这一行"主产物"那半句文字（显示名优先，其次是物品 id，再不行是 tag / 未知）。 */
        public String mainText() {
            if (mainName != null && !mainName.isEmpty() && (hasMain() || (main != null && main.tag() != null))) {
                return mainName;
            }
            if (main == null) {
                return "";
            }
            return main.text();
        }
    }

    /**
     * 服务端下发/展示用的那一行文字：<b>主产物 §7(配方id)</b>。
     *
     * <p>口径（用户点单原文）：{@code [主产物图标] 主产物名 §7(配方id)§r} —— 图标由面板另画，
     * 本方法只出文字；主产物拿不到时显示 {@code §8(主产物未知)} <b>而不是留空</b>
     * （留空会让人以为"这条没有产物"，那是另一种假话）。
     */
    public static String rowLabel(Row row) {
        if (row == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        if (row.hasMain()) {
            final String text = row.mainText();
            sb.append("§f").append(text);
            final String tail = " x" + row.mainCount();
            // 显示名自带数量时不再重复写；没有显示名时也只在文字里还没带数量时补一次
            // （2026-11-07：老代码会写出 "gtceu:xxx x2 §7x2" 这种重复数量）
            if (row.mainCount() > 1 && !text.endsWith(tail)
                    && (row.mainName() == null || row.mainName().isEmpty())) {
                sb.append(" §7").append(tail);
            }
        } else if (row.main() != null && row.main().tag() != null && !row.main().tag().isEmpty()) {
            // 流体 / tag / 编程电路那一支：拿得到显示名（流体名由服务端用 FluidStack 换好）
            // 就跟物品名一样用 §f 白字，拿不到才是 §7 灰字（灰的是"只有 id/占位"的那种）
            final boolean named = row.mainName() != null && !row.mainName().isEmpty();
            sb.append(named ? "§f" : "§7").append(row.mainText());
        } else if (!row.hasSnapshot()) {
            // 🔴 2026-11-07：**缺快照** 与 **快照里认不出产物** 是两件事，必须分开写。
            //    前者可以修（下次开机补份 / 删除那一刻开始写快照），后者修不了（快照本来就没写产物）。
            //    混成一句「主产物未知」会让人以为"配方本来就没产物"，那是另一种假话。
            sb.append("§8(缺快照)");
        } else {
            sb.append("§8(主产物未知)");
        }
        sb.append(" §7(").append(row.id()).append(")");
        return sb.toString();
    }

    // ================================================================== 列表本体

    private final List<Row> rows;

    private ShanhaiDeletedRecipeList(List<Row> rows) {
        this.rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public static ShanhaiDeletedRecipeList empty() {
        return new ShanhaiDeletedRecipeList(List.of());
    }

    /**
     * <b>"某条 id 现在能拿到的快照"</b> —— 纯核侧唯一的外部依赖。
     *
     * <p>把它做成接口（而不是直接读文件）有两个理由：
     * <ol>
     *   <li>本类必须保持<b>零 MC / 零 Forge 依赖</b>（自检要能离线 {@code javac + java} 跑）；</li>
     *   <li>自检可以伪造它 ⇒ 正向（有一条快照）与负向（没有快照 / 只有空壳）都能机器断言，
     *       而且断言的<b>就是生产路径同一个 {@link #assemble}</b>，不是复制品。</li>
     * </ol>
     */
    public interface SnapshotSource {

        /** 这条 id 现在<b>能用</b>的那份配方 JSON（没有 / 不可用 ⇒ {@code null}）。 */
        JsonObject jsonOf(String recipeId);

        /**
         * 盘/内存里到底<b>有没有</b>这条的快照 —— <b>哪怕它不可用也算有</b>。
         *
         * <p>两种"没有"必须能分开：{@code exists=false} 是"从来没留过"（可以靠补份修），
         * {@code exists=true && jsonOf()==null} 是"留过但是残的"（修不了，只能重删重留）。
         */
        boolean exists(String recipeId);
    }

    /**
     * 按覆盖文件里的条目 ＋ 快照组装列表（<b>纯函数</b>：不读文件、不碰注册表）。
     *
     * @param entries   条目（顺序 = 显示顺序；调用方负责排序）
     * @param snapshots 快照来源（{@code null} ⇒ 全部按"缺快照"处理，不崩）
     */
    public static ShanhaiDeletedRecipeList assemble(List<Row> entries, SnapshotSource snapshots) {
        final List<Row> out = new ArrayList<>(entries == null ? 0 : entries.size());
        if (entries != null) {
            for (Row r : entries) {
                if (r == null || r.id() == null || r.id().isEmpty()) {
                    continue;                       // 没有 id 的行画不出来，也不许占位
                }
                JsonObject json = null;
                boolean exists = false;
                if (snapshots != null) {
                    try {
                        json = snapshots.jsonOf(r.id());
                    } catch (Throwable ignored) {
                        json = null;
                    }
                    try {
                        exists = snapshots.exists(r.id());
                    } catch (Throwable ignored) {
                        exists = false;
                    }
                }
                if (!isUsableRecipeJson(json)) {
                    json = null;                    // 空壳一律当作"没有"（宁可缺，不可假）
                }
                final Main main = r.main() != null ? r.main() : mainProductOf(json);
                out.add(new Row(r.id(), r.typeId(), main, r.ts(), r.mainName(), exists || json != null));
            }
        }
        return new ShanhaiDeletedRecipeList(out);
    }

    /**
     * 老的入口（{@code id ⇒ json} 的 map 版本）：保留给既有调用点/自检。
     *
     * <p>语义 = {@link #assemble}：map 里有非 null 的 json 就等于"有快照"，没有就等于"缺快照"。
     */
    public static ShanhaiDeletedRecipeList build(List<Row> entries, java.util.Map<String, JsonObject> jsonById) {
        if (jsonById == null) {
            return assemble(entries, null);
        }
        return assemble(entries, new SnapshotSource() {
            @Override
            public JsonObject jsonOf(String recipeId) {
                return jsonById.get(recipeId);
            }

            @Override
            public boolean exists(String recipeId) {
                return jsonById.get(recipeId) != null;
            }
        });
    }

    /** 直接由已经算好的行建表（服务端那份就是这条入口）。 */
    public static ShanhaiDeletedRecipeList of(List<Row> rows) {
        return new ShanhaiDeletedRecipeList(rows);
    }

    /** 全量行（服务端那份是全的；客户端那份不用它）。 */
    public List<Row> rows() {
        return rows;
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public int size() {
        return rows.size();
    }

    /** 一共几页（<b>至少 1</b> —— 空列表也是"第 1/1 页"，与工作区其它列表同口径）。 */
    public int pageCount() {
        return Math.max(1, (rows.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
    }

    /** 页号夹回 {@code [0, pageCount()-1]}（页数变少时安全退到末页）。 */
    public int clampPage(int page) {
        return Math.max(0, Math.min(page, pageCount() - 1));
    }

    /** 第 {@code page} 页的行（页号自动夹回；永远 ≤ {@link #ROWS_PER_PAGE} 行）。 */
    public List<Row> page(int page) {
        if (rows.isEmpty()) {
            return List.of();
        }
        final int p = clampPage(page);
        final int from = p * ROWS_PER_PAGE;
        final int to = Math.min(rows.size(), from + ROWS_PER_PAGE);
        if (from >= to) {
            return List.of();
        }
        return rows.subList(from, to);
    }

    /** 第 {@code page} 页第 {@code row} 行（越界 ⇒ {@code null}，<b>不抛</b>）。 */
    public Row at(int page, int row) {
        if (row < 0 || row >= ROWS_PER_PAGE) {
            return null;
        }
        final List<Row> p = page(page);
        return row < p.size() ? p.get(row) : null;
    }

    /** 第 {@code page} 页第 {@code row} 行的文字（越界 ⇒ {@code ""}）。 */
    public String labelAt(int page, int row) {
        final Row r = at(page, row);
        return r == null ? "" : rowLabel(r);
    }

    /**
     * <b>恢复一条之后</b>：把这条从列表里摘掉的<b>新</b>列表（原对象不动）。
     *
     * <p>用户要求的可见结果：「恢复成功后：该条从列表消失、详情页回到列表」。
     * 做成"返回新对象"而不是"就地删"，是为了让自检能把"之前"和"之后"两份都拿到手里对照
     * （就地删的话，断言只能看结果、没法证明它是从哪一份变来的）。
     */
    public ShanhaiDeletedRecipeList without(String recipeId) {
        if (recipeId == null || rows.isEmpty()) {
            return this;
        }
        final List<Row> next = new ArrayList<>(rows.size());
        for (Row r : rows) {
            if (!recipeId.equals(r.id())) {
                next.add(r);
            }
        }
        return next.size() == rows.size() ? this : new ShanhaiDeletedRecipeList(next);
    }

    /** 按 id 找（没有 ⇒ {@code null}）。 */
    public Row find(String recipeId) {
        if (recipeId == null) {
            return null;
        }
        for (Row r : rows) {
            if (recipeId.equals(r.id())) {
                return r;
            }
        }
        return null;
    }

    /** 一页的行文字（面板给那 12 个行标签用；服务端每帧取一次）。 */
    public List<String> pageLabels(int page) {
        final List<Row> p = page(page);
        final List<String> out = new ArrayList<>(p.size());
        for (Row r : p) {
            out.add(rowLabel(r));
        }
        return Collections.unmodifiableList(out);
    }

    // ================================================================== 小工具（都防御 null）

    private static JsonObject obj(JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonArray arr(JsonElement e) {
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key)) {
            return null;
        }
        final JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return null;
        }
        try {
            return e.getAsString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int number(JsonObject o, String key, int fallback) {
        if (o == null || !o.has(key)) {
            return fallback;
        }
        final JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return fallback;
        }
        try {
            final int v = e.getAsInt();
            return v;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int countOf(JsonElement entry, int fallback) {
        final JsonObject o = obj(entry);
        if (o == null) {
            return fallback;
        }
        if (o.has("content")) {
            final JsonObject c = obj(o.get("content"));
            if (c != null) {
                final int n = number(c, "amount", number(c, "count", fallback));
                if (n > 0) {
                    return n;
                }
            }
        }
        final int n = number(o, "amount", number(o, "count", fallback));
        return n > 0 ? n : fallback;
    }

    /** 一行摘要（日志/自检读数用）。 */
    public String statsLine() {
        final StringBuilder sb = new StringBuilder();
        sb.append("rows=").append(rows.size()).append(" pages=").append(pageCount());
        int withMain = 0;
        int noSnapshot = 0;
        int snapshotButUnknown = 0;
        for (Row r : rows) {
            if (r.hasMain()) {
                withMain++;
            } else if (!r.hasSnapshot()) {
                noSnapshot++;
            } else {
                snapshotButUnknown++;
            }
        }
        sb.append(" with_main=").append(withMain).append('/').append(rows.size());
        // 🆕 两个数分开报：missing_snapshot 可以靠"下次开机补份"修，snapshot_no_main 修不了。
        sb.append(" missing_snapshot=").append(noSnapshot)
                .append(" snapshot_no_main=").append(snapshotButUnknown);
        if (!rows.isEmpty()) {
            sb.append(" first=").append(rows.get(0).id());
        }
        return sb.toString();
    }

    /** 与 {@link #statsLine()} 同口径的小写名字（日志前缀方便 grep）。 */
    public String statsName() {
        return "deleted_recipe_list";
    }

    static {
        // 纯逻辑核的一条自证：按钮集合恰好两个，且常量与数组不会漂（见离线自检）。
        if (DETAIL_BUTTONS.length != 2) {
            throw new IllegalStateException("DETAIL_BUTTONS 必须恰好两个");
        }
    }
}
