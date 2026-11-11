package com.dishanhai.gt_shanhai.common.recipe.editor;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class ShanhaiVanillaRecipeTableTest {
    private static final ResourceLocation SHARED = new ResourceLocation("minecraft", "proxy_source");
    private static final ResourceLocation EDITED = new ResourceLocation("gt_shanhai", "edited");
    private static final ResourceLocation REMOVED = new ResourceLocation("gt_shanhai", "removed");
    private static final ResourceLocation CREATED = new ResourceLocation("gt_shanhai", "created");

    @Test
    void keepsNativeProxyOwnerAndUpdatesEntriesOwnedByTheEditedType() {
        RecipeType<?> nativeType = new RecipeType<>() {};
        RecipeType<?> editedType = new RecipeType<>() {};
        Recipe<?> nativeSource = recipe(nativeType);
        Recipe<?> replacement = recipe(editedType);
        Recipe<?> created = recipe(editedType);
        Map<ResourceLocation, Recipe<?>> byName = new LinkedHashMap<>(Map.of(
                SHARED, nativeSource, EDITED, recipe(editedType), REMOVED, recipe(editedType)));

        ShanhaiVanillaRecipeTable.updateByName(byName, editedType, Set.of(SHARED, REMOVED), Map.of(
                SHARED, recipe(editedType), EDITED, replacement, CREATED, created));

        assertSame(nativeSource, byName.get(SHARED));
        assertSame(replacement, byName.get(EDITED));
        assertSame(created, byName.get(CREATED));
        assertFalse(byName.containsKey(REMOVED));
    }

    private static Recipe<?> recipe(RecipeType<?> type) {
        return (Recipe<?>) Proxy.newProxyInstance(Recipe.class.getClassLoader(), new Class<?>[] {Recipe.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getType")) return type;
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
