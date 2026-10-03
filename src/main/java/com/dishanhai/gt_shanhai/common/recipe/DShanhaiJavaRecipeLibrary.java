package com.dishanhai.gt_shanhai.common.recipe;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;

import net.minecraft.data.recipes.FinishedRecipe;

import java.util.Objects;
import java.util.function.Consumer;

/** Java 配方的統一入口，由 GTCEu 的附屬配方載入階段呼叫。 */
public final class DShanhaiJavaRecipeLibrary {

    private DShanhaiJavaRecipeLibrary() {}

    public static void registerRecipes(Consumer<FinishedRecipe> provider) {
        Objects.requireNonNull(provider, "provider");
        // 後續各配方組在此呼叫，並透過 GTRecipeBuilder.save(provider) 輸出。
        GTDishanhaiMod.LOGGER.info("[JavaRecipeLibrary] 配方入口已載入，目前註冊 0 條配方");
    }
}
