package com.dishanhai.gt_shanhai.client;

import guideme.Guide;
import guideme.GuideItemSettings;
import guideme.compiler.TagCompiler;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.guideme.RecipeCardTagCompiler;
import com.dishanhai.gt_shanhai.api.guideme.RecipeTypeIndexTagCompiler;
import com.dishanhai.gt_shanhai.api.guideme.RecipeTypeIndexLinkedTagCompiler;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * Guide 程序化注册
 * <p>
 * 支持从 {@code config/gt_shanhai/guides/} 热重载 .md 文件。
 * 该目录下的文件**覆盖** jar 内同路径资源。
 * </p>
 */
public final class GuideRegistration {

    /**
     * 游戏运行时 dev sources 根目录（相对游戏根）。
     * <p>必须对齐 jar 内 {@code folder("guides/gt_shanhai/guide")} 那一层，
     * 因为 GuideME 的 pageId = sourceFolder.relativize(file) 去掉 .md。
     * 这样 dev 文件 {@code .../gt_shanhai/guide/recipe/type/x.md}
     * → pageId {@code gt_shanhai:recipe/type/x.md}，与 jar 资源一致。</p>
     */
    private static final Path DEV_GUIDES_DIR = Paths.get("config/gt_shanhai/guides/gt_shanhai/guide");

    private GuideRegistration() {}

    public static void register() {
        ensureDevDirExists();

        // 复制 guide.json 的 item_settings（硬编码）
        GuideItemSettings itemSettings = new GuideItemSettings(
            Optional.of(Component.translatable("gt_shanhai.guide_name")),
            List.of(Component.translatable("gt_shanhai.guide_tooltip")
                        .withStyle(s -> s.withColor(0x555555))),  // dark_gray = 0x555555
            Optional.of(new ResourceLocation(GTDishanhaiMod.MOD_ID, "item/guide_book"))
        );

        Guide.builder(new ResourceLocation(GTDishanhaiMod.MOD_ID, "guide"))
            .defaultNamespace(GTDishanhaiMod.MOD_ID)
            .folder("guides/" + GTDishanhaiMod.MOD_ID + "/guide")   // jar 内 assets 路径不变
            .startPage(new ResourceLocation(GTDishanhaiMod.MOD_ID, "index.md"))
            .developmentSources(DEV_GUIDES_DIR, GTDishanhaiMod.MOD_ID)
            .watchDevelopmentSources(true)                          // 自动启动文件监听
            .itemSettings(itemSettings)
            .extension(TagCompiler.EXTENSION_POINT, new RecipeCardTagCompiler())
            .extension(TagCompiler.EXTENSION_POINT, new RecipeTypeIndexTagCompiler())
            .extension(TagCompiler.EXTENSION_POINT, new RecipeTypeIndexLinkedTagCompiler())
            .build();

        GTDishanhaiMod.LOGGER.info("[Guide] 已注册 Guide，支持从 {} 热重载", DEV_GUIDES_DIR.toAbsolutePath());
    }

    private static void ensureDevDirExists() {
        try {
            // 预建整棵目录树（含 recipe/type），确保 GuideME 文件监听器启动时
            // 就能递归监听到该子目录，进世界后运行时生成的子页面才会被实时收录。
            Path recipeTypeDir = DEV_GUIDES_DIR.resolve("recipe").resolve("type");
            if (!Files.exists(recipeTypeDir)) {
                Files.createDirectories(recipeTypeDir);
                GTDishanhaiMod.LOGGER.info(
                    "[Guide] 已创建热重载目录: {} — 将此目录下的 .md 文件优先于 jar 内资源展示",
                    DEV_GUIDES_DIR.toAbsolutePath());
            }
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Guide] 无法创建热重载目录 {}: {}", DEV_GUIDES_DIR, e.getMessage());
        }
    }
}
