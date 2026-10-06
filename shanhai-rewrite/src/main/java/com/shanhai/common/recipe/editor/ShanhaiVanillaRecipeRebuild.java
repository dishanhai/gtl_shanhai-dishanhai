package com.shanhai.common.recipe.editor;

import com.shanhai.ShanhaiMod;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import java.util.List;

/**
 * <b>用原版自己的 public 构造器重建一条同类新实例</b>。
 *
 * <h2>1. 🔴 为什么是"重建"而不是"改字段"或"写包装"</h2>
 * 三条都取证过（前置调查 §3.4）：
 * <ol>
 *   <li>原版配方的字段<b>全是 {@code final}</b>（{@code ShapedRecipe.f_44146_/f_44149_} 等）
 *       ⇒ <b>就地改不了</b>；</li>
 *   <li>写一个自定义 {@code Recipe} 包装有两个坑：会被别的 mod 的 {@code instanceof}
 *       判掉（Polymorph / KubeJS），而且同步给客户端时 {@code ClientboundUpdateRecipesPacket}
 *       要按原 serializer 重新编码 ⇒ 包装类编不出来；</li>
 *   <li>构造器与取值器<b>全是 public</b>（javap 实证，见本类每个分支的注释）
 *       ⇒ 可以<b>无损重建</b>，且 serializer / 类型 / id 一个都不变。</li>
 * </ol>
 *
 * <h2>2. 🔴 "底本"纪律照抄 GT 那条线</h2>
 * 重建必须<b>永远从底本（第一次见到的那个原对象）出发</b>，绝不在"上一次改过的对象"上再改 ——
 * 否则连改两次会累加、而且"恢复原样"再也回不去（这条在 {@code ShanhaiRecipeBase} 的类注释里
 * 有完整的现场记录）。
 *
 * <h2>3. ⚠️ 改不了的就说改不了</h2>
 * 每一处失败都<b>返回 {@code null} 并打一条 ERROR</b>，绝不返回一个"看起来改成功了"的原对象
 * —— 那会让面板显示新值而游戏里还是老值（本工程最怕的那一类静默失败）。
 */
public final class ShanhaiVanillaRecipeRebuild {

    public static final String PREFIX = "[SHANHAI-EDIT] editor";

    private ShanhaiVanillaRecipeRebuild() {}

    /**
     * 用底本 {@code base} 造一条新配方。
     *
     * @param base        底本（<b>必须是</b> {@code ShanhaiVanillaRecipeTable} 里那份原对象）
     * @param result      {@code null} = 产物不动
     * @param cookingTime {@code null} = 时间不动（只对 {@link ShanhaiVanillaRecipeView.Kind#COOKING} 有意义）
     * @param experience  {@code null} = 经验不动（同上）
     * @return 新对象；<b>没有任何一项能改 / 重建失败 ⇒ {@code null}</b>
     */
    public static Recipe<?> rebuild(Recipe<?> base, ItemStack result,
                                    Integer cookingTime, Double experience) {
        return rebuild(base, result, cookingTime, experience, null);
    }

