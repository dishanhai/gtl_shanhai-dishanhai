package com.shanhai.mixin;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.recipe.editor.ShanhaiRecipeBase;
import com.shanhai.common.recipe.editor.ShanhaiRecipeEditorOps;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <b>收束 {@code GTRecipeType#getRecipe(RecipeManager, id)} 这条【按 id 直读】的路</b>。
 *
 * <h2>🔴 为什么必须有这一条（第 5 轮 #4 的一条已证实的漏点）</h2>
 * 本轮把"JEI 为什么不跟着变"追到字节码一级之后，结论是：
 * <pre>
 *   机器  → GTRecipeType.getLookup()（我们重建的那棵树）   ⇒ 新值 ✓
 *   JEI   → GTRecipeTypeCategory.registerRecipes
 *            → ClientPacketListener → RecipeManager.byType(RecipeType)（原版按类型表） ⇒ 旧值 ✗
 * </pre>
 * 而 {@code replaceRecipes} 的字节码<b>只写原版那两个 Map 字段</b>（{@code f_44007_} / {@code f_199900_}），
 * 不会去动 GT 索引 —— 反过来，我们重建 GT 索引时也<b>不会</b>动到"按 id 直读原版表"这条小路。
 * ⇒ {@code getRecipe(RecipeManager, id)} 会拿到 <b>原版表里那份（= 没改过的）</b>，
 * 而任何走它的调用方（别的 mod 的兼容层、我们自己将来新增的代码）都会静默拿到旧值。
 *
 * <h2>做法（与上游同一形状，独立实现）</h2>
 * 只在"这条 id 真的被我们动过"（{@code EVER_TOUCHED}）时才改道：
 * 去 GT 索引里按 id 取我们重建出来的那一条，取到就返回它；取不到就<b>不动手</b>
 * （原样走原版那条路 —— 宁可给旧的，也不凭空造一条）。
 *
 * <h2>⚠️ 为什么条件写得这么窄</h2>
 * 这是一个会改变<b>所有</b>按 id 直读行为的注入点。判据必须满足三条：
 * <ol>
 *   <li>只有 {@code everTouched(id)} 为真才改道 ⇒ 没编辑过的配方行为<b>逐字节不变</b>；</li>
 *   <li>{@code remap = false}：{@code GTRecipeType} 是 GT 自己的类，名字本来就不是 SRG，不需要映射；</li>
 *   <li>任何一个环节抛异常都<b>回落</b>到原版那条路（{@code cir} 不设值就是原行为）。</li>
 * </ol>
 * 判据行（机器可 grep）：{@code getrecipe_redirected id=… type=…} /
 * 冒烟装置里"注入失败"会以 {@code Mixin apply failed} 出现（那一条是本类的红）。
 */
@Mixin(value = GTRecipeType.class, remap = false)
public abstract class ShanhaiRecipeTypeGetRecipeMixin {

    @Inject(method = "getRecipe", at = @At("HEAD"), cancellable = true, remap = false)
    private void shanhai$resolveRuntimeRecipe(RecipeManager recipeManager, ResourceLocation id,
                                              CallbackInfoReturnable<GTRecipe> cir) {
        try {
            if (id == null) {
                return;
            }
            // 只处理"我们动过的"那几条 —— 其余一律原样
            if (!ShanhaiRecipeBase.everTouched(id)) {
                return;
            }
            final GTRecipeType self = (GTRecipeType) (Object) this;
            final GTRecipe runtime = ShanhaiRecipeEditorOps.readFromIndex(self, id);
            if (runtime != null) {
                ShanhaiMod.LOGGER.info("{} getrecipe_redirected id={} type={} source=gt_index "
                                + "(按 id 直读改道到重建后的索引；原版表里那份还是旧的)",
                        ShanhaiRecipeBase.PREFIX, id, self.registryName);
                cir.setReturnValue(runtime);
            }
        } catch (Throwable t) {
            // 不改道 = 原行为。这里绝不 setReturnValue(null)：那会让"取不到"变成"没有这条配方"。
            ShanhaiMod.LOGGER.warn("{} getrecipe_redirect_failed id={} err={} -> 走原版那条路",
                    ShanhaiRecipeBase.PREFIX, id, t.toString());
        }
    }
}
