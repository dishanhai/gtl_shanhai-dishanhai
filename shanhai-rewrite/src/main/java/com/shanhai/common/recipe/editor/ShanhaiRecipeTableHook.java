package com.shanhai.common.recipe.editor;

import com.shanhai.ShanhaiMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 🔴 <b>第 12 刀：把 {@code RecipeManager} 那两张表"钉死"成我们刚写下去的那一份。</b>
 *
 * <h2>为什么需要这一层（现场读数，不是推测）</h2>
 * 冒烟第 3 局（19:38）同一个 id 在同一拍里出现了<b>互相矛盾</b>的三份读数：
 * <pre>
 *   case=vanilla_remove … 三张视图：byName_has=false getRecipes_has=0 合成台命中=[minecraft:stick]
 * </pre>
 * 而按字节码 {@code getRecipes()}（{@code m_44051_}）是
 * <b>从 {@code f_44007_}（= byType，嵌套 Map）展平出来的</b> ⇒ 它和
 * {@code byType(...)}（{@code m_44054_}，同一个字段）<b>不可能给出不同的答案</b>。
 * 同一个现象在"新建"那一侧也出现过（新建的配方 {@code getRecipes()} 找得到、
 * {@code getRecipesFor(...)} 里没有）⇒ <b>这不是判据的问题，是"写回两张表"这一步没有全覆盖</b>。
 *
 * <h2>本类做什么</h2>
 * <ol>
 *   <li>{@link #readViews} —— <b>只读</b>地把两张私有表的内容读出来（诊断用，供自检打印）；</li>
 *   <li>{@link #forceWriteBack} —— 用<b>我们手里那一份全量表</b>重建两张表并写回去：
 *       {@code byType} 走 GTCEu 自己暴露的 {@code RecipeManagerAccessor#setRawRecipes}
 *       （那不是"黑反射"，是这个整合包里 GT 官方给出的写入点），
 *       {@code byName} 走一次字段反射。两份都<b>先去重</b>——
 *       {@code ImmutableMap.Builder.build()} 遇重复 id 会抛异常（前置调查已记过这条）。</li>
 * </ol>
 *
 * <h2>🔴 边界（如实写清）</h2>
 * <ul>
 *   <li>本类<b>不改</b>任何配方对象、不碰 GT 的索引树，只把"这两张表 == 我们刚写的那一份"这件事坐实；</li>
 *   <li>它<b>不</b>负责通知客户端（那是 {@code syncRecipesToClients} 的事）、也不碰覆盖文件；</li>
 *   <li>反射失败 / GT 访问器不在 ⇒ <b>只降级</b>（打一条 WARN 并返回 false），
 *       绝不抛出去影响保存这条路。</li>
 * </ul>
 */
final class ShanhaiRecipeTableHook {

    private static final String PREFIX = "[SHANHAI-EDIT] editor";

    private ShanhaiRecipeTableHook() {}

    /** 一次只读快照：两张表里各有什么。 */
    record Views(int byTypeTypes, int byTypeCraftingSize, boolean byTypeHas,
                 int byNameSize, boolean byNameHas, String note) {}

    /** 只读读两张表（{@code id} 是"要问在不在"的那一条；给 null 就只报尺寸）。 */
    static Views readViews(MinecraftServer server, ResourceLocation id) {
        if (server == null) {
            return new Views(-1, -1, false, -1, false, "server==null");
        }
        final RecipeManager rm = server.getRecipeManager();
        try {
            final Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> byType = byTypeMap(rm);
            final Map<ResourceLocation, Recipe<?>> byName = byNameMap(rm);
            if (byType == null || byName == null) {
                return new Views(-1, -1, false, byName == null ? -1 : byName.size(), false,
                        "拿不到表：byType=" + (byType == null ? "null" : "ok")
                                + " byName=" + (byName == null ? "null" : "ok"));
            }
            final Map<ResourceLocation, Recipe<?>> craft = byType.get(RecipeType.CRAFTING);
            return new Views(byType.size(), craft == null ? -1 : craft.size(),
                    id != null && craft != null && craft.containsKey(id),
                    byName.size(), id != null && byName.containsKey(id),
                    "byType_field=" + byTypeFieldName + " byName_field=" + byNameFieldName);
        } catch (Throwable t) {
            return new Views(-1, -1, false, -1, false, "threw " + t);
        }
    }

    /**
     * 🔴 用 {@code all} 这一份全量表<b>重建两张表</b>并写回去。
     *
     * @return true = 两张表都写成了；false = 降级（已在日志里说明）
     */
    static boolean forceWriteBack(MinecraftServer server, List<Recipe<?>> all) {
        if (server == null || all == null) {
            return false;
        }
        final RecipeManager rm = server.getRecipeManager();
        boolean okType = false;
        boolean okName = false;
        try {
            // 去重（ImmutableMap.Builder.build() 遇重复 id 会抛）；同 id 后写的赢（与 upsert 同口径）
            final Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> byType = new LinkedHashMap<>();
            final Map<ResourceLocation, Recipe<?>> byName = new LinkedHashMap<>();
            int dup = 0;
            for (Recipe<?> r : all) {
                if (r == null || r.getId() == null || r.getType() == null) {
                    continue;
                }
                if (byName.put(r.getId(), r) != null) {
                    dup++;
                }
                byType.computeIfAbsent(r.getType(), t -> new LinkedHashMap<>()).put(r.getId(), r);
            }
            try {
                final com.gregtechceu.gtceu.core.mixins.RecipeManagerAccessor acc =
                        (com.gregtechceu.gtceu.core.mixins.RecipeManagerAccessor) rm;
                acc.setRawRecipes(new LinkedHashMap<>(byType));
                okType = true;
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn("{} table_hook_byType_failed err={} (退回只用 replaceRecipes 的结果)",
                        PREFIX, t.toString());
            }
            final Field f = byNameField(rm);
            if (f != null) {
                f.set(rm, new LinkedHashMap<>(byName));
                okName = true;
            }
            // 🔴 第三方缓存（FastSuite 的 AuxRecipeManager）:必须打掉，否则合成台/熔炉这一拍
            //    问到的还是旧快照 —— 这就是"改完必须重进"的真正原因。
            final int cleared = invalidateCaches(server);
            // 🔴🔴 表被写过了 ⇒ 版本 +1。面板据此重建自己那份 id 列表
            //    （用户两条报障的共同根因：列表是旧快照、数据现读 ⇒ 新建的看不见 / 抹掉的剩个空壳）。
            final int ver = TABLE_VERSION.incrementAndGet();
            ShanhaiMod.LOGGER.info("{} table_hook_writeback all={} dup_ids={} byType_ok={} byName_ok={} "
                            + "types={} names={} manager={} caches_cleared={} table_version={}",
                    PREFIX, all.size(), dup, okType, okName, byType.size(), byName.size(),
                    managerClass(server), cleared, ver);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} table_hook_failed err={} (本局退回 replaceRecipes 的结果)",
                    PREFIX, t.toString());
        }
        return okType && okName;
    }

    // ---------------------------------------------------------------- 反射细节

    private static String byTypeFieldName = "(unresolved)";
    private static String byNameFieldName = "(unresolved)";

    @SuppressWarnings("unchecked")
    private static Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> byTypeMap(RecipeManager rm) {
        try {
            final com.gregtechceu.gtceu.core.mixins.RecipeManagerAccessor acc =
                    (com.gregtechceu.gtceu.core.mixins.RecipeManagerAccessor) rm;
            byTypeFieldName = "gtceu:RecipeManagerAccessor#getRawRecipes";
            return acc.getRawRecipes();
        } catch (Throwable t) {
            byTypeFieldName = "unavailable(" + t.getClass().getSimpleName() + ")";
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<ResourceLocation, Recipe<?>> byNameMap(RecipeManager rm) {
        final Field f = byNameField(rm);
        if (f == null) {
            return null;
        }
        try {
            return (Map<ResourceLocation, Recipe<?>>) f.get(rm);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code byName} 那个私有字段：开发环境叫 {@code byName}，线上叫 {@code f_199900_} —— 两个名字都试。 */
    private static Field byNameField(RecipeManager rm) {
        for (String name : new String[]{"byName", "f_199900_"}) {
            try {
                final Field f = RecipeManager.class.getDeclaredField(name);
                f.setAccessible(true);
                byNameFieldName = name;
                return f;
            } catch (Throwable ignored) {
                // 试下一个名字
            }
        }
        byNameFieldName = "unresolved";
        return null;
    }

    /** 诊断：把两张表里"这个类型下有哪些 id"打成一行（只在自检里用，别在生产路径上调）。 */
    static String describeType(MinecraftServer server, RecipeType<?> type, int limit) {
        try {
            final Map<ResourceLocation, Recipe<?>> m = byTypeMap(server.getRecipeManager()).get(type);
            if (m == null) {
                return "(no bucket)";
            }
            final List<String> ids = new ArrayList<>();
            for (ResourceLocation k : m.keySet()) {
                if (ids.size() >= limit) {
                    ids.add("…(+" + (m.size() - limit) + ")");
                    break;
                }
                ids.add(k.toString());
            }
            return ids.toString();
        } catch (Throwable t) {
            return "(threw " + t + ")";
        }
    }

    /** 诊断：byType 里那个类型桶有没有这条 id。 */
    static boolean byTypeHas(MinecraftServer server, RecipeType<?> type, ResourceLocation id) {
        try {
            final Map<ResourceLocation, Recipe<?>> m = byTypeMap(server.getRecipeManager()).get(type);
            return m != null && id != null && m.containsKey(id);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 交叉核对（第 12 刀）

    private static String byTypeRawFieldName = "(unresolved)";

    /**
     * 🔴 <b>绕过 GT 的访问器，直接反射读那个真字段</b>（开发名 {@code byType}／线上名 {@code f_44007_}）。
     *
     * <p>为什么必须交叉核对：现场出现过"GT 访问器读到的桶里明明有这条、而 {@code getRecipesFor}
     * 就是查不到它"这种<b>自相矛盾</b>的读数 ⇒ 在写任何"修好了"的结论之前，
     * 必须先证明"我读的那个东西 == {@code byType()} 读的那个东西"。
     */
    @SuppressWarnings("unchecked")
    static Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> byTypeMapRaw(RecipeManager rm) {
        for (String name : new String[]{"byType", "f_44007_"}) {
            try {
                final Field f = RecipeManager.class.getDeclaredField(name);
                f.setAccessible(true);
                byTypeRawFieldName = name;
                return (Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>>) f.get(rm);
            } catch (Throwable ignored) {
                // 试下一个名字
            }
        }
        byTypeRawFieldName = "unresolved";
        return null;
    }

    /** 交叉核对行：GT 访问器 vs 真字段，以及"这条在不在桶里"。 */
    static String crossCheck(MinecraftServer server, RecipeType<?> type, ResourceLocation id) {
        try {
            final RecipeManager rm = server.getRecipeManager();
            final Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> viaGt = byTypeMap(rm);
            final Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> viaRaw = byTypeMapRaw(rm);
            final boolean gt = viaGt != null && viaGt.get(type) != null && id != null
                    && viaGt.get(type).containsKey(id);
            final boolean raw = viaRaw != null && viaRaw.get(type) != null && id != null
                    && viaRaw.get(type).containsKey(id);
            return "GT访问器_has=" + gt + "(" + (viaGt == null ? "null" : viaGt.size()) + "类)"
                    + " 真字段(" + byTypeRawFieldName + ")_has=" + raw
                    + "(" + (viaRaw == null ? "null" : viaRaw.size()) + "类)"
                    + " 同一个对象=" + (viaGt == viaRaw);
        } catch (Throwable t) {
            return "crossCheck threw " + t;
        }
    }

    /**
     * 🔴 <b>把 {@code getRecipesFor} 那条流水线原样自己跑一遍</b>，并与官方那条对答案。
     *
     * <p>现场出现过"桶里有它、它自己也说 matches=true，可 {@code getRecipesFor} 就是不返回它"这种
     * <b>逻辑上不该发生</b>的读数 ⇒ 这里把三件事分开问：
     * ① 官方 {@code getRecipesFor} 返回什么；② 我自己照着它的语义过一遍桶返回什么；
     * ③ 桶里那个对象是<b>不是</b>我没问过的那一个（同 id 不同对象 ⇒ 全都能解释通）。
     */
    static String replicateQuery(MinecraftServer server, RecipeType<?> type,
                                 net.minecraft.world.Container container, ResourceLocation id) {
        try {
            final RecipeManager rm = server.getRecipeManager();
            final Map<ResourceLocation, Recipe<?>> bucket = byTypeMapRaw(rm).get(type);
            if (bucket == null) {
                return "桶不存在";
            }
            final Recipe<?> inBucket = id == null ? null : bucket.get(id);
            final Recipe<?> byNameObj = id == null ? null : rm.byKey(id).orElse(null);
            final java.util.List<String> manual = new ArrayList<>();
            for (Map.Entry<ResourceLocation, Recipe<?>> e : bucket.entrySet()) {
                try {
                    @SuppressWarnings("unchecked")
                    final Recipe<net.minecraft.world.Container> rr =
                            (Recipe<net.minecraft.world.Container>) e.getValue();
                    if (rr.matches(container, server.overworld())) {
                        manual.add(e.getKey().toString());
                    }
                } catch (Throwable ignored) {
                    // 某一条自己抛了（例如 trimming）不影响别的
                }
            }
            final java.util.List<String> official = new ArrayList<>();
            // ⚠️ `<C extends Container, T extends Recipe<C>>` 的通配在显式参数下无法推导 ⇒ 按原始类型调
            @SuppressWarnings({"unchecked", "rawtypes"})
            final java.util.List<Recipe<?>> off = (java.util.List) rm.getRecipesFor(
                    (RecipeType) type, container, server.overworld());
            for (Recipe<?> r : off) {
                official.add(r.getId().toString());
            }
            final boolean bucketMatches;
            try {
                @SuppressWarnings("unchecked")
                final Recipe<net.minecraft.world.Container> rr =
                        (Recipe<net.minecraft.world.Container>) inBucket;
                bucketMatches = inBucket != null && rr.matches(container, server.overworld());
            } catch (Throwable t) {
                return "桶里那条 matches 抛了 " + t;
            }
            return "桶大小=" + bucket.size()
                    + " 桶里有这条=" + (inBucket != null)
                    + " 桶里那条.matches=" + bucketMatches
                    + " 桶里那条==byName那条:" + (inBucket != null && inBucket == byNameObj)
                    + " | 官方getRecipesFor=" + official + " 我手跑同一语义=" + manual;
        } catch (Throwable t) {
            return "replicateQuery threw " + t;
        }
    }

    // ---------------------------------------------------------------- 第三方配方缓存（第 12 刀）

    /** 运行期的 {@code RecipeManager} 到底是哪个类（现场实测：本整合包是 FastSuite 的缓存子类）。 */
    static String managerClass(MinecraftServer server) {
        try {
            return server.getRecipeManager().getClass().getName();
        } catch (Throwable t) {
            return "(threw " + t + ")";
        }
    }

    /**
     * 🔴🔴 <b>把第三方"配方查询缓存"打掉 —— 这是"改完立刻生效"最后一公里的真正堵点。</b>
     *
     * <h2>现场证据（不是推测）</h2>
     * <pre>
     * 运行期 RecipeManager 的真实类 = dev.shadowsoffire.fastsuite.AuxRecipeManager（extends RecipeManager）
     *   public List&lt;T&gt; m_44056_(RecipeType, Container, Level)   ← getRecipesFor 被<b>覆盖成走缓存</b>
     *   private final Map&lt;RecipeType&lt;?&gt;, CachedRecipeList&lt;?,?&gt;&gt; cachedRecipeListMap
     * </pre>
     * 同一个 tick 里的三份读数：
     * <pre>
     *   真字段 f_44007_.CRAFTING 里有这条 = true ；桶里那条.matches(石头) = true
     *   我照官方语义手跑一遍 = [我们的探针, minecraft:stone_button]   ← 有
     *   官方 getRecipesFor   = [minecraft:stone_button]              ← 没有（走的是缓存快照）
     * </pre>
     * ⇒ 我们的 {@code replaceRecipes} 只更新了<b>字段</b>（和 {@code getRecipes()} 那条路），
     * 而<b>合成台/熔炉问的是被覆盖过的 {@code getRecipesFor}/{@code byType}（缓存快照）</b>
     * ⇒ 界面上就是"改完不实时、必须重进"（重进会重建缓存 ⇒ 就对了）。
     *
     * <h2>本方法做什么</h2>
     * 沿着管理器类（含父类）找<b>名字里带 cache 的 Map 字段</b>，{@code clear()} 掉；
     * 下一次查询它会自己用新表重建。返回清掉了几张缓存（0 = 这个包里没有此类缓存，属正常）。
     *
     * <h2>边界</h2>
     * 只 {@code clear()}，<b>不删字段、不改值</b>；反射失败只 WARN 降级，绝不抛出。
     */
    static int invalidateCaches(MinecraftServer server) {
        int cleared = 0;
        try {
            Class<?> c = server.getRecipeManager().getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (!Map.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                    final String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                    if (!n.contains("cache")) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        final Object v = f.get(server.getRecipeManager());
                        if (v instanceof Map<?, ?> m && !m.isEmpty()) {
                            final int n0 = m.size();
                            ((Map<?, ?>) m).clear();
                            cleared++;
                            ShanhaiMod.LOGGER.info("{} recipe_cache_cleared field={}.{} entries={}",
                                    PREFIX, c.getSimpleName(), f.getName(), n0);
                        }
                    } catch (Throwable t) {
                        ShanhaiMod.LOGGER.warn("{} recipe_cache_clear_failed field={}.{} err={}",
                                PREFIX, c.getSimpleName(), f.getName(), t.toString());
                    }
                }
                c = c.getSuperclass();
            }
            if (cleared == 0) {
                ShanhaiMod.LOGGER.info("{} recipe_cache_none manager={}（这个包里没有第三方配方缓存，或它不是 Map 形态）",
                        PREFIX, managerClass(server));
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} recipe_cache_probe_failed err={}", PREFIX, t.toString());
        }
        return cleared;
    }

    private static final java.util.concurrent.atomic.AtomicInteger TABLE_VERSION =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 读数：表被写过几次（面板据此判断"要不要重建自己那份列表"）。 */
    public static int tableVersion() {
        return TABLE_VERSION.get();
    }

    /** 未用到的占位（保持 import 干净）。 */
    static Map<ResourceLocation, Recipe<?>> empty() {
        return new HashMap<>();
    }
}
