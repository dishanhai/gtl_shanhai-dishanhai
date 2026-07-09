package com.dishanhai.gt_shanhai.api.guideme;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeEngine;
import guideme.PageAnchor;
import guideme.compiler.PageCompiler;
import guideme.compiler.tags.BlockTagCompiler;
import guideme.document.block.LytBlockContainer;
import guideme.document.block.LytParagraph;
import guideme.document.block.LytVBox;
import guideme.document.flow.LytFlowLink;
import guideme.document.flow.LytFlowText;
import guideme.libs.mdast.mdx.model.MdxJsxElementFields;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * &lt;RecipeTypeIndexLinked /&gt; — 可点击的配方类型索引（用于 recipe_index.md）。
 *
 * <p>数据来源与 RecipeTypeIndex 相同（{@link DShanhaiRecipeEngine#getRecipeTypeCounts()}），
 * 但每行是一个 {@link LytFlowLink}，点击后导航到 recipe/type/{slug}.md 子页面，
 * 该页面展示该类型的所有配方卡。</p>
 *
 * <p>属性（均可选）：</p>
 * <ul>
 *   <li>{@code title} — 区块标题，默认「配方类型索引」。</li>
 *   <li>{@code showId} — 是否在每行附带原始 recipeType id，默认 true。</li>
 *   <li>{@code showCount} — 是否显示每类配方数量，默认 true。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public class RecipeTypeIndexLinkedTagCompiler extends BlockTagCompiler {

    @Override
    public Set<String> getTagNames() {
        return Set.of("RecipeTypeIndexLinked");
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
                "RecipeTypeIndexLinked: 读取配方类型统计失败 - " + e.getMessage(), fields));
            return;
        }

        LytVBox box = new LytVBox();
        box.setGap(2);

        // 标题
        LytParagraph titlePara = new LytParagraph();
        titlePara.append(LytFlowText.of(title));
        box.append(titlePara);

        if (counts == null || counts.isEmpty()) {
            LytParagraph empty = new LytParagraph();
            empty.append(LytFlowText.of("（暂无数据：配方库尚未加载，或本页在专用服务器客户端打开）"));
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
        summary.append(LytFlowText.of("共 " + totalTypes + " 种机器类型 · " + totalRecipes + " 个山海配方"));
        box.append(summary);

        // 渲染每个类型行为可点击链接
        for (Map.Entry<String, Integer> entry : entries) {
            String type = entry.getKey();
            int count = entry.getValue();
            String displayName = recipeTypeDisplayName(type);
            String slug = typeToSlug(type);

            // 构建链接文本
            StringBuilder linkText = new StringBuilder();
            linkText.append("• ").append(displayName);
            if (showCount) {
                linkText.append(" · ").append(count).append(" 个配方");
            }
            if (showId && !type.equals(displayName)) {
                linkText.append("  (").append(type).append(")");
            }

            LytParagraph p = new LytParagraph();

            // 子页面 id = <当前页命名空间>:recipe/type/{slug}.md（从 guide 根算起的完整路径）
            PageAnchor anchor = resolvePageAnchor(compiler, "recipe/type/" + slug + ".md");
            if (anchor != null) {
                LytFlowLink link = new LytFlowLink();
                link.setPageLink(anchor);
                link.append(LytFlowText.of(linkText.toString()));
                p.append(link);
            } else {
                // 无法解析链接则降级为纯文本
                p.append(LytFlowText.of(linkText.toString()));
            }
            box.append(p);
        }

        parent.append(box);
    }

    /**
     * 将从 guide 根算起的页面路径解析为 PageAnchor。
     * PageCompiler.resolveId 会给不含命名空间的路径补上当前页命名空间，
     * 例如 "recipe/type/x.md" → "gt_shanhai:recipe/type/x.md"。
     */
    private static PageAnchor resolvePageAnchor(PageCompiler compiler, String pagePath) {
        try {
            ResourceLocation pageId = compiler.resolveId(pagePath);
            if (pageId == null) return null;
            return PageAnchor.page(pageId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将配方类型 ID 转换为文件名 slug。
     * 例如：primordial_matter_recombination → primordial_matter_recombination
     *       gtceu:assembler → assembler
     */
    private static String typeToSlug(String type) {
        if (type == null || type.isEmpty()) return "unknown";
        String result = type;
        int colon = result.indexOf(':');
        if (colon >= 0) {
            result = result.substring(colon + 1);
        }
        return result.replace('/', '_');
    }

    // 复用 DShanhaiItemTooltipAPI 的解析链：依次尝试多种 lang key，取不到就回退原始字符串。
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
