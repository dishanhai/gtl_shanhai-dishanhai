package com.dishanhai.gt_shanhai.api.guideme;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.utils.GTUtil;
import com.gtladd.gtladditions.api.gui.GTLytSlotGrid;
import guideme.document.block.LytBlock;
import guideme.document.block.LytParagraph;
import guideme.document.block.LytVBox;
import guideme.document.block.recipes.LytStandardRecipeBox;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

@OnlyIn(Dist.CLIENT)
public class RecipeCardBlockFactory {

    public static LytBlock createRecipeCardBlock(GTRecipe recipe, String title, boolean showEU, boolean showDuration) {
        LytStandardRecipeBox.Builder builder = LytStandardRecipeBox.builder();

        builder.title(title);

        // 复用 gtladditions 的 GTLytSlotGrid：物品 + 流体统一网格，自动排布输入/输出。
        // 构建前后夹一层计数：GTLytSlotGridOverflowMixin 会把超出网格容量的槽位阻断并累加。
        RecipeCardSlotOverflow.reset();
        GTLytSlotGrid.Builder slotGrid = GTLytSlotGrid.builder(recipe);
        builder.input(slotGrid.getRecipeInput());
        builder.output(slotGrid.getRecipeOutput());
        int truncated = RecipeCardSlotOverflow.get();

        // 构建底部信息行（EU/t、duration、截断提示）
        LytVBox infoBox = buildInfoBox(recipe, showEU, showDuration, truncated);
        if (infoBox != null) {
            builder.addBottom(infoBox);
        }

        return builder.build(recipe);
    }

    private static LytVBox buildInfoBox(GTRecipe recipe, boolean showEU, boolean showDuration, int truncated) {
        List<String> lines = new ArrayList<>();

        if (showEU) {
            long eut = RecipeHelper.getInputEUt(recipe);
            if (eut == 0) {
                eut = RecipeHelper.getOutputEUt(recipe);
            }
            if (eut != 0) {
                lines.add(formatEU(eut));
            }
        }

        if (showDuration) {
            int ticks = recipe.duration;
            if (ticks > 0) {
                lines.add(formatDuration(ticks));
            }
        }

        // 截断提示：网格容量放不下的产物被 GTLytSlotGridOverflowMixin 阻断，这里如实告知数量。
        if (truncated > 0) {
            lines.add("⚠ 另有 " + truncated + " 格产物超出展示上限（已截断）");
        }

        if (lines.isEmpty()) {
            return null;
        }

        LytVBox box = new LytVBox();
        box.setGap(2);
        for (String line : lines) {
            LytParagraph p = new LytParagraph();
            p.appendText(line);
            box.append(p);
        }
        return box;
    }

    // EU/t：加千分位 + 电压等级名（复用 GTCEu 的 GTUtil.getTierByVoltage）。
    // EU/t 单位已自描述、无需前缀；取绝对值查等级（发电配方 EU 可能为负）。
    private static String formatEU(long eut) {
        long abs = Math.abs(eut);
        byte tier = GTUtil.getTierByVoltage(abs);
        String tierName = (tier >= 0 && tier < GTValues.VN.length) ? GTValues.VN[tier] : "?";
        return String.format("%,d EU/t (%s)", eut, tierName);
    }

    // 耗时：游戏刻 + 换算秒数（20 刻 = 1 秒）。单数不加复数词，整秒不带小数。
    private static String formatDuration(int ticks) {
        double seconds = ticks / 20.0;
        String secStr;
        if (ticks % 20 == 0) {
            secStr = (ticks / 20) + " 秒";
        } else {
            secStr = String.format("%.2f 秒", seconds);
        }
        return String.format("耗时 %,d 游戏刻 (%s)", ticks, secStr);
    }
}
