package com.shanhai.client.ptype;

import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.shanhai.ShanhaiMod;
import com.shanhai.machine.stellar.ShanhaiRecipeTypeLabels;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「真实存在的 GT 配方类型」清单。
 *
 * <h2>1. 数据来源（不写死任何清单）</h2>
 * 唯一来源是 GTCEu 自己的<b>配方类型注册表</b>：
 * <pre>
 *   com.gregtechceu.gtceu.api.registry.GTRegistries.RECIPE_TYPES
 *     -> GTRegistry&lt;ResourceLocation, GTRecipeType&gt;
 *     -> entries() : Set&lt;Map.Entry&lt;ResourceLocation, GTRecipeType&gt;&gt;   // 键 = 配方类型 id
 * </pre>
 * javap 实证：{@code GTRegistries.RECIPE_TYPES} 是 {@code public static final GTRegistry$RL&lt;GTRecipeType&gt;}，
 * 基类 {@code GTRegistry} 有 {@code public Set<Map.Entry<K,V>> entries()}。
 * ⇒ GTCEu + gtladditions + 本模组自己注册的配方类型会<b>自动</b>全部出现在列表里，
 * 上游删/加类型时本界面不需要改一行代码。
 *
 * <h2>2. 中文名怎么来（回退链复用本工程已有实现）</h2>
 * 走 {@link ShanhaiRecipeTypeLabels#displayName} —— 它是上游 {@code wildcardRecipeTypeDisplayName}
 * 逐字照抄的 5 级 lang 键回退（{@code ns.path} → {@code gtceu.path} → {@code gtceu.recipe_type.path}
 * → {@code recipe_type.path} → {@code gtceu.recipe_type.ns.path}），判据是「译文 ≠ 键」。
 * 之后再用 gtlcore 的公开工具 {@code PatternQuickUploadMetadata.recipeTypeName(id)} 兜一层
 * （它的键就是第 ① 级，与 JEI 快速上传同源），都拿不到就<b>直接显示 {@code ns:path}</b>。
 * <p>{@code gtceu} 的 {@code assets/gtceu/lang/zh_cn.json} 里正好就是 {@code "gtceu.macerator": "研磨机"}
 * 这种键（已实证读 jar：assembler/chemical_reactor/distillery/electric_furnace/macerator 全部命中）。
 *
 * <h2>3. 排序</h2>
 * 按<b>中文名</b>排序：用 {@link Collator}（{@code Locale.CHINA}，JDK/CLDR 中文排序＝拼音序），
 * 拿不到时退回 {@code String.CASE_INSENSITIVE_ORDER}，同名再按 id 字典序，保证顺序<b>稳定可复现</b>。
 * <p>⚠️ {@link Collator} <b>不是线程安全</b>的：本类只在客户端渲染线程用（单人单线程），
 * 且实例是 {@code static final} 只建一次（{@code Collator.getInstance} 每次调用都不便宜，
 * 逐次比较各建一个会让排序慢到秒级）。
 */
@OnlyIn(Dist.CLIENT)
public final class RecipeTypeCatalog {

    /** 一条候选：id + 已解析好的中文名（名字只解析一次，列表每帧要用）。 */
    public record Entry(ResourceLocation id, String name) {}

    private static final Collator COLLATOR = makeCollator();

    /**
     * 名字缓存（id → 中文名）。列表每帧要画十几行、胶囊每帧要一个名字，
     * 每次都走一遍 gtlcore 的 translatable + 语言表查询会白白分配。
     * <p>⚠️ 语言可以运行期切换（资源重载）⇒ 缓存必须能被清，见 {@link #invalidateNames()}
     * （由 {@code ScreenEvent.Init.Post} 触发，切语言必然伴随一次界面重 init）。
     */
    private static final Map<ResourceLocation, String> NAME_CACHE = new java.util.HashMap<>();

    private static List<Entry> all;
    private static boolean buildFailed;
    /** 「枚举到 0 条」这条 warn 只打一次。 */
    private static boolean emptyWarned;
    /** 名字缓存被清过 ⇒ {@link #all()} 里烘焙的旧名字也不可信，要重建一次列表。 */
    private static boolean namesDirty;

    private RecipeTypeCatalog() {}

    private static Collator makeCollator() {
        try {
            Collator collator = Collator.getInstance(Locale.CHINA);
            collator.setStrength(Collator.SECONDARY);
            return collator;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 全量清单（懒加载 + 缓存；注册表运行期冻结，所以缓存一次就够）。
     *
     * <p>唯一会让它重建的情况是 {@link #invalidateNames()}（切语言）——
     * 那时<b>不打</b>那条 INFO，只打一条 DEBUG，避免界面一开就刷屏。
     *
     * @return 不可变列表；枚举失败时为<b>空列表</b>（界面显示「读取失败」，不抛异常）
     */
    public static List<Entry> all() {
        List<Entry> cached = all;
        if (cached != null && !namesDirty) {
            return cached;
        }
        if (cached == null && buildFailed) {
            return Collections.emptyList();
        }
        boolean rebuildOnly = namesDirty;
        try {
            List<Entry> list = new ArrayList<>();
            for (Map.Entry<ResourceLocation, GTRecipeType> entry : GTRegistries.RECIPE_TYPES.entries()) {
                ResourceLocation id = entry.getKey();
                if (id == null) {
                    continue;
                }
                list.add(new Entry(id, nameOf(id)));
            }
            list.sort(RecipeTypeCatalog::compare);
            if (list.isEmpty()) {
                // 空结果【不缓存】：注册表还没填满（理论上不可能，因为要先进终端）时，
                // 缓存空列表会让界面永远显示「读取失败」。下次再试一次即可。
                if (!emptyWarned) {
                    emptyWarned = true;
                    ShanhaiMod.LOGGER.warn("{} 配方类型枚举结果为 0 条（GTRegistries.RECIPE_TYPES 为空）"
                            + "⇒ 不缓存，下次重新读取", PatternTypeSupport.PREFIX);
                }
                return Collections.emptyList();
            }
            all = Collections.unmodifiableList(list);
            namesDirty = false;
            if (rebuildOnly) {
                ShanhaiMod.LOGGER.debug("{} 配方类型列表已按新语言重建 count={}",
                        PatternTypeSupport.PREFIX, all.size());
            } else {
                ShanhaiMod.LOGGER.info("{} 配方类型已枚举 count={}（来源=GTRegistries.RECIPE_TYPES；按中文名排序）",
                        PatternTypeSupport.PREFIX, all.size());
            }
            return all;
        } catch (Throwable t) {
            namesDirty = false;
            buildFailed = true;
            all = null;
            ShanhaiMod.LOGGER.error("{} 枚举配方类型失败（GTRegistries.RECIPE_TYPES 不可读）⇒ 列表按空处理",
                    PatternTypeSupport.PREFIX, t);
            return Collections.emptyList();
        }
    }

    /** 中文名（解析顺序：既有 5 级 lang 回退链 → gtlcore 的同一套键 → {@code ns:path}；带缓存）。 */
    public static String nameOf(ResourceLocation id) {
        if (id == null) {
            return "?";
        }
        String cached = NAME_CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        String resolved = resolveName(id);
        NAME_CACHE.put(id, resolved);
        return resolved;
    }

    /**
     * 名字解析（<b>回退链复用本工程已有实现，不另写一套</b>）。
     *
     * <ol>
     *   <li>{@link ShanhaiRecipeTypeLabels#displayName} —— 上游
     *       {@code wildcardRecipeTypeDisplayName} 逐字照抄的 5 级键：
     *       {@code ns.path}（= {@code toLanguageKey()}）→ {@code gtceu.path}
     *       → {@code gtceu.recipe_type.path} → {@code recipe_type.path}
     *       → {@code gtceu.recipe_type.ns.path}；判据是「译文 ≠ 键」；</li>
     *   <li>gtlcore 的 {@code PatternQuickUploadMetadata.recipeTypeName(id)}（与 JEI 快速上传同源，
     *       它的键其实就是第 ①级；放在这里是为了"上游改键时我们跟着改"的兼容位）；</li>
     *   <li>都拿不到 ⇒ <b>直接显示 {@code ns:path}</b>（用户口径：「取不到中文名就显示 ns:path」）。
     *       旧实现在这里会 {@link #prettify} 成英文，与实际显示预期不符，已不再用于本界面。</li>
     * </ol>
     */
    private static String resolveName(ResourceLocation id) {
        String chained = ShanhaiRecipeTypeLabels.displayName(id.getNamespace(), id.getPath(),
                key -> Component.translatable(key).getString());
        if (!chained.equals(id.getPath()) && !ShanhaiRecipeTypeLabels.UNKNOWN.equals(chained)) {
            return chained;
        }
        String key = id.getNamespace() + "." + id.getPath();
        try {
            Component component = org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadMetadata
                    .recipeTypeName(id);
            String translated = component == null ? null : component.getString();
            if (translated != null && !translated.isEmpty() && !key.equals(translated)) {
                return translated;
            }
        } catch (Throwable ignored) {
            // gtlcore 缺席 / 该 id 让它抛异常 ⇒ 走下面的 ns:path
        }
        return id.toString();
    }

    /**
     * 清空名字缓存（语言/资源重载后必须调，否则名字会停在旧语言上）。
     * <p>不立刻重建列表：只打脏标记，等下一次 {@link #all()} 时再重建（构建一次约 300 条）。
     */
    public static void invalidateNames() {
        NAME_CACHE.clear();
        namesDirty = all != null;
    }

    /** 键不存在时的名字兜底：{@code gtceu:large_chemical_reactor} → {@code Large Chemical Reactor}。 */
    public static String prettify(ResourceLocation id) {
        String path = id.getPath().replace('_', ' ');
        StringBuilder builder = new StringBuilder(path.length());
        boolean capitalize = true;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (capitalize && Character.isLetter(c)) {
                builder.append(Character.toUpperCase(c));
                capitalize = false;
            } else {
                builder.append(c);
                if (c == ' ') {
                    capitalize = true;
                }
            }
        }
        String base = builder.toString().trim();
        return "gtceu".equals(id.getNamespace()) ? base : id.getNamespace() + ": " + base;
    }

    /** 按查询串过滤（空查询 = 原样返回）。匹配中文名 / 完整 id / id 路径（下划线当空格）。 */
    public static List<Entry> filter(List<Entry> source, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return source;
        }
        List<Entry> out = new ArrayList<>();
        for (Entry entry : source) {
            if (entry.name().toLowerCase(Locale.ROOT).contains(q)
                    || entry.id().toString().toLowerCase(Locale.ROOT).contains(q)
                    || entry.id().getPath().replace('_', ' ').toLowerCase(Locale.ROOT).contains(q)) {
                out.add(entry);
            }
        }
        return out;
    }

    private static int compare(Entry a, Entry b) {
        Collator collator = COLLATOR;
        int result = collator != null
                ? collator.compare(a.name(), b.name())
                : String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name());
        return result != 0 ? result : a.id().toString().compareTo(b.id().toString());
    }
}
