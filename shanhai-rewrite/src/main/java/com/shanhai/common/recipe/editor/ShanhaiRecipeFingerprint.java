package com.shanhai.common.recipe.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeSerializer;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.shanhai.ShanhaiMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * {@code base_fp} 的生产者 —— <b>逐字复刻覆盖层脚本的口径</b>。
 *
 * <h2>1. 为什么必须是"复刻"而不是"自定义"</h2>
 * {@code base_fp} 是<b>契约</b>：覆盖层脚本（{@code shanhai_recipe_overrides.js}，已冻结、不许改）
 * 在下一局开机时把它的值与"它自己对同一个配方算出来的值"做<b>字符串全等比较</b>，
 * 不等就打 {@code STALE} 并且<b>不套用</b>。⇒ 生产者与消费者必须产出<b>同一个字符串</b>。
 *
 * <h2>2. 算法原文（抄自脚本 104-138 行，一字不改）</h2>
 * <pre>
 *   content(recipe):
 *       j = recipe.json                       // KubeJS 的 RecipeJS.json（public 字段）
 *       若 j 非空:  s = JsonIO.toString(j); 若 s 非空 ⇒ { src:'json', s }
 *       否则:       ⇒ { src:'fallback-tostring', s: String(recipe) }
 *       再否则:     ⇒ { src:'none', s: '' }
 *   fingerprint(recipe):
 *       s = content(recipe).s
 *       若 s.length &gt; 400:  s = s.substring(0,220) + "~~" + s.substring(s.length-180)
 *       return "v2." + src + ":" + s
 * </pre>
 *
 * <h2>3. 🔴 三条关键事实（都不是猜的）</h2>
 * <ol>
 *   <li><b>{@code JsonIO.toString} 的输出格式</b>：KubeJS 的
 *       {@code dev.latvian.mods.kubejs.util.JsonIO#toString(JsonElement)} 是 public static 方法
 *       （javap 实证）。本类<b>直接反射调它本人</b>，不自己拿 Gson 去拼 —— 拼不出来的风险
 *       全在"格式恰好一样"上，而调它本人是<b>构造上必然一样</b>。</li>
 *   <li><b>{@code recipe.json} 是配方【加载时】的 JSON，不随 {@code recipe.set(...)} 变</b>。
 *       实测证据：{@code temp/overlay-rig/matrix/20261004-221113/C_applied.evidence.txt} 第 12 行
 *       —— 同一条配方 {@code dur=20}（已被覆盖层改过）而 {@code fp} 里仍写着
 *       {@code "duration":300}。⇒ 这个指纹测的是"<b>配方源声明长什么样</b>"，正是覆盖层
 *       下一局开机时看到的那一份。</li>
 *   <li><b>从哪里拿到那个 {@code JsonObject}</b>：KubeJS 的
 *       {@code dev.latvian.mods.kubejs.recipe.RecipesEventJS} 有
 *       {@code public static RecipesEventJS instance} 与
 *       {@code public final Map&lt;ResourceLocation, RecipeJS&gt; takenIds / originalRecipes}
 *       （javap 实证）。拿到 {@code RecipeJS} 之后读它的 public 字段
 *       {@code com.google.gson.JsonObject json} 即可。</li>
 * </ol>
 *
 * <h2>4. 降级是【响的】，不是静默的</h2>
 * KubeJS 不在 / {@code RecipesEventJS.instance} 已被清空 / 该 id 不在 KubeJS 的账上 ——
 * 三种情况都返回 {@link #UNKNOWN} 并打一条 ERROR，<b>绝不返回一个"看起来像指纹"的假字符串</b>。
 * 调用方（编辑器保存路径）见到 {@link #UNKNOWN} 时<b>拒绝落盘</b>（见
 * {@link ShanhaiRecipeEditorOps}），因为写一条永远对不上的指纹等于给覆盖层埋一条永久 STALE。
 */
public final class ShanhaiRecipeFingerprint {

    public static final String PREFIX = "[SHANHAI-EDIT] editor";

    // ================================================================== 指纹版本与规范化
    //
    // 🔴 2026-10-05（用户实测 B4）：「重启之后 kjs 报错消失，但是时长重置了」
    //    —— 同一 JVM 内编辑运行期生效（所以他"重进"时时长还在），重启后只剩覆盖层重放
    //    ⇒ 指纹对不上 ⇒ 判 STALE、不套用 ⇒ 时长回退。
    //    根因：`v2` 的指纹 = JsonIO.toString(recipe.json) 的【原样】字符串，
    //    而那份 JSON 的对象键顺序【跨开机不稳】。实测样本（多输入能力配方
    //    gtceu:assembler/cover_steel_wire_gt_octal_rubber，同时吃 item 与 fluid 两种输入能力）：
    //      `"inputs":{"fluid":[…],"item":[…]}`  与  `"inputs":{"item":[…],"fluid":[…]}` 都出现过。
    //    ⇒ 同一个"没被改过"的配方，两次开机算出两个不同的指纹。
    //
    //    修法：取指纹前先把 JSON【规范化】（递归按字典序排对象键）再序列化，再走原来的截断规则。
    //    ⚠️ 密钥（对象键）排序，不排数组：数组是有序语义（物品顺序、输出顺序），排它会抹掉真实编辑。

    /** 当前指纹版本前缀。<b>改算法必须换它</b>（否则老条目会被误判成 STALE 而不是"要重存"）。 */
    public static final String FP_PREFIX_V3 = "v3.sorted:";

    /** 历史前缀（只用于识别 + 明确提示"需重新保存一次"，不再产出）。 */
    public static final String FP_PREFIX_V2 = "v2.";

    /** 算不出来时的哨兵值。绝不与任何真实指纹相等（真实指纹一定以 {@link #FP_PREFIX_V3} 开头）。 */
    public static final String UNKNOWN = "<unknown-fp>";

    /** 截断阈值/窗口，逐字抄脚本。 */
    private static final int FP_MAX = 400;
    private static final int FP_HEAD = 220;
    private static final int FP_TAIL = 180;

    /** 反射缓存（判空都走 try/catch，任何一步失败都降级）。 */
    private static Boolean kubeJsResolvable = null;
    private static String lastDiagnosis = "(never probed)";

    // ============================================================ 🆕 第 12 刀：相位口径
    //
    // 自检里那一行 `case=fp_reflection_window_usable ok=false` 一直挂着，读起来像"坏了"。
    // 它真正在说的是：**自检那一拍**（服务器起来之后）反射拿 `RecipesEventJS.instance` 拿到的是 null。
    // 「是永远拿不到，还是某个相位拿不到」这件事以前没人测过 —— 第 12 刀补上：
    // 在 KubeJS 自己的窗口【里面】（`KubeJSPlugin.injectRuntimeRecipes`，也就是本类缓存被填的那一拍）
    // 再探一次，把结果留档，供自检对照。
    //
    // 字节码取证（本机 javap，`temp\evidence-cut12\RecipesEventJS.javap.txt` 与
    // `kubejs-classes\dev\latvian\mods\kubejs\core\mixin\common\RecipeManagerMixin.class`）：
    //   · `ServerScriptManager` 在脚本重载时装上实例：`putstatic RecipesEventJS.instance = new RecipesEventJS()`；
    //   · `RecipesEventJS.post()` 【内部】调 `KubeJSPlugin.injectRuntimeRecipes(...)`（我们的捕获点就在这）；
    //   · `RecipeManagerMixin.customRecipesHead` 在 `post()` 【返回之后】立刻 `aconst_null; putstatic instance`。
    // ⇒ 窗口 = 从脚本重载到配方事件结束；出了窗口**永远是 null**。

    /** 窗口内那一拍的读数（null = 还没测过）。 */
    private static volatile Boolean windowProbePresent = null;
    private static volatile String windowProbePhase = "(never)";
    private static volatile String windowProbeAt = "(never)";

    /** 由捕获器在 KubeJS 的窗口里调用：把"这个相位到底拿不拿得到"留档。 */
    public static void noteWindowProbe(String phase, boolean present) {
        windowProbePhase = phase == null ? "(unknown)" : phase;
        windowProbePresent = present;
        windowProbeAt = java.time.LocalTime.now().withNano(0).toString();
        ShanhaiMod.LOGGER.info("{} fp_window_probe phase={} RecipesEventJS_instance_present={} at={} "
                        + "(这一拍跑在 KubeJS 的 ServerEvents.recipes 窗口【里面】；"
                        + "自检那一拍在窗口【外面】⇒ 两处读数本来就不该相同)",
                PREFIX, windowProbePhase, present, windowProbeAt);
    }

    /** 窗口内那一拍拿到了吗（null = 没测到 ⇒ 不许当"已知限制"糊过去）。 */
    public static Boolean windowProbePresent() {
        return windowProbePresent;
    }

    /** 窗口探针的一行读数（自检打印用）。 */
    public static String windowProbeLine() {
        return "window_phase=" + windowProbePhase + " window_present="
                + (windowProbePresent == null ? "(未测到)" : windowProbePresent.toString())
                + " window_at=" + windowProbeAt;
    }

    /**
     * 🆕 捕获式缓存：{@code id → base_fp}，由 {@link ShanhaiRecipeFingerprintCapture} 在
     * KubeJS 的 {@code injectRuntimeRecipes} 窗口里一次性填好。
     *
     * <p>这是<b>主路</b>：窗口一关（{@code RecipesEventJS.instance} 变 null）反射路就没了，
     * 但缓存还在。反射路保留为后备（万一某个环境里窗口还开着）。
     */
    private static volatile Map<ResourceLocation, String> CACHE = null;
    private static volatile long cacheCapturedAtMs = -1;

    private ShanhaiRecipeFingerprint() {}

    /** 由捕获器调用：装上缓存并打一行可判读数。 */
    public static void installCache(Map<ResourceLocation, String> cache) {
        CACHE = cache;
        cacheCapturedAtMs = System.currentTimeMillis();
        ShanhaiMod.LOGGER.info("{} fp_cache_installed entries={} at_ms={}", PREFIX,
                cache == null ? 0 : cache.size(), cacheCapturedAtMs);
    }

    /** 缓存条数（-1 = 没装）。 */
    public static int cacheSize() {
        final Map<ResourceLocation, String> c = CACHE;
        return c == null ? -1 : c.size();
    }

    /** 按覆盖层口径算 {@code base_fp}。 */
    public static String forRecipeId(ResourceLocation id) {
        // ① 捕获式缓存（主路，KubeJS 窗口里算好的）
        final Map<ResourceLocation, String> c = CACHE;
        if (c != null) {
            final String hit = c.get(id);
            if (hit != null) {
                return hit;
            }
            lastDiagnosis = "cache installed (" + c.size() + " entries) but no entry for id=" + id;
        }
        // ② 反射路（窗口还开着时才可能成功）
        final JsonObject json = kubeJsRecipeJson(id);
        if (json != null) {
            final String fp = fingerprint(json);
            if (fp != null) {
                return fp;
            }
            lastDiagnosis = "RecipeJS.json present but canonical fingerprint unavailable";
        } else if (c == null) {
            lastDiagnosis = "no cache installed and no KubeJS RecipeJS found for id=" + id;
        }
        // 脚本的第二分支是 String(recipe)（Rhino 的 toString），它在 Java 侧不可复现
        // （RecipeJS.toString() 走 KubeJS 的 recipeToString，两者不是同一个东西）。
        // 与其产出一个"看着像、其实对不上"的串，不如如实标 UNKNOWN —— 本工程红线：宁可缺，不可假。
        ShanhaiMod.LOGGER.error("{} fp_unavailable id={} reason={}", PREFIX, id, lastDiagnosis);
        return UNKNOWN;
    }

    /** 上一次失败的原因（供自检打印，避免"没出数"与"出错了"分不开）。 */
    public static String lastDiagnosis() {
        return lastDiagnosis;
    }

    /** KubeJS 这条链能不能用（自检第一拍打一次，让"没生效"与"没跑"可区分）。 */
    public static boolean kubeJsReachable() {
        try {
            final Class<?> cls = Class.forName("dev.latvian.mods.kubejs.recipe.RecipesEventJS");
            final Object inst = cls.getField("instance").get(null);
            final boolean ok = inst != null;
            kubeJsResolvable = ok;
            lastDiagnosis = ok ? "ok" : "RecipesEventJS.instance == null";
            return ok;
        } catch (Throwable t) {
            kubeJsResolvable = Boolean.FALSE;
            lastDiagnosis = "kubejs unreachable: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        }
    }

    // --------------------------------------------------------------- 取 JsonObject

    /** 从 KubeJS 的账上取该 id 的 {@code RecipeJS.json}；取不到返回 null。 */
    private static JsonObject kubeJsRecipeJson(ResourceLocation id) {
        try {
            final Class<?> evCls = Class.forName("dev.latvian.mods.kubejs.recipe.RecipesEventJS");
            final Object instance = evCls.getField("instance").get(null);
            if (instance == null) {
                lastDiagnosis = "RecipesEventJS.instance == null";
                return null;
            }

            Object recipeJS = mapGet(evCls, instance, "takenIds", id);
            if (recipeJS == null) {
                recipeJS = mapGet(evCls, instance, "originalRecipes", id);
            }
            if (recipeJS == null) {
                recipeJS = scanCollection(evCls, instance, "addedRecipes", id);
            }
            if (recipeJS == null) {
                return null;
            }

            final Class<?> rjs = Class.forName("dev.latvian.mods.kubejs.recipe.RecipeJS");
            final Field jf = rjs.getField("json");
            final Object o = jf.get(recipeJS);
            if (o instanceof JsonObject jo) {
                return jo;
            }
            if (o instanceof JsonElement je && je.isJsonObject()) {
                return je.getAsJsonObject();
            }
            lastDiagnosis = "RecipeJS.json is not a JsonObject (got " + (o == null ? "null" : o.getClass().getName()) + ")";
            return null;
        } catch (Throwable t) {
            lastDiagnosis = "kubejs lookup threw " + t.getClass().getSimpleName() + ": " + t.getMessage();
            return null;
        }
    }

    private static Object mapGet(Class<?> evCls, Object instance, String fieldName, ResourceLocation id) {
        try {
            final Object m = evCls.getField(fieldName).get(instance);
            if (m instanceof Map<?, ?> map) {
                return map.get(id);
            }
        } catch (Throwable ignored) {
            // 该字段不存在/不可读 ⇒ 走下一路，不是错误
        }
        return null;
    }

    /** 最后一招：遍历 {@code addedRecipes} 比 id（{@code kjs$getOrCreateId()}）。 */
    private static Object scanCollection(Class<?> evCls, Object instance, String fieldName, ResourceLocation id) {
        try {
            final Object c = evCls.getField(fieldName).get(instance);
            if (!(c instanceof Collection<?> coll)) {
                return null;
            }
            final Method getId = Class.forName("dev.latvian.mods.kubejs.recipe.RecipeJS")
                    .getMethod("kjs$getOrCreateId");
            for (Object r : coll) {
                try {
                    if (id.equals(getId.invoke(r))) {
                        return r;
                    }
                } catch (Throwable ignored) {
                    // 单条读不出来就跳过，不影响其它条
                }
            }
        } catch (Throwable ignored) {
            // 字段不可读 ⇒ 放弃这一路
        }
        return null;
    }

    // --------------------------------------------------------------- 序列化 + 截断

    /** 直接调 KubeJS 的 {@code JsonIO.toString(JsonElement)}（反射），保证与脚本逐字节同源。 */
    public static String jsonIoToString(JsonElement el) {
        try {
            final Class<?> jsonIO = Class.forName("dev.latvian.mods.kubejs.util.JsonIO");
            final Method m = jsonIO.getMethod("toString", JsonElement.class);
            return (String) m.invoke(null, el);
        } catch (Throwable t) {
            lastDiagnosis = "JsonIO.toString threw " + t.getClass().getSimpleName() + ": " + t.getMessage();
            return null;
        }
    }

    /** {@code "v3.sorted:" + src + ":" + 截断(s)} —— 与覆盖层脚本逐字同口径。 */
    public static String compose(String src, String s) {
        return FP_PREFIX_V3 + src + ":" + truncate(s);
    }

    /**
     * <b>核心修复</b>：JSON 规范化 —— 递归把<b>对象</b>的键按字典序排好（数组保持原序）。
     *
     * <p>排序键的口径 = {@link String#compareTo}（UTF-16 码元序），两侧同一份实现
     * ⇒ 与"哪个语言写的"无关。见类顶部的版本说明。
     */
    public static JsonElement canonicalize(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return JsonNull.INSTANCE;
        }
        if (el.isJsonObject()) {
            final JsonObject in = el.getAsJsonObject();
            final List<String> keys = new ArrayList<>(in.size());
            for (Map.Entry<String, JsonElement> e : in.entrySet()) {
                keys.add(e.getKey());
            }
            keys.sort(null);                       // 字典序（与 JS 的默认 sort 同为码元序）
            final JsonObject out = new JsonObject();
            for (String k : keys) {
                out.add(k, canonicalize(in.get(k)));
            }
            return out;
        }
        if (el.isJsonArray()) {
            final JsonArray in = el.getAsJsonArray();
            final JsonArray out = new JsonArray(in.size());
            for (JsonElement e : in) {
                out.add(canonicalize(e));
            }
            return out;
        }
        return el;                                  // 基本类型原样
    }

    /**
     * 从一份 JSON 算 {@code base_fp}：<b>规范化 → JsonIO.toString → 截断 → 加前缀</b>。
     *
     * <p>🔴 这是<b>唯一</b>的产出口径。KubeJS 侧走的是同一个方法的绑定
     * （{@code ShanhaiKubeJSPlugin.registerBindings} 里的 {@code ShanhaiFingerprint}），
     * ⇒ 两侧不是"两份实现碰巧一样"，而是<b>同一段代码</b>。
     *
     * @return 指纹；{@code null} = 这条算不出来（调用方必须<b>拒绝落盘</b>，绝不写一个凑合的串）
     */
    public static String fingerprint(JsonElement el) {
        if (el == null) {
            lastDiagnosis = "fingerprint(null)";
            return null;
        }
        final String s = jsonIoToString(canonicalize(el));
        if (s == null || s.isEmpty()) {
            lastDiagnosis = "JsonIO.toString empty after canonicalize";
            return null;
        }
        return compose("json", s);
    }

    /**
     * 旧版（{@code v2}）指纹：<b>没有规范化</b>的原始串。
     * <p>它只留作<b>迁移判定</b>与诊断（"这条老条目是哪个版本写的"），<b>不再用于产出</b>。
     */
    public static String fingerprintV2(JsonElement el) {
        if (el == null) {
            return null;
        }
        final String s = jsonIoToString(el);
        if (s == null || s.isEmpty()) {
            return null;
        }
        return FP_PREFIX_V2 + "json:" + truncate(s);
    }

    /** 一个指纹串的版本标签（迁移提示用；认不出的返回 {@code "?"}）。 */
    public static String versionOf(String fp) {
        if (fp == null || fp.isEmpty()) {
            return "(empty)";
        }
        if (fp.startsWith(FP_PREFIX_V3)) {
            return "v3.sorted";
        }
        if (fp.startsWith(FP_PREFIX_V2)) {
            return "v2";
        }
        return "?";
    }

    /** 当前版本前缀（给 KubeJS 侧读，免得脚本里硬写常量）。 */
    public static String currentVersionPrefix() {
        return FP_PREFIX_V3;
    }

    /** 这个串是不是<b>当前版本</b>的指纹（不是 ⇒ 覆盖层要明确提示"重新保存一次"）。 */
    public static boolean isCurrentVersion(String fp) {
        return fp != null && fp.startsWith(FP_PREFIX_V3);
    }

    /** {@code if (s.length > 400) s = s.substring(0,220) + "~~" + s.substring(s.length-180)}。 */
    public static String truncate(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() > FP_MAX) {
            return s.substring(0, FP_HEAD) + "~~" + s.substring(s.length() - FP_TAIL);
        }
        return s;
    }

    // --------------------------------------------------------------- 旁证候选（只用于诊断）

    /** 一段<b>原始 JSON 文本</b> → 规范化后的指纹（诊断/自检用；解析失败返回 {@code null}）。 */
    public static String fingerprintOfRawJson(String raw) {
        if (raw == null || raw.isEmpty() || raw.startsWith("<")) {
            return null;
        }
        try {
            return fingerprint(com.google.gson.JsonParser.parseString(raw));
        } catch (Throwable t) {
            lastDiagnosis = "raw json parse failed: " + t.getClass().getSimpleName();
            return null;
        }
    }

    /**
     * <b>旁证候选</b>：把活的 {@code GTRecipe} 用 GT 自己的 codec（{@code GTRecipeSerializer.CODEC}）
     * 编成 JSON，再按同一套（规范化 + 截断）规则拼串。
     *
     * <p>它<b>不是</b>交付口径 —— 之所以留着，是因为 {@link #forRecipeId} 若拿不到 KubeJS 的
     * {@code JsonObject}，我需要一条"能自证是哪种失败"的读数：如果这个候选<b>恰好等于</b>
     * 覆盖层打出来的 {@code actual base_fp}，那说明 KubeJS 那个 {@code json} 就是 GT codec 的输出，
     * 可以退到这条路上；如果不等，它就是纯诊断，不参与交付。
     */
    public static String diagnosticGtCodecFp(GTRecipe recipe) {
        final String raw = diagnosticGtCodecRaw(recipe);
        if (raw == null) {
            return "<gtcodec-null>";
        }
        if (raw.startsWith("<")) {
            return raw;
        }
        final String fp = fingerprintOfRawJson(raw);
        return fp == null ? "<gtcodec-canon-failed>" : fp;
    }

    /** 旁证候选的<b>未截断</b>原文（自检要把它整条打出来，跟 JS 侧打出来的逐字节比）。 */
    public static String diagnosticGtCodecRaw(GTRecipe recipe) {
        try {
            final DataResult<JsonElement> dr = GTRecipeSerializer.CODEC.encodeStart(JsonOps.INSTANCE, recipe);
            final JsonElement el = dr.result().orElse(null);
            if (el == null) {
                return "<gtcodec-encode-failed>";
            }
            final String s = jsonIoToString(el);
            if (s == null || s.isEmpty()) {
                return "<gtcodec-tostring-failed>";
            }
            return s;
        } catch (Throwable t) {
            return "<gtcodec-threw:" + t.getClass().getSimpleName() + ">";
        }
    }

    /** 自检用：KubeJS 是否已被成功解析过一次（避免"根本没 probe"被读成"probe 失败"）。 */
    public static String resolvableState() {
        return kubeJsResolvable == null ? "not-probed" : kubeJsResolvable.toString();
    }
}
