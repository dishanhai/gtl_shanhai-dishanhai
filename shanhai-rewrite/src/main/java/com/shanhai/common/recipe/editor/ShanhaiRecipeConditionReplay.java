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

        // 🆕🔴 2026-10-07（显示层缺口）：条件装上了【服务端】，还必须让【客户端 JEI 那一份】也带上 ——
        //    否则 JEI 配方页里那一行催化剂槽看不见：ModuleCatalystSlotUI.firstGateModule 读的就是
        //    recipe.conditions，而客户端那份是"照服务端发过去的字节/载荷"造的。
        //    通道用**现成的**：ShanhaiJeiBridge 的 ACTION_CHANGED（载荷里就有 conditionsJson 这一格，
        //    客户端 synthesize() 会整段替换副本的 conditions）—— 这里只把【受影响的这一条】登记进去，
        //    真正发出去的时点见 ShanhaiJeiBridge.noteReplayedConditions 的整段注释。
        noteToJei(recipe, json, normalized);
        return "";
    }

    /**
     * 把"这一条刚被重放装上条件"交给 JEI 那条现成的通道（{@link ShanhaiJeiBridge}）。
     *
     * <p>🔴 <b>只登记，不在这里发送</b>：本方法跑在配方加载期（KubeJS 事件里），那一刻
     * <b>没有 server、也没有玩家</b> ⇒ 直接发必然发不出去。登记之后由"玩家登录 / reload /
     * 立刻有玩家在线"这三个时点补发（掉线的客户端下次上线就由登录那一拍补上）。
     *
     * <p>⚠️ <b>fail-open 且如实报</b>：任何一步失败都只打一行日志 ——
     * 重放本身<b>已经成功</b>（服务端那一份是对的），绝不因为"推不出去"把重放判成失败。
     * 拿不到类型 id 时<b>宁可不推</b>并说明理由（客户端是按"类型 == 当前分类 uid"对号入座的，推错类型等于把补丁丢进黑洞）。
     */
    private static void noteToJei(RecipeJS recipe, JsonObject json, JsonArray normalized) {
        final String id = safeId(recipe);
        if (id == null || id.isEmpty() || "(id?)".equals(id)) {
            ShanhaiMod.LOGGER.warn("{} conditions_replay_jei_skipped id={} reason=no_recipe_id "
                    + "（这条条件在服务端已装上，只是没法告诉客户端是哪一条 ⇒ JEI 那一页看不到槽）", PREFIX, id);
            return;
        }
        String type = null;
        try {
            final JsonElement t = json.get("type");
            if (t != null && t.isJsonPrimitive()) {
                type = t.getAsString();
            }
        } catch (Throwable ignored) {
            // 落到下面那句"没有类型"的如实报告
        }
        if (type == null || type.isEmpty()) {
            ShanhaiMod.LOGGER.warn("{} conditions_replay_jei_skipped id={} reason=no_type_id "
                    + "（配方 json 里没有 \"type\" ⇒ 不知道它属于 JEI 哪个分类 ⇒ 宁可不推，也不推错）", PREFIX, id);
            return;
        }
        try {
            ShanhaiJeiBridge.noteReplayedConditions(id, type, normalized.toString());
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} conditions_replay_jei_note_failed id={} type={} err={} "
                    + "（重放本身已成功；只有 JEI 那一次推送没登记上）", PREFIX, id, type, t.toString());
        }
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
