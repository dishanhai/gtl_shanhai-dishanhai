package com.shanhai.mixin;

import com.shanhai.client.jei.ShanhaiJeiRecipeOrdering;
import com.shanhai.client.jei.ShanhaiJeiRecipePatches;

import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.library.recipes.PluginManager;
import mezz.jei.library.recipes.collect.RecipeTypeData;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.stream.Stream;

/**
 * 山海重构 · <b>JEI「分类内排序」混入（第 6 个 mixin，也是第 2 个客户端侧 mixin）</b>。
 *
 * <h2>0. 它解决的是哪个问题</h2>
 * 上一轮（方案 A）改的是 {@code config\jei\recipe-category-sort-order.ini} —— 那管的是
 * <b>「分类之间」</b>的先后（跨分类列表里先弹哪个分类的页）。
 * 用户实测反馈「提取机我这看也没变，铁锭提取液态铁还在第二页」针对的是
 * <b>「同一个分类内部」</b>的配方顺序 ⇒ <b>A 管不到那里，只有本 mixin 能管</b>。
 *
 * <h2>1. 排序口径（**按配方类型分别配**，不是一套通用规则）</h2>
 * <pre>
 *   研磨机 gtceu:macerator ⇒ 输入含【锭】的排第 1 档、含【宝石】的排第 2 档，其余保持原顺序
 *   提取机 gtceu:extractor ⇒ 输入含【锭】的排第 1 档、含【粉】的排第 2 档，其余保持原顺序
 * </pre>
 * 档位表本身是<b>数据</b>（{@code com.shanhai.common.jei.RecipeIngredientOrdering.TIER_TABLE}），
 * 判据是纯函数、可离线自证；本 mixin 只负责"把 JEI 算好的列表交给它"。
 * ⇒ 研磨机里"是粉的"不会被提前、提取机里"是宝石的"也不会被提前（用户口径要求）。
 *
 * <h2>2. 🔴 注入点是哪、为什么是它</h2>
 * <pre>
 * 目标类   mezz.jei.library.recipes.PluginManager          （JEI 自己内部的配方聚合器，不是公开 API）
 * 目标方法 getRecipes(RecipeTypeData&lt;T&gt;, IFocusGroup, boolean) → Stream&lt;T&gt;
 * 注入时机 &#64;At("RETURN") + cancellable ⇒ 把它算好的那个 Stream 换成"排好序的 Stream"
 * </pre>
 * <b>为什么选它（不是随手挑的，是一条链证下来的）</b>：
 * <ol>
 *   <li>{@code javap -c} 实测：{@code RecipeManagerInternal.getRecipesStream(...)} 只做一件事 ——
 *       把 {@code RecipeTypeData} 交给 {@code PluginManager.getRecipes(...)}；</li>
 *   <li>对 <b>全 JEI 的 1071 个 class</b> 逐个扫常量池文本，「引用 {@code getRecipesStream}」的类
 *       <b>只有 1 个</b>：{@code mezz.jei.library.recipes.RecipeLookup}；
 *       更进一步：「引用 {@code PluginManager}」的类也<b>只有 1 个</b>：{@code RecipeManagerInternal}</li>
 *   <li>GUI 那一头也证到了：{@code RecipeGuiLogic.showRecipes(IFocusedRecipes, ...)} →
 *       {@code IRecipeLayoutList.create(..., IFocusedRecipes, ...)}，而 {@code FocusedRecipes}
 *       的构造器里就是 {@code createRecipeLookup(type).limitFocus(focuses).get()}
 *       ⇒ <b>屏幕上那份配方列表 = 本 mixin 的注入对象</b></li>
 *   <li>同一份字节码还证明：<b>聚焦路径</b>（{@code getRecipes(category, focus)} → 按物品建的倒排表）
 *       与 <b>无聚焦路径</b>（{@code getRecipes(category)} → {@code RecipeTypeData.getRecipes()}）
 *       都在这个方法内部被拼起来再返回，所以<b>一个注入点同时覆盖两种界面</b>，
 *       不必再去动 {@code RecipeIngredientTable} / {@code RecipeTypeData} 的内部字段。</li>
 * </ol>
 *
 * <h2>3. 🔴 为什么只能动"返回的列表"，不能动 JEI 的索引</h2>
 * 我们排序的是 {@code PluginManager.getRecipes} <b>交给界面显示</b>的那份 Stream（先 {@code toList()} 物化再重排），
 * <b>完全不写回</b> JEI 的任何字段：
 * <ul>
 *   <li>不碰 {@code RecipeTypeData.recipes}（JEI 的分类索引）；</li>
 *   <li>不碰 {@code IngredientToRecipesMap}（按物品的倒排表）；
 *       ⚠️ 那两份都是 {@code Collections.unmodifiableList} 包的，本来也不能就地排；</li>
 *   <li>不碰原版 {@code net.minecraft.world.item.crafting.RecipeManager} ⇒
 *       <b>机器实际能跑什么、匹配到哪一条配方，一个字节都不变</b>（这是任务书的硬红线）。</li>
 * </ul>
 * ⇒ 本 mixin 的可见影响面 = <b>只有 JEI 的显示先后</b>。
 *
 * <h2>4. 🔴 稳定性（"每次打开顺序都变"是比不排更糟的结果）</h2>
 * 重排用的是 {@code RecipeIngredientOrdering#stableByRank}：
 * 自实现的「按档分桶」稳定分区（<b>不依赖</b> {@code Stream.sorted} / {@code List.sort} 的稳定性承诺），
 * 且"只有一档命中分布"时<b>恒等短路</b>。⇒ 同一个基础顺序反复调用，输出逐元素相同（幂等）。
 * 稳定性本身是用<b>离线自证</b>跑的（含"预期失败"档），见 {@code temp\jei-order2\selftest}。
 * ⚠️ 档内顺序 = JEI 自己的基础顺序（{@code RecipeManager} 那张 HashMap 的迭代序）——
 * 这是刻意的：需求就是"同优先级保持原顺序"，我们**不许**再去细分。
 *
 * <h2>5. 与另外 5 个 mixin 的关系</h2>
 * 现有 5 个：{@code ShanhaiSmokeMixin} / {@code ShanhaiInfiniteThreadDisplayMixin} /
 * {@code ShanhaiGeneratorWirelessEnergyJadeMixin} / {@code ShanhaiSetBlockWatchMixin}（4 个 common 侧）
 * + {@code ShanhaiFontStyleMixin}（1 个 client 侧）。本类是<b>第 6 个、第 2 个 client 侧</b>：
 * <ul>
 *   <li>它<b>注的是 JEI 的类</b>，而 JEI 是客户端 mod ⇒ 必须进 {@code shanhai.mixin.json} 的
 *       {@code "client"} 数组（<b>不能</b>进 {@code "mixins"}）：这样无头专服根本不会尝试应用它，
 *       不会因为"类不存在"在服务端炸；</li>
 *   <li>JEI 的类<b>不经 Minecraft 混淆</b> ⇒ 与 {@code ShanhaiSmokeMixin} 同理用 {@code remap = false}，
 *       <b>不需要 refmap 条目</b>（本工程 refmap 只为 {@code ShanhaiFontStyleMixin} 那条
 *       注 Minecraft 类 {@code Font} 的路服务）；</li>
 *   <li>{@code require = 1} 是刻意的，与 {@code ShanhaiFontStyleMixin} 同一取舍：
 *       <b>注入点找不到 = 加载期响亮地崩</b>，绝不静默变成"看起来没效果"（那正是本轮要避免的失败形态）。</li>
 * </ul>
 *
 * <h2>6. 🔴 描述符为什么写全（不写光秃秃的 "getRecipes"）</h2>
 * {@code javap} 实测 {@code PluginManager} 里叫 {@code getRecipes} 的方法<b>有三个</b>：
 * <pre>
 * public  &lt;T&gt; Stream&lt;T&gt; getRecipes(RecipeTypeData&lt;T&gt;, IFocusGroup, boolean)      ← 本 mixin 要的就是它
 * private &lt;T&gt; Stream&lt;T&gt; getRecipes(IRecipeManagerPlugin, IRecipeCategory&lt;T&gt;)
 * private &lt;T&gt; Stream&lt;T&gt; getRecipes(IRecipeManagerPlugin, IRecipeCategory&lt;T&gt;, IFocus&lt;?&gt;)
 * </pre>
 * 只写名字 = 一次匹配到 3 个目标，属于<b>"能不能选中"靠混入实现细节</b>的写法；
 * 写全描述符 = 唯一命中，且在版本变化时立刻响亮地失败（而不是悄悄注错重载）。
 * ⚠️ 另一条实测：mixin 注解处理器<b>会</b>解析目标方法，但找不到时只打一条 <b>Note</b>
 * （{@code Cannot find target method ...}），<b>构建仍然成功</b> ⇒
 * "构建成功"本身**不能**证明注入点正确；能证明的是"<b>没有</b>那条 Note"。
 *
 * <h2>7. 生效判据（用户可自查）</h2>
 * 客户端 {@code logs\latest.log} 里应出现 <b>每个目标分类一行</b>
 * {@code [SHANHAI-JEIORDER] JEI 分类内排序已生效：gtceu:macerator 本批 N 条配方；命中档位[锭 X 条、宝石 Y 条]…}。
 * <b>打不出来 = 注入没生效</b>；<b>打出来了但命中数全 0 = 判据没命中</b>；
 * <b>命中数 &gt; 0 而顺序没变 = 显示的不是这份列表</b>（三种病要分开）。
 */