    /**
     * 🆕 阶段 2：<b>连同输入一起</b>重建。
     *
     * @param shape 输入侧的统一中间表示（{@code null} 或 {@link ShanhaiVanillaRecipeShape#isDirty()} 为假
     *              ⇒ 输入<b>原样不动</b>，与 {@link #rebuild(Recipe, ItemStack, Integer, Double)} 逐字节等价）
     */
    public static Recipe<?> rebuild(Recipe<?> base, ItemStack result,
                                    Integer cookingTime, Double experience,
                                    ShanhaiVanillaRecipeShape shape) {
        if (base == null) {
            return null;
        }
        final ShanhaiVanillaRecipeView v = ShanhaiVanillaRecipeView.of(base);
        if (v == null) {
            return null;
        }
        final ItemStack newResult = (result == null || result.isEmpty()) ? v.result : result;
        final int newCook = cookingTime == null ? v.cookingTime : cookingTime.intValue();
        final double newXp = experience == null ? v.experience : experience.doubleValue();
        // 🔴 输入这一份只有在"真的动过"且"形态合法"时才参与重建。
        //    不合法 ⇒ 整条拒收（返回 null），绝不造一条"输入被清空/半截"的配方。
        final boolean useShape = shape != null && shape.isDirty();
        if (useShape && !shape.valid()) {
            ShanhaiMod.LOGGER.error("{} vanilla_rebuild_shape_invalid id={} mode={} reason={}",
                    PREFIX, v.id, shape.mode(), shape.invalidReason());
            return null;
        }
        try {
            final Recipe<?> out = switch (v.kind) {
                case SHAPED -> shaped((ShapedRecipe) base, newResult, useShape ? shape : null);
                case SHAPELESS -> shapeless((ShapelessRecipe) base, newResult, useShape ? shape : null);
                case COOKING -> cooking((AbstractCookingRecipe) base, newResult, newXp, newCook,
                        useShape ? shape : null);
                case STONECUTTING -> stonecutting((StonecutterRecipe) base, newResult,
                        useShape ? shape : null);
                case SMITHING_TRANSFORM -> smithingTransform(base, newResult);
                default -> null;
            };
            if (out == null) {
                ShanhaiMod.LOGGER.error("{} vanilla_rebuild_refused id={} kind={} -> 这个类型本版不能重建"
                                + "（面板上也不会给编辑控件）",
                        PREFIX, v.id, v.kind);
                return null;
            }
            final ShanhaiVanillaRecipeView nv = ShanhaiVanillaRecipeView.of(out);
            ShanhaiMod.LOGGER.info("{} vanilla_rebuild id={} kind={} old_result={} new_result={} "
                            + "old_cook={} new_cook={} old_xp={} new_xp={} class={} inputs_dirty={} "
                            + "old_shape={} new_shape={}",
                    PREFIX, v.id, v.kind, describe(v.result), describe(newResult),
                    v.cookingTime, nv == null ? -1 : nv.cookingTime,
                    v.experience, nv == null ? -1.0 : nv.experience,
                    out.getClass().getSimpleName(), useShape,
                    describeShape(v), nv == null ? "(?)" : describeShape(nv));
            return out;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} vanilla_rebuild_failed id={} kind={} err={}",
                    PREFIX, v.id, v.kind, t.toString(), t);
            return null;
        }
    }

    private static String describe(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return "(empty)";
        }
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()) + " x" + s.getCount();
    }

    /** 输入形状的一行摘要（日志里一眼看出"形状有没有变"）。 */
    private static String describeShape(ShanhaiVanillaRecipeView v) {
        final StringBuilder sb = new StringBuilder();
        if (v.kind == ShanhaiVanillaRecipeView.Kind.SHAPED) {
            sb.append(v.width).append('x').append(v.height).append(':');
            for (int y = 0; y < v.height; y++) {
                if (y > 0) {
                    sb.append('/');
                }
                for (int x = 0; x < v.width; x++) {
                    final int i = y * v.width + x;
                    final boolean empty = i >= v.inputs.size()
                            || ShanhaiVanillaRecipeView.representative(v.inputs.get(i)).isEmpty();
                    sb.append(empty ? '.' : 'X');
                }
            }
        } else {
            sb.append(v.inputs.size()).append(" 格");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 各类型的重建
    //
    // 每个分支的构造器签名都是 javap 抄下来的（SRG 服务端 jar，
    // net.minecraft.world.item.crafting.*）：
    //   ShapedRecipe(ResourceLocation, String, CraftingBookCategory, int, int,
    //                NonNullList<Ingredient>, ItemStack, boolean)
    //   ShapelessRecipe(ResourceLocation, String, CraftingBookCategory, ItemStack, NonNullList<Ingredient>)
    //   SmeltingRecipe(ResourceLocation, String, CookingBookCategory, Ingredient, ItemStack, float, int)
    //   StonecutterRecipe(ResourceLocation, String, Ingredient, ItemStack)
    //   SmithingTransformRecipe(ResourceLocation, Ingredient, Ingredient, Ingredient, ItemStack)

    private static Recipe<?> shaped(ShapedRecipe s, ItemStack result, ShanhaiVanillaRecipeShape shape) {
        if (shape == null) {
            final NonNullList<Ingredient> ins = copyIngredients(s.getIngredients());
            if (ins.size() != s.getWidth() * s.getHeight()) {
                ShanhaiMod.LOGGER.error("{} vanilla_rebuild_shaped_bad_shape id={} w={} h={} ingredients={}",
                        PREFIX, s.getId(), s.getWidth(), s.getHeight(), ins.size());
                return null;
            }
            return new ShapedRecipe(s.getId(), s.getGroup(), s.category(),
                    s.getWidth(), s.getHeight(), ins, result, s.showNotification());
        }
        // 🆕 阶段 2：按 IR 重排输入。
        //    🔴 宽高【由去外圈空格之后的实际形状算出来】，不是照抄用户按的 3×3 ——
        //       原版读 JSON 时也是这么做的（ShapedRecipe.Serializer 的 firstNonSpace/lastNonSpace）。
        final int[] b = shape.trimmedBounds();
        if (b == null) {
            ShanhaiMod.LOGGER.error("{} vanilla_rebuild_shaped_empty id={}", PREFIX, s.getId());
            return null;
        }
        final int nw = b[2] - b[0] + 1;
        final int nh = b[3] - b[1] + 1;
        final NonNullList<Ingredient> ins = NonNullList.create();
        for (int y = b[1]; y <= b[3]; y++) {
            for (int x = b[0]; x <= b[2]; x++) {
                ins.add(shape.ingredientAt(y * ShanhaiVanillaRecipeShape.MAX_SIDE + x));
            }
        }
        if (ins.size() != nw * nh) {
            ShanhaiMod.LOGGER.error("{} vanilla_rebuild_shaped_size_mismatch id={} w={} h={} n={}",
                    PREFIX, s.getId(), nw, nh, ins.size());
            return null;
        }
        return new ShapedRecipe(s.getId(), s.getGroup(), s.category(),
                nw, nh, ins, result, s.showNotification());
    }

    private static Recipe<?> shapeless(ShapelessRecipe s, ItemStack result, ShanhaiVanillaRecipeShape shape) {
        if (shape == null) {
            return new ShapelessRecipe(s.getId(), s.getGroup(), s.category(),
                    result, copyIngredients(s.getIngredients()));
        }
        final List<Ingredient> l = shape.ingredientList();
        if (l.isEmpty()) {
            ShanhaiMod.LOGGER.error("{} vanilla_rebuild_shapeless_empty id={}", PREFIX, s.getId());
            return null;
        }
        return new ShapelessRecipe(s.getId(), s.getGroup(), s.category(), result, copyIngredients(l));
    }

    private static Recipe<?> cooking(AbstractCookingRecipe c, ItemStack result, double xp, int cookTime,
                                     ShanhaiVanillaRecipeShape shape) {
        final Ingredient ing;
        if (shape != null) {
            ing = shape.ingredientAt(0);
            if (ing == null || ing.isEmpty()) {
                ShanhaiMod.LOGGER.error("{} vanilla_rebuild_cooking_empty_input id={}", PREFIX, c.getId());
                return null;
            }
        } else {
            final List<Ingredient> ins = c.getIngredients();
            if (ins.isEmpty() || ins.get(0) == null) {
                ShanhaiMod.LOGGER.error("{} vanilla_rebuild_cooking_no_ingredient id={}", PREFIX, c.getId());
                return null;
            }
            ing = ins.get(0);
        }
        final int ct = Math.max(1, cookTime);
        final float x = (float) Math.max(0.0, xp);
        final ResourceLocation id = c.getId();
        final String group = c.getGroup();
        if (c instanceof SmeltingRecipe) {
            return new SmeltingRecipe(id, group, c.category(), ing, result, x, ct);
        }
        if (c instanceof BlastingRecipe) {
            return new BlastingRecipe(id, group, c.category(), ing, result, x, ct);
        }
        if (c instanceof SmokingRecipe) {
            return new SmokingRecipe(id, group, c.category(), ing, result, x, ct);
        }
        if (c instanceof CampfireCookingRecipe) {
            return new CampfireCookingRecipe(id, group, c.category(), ing, result, x, ct);
        }
        ShanhaiMod.LOGGER.error("{} vanilla_rebuild_cooking_unknown_class id={} class={}",
                PREFIX, id, c.getClass().getName());
        return null;
    }

    private static Recipe<?> stonecutting(StonecutterRecipe s, ItemStack result,
                                          ShanhaiVanillaRecipeShape shape) {
        final Ingredient ing;
        if (shape != null) {
            ing = shape.ingredientAt(0);
            if (ing == null || ing.isEmpty()) {
                ShanhaiMod.LOGGER.error("{} vanilla_rebuild_stonecutting_empty_input id={}", PREFIX, s.getId());
                return null;
            }
        } else {
            final List<Ingredient> ins = s.getIngredients();
            if (ins.isEmpty() || ins.get(0) == null) {
                ShanhaiMod.LOGGER.error("{} vanilla_rebuild_stonecutting_no_ingredient id={}", PREFIX, s.getId());
                return null;
            }
            ing = ins.get(0);
        }
        // 注意：StonecutterRecipe 没有 getGroup()（SingleItemRecipe 不带 group）⇒ 传 ""。
        // 🔴 切石机的产物数量【恒为 1】：调用方（编辑器）不给它改数量。
        return new StonecutterRecipe(s.getId(), "", ing, result);
    }

    private static Recipe<?> smithingTransform(Recipe<?> base, ItemStack result) {
        // SmithingTransformRecipe 的三个 Ingredient 没有 public 取值器（只有 matches 那几个方法），
        // ⇒ 用 getIngredients() 拿那一份（顺序 = template / base / addition，javap 实证）。
        final List<Ingredient> ins = base.getIngredients();
        if (ins.size() < 3) {
            ShanhaiMod.LOGGER.error("{} vanilla_rebuild_smithing_ingredients id={} n={}",
                    PREFIX, base.getId(), ins.size());
            return null;
        }
        return new net.minecraft.world.item.crafting.SmithingTransformRecipe(
                base.getId(), ins.get(0), ins.get(1), ins.get(2), result);
    }

    /** {@code NonNullList} 的防御性拷贝（原版 {@code getIngredients()} 给的是可变表，直接交出去有被改的风险）。 */
    private static NonNullList<Ingredient> copyIngredients(List<Ingredient> src) {
        final NonNullList<Ingredient> out = NonNullList.create();
        if (src != null) {
            out.addAll(src);
        }
        return out;
    }
}
