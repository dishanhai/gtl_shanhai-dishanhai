package com.dishanhai.gt_shanhai.mixin;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.integration.jei.GTJEIPlugin;
import com.gregtechceu.gtceu.integration.jei.multipage.MultiblockInfoCategory;
import com.gregtechceu.gtceu.integration.jei.multipage.MultiblockInfoWrapper;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;

/**
 * Prevents one broken GTLCore pattern preview from dropping the complete JEI multiblock category.
 */
@Mixin(value = GTJEIPlugin.class, priority = 1100, remap = false)
public abstract class GTLCoreMultiblockInfoRegistrationMixin {

    private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai_jei_multiblock");

    @Redirect(method = "registerRecipes", at = @At(value = "INVOKE",
            target = "Lcom/gregtechceu/gtceu/integration/jei/multipage/MultiblockInfoCategory;registerRecipes(Lmezz/jei/api/registration/IRecipeRegistration;)V"),
            remap = false)
    private void gtShanhai$registerRecoverablePreviews(IRecipeRegistration registry) {
        AtomicInteger failed = new AtomicInteger();
        Minecraft minecraft = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() ->
                        MultiblockPreviewRegistrationHelper.collect(
                                GTRegistries.MACHINES.values(),
                                definition -> definition instanceof MultiblockMachineDefinition multiblock
                                        && multiblock.isRenderXEIPreview(),
                                definition -> new MultiblockInfoWrapper((MultiblockMachineDefinition) definition),
                                (definition, error) -> {
                                    failed.incrementAndGet();
                                    LOG.warn("[山海JEI] 跳過無法建立結構預覽的多方塊: {}",
                                            definition.getId(), error);
                                }), minecraft)
                .thenAcceptAsync(wrappers -> {
                    registry.addRecipes(MultiblockInfoCategory.RECIPE_TYPE, wrappers);
                    LOG.info("[山海JEI] GTCEu 多方塊結構預覽已註冊: {}, 跳過失敗: {}",
                            wrappers.size(), failed.get());
                }, minecraft)
                .exceptionally(error -> {
                    LOG.error("[山海JEI] 多方塊結構預覽註冊失敗", error);
                    return null;
                });
    }
}