@Mixin(value = PluginManager.class, remap = false)
public class ShanhaiJeiRecipeOrderMixin {

    /**
     * 把 JEI 已经算好的配方列表交给档位排序（档内保持原顺序）。
     *
     * <p>参数表必须与目标方法一致：{@code (RecipeTypeData, IFocusGroup, boolean)} → {@code Stream}。
     * 全程包在 {@code try/catch(Throwable)} 里：<b>排序出任何问题都只降级、绝不把 JEI 弄崩</b>。
     */
    @Inject(
            method = "getRecipes(Lmezz/jei/library/recipes/collect/RecipeTypeData;Lmezz/jei/api/recipe/IFocusGroup;Z)Ljava/util/stream/Stream;",
            at = @At("RETURN"),
            cancellable = true,
            require = 1)
    private void shanhai$ingotFirstOrder(RecipeTypeData<?> recipeTypeData, IFocusGroup focusGroup,
                                         boolean includeHidden, CallbackInfoReturnable<Stream<?>> cir) {
        try {
            Stream<?> original = cir.getReturnValue();
            if (original == null || recipeTypeData == null) {
                return;
            }
            RecipeType<?> recipeType = recipeTypeData.getRecipeCategory().getRecipeType();
            if (recipeType == null) {
                return;
            }
            String uid = String.valueOf(recipeType.getUid());
            boolean targetCategory = ShanhaiJeiRecipeOrdering.isTargetCategory(recipeType.getUid());
            boolean hasPatches = !ShanhaiJeiRecipePatches.isEmpty();
            if (!targetCategory && !hasPatches) {
                // ⚠️ 两个分支都不命中 ⇒ 【原样返回同一个 Stream】，与"本 mixin 不存在"完全等价。
                return;
            }
            List<?> base = original.toList();
            // 🔴 顺序有意为之：先贴补丁（配方内容变了），再排序（按贴完之后的输入形态分档）。
            List<?> patched = hasPatches ? ShanhaiJeiRecipePatches.patched(base, uid) : base;
            List<?> ordered = targetCategory ? ShanhaiJeiRecipeOrdering.order(patched, uid) : patched;
            cir.setReturnValue(ordered.stream());
        } catch (Throwable throwable) {
            ShanhaiJeiRecipeOrdering.logFailureOnce(throwable);
        }
    }
}
