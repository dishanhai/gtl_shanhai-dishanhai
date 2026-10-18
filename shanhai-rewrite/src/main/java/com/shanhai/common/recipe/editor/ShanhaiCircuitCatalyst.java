package com.shanhai.common.recipe.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shanhai.ShanhaiMod;

/**
 * <b>编程电路恒为催化剂（不消耗）—— 全工程唯一的一份规范化实现。</b>
 *
 * <h2>1. 用户口径（原话，逐字）</h2>
 * <blockquote>「你给配方编辑器新增功能，<b>编程电路强制为催化剂</b>，免得我还要<b>一直改</b>，
 * 还老是<b>忘了改</b>」</blockquote>
 * 现场读数（用户实例 {@code config/shanhai/recipe_overrides.json}，2026-10-16 只读盘点）：
 * 25 条 entry 里输入侧共 <b>13</b> 个电路格 —— <b>11</b> 个已经是他手改的 {@code "chance":0}，
 * <b>2</b> 个没带 {@code chance} 键（⇒ GT 缺省 10000 ⇒ <b>当消耗品</b>），正是他说"忘了改"的那两个：
 * <pre>
 *   ${id}=shanhai:primordial_matter_recombination/new_recipe_5  configuration=7  ← 无 chance 键
 *   ${id}=shanhai:primordial_matter_recombination/new_recipe_6  configuration=2  ← 无 chance 键
 * </pre>
 *
 * <h2>2. "不消耗"在 JSON 里只有一种表达：{@code chance: 0}</h2>
 * GT 没有 {@code notConsumable} 这样的字段，判据就在 {@code Content.chance} 上。三层证据：
 * <ul>
 *   <li>{@code GTRecipeBuilder.notConsumable(...)} 的字节码只把 {@code chance} 置 0
 *       （{@code maxChance} 保持 {@code getMaxChancedValue()} = 10000）—— 见
 *       {@link ShanhaiIoTable.Cell#setChanceAndCatalyst(int, int, boolean)} 的 A6 取证；</li>
 *   <li>{@code Content.drawChance} 用 {@code percent = 100f*chance/maxChance}，
 *       {@code percent == 0} ⇒ 红字「§c不消耗§r」；</li>
 *   <li>本工程既有的判据也是它：{@link ShanhaiIoTable#isNotConsumable} 只认 {@code chance == 0}。</li>
 * </ul>
 * ⇒ <b>不引入任何新机制</b>（红线：不许改判定逻辑本身），只把该为 0 的那个数改成 0。
 *
 * <h2>3. 🔴 为什么只有两个调用点，而不是在七八个地方各打一个补丁</h2>
 * 落盘链上<b>所有</b>会写进 item 输入的路径最终都汇到同一个写盘函数
 * {@link ShanhaiRecipeOverrideStore#upsert(JsonObject)}（覆盖文件 {@code config/shanhai/
 * recipe_overrides.json} 唯一的"加/改条目"出口）；活配方那一侧则都汇到
 * {@link ShanhaiRecipeIoApply#applyTable}（JSON 表 ⇒ 活 {@code GTRecipe} 表的唯一入口）。
 * 本类只在这两个漏斗里被调用，规范化逻辑<b>只有这一份</b>：
 * <pre>
 *   落盘（盘上那份） : upsert(entry)                       → {@link #forceInEntry(JsonObject)}
 *   活配方（本局生效）: ShanhaiRecipeIoApply.applyTable(...) → {@link #forceInTable(String, String, JsonObject)}
 * </pre>
 *
 * <h2>4. 🔴🔴 只在【输入侧】动手</h2>
 * 输出侧的 {@code chance == 0} 是「0% 产出」，<b>不是</b>"不消耗"。见
 * {@link ShanhaiIoTable} 里 {@code fill(...)} 那条注释：{@code catalystRepair} 只在
 * {@code inputSide} 为真时才置。本类沿用同一条纪律 —— 表名不是 {@code inputs} 一律原样返回。
 *
 * <h2>5. 幂等</h2>
 * 只在"值真的要变"时才写：已经是 {@code chance == 0} 的条目<b>一个字节都不碰</b>
 * （{@code JsonObject.addProperty} 对已存在的键是就地替换，Gson 的 {@code LinkedTreeMap}
 * 会保留该键原来的位置 ⇒ 重复保存的产物逐字节相同）。日志也只在 {@code n > 0} 时打。
 */
