package com.shanhai.common.recipe.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>物品原料形状的规范化 —— 全工程唯一的一份（纯 JDK ＋ Gson，可离线单跑）。</b>
 *
 * <h2>1. 为什么需要它（现场证据，逐字）</h2>
 * 用户实例 {@code config\shanhai\recipe_overrides.json} 里这条配方的 inputs 是编辑器写的：
 * <pre>
 *   shanhai:primordial_causal_weaving/new_recipe_4（产物 shanhai:light_voyage）
 *   {"type":"gtceu:long","actualAmount":1,"ingredient":{"type":"gtceu:circuit","configuration":7}},"chance":0
 *   {"type":"gtceu:long","actualAmount":1,
 *     "ingredient":{"type":"gtceu:long","actualAmount":4,"ingredient":{"item":"shanhai:navigate_prism"}}}
 *   {"type":"gtceu:sized","count":256,"ingredient":{"item":"gtceu:uv_field_generator"}}
 *   {"type":"gtceu:long","actualAmount":1,
 *     "ingredient":{"type":"gtceu:long","actualAmount":1,"ingredient":{"item":"gtceu:space_elevator"}}}
 *   {"type":"gtceu:long","actualAmount":4,"ingredient":{"item":"shanhai:z_boson"}}
 *   {"type":"gtceu:long","actualAmount":4,"ingredient":{"item":"shanhai:w_boson"}}
 *   {"type":"gtceu:long","actualAmount":64,"ingredient":{"item":"shanhai:graviton"}}
 * </pre>
 * 全库 67,724 条导出配方的只读扫描（{@code temp\zhuguang-bug\work-shapes.cjs}）：
 * <pre>
 *   输入侧 item 条目按外层形状：sized 77,077（其中 count=1 有 **53,527**）／circuit 10,589
 *                             ／裸 {"item":…} **1** 条／gtceu:long **0** 条
 *   输入侧含 long 层的：**2** 条 —— 就是上面这条配方的 navigate_prism 与 space_elevator
 *   产出侧 gtceu:long：949 条，全部属于 gtceu:star_core_stripper（产出侧合法且常见）
 * </pre>
 * ⇒ 两个结论都是数出来的，不是猜的：
 * <ol>
 *   <li><b>标准形态 = 一律带 {@code sized} 包装，连 N=1 也带</b>
 *       （53,527/53,528 的 count=1 输入就是 {@code {"type":"gtceu:sized","count":1,…}}）；
 *       裸 {@code {"item":…}} 全库只有 1 条，不能当范式；</li>
 *   <li>{@code gtceu:long} 在<b>输入侧</b>全库只出现在这一条配方里 ⇒ 它是本编辑器
 *       「把活配方里那条 {@code LongIngredient} 原样 encode 出来」写出去的，
 *       不是 KJS 底本形状（底本是 {@code .itemInputs('4x shanhai:navigate_prism')}）。</li>
 * </ol>
 *
 * <h2>2. {@code long} 在输入侧的实际行为（javap 实证，不是推断）</h2>
 * 匹配链（{@code PrimordialModuleRecipeLogic:1142} 那第一句）：
 * <pre>
 *   RecipeRunnerHelper.matchRecipe → matchRecipeInput
 *     → handleRecipe(IO.IN, holder, recipe.inputs, emptyMap, false, recipe, true)   [gtlcore 偏移 14-34]
 *     → RecipeRunner.handle(...simulate=true)
 *     → RecipeRunner.fillContent：simulate 那支**每条 content 都进表**（含 chance==0 的电路；
 *       非 simulate 那支才是 chance==0 跳过，偏移 129-239）
 *     → RecipeRunner.handleInputNotDistinct → RecipeHandlePart.handleRecipe(IO.IN,…)
 *     → IRecipeHandler.handleRecipe → NotifiableItemStackHandler.handleIngredient   [gtceu]
 * </pre>
 * {@code handleIngredient} 里的<b>需求量取的是 {@code ingredient.getItems()[i].getCount()}</b>
 * （偏移 83-217：{@code ingredient.test(栈)} 过硬之后逐候选 {@code getItems()}、按栈上数量
 * {@code extractItem}、把样本栈 {@code setCount} 扣到空才 {@code iterator.remove()}），
 * <b>不是</b> {@code getAmount()}。而 gtlcore 的 {@code LongIngredient.getItems()} 是
 * <pre>
 *   getInner().getItems() → 每栈 copyWithCount(Ints.saturatedCast(actualAmount))   [LongIngredient 偏移 93-129]
 * </pre>
 * ⇒ <b>数量只认【最外层】那个 long 的 {@code actualAmount}</b>；内层那个 4 被
 * {@code copyWithCount} 整个盖掉 ⇒ 生效量变成 <b>1</b>。
 * 第二条读数量的路（并行比）同结论：{@code ItemRecipeCapability.getMaxParallelRatio}
 * 偏移 82-104 是 {@code ingredient instanceof SizedIngredient → getAmount()}，
 * 而 {@code LongIngredient extends SizedIngredient}、{@code getAmount()} = 外层 saturatedCast。
 * <p>⇒ <b>long 不会让需求变大，只会让需求悄悄变小</b>（4 变成 1）：它是<b>静默丢值</b>，
 * 不是"永远匹配不上"的唯一成因；但它同时是 <b>GT 匹配链在输入侧没有任何先例的形状</b>
 * （全库 0 条），必须收回到标准形态。
 *
 * <h2>3. 规范化规则（三条，全部可审计）</h2>
 * <ol>
 *   <li><b>只在链上出现 {@code gtceu:long} 时动手</b>。已经是 {@code sized}／{@code circuit}／
 *       裸 {@code {"item":…}} 的一律<b>一个字节都不碰</b>（幂等：re-save 产物逐字节相同）；</li>
 *   <li><b>剥到最内层</b>，数量取<b>链上各层 {@code actualAmount}/{@code count} 的最大值</b>
 *       （{@code max} 方向 = 只会"要多"、不会"要少" —— 本工程红线：宁可缺，不可假）。
 *       对本题数据：{@code 1→4} 给 4、{@code 1→1} 给 1、单层 4/64/256 原样；</li>
 *   <li><b>写成 GT 的标准形态</b>：电路 → 裸 {@code {"type":"gtceu:circuit","configuration":N}}
 *       （{@code IntCircuitIngredient#toJson} 原样，全库 10,589 条）；
 *       其余 → {@code {"type":"gtceu:sized","count":N,"ingredient":<最内层>}}（全库 77,077 条）。
 *       数量超过 {@code Integer.MAX_VALUE} 时<b>保留 long 不动</b>（{@code sized.count} 是 int，
 *       硬写会截断），并打 {@code kept_long reason=amount_overflow}。</li>
 * </ol>
 *
 * <h2>4. 三个落点（都在既有漏斗上，不新增调用面）</h2>
 * <pre>
 *   活配方读回 : ShanhaiRecipeIoApply.applyTable      → normalizeTableCopy（旧数据进 GT codec 之前就剥开）
 *   活配方写出 : ShanhaiRecipeIoApply.encode          → standardize      （活配方里的 LongIngredient 不再被 encode 成 long）
 *   落盘漏斗   : ShanhaiRecipeOverrideStore.upsert    → normalizeEntry   （与 ShanhaiCircuitCatalyst.forceInEntry 同一位置）
 * </pre>
 *
 * <h2>5. 负对照</h2>
 * {@link #setEnabled(boolean)} 是给离线自证的负对照用的：关掉之后 {@link #standardize}
 * 原样返回 ⇒「规范化后没有 long」这条断言必须变红。生产路径上永远是 {@code true}。
 */
public final class ShanhaiIngredientShape {

    /** 可 grep 的日志标记（与 {@code ShanhaiCircuitCatalyst.LOG_MARK} 同级）。 */
    public static final String LOG_MARK = "ingredient_normalized";

    private static final String T_LONG = "gtceu:long";
    private static final String T_SIZED = "gtceu:sized";
    private static final String T_CIRCUIT = "gtceu:circuit";
    private static final String T_INT_CIRCUIT = "gtceu:int_circuit";

    /** GT 物品那条 cap 的键名（{@code GTRecipeSchema}/{@code GTRecipe} 的 inputs.outputs 都是它）。 */
    private static final String K_ITEM = "item";
    private static final String K_TYPE = "type";
    private static final String K_INGREDIENT = "ingredient";
    private static final String K_CONTENT = "content";
    private static final String K_COUNT = "count";
    private static final String K_ACTUAL = "actualAmount";
    private static final String K_CONFIG = "configuration";

    /** 生产恒 {@code true}；{@code false} 只给离线自证的负对照。 */
    private static volatile boolean enabled = true;

    private ShanhaiIngredientShape() {}

    /** 负对照用：关掉/恢复规范化。 */
    public static void setEnabled(boolean v) {
        enabled = v;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    // ══════════════════════════════════════════════════════ 读兼容（只读，不改对象）

    /**
     * 一条 content（或一条裸 ingredient）的<b>生效数量</b> —— 只读，不改任何对象。
     *
     * <p>与 {@link #standardize} 同源：返回的就是"规范化会写回去的那个数"，
     * 否则"读出来的量"和"存下去的量"会分叉（本工程最怕那类静默失败）。
     *
     * <p>三种形状各有口径（全部来自 javap，见类注释 §2）：
     * <pre>
     *   包装链 long/sized  → 链上各层 actualAmount/count 的最大值（外层覆盖内层）
     *   裸 {"item":…}/{"tag":…} → 1（GT 的 Ingredient.getItems() 就是那一栈，包里写几个就是几个）
     *   裸 gtceu:circuit   → 1（IntCircuitIngredient 恒 1）
     * </pre>
     *
     * @return 生效数量；形状不认识时返回 {@code -1}
     */
    public static long amountOf(JsonElement el) {
        final Chain chain = peelOf(el);
        if (chain != null) {
            return chain.max();
        }
        final JsonObject core = coreOf(el);
        if (core == null) {
            return -1L;
        }
        final String type = typeOf(core);
        if (T_CIRCUIT.equals(type) || T_INT_CIRCUIT.equals(type)) {
            return 1L;
        }
        return core.has("item") || core.has("tag") ? 1L : -1L;
    }

    /** 这条 content 里有没有 {@code gtceu:long} 层（判"是不是旧数据"的唯一判据）。 */
    public static boolean hasLong(JsonElement el) {
        final Chain chain = peelOf(el);
        return chain != null && chain.hasLong;
    }

    /** 旧数据那条 long 的原始链（{@code "1->4"}，日志/自证用）。 */
    public static String chainOf(JsonElement el) {
        final Chain chain = peelOf(el);
        return chain == null ? "" : chain.chainText();
    }

    // ══════════════════════════════════════════════════════ 规范化（纯函数：给什么回什么）

    /**
     * 把一条 content（或一条裸 ingredient）写成 GT 的标准形态。<b>不改入参</b>。
     *
     * <p>不需要动 / 不敢动的情况（没有 long 层／数量溢出／形状不认识）<b>原样返回同一个实例</b>
     * ⇒ 调用方可以用 {@code out == in} 判"没动过"。
     */
    public static JsonElement standardize(JsonElement el) {
        if (!enabled) {
            return el;
        }
        final Fix fix = fixOf(el);
        return fix == null || fix.standard == null ? el : fix.standard;
    }

    /**
     * 就地规范化一张 GT 形状的表（{@code {"item":[…]}}）。
     *
     * <p>只碰 {@code item} 数组 —— <b>fluid 与 eu 的 content 是另一种形状</b>
     * （{@code {"amount":…,"value":[…]}} / 一个纯数字），一律不碰。
     *
     * @param id    只进日志
     * @param which {@code "inputs"}／{@code "outputs"}／{@code "fields.inputs"}…（判 input/output 与日志用）
     */
    public static Report normalizeTable(String id, String which, JsonObject table) {
        final Report rep = new Report();
        if (!enabled || table == null || !hasItemArray(table)) {
            return rep;
        }
        final boolean inputSide = isInputSide(which);
        final JsonArray arr = table.getAsJsonArray(K_ITEM);
        for (int i = 0; i < arr.size(); i++) {
            final Fix fix = fixOf(arr.get(i));
            if (fix == null) {
                continue;
            }
            if (fix.standard == null) {
                // 只记录、不动手（数量溢出这一类：我们不敢替他决定）
                rep.lines.add(fix.keptLine(id, which, inputSide, i));
                continue;
            }
            arr.set(i, fix.standard);
            rep.changed++;
            rep.lines.add(fix.fixedLine(id, which, inputSide, i));
        }
        return rep;
    }

    /**
     * 换出一张规范化后的表（<b>不改入参</b>）；没有需要改的条目时原样返回同一个实例（零成本）。
     *
     * <p>读回路径用它（{@code ShanhaiRecipeIoApply.applyTable}）：旧数据在这里被剥开，
     * 进 GT codec 的已经是标准形态 ⇒ 活配方里<b>不会再出现 {@code LongIngredient}</b>。
     *
     * @param sink 非 null ⇒ 把每条的日志行写进去（调用方负责 {@code LOGGER}；本类是纯核，不 import 游戏类）
     */
    public static JsonObject normalizeTableCopy(String id, String which, JsonObject table, Report sink) {
        if (!enabled || table == null || !hasItemArray(table)) {
            return table;
        }
        final boolean inputSide = isInputSide(which);
        JsonObject copy = null;
        final JsonArray src = table.getAsJsonArray(K_ITEM);
        for (int i = 0; i < src.size(); i++) {
            final Fix fix = fixOf(src.get(i));
            if (fix == null) {
                continue;
            }
            if (fix.standard == null) {
                if (sink != null) {
                    sink.lines.add(fix.keptLine(id, which, inputSide, i));
                }
                continue;
            }
            if (copy == null) {
                copy = table.deepCopy();
            }
            copy.getAsJsonArray(K_ITEM).set(i, fix.standard);
            if (sink != null) {
                sink.changed++;
                sink.lines.add(fix.fixedLine(id, which, inputSide, i));
            }
        }
        return copy == null ? table : copy;
    }

    /**
     * <b>落盘漏斗</b>：把一条覆盖层条目里输入/输出侧的 item 条目规范化（就地改）。
     *
     * <p>两种 entry 形状都覆盖（与 {@code ShanhaiCircuitCatalyst.forceInEntry} 同一份纪律）：
     * <pre>
     *   op=set : entry.fields.inputs.item[]  / entry.fields.outputs.item[]
     *   op=add : entry.recipe.inputs.item[]  / entry.recipe.outputs.item[]
     * </pre>
     */
    public static Report normalizeEntry(JsonObject entry) {
        final Report rep = new Report();
        if (!enabled || entry == null) {
            return rep;
        }
        final String id = entry.has("id") && entry.get("id").isJsonPrimitive()
                ? entry.get("id").getAsString() : "?";
        merge(rep, id, "fields.inputs", entry, "fields", "inputs");
        merge(rep, id, "fields.outputs", entry, "fields", "outputs");
        merge(rep, id, "recipe.inputs", entry, "recipe", "inputs");
        merge(rep, id, "recipe.outputs", entry, "recipe", "outputs");
        return rep;
    }

    private static void merge(Report into, String id, String label, JsonObject entry,
                              String holderKey, String tableKey) {
        if (!entry.has(holderKey) || !entry.get(holderKey).isJsonObject()) {
            return;
        }
        final JsonObject holder = entry.getAsJsonObject(holderKey);
        if (!holder.has(tableKey) || !holder.get(tableKey).isJsonObject()) {
            return;
        }
        final Report r = normalizeTable(id, label, holder.getAsJsonObject(tableKey));
        into.changed += r.changed;
        into.lines.addAll(r.lines);
    }

    // ══════════════════════════════════════════════════════ 核心：剥链 → 重建

    private static boolean hasItemArray(JsonObject table) {
        return table.has(K_ITEM) && table.get(K_ITEM).isJsonArray();
    }

    private static boolean isInputSide(String which) {
        return which != null && which.endsWith("inputs");
    }

    /**
     * 一条 content/ingredient ⇒ 一条 {@link Fix}；{@code null} = 不需要动（不是长链）。
     *
     * <p>两种入参形状都接受：Content JSON（带 {@code content} 键）与裸 ingredient。
     * {@code Fix.standard == null} 表示"是 long 链但不敢动"（数量溢出等），只记录不改。
     */
    private static Fix fixOf(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        final JsonObject o = el.getAsJsonObject();
        final boolean contentShaped = o.has(K_CONTENT) && o.get(K_CONTENT).isJsonObject();
        final Chain chain = peel(contentShaped ? o.get(K_CONTENT) : o);
        if (chain == null || !chain.hasLong) {
            return null;
        }
        return new Fix(rebuild(chain, contentShaped ? o : null), chain);
    }

    private static Chain peelOf(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        final JsonObject o = el.getAsJsonObject();
        return peel(o.has(K_CONTENT) && o.get(K_CONTENT).isJsonObject() ? o.get(K_CONTENT) : o);
    }

    /** 剥掉 content 那一层后拿到的 ingredient 对象（不剥包装；不认识就 null）。 */
    private static JsonObject coreOf(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        final JsonObject o = el.getAsJsonObject();
        if (o.has(K_CONTENT) && o.get(K_CONTENT).isJsonObject()) {
            return o.getAsJsonObject(K_CONTENT);
        }
        return o;
    }

    /**
     * 把 {@code long/sized} 包装一层层剥掉，记下每层的数量；{@code null} = 根本不是包装链。
     *
     * <p>⚠️ 只认带 {@code ingredient} 子对象的那一支：{@code {"item":…}} / {@code {"item":…,"nbt":…}}
     * 这类原版平铺形状没有 {@code ingredient}，不会被误当成包装。
     */
    private static Chain peel(JsonElement el) {
        final Chain chain = new Chain();
        JsonElement cur = el;
        while (cur != null && cur.isJsonObject()) {
            final JsonObject o = cur.getAsJsonObject();
            final String type = typeOf(o);
            if (!T_LONG.equals(type) && !T_SIZED.equals(type)) {
                break;
            }
            if (!o.has(K_INGREDIENT) || !o.get(K_INGREDIENT).isJsonObject()) {
                return null;            // 形状不认识 ⇒ 不敢动
            }
            final Long n = numberOf(o, T_LONG.equals(type) ? K_ACTUAL : K_COUNT);
            if (n == null) {
                return null;
            }
            if (T_LONG.equals(type)) {
                chain.hasLong = true;
            } else {
                chain.hasSized = true;
            }
            chain.amounts.add(n);
            cur = o.get(K_INGREDIENT);
        }
        if (chain.amounts.isEmpty() || cur == null || !cur.isJsonObject()) {
            return null;
        }
        chain.core = cur.getAsJsonObject();
        return chain;
    }

    /**
     * 用剥出来的链重建成 GT 标准形态；{@code null} = 不敢动（保留原样，另记一行日志）。
     *
     * @param contentHolder 非 null ⇒ 入参是 Content JSON：换掉它的 {@code content} 键，
     *                      {@code chance/maxChance/tierChanceBoost} 等键<b>一个字节不动</b>
     */
    private static JsonElement rebuild(Chain chain, JsonObject contentHolder) {
        final long amount = chain.max();
        if (amount <= 0L || amount > Integer.MAX_VALUE) {
            // size.count 是 int（javap：SizedIngredient.amount 是 int）⇒ 硬写会截断，
            // 数量为 0/负数也不是我们能替他决定的 ⇒ 两者都保留原样，交给调用方记一行
            chain.overflow = (amount > Integer.MAX_VALUE);
            return null;
        }
        final JsonObject core = chain.core;
        final String coreType = typeOf(core);
        final JsonElement standardContent;
        if (T_CIRCUIT.equals(coreType) || T_INT_CIRCUIT.equals(coreType)) {
            // 电路的标准形态就是裸的 IntCircuitIngredient#toJson（全库 10,589 条）
            final JsonObject c = new JsonObject();
            c.addProperty(K_TYPE, T_CIRCUIT);
            final JsonElement cfg = core.get(K_CONFIG);
            c.add(K_CONFIG, cfg == null ? new JsonPrimitive(0) : cfg.deepCopy());
            chain.toCircuit = true;
            if (amount != 1L) {
                chain.circuitAmountDropped = amount;
            }
            standardContent = c;
        } else {
            final JsonObject sized = new JsonObject();
            sized.addProperty(K_TYPE, T_SIZED);
            sized.addProperty(K_COUNT, amount);
            sized.add(K_INGREDIENT, core.deepCopy());
            standardContent = sized;
        }
        if (contentHolder == null) {
            return standardContent;
        }
        final JsonObject out = contentHolder.deepCopy();
        out.add(K_CONTENT, standardContent);
        return out;
    }

    private static String typeOf(JsonObject o) {
        if (!o.has(K_TYPE)) {
            return "";
        }
        final JsonElement t = o.get(K_TYPE);
        return t.isJsonPrimitive() && t.getAsJsonPrimitive().isString() ? t.getAsString() : "";
    }

    private static Long numberOf(JsonObject o, String key) {
        if (!o.has(key)) {
            return null;
        }
        final JsonElement v = o.get(key);
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return v.getAsLong();
        } catch (Throwable t) {
            return null;
        }
    }

    // ══════════════════════════════════════════════════════ 数据结构

    /** 一条包装链（外 → 内）。 */
    private static final class Chain {
        final List<Long> amounts = new ArrayList<>(2);
        JsonObject core;
        boolean hasLong;
        boolean hasSized;
        boolean toCircuit;
        boolean overflow;
        long circuitAmountDropped;

        long max() {
            long m = Long.MIN_VALUE;
            for (long a : amounts) {
                if (a > m) {
                    m = a;
                }
            }
            return m;
        }

        /** {@code "1->4"}（外 → 内）。 */
        String chainText() {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < amounts.size(); i++) {
                if (i > 0) {
                    sb.append("->");
                }
                sb.append(amounts.get(i));
            }
            return sb.toString();
        }
    }

    /** 一条改动的完整证据（在哪、从什么形状到什么形状、数量多少、原来的链长什么样）。 */
    private static final class Fix {
        final JsonElement standard;
        final Chain chain;

        Fix(JsonElement standard, Chain chain) {
            this.standard = standard;
            this.chain = chain;
        }

        /** 任务书要求的那一行：{@code input_normalized id=… from=long to=sized count=N}。 */
        String fixedLine(String id, String where, boolean inputSide, int index) {
            final StringBuilder sb = head(inputSide, id, where, index);
            sb.append(" from=").append(T_LONG)
                    .append(" to=").append(chain.toCircuit ? T_CIRCUIT : T_SIZED)
                    .append(" count=").append(chain.max())
                    .append(" chain=").append(chain.chainText());
            if (chain.toCircuit && chain.circuitAmountDropped != 0L) {
                sb.append(" note=circuit_amount_dropped(").append(chain.circuitAmountDropped).append(')');
            }
            return sb.toString();
        }

        /** 认出是旧数据但不动手时的那一行（不许静默）。 */
        String keptLine(String id, String where, boolean inputSide, int index) {
            final StringBuilder sb = head(inputSide, id, where, index);
            sb.append(" from=").append(T_LONG)
                    .append(" kept=1")
                    .append(" reason=").append(chain.overflow ? "amount_overflow" : "amount_non_positive")
                    .append(" count=").append(chain.max())
                    .append(" chain=").append(chain.chainText());
            return sb.toString();
        }

        private StringBuilder head(boolean inputSide, String id, String where, int index) {
            final StringBuilder sb = new StringBuilder(96);
            sb.append(inputSide ? "input_normalized" : "output_normalized")
                    .append(" id=").append(id)
                    .append(" where=").append(where == null ? "?" : where)
                    .append(" index=").append(index);
            return sb;
        }
    }

    /** {@link #normalizeTable} / {@link #normalizeEntry} 的报告。 */
    public static final class Report {
        private int changed;
        private final List<String> lines = new ArrayList<>(0);

        /** 真的被改动的条目数（0 = 一个字节都没动）。 */
        public int changed() {
            return changed;
        }

        /** 每条的日志行（已带 {@code input_normalized/output_normalized} 前缀）。 */
        public List<String> lines() {
            return lines;
        }

        @Override
        public String toString() {
            return "changed=" + changed + " " + lines;
        }
    }
}
