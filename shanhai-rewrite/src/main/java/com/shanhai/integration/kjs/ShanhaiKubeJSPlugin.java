package com.shanhai.integration.kjs;

import com.shanhai.ShanhaiMod;
import com.shanhai.machine.module.ModuleLevelCondition;

import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingsEvent;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.kubejs.util.ClassFilter;

/**
 * 山海重构的 KubeJS 集成插件 —— 把 {@link ModuleLevelCondition} 暴露给脚本。
 *
 * <h2>1. 为什么需要这个类（KubeJS 脚本无法自己注册 RecipeCondition）</h2>
 * <p>取证（jar 字节码扫描，不是推断）：{@code kubejs-forge-2001.6.5-build.26.jar} 里
 * <b>没有任何</b> {@code recipe/condition} 注册类 ⇒ 脚本侧<b>没有</b>注册 {@code RecipeConditionType} 的手段。
 * 而条件类型必须进 {@code GTRegistries.RECIPE_CONDITIONS}（{@code GTRecipeSchema.CONDITIONS} 那个
 * RecipeKey 要靠它做 id ↔ 类型的往返），所以<b>只能由 jar 侧注册 + 绑定</b>。
 *
 * <h2>2. 🔴 发现机制 = jar 根目录下的 {@code kubejs.plugins.txt}（实测，不是推断）</h2>
 * <p>本机三个 jar 都在自己根目录放了同名文件，内容是<b>一行一个插件 FQCN</b>：
 * <pre>
 *   kubejs-forge-2001.6.5-build.26.jar : dev.latvian.mods.kubejs.forge.BuiltinKubeJSForgePlugin
 *                                        （另有 {@code ...GameStagesIntegration gamestages} 这类"FQCN 空格 参"的行）
 *   gtceu-1.20.1-1.4.4.jar             : com.gregtechceu.gtceu.integration.kjs.GregTechKubeJSPlugin
 *   gtlcore-1.2.3.2.jar                : org.gtlcore.gtlcore.integration.kjs.GTLKubejsPlugin
 *   ldlib-forge-1.20.1-1.0.33.b.jar    : com.lowdragmc.lowdraglib.kjs.LDLibKubeJSPlugin
 * </pre>
 * <p><b>运行期证据</b>（{@code _batchfix-verify\smoke_latest-r4.log}，KubeJS 自己打的）：
 * <pre>
 *   [KubeJS/]: Looking for KubeJS plugins...
 *   [KubeJS/]: Found plugin source kubejs / gtlcore / gtceu / ldlib / meinfinitycell / ...
 *   [KubeJS/]: Plugin ...KubeJSPlugin does not load on server side, skipping
 *   [KubeJS/]: Failed to load plugin ... from source avaritia: java.lang.ClassNotFoundException: ...
 * </pre>
 * ⇒ <b>每个 {@code kubejs.plugins.txt} 都对应一条 {@code Found plugin source <modid>}</b>，
 * 且加载失败会当场报 ERROR 并点名 FQCN。⇒ 落地后有<b>可 grep 的判据</b>：
 * 日志里必须出现 {@code Found plugin source shanhai}，且<b>不得</b>出现指向本类 FQCN 的
 * {@code Failed to load plugin}。
 * <p>本类走的就是这条：{@code src/main/resources/kubejs.plugins.txt} = {@code com.shanhai.integration.kjs.ShanhaiKubeJSPlugin}。
 * <p>⚠️ 与 {@code @Mod}/{@code @KubeJSPlugin} 注解无关：KubeJS 6 <b>不扫注解</b>，只读这个清单文件
 * （三个自带插件的 jar 里都没有对应的 {@code META-INF/services} 条目，取证见上）。
 *
 * <h2>3. 两个注册点分别解决什么</h2>
 * <ul>
 *   <li>{@link #registerBindings} —— <b>本轮唯一必需的</b>：{@code event.add("ModuleLevelCondition", …)}
 *       把类对象放进 server 脚本的全局作用域，脚本里才写得出
 *       {@code new ModuleLevelCondition('shanhai:introductory_material_module', 1)}。
 *       脚本侧的探测写法是 {@code typeof ModuleLevelCondition !== 'undefined'}
 *       （{@code shanhai_pf_recipes.js:224}）—— 未绑定时该表达式为 false，脚本自动退回旧形态，不会抛 ReferenceError。</li>
 *   <li>{@link #registerClasses} —— 照 {@code GTLKubejsPlugin} / {@code GregTechKubeJSPlugin} 的形状写的
 *       {@code filter.allow(...)}，让脚本可以用 {@code Java.loadClass("com.shanhai.machine.module.ModuleLevelCondition")}。
 *       <p>⚠️ <b>诚实边界</b>：实测 {@code ClassFilter.isAllowed0} 的字节码是「只查 denyStrong / allowStrong / denyWeak，
 *       查完就 {@code return 1}」⇒ 本整合包里它<b>本就是"默认放行、按黑名单拒绝"</b>
 *       （旁证：{@code smoke_latest-r4.log} 里 {@code shanhai_test_recipes.js#164: Loaded Java class
 *       'com.shanhai.common.recipe.ShanhaiRecipeStats'} —— 那时本工程<b>还没有</b>任何 KubeJS 插件）。
 *       ⇒ 这几行 {@code allow} 对本项目<b>很可能是冗余的</b>；保留是因为它与上游/同族插件写法一致、且无副作用。
 *       <b>不要把它当成"起作用了"的证据。</b></li>
 * </ul>
 */
