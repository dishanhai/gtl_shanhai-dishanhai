package com.shanhai.mixin;

import com.shanhai.client.jei.ShanhaiJeiRecipePatches;

import mezz.jei.gui.recipes.RecipesGui;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/**
 * 山海重构 · <b>JEI 收藏夹通道的补丁混入（第 7 个 mixin，第 3 个客户端侧）</b>。
 *
 * <h2>1. 它解决的是哪个问题（用户原话）</h2>
 * <blockquote>
 *   「E3额外一点，你通过<b>输入/输出/机器查询</b>这个配方时是会刷新的，但是你如果<b>收藏了这个配方</b>，
 *    通过收藏的通道查看这个配方，那它是不会刷新的」
 * </blockquote>
 *
 * <h2>2. 🔴 为什么分类页那条 mixin 覆盖不到收藏夹（字节码取证，不是推断）</h2>
 * <pre>
 * 分类页： PluginManager#getRecipes(RecipeTypeData, IFocusGroup, boolean)
 *          ← ShanhaiJeiRecipeOrderMixin 已注在这里 ✔
 * 收藏夹： mezz.jei.gui.bookmarks.RecipeBookmark 的把配方【对象本身】存了下来
 *          （构造器描述符：(IRecipeCategory;Ljava/lang/Object;LResourceLocation;
 *                          LITypedIngredient;LRecipeIngredientRole;)V —— 第二个参数就是配方对象）
 *          点它的时候走的是 mezz.jei.gui.overlay.elements.RecipeBookmarkElement：
 *              IRecipesGui#showRecipes(IRecipeCategory, List.of(bookmark.getRecipe()), List)
 *          ⇒ 【根本不经过 getRecipes】
 * </pre>
 * 所以分类页刷新了、收藏夹仍然是收藏那一刻那份旧对象 —— 与用户观察到的现象逐字一致。
 *
 * <h2>3. 注入点与修法</h2>
 * <pre>
 *   目标类   mezz.jei.gui.recipes.RecipesGui（JEI 内部实现，不是 API 接口）
 *   目标方法 public &lt;T&gt; void showRecipes(IRecipeCategory&lt;T&gt;, List&lt;T&gt;, List&lt;IFocus&lt;?&gt;&gt;)
 *   注入     &#64;ModifyVariable(at = HEAD, argsOnly = true, index = 2) ⇒ 换掉"第二参数：配方列表"
 * </pre>
 * 换法复用分类页那一套（{@code ShanhaiJeiRecipePatches#patchedForBookmark}）：
 * <b>按完整 recipe id</b> 过滤/替换/追加，不是"找一条换一条"。
 * <p>⚠️ 描述符写全（三个参数 + void 返回）是刻意的：{@code RecipesGui} 里 {@code showRecipes}
 * 只有一个重载，但写全之后如果 JEI 换签名，构建期就会响亮地失败（而不是悄悄注到别的东西上）。
 *
 * <h2>4. ⚠️ 如实交代：这一条本机【验不了】</h2>
 * 红线禁止启动客户端 ⇒ 这里能给的判据只有"日志里有没有出现
 * {@code [SHANHAI-JEIPATCH] jei_bookmark_sync …} 那一行"。
 * 交付报告里把它归到"只能他进游戏看"那一类，不吹成已验。
 */
@Mixin(value = RecipesGui.class, remap = false)
public class ShanhaiJeiBookmarkMixin {

    @ModifyVariable(
            method = "showRecipes(Lmezz/jei/api/recipe/category/IRecipeCategory;Ljava/util/List;Ljava/util/List;)V",
            at = @At("HEAD"),
            argsOnly = true,
            index = 2)
    private List<?> shanhai$patchFavorites(List<?> recipes) {
        try {
            if (ShanhaiJeiRecipePatches.isEmpty()) {
                return recipes;
            }
            return ShanhaiJeiRecipePatches.patchedForBookmark(recipes);
        } catch (Throwable t) {
            // 🔴 补丁出任何问题都只降级为"原样显示"，绝不把 JEI 弄崩
            com.shanhai.ShanhaiMod.LOGGER.error(
                    "[SHANHAI-JEIPATCH] jei_bookmark_patch_failed err={} (降级为原列表)", t.toString(), t);
            return recipes;
        }
    }
}
