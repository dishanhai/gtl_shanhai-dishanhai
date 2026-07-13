package com.dishanhai.gt_shanhai.common.recipe;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.event.AddPackFindersEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 把 {@link DShanhaiRecipeCache} 落盘的 dishanhai_recipe_cache/ 常驻注入成一个数据包，
 * 每次开服都走 vanilla 原生数据包加载，不需要反射改 RecipeManager。
 */
public final class DShanhaiRecipePackFinder {
    private static final Logger LOG = LoggerFactory.getLogger("DShanhaiRecipePackFinder");
    private static final String PACK_ID = "dishanhai_recipe_cache";

    private DShanhaiRecipePackFinder() {}

    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) return;

        Path packRoot = DShanhaiRecipeCache.DATA_ROOT.getParent();
        if (!Files.isDirectory(packRoot) || !Files.exists(packRoot.resolve("pack.mcmeta"))) {
            return;
        }

        try {
            Pack.ResourcesSupplier supplier = FolderRepositorySource.detectPackResources(packRoot, false);
            Pack pack = Pack.readMetaAndCreate(PACK_ID, Component.literal("山海配方缓存（自动生成）"), true,
                    supplier, PackType.SERVER_DATA, Pack.Position.TOP, PackSource.BUILT_IN);
            if (pack == null) {
                LOG.warn("[DShanhaiRecipePackFinder] Pack.readMetaAndCreate 返回 null，跳过注入: {}", packRoot);
                return;
            }
            event.addRepositorySource(consumer -> consumer.accept(pack));
        } catch (Throwable t) {
            LOG.warn("[DShanhaiRecipePackFinder] 注入配方缓存数据包失败", t);
        }
    }
}
