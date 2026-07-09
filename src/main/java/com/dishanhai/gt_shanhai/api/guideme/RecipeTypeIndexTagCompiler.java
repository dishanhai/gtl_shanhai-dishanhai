package com.dishanhai.gt_shanhai.api.guideme;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeEngine;
import guideme.compiler.PageCompiler;
import guideme.compiler.tags.BlockTagCompiler;
import guideme.document.block.LytBlockContainer;
import guideme.document.block.LytParagraph;
import guideme.document.block.LytVBox;
import guideme.libs.mdast.mdx.model.MdxJsxElementFields;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * &lt;RecipeTypeIndex /&gt; — 机器索引页「配方类型自动生成」区块。
 *
 * <p>数据来源：{@link DShanhaiRecipeEngine#getRecipeTypeCounts()}，即所有 dishanhai:
 * 配方唯一必经的 safeAddRecipe 累积的类型统计。渲染时实时读取，配方增删自动反映，
 * 无需手写、无需重新生成。</p>
 *
 * <p>属性（均可选）：</p>
 * <ul>
 *   <li>{@code title} — 区块标题，默认「配方类型索引」。</li>
 *   <li>{@code showId} — 是否在每行附带原始 recipeType id（便于写 &lt;RecipeCard /&gt;），默认 true。</li>
 *   <li>{@code showCount} — 是否显示每类配方数量，默认 true。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public class RecipeTypeIndexTagCompiler extends BlockTagCompiler {

    @Override
    public Set<String> getTagNames() {
        return Set.of("RecipeTypeIndex");
    }

    @Override
    protected void compile(PageCompiler compiler, LytBlockContainer parent, MdxJsxElementFields fields) {
        String title = fields.getAttributeString("title", "配方类型索引");
        boolean showId = parseBoolean(fields, "showId", true);
        boolean showCount = parseBoolean(fields, "showCount", true);

        Map<String, Integer> counts;
        try {
            counts = DShanhaiRecipeEngine.getRecipeTypeCounts();
        } catch (Exception e) {
            parent.append(compiler.createErrorBlock(
                "RecipeTypeIndex: 读取配方类型统计失败 - " + e.getMessage(), fields));
            return;
        }

        LytVBox box = new LytVBox();
        box.setGap(2);

        // 标题
        LytParagraph titlePara = new LytParagraph();
        titlePara.appendText(title);
        box.append(titlePara);

        if (counts == null || counts.isEmpty()) {
            LytParagraph empty = new LytParagraph();
            empty.appendText("（暂无数据：配方库尚未加载，或本页在专用服务器客户端打开）");
            box.append(empty);
            parent.append(box);
            return;
        }

        // 按配方数降序排列
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        int totalTypes = entries.size();
        int totalRecipes = 0;
        for (Map.Entry<String, Integer> e : entries) totalRecipes += e.getValue();

        LytParagraph summary = new LytParagraph();
        summary.appendText("共 " + totalTypes + " 种机器类型 · " + totalRecipes + " 个山海配方");
        box.append(summary);

        for (Map.Entry<String, Integer> entry : entries) {
            String type = entry.getKey();
            int count = entry.getValue();
            String displayName = recipeTypeDisplayName(type);

            StringBuilder line = new StringBuilder();
            line.append("• ").append(displayName);
            if (showCount) {
                line.append(" · ").append(count).append(" 个配方");
            }
            if (showId && !type.equals(displayName)) {
                line.append("  (").append(type).append(")");
            }

            LytParagraph p = new LytParagraph();
            p.appendText(line.toString());
            box.append(p);
        }

        parent.append(box);
    }

    // 复用 DShanhaiItemTooltipAPI 的解析链：依次尝试多种 lang key，取不到就回退原始字符串。
    // type 可能带命名空间（primordial_power_generator:zero_point_energy）或裸名（assembler）。
    private static String recipeTypeDisplayName(String type) {
        String namespace;
        String path;
        int colon = type.indexOf(':');
        if (colon >= 0) {
            namespace = type.substring(0, colon);
            path = type.substring(colon + 1);
        } else {
            namespace = "gtceu";
            path = type;
        }

        String[] keys = new String[] {
                "gtceu." + path,
                "gtceu.recipe_type." + path,
                "recipe_type." + path,
                "gtceu.recipe_type." + namespace + "." + path,
                namespace + "." + path
        };
        for (String key : keys) {
            String translated = Component.translatable(key).getString();
            if (!translated.equals(key)) {
                return translated;
            }
        }
        return type;
    }

    private boolean parseBoolean(MdxJsxElementFields fields, String name, boolean defaultValue) {
        var attr = fields.getAttribute(name);
        if (attr == null) return defaultValue;
        if (attr.hasStringValue()) {
            return "true".equalsIgnoreCase(attr.getStringValue());
        } else if (attr.hasExpressionValue()) {
            return "true".equalsIgnoreCase(attr.getExpressionValue());
        }
        return defaultValue;
    }
}
