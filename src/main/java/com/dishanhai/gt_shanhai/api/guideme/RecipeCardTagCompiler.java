package com.dishanhai.gt_shanhai.api.guideme;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import guideme.compiler.PageCompiler;
import guideme.compiler.tags.BlockTagCompiler;
import guideme.document.block.LytBlock;
import guideme.document.block.LytBlockContainer;
import guideme.internal.util.Platform;
import guideme.libs.mdast.mdx.model.MdxJsxElementFields;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

@OnlyIn(Dist.CLIENT)
public class RecipeCardTagCompiler extends BlockTagCompiler {
    @Override
    public Set<String> getTagNames() {
        return Set.of("RecipeCard");
    }

    @Override
    protected void compile(PageCompiler compiler, LytBlockContainer parent, MdxJsxElementFields mdxJsxElementFields) {
        String recipeTypeStr = mdxJsxElementFields.getAttributeString("recipeType", "");
        String recipeIdStr = mdxJsxElementFields.getAttributeString("recipeId", "");
        String title = mdxJsxElementFields.getAttributeString("title", recipeIdStr);

        boolean showEU = parseBoolean(mdxJsxElementFields, "showEU", true);
        boolean showDuration = parseBoolean(mdxJsxElementFields, "showDuration", true);

        if (recipeTypeStr.isEmpty()) {
            parent.append(compiler.createErrorBlock(
                "RecipeCard: missing required attribute (recipeType)",
                mdxJsxElementFields
            ));
            return;
        }

        try {
            ResourceLocation recipeTypeId = ResourceLocation.tryParse(recipeTypeStr);
            if (recipeTypeId == null) {
                parent.append(compiler.createErrorBlock(
                    "RecipeCard: invalid recipeType format: " + recipeTypeStr,
                    mdxJsxElementFields
                ));
                return;
            }

            GTRecipeType recipeType = findRecipeType(recipeTypeId);
            if (recipeType == null) {
                parent.append(compiler.createErrorBlock(
                    "RecipeCard: unknown recipe type: " + recipeTypeStr,
                    mdxJsxElementFields
                ));
                return;
            }

            GTRecipe recipe;
            if (recipeIdStr.isEmpty()) {
                // 未指定 recipeId：显示该类型的第一个配方（便于测试/展示）
                recipe = findFirstRecipe(recipeType);
                if (recipe == null) {
                    parent.append(compiler.createErrorBlock(
                        "RecipeCard: no recipes found in type " + recipeTypeStr,
                        mdxJsxElementFields
                    ));
                    return;
                }
            } else {
                ResourceLocation recipeId = ResourceLocation.tryParse(recipeIdStr);
                if (recipeId == null) {
                    parent.append(compiler.createErrorBlock(
                        "RecipeCard: invalid recipeId format: " + recipeIdStr,
                        mdxJsxElementFields
                    ));
                    return;
                }
                recipe = findRecipe(recipeType, recipeId);
                if (recipe == null) {
                    parent.append(compiler.createErrorBlock(
                        "RecipeCard: recipe not found: " + recipeIdStr + " in type " + recipeTypeStr,
                        mdxJsxElementFields
                    ));
                    return;
                }
            }

            LytBlock recipeCard = RecipeCardBlockFactory.createRecipeCardBlock(
                recipe, title, showEU, showDuration
            );
            parent.append(recipeCard);

        } catch (Exception e) {
            parent.append(compiler.createErrorBlock(
                "RecipeCard: error rendering recipe - " + e.getMessage(),
                mdxJsxElementFields
            ));
        }
    }

    @Nullable
    private GTRecipeType findRecipeType(ResourceLocation typeId) {
        // 直接按写法查
        GTRecipeType type = GTRegistries.RECIPE_TYPES.get(typeId);
        if (type != null) {
            return type;
        }
        // 兜底：山海配方类型经 GTRecipeTypes.register 注册，实际命名空间是 gtceu（GTCEu.id 强制）。
        // 用户写 gt_shanhai:xxx 时，自动尝试 gtceu:xxx。
        if (!"gtceu".equals(typeId.getNamespace())) {
            return GTRegistries.RECIPE_TYPES.get(new ResourceLocation("gtceu", typeId.getPath()));
        }
        return null;
    }

    @Nullable
    private GTRecipe findRecipe(GTRecipeType recipeType, ResourceLocation recipeId) {
        try {
            RecipeManager recipeManager = Platform.getClientRecipeManager();
            if (recipeManager == null) {
                return null;
            }
            return recipeType.getRecipe(recipeManager, recipeId);
        } catch (Exception e) {
            return null;
        }
    }

    // 从配方类型的内部查找表取第一个配方（与 DShanhaiGTRecipeQuery 一致的枚举方式）。
    // GT 配方 ID 常由 KubeJS 自动生成、难以手查，故 recipeId 留空时用此兜底展示。
    @Nullable
    private GTRecipe findFirstRecipe(GTRecipeType recipeType) {
        try {
            var lookup = recipeType.getLookup();
            if (lookup == null) {
                return null;
            }
            var branch = lookup.getLookup();
            if (branch == null) {
                return null;
            }
            return branch.getRecipes(true)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean parseBoolean(MdxJsxElementFields fields, String name, boolean defaultValue) {
        var attr = fields.getAttribute(name);
        if (attr == null) {
            return defaultValue;
        }

        if (attr.hasStringValue()) {
            String value = attr.getStringValue();
            return "true".equalsIgnoreCase(value);
        } else if (attr.hasExpressionValue()) {
            String expr = attr.getExpressionValue();
            return "true".equalsIgnoreCase(expr);
        }

        return defaultValue;
    }
}
