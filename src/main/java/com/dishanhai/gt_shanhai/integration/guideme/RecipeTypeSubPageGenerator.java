package com.dishanhai.gt_shanhai.integration.guideme;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.DShanhaiRecipeEngine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RecipesUpdatedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * 配方类型子页面生成器。
 * 在游戏启动时，为每个配方类型生成一个 .md 子页面，放在热重载目录。
 * 页面中包含该类型的所有配方卡。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class RecipeTypeSubPageGenerator {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("RecipeTypeSubPageGen");
    private static final Path HOT_RELOAD_BASE = Paths.get("config/gt_shanhai/guides/gt_shanhai/guide/recipe/type");

    /** 防止一次会话内重复生成（配方每次同步都会触发 RecipesUpdatedEvent） */
    private static volatile boolean generated = false;

    /**
     * 配方同步到客户端后触发。此时 RECIPE_TYPE_STATS 已由 safeAddRecipe 填充，
     * 且客户端 RecipeManager 已就绪，可枚举各类型的配方。
     */
    @SubscribeEvent
    public static void onRecipesUpdated(RecipesUpdatedEvent event) {
        if (generated) return;
        // 只有存在山海配方统计时才生成（避免菜单界面等空数据场景）
        Map<String, Integer> counts = DShanhaiRecipeEngine.getRecipeTypeCounts();
        if (counts == null || counts.isEmpty()) return;
        generated = true;
        generateSubPages();
    }

    /**
     * 生成所有配方类型子页面。
     */
    public static void generateSubPages() {
        try {
            Files.createDirectories(HOT_RELOAD_BASE);
        } catch (Exception e) {
            LOG.warn("[RecipeTypeSubPageGen] 无法创建子页面目录: {}", e.getMessage());
            return;
        }

        Map<String, Integer> counts = DShanhaiRecipeEngine.getRecipeTypeCounts();
        if (counts == null || counts.isEmpty()) {
            LOG.info("[RecipeTypeSubPageGen] 无配方，跳过子页面生成");
            return;
        }

        LOG.info("[RecipeTypeSubPageGen] 生成 {} 个配方类型子页面...", counts.size());
        int success = 0;
        for (String recipeType : counts.keySet()) {
            if (generateSinglePage(recipeType)) {
                success++;
            }
        }
        LOG.info("[RecipeTypeSubPageGen] 完成: {}/{} 页面", success, counts.size());
    }

    /**
     * 为单个配方类型生成子页面。
     * 文件名为 {slug}.md，其中 slug 是去掉命名空间后的类型名。
     */
    private static boolean generateSinglePage(String recipeType) {
        try {
            String slug = typeToSlug(recipeType);
            Path filePath = HOT_RELOAD_BASE.resolve(slug + ".md");

            // 获取中文显示名
            String displayName = recipeTypeDisplayName(recipeType);

            // 枚举该类型的所有配方，只保留山海配方（id 以 dishanhai: 开头）
            List<GTRecipe> allRecipes = DShanhaiRecipeEngine.getRecipesOfType(recipeType);
            List<GTRecipe> recipes = new ArrayList<>();
            for (GTRecipe r : allRecipes) {
                if (r != null && r.id != null && "dishanhai".equals(r.id.getNamespace())) {
                    recipes.add(r);
                }
            }
            // 若过滤后为空（可能 id 命名空间不同），回退显示全部
            if (recipes.isEmpty() && !allRecipes.isEmpty()) {
                recipes = allRecipes;
            }

            // 生成 .md 内容
            StringBuilder content = new StringBuilder();

            // 前言（navigation 元数据）
            content.append("---\n");
            content.append("navigation:\n");
            content.append("  title: \"").append(escapeMdString(displayName)).append("\"\n");
            content.append("  parent: recipe/recipe_index.md\n");
            content.append("categories:\n");
            content.append("  - gt_shanhai\n");
            content.append("  - recipe_type\n");
            content.append("---\n\n");

            // 标题与说明
            content.append("# ").append(displayName).append(" 配方\n\n");
            if (!recipeType.equals(displayName)) {
                content.append("> 机器类型 ID：`").append(recipeType).append("`\n\n");
            }
            content.append("共 ").append(recipes.size()).append(" 个配方。\n\n");

            // 配方列表
            if (recipes.isEmpty()) {
                content.append("（暂无配方）\n");
            } else {
                content.append("## 全部配方\n\n");
                // 渲染该类型下所有山海配方卡
                for (int i = 0; i < recipes.size(); i++) {
                    GTRecipe recipe = recipes.get(i);
                    String recipeId = recipe.id.toString();
                    String recipeTitle = displayName + " #" + (i + 1);

                    content.append("<RecipeCard\n");
                    content.append("  recipeType=\"").append(recipeType).append("\"\n");
                    content.append("  recipeId=\"").append(recipeId).append("\"\n");
                    content.append("  title=\"").append(recipeTitle).append("\"\n");
                    content.append("  showEU={true}\n");
                    content.append("  showDuration={true}\n");
                    content.append("/>\n\n");
                }
            }

            // 写入文件
            try (BufferedWriter writer = Files.newBufferedWriter(
                    filePath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.write(content.toString());
            }

            LOG.debug("[RecipeTypeSubPageGen] 生成: {} ({} 个配方)", filePath.getFileName(), recipes.size());
            return true;
        } catch (Exception e) {
            LOG.warn("[RecipeTypeSubPageGen] 生成 {} 失败: {}", recipeType, e.getMessage());
            return false;
        }
    }

    /**
     * 将配方类型 ID 转换为文件名 slug。
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

    /**
     * 获取配方类型的中文显示名（兜底回退链）。
     */
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
            net.minecraft.network.chat.Component translated = net.minecraft.network.chat.Component.translatable(key);
            String result = translated.getString();
            if (!result.equals(key)) {
                return result;
            }
        }
        return type;
    }

    /**
     * 转义 Markdown 字符串中的特殊字符。
     */
    private static String escapeMdString(String input) {
        if (input == null) return "";
        // 简单起见，只转义双引号
        return input.replace("\"", "\\\"");
    }
}
