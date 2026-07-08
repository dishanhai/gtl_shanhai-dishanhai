package com.dishanhai.gt_shanhai.api.guideme;

import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import guideme.document.block.LytBlock;
import guideme.document.block.LytParagraph;
import guideme.document.block.LytSlotGrid;
import guideme.document.block.LytVBox;
import guideme.document.block.recipes.LytStandardRecipeBox;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

@OnlyIn(Dist.CLIENT)
public class RecipeCardBlockFactory {

    public static LytBlock createRecipeCardBlock(GTRecipe recipe, String title, boolean showEU, boolean showDuration) {
        LytStandardRecipeBox.Builder builder = LytStandardRecipeBox.builder();

        builder.title(title);

        // 构建输入 SlotGrid
        List<Ingredient> itemInputs = collectItemIngredients(recipe, true);
        if (!itemInputs.isEmpty()) {
            builder.input(LytSlotGrid.row(itemInputs, true));
        }

        // 构建输出 SlotGrid
        List<Ingredient> itemOutputs = collectItemIngredients(recipe, false);
        if (!itemOutputs.isEmpty()) {
            builder.output(LytSlotGrid.row(itemOutputs, true));
        }

        // 构建底部信息行（EU/t 和 duration）
        LytVBox infoBox = buildInfoBox(recipe, showEU, showDuration);
        if (infoBox != null) {
            builder.addBottom(infoBox);
        }

        return builder.build(recipe);
    }

    private static List<Ingredient> collectItemIngredients(GTRecipe recipe, boolean input) {
        List<Content> contents = input
                ? recipe.getInputContents(ItemRecipeCapability.CAP)
                : recipe.getOutputContents(ItemRecipeCapability.CAP);

        List<Ingredient> result = new ArrayList<>();
        for (Content content : contents) {
            Object raw = content.getContent();
            if (raw instanceof Ingredient) {
                result.add((Ingredient) raw);
            }
        }
        return result;
    }

    private static LytVBox buildInfoBox(GTRecipe recipe, boolean showEU, boolean showDuration) {
        List<String> lines = new ArrayList<>();

        if (showEU) {
            long eut = RecipeHelper.getInputEUt(recipe);
            if (eut == 0) {
                eut = RecipeHelper.getOutputEUt(recipe);
            }
            if (eut != 0) {
                lines.add(eut + " EU/t");
            }
        }

        if (showDuration) {
            int ticks = recipe.duration;
            if (ticks > 0) {
                // 20 ticks = 1 秒
                if (ticks % 20 == 0) {
                    lines.add(ticks / 20 + "s");
                } else {
                    lines.add(ticks + " ticks");
                }
            }
        }

        if (lines.isEmpty()) {
            return null;
        }

        LytVBox box = new LytVBox();
        box.setGap(1);
        for (String line : lines) {
            LytParagraph p = new LytParagraph();
            p.appendText(line);
            box.append(p);
        }
        return box;
    }
}