public final class ShanhaiCircuitCatalyst {

    /** 与工程其它地方同一个日志前缀（便于 {@code grep "\[SHANHAI-EDIT\]"}）。 */
    private static final String PREFIX = "[SHANHAI-EDIT]";

    /** 可 grep 的落盘标记。只在真的改动过时出现。 */
    public static final String LOG_MARK = "circuit_forced_catalyst";

    /** GT 的 {@code IntCircuitIngredient#toJson} 写出来的 ingredient 类型（javap 实测 {@code ldc "circuit"}）。 */
    private static final String CIRCUIT_TYPE = "gtceu:circuit";

    /**
     * 裸的编程电路<b>物品</b> id（NBT 里没有 {@code Configuration} 的那种）。
     *
     * <p>为什么也认它：{@link ShanhaiIoTable#ingredientForStack} 对"没有号的裸芯片"走的是
     * {@code Ingredient.of(栈)}，序列化出来就是 {@code {"item":"gtceu:programmed_circuit"}}
     * —— 它<b>仍然是编程电路</b>，只是没带号。id 出处：{@code gtceu-1.20.1-1.4.4.jar} 的
     * {@code assets/gtceu/lang/en_us.json} 里 {@code "item.gtceu.programmed_circuit"}。
     */
    private static final String CIRCUIT_ITEM = "gtceu:programmed_circuit";

    /**
     * 规范化的唯一开关。
     *
     * <p>生产路径上永远是 {@code true}（没有任何地方把它关掉）。
     * {@code false} 只给离线单测的<b>负对照</b>用：关掉它之后，那条 {@code chance:10000} 的坏样本
     * <b>必须</b>原样留在产物里 —— 证明这把尺子是活的，而不是"恒绿"。
     */
    private static volatile boolean enabled = true;

    private ShanhaiCircuitCatalyst() {}

