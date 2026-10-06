package com.shanhai.common.recipe.editor;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.shanhai.ShanhaiMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>非 GT 配方的「底本 ＋ 编辑台账 ＋ 索引」</b> —— 与 GT 那条线（{@link ShanhaiRecipeBase}）同构的模型。
 *
 * <h2>1. 🔴 为什么不能复用 {@link ShanhaiRecipeBase}</h2>
 * 它抓底本的第一句就是
 * {@code if (!(r instanceof GTRecipe gt) || gt.id == null) continue;}（{@code ShanhaiRecipeBase.java:209-221}）
 * ⇒ 非 GT 配方<b>一条都进不来</b>。反查索引 {@code ShanhaiRecipeReverseIndex} 里 5 处扫描循环同样如此。
 * 而且台账条目的字段也是 GT 专用的（{@code duration / inputs / outputs / tickInputs / euTier / conditions}），
 * 原版配方要的是 {@code result / cookingtime / experience}。⇒ <b>结构性地必须另起一套</b>。
 *
 * <h2>2. 模型（与 GT 那条线逐字同构）</h2>
 * <pre>
 *   BASE   : id → 第一次从 RecipeManager 见到的那个 {@code Recipe<?>} 对象（原样，永不修改）
 *   LEDGER : id → Edit{ result | cookingTime | experience | removed }
 *   重建   : BASE 里的底本 ＋ 台账 一起喂给 {@link ShanhaiVanillaRecipeRebuild}
 * </pre>
 * ⇒ 连改 N 次，结果只取决于<b>最后一次</b>台账值（不累加）。
 *
 * <h2>3. ⚠️ 边界（如实写清）</h2>
 * <ul>
 *   <li>底本只在<b>第一次</b>建立时抓一次。一次 {@code /reload} 之后 {@code RecipeManager} 里换成了
 *       新对象，底本会变旧 —— 与 GT 那条线一样，提供显式 {@link #recapture}，<b>不自动触发</b>
 *       （自动重抓会在"用户刚改完还没重启"的时候把台账和底本一起换掉，那才是真丢数据）；</li>
 *   <li>索引（{@code BY_TYPE / LIVE}）是<b>纯缓存</b>，编辑之后 {@link #invalidate} 重扫一次；
 *       它不参与正确性判定，只决定列表里看得见什么。</li>
 * </ul>
 */
public final class ShanhaiVanillaRecipeTable {

    public static final String PREFIX = "[SHANHAI-EDIT] editor";

    /**
     * 台账条目。
     *
     * <p>口径与 GT 那条线一致：<b>{@code null} 与"改成空"是两件事</b> ——
     * {@code result == null} 表示"这次不动产物"，而不是"把产物清空"。
     *
     * <p>🆕 阶段 2 加了 {@code shape}（输入侧的统一中间表示）：
     * {@code null} = 这次不动输入（与阶段 1 逐字节等价）。
     * 只有 {@link ShanhaiVanillaRecipeShape#isDirty()} 为真的那一份才会被重建用上。
     */
    public record Edit(ItemStack result, Integer cookingTime, Double experience, boolean removed,
                       ShanhaiVanillaRecipeShape shape) {

        public static final Edit EMPTY = new Edit(null, null, null, false, null);

        public boolean hasAnything() {
            return result != null || cookingTime != null || experience != null || shapeDirty();
        }

        /** 这一条有没有"要写回配方的输入改动"。 */
        public boolean shapeDirty() {
            return shape != null && shape.isDirty();
        }

        public Edit withResult(ItemStack s) {
            return new Edit(s == null ? null : s.copy(), cookingTime, experience, removed, shape);
        }

        public Edit withCookingTime(Integer t) {
            return new Edit(result, t, experience, removed, shape);
        }

        public Edit withExperience(Double x) {
            return new Edit(result, cookingTime, x, removed, shape);
        }

        /** 记下输入侧那一份（存<b>拷贝</b>：用户继续编辑不会改到这里，回滚也能真回去）。 */
        public Edit withShape(ShanhaiVanillaRecipeShape s) {
            return new Edit(result, cookingTime, experience, removed, s == null ? null : s.copy());
        }

        public Edit asRemoved() {
            return new Edit(result, cookingTime, experience, true, shape);
        }

        public Edit asPresent() {
            return new Edit(result, cookingTime, experience, false, shape);
        }

        /** 日志/自检用的一行读数。 */
        public String describe() {
            return "result=" + (result == null ? "(untouched)"
                    : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(result.getItem())
                    + " x" + result.getCount())
                    + " cook=" + (cookingTime == null ? "(untouched)" : cookingTime)
                    + " xp=" + (experience == null ? "(untouched)" : experience)
                    + " inputs=" + (shape == null ? "(untouched)"
                    : (shape.isDirty() ? shape.describe() : "(untouched)"))
                    + (removed ? " REMOVED" : "");
        }
    }

    // ---------------------------------------------------------------- 状态

    /** 底本：id → 第一次见到的那个原对象。 */
    private static final Map<ResourceLocation, Recipe<?>> BASE = new LinkedHashMap<>();

    /** 台账：id → 这次要改成什么样。 */
    private static final Map<ResourceLocation, Edit> LEDGER = new LinkedHashMap<>();

    /** 被我们删过的 id（"放回去"那一拍靠它决定要不要重新插回原版表）。 */
    private static final Set<ResourceLocation> EVER_REMOVED = new LinkedHashSet<>();

    /** 被我们动过的 id（台账撤销之后要把表里那条还原成底本原对象）。 */
    private static final Set<ResourceLocation> EVER_TOUCHED = new LinkedHashSet<>();

    /**
     * 🆕 第 12 刀：<b>本编辑器「新建」出来的配方</b>（id → 那个新对象）。
     *
     * <p>它同时也在 {@link #BASE} 里（重建、索引、恢复都走同一条路）—— 这一张表只多回答一个问题：
     * 「这条是<b>从无到有</b>造出来的吗」。
     *
     * <p>🔴 <b>它绝不能被当成"这条配方已经持久化过"的判据</b>。上一刀那条 P0（GT 侧）
     * 的病根正是拿"活配方表里有没有它"当判据：新建出来的配方在<b>本局</b>是覆盖层
     * {@code op=add} 重放出来的 / 或本局刚造的，它从来没有被持久化过 ⇒ 下一次保存被改写成
     * {@code op=set} ⇒ {@code upsert} 按 id 去重把 {@code op=add} 顶掉 ⇒ 下一局开机
     * {@code MISSING no such recipe id} ⇒ <b>配方消失</b>。
     * ⇒ 判据必须是「<b>覆盖文件里那条 entry 的 op 是不是 add</b>」（见
     * {@link ShanhaiVanillaRecipeOps#existingAddEntry}），本表只用于「删除时怎么收尾」这类内部动作。
     */
    private static final Map<ResourceLocation, Recipe<?>> NEW = new LinkedHashMap<>();

    /** 纯缓存：当前表里非 GT 各类型 → 该类型的配方 id（已排序）。 */
    private static final Map<ResourceLocation, List<ResourceLocation>> BY_TYPE = new LinkedHashMap<>();

    /** 纯缓存：当前表里非 GT 的 id → 活对象。 */
    private static final Map<ResourceLocation, Recipe<?>> LIVE = new LinkedHashMap<>();

    private static boolean captured = false;
    private static boolean indexBuilt = false;
    private static int capturedCount = 0;
    private static int capturedDup = 0;
    private static int capturedSkippedNoId = 0;
    private static String capturedAt = "(never)";

    private ShanhaiVanillaRecipeTable() {}

    // ---------------------------------------------------------------- 判定

    /** 这条配方算不算"非 GT"（GT 那条线一律不碰）。 */
    public static boolean isVanilla(Recipe<?> r) {
        return r != null && !(r instanceof GTRecipe);
    }

    // ---------------------------------------------------------------- 抓底本

    public static synchronized void captureIfAbsent(MinecraftServer server) {
        if (captured) {
            return;
        }
        capture(server);
    }

    /** 显式重抓（{@code /shanhai edit vrecapture}）：丢掉台账、重新抓底本。 */
    public static synchronized int recapture(MinecraftServer server) {
        LEDGER.clear();
        EVER_REMOVED.clear();
        EVER_TOUCHED.clear();
        captured = false;
        indexBuilt = false;
        capture(server);
        ShanhaiMod.LOGGER.warn("{} vanilla_base_recaptured entries={} dup_ids={} (非 GT 台账已清空)",
                PREFIX, capturedCount, capturedDup);
        return capturedCount;
    }

    private static void capture(MinecraftServer server) {
        BASE.clear();
        capturedDup = 0;
        capturedSkippedNoId = 0;
        int n = 0;
        if (server != null) {
            for (Recipe<?> r : server.getRecipeManager().getRecipes()) {
                if (!isVanilla(r)) {
                    continue;
                }
                final ResourceLocation id = r.getId();
                if (id == null) {
                    // 🔴 不许静默：没有 id 的配方在编辑器里根本无从定位（面板是按 id 索引的）
                    capturedSkippedNoId++;
                    continue;
                }
                if (BASE.containsKey(id)) {
                    capturedDup++;
                    continue;
                }
                BASE.put(id, r);
                n++;
            }
        }
        capturedCount = n;
        captured = true;
        capturedAt = java.time.LocalTime.now().withNano(0).toString();
        ShanhaiMod.LOGGER.info("{} vanilla_base_captured count={} dup_ids_skipped={} no_id_skipped={} at={}",
                PREFIX, n, capturedDup, capturedSkippedNoId, capturedAt);
        if (capturedDup > 0) {
            ShanhaiMod.LOGGER.warn("{} vanilla_base_duplicate_ids={} 已按 id 压成一份（后面的那份被丢弃，没有被静默保留）",
                    PREFIX, capturedDup);
        }
    }

    // ---------------------------------------------------------------- 索引（纯缓存）

    /** 重扫一遍活表，重建"类型 → 配方 id"这份缓存。面板"重读配方表"与编辑之后都会调。 */
    public static synchronized void rebuildIndex(MinecraftServer server) {
        BY_TYPE.clear();
        LIVE.clear();
        if (server == null) {
            indexBuilt = false;
            return;
        }
        final Map<ResourceLocation, List<ResourceLocation>> byType = new LinkedHashMap<>();
        for (Recipe<?> r : server.getRecipeManager().getRecipes()) {
            if (!isVanilla(r)) {
                continue;
            }
            final ResourceLocation id = r.getId();
            if (id == null) {
                continue;
            }
            final ResourceLocation t = typeIdOf(r);
            byType.computeIfAbsent(t, k -> new ArrayList<>()).add(id);
            LIVE.put(id, r);
        }
        for (Map.Entry<ResourceLocation, List<ResourceLocation>> e : byType.entrySet()) {
            final List<ResourceLocation> list = e.getValue();
            list.sort(Comparator.comparing(ResourceLocation::toString));
            BY_TYPE.put(e.getKey(), List.copyOf(list));
        }
        indexBuilt = true;
        ShanhaiMod.LOGGER.info("{} vanilla_index_built types={} recipes={} (非 GT；只算有 id 的那些)",
                PREFIX, BY_TYPE.size(), LIVE.size());
    }

    public static synchronized void invalidate() {
        indexBuilt = false;
        BY_TYPE.clear();
        LIVE.clear();
    }

    public static synchronized boolean isIndexBuilt() {
        return indexBuilt;
    }

    /** 配方类型 id（{@code BuiltInRegistries.RECIPE_TYPE} 的键；拿不到给 {@code minecraft:unknown}）。 */
    public static ResourceLocation typeIdOf(Recipe<?> r) {
        if (r == null || r.getType() == null) {
            return new ResourceLocation("minecraft", "unknown");
        }
        final ResourceLocation k = net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE.getKey(r.getType());
        return k == null ? new ResourceLocation("minecraft", "unknown") : k;
    }

    /** 当前表里非 GT 的所有类型 id（已排序）。 */
    public static synchronized List<ResourceLocation> types() {
        final List<ResourceLocation> out = new ArrayList<>(BY_TYPE.keySet());
        out.sort(Comparator.comparing(ResourceLocation::toString));
        return out;
    }

    /** 某个类型下的所有配方 id（已排序，没有则空表）。 */
    public static synchronized List<ResourceLocation> idsOf(ResourceLocation typeId) {
        final List<ResourceLocation> l = typeId == null ? null : BY_TYPE.get(typeId);
        return l == null ? List.of() : l;
    }

    public static synchronized int countOf(ResourceLocation typeId) {
        final List<ResourceLocation> l = typeId == null ? null : BY_TYPE.get(typeId);
        return l == null ? 0 : l.size();
    }

    /** 活表里那条非 GT 配方（索引没建 / 没有 ⇒ null）。 */
    public static synchronized Recipe<?> liveById(ResourceLocation id) {
        return id == null ? null : LIVE.get(id);
    }

    /** 直接走 {@code RecipeManager} 的 {@code byName} 表按 id 取（不依赖索引缓存）。 */
    public static Recipe<?> byIdFromManager(MinecraftServer server, ResourceLocation id) {
        if (server == null || id == null) {
            return null;
        }
        try {
            final RecipeManager rm = server.getRecipeManager();
            for (Recipe<?> r : rm.getRecipes()) {
                if (isVanilla(r) && id.equals(r.getId())) {
                    return r;
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} vanilla_by_id_failed id={} err={}", PREFIX, id, t.toString());
        }
        return null;
    }

    public static synchronized int liveSize() {
        return LIVE.size();
    }

    // ---------------------------------------------------------------- 底本 / 台账

    public static synchronized boolean isCaptured() {
        return captured;
    }

    public static synchronized int baseSize() {
        return BASE.size();
    }

    /** 那条配方的<b>原样</b>底本对象（没有就 null）。 */
    public static synchronized Recipe<?> pristine(ResourceLocation id) {
        return id == null ? null : BASE.get(id);
    }

    public static synchronized Edit editOf(ResourceLocation id) {
        return id == null ? null : LEDGER.get(id);
    }

    public static synchronized boolean isRemoved(ResourceLocation id) {
        final Edit e = id == null ? null : LEDGER.get(id);
        return e != null && e.removed();
    }

    public static synchronized boolean everRemoved(ResourceLocation id) {
        return id != null && EVER_REMOVED.contains(id);
    }

    public static synchronized boolean everTouched(ResourceLocation id) {
        return id != null && EVER_TOUCHED.contains(id);
    }

    public static synchronized Set<ResourceLocation> allIds() {
        return new LinkedHashSet<>(BASE.keySet());
    }

    public static synchronized int ledgerSize() {
        return LEDGER.size();
    }

    public static synchronized Map<ResourceLocation, Edit> ledgerSnapshot() {
        return new LinkedHashMap<>(LEDGER);
    }

    public static synchronized int everRemovedCount() {
        return EVER_REMOVED.size();
    }

    // ---------------------------------------------------------------- 🆕 第 12 刀：新建

    /**
     * 🆕 第 12 刀：把一条<b>本编辑器造出来的</b>非 GT 配方登记进底本。
     *
     * <p>为什么必须登记（与 GT 那条线 {@code ShanhaiRecipeBase.registerNew} 同一条纪律）：
     * 不登记的话，下次重建索引时它在底本里查不到 ⇒ {@code finalRecipeOf} 返回 null ⇒
     * 这条配方<b>静默消失</b>（列表里还在、点进去打不开、也重建不了）。
     *
     * @return {@code false} = <b>id 已经被占了</b>（底本里已有 / 本次已登记过）⇒ 调用方必须报错，
     *         绝不许静默覆盖
     */
    public static synchronized boolean registerNew(Recipe<?> r) {
        if (r == null || r.getId() == null) {
            return false;
        }
        final ResourceLocation id = r.getId();
        if (BASE.containsKey(id) || NEW.containsKey(id)) {
            ShanhaiMod.LOGGER.error("{} vanilla_new_id_taken id={} -> 底本里已经有这个 id，"
                    + "拒绝新建（绝不静默覆盖）", PREFIX, id);
            return false;
        }
        NEW.put(id, r);
        BASE.put(id, r);
        EVER_TOUCHED.add(id);
        ShanhaiMod.LOGGER.info("{} vanilla_new_registered id={} class={} base_size={} new_size={}",
                PREFIX, id, r.getClass().getName(), BASE.size(), NEW.size());
        return true;
    }

    /** 这条 id 是不是"本编辑器新建出来的"（本局有效）。 */
    public static synchronized boolean isNew(ResourceLocation id) {
        return id != null && NEW.containsKey(id);
    }

    /**
     * 忘掉一条新建（= 文件里那条 {@code op=add} 已经没了 / 用户把它删了）。
     *
     * <p>它把这条从 {@link #NEW} 与 {@link #BASE} 里一起摘掉 —— 于是"被删过就不许再插回来"
     * 那一拍（{@code applyLedger} 尾部的补插循环要求 {@code pristine != null}）不会把它复活。
     * <b>本方法自己不动活表</b>：活表由台账驱动（调用方必须先 {@code markRemoved}）。
     */
    public static synchronized void forgetNew(ResourceLocation id) {
        if (id == null) {
            return;
        }
        NEW.remove(id);
        BASE.remove(id);
    }

    /** 本局新建出来的那些 id（"恢复全部"要用它收尾）。 */
    public static synchronized Set<ResourceLocation> newIdsSnapshot() {
        return new LinkedHashSet<>(NEW.keySet());
    }

    public static synchronized int newCount() {
        return NEW.size();
    }

    /**
     * 🆕 2026-10-05（冒烟自检抓出来的真 bug 的修法本体）：
     * <b>"被我们动过"的条数</b> —— {@code ShanhaiVanillaRecipeOps.applyLedger} 的"什么都没得做"早退
     * <b>必须看它，不能只看台账条数</b>。
     *
     * <h4>漏了它会怎样（实测读数）</h4>
     * {@code restoreOne} 先把台账那一条清掉、再调 {@code syncVanillaFromBase}。
     * 那一刻台账已经空了 ⇒ 只看 {@code ledgerSize()==0} 就会<b>提前返回、一个元素都不碰</b>
     * ⇒ 原版表里留着的还是上一拍的<b>改过副本</b>，"恢复原样"静默失效。
     * <pre>
     *   case=vanilla_restore ok=false id=ad_astra:aeronos_door expected_old=3 read=7 ledger_size=0
     * </pre>
     * （GT 那条线没有这个早退，所以只有非 GT 这一支会犯。）
     */
    public static synchronized int everTouchedCount() {
        return EVER_TOUCHED.size();
    }

    public static synchronized void clearEverRemoved() {
        EVER_REMOVED.clear();
    }

    public static synchronized void clearAllEditsKeepTypes() {
        LEDGER.clear();
    }

    public static synchronized Set<ResourceLocation> everTouchedSnapshot() {
        return new LinkedHashSet<>(EVER_TOUCHED);
    }

    // ---------------------------------------------------------------- 写台账

    public static synchronized void setResult(ResourceLocation id, ItemStack s) {
        if (id == null) {
            return;
        }
        EVER_TOUCHED.add(id);
        final Edit cur = LEDGER.getOrDefault(id, Edit.EMPTY);
        LEDGER.put(id, cur.asPresent().withResult(s));
    }

    public static synchronized void setCookingTime(ResourceLocation id, Integer t) {
        if (id == null) {
            return;
        }
        EVER_TOUCHED.add(id);
        final Edit cur = LEDGER.getOrDefault(id, Edit.EMPTY);
        LEDGER.put(id, cur.asPresent().withCookingTime(t));
    }

    public static synchronized void setExperience(ResourceLocation id, Double x) {
        if (id == null) {
            return;
        }
        EVER_TOUCHED.add(id);
        final Edit cur = LEDGER.getOrDefault(id, Edit.EMPTY);
        LEDGER.put(id, cur.asPresent().withExperience(x));
    }

    /**
     * 🆕 阶段 2：记下"输入侧要改成什么样"。
     *
     * <p>⚠️ 存的是<b>拷贝</b>（{@link ShanhaiVanillaRecipeShape#copy()}）——
     * 用户接着在界面上继续改不会回头改到这里，"整条拒收"时的回滚也才真的回得去。
     */
    public static synchronized void setShape(ResourceLocation id, ShanhaiVanillaRecipeShape shape) {
        if (id == null) {
            return;
        }
        EVER_TOUCHED.add(id);
        final Edit cur = LEDGER.getOrDefault(id, Edit.EMPTY);
        LEDGER.put(id, cur.asPresent().withShape(shape));
    }

    /** 这一条现在记着的输入形状（没有 ⇒ null）。 */
    public static synchronized ShanhaiVanillaRecipeShape shapeOf(ResourceLocation id) {
        final Edit e = id == null ? null : LEDGER.get(id);
        return e == null ? null : e.shape();
    }

    public static synchronized void markRemoved(ResourceLocation id) {
        if (id == null) {
            return;
        }
        EVER_TOUCHED.add(id);
        EVER_REMOVED.add(id);
        final Edit cur = LEDGER.getOrDefault(id, Edit.EMPTY);
        LEDGER.put(id, cur.asRemoved());
    }

    public static synchronized void clearEdit(ResourceLocation id) {
        if (id != null) {
            LEDGER.remove(id);
        }
    }

    /**
     * 把台账那一条<b>整份写回</b>（{@code e == null} = 删掉这一条）。
     *
     * <p>用途只有一个：{@code ShanhaiVanillaRecipeOps.applyEdits} 在"重建失败 ⇒ 整条拒收"时
     * <b>把台账回滚成改之前那一份</b>。分开一个方法（而不是只把 result 塞回去）是因为
     * 回滚必须<b>逐字段还原</b> —— 只还原 result 会把这一次没改的时间/经验也一起丢掉。
     */
    public static synchronized void restoreEdit(ResourceLocation id, Edit e) {
        if (id == null) {
            return;
        }
        if (e == null) {
            LEDGER.remove(id);
        } else {
            LEDGER.put(id, e);
        }
    }

    /**
     * 这条配方<b>此刻该长什么样</b>。
     *
     * <p>{@code null} = 被台账删掉了；<b>没有任何编辑 ⇒ 返回底本那个原对象本身</b>
     * （引用同一性保住 —— 没被编辑过的配方不该换对象）。
     */
    public static synchronized Recipe<?> finalRecipeOf(ResourceLocation id) {
        final Recipe<?> base = id == null ? null : BASE.get(id);
        if (base == null) {
            return null;
        }
        final Edit e = LEDGER.get(id);
        if (e == null) {
            return base;                     // 从没动过 ⇒ 原对象（引用同一性保住）
        }
        if (e.removed()) {
            return null;                     // 被我们删了
        }
        if (!e.hasAnything()) {
            return base;                     // 台账在、但没写任何一项（例如只撤销过）⇒ 仍是原对象
        }
        return ShanhaiVanillaRecipeRebuild.rebuild(base, e.result(), e.cookingTime(), e.experience(),
                e.shape());
    }

    public static synchronized String statsLine() {
        return "vanilla_base_count=" + capturedCount + " dup_ids_skipped=" + capturedDup
                + " no_id_skipped=" + capturedSkippedNoId
                + " vanilla_ledger_size=" + LEDGER.size()
                + " vanilla_ever_removed=" + EVER_REMOVED.size()
                + " captured_at=" + capturedAt;
    }
}
