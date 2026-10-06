package com.shanhai.common.recipe.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.gregtechceu.gtceu.api.machine.multiblock.CleanroomType;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.condition.RecipeConditionType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.shanhai.ShanhaiMod;
import com.shanhai.machine.module.ModuleLevelCondition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>配方「额外条件」的编码 / 解码 / 描述</b> —— B 组的全部机制都收在这一个类里。
 *
 * <h2>1. 用户点单（逐字）</h2>
 * <blockquote>
 * 「还有一些配方需要额外条件（例如超净间，或者我们的物质模块等级），你可以添加一个加号，
 * 然后可以通过这个加号来新增额外条件，同时，对于已有的条件，你应该也需要在面版中列出，
 * 并且右键可以编辑」
 * </blockquote>
 *
 * <h2>2. 🔴 形状：只有一种，而且它不是"我设计的"，是 GT 自己的 dispatcher 给的</h2>
 * 读通的那条链（全部由 {@code javap} 实测，不是推断）：
 * <pre>
 *   GTRecipeSerializer.CODEC        = RecordCodecBuilder
 *     "recipeConditions"            = RecipeCondition.CODEC.listOf().optionalFieldOf(…, List.of())
 *   GTRecipeSerializer.fromJson()   = CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(false, LOGGER::error)
 *     ⇒ 【Gson 那一份 json 完全由 codec 说了算】：KubeJS 造的配方也走 fromJson（见 §4）
 *   RecipeCondition.CODEC           = GTRegistries.RECIPE_CONDITIONS.codec()
 *                                       .dispatch(RecipeCondition::getType, t -&gt; t.codec)
 *     ⇒ KeyDispatchCodec ⇒ 平铺：{"type":"&lt;类型名&gt;", …该类型自己的字段…}
 *   实测样本（用户实例 local/kubejs/export 里 1509 条带条件的配方）：
 *     {"recipeConditions":[{"cleanroom":"law_cleanroom","type":"cleanroom"}]}
 * </pre>
 * ⇒ 本类<b>不手拼</b>那个 JSON：编码走 {@link RecipeCondition#CODEC} 的
 * {@code encodeStart}，解码走同一个 codec 的 {@code parse}。
 * 「编辑器的形状」与「配方加载器认的形状」出自<b>同一段代码</b>，没有"两边各写一份靠人核对"的漂移面。
 *
 * <h2>3. 🔴 为什么【不能】走 KubeJS 那个 {@code RecipeComponent}（实证，含一条自我更正）</h2>
 * 上游那个组件（{@code GTRecipeComponents$5}）的 {@code write} 与 {@code read} <b>形状不对称</b>：
 * <pre>
 *   write(cond)  → { "type": &lt;注册名&gt;, "data": &lt;cond.serialize()&gt; }   ← 字段包在 "data" 里
 *   read(json)   → 取 json["type"] 拿到类型，再调 cond.deserialize(&lt;外层那个对象&gt;)
 *                  ← 它把【外层】交回去，不是 "data"
 * </pre>
 * 后果有两条，都在本工程里能兑现：
 * <ol>
 *   <li>🔴 {@code recipe.set("recipeConditions", …)} ⇒ {@code RecipeJS.serialize()} 把上面那个
 *       {@code data} 包裹的形状写进 recipe.json，而 GT 的加载器走
 *       {@code GTRecipeSerializer.fromJson → RecipeCondition.CODEC}（KeyDispatchCodec，<b>平铺</b>）
 *       ⇒ <b>整条配方 json 解析失败</b>（不是"条件没生效"，是配方没了）。</li>
 *   <li>🔴 <b>逐类判断</b>（这一条是 2026-10-05 自检抓出来后更正的）：字段会不会丢，
 *       取决于那个条件的 {@code deserialize()} 有没有跟着写"从外层读"：
 *       <pre>
 *         CleanroomCondition          deserialize 读的是【外层】的 "cleanroom"（字节码实测）
 *                                     ⇒ 给它 data 包裹的形状 ⇒ 档位静默退回默认值 ✗
 *         ModuleLevelCondition        本工程重写过，两种形状都认（见它的 deserialize 注释）✓
 *       </pre>
 *       ⇒ <b>不能指望"上游那个组件能原样搬运条件"</b>：它对一部分类型会静默丢字段，
 *       而我们没有一张"哪些类型安全"的可信表。</li>
 * </ol>
 * ⇒ 本工程的做法：<b>绕开那个组件</b>（见 {@link ShanhaiRecipeConditionReplay}），
 * 把平铺 JSON 直接写进 {@code RecipeJS.json}，让 GT 自己的 codec 去解析。
 * 编码/解码两边都用 {@link RecipeCondition#CODEC} ⇒「编辑器写出来的形状」与「配方加载器认的形状」
 * 出自<b>同一段代码</b>，没有"两边各写一份靠人核对"的漂移面。
 *
 * <h2>4. 未知类型一律 fail-closed（用户点单：「不要假装能编辑」）</h2>
 * {@link #decode(JsonElement)} 对任何解不出来的元素返回 {@code ok=false} 并带上原文错误；
 * 调用方（编辑/重放/自检）据此<b>整条拒收</b>，绝不"能吃几条吃几条"。
 * 面板上认不出的类型显示成 {@code 未知条件：&lt;type&gt;（只读）}。
 */
public final class ShanhaiRecipeConditions {

    public static final String PREFIX = "[SHANHAI-EDIT] editor";

    /** 🔴 覆盖文件 {@code fields} 里的键名（我们自己定的）。 */
    public static final String FIELD = "conditions";

    /** GT 配方 JSON 里的键名（GT 定的，不许改）。 */
    public static final String RECIPE_JSON_KEY = "recipeConditions";

    /** 面板上"优先做全"的两类（用户点名的）。 */
    public static final String TYPE_CLEANROOM = "cleanroom";

    /**
     * 本工程自有条件的注册名。
     * <p>🔴 口径来源（不是猜的）：{@code ShanhaiRegistry#onRecipeConditionRegister} 那一行原文是
     * {@code GTRegistries.RECIPE_CONDITIONS.register("module_level", ModuleLevelCondition.TYPE);}
     * ⇒ 键就是 {@code module_level}，<b>没有</b> {@code shanhai:} 前缀。
     */
    public static final String TYPE_MODULE_LEVEL = "module_level";

    /** 编辑表单的形态。 */
    public enum Form {
        /** 超净间：一个"档位"下拉（{@link CleanroomType#getAllTypes()}）。 */
        CLEANROOM,
        /** 物质模块等级：模块物品 id ＋ 数量（数量只用于显示，门槛由模块自身等级决定）。 */
        MODULE_LEVEL,
        /** 已注册、但本版没有专属表单 ⇒ 只能"新增默认值 / 删除 / 取反"，不假装能编辑。 */
        GENERIC,
        /** 注册表里没有这个类型 ⇒ 只读，面板上如实写"未知条件"。 */
        UNKNOWN
    }

    private ShanhaiRecipeConditions() {}

    // ================================================================== 类型 / 注册表

    /** 这个类型名在 GT 的条件注册表里认不认得。 */
    public static boolean isKnownType(String typeKey) {
        if (typeKey == null || typeKey.isEmpty()) {
            return false;
        }
        try {
            return GTRegistries.RECIPE_CONDITIONS.get(typeKey) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 条件实例 → 类型名（注册表里查不到就返回 {@code "?"}，绝不编一个）。 */
    public static String typeKeyOf(RecipeCondition c) {
        if (c == null) {
            return "?";
        }
        try {
            final String k = GTRegistries.RECIPE_CONDITIONS.getKey(c.getType());
            return k == null ? "?" : k;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** JSON 元素里那个 {@code type} 字段（没有就是空串）。 */
    public static String typeKeyOf(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return "";
        }
        final JsonObject o = el.getAsJsonObject();
        return o.has("type") && o.get("type").isJsonPrimitive() ? o.get("type").getAsString() : "";
    }

    /** 由类型名造一个该类型的<b>默认</b>条件（未知类型返回 null，不编）。 */
    public static RecipeCondition defaultOf(String typeKey) {
        if (typeKey == null) {
            return null;
        }
        try {
            final RecipeConditionType<?> t = GTRegistries.RECIPE_CONDITIONS.get(typeKey);
            if (t == null || t.factory == null) {
                return null;
            }
            return t.factory.createDefault();
        } catch (Throwable ex) {
            ShanhaiMod.LOGGER.warn("{} conditions_default_failed type={} err={}", PREFIX, typeKey, ex.toString());
            return null;
        }
    }

    /**
     * 注册表里全部类型名，<b>把用户点名的两类排在最前</b>（面板的"＋新增"下拉按这个顺序列）。
     */
    public static List<String> knownTypeKeys() {
        final List<String> out = new ArrayList<>();
        try {
            for (String k : GTRegistries.RECIPE_CONDITIONS.keys()) {
                if (k != null && !out.contains(k)) {
                    out.add(k);
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} conditions_registry_enum_failed err={}", PREFIX, t.toString());
        }
        out.sort((a, b) -> {
            final int ra = rank(a);
            final int rb = rank(b);
            return ra != rb ? Integer.compare(ra, rb) : a.compareTo(b);
        });
        return out;
    }

    private static int rank(String k) {
        if (TYPE_CLEANROOM.equals(k)) {
            return 0;
        }
        if (TYPE_MODULE_LEVEL.equals(k)) {
            return 1;
        }
        return 10;
    }

    /**
     * 类型名的中文/可读标签（认不出就原样返回类型名，不编中文）。
     *
     * <p>🔴 键名必须与条件注册表里的<b>真实键</b>逐字对齐 ——
     * 2026-10-05 冒烟实测的条件注册表一共 14 个键：
     * <pre>
     *   [cleanroom, module_level, adjacent_block, biome, dimension, environmental_hazard,
     *    eu_to_start, gravity, pos_y, rain, research, rock_breaker, steam_vent, thunder]
     * </pre>
     * 前两版这里写的是 {@code position_y} / {@code raining}（<b>不存在的键</b>）⇒
     * 那两个类型在面板上会显示成英文键名。现在按上表逐字对齐。
     */
    public static String typeLabel(String typeKey) {
        if (typeKey == null || typeKey.isEmpty()) {
            return "(没有类型)";
        }
        return switch (typeKey) {
            case TYPE_CLEANROOM -> "超净间";
            case TYPE_MODULE_LEVEL -> "物质模块等级";
            case "dimension" -> "维度";
            case "biome" -> "生物群系";
            case "rain" -> "下雨";
            case "thunder" -> "雷暴";
            case "pos_y" -> "Y 高度";
            case "eu_to_start" -> "启动所需 EU";
            case "environmental_hazard" -> "环境危害";
            case "research" -> "研究";
            case "rock_breaker" -> "碎岩";
            case "adjacent_block" -> "邻接方块";
            case "steam_vent" -> "蒸汽通风口";
            case "gravity" -> "重力（要 / 不要）";
            default -> typeKey;
        };
    }

    // ================================================================== 编码 / 解码

    /** 一条条件 → 平铺 JSON。算不出来返回 {@code null}（调用方必须当失败处理，不许凑合）。 */
    public static JsonElement encodeOne(RecipeCondition c) {
        if (c == null) {
            return null;
        }
        try {
            final DataResult<JsonElement> r = RecipeCondition.CODEC.encodeStart(JsonOps.INSTANCE, c);
            final JsonElement el = r.result().orElse(null);
            if (el == null) {
                ShanhaiMod.LOGGER.error("{} conditions_encode_failed type={} err={}",
                        PREFIX, typeKeyOf(c), r.error().map(e -> e.message()).orElse("(no error message)"));
            }
            return el;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} conditions_encode_threw type={} err={}", PREFIX, typeKeyOf(c), t.toString());
            return null;
        }
    }

    /**
     * 一串条件 → 平铺 JSON 数组。
     *
     * <p>🔴 <b>永不为 null</b>；任何一条算不出来都<b>整串拒收</b>（返回空数组 + ERROR），
     * 绝不产出一个"少了一条"的数组 —— 那种数组落盘之后，下一局机器就少一个门槛，
     * 而日志上看不出任何异常。
     */
    public static JsonArray encode(List<RecipeCondition> list) {
        final JsonArray arr = new JsonArray();
        if (list == null) {
            return arr;
        }
        for (RecipeCondition c : list) {
            final JsonElement el = encodeOne(c);
            if (el == null) {
                ShanhaiMod.LOGGER.error("{} conditions_encode_aborted at_index={} type={} -> 整串不产出"
                        + "（宁可没有条件，也不产出一个静默少一条的数组）", PREFIX, arr.size(), typeKeyOf(c));
                return new JsonArray();
            }
            arr.add(el);
        }
        return arr;
    }

    /** 配方的条件 → 平铺 JSON 数组（永不为 null）。 */
    public static JsonArray encodeOf(GTRecipe r) {
        return r == null ? new JsonArray() : encode(r.conditions);
    }

    /** 解码结果。{@code list} 在 {@code ok=false} 时是 null（fail-closed，不返回半截）。 */
    public record Decoded(boolean ok, List<RecipeCondition> list, String error) {
        public int size() {
            return list == null ? 0 : list.size();
        }
    }

    /**
     * 平铺 JSON 数组 → 条件列表。任何一条解不出来 ⇒ {@code ok=false} 且 {@code list=null}。
     *
     * <p>判据行：{@code conditions_decode ok=true n=2} / {@code conditions_decode ok=false …}。
     */
    public static Decoded decode(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return new Decoded(true, new ArrayList<>(), "");
        }
        if (!el.isJsonArray()) {
            return new Decoded(false, null, "conditions 必须是数组，实得 " + kind(el));
        }
        final JsonArray arr = el.getAsJsonArray();
        final List<RecipeCondition> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            final JsonElement e = arr.get(i);
            if (e == null || !e.isJsonObject()) {
                return new Decoded(false, null, "第 " + i + " 条不是对象（实得 " + kind(e) + "）");
            }
            final String typeKey = typeKeyOf(e);
            if (typeKey.isEmpty()) {
                return new Decoded(false, null, "第 " + i + " 条没有 type 字段");
            }
            if (!isKnownType(typeKey)) {
                return new Decoded(false, null, "第 " + i + " 条的 type=" + typeKey
                        + " 不在 GT 条件注册表里（未知条件类型，本版不认）");
            }
            RecipeCondition c = null;
            String why = "";
            try {
                final DataResult<RecipeCondition> r = RecipeCondition.CODEC.parse(JsonOps.INSTANCE, e);
                c = r.result().orElse(null);
                if (c == null) {
                    why = r.error().map(er -> er.message()).orElse("(no error message)");
                }
            } catch (Throwable t) {
                why = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            if (c == null) {
                return new Decoded(false, null, "第 " + i + " 条（type=" + typeKey + "）解不出来：" + why);
            }
            out.add(c);
        }
        return new Decoded(true, out, "");
    }

    /** 便利出口：解不出来返回 {@code null}（调用方按失败处理）。 */
    public static List<RecipeCondition> decodeList(JsonElement el) {
        final Decoded d = decode(el);
        if (!d.ok()) {
            ShanhaiMod.LOGGER.error("{} conditions_decode ok=false err={}", PREFIX, d.error());
            return null;
        }
        return d.list();
    }

    private static String kind(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return "null";
        }
        if (el.isJsonArray()) {
            return "array";
        }
        if (el.isJsonObject()) {
            return "object";
        }
        return "primitive(" + el + ")";
    }

    /**
     * 两份条件表是不是<b>同一份</b>（用于"这次到底改没改"）。
     *
     * <p>比较口径 = 规范化之后逐字比（递归按字典序排对象键、数组保持原序）。
     * 🔴 为什么不用 {@code JsonArray.equals}：Gson 的 {@code JsonObject.equals} 比的是
     * {@code LinkedTreeMap} 的 entrySet，而键序取决于谁写的 ⇒ 同一条条件、两个来源会判不等。
     */
    public static boolean sameAs(JsonElement a, JsonElement b) {
        return canonical(a).equals(canonical(b));
    }

    /** 递归规范化（对象键排序；数组保序）后序列化。 */
    public static String canonical(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return "null";
        }
        if (el.isJsonArray()) {
            final StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (JsonElement e : el.getAsJsonArray()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(canonical(e));
                first = false;
            }
            return sb.append(']').toString();
        }
        if (el.isJsonObject()) {
            final TreeMap<String, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> en : el.getAsJsonObject().entrySet()) {
                sorted.put(en.getKey(), en.getValue());
            }
            final StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, JsonElement> en : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append('"').append(en.getKey()).append("\":").append(canonical(en.getValue()));
                first = false;
            }
            return sb.append('}').toString();
        }
        return el.toString();
    }

    // ================================================================== 面板：造 / 读 / 描述

    /** 造一条超净间条件（{@code cleanroomName} 不认时退回默认档，并如实打一行）。 */
    public static RecipeCondition makeCleanroom(String cleanroomName, boolean reverse) {
        CleanroomType t = null;
        try {
            t = CleanroomType.getByName(cleanroomName);
        } catch (Throwable ignored) {
            // 走下面的兜底
        }
        if (t == null) {
            t = CleanroomType.CLEANROOM;
            ShanhaiMod.LOGGER.warn("{} conditions_cleanroom_unknown name={} -> 退回默认档 {}",
                    PREFIX, cleanroomName, t.getName());
        }
        final com.gregtechceu.gtceu.common.recipe.condition.CleanroomCondition c =
                new com.gregtechceu.gtceu.common.recipe.condition.CleanroomCondition(t);
        c.setReverse(reverse);
        return c;
    }

    /** 造一条物质模块等级条件。 */
    public static RecipeCondition makeModuleLevel(String moduleId, int level, boolean reverse) {
        final ModuleLevelCondition c = new ModuleLevelCondition(moduleId == null ? "" : moduleId, Math.max(0, level));
        c.setReverse(reverse);
        return c;
    }

    // ==================================================================
    // 🆕 2026-10-05（第 5 轮）：【档位】= 那个类型自己的取值表
    //
    // 用户原话（逐字，他当场纠正过我一次）：
    //   「挡位应该根据上面的类型变动指的是我要是选物质模块等级，那挡位应该是17种物质模块」
    // ⇒ 正确口径：
    //   · 类型「超净间」        ⇒ 档位 = 超净间那几档（普通 / 无菌 / 法则 …）
    //   · 类型「物质模块等级」  ⇒ 档位 = **17 种物质模块**（显示中文名，id 只进 tooltip）
    //   · 类型「维度」          ⇒ 档位 = 服务器上那套维度
    //   · 类型「生物群系」      ⇒ 档位 = 注册表里的生物群系
    //   · 其余（要/不要那种，例：无重力）⇒ **没有档位这一栏**
    //
    // 🔴 于是「模块 id 输入框」「数量」「手持」三样全部不需要了：
    //    选模块 = 在档位下拉里选；真实门槛 = 该模块自身等级（见 ModuleLevelCondition#requiredLevelForGate）。
    // ==================================================================

    /**
     * 档位的一项。
     *
     * @param key   写进条件 JSON 的那个值（{@code law_cleanroom} / {@code shanhai:apex_material_module} / 维度 id）
     * @param label 界面上一律显示这个（中文名 / 中文档位名）；<b>id 不上界面</b>，只进 {@link #hint()}
     * @param hint  tooltip 里那一行（含 id，给"我要核对内部名"的场合用）
     */
    public record Value(String key, String label, String hint) {
        /** 下拉里那一行的显示文字（带 id 的话太长，用户明确说看不懂内部 id ⇒ 只给中文名）。 */
        public String dropdown() {
            return label;
        }
    }

    /**
     * 🔴 17 个物质模块的 <b>id ＋ 中文名</b>（顺序 = 等级 1..17）。
     *
     * <h4>两个来源，都逐字核过</h4>
     * <ol>
     *   <li><b>id 与顺序</b>：{@code PrimordialModuleMachine.MODULE_LEVELS} 那 17 行
     *       （它是全工程唯一的"id → 等级"源，本表<b>不另立等级</b>）；</li>
     *   <li><b>中文名</b>：{@code assets/shanhai/lang/zh_cn.json} 的
     *       {@code item.shanhai.<id>}（原文带 {@code &$crimson-} 这类颜色前缀，这里剥掉）。</li>
     * </ol>
     * <p>🔴 为什么中文名必须写死在这里：面板在<b>服务端</b>算文字，服务端的语言环境通常是 en_us，
     * {@code ItemStack.getHoverName()} 只会给英文 ⇒ 想要中文名只能自带一张表。
     * <p>⚠️ 这张表<b>不带等级</b>：等级一律现问 {@code PrimordialModuleMachine.getModuleLevelById}
     * （见 {@link #moduleValues()}），并且 {@link #selfcheck()} 有一条断言逐项核对
     * "表里第 i 项的等级 == i"，对不上就判红 —— 这样它不可能悄悄和真源漂移。
     */
    private static final String[][] MODULE_TABLE = {
            {"shanhai:introductory_material_module", "入门物质模块"},
            {"shanhai:basic_material_module", "基础物质模块"},
            {"shanhai:material_deduction_module", "物质推演模块"},
            {"shanhai:virtual_image_material_module", "虚像物质模块"},
            {"shanhai:material_recombination_module", "物质重组模块"},
            {"shanhai:zeroing_material_module", "归零物质模块"},
            {"shanhai:dark_star_material_module", "暗星物质模块"},
            {"shanhai:imaginary_material_transition_remolding_module", "虚数物质跃迁重塑模块"},
            {"shanhai:transformation_material_module", "嬗变物质模块"},
            {"shanhai:dimensional_ascension_material_module", "升维物质模块"},
            {"shanhai:apex_material_module", "巅峰物质模块"},
            {"shanhai:chaos_material_module", "混沌物质模块"},
            {"shanhai:transfinite_material_module", "超限物质模块"},
            {"shanhai:eternal_material_module", "永恒物质模块"},
            {"shanhai:material_creation_module", "物质创造模块"},
            {"shanhai:reality_anchor_module", "现实锚点模块"},
            {"shanhai:genesis_reality_modification_module", "创始现实修改模块"},
    };

    /** 物质模块档位表（等级现问 {@code PrimordialModuleMachine}，不在本类另立一份）。 */
    public static List<Value> moduleValues() {
        final List<Value> out = new ArrayList<>(MODULE_TABLE.length);
        for (String[] row : MODULE_TABLE) {
            final String id = row[0];
            int lv = 0;
            try {
                lv = com.shanhai.machine.module.PrimordialModuleMachine.getModuleLevelById(id);
            } catch (Throwable t) {
                lv = 0;
            }
            final String label = row[1] + (lv > 0 ? "（等级 " + lv + "）" : "");
            out.add(new Value(id, label, id));
        }
        return out;
    }

    /** 本类这张模块表里第 {@code i} 项的 id（自检用）。 */
    public static String moduleTableId(int i) {
        return i >= 0 && i < MODULE_TABLE.length ? MODULE_TABLE[i][0] : "";
    }

    public static int moduleTableSize() {
        return MODULE_TABLE.length;
    }

    /**
     * <b>档位取值表</b>（类型 → 那个类型自己的那一套取值）。
     *
     * <p>返回<b>空表</b>的语义是明确的：<b>这个类型没有档位这一栏</b>（面板据此把那一行整个藏掉，
     * 而不是画一个空下拉）。
     *
     * @param server  只有"维度 / 生物群系"这种要枚举注册表的类型才用得到；可以为 null（那就给空表）
     * @param typeKey 条件类型注册名（{@code cleanroom} / {@code module_level} / …）
     */
    public static List<Value> valueTableOf(net.minecraft.server.MinecraftServer server, String typeKey) {
        if (typeKey == null || typeKey.isEmpty()) {
            return List.of();
        }
        switch (typeKey) {
            case TYPE_CLEANROOM -> {
                final List<Value> out = new ArrayList<>();
                for (String n : cleanroomNames()) {
                    out.add(new Value(n, cleanroomLabel(n), n));
                }
                return out;
            }
            case TYPE_MODULE_LEVEL -> {
                return moduleValues();
            }
            case "dimension" -> {
                return dimensionValues(server);
            }
            case "biome" -> {
                return biomeValues(server);
            }
            default -> {
                return List.of();
            }
        }
    }

    /** 这个类型有没有档位（= 取值表非空）。面板据此显隐那一行。 */
    public static boolean hasValueTable(net.minecraft.server.MinecraftServer server, String typeKey) {
        return !valueTableOf(server, typeKey).isEmpty();
    }

    /**
     * <b>"这个类型有没有档位这一栏"的静态判据</b>（不枚举注册表 ⇒ <b>服务端与客户端给出同一个答案</b>）。
     *
     * <p>为什么不直接用 {@link #hasValueTable}：面板的显隐要在<b>两侧</b>都算对，而客户端本地的
     * {@code server} 是 null（维度 / 生物群系那两张表在客户端枚举不出来）⇒ 用会枚举的那一版，
     * 客户端就会把"维度"的档位栏藏掉，而服务端明明有。
     * 这一版只按<b>类型名</b>判，两侧必然一致；真正的候选值仍然由服务端推过去。
     */
    public static boolean hasValueColumn(String typeKey) {
        if (typeKey == null) {
            return false;
        }
        return switch (typeKey) {
            case TYPE_CLEANROOM, TYPE_MODULE_LEVEL, "dimension", "biome" -> true;
            default -> false;
        };
    }

    /** 服务器上那套维度（取不到就给空表，绝不编）。 */
    private static List<Value> dimensionValues(net.minecraft.server.MinecraftServer server) {
        final List<Value> out = new ArrayList<>();
        if (server == null) {
            return out;
        }
        try {
            for (var k : server.levelKeys()) {
                final String id = k.location().toString();
                out.add(new Value(id, id, id));
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} conditions_dimension_enum_failed err={}", PREFIX, t.toString());
        }
        out.sort(java.util.Comparator.comparing(Value::key));
        return out;
    }

    /** 注册表里的生物群系（取不到就给空表，绝不编）。 */
    private static List<Value> biomeValues(net.minecraft.server.MinecraftServer server) {
        final List<Value> out = new ArrayList<>();
        if (server == null) {
            return out;
        }
        try {
            final var reg = server.registryAccess()
                    .registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
            for (var k : reg.keySet()) {
                final String id = k.toString();
                out.add(new Value(id, id, id));
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} conditions_biome_enum_failed err={}", PREFIX, t.toString());
        }
        out.sort(java.util.Comparator.comparing(Value::key));
        return out;
    }

    /**
     * 从一条条件 JSON 里读出它<b>当前选的那一项</b>的 key（= 档位下拉要显示的当前值）。
     * <p>读不出来返回空串（面板据此回落到"第一项"）。模块那一路会剥掉 {@code "Nx "} 前缀。
     */
    public static String valueKeyOf(String typeKey, JsonElement el) {
        if (typeKey == null) {
            return "";
        }
        if (TYPE_CLEANROOM.equals(typeKey)) {
            return cleanroomNameOf(el);
        }
        if (TYPE_MODULE_LEVEL.equals(typeKey)) {
            try {
                return com.shanhai.machine.module.ModuleLevelCondition.normalizeModuleId(moduleIdOf(el));
            } catch (Throwable t) {
                return moduleIdOf(el);
            }
        }
        if (el == null || !el.isJsonObject()) {
            return "";
        }
        final JsonObject o = el.getAsJsonObject();
        for (String k : new String[]{"dimension", "biome"}) {
            if (o.has(k) && o.get(k).isJsonPrimitive()) {
                return o.get(k).getAsString();
            }
        }
        return "";
    }

    /**
     * 某一项在界面上的中文名（查不到就<b>退回 key 本身</b>并打一行 WARN —— 不编一个名字出来）。
     */
    public static String valueLabelOf(net.minecraft.server.MinecraftServer server, String typeKey, String key) {
        if (key == null || key.isEmpty()) {
            return "(未选)";
        }
        for (Value v : valueTableOf(server, typeKey)) {
            if (v.key().equals(key)) {
                return v.label();
            }
        }
        return key;
    }

    /**
     * <b>按「类型 + 档位」造一条条件</b>。
     *
     * <p>这是面板编辑控件唯一的下游入口 ⇒ 用户在下拉里选什么，落盘的就是什么。
     * <ul>
     *   <li>超净间：{@code cleanroom = <档位名>}</li>
     *   <li>物质模块：{@code module_id = <模块 id>}，并且 {@code level} 一并写成<b>该模块自身等级</b>
     *       ——{@code level} 在 {@code ModuleLevelCondition} 里<b>只用于显示</b>
     *       （真门槛由 {@code requiredLevelForGate()} 现查等级表，见它的注释），
     *       写成自身等级可以让落盘的 JSON 自洽，不会再出现"数量 1 但门槛是 4"那种自相矛盾的记录。</li>
     *   <li>维度 / 生物群系：{@code DimensionCondition}/{@code BiomeCondition} 的公开构造器</li>
     *   <li>其余：造该类型的默认值，只翻 {@code reverse}</li>
     * </ul>
     * 认不出的类型返回 {@code null}（调用方按失败处理，不凑合）。
     */
    public static RecipeCondition makeValueCondition(net.minecraft.server.MinecraftServer server,
                                                      String typeKey, String key, boolean reverse) {
        if (typeKey == null || typeKey.isEmpty()) {
            return null;
        }
        try {
            if (TYPE_CLEANROOM.equals(typeKey)) {
                final String k = (key == null || key.isEmpty())
                        ? firstKey(valueTableOf(server, typeKey)) : key;
                return makeCleanroom(k, reverse);
            }
            if (TYPE_MODULE_LEVEL.equals(typeKey)) {
                String k = (key == null || key.isEmpty())
                        ? firstKey(valueTableOf(server, typeKey)) : key;
                k = com.shanhai.machine.module.ModuleLevelCondition.normalizeModuleId(k);
                final int lv = com.shanhai.machine.module.PrimordialModuleMachine.getModuleLevelById(k);
                return makeModuleLevel(k, lv > 0 ? lv : 1, reverse);
            }
            if ("dimension".equals(typeKey)) {
                final String k = (key == null || key.isEmpty())
                        ? firstKey(valueTableOf(server, typeKey)) : key;
                if (k.isEmpty()) {
                    return null;
                }
                final com.gregtechceu.gtceu.common.recipe.condition.DimensionCondition c =
                        new com.gregtechceu.gtceu.common.recipe.condition.DimensionCondition(
                                new net.minecraft.resources.ResourceLocation(k));
                c.setReverse(reverse);
                return c;
            }
            if ("biome".equals(typeKey)) {
                final String k = (key == null || key.isEmpty())
                        ? firstKey(valueTableOf(server, typeKey)) : key;
                if (k.isEmpty()) {
                    return null;
                }
                final com.gregtechceu.gtceu.common.recipe.condition.BiomeCondition c =
                        new com.gregtechceu.gtceu.common.recipe.condition.BiomeCondition(
                                new net.minecraft.resources.ResourceLocation(k));
                c.setReverse(reverse);
                return c;
            }
            final RecipeCondition d = defaultOf(typeKey);
            if (d != null) {
                d.setReverse(reverse);
            }
            return d;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} conditions_make_value_failed type={} key={} err={}",
                    PREFIX, typeKey, key, t.toString());
            return null;
        }
    }

    private static String firstKey(List<Value> vs) {
        return vs.isEmpty() ? "" : vs.get(0).key();
    }

    /** 全部超净间档位名（面板那个下拉的候选，来源 = GT 自己的 {@code getAllTypes()}，含别的 mod 注册的）。 */
    public static List<String> cleanroomNames() {        final List<String> out = new ArrayList<>();
        try {
            for (CleanroomType t : CleanroomType.getAllTypes()) {
                if (t != null && t.getName() != null) {
                    out.add(t.getName());
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} conditions_cleanroom_enum_failed err={}", PREFIX, t.toString());
        }
        out.sort(String::compareTo);
        return out;
    }

    /** 某条条件是不是"超净间"。 */
    public static boolean isCleanroom(RecipeCondition c) {
        return c instanceof com.gregtechceu.gtceu.common.recipe.condition.CleanroomCondition;
    }

    /** 某条条件的类型名是不是超净间（按 JSON 判，用于编辑中的那份）。 */
    public static boolean isCleanroomType(String typeKey) {
        return TYPE_CLEANROOM.equals(typeKey);
    }

    public static boolean isModuleLevelType(String typeKey) {
        return TYPE_MODULE_LEVEL.equals(typeKey);
    }

    /** 超净间档位名（不是超净间就返回空串）。 */
    public static String cleanroomNameOf(RecipeCondition c) {
        if (c instanceof com.gregtechceu.gtceu.common.recipe.condition.CleanroomCondition cc) {
            try {
                return cc.getCleanroom() == null ? "" : cc.getCleanroom().getName();
            } catch (Throwable t) {
                return "";
            }
        }
        return "";
    }

    /** 从 JSON 里读超净间档位名。 */
    public static String cleanroomNameOf(JsonElement el) {
        if (el != null && el.isJsonObject()) {
            final JsonObject o = el.getAsJsonObject();
            if (o.has("cleanroom") && o.get("cleanroom").isJsonPrimitive()) {
                return o.get("cleanroom").getAsString();
            }
        }
        return "";
    }

    /** 从 JSON 里读模块物品 id。 */
    public static String moduleIdOf(JsonElement el) {
        if (el != null && el.isJsonObject()) {
            final JsonObject o = el.getAsJsonObject();
            if (o.has("module_id") && o.get("module_id").isJsonPrimitive()) {
                return o.get("module_id").getAsString();
            }
        }
        return "";
    }

    /** 从 JSON 里读数量（{@code level} 键；缺省 0）。 */
    public static int levelOf(JsonElement el) {
        if (el != null && el.isJsonObject()) {
            final JsonObject o = el.getAsJsonObject();
            if (o.has("level") && o.get("level").isJsonPrimitive()) {
                try {
                    return o.get("level").getAsInt();
                } catch (Throwable ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    /** 取反（{@code reverse:true}）。 */
    public static boolean isReverse(JsonElement el) {
        return el != null && el.isJsonObject() && el.getAsJsonObject().has("reverse")
                && el.getAsJsonObject().get("reverse").isJsonPrimitive()
                && el.getAsJsonObject().get("reverse").getAsBoolean();
    }

    public static Form formOf(String typeKey) {
        if (isCleanroomType(typeKey)) {
            return Form.CLEANROOM;
        }
        if (isModuleLevelType(typeKey)) {
            return Form.MODULE_LEVEL;
        }
        return isKnownType(typeKey) ? Form.GENERIC : Form.UNKNOWN;
    }

    /** 超净间档位的中文（拿不到翻译就退回档位名本身，绝不编）。 */
    public static String cleanroomLabel(String name) {
        if (name == null || name.isEmpty()) {
            return "(未指定档位)";
        }
        try {
            final CleanroomType t = CleanroomType.getByName(name);
            if (t != null) {
                final String s = net.minecraft.network.chat.Component
                        .translatable(t.getTranslationKey()).getString();
                // 无头专服上会拿到未翻译的 lang key（含 '.'）⇒ 如实退回档位名
                if (s != null && !s.isEmpty() && s.indexOf('.') < 0) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
            // 退回档位名
        }
        return name;
    }

    /**
     * 一条条件的一行中文说明（面板列表行 + 日志判据共用）。
     *
     * <p>🔴 认不出的类型<b>如实写"未知条件"</b>，绝不假装它是个认识的东西。
     *
     * <h4>2026-10-05 第 5 轮：按用户原话改口径</h4>
     * <ul>
     *   <li>「取反」这个词他看不懂 ⇒ 改成大白话 <b>「要求不满足」</b>；</li>
     *   <li>「数量」那一栏他不要了 ⇒ 行里<b>不再出现 {@code （×N）}</b>，
     *       只写模块名 ＋ 它自身的等级（那才是真门槛）；</li>
     *   <li>界面上<b>不出现内部 id</b>（只有认不出的类型才不得不回落到类型名）。</li>
     * </ul>
     */
    public static String describe(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return "未知条件（不是对象）";
        }
        final String typeKey = typeKeyOf(el);
        final String rev = isReverse(el) ? "§c要求不满足§r " : "";
        if (typeKey.isEmpty()) {
            return rev + "未知条件（没有 type）";
        }
        if (!isKnownType(typeKey)) {
            return rev + "未知条件：" + typeKey + "（本版不认识 ⇒ 只读，不能编辑）";
        }
        switch (formOf(typeKey)) {
            case CLEANROOM -> {
                final String n = cleanroomNameOf(el);
                return rev + "超净间：" + cleanroomLabel(n);
            }
            case MODULE_LEVEL -> {
                final String id = valueKeyOf(typeKey, el);
                final String name = moduleLabel(id);
                return rev + "物质模块：" + name + " §8(门槛 = 该模块自身等级)";
            }
            default -> {
                final String t = typeLabel(typeKey);
                // 已知类型且有中文名 ⇒ 连那个内部名一起省掉；认不出中文名的才把类型名露出来
                return rev + t + (t.equals(typeKey) ? "" : " §8(" + typeKey + ") ") + shortJson(el);
            }
        }
    }

    /**
     * 物质模块的中文名（查本类的 17 项表；查不到就如实写"不在 17 个模块里"＋原 id，不编名字）。
     */
    public static String moduleLabel(String id) {
        final String k;
        try {
            k = com.shanhai.machine.module.ModuleLevelCondition.normalizeModuleId(id);
        } catch (Throwable t) {
            return "(未选模块)";
        }
        if (k == null || k.isEmpty()) {
            return "(未选模块)";
        }
        for (String[] row : MODULE_TABLE) {
            if (row[0].equals(k)) {
                return row[1];
            }
        }
        return "不在 17 个物质模块里：" + k;
    }

    /** 物品的中文名（取不到就退回 id；空 id 如实写"未指定"）。 */
    public static String itemLabel(String id) {
        if (id == null || id.isEmpty()) {
            return "(未指定模块)";
        }
        try {
            final net.minecraft.resources.ResourceLocation rl = new net.minecraft.resources.ResourceLocation(id);
            final net.minecraft.world.item.Item it = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
            if (it != null && it != net.minecraft.world.item.Items.AIR) {
                final String s = new net.minecraft.world.item.ItemStack(it).getHoverName().getString();
                if (s != null && !s.isEmpty() && s.indexOf('.') < 0) {
                    return s + " §8(" + id + ")";
                }
                return id;
            }
        } catch (Throwable ignored) {
            // 退回 id
        }
        return id;
    }

    /** 一段压缩过的 JSON（generic 条件的列表行用它，避免长文本撑破行宽）。 */
    private static String shortJson(JsonElement el) {
        String s;
        try {
            s = canonical(el);
        } catch (Throwable t) {
            s = String.valueOf(el);
        }
        return s.length() <= 60 ? s : s.substring(0, 57) + "...";
    }

    // ================================================================== 数组小工具

    /** 往数组里加一条（返回同一个数组，便于链式用）。 */
    public static JsonArray add(JsonArray arr, RecipeCondition c) {
        final JsonElement el = encodeOne(c);
        if (arr != null && el != null) {
            arr.add(el);
        }
        return arr;
    }

    /** 删掉第 {@code index} 条；越界不动。 */
    public static JsonArray removeAt(JsonArray arr, int index) {
        if (arr != null && index >= 0 && index < arr.size()) {
            arr.remove(index);
        }
        return arr;
    }

    public static JsonArray copyOf(JsonArray arr) {
        return arr == null ? new JsonArray() : arr.deepCopy();
    }

    /** 一个只读视图，供日志打印（不参与判定）。 */
    public static String summary(JsonArray arr) {
        if (arr == null) {
            return "(null)";
        }
        final StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.size(); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(i).append(':').append(typeKeyOf(arr.get(i)));
        }
        return sb.append(']').toString();
    }

    // ================================================================== 自检（纯内存，无 server）

    /**
     * <b>本组的机器判据</b>（照 {@code IO_CHANCE_SELFCHECK} 的形态：正对照 ＋ 负对照 ＋ 逐字断言）。
     *
     * <pre>
     *   C1 正对照：干净房间条件 encode ⇒ 数组 1 条、type=cleanroom、cleanroom 档位逐字保留
     *   C2 往返  ：encode ⇒ decode ⇒ 再 encode，两次 JSON【逐字相同】
     *   C3 正对照：物质模块条件 encode ⇒ type=module_level 且 module_id/level 都在
     *   C4 负对照：KubeJS 那个组件写出来的形状 {"type":k,"data":{…}} ⇒ decode 必须【拒收】
     *              （它同时证明"平铺形状是必须的"，以及"校验器真的会拒"）
     *   C5 负对照：未知类型（definitely_not_a_condition_zzz）⇒ decode 必须 ok=false，且 list==null
     *   C6 负对照：结构坏的输入（不是数组 / 元素不是对象 / 缺 type / type 不是字符串）
     *              四种都必须 ok=false（否则"fail-closed"是句空话）
     *   C7 口径  ：空数组 decode ⇒ ok=true 且 0 条（"清空条件"是合法输入，不是错误）
     *   C8 规范性：同一个对象两种键序 ⇒ sameAs=true（证明"改没改"的判据不依赖键序）
     *   C9 负对照：C8 的两份里改掉一个值 ⇒ sameAs=false（证明 C8 不是恒真）
     * </pre>
     *
     * <p>⚠️ 2026-10-05：本自检第一版有<b>两条自己的假设是错的</b>，是它自己报出来的
     * （C2 把单个条件对象喂给"要数组"的解码口；C4 原来断言 {@code serialize()} 会丢 module_id，
     * 而 {@code ModuleLevelCondition} 其实<b>重写过</b> {@code serialize/deserialize}）
     * ⇒ 两条都已按实测更正。<b>这正是自检要有负对照的原因</b>：C4 原版是恒真的假绿。
     */
    public static boolean selfcheck() {
        int pass = 0;
        int total = 0;
        final StringBuilder bad = new StringBuilder();

        // ---- C1 超净间正对照 ----
        final RecipeCondition cr = makeCleanroom("law_cleanroom", false);
        final JsonElement crJson = encodeOne(cr);
        total++;
        if (crJson != null && crJson.isJsonObject()
                && TYPE_CLEANROOM.equals(typeKeyOf(crJson))
                && "law_cleanroom".equals(cleanroomNameOf(crJson))) {
            pass++;
        } else {
            bad.append(" C1(").append(crJson).append(')');
        }

        // ---- C2 往返逐字相同 ----
        final JsonArray expect2 = new JsonArray();
        expect2.add(crJson);
        final Decoded d2 = decode(expect2);
        final JsonArray re2 = d2.ok() ? encode(d2.list()) : null;
        total++;
        if (d2.ok() && re2 != null && re2.size() == 1
                && canonical(re2).equals(canonical(expect2))) {
            pass++;
        } else {
            bad.append(" C2(ok=").append(d2.ok()).append(" err=").append(d2.error()).append(')');
        }

        // ---- C3 物质模块：字段必须真的在 ----
        final RecipeCondition ml = makeModuleLevel("shanhai:introductory_material_module", 3, false);
        final JsonElement mlJson = encodeOne(ml);
        total++;
        if (mlJson != null && mlJson.isJsonObject()
                && TYPE_MODULE_LEVEL.equals(typeKeyOf(mlJson))
                && "shanhai:introductory_material_module".equals(moduleIdOf(mlJson))
                && levelOf(mlJson) == 3) {
            pass++;
        } else {
            bad.append(" C3(").append(mlJson).append(')');
        }

        // ---- C4 负对照：KubeJS component 写出来的形状必须被拒 ----
        //    ⚠️ 本条的第一版断言的是"serialize() 会丢 module_id"，而实测是【不会】
        //      （ModuleLevelCondition 重写了 serialize/deserialize，两种形状都认）
        //      ⇒ 那条负对照是恒真的假绿，2026-10-05 自检自己报出来后换成现在这条。
        //    现在断言的是一条【真的事实】：GT 的加载器要平铺形状，data 包裹的那种它解不出来。
        final JsonObject kjsShape = new JsonObject();
        kjsShape.addProperty("type", TYPE_CLEANROOM);
        final JsonObject data = new JsonObject();
        data.addProperty("cleanroom", "law_cleanroom");
        kjsShape.add("data", data);
        final Decoded d4 = decode(arr(kjsShape));
        final boolean flatOk = decode(arr(crJson)).ok();
        total++;
        if (!d4.ok() && d4.list() == null && flatOk) {
            pass++;
        } else {
            bad.append(" C4(data包裹的形状 ok=").append(d4.ok())
                    .append(" flat形状 ok=").append(flatOk)
                    .append(") —— 期望 data 包裹【被拒】且平铺【通过】");
        }

        // ---- C5 未知类型 fail-closed ----
        final JsonObject unknown = new JsonObject();
        unknown.addProperty("type", "definitely_not_a_condition_zzz");
        total++;
        final Decoded d5 = decode(arr(unknown));
        if (!d5.ok() && d5.list() == null) {
            pass++;
        } else {
            bad.append(" C5(ok=").append(d5.ok()).append(" list=").append(d5.list()).append(')');
        }

        // ---- C6 结构坏的四种输入 ----
        final JsonObject noType = new JsonObject();
        noType.addProperty("cleanroom", "cleanroom");
        final JsonArray nested = new JsonArray();
        nested.add(new JsonArray());
        final JsonObject badType = new JsonObject();
        badType.add("type", new JsonArray());
        final JsonElement[] broken = {
                new JsonPrimitive("cleanroom"),      // 不是数组（是一个字符串）
                nested,                              // 元素不是对象
                arr(noType),                         // 缺 type
                arr(badType),                        // type 不是字符串
        };
        final String[] names = {"not_array", "elem_not_object", "no_type", "type_not_string"};
        total++;
        final StringBuilder c6 = new StringBuilder();
        boolean c6ok = true;
        for (int i = 0; i < broken.length; i++) {
            final Decoded d = decode(broken[i]);
            c6.append(names[i]).append('=').append(d.ok()).append(' ');
            if (d.ok() || d.list() != null) {
                c6ok = false;
            }
        }
        if (c6ok) {
            pass++;
        } else {
            bad.append(" C6(").append(c6).append(')');
        }

        // ---- C7 空数组是合法输入 ----
        total++;
        final Decoded d7 = decode(new JsonArray());
        if (d7.ok() && d7.list() != null && d7.list().isEmpty()) {
            pass++;
        } else {
            bad.append(" C7(ok=").append(d7.ok()).append(')');
        }

        // ---- C8 / C9 规范化的判据 ----
        final JsonObject a1 = new JsonObject();
        a1.addProperty("type", TYPE_CLEANROOM);
        a1.addProperty("cleanroom", "cleanroom");
        final JsonObject a2 = new JsonObject();
        a2.addProperty("cleanroom", "cleanroom");
        a2.addProperty("type", TYPE_CLEANROOM);
        total++;
        if (sameAs(arr(a1), arr(a2))) {
            pass++;
        } else {
            bad.append(" C8(键序不同被判成不同 ⇒ '改没改'的判据会误报)");
        }
        final JsonObject a3 = new JsonObject();
        a3.addProperty("cleanroom", "law_cleanroom");
        a3.addProperty("type", TYPE_CLEANROOM);
        total++;
        if (!sameAs(arr(a1), arr(a3))) {
            pass++;
        } else {
            bad.append(" C9(值改了却判成相同 ⇒ C8 是恒真的假绿)");
        }

        // ---- 🆕 第 5 轮：档位取值表（这是"档位随类型变动"那条的机器判据） ----
        //   C10 物质模块档位表必须是 17 项，且第 i 项的等级（现问 PrimordialModuleMachine）== i+1
        //       ⇒ 只看项数不够：顺序错了同样"17 项"，所以逐项核等级。
        total++;
        final List<Value> mods = moduleValues();
        final StringBuilder c10 = new StringBuilder();
        boolean c10ok = mods.size() == 17;
        for (int i = 0; i < mods.size(); i++) {
            final int lv = com.shanhai.machine.module.PrimordialModuleMachine
                    .getModuleLevelById(mods.get(i).key());
            if (lv != i + 1) {
                c10ok = false;
                c10.append(" #").append(i + 1).append('=').append(mods.get(i).key()).append("(lv=").append(lv).append(')');
            }
        }
        if (c10ok) {
            pass++;
        } else {
            bad.append(" C10(size=").append(mods.size()).append(c10).append(')');
        }

        //   C11 超净间档位非空；且它【不是】那 17 个模块（负对照：两个类型的档位表必须不同）
        total++;
        final List<Value> crs = valueTableOf(null, TYPE_CLEANROOM);
        final boolean c11ok = !crs.isEmpty()
                && crs.stream().noneMatch(v -> v.key().startsWith("shanhai:"));
        if (c11ok) {
            pass++;
        } else {
            bad.append(" C11(cleanroom_n=").append(crs.size()).append(")");
        }

        //   C12 负对照：没有取值的类型（gravity —— "要/不要"那种）档位表必须为空
        //       ⇒ 面板据此不画档位那一栏。没有这条，"所有类型都给档位"也会绿。
        total++;
        if (valueTableOf(null, "gravity").isEmpty() && !hasValueTable(null, "gravity")) {
            pass++;
        } else {
            bad.append(" C12(gravity 竟然有档位表 ⇒ 面板会画出不该有的下拉)");
        }

        //   C13 按"类型 + 档位"造出来的条件：模块 id 逐字回到档位 key，且 level == 该模块自身等级
        total++;
        final String probeKey = moduleTableId(10);            // 第 11 项 = 巅峰物质模块
        final RecipeCondition made = makeValueCondition(null, TYPE_MODULE_LEVEL, probeKey, false);
        final JsonElement madeJson = made == null ? null : encodeOne(made);
        final int wantLv = com.shanhai.machine.module.PrimordialModuleMachine.getModuleLevelById(probeKey);
        if (madeJson != null
                && probeKey.equals(valueKeyOf(TYPE_MODULE_LEVEL, madeJson))
                && levelOf(madeJson) == wantLv) {
            pass++;
        } else {
            bad.append(" C13(key=").append(probeKey).append(" json=").append(madeJson)
                    .append(" want_lv=").append(wantLv).append(')');
        }

        //   C14 界面语言：行里不许再出现「取反」「×N」（用户明确说看不懂 / 不要）
        total++;
        final String lineCr = describe(crJson);
        final String lineMl = describe(madeJson);
        if (lineCr.indexOf("取反") < 0 && lineMl.indexOf("取反") < 0
                && lineCr.indexOf("×") < 0 && lineMl.indexOf("×") < 0) {
            pass++;
        } else {
            bad.append(" C14(超净间行=").append(lineCr).append(" 模块行=").append(lineMl).append(')');
        }

        final boolean ok = pass == total;
        ShanhaiMod.LOGGER.info("{} CONDITIONS_SELFCHECK {}/{} PASS={}（判据 = 编解码走 GT 自己的 "
                        + "RecipeCondition.CODEC；C4/C5/C6/C12/C14 = 负对照；"
                        + "C10–C13 = 第 5 轮「档位随类型变动」那一条）registry_types={} cleanroom_types={} "
                        + "module_values={} value_tables={}",
                PREFIX, pass, total, ok, knownTypeKeys().size(), cleanroomNames().size(),
                moduleValues().size(), valueTableSummary(null));
        if (!ok) {
            ShanhaiMod.LOGGER.error("{} CONDITIONS_SELFCHECK 未过：{}", PREFIX, bad);
        }
        return ok;
    }

    private static JsonArray arr(JsonElement e) {
        final JsonArray a = new JsonArray();
        a.add(e);
        return a;
    }

    /** 给面板/日志用的一张"类型 → 表单形态"表（只读快照）。 */
    public static Map<String, String> formTable() {
        final Map<String, String> out = new LinkedHashMap<>();
        for (String k : knownTypeKeys()) {
            out.put(k, formOf(k).name());
        }
        return out;
    }

    /**
     * <b>「类型 → 档位项数 → 前几项的名字」</b>那张表（用户点单要他看的读数）。
     *
     * <p>形如 {@code cleanroom=3[超净间·低档/超净间·中档/超净间·高档] | module_level=17[入门物质模块（等级 1）/…]
     * | dimension=39[...] | (无档位 types=…) }。<b>没有档位栏的类型也会被点名列出来</b>
     * —— 否则"没列出来"与"我漏打了"在日志上长得一样。
     */
    public static String valueTableSummary(net.minecraft.server.MinecraftServer server) {
        final StringBuilder sb = new StringBuilder();
        final StringBuilder none = new StringBuilder();
        for (String k : knownTypeKeys()) {
            final List<Value> vs = valueTableOf(server, k);
            if (vs.isEmpty()) {
                if (none.length() > 0) {
                    none.append(',');
                }
                none.append(k);
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(k).append('=').append(vs.size()).append('[');
            for (int i = 0; i < vs.size() && i < 4; i++) {
                if (i > 0) {
                    sb.append('/');
                }
                sb.append(vs.get(i).label());
            }
            if (vs.size() > 4) {
                sb.append("…");
            }
            sb.append(']');
        }
        sb.append(" | (无档位栏的类型=").append(none).append(')');
        return sb.toString();
    }
}