    /** 负对照用：关掉/恢复规范化。 */
    public static void setEnabled(boolean v) {
        enabled = v;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    // ================================================================== 入口

    /**
     * <b>落盘漏斗</b>：把一条覆盖文件条目里所有输入侧的电路格规范成催化剂。
     *
     * <p>两种 entry 形状都覆盖（这也是"不许漏路径"的判据）：
     * <pre>
     *   op=set : entry.fields.inputs.item[]      ← setIo / applyEdits / 面板保存 / 原版配方
     *   op=add : entry.recipe.inputs.item[]      ← 新建 GT 配方 / 新建后再保存
     * </pre>
     *
     * @return 真的被改动的格数（0 = 一个字节都没动）
     */
    public static int forceInEntry(JsonObject entry) {
        if (!enabled || entry == null) {
            return 0;
        }
        final String id = entry.has("id") && entry.get("id").isJsonPrimitive()
                ? entry.get("id").getAsString() : "?";
        int n = 0;
        if (entry.has("fields") && entry.get("fields").isJsonObject()) {
            n += forceInInputsOf(id, "fields.inputs", entry.getAsJsonObject("fields"));
        }
        if (entry.has("recipe") && entry.get("recipe").isJsonObject()) {
            n += forceInInputsOf(id, "recipe.inputs", entry.getAsJsonObject("recipe"));
        }
        return n;
    }

    private static int forceInInputsOf(String id, String where, JsonObject holder) {
        if (!holder.has("inputs") || !holder.get("inputs").isJsonObject()) {
            return 0;
        }
        return core(id, where, holder.getAsJsonObject("inputs"));
    }

    /**
     * <b>活配方漏斗</b>：在把一张 JSON 表灌进活 {@code GTRecipe} 之前先规范化。
     *
     * @param id    配方 id（只进日志）
     * @param which {@code "inputs"} / {@code "outputs"} / {@code "tickInputs"} / {@code "tickOutputs"}
     * @return 真的被改动的格数
     */
    public static int forceInTable(String id, String which, JsonObject table) {
        if (!enabled || table == null || which == null) {
            return 0;
        }
        // 🔴 只有输入侧。输出侧的 chance==0 是「0% 产出」，改它＝静默改变配方语义。
        if (!"inputs".equals(which)) {
            return 0;
        }
        return core(id, which, table);
    }

    /**
     * 真正的实现（两个漏斗都汇到这里）。
     *
     * <p>⚠️ {@code where} 只是<b>日志标签</b>（{@code "inputs"} / {@code "fields.inputs"} /
     * {@code "recipe.inputs"}）；"是不是输入侧"这件事由调用方在进来之前判完 ——
     * 这里不再判一次，免得把标签当语义用（第一版就是这么写错的：把 {@code "fields.inputs"}
     * 丢给了只认 {@code "inputs"} 的那道闸门 ⇒ 规范化静默失效，而日志上一切正常）。
     */
    private static int core(String id, String where, JsonObject table) {
        final int n = scan(table);
        if (n > 0) {
            ShanhaiMod.LOGGER.info("{} {} id={} where={} n={} "
                            + "(编程电路恒为催化剂：chance 一律 0；GT 没有第二个字段表达「不消耗」，"
                            + "见 ShanhaiCircuitCatalyst 的类注释)",
                    PREFIX, LOG_MARK, id, where, n);
        }
        return n;
    }

    // ================================================================== 核心

    /** 扫一张 GT 形状的表（{@code {"item":[…],"fluid":[…]}}）里的 item 数组。 */
    private static int scan(JsonObject table) {
        if (!table.has("item") || !table.get("item").isJsonArray()) {
            return 0;
        }
        final JsonArray arr = table.getAsJsonArray("item");
        int n = 0;
        for (JsonElement el : arr) {
            int r = forceOne(el);
            if (r != 0) {
                n++;
            }
        }
        return n;
    }

    /** @return 这一格是否被改动 */
    private static int forceOne(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return 0;
        }
        final JsonObject entry = el.getAsJsonObject();
        if (!isCircuitEntry(entry)) {
            return 0;
        }
        if (hasZeroChance(entry)) {
            return 0;       // 已经是催化剂 ⇒ 一个字节都不动（幂等的落点）
        }
        entry.addProperty("chance", 0);
        // A6 的教训：GT 画那行字用 percent = 100f*chance/maxChance，而 NaN == 0 是 false
        // ⇒ (0,0) 会被画成黄字 NaN%。这里只在 maxChance【存在且 <= 0】时补满
        //（缺省不写 = GT 自己按 10000 处理，与用户实例里那 11 条既有催化剂形状逐字一致）。
        if (entry.has("maxChance") && entry.get("maxChance").isJsonPrimitive()
                && entry.get("maxChance").getAsJsonPrimitive().isNumber()
                && entry.get("maxChance").getAsInt() <= 0) {
            entry.addProperty("maxChance", ShanhaiIoTable.MAX_CHANCE_VALUE);
        }
        return 1;
    }

    private static boolean hasZeroChance(JsonObject entry) {
        if (!entry.has("chance")) {
            return false;
        }
        final JsonElement c = entry.get("chance");
        return c.isJsonPrimitive() && c.getAsJsonPrimitive().isNumber() && c.getAsInt() == 0;
    }

    /** 一条 entry 的内容是不是编程电路（认两种形状，见 {@link #CIRCUIT_TYPE} / {@link #CIRCUIT_ITEM}）。 */
    public static boolean isCircuitEntry(JsonObject entry) {
        if (entry == null || !entry.has("content")) {
            return false;
        }
        final JsonElement content = entry.get("content");
        if (content == null || !content.isJsonObject()) {
            return false;
        }
        final JsonObject c = content.getAsJsonObject();
        // 形状 A（带 sized 包装，盘上 13/13 都是它）：
        //   {"type":"gtceu:sized","count":1,"ingredient":{"type":"gtceu:circuit","configuration":N}}
        if (isCircuitIngredient(c.get("ingredient"))) {
            return true;
        }
        // 形状 B（不带包装的裸 ingredient，GT codec 也认）
        return isCircuitIngredient(c);
    }

    /** 一个 ingredient JSON 是不是编程电路。 */
    public static boolean isCircuitIngredient(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return false;
        }
        final JsonObject ing = el.getAsJsonObject();
        if (isString(ing, "type", CIRCUIT_TYPE)) {
            return true;        // 带号电路：IntCircuitIngredient#toJson
        }
        return isString(ing, "item", CIRCUIT_ITEM);   // 裸芯片（没带 Configuration）
    }

    private static boolean isString(JsonObject o, String key, String want) {
        if (!o.has(key)) {
            return false;
        }
        final JsonElement v = o.get(key);
        return v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && want.equals(v.getAsString());
    }
}