public class ShanhaiKubeJSPlugin extends KubeJSPlugin {

    @Override
    public void registerClasses(ScriptType type, ClassFilter filter) {
        super.registerClasses(type, filter);
        filter.allow(ModuleLevelCondition.class);
    }

    @Override
    public void registerBindings(BindingsEvent event) {
        super.registerBindings(event);
        event.add("ModuleLevelCondition", ModuleLevelCondition.class);
        // 🔴 2026-10-05（B4）：把【指纹函数本身】绑进 KubeJS。
        //    覆盖层脚本 shanhai_recipe_overrides.js 与编辑器必须算出【逐字节相同】的 base_fp，
        //    而"两份实现碰巧一样"是靠不住的（老版就是靠"逐字复刻"维持的，一旦有一边改了就会漂）。
        //    绑同一个类 ⇒ 两边跑的是同一段代码 ⇒ 一致性是【构造上必然】的，不是"验出来的"。
        //    脚本侧用法：ShanhaiFingerprint.fingerprint(recipe.json) → String（null = 算不出来）
        event.add("ShanhaiFingerprint",
                com.shanhai.common.recipe.editor.ShanhaiRecipeFingerprint.class);
        // 🆕 2026-10-05（B 组：额外条件）：把"重放额外条件"这件事做成一个 Java 绑定。
        //    🔴 为什么不能在脚本里用 recipe.set('recipeConditions', …)：KubeJS 那个 component
        //      写出来的形状是 {"type":k,"data":{…}}，而 GT 的加载器要的是平铺
        //      {"type":k,…字段…}（RecipeCondition.CODEC 是 KeyDispatchCodec）⇒ 形状不对称，
        //      会让整条配方 json 解析失败。取证与三步做法见 ShanhaiRecipeConditionReplay 的类注释。
        //    脚本侧用法：var err = ShanhaiConditions.apply(recipe, JsonIO.of(entry.fields.conditions));
        //               err === "" ⇒ 成功；非空 ⇒ 这一条 entry 整体不套用（fail-closed）。
        event.add("ShanhaiConditions",
                com.shanhai.common.recipe.editor.ShanhaiRecipeConditionReplay.class);
        // 一次性自证：插件被加载时打一行，避免"没加载"与"加载了但没人用"在日志上长得一样。
        ShanhaiMod.LOGGER.info("[SHANHAI-KJS] ShanhaiKubeJSPlugin 已加载：已绑定全局类 "
                + "ModuleLevelCondition（构造器 (String 物质模块物品id, int 数量)）、"
                + "ShanhaiFingerprint（静态 fingerprint(JsonElement) / versionOf(String) / isCurrentVersion(String)）、"
                + "ShanhaiConditions（静态 validate(JsonElement) / apply(RecipeJS,JsonElement) / count(JsonElement)）；"
                + "配方条件类型 module_level 的注册在 ShanhaiRegistry#onRecipeConditionRegister。");
    }

    /**
     * 🆕 2026-10-05：把 KubeJS 侧的<b>配方 JSON</b>在一次性的窗口里抓下来，供配方编辑器算 {@code base_fp}。
     *
     * <h2>🔴 为什么必须抢在这个窗口里（不抢就永远拿不到）</h2>
     * 覆盖层脚本算 {@code base_fp} 用的是 {@code JsonIO.toString(recipe.json)}，其中
     * {@code recipe.json} 是 KubeJS 的 {@code RecipeJS.json}。而 KubeJS 只在
     * {@code ServerEvents.recipes} 那一小段里持有这些 {@code RecipeJS}：
     * <ul>
     *   <li>实测（冒烟第 1、2 局）：到 {@code ServerStartedEvent} 时
     *       {@code RecipesEventJS.instance} <b>已经是 null</b>；</li>
     *   <li>备选路线（拿活的 {@code GTRecipe} 过一遍 {@code GTRecipeSerializer.CODEC}）已被
     *       <b>同一次运行内的逐字节比对证否</b>：键序从 {@code data} 开头（KubeJS 那份从
     *       {@code type} 开头），长度 777 vs 1305，且 codec 那份根本不写
     *       {@code chance/maxChance/tierChanceBoost}。</li>
     * </ul>
     * 本方法就是 KubeJS 官方给插件留的那个窗口（{@code KubeJSPlugin.injectRuntimeRecipes}），
     * 参数里<b>直接带着 {@code RecipesEventJS}</b> ⇒ 拿得到全部 {@code RecipeJS}。
     *
     * <p>抓法是"算好就存"：当场把每条配方的 {@code base_fp} 字符串算出来放进缓存
     * （而不是留着 {@code RecipeJS} 引用）—— 前者约 20 MB 的紧凑字符串，后者会把整批
     * 配方对象图钉死在内存里。抓完打一行读数（条数 / 字符数 / 用时），"没抓到"与
     * "抓到了但是空的"因此可区分。
     */
    @Override
    public void injectRuntimeRecipes(dev.latvian.mods.kubejs.recipe.RecipesEventJS event,
                                     net.minecraft.world.item.crafting.RecipeManager recipeManager,
                                     java.util.Map<net.minecraft.resources.ResourceLocation,
                                             net.minecraft.world.item.crafting.Recipe<?>> recipes) {
        super.injectRuntimeRecipes(event, recipeManager, recipes);
        try {
            com.shanhai.common.recipe.editor.ShanhaiRecipeFingerprintCapture.captureFrom(event);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("[SHANHAI-EDIT] editor fp_capture_failed err={}", t.toString(), t);
        }
    }
}
