package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.client.renderer.item.SelectedRecipePatternRenderer;
import com.gregtechceu.gtceu.api.item.ComponentItem;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

/**
 * 星律通配样板。GUI 里在本体材质四个角叠前几条配方的物品材质。
 */
public final class SelectedRecipePatternItem extends ComponentItem {

    public SelectedRecipePatternItem(Properties properties) {
        super(properties);
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return SelectedRecipePatternRenderer.getInstance();
            }
        });
    }
}
