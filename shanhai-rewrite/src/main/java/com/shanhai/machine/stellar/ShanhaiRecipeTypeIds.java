package com.shanhai.machine.stellar;

import com.gregtechceu.gtceu.api.registry.GTRegistries;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

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
 *   <li><b>别名表</b>（本文件唯一的别名来源）—— {@code smelting}/{@code furnace} ⇒ {@code gtceu:electric_furnace}；</li>
 *   <li>没写命名空间 ⇒ 补 {@code gtceu:}（GT 的配方类型绝大多数在这个命名空间）；</li>
 *   <li>{@link ResourceLocation#tryParse} 兜底：解析不出 ⇒ 返回 {@code ""}（= 当没有），绝不返回半成品。</li>
 * </ol>
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
        String alias = ALIASES.get(aliasKey(s));
        if (alias != null) {
            return alias;
        }
        String normalized = s.toLowerCase(Locale.ROOT).replace('-', '_');
        if (normalized.indexOf(':') < 0) {
            normalized = DEFAULT_NAMESPACE + ":" + normalized;
        }
        final ResourceLocation id = ResourceLocation.tryParse(normalized);
        return id == null ? "" : id.toString();
    }

    /**
     * 从一条<b>配方 id</b> 里推出配方类型 id：取第一个 {@code '/'} 之前那一段。
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
