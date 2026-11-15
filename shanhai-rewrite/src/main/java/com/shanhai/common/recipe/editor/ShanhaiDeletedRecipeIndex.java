package com.shanhai.common.recipe.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeSerializer;
import com.shanhai.ShanhaiMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 山海重构 · <b>「已删除配方」列表的服务端组装</b>。
 *
 * <h2>1. 数据源（全部服务端权威，客户端一个都不算）</h2>
 * <pre>
 *   ① 名单    ：config/shanhai/recipe_overrides.json 里 op=remove / op=disable 的条目
 *                （{@link ShanhaiRecipeOverrideStore}）—— 这是"哪些配方被删过"的唯一真值
 *   ② 主产物  ：config/shanhai/removed_recipe_snapshots.json（{@link ShanhaiRemovedRecipeSnapshot}）
 *   ③ 兜底    ：本局内存里的底本 {@link ShanhaiRecipeBase#pristine}（同一局里刚删的那种，
 *                底本还握着原对象 ⇒ 直接编成 JSON，不必等快照）
 * </pre>
 * ②③ 都拿不到 ⇒ 走"主产物未知"的降级（{@link ShanhaiDeletedRecipeList#rowLabel}），
 * <b>不编</b>一个产物出来。
 *
 * <h2>2. 为什么"主产物"要在服务端算</h2>
 * 客户端没有配方表（{@code ShanhaiRecipeEditorWorkspace} 的客户端那份 {@code server == null}），
 * 也<b>不该</b>持有整份快照 JSON ⇒ 主产物名与图标都由服务端算好、随状态推下去
 * （图标走 {@code writeItem}，见工作区 {@code writeState}）。这条纪律与编辑器其它每一屏一致。
 *
 * <h2>3. 每次进列表重算，不做常驻缓存</h2>
 * 删除/恢复都会改覆盖文件，缓存要额外维护"什么时候失效"；而这份列表的条数通常是个位数
 * （用户点名的场景就是"我删掉的那几条"），每次进列表读一遍两个 json 文件（各 ≤ 几十 KB）
 * 比维护一份会过期的缓存更稳 —— 上一轮那些"第一屏条数不动"的事故全是缓存没失效引起的。
 */
public final class ShanhaiDeletedRecipeIndex {

    public static final String PREFIX = ShanhaiRecipeOverrideStore.PREFIX;

    private ShanhaiDeletedRecipeIndex() {}

    /** 现在覆盖文件里被整条删掉的那几条（id + type + ts，<b>没有</b>主产物）。 */
    public static List<ShanhaiDeletedRecipeList.Row> removedEntries() {
        final List<ShanhaiDeletedRecipeList.Row> out = new ArrayList<>();
        try {
            final JsonObject root = ShanhaiRecipeOverrideStore.loadRoot();
            if (root == null || !root.has("entries") || !root.get("entries").isJsonArray()) {
                return out;
            }
            final Map<String, ShanhaiDeletedRecipeList.Row> byId = new LinkedHashMap<>();
            for (JsonElement e : root.getAsJsonArray("entries")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                final JsonObject o = e.getAsJsonObject();
                if (!o.has("id") || !o.has("op")) {
                    continue;
                }
                final String op;
                try {
                    op = o.get("op").getAsString();
                } catch (Throwable t) {
                    continue;
                }
                if (!"remove".equals(op) && !"disable".equals(op)) {
                    continue;
                }
                final String id = o.get("id").getAsString();
                if (id == null || id.isEmpty()) {
                    continue;
                }
                final String type = o.has("type") && !o.get("type").isJsonNull()
                        ? o.get("type").getAsString() : null;
                final String ts = o.has("ts") && !o.get("ts").isJsonNull()
                        ? o.get("ts").getAsString() : "";
                // 同 id 多条（历史遗留）⇒ 保留最后一条（与覆盖层的"后写的赢"同口径）
                byId.put(id, new ShanhaiDeletedRecipeList.Row(id, type, null, ts));
            }
            out.addAll(byId.values());
            // 新的排前面（把最近删掉的放在第一页 —— 用户要找的多半是刚才那条）
            out.sort(Comparator.comparing((ShanhaiDeletedRecipeList.Row r) -> r.ts() == null ? "" : r.ts())
                    .reversed()
                    .thenComparing(ShanhaiDeletedRecipeList.Row::id));
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} deleted_list_entries_failed err={}", PREFIX, t.toString());
        }
        return out;
    }

    /**
     * 组装完整列表（含主产物）。
     *
     * <p>🔴 2026-11-07：每一行都带 {@code hasSnapshot}（{@link ShanhaiDeletedRecipeList.Row#hasSnapshot()}），
     * 让"<b>缺快照</b>"与"<b>有快照但认不出产物</b>"在列表里就是两句话 —— 用户实测那句
     * 「全是主产物未知」的真实情况是 16/17 条<b>压根没有快照</b>（不是没有产物）。
     *
     * @param server 可以为 {@code null}（那样就没有底本兜底，只剩快照那一路）
     */
    public static ShanhaiDeletedRecipeList load(net.minecraft.server.MinecraftServer server) {
        final List<ShanhaiDeletedRecipeList.Row> entries = removedEntries();
        final Map<String, JsonObject> jsonById = new LinkedHashMap<>();
        final java.util.Set<String> existsIds = new java.util.HashSet<>();
        // 一次读全表（别按行读盘：17 行 × 2 次解析毫无必要）
        Map<String, JsonObject> onDisk = new LinkedHashMap<>();
        java.util.Set<String> onDiskIds = new java.util.HashSet<>();
        try {
            onDisk = ShanhaiRemovedRecipeSnapshot.allJson();
            onDiskIds = ShanhaiRemovedRecipeSnapshot.allIds();
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} deleted_list_snapshot_read_failed err={}", PREFIX, t.toString());
        }
        for (ShanhaiDeletedRecipeList.Row r : entries) {
            JsonObject json = onDisk.get(r.id());
            final boolean exists = onDiskIds.contains(r.id()) || json != null;
            if (!ShanhaiDeletedRecipeList.isUsableRecipeJson(json)) {
                // 空壳/残的 ⇒ 当作没有；本局底本（同一局里刚删的那种）还能现编一份
                json = jsonFromBase(r.id());
            }
            if (json != null) {
                jsonById.put(r.id(), json);
            }
            if (exists || json != null) {
                existsIds.add(r.id());
            }
        }
        final ShanhaiDeletedRecipeList list = ShanhaiDeletedRecipeList.assemble(entries,
                new ShanhaiDeletedRecipeList.SnapshotSource() {
                    @Override
                    public JsonObject jsonOf(String recipeId) {
                        return jsonById.get(recipeId);
                    }

                    @Override
                    public boolean exists(String recipeId) {
                        return existsIds.contains(recipeId);
                    }
                });
        // 显示名要在服务端取（客户端没有物品注册表的中文名上下文也不该自己算）
        final List<ShanhaiDeletedRecipeList.Row> named = new ArrayList<>(list.rows().size());
        for (ShanhaiDeletedRecipeList.Row r : list.rows()) {
            named.add(new ShanhaiDeletedRecipeList.Row(r.id(), r.typeId(), r.main(), r.ts(), mainNameOf(r),
                    r.hasSnapshot()));
        }
        final ShanhaiDeletedRecipeList out = ShanhaiDeletedRecipeList.of(named);
        ShanhaiMod.LOGGER.info("{} deleted_list_built {} snapshots_usable={} snapshots_on_disk={} entries={} server={}",
                PREFIX, out.statsLine(), jsonById.size(), existsIds.size(), entries.size(), server != null);
        return out;
    }

    /** 详情页要用到的三种快照状态（"没有" / "有但没用" / "能用"）。 */
    public enum SnapshotState {
        /** 盘上没有这条（可以靠下次开机补份修）。 */
        NONE,
        /** 盘上有、但内容不可用（空壳/残的）—— 补份**不会**再覆盖它，只能重删重留。 */
        UNUSABLE,
        /** 有一份能用的。 */
        OK
    }

    /** 这条 id 的快照现在是什么状态（详情页据此选那一句提示，不许混成一句"未知"）。 */
    public static SnapshotState stateOf(String recipeId) {
        if (recipeId == null) {
            return SnapshotState.NONE;
        }
        final JsonObject j = ShanhaiRemovedRecipeSnapshot.get(recipeId);
        if (ShanhaiDeletedRecipeList.isUsableRecipeJson(j)) {
            return SnapshotState.OK;
        }
        try {
            return ShanhaiRemovedRecipeSnapshot.entryExists(recipeId) ? SnapshotState.UNUSABLE : SnapshotState.NONE;
        } catch (Throwable t) {
            return SnapshotState.NONE;
        }
    }

    /** 底本（本局内存）里还握着那条原对象 ⇒ 现编一份 JSON（拿不到返回 null）。 */
    private static JsonObject jsonFromBase(String recipeId) {
        try {
            final ResourceLocation id = ResourceLocation.tryParse(recipeId);
            if (id == null) {
                return null;
            }
            final GTRecipe pristine = ShanhaiRecipeBase.pristine(id);
            if (pristine == null) {
                return null;
            }
            return ShanhaiRemovedRecipeSnapshot.jsonOf(pristine);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 那一行的图标（拿不到主产物 ⇒ {@code ItemStack.EMPTY}，面板就不画图标）。
     *
     * <p>⚠️ 只认"能解析成物品 id"的那一支：tag / 编程电路 / 流体产出都没有确定的物品可画，
     * 这时<b>宁可不画</b>，也不拿一个近似的物品冒充（活的界面上不许放假数据）。
     */
    public static ItemStack iconOf(ShanhaiDeletedRecipeList.Row row) {
        if (row == null || !row.hasMain()) {
            return ItemStack.EMPTY;
        }
        final String itemId = row.mainItemId();
        if (itemId == null || itemId.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            final ResourceLocation rl = ResourceLocation.tryParse(itemId);
            if (rl == null) {
                return ItemStack.EMPTY;
            }
            final Item item = BuiltInRegistries.ITEM.get(rl);
            if (item == null) {
                return ItemStack.EMPTY;
            }
            final ItemStack s = new ItemStack(item);
            s.setCount(Math.max(1, Math.min(64, row.mainCount())));
            return s;
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 🆕 <b>流体 id ⇒ 显示名</b>（用户原话：「流体产出没有标注流体的名字」）。
     *
     * <p>走的是 {@code FluidStack#getDisplayName()} —— 与编辑屏里那个格子外面那一行字
     * <b>同一个口径</b>（{@code ShanhaiIOWidget#describeCell}），所以这里显示的名字
     * 与用户在输入输出表里看到的<b>一定是同一个</b>。单机/局域网下服务端与客户端同一个 JVM
     * ⇒ 这里的 {@code getString()} 拿到的是<b>客户端语言（zh_cn）</b>的名字。
     *
     * @return 名字；<b>拿不到就 {@code null}</b>（调用方退回 id，绝不编一个名字 —— 本工程红线）
     */
    public static String fluidDisplayName(String fluidId) {
        if (fluidId == null || fluidId.isEmpty()) {
            return null;
        }
        try {
            final ResourceLocation rl = ResourceLocation.tryParse(fluidId);
            if (rl == null) {
                return null;
            }
            final net.minecraft.world.level.material.Fluid f =
                    net.minecraft.core.registries.BuiltInRegistries.FLUID.get(rl);
            if (f == null || f == net.minecraft.world.level.material.Fluids.EMPTY) {
                return null;
            }
            final String raw = new net.minecraftforge.fluids.FluidStack(f, 1)
                    .getDisplayName().getString();
            final String base = com.shanhai.common.text.ShanhaiTextParser.stripStyleCode(raw);
            return base == null || base.isEmpty() ? null : base;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 流体主产物那一行的人话：{@code 硫酸 x1000 mB}。
     *
     * <p>阶梯：显示名 → 流体 id → 空串（调用方再退回 {@code 流体产出}）。
     * 数量单位写死 {@code mB} 是因为 {@code outputs.fluid[0].content.amount} 就是<b>毫桶</b>
     * （实测样本：{@code "amount":48000} = 48000 mB，见 {@link ShanhaiDeletedRecipeList#fluidIdOf}）。
     */
    public static String fluidNameOf(String fluidId, int amountMb) {
        if (fluidId == null || fluidId.isEmpty()) {
            return "";
        }
        final String base = fluidDisplayName(fluidId);
        final String shown = base != null ? base : fluidId;
        return shown + " x" + Math.max(1, amountMb) + " mB";
    }

    /** 🆕 物品 id ⇒ 显示名（与 {@link #mainNameOf} 同一个口径：{@code getHoverName()}；拿不到 ⇒ {@code null}）。 */
    public static String itemDisplayName(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        try {
            final ResourceLocation rl = ResourceLocation.tryParse(itemId);
            if (rl == null) {
                return null;
            }
            final Item item = BuiltInRegistries.ITEM.get(rl);
            if (item == null) {
                return null;
            }
            final String base = com.shanhai.common.text.ShanhaiTextParser
                    .stripStyleCode(new ItemStack(item).getHoverName().getString());
            return base == null || base.isEmpty() ? null : base;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 🆕 <b>详情页那行读数后面要跟的"产物名字清单"</b>（物品名 ＋ 流体名）。
     *
     * <p>为什么必须补这个：详情页的输入输出读数（{@code ioOf}）只有数量
     * （{@code 物品 3 入 / 1 出 · 流体 0 入 / 0 出}）—— 用户看的就是那一行，
     * 而"流体 0 入 / 1 出"一个字都没说<b>产出的是什么</b>。
     *
     * @param json 快照 JSON（{@code null} ⇒ 空串）
     * @return 例如 {@code 大型组装机外壳} / {@code 硫酸} / {@code A、B… …等 9 项}；认不出任何产物 ⇒ {@code ""}
     */
    public static String productsLine(com.google.gson.JsonObject json) {
        return ShanhaiDeletedRecipeList.productsLine(json,
                ShanhaiDeletedRecipeIndex::itemDisplayName,
                ShanhaiDeletedRecipeIndex::fluidDisplayName, 6);
    }

    /** 主产物的中文名（拿不到 ⇒ 空串；面板只把它画在文字里，取不到就不画）。数量 >1 时带上 {@code xN}。 */
    public static String mainNameOf(ShanhaiDeletedRecipeList.Row row) {
        // 🆕 纯流体产出：主产物没有物品可画，但有名字 —— 用 FluidStack 的显示名（拿不到退回 id）
        if (row != null && row.main() != null && row.main().hasFluid()) {
            return fluidNameOf(row.main().fluidId(), row.mainCount());
        }
        final ItemStack s = iconOf(row);
        if (s.isEmpty()) {
            return "";
        }
        final String base;
        try {
            base = com.shanhai.common.text.ShanhaiTextParser
                    .stripStyleCode(s.getHoverName().getString());
        } catch (Throwable t) {
            return "";
        }
        if (base == null || base.isEmpty()) {
            return "";
        }
        return row.mainCount() > 1 ? base + " x" + row.mainCount() : base;
    }

    /**
     * 详情页要显示的那条配方：把快照 JSON 交给 GT 自己的反序列化器造一条<b>只读</b>对象出来。
     *
     * <p>⚠️ 它<b>不进</b>任何索引、<b>不</b>登记底本、<b>不</b>写表 —— 纯展示用。
     * 解析失败返回 {@code null}（详情页会如实写"这条没有留存快照/解析失败"）。
     */
    public static GTRecipe parseForDisplay(String recipeId, JsonObject json) {
        if (recipeId == null || json == null) {
            return null;
        }
        // 🔴 2026-11-07：空壳（KubeJS 的 {"type":"unknown"}）不必再往 GT 里送 —— 送进去也只会
        //    抛一条解析异常，把日志搅成"解析失败"，而真因是"根本没有快照内容"。
        if (!ShanhaiDeletedRecipeList.isUsableRecipeJson(json)) {
            ShanhaiMod.LOGGER.warn("{} deleted_preview_snapshot_unusable id={} keys={}（不送 GT：这份里没有配方内容）",
                    PREFIX, recipeId, json.keySet());
            return null;
        }
        try {
            final ResourceLocation id = ResourceLocation.tryParse(recipeId);
            if (id == null) {
                return null;
            }
            final JsonObject copy = json.deepCopy();
            if (!copy.has("type")) {
                // 快照里没写 type 的话，从快照条目里补（GT 的 fromJson 靠它分发）
                final String type = ShanhaiRemovedRecipeSnapshot.typeOf(recipeId);
                if (type != null) {
                    copy.addProperty("type", type);
                }
            }
            final GTRecipe r = GTRecipeSerializer.SERIALIZER.fromJson(id, copy);
            if (r != null) {
                r.id = id;
            }
            return r;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} deleted_preview_parse_failed id={} err={}", PREFIX, recipeId, t.toString());
            return null;
        }
    }

    /** 快照里那几条 id（自检读数用）。 */
    public static List<String> snapshotIds() {
        final List<String> out = new ArrayList<>();
        try {
            for (String k : ShanhaiRemovedRecipeSnapshot.allJson().keySet()) {
                out.add(k);
            }
        } catch (Throwable ignored) {
            // 读不出来就是空表（自检会把它当"0 条"如实打出来）
        }
        return out;
    }

    /** 一行读数（自检/日志）。 */
    public static String statsLine() {
        final List<ShanhaiDeletedRecipeList.Row> rows = removedEntries();
        return "overlay_removed=" + rows.size()
                + " snapshots=" + ShanhaiRemovedRecipeSnapshot.size();
    }
}
