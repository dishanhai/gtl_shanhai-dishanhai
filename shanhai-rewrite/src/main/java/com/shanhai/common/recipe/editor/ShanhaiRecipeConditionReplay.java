package com.shanhai.common.recipe.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shanhai.ShanhaiMod;

import dev.latvian.mods.kubejs.recipe.RecipeJS;
import dev.latvian.mods.kubejs.recipe.component.RecipeComponentValue;

import java.util.List;
import java.util.Map;

/**
 * <b>覆盖层脚本那一侧的「额外条件重放」入口</b> —— 由 KubeJS 脚本调用，逻辑全部在 Java 侧。
 *
 * <h2>1. 为什么重放必须在 Java 侧做（不是"图省事"）</h2>
 * 三条都由 {@code javap} 实测，缺一条这个功能就会静默毁数据：
 * <ol>
 *   <li>🔴 <b>不能用 {@code recipe.set("recipeConditions", …)}</b>：
 *       {@code RecipeJS.set} 的字节码是 {@code value = component.read(...); holder.write(); save();}
 *       ⇒ 它把 KubeJS 那个 {@code RecipeComponent} 标记成"要写回"，而
 *       {@code GTRecipeComponents$5.write()} 产出的形状是 {@code {"type":k,"data":{…}}}，
 *       而 GT 的加载器走的是 {@code RecipeCondition.CODEC}（KeyDispatchCodec，<b>平铺</b>形状）
 *       ⇒ 两边形状不对称 ⇒ <b>配方 json 解析失败</b>（不是"条件没生效"，是整条配方崩）。
 *       实测旁证：用户实例导出目录里两万多个 {@code recipeConditions} 元素，
 *       数据包/GT 自己产的全是平铺，KubeJS component 产的是 {@code data} 包裹的那种。</li>
 *   <li>🔴 <b>{@code serialize()} 会把平铺形状覆盖回 {@code data} 包裹形状</b>：
 *       {@code RecipeJS.serialize()} 对每个 {@code shouldWrite()} 的键调 component 的 write。
 *       ⇒ 光把 JSON 写对还不够，必须把那个键的 {@code write} 标志压成 {@code false}。
 *       （本类做的第二件事就是这个，并把压之前观察到的值打进日志 —— 不猜。）</li>
 *   <li>🔴 <b>走 KubeJS 那个组件会静默丢字段，但<b>逐类不同</b>（2026-10-05 自检更正）</b>：
 *       那个 component 的 {@code read} 把<b>外层</b>对象交给 {@code condition.deserialize()}，
 *       而它的 {@code write} 把字段包在 {@code "data"} 里 ⇒ 形状不对称。字段丢不丢取决于
 *       那个条件有没有写"从外层读"：
 *       <ul>
 *         <li>{@code CleanroomCondition}：{@code deserialize} 读的是外层的 {@code "cleanroom"}
 *             ⇒ data 包裹的形状下，档位<b>静默退回默认值</b> ✗</li>
 *         <li>{@code ModuleLevelCondition}：本工程重写过，两种形状都认 ✓</li>
 *       </ul>
 *       ⇒ 我们<b>没有一张"哪些类型安全"的可信表</b>，所以整条绕开它。</li>
 * </ol>
 *
 * <h2>2. 🔴 fail-closed：校验不过就<b>整条不套用</b></h2>
 * {@link #apply} 在动 json <b>之前</b>先完整解码一遍（{@link ShanhaiRecipeConditions#decode}），
 * 任何一条解不出来（未知类型 / 缺字段 / 结构不对）就返回错误串，脚本据此
 * <b>把整条 entry 判 SKIPPED 并打 ERROR</b> —— 绝不"能吃几条吃几条"。
 * 校验通过之后写进去的是<b>重新编码</b>的那一份（与解码同一个 codec），
 * 所以"能过校验" ⇔ "GT 一定解析得动"。
 *
 * <h2>3. 本类直接引用 KubeJS 类型</h2>
 * 与 {@link ShanhaiRecipeFingerprintCapture} 同一条纪律：只要 KubeJS 不在，
 * 本类就不会被类加载器碰到（调用点只在 KubeJS 脚本里 / 插件绑定里）。
 */
public final class ShanhaiRecipeConditionReplay {

    public static final String PREFIX = "[SHANHAI-EDIT] editor";

    private ShanhaiRecipeConditionReplay() {}

