package com.dishanhai.gt_shanhai.client;

import guideme.Guide;
import guideme.GuideItemSettings;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
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

    /** 游戏运行时相对路径（相对于游戏根目录） */
    private static final Path DEV_GUIDES_DIR = Paths.get("config/gt_shanhai/guides");

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
            .build();

        GTDishanhaiMod.LOGGER.info("[Guide] 已注册 Guide，支持从 {} 热重载", DEV_GUIDES_DIR.toAbsolutePath());
    }

    private static void ensureDevDirExists() {
        try {
            if (!Files.exists(DEV_GUIDES_DIR)) {
                Files.createDirectories(DEV_GUIDES_DIR);
                GTDishanhaiMod.LOGGER.info(
                    "[Guide] 已创建热重载目录: {} — 将此目录下的 .md 文件优先于 jar 内资源展示",
                    DEV_GUIDES_DIR.toAbsolutePath());
            }
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[Guide] 无法创建热重载目录 {}: {}", DEV_GUIDES_DIR, e.getMessage());
        }
    }
}
