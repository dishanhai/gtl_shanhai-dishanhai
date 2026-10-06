package com.shanhai.common.recipe.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.utils.GTUtil;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.shanhai.ShanhaiMod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>把「输入 / 输出 / 每 tick EU」读写到一个 {@link GTRecipe} 上</b> —— 编辑器 IO/电压那一半的全部机制。
 *
 * <h2>1. 为什么 JSON 是 GT 自己的形状，而不是中立形状</h2>
 * 序列化/反序列化两边都走 <b>GT 自己的 codec</b>：
 * <pre>
 *   写 : Content.codec(cap).encodeStart(JsonOps.INSTANCE, content)
 *   读 : Content.codec(cap).parse(JsonOps.INSTANCE, element)
 * </pre>
 * ⇒ 「编辑器写出来的形状」与「配方加载器认的形状」<b>出自同一段代码</b>，不存在"两边各写一份、
 * 靠人核对"的漂移面。落盘那一层（覆盖文件 {@code fields.inputs}）用的也是这个形状，
 * 所以整条链上只有一种形状。
 *
 * <h2>2. 🔴 运行期正面对照（2026-10-05 冒烟装置实测，不是推断）</h2>
 * 电压那一侧有两条硬事实，都在 {@code temp\recipe-editor-forensics\runs\20261005-103937\} 里：
 * <pre>
 *   P0-TICK-WRITE setInputEUt(original,1024) -> getInputEUT(original)=1024  => GREEN
 *                data.euTier while written = 7 (was 7) => euTier DID NOT follow
 * </pre>
 * ⇒ ① 电压真的住在 {@code tickInputs} 里（改它，{@code RecipeHelper.getInputEUt} 立刻读到新值）；
 * ② <b>改 {@code tickInputs} 不会自动更新 {@code data.euTier}</b> ⇒ 必须同拍手写，
 * 否则"机器看到的电压档"与"真实耗电"分叉。{@link #applyEut} 就是这两步。
 *
 * <h2>3. 🔴 空表 ≠ 没改：返回 null 与返回空对象是两件事</h2>
 * {@code fields.inputs = {"item":[]}} 的语义是"把输入改成空的"（用户右键删光了），
 * 而 {@code fields} 里<b>没有</b> {@code inputs} 这个键的语义是"这次不动输入"。
 * 本类只负责"给什么就写什么"，两者的区分由调用方（{@code Edit} 里字段为 null）承担。
 */
public final class ShanhaiRecipeIoApply {

    private static final String PREFIX = "[SHANHAI-EDIT] editor";

    /** 编辑器认的三种 capability 键名（与 GTRecipeSchema 的键名一致）。 */
    public static final String K_ITEM = "item";
    public static final String K_FLUID = "fluid";
    public static final String K_EU = "eu";

    private ShanhaiRecipeIoApply() {}

    // ================================================================== 读出来

    /**
     * 把一张表（inputs / outputs / tickInputs / tickOutputs）读成 GT 形状的 JSON。
     *
     * @param which {@code "inputs"} / {@code "outputs"} / {@code "tickInputs"} / {@code "tickOutputs"}
     * @return 永不为 null（没有内容就是空对象 {@code {}}）
     */
    public static JsonObject tableJson(GTRecipe recipe, String which) {
        final JsonObject out = new JsonObject();
        if (recipe == null) {
            return out;
        }
        final Map<RecipeCapability<?>, List<Content>> table = tableOf(recipe, which);
        if (table == null) {
            return out;
        }
        addCap(out, table, ItemRecipeCapability.CAP, K_ITEM);
        addCap(out, table, FluidRecipeCapability.CAP, K_FLUID);
        if ("tickInputs".equals(which) || "tickOutputs".equals(which)) {
            addCap(out, table, EURecipeCapability.CAP, K_EU);
        }
        return out;
    }

    /** 读取一张表的全部内容（GT 形状），用于面板显示与自检读数。 */
    private static void addCap(JsonObject out, Map<RecipeCapability<?>, List<Content>> table,
                               RecipeCapability<?> cap, String key) {
        final List<Content> list = table.get(cap);
        if (list == null || list.isEmpty()) {
            return;
        }
        final JsonArray arr = new JsonArray();
        for (Content c : list) {
            final JsonElement el = encode(cap, c);
            if (el != null) {
                arr.add(el);
            }
        }
        out.add(key, arr);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JsonElement encode(RecipeCapability cap, Content c) {
        try {
            final Codec<Content> codec = Content.codec(cap);
            return codec.encodeStart(JsonOps.INSTANCE, c).result().orElse(null);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} io_encode_failed cap={} err={}", PREFIX, cap, t.toString());
            return null;
        }
    }

    /**
     * 对外暴露的"一条 {@link Content} ⇒ GT 形状 JSON"（第二刀：面板那条链要自己拼 IO 表）。
     *
     * <p>存在的理由是<b>只有一种形状</b>：面板写出来的、落盘写下来的、配方加载器认的，
     * 三者都出自 {@code Content.codec(cap)}，不存在"两边各写一份、靠人核对"的漂移面。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static JsonElement encodeContent(RecipeCapability<?> cap, Content c) {
        if (cap == null || c == null) {
            return null;
        }
        return encode(cap, c);
    }

    private static Map<RecipeCapability<?>, List<Content>> tableOf(GTRecipe r, String which) {
        if (which == null) {
            return null;
        }
        return switch (which) {
            case "inputs" -> r.inputs;
            case "outputs" -> r.outputs;
            case "tickInputs" -> r.tickInputs;
            case "tickOutputs" -> r.tickOutputs;
            default -> null;
        };
    }

    // ================================================================== 写进去

    /**
     * 用 {@code json} <b>整段替换</b> {@code which} 那张表。只认 item / fluid（tick 表另认 eu）。
     *
     * @return 写进去的 Content 条数；{@code -1} = 这次没写（json 为 null 或表名不认识）
     */
    public static int applyTable(GTRecipe recipe, String which, JsonObject json) {
        if (recipe == null || json == null) {
            return -1;
        }
        final Map<RecipeCapability<?>, List<Content>> table = tableOf(recipe, which);
        if (table == null) {
            ShanhaiMod.LOGGER.error("{} io_apply_unknown_table which={}", PREFIX, which);
            return -1;
        }
        int written = 0;
        // 🔴 顺序固定：先清空整张表，再按 JSON 里出现的键放回去。
        //    「先清空」是必须的 —— 不这么做的话，用户把某个 capability 的条目删光时，
        //    旧条目会原地留着（"删不掉"），而两种状态在日志上长得一样。
        table.clear();
        written += put(table, ItemRecipeCapability.CAP, json, K_ITEM);
        written += put(table, FluidRecipeCapability.CAP, json, K_FLUID);
        if ("tickInputs".equals(which) || "tickOutputs".equals(which)) {
            written += put(table, EURecipeCapability.CAP, json, K_EU);
        }
        return written;
    }

    private static int put(Map<RecipeCapability<?>, List<Content>> table, RecipeCapability<?> cap,
                           JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            return 0;
        }
        final JsonArray arr = json.getAsJsonArray(key);
        final List<Content> list = new ArrayList<>(arr.size());
        int bad = 0;
        for (JsonElement el : arr) {
            final Content c = decode(cap, el);
            if (c == null) {
                bad++;
                continue;
            }
            list.add(c);
        }
        if (bad > 0) {
            // 🔴 不许静默：解析不出来的条目会让"用户写了 3 条、结果只进去 2 条"这种事
            //    看起来跟"他本来就只写了 2 条"一模一样。
            ShanhaiMod.LOGGER.error("{} io_apply_dropped_unparsable cap={} dropped={} of={} (这些条目没进表)",
                    PREFIX, key, bad, arr.size());
        }
        if (!list.isEmpty()) {
            table.put(cap, list);
        }
        return list.size();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Content decode(RecipeCapability cap, JsonElement el) {
        try {
            final Codec<Content> codec = Content.codec(cap);
            return codec.parse(JsonOps.INSTANCE, el).result().orElse(null);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} io_decode_failed cap={} err={}", PREFIX, cap, t.toString());
            return null;
        }
    }

    // ================================================================== 电压

    /** 这条配方是"耗电"还是"发电"（看哪一 tick 表里有 EU）。 */
    public enum EuSide { CONSUME, GENERATE, NONE }

    public static EuSide euSideOf(GTRecipe r) {
        if (r == null) {
            return EuSide.NONE;
        }
        if (r.getTickInputContents(EURecipeCapability.CAP) != null
                && !r.getTickInputContents(EURecipeCapability.CAP).isEmpty()) {
            return EuSide.CONSUME;
        }
        if (r.getTickOutputContents(EURecipeCapability.CAP) != null
                && !r.getTickOutputContents(EURecipeCapability.CAP).isEmpty()) {
            return EuSide.GENERATE;
        }
        return EuSide.NONE;
    }

    /** 当前 EU/t（消耗型读 tickInputs，发电型读 tickOutputs；都没有则 0）。 */
    public static long euOf(GTRecipe r) {
        if (r == null) {
            return 0L;
        }
        final long in = RecipeHelper.getInputEUt(r);
        return in != 0L ? in : RecipeHelper.getOutputEUt(r);
    }

    /**
     * <b>同拍把电压写全</b>：{@code tickInputs/tickOutputs} 里的 EU ＋ {@code data.euTier}。
     *
     * <p>两步缺一不可（见类注释 §2 的运行期读数）：只写第一步 ⇒ 机器按旧档判定；
     * 只写第二步 ⇒ 读数与真实耗电分叉。
     *
     * @param eut 目标 EU/t（发电型请传正数；本方法按现有方向写）
     * @return true = 两步都写了
     */
    public static boolean applyEut(GTRecipe r, long eut) {
        if (r == null) {
            return false;
        }
        final EuSide side = euSideOf(r);
        switch (side) {
            case CONSUME -> RecipeHelper.setInputEUt(r, eut);
            case GENERATE -> RecipeHelper.setOutputEUt(r, eut);
            case NONE -> {
                // 这条配方原本没有 EU 输入（例如无电类型的机器）⇒ 显式加一条，并如实记日志。
                final List<Content> list = new ArrayList<>(1);
                list.add(new Content(eut, 10000, 10000, 0, null, null));
                r.tickInputs.put(EURecipeCapability.CAP, list);
                ShanhaiMod.LOGGER.info("{} eu_apply_created_tick_input eut={} (这条配方原本没有 tickInputs.eu)",
                        PREFIX, eut);
            }
            default -> {
                return false;
            }
        }
        // 🔴 data.euTier 是【派生值】，改 tickInputs 不会自动更新它（运行期实测，见类注释 §2）。
        final int tier = GTUtil.getTierByVoltage(Math.abs(eut));
        r.data.putInt("euTier", tier);
        return true;
    }
}
