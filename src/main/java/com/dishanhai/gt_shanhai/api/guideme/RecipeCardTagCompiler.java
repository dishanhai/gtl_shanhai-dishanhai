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

        if (recipeTypeStr.isEmpty() || recipeIdStr.isEmpty()) {
            parent.append(compiler.createErrorBlock(
                "RecipeCard: missing required attributes (recipeType and recipeId)",
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

            ResourceLocation recipeId = ResourceLocation.tryParse(recipeIdStr);
            if (recipeId == null) {
                parent.append(compiler.createErrorBlock(
                    "RecipeCard: invalid recipeId format: " + recipeIdStr,
                    mdxJsxElementFields
                ));
                return;
            }

            GTRecipe recipe = findRecipe(recipeType, recipeId);
            if (recipe == null) {
                parent.append(compiler.createErrorBlock(
                    "RecipeCard: recipe not found: " + recipeIdStr + " in type " + recipeTypeStr,
                    mdxJsxElementFields
                ));
                return;
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
        return GTRegistries.RECIPE_TYPES.get(typeId);
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