    /**
     * <b>只校验，不动任何东西</b>（脚本在 {@code set} 循环之前调它，实现"要么全成、要么全不动"）。
     *
     * @return 空串 = 通过；否则是给用户看的原因（脚本会原样打进 ERROR 行）
     */
    public static String validate(JsonElement arr) {
        try {
            final ShanhaiRecipeConditions.Decoded d = ShanhaiRecipeConditions.decode(arr);
            if (!d.ok()) {
                return d.error();
            }
            return "";
        } catch (Throwable t) {
            return "conditions_validate_threw " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 这一份里有几条条件（日志判据用；解不出来返回 -1）。 */
    public static int count(JsonElement arr) {
        try {
            final ShanhaiRecipeConditions.Decoded d = ShanhaiRecipeConditions.decode(arr);
            return d.ok() ? d.list().size() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * <b>把这一份条件装回这条 KubeJS 配方</b>（下一局开机时覆盖层调它）。
     *
     * <p>三步：① 解码校验（fail-closed）→ ② 重新编码后写进 {@code recipe.json.recipeConditions}
     * → ③ 把 KubeJS 那个键的 {@code write} 标志压成 false，并 {@code recipe.save()}。
     *
     * @return 空串 = 成功；否则是原因（脚本据此把整条 entry 判 SKIPPED 并打 ERROR）
     */
    public static String apply(RecipeJS recipe, JsonElement arr) {
        if (recipe == null) {
            return "recipe_is_null";
        }
        final JsonArray normalized;
        try {
            final ShanhaiRecipeConditions.Decoded d = ShanhaiRecipeConditions.decode(arr);
            if (!d.ok()) {
                return d.error();
            }
            normalized = ShanhaiRecipeConditions.encode(d.list());
            if (normalized.size() != d.list().size()) {
                // encode() 在任何一条算不出来时会整串拒收（返回空数组）——这里必须如实报，不许静默
                return "conditions_reencode_lost_entries want=" + d.list().size()
                        + " got=" + normalized.size();
            }
        } catch (Throwable t) {
            return "conditions_decode_threw " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }

        final JsonObject json;
        try {
            json = recipe.json;
        } catch (Throwable t) {
            return "recipe_json_unreadable " + t;
        }
        if (json == null) {
            return "recipe_json_is_null";
        }
        json.add(ShanhaiRecipeConditions.RECIPE_JSON_KEY, normalized);

        // ② 把 KubeJS 那个 component 的写回标志压掉（理由见类注释 §1.2）。
        final String flag = suppressComponentWrite(recipe);
        try {
            recipe.save();
        } catch (Throwable t) {
            return "recipe_save_threw " + t;
        }
        ShanhaiMod.LOGGER.info("{} conditions_replay_applied id={} n={} types={} write_flag={} "
                        + "（直接写 GT 认的平铺 JSON，绕过 KubeJS 那个 read/write 不对称的 component）",
                PREFIX, safeId(recipe), normalized.size(), ShanhaiRecipeConditions.summary(normalized), flag);
        return "";
    }

    /**
     * 把 {@code recipeConditions} 这个键在 KubeJS 的 component 表里标成"不要写回"。
     *
     * @return 一行可判读的读数（压之前观察到的值 → 压之后的值），供日志/排查
     */
    private static String suppressComponentWrite(RecipeJS recipe) {
        String observed = "(no_value_map)";
        try {
            final Map<String, RecipeComponentValue<?>> vm = recipe.getAllValueMap();
            if (vm == null) {
                return "(value_map_null)";
            }
            RecipeComponentValue<?> hit = vm.get(ShanhaiRecipeConditions.RECIPE_JSON_KEY);
            if (hit == null) {
                for (Map.Entry<String, RecipeComponentValue<?>> e : vm.entrySet()) {
                    if (e.getKey() != null && e.getKey().contains("recipeConditions")) {
                        hit = e.getValue();
                        break;
                    }
                }
            }
            if (hit == null) {
                return "(no_conditions_holder keys=" + vm.keySet() + ")";
            }
            observed = String.valueOf(hit.shouldWrite());
            // 🔴 直接写 public 字段：RecipeComponentValue 只有 {@code write()}（把标志置 true），
            //    没有 {@code write(boolean)}。字段本身是 public ⇒ 这里赋值即"不要写回"。
            hit.write = false;
            return observed + "->" + hit.shouldWrite();
        } catch (Throwable t) {
            return "(suppress_threw " + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
        }
    }

    private static String safeId(RecipeJS js) {
        try {
            return String.valueOf(js.kjs$getOrCreateId());
        } catch (Throwable t) {
            return "(id?)";
        }
    }

    /** 供 KubeJS 脚本打一行"我这边看见的候选类型"，确认脚本侧那条链是通的。 */
    public static String knownTypesSummary() {
        final List<String> keys = ShanhaiRecipeConditions.knownTypeKeys();
        return "known_types=" + keys.size() + " " + keys;
    }
}
