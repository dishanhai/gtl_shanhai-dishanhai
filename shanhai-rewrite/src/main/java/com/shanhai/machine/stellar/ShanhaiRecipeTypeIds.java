package com.shanhai.machine.stellar;

import com.gregtechceu.gtceu.api.registry.GTRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 星律样板总成的<b>配方类型 id 词法层</b>：别名表 + 归一化 + 「这个 id 真的存在吗」的校验。
 *
 * <h2>一、键名常量（四来源里的 ②③④；① 走 gtlcore 自己的公开工具，见 reader）</h2>
 * <pre>
 *   gt_shanhai_recipe_type                 // ② 类型 id（母槽中间配置写的就是这个键）
 *   gt_shanhai_recipe_id                   // ③ 具体配方 id，形如 "gtceu:electric_furnace/aluminium_ingot"
 *   gt_shanhai_recipe_type_authoritative   // ④ 权威声明（本轮按「也可能直接带一个类型 id」处理，见 reader）
 * </pre>
 * ⚠️ 这三个键<b>在本仓库源码与 kubejs 脚本里都不存在</b>（本轮 grep 全库：0 命中，只有
 * {@code gtlcore.patternQuickUploadRecipeTypes} 一处命中）⇒ 它们是<b>宿主/上游</b>侧的约定。
 * 所以本类的读法一律<b>防御式</b>：认 String、也认 ListTag&lt;String&gt;，认不出就当没有，<b>绝不猜</b>。
 *
 * <h2>二、归一化的四步（顺序不能换）</h2>
 * <ol>
 *   <li>去空白、去掉可能残留的一对双引号；</li>
 *   <li><b>① 已是可用 id</b> —— 形如 {@code ns:path}、{@link ResourceLocation#tryParse} 能解析、
 *       <b>并且</b>{@link #exists(String)} 为真 ⇒ <b>原样返回</b>（<b>不做任何二次转换</b>）；</li>
 *   <li><b>② 别名表</b>（本文件唯一的别名来源）—— {@code smelting}/{@code furnace} ⇒ {@code gtceu:electric_furnace}；</li>
 *   <li><b>③ 显示名反查</b>（2026-11 新增，见 §二½）—— 中文名 / 英文显示名 ⇒ 类型 id；</li>
 *   <li><b>④ 老兜底</b> —— 没写命名空间 ⇒ 补 {@code gtceu:}；{@link ResourceLocation#tryParse} 兜底：
 *       解析不出 ⇒ 返回 {@code ""}（= 当没有），绝不返回半成品。</li>
 * </ol>
 *
 * <h2>二½、🔴 为什么必须加「③ 显示名反查」（本轮实机证据）</h2>
 * <pre>
 *   17:35:26 [SHANHAI-STELLAR] XRECIPE slot=3 … slotType=none … verdict=NO_BIND
 *   17:35:31 [SHANHAI-WHY] #6 产物=shanhai:light_voyage × 8 声明类型=unknown
 * </pre>
 * 而玩家界面上的类型胶囊明明写着「原初因果编织」。老的四步里<b>没有一步认中文</b>：
 * {@code 原初因果编织} ⇒ 补成 {@code gtceu:原初因果编织} ⇒ {@code tryParse} 必失败
 * （{@code ResourceLocation} 的 path 只允许 {@code [a-z0-9_.-]}）⇒ 返回 {@code ""} ⇒ NONE。
 * 于是新增第 ③ 步：拿<b>宿主真实注册表 + 语言表</b>现建一张
 * 「显示名 → id」反查表（{@link ShanhaiRecipeTypeNameIndex}），整串相等才命中，
 * <b>查不到就继续走 ④ / 最终返回 NONE，绝不猜</b>。
 * <p>⚠️ 这张表<b>一个中文都没有手写</b>：键来自 {@code GTRegistries.RECIPE_TYPES}，
 * 值来自 {@link ShanhaiRecipeTypeLabels#displayName}（上游 5 级 lang 回退链）。
 * <p>⚠️ 语言表没就绪（例如专用服务器）⇒ 表建出来是空的 ⇒ <b>不缓存</b>、按
 * {@link #NAME_INDEX_RETRY_MS} 限频重试；此时行为与加这一步之前<b>完全一致</b>（反查这一路永远查不到）。
 *
 * <p>🔴 「-」会被折成「_」：GT 的配方类型 id 全部是下划线（已用 {@code RecipeTypeCatalog} 的注册表枚举证过），
 * 而玩家手输 {@code electric-furnace} 的本意必然是 {@code electric_furnace}。
 * 代价：真有以「-」命名的第三方类型会被折错 —— 这种类型在本环境里<b>没有</b>，若将来出现，
 * 只需在别名表里加一条同名映射把它救回来。
 *
 * <h2>三、校验（只用于日志/诊断，不用来做闸）</h2>
 * {@link #exists(String)} 查 {@code GTRegistries.RECIPE_TYPES.keys()}。
 * 查不到的类型 id <b>照样原样返回</b>，由 {@link ShanhaiPatternTypeReader} 标成 {@code unverified}
 * ——「不存在」与「拼错」在阶段二不做拦截（那是阶段三闸的事）。
 */
public final class ShanhaiRecipeTypeIds {

    /** ② 类型 id（母槽中间配置写入/清除的就是它）。 */
    public static final String KEY_RECIPE_TYPE = "gt_shanhai_recipe_type";

    /** ③ 具体配方 id。 */
    public static final String KEY_RECIPE_ID = "gt_shanhai_recipe_id";

    /** ④ 权威声明。 */
    public static final String KEY_AUTHORITATIVE = "gt_shanhai_recipe_type_authoritative";

    /** 没写命名空间时的默认命名空间。 */
    public static final String DEFAULT_NAMESPACE = "gtceu";

    /**
     * 别名表 —— <b>本工程里唯一允许出现「A 就是 B」这类断言的地方</b>。
     *
     * <p>键 = {@link #aliasKey(String)} 归一化后的写法（小写 + 「-」折成「_」）；
     * 值 = 目标类型的<b>完整 id</b>。
     *
     * <p>⚠️ 加条目时必须给出依据（哪个模组/哪条配置写的是这个名字），
     * <b>不许凭印象加</b>——别名写错比不写更糟：它会让一台机器静默按错的配方类型跑。
     */
    private static final Map<String, String> ALIASES = Map.of(
            // 用户/父代理点名的例子：vanilla 熔炉（smelting）= GT 的电力炉。
            "smelting", "gtceu:electric_furnace",
            // 同义缩写（同一个来源的另一种写法）。
            "furnace", "gtceu:electric_furnace",
            // 去掉下划线的写法（玩家手输常见）。
            "electricfurnace", "gtceu:electric_furnace");

    private ShanhaiRecipeTypeIds() {}

    /** 别名表的键归一化（只给查表用，不参与最终 id 的拼装）。 */
    public static String aliasKey(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    /**
     * 把「玩家/别的模组写下来的任意字符串」归一化成一个<b>可用的</b>配方类型 id。
     *
     * <p>四步见类注释 §二：① 已是可用 id ⇒ 原样；② 别名表；③ 显示名反查（中文/英文）；
     * ④ 老兜底（补命名空间 + {@code tryParse}）。
     *
     * @return 归一化后的 id；{@code ""} = 这条值不可用（当它不存在，由调用方决定「没有」还是「歧义」）
     */
    public static String canonical(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        // 有人把 JSON 字符串整段写进 NBT 时，会带上成对的双引号。
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.isEmpty()) {
            return "";
        }

        // ---- ① 已经是「能解析 + 宿主真有」的类型 id ⇒ 原样返回（🔴 不做二次转换）
        final String asId = usableIdForm(s);
        if (!asId.isEmpty()) {
            return asId;
        }

        // ---- ② 别名表（位置与语义都保持原样，老行为不能regress）
        String alias = ALIASES.get(aliasKey(s));
        if (alias != null) {
            return alias;
        }

        // ---- ③ 显示名反查：中文名 / 英文显示名 ⇒ 类型 id（表 = 注册表 + lang 现建，不写死名字）
        final String byName = ShanhaiRecipeTypeNameIndex.lookup(nameIndexOf(), s);
        if (!byName.isEmpty()) {
            return byName;
        }

        // ---- ④ 老兜底：小写 + 「-」折「_」 + 没命名空间补 gtceu: + tryParse
        String normalized = s.toLowerCase(Locale.ROOT).replace('-', '_');
        if (normalized.indexOf(':') < 0) {
            normalized = DEFAULT_NAMESPACE + ":" + normalized;
        }
        final ResourceLocation id = ResourceLocation.tryParse(normalized);
        return id == null ? "" : id.toString();
    }

    /**
     * 第 ① 步：「形如 {@code ns:path}、能解析、且宿主真有这个类型」⇒ 返回它的规范写法（= 原样）；否则 {@code ""}。
     *
     * <p>⚠️ 必须带冒号：不带冒号的裸串（{@code assembler}）会按 {@code minecraft:} 命名空间解析，
     * 那样直接采信就等于<b>把「组装机」当成 {@code minecraft:assembler}</b> —— 所以这里只认显式命名空间。
     */
    private static String usableIdForm(@Nullable String s) {
        if (s == null || s.isEmpty() || s.indexOf(':') <= 0) {
            return "";
        }
        try {
            final ResourceLocation id = ResourceLocation.tryParse(s);
            if (id == null) {
                return "";
            }
            return exists(id.toString()) ? id.toString() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    // ============================================================ ③ 的支撑：显示名反查表

    /** 反查表（{@code null} = 还没建过；建出来是空表时<b>不进这里</b>，见 {@link #nameIndexOf()}）。 */
    private static volatile Map<String, String> nameIndex;

    /** 上次尝试建表的毫秒时刻（空表不缓存 ⇒ 靠它限频，避免每 tick 枚举一遍注册表 + 语言表）。 */
    private static volatile long nameIndexLastAttemptMs = Long.MIN_VALUE;

    /** 「表是空的 / 建表失败」的告警只打一次。 */
    private static volatile boolean nameIndexWarned;

    /** 空表时的重试间隔（毫秒）；语言表可能晚于本类首次被调用而就绪。 */
    static final long NAME_INDEX_RETRY_MS = 5000L;

    /**
     * 显示名反查表（懒建 + 缓存）。
     *
     * <h2>怎么来的（🔴 一行中文都不手写）</h2>
     * <pre>
     *   for (entry : GTRegistries.RECIPE_TYPES.entries())          // 宿主真实存在的类型
     *       name = ShanhaiRecipeTypeLabels.displayName(ns, path, key -> Component.translatable(key).getString())
     *       if (name 有译文 &amp;&amp; name ≠ path)  entries.add(id, name)
     *   map = ShanhaiRecipeTypeNameIndex.build(entries)             // 重名 ⇒ 该名字整体作废（不猜）
     * </pre>
     *
     * <h2>🔴 为什么「空表不缓存」</h2>
     * 专用服务器上客户端语言表不存在 ⇒ 每一级 {@code Component.translatable(k).getString()} 都返回键本身
     * ⇒ {@code displayName} 退化成 {@code path} ⇒ <b>表必然是空的</b>。若把空表缓存下来，
     * 一旦首次调用发生在语言表就绪之前（整合服/资源重载），反查就<b>永久失效</b>。
     * 所以：空 ⇒ 不缓存 + 按 {@link #NAME_INDEX_RETRY_MS} 限频重试（与 {@code RecipeTypeCatalog} 同一条纪律）。
     * <p>任何异常（注册表未就绪 / gtlcore 缺席）⇒ 返回空表，<b>不抛</b>（本类会在 tick 与渲染路径上被调用）。
     */
    private static @NotNull Map<String, String> nameIndexOf() {
        final Map<String, String> cached = nameIndex;
        if (cached != null) {
            return cached;
        }
        final long now = System.currentTimeMillis();
        if (nameIndexLastAttemptMs != Long.MIN_VALUE && now - nameIndexLastAttemptMs < NAME_INDEX_RETRY_MS) {
            return Map.of();
        }
        synchronized (ShanhaiRecipeTypeIds.class) {
            if (nameIndex != null) {
                return nameIndex;
            }
            nameIndexLastAttemptMs = System.currentTimeMillis();
            try {
                final List<ShanhaiRecipeTypeNameIndex.Entry> entries = new ArrayList<>();
                for (Map.Entry<ResourceLocation, ?> entry : GTRegistries.RECIPE_TYPES.entries()) {
                    final ResourceLocation id = entry == null ? null : entry.getKey();
                    if (id == null) {
                        continue;
                    }
                    final String name = ShanhaiRecipeTypeLabels.displayName(id.getNamespace(), id.getPath(),
                            key -> Component.translatable(key).getString());
                    if (name == null || name.isEmpty()
                            || name.equals(id.getPath())
                            || ShanhaiRecipeTypeLabels.UNKNOWN.equals(name)) {
                        // 没有译文（服务器/键缺失）：这个 id 不进反查表 —— 它没有任何「名字」可言
                        continue;
                    }
                    entries.add(new ShanhaiRecipeTypeNameIndex.Entry(id.toString(), name));
                }
                if (entries.isEmpty()) {
                    if (!nameIndexWarned) {
                        nameIndexWarned = true;
                        ShanhaiStellarLog.warn("配方类型显示名反查表为空（语言表还没就绪？）⇒ 本轮不缓存，"
                                + "{} ms 后重试；在此之前「中文名 ⇒ id」这条容错不生效（老路径不变）",
                                Long.valueOf(NAME_INDEX_RETRY_MS));
                    }
                    return Map.of();
                }
                final Map<String, String> built = ShanhaiRecipeTypeNameIndex.build(entries);
                nameIndex = built;
                final Map<String, List<String>> clash = ShanhaiRecipeTypeNameIndex.ambiguousNames(entries);
                ShanhaiStellarLog.info("配方类型显示名反查表已建 count={}（来源=GTRegistries.RECIPE_TYPES × lang；重名作废 {} 个）",
                        Integer.valueOf(ShanhaiRecipeTypeNameIndex.size(built)), Integer.valueOf(clash.size()));
                return built;
            } catch (Throwable t) {
                if (!nameIndexWarned) {
                    nameIndexWarned = true;
                    ShanhaiStellarLog.warn("配方类型显示名反查表建表失败（按空表处理，老路径不变）：{}",
                            String.valueOf(t));
                }
                return Map.of();
            }
        }
    }

    /** 反查表当前有多少条（日志/诊断读数用；会触发一次懒建）。 */
    public static int nameIndexSize() {
        return ShanhaiRecipeTypeNameIndex.size(nameIndexOf());
    }

    /**
     * <b>只给离线自证用</b>：直接指定反查表（{@code null} = 清掉、恢复按注册表 + 语言表现建）。
     *
     * <p>存在的理由：{@code GTRegistries.RECIPE_TYPES} 与语言表都要<b>运行期</b>才就绪，
     * 而「中文名 ⇒ id」这条容错必须能在<b>裸 JVM</b> 里被实跑断言（含负对照）。
     * 生产代码<b>不许</b>调它。
     */
    public static void probeSetNameIndex(@Nullable List<ShanhaiRecipeTypeNameIndex.Entry> entries) {
        if (entries == null) {
            nameIndex = null;
            nameIndexLastAttemptMs = Long.MIN_VALUE;
            nameIndexWarned = false;
        } else {
            nameIndex = ShanhaiRecipeTypeNameIndex.build(entries);
            nameIndexLastAttemptMs = System.currentTimeMillis();
        }
    }

    /** 从一条<b>配方 id</b> 里推出配方类型 id：取第一个 {@code '/'} 之前那一段。
     *
     * <p>依据：上游的 {@code PatternRecipeIdMatchPolicy.hasTypePrefix(typeId, recipeId)} 就是按这个
     * 「类型 id 是配方 id 的前缀」约定做匹配的（本工程 {@code temp/stellar-ground/upstream-*} 里可查）。
     * <p>推不出（没有 {@code '/'}、或前缀不是合法 id）⇒ {@code ""}，<b>绝不拿整条 recipeId 当类型</b>。
     */
    public static String typeIdFromRecipeId(@Nullable String recipeId) {
        if (recipeId == null) {
            return "";
        }
        String s = recipeId.trim();
        final int slash = s.indexOf('/');
        if (slash <= 0) {
            return "";
        }
        return canonical(s.substring(0, slash));
    }

    /**
     * 这个类型 id 在 GTCEu 的配方类型注册表里<b>真的存在吗</b>。
     *
     * <p>只用于诊断（把「拼错/不存在」标出来），不参与采信判断。
     * 任何异常（注册表未就绪、拿不到）一律返回 {@code false}，<b>不抛</b>。
     */
    public static boolean exists(@Nullable String typeId) {
        if (typeId == null || typeId.isEmpty()) {
            return false;
        }
        try {
            final ResourceLocation id = ResourceLocation.tryParse(typeId);
            if (id == null) {
                return false;
            }
            // javap：GTRegistry.RL 有 keys()/get(K)/entries()，没有 containsKey ⇒ 用 keys()。
            return GTRegistries.RECIPE_TYPES.keys().contains(id);
        } catch (Throwable t) {
            return false;
        }
    }
}
