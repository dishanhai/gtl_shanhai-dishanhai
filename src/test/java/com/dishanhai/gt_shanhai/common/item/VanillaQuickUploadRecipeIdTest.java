package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.integration.jei.JeiPatternQuickEncodeButtons;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 山海：實際呼叫 JEI ID 擷取，重現分類 ID 與配方 ID 不同的情境。 */
class VanillaQuickUploadRecipeIdTest {

    @Test
    void vanillaRecipeIdWinsEvenWhenCategoryReturnsItsRecipeType() throws Exception {
        ResourceLocation id = new ResourceLocation("minecraft", "netherite_chestplate_smithing");
        AtomicBoolean categoryRead = new AtomicBoolean();
        Object layout = layout(new IdOnlyRecipe(id), new ResourceLocation("minecraft", "smithing"), categoryRead);

        assertEquals(id, extract(layout));
        assertFalse(categoryRead.get(), "原版配方不得被分類 ID 覆蓋");
    }

    @Test
    void thirdPartyWrapperStillUsesCategoryRegistryId() throws Exception {
        ResourceLocation id = new ResourceLocation("example", "wrapped_recipe");
        Object layout = layout(new Object(), id, new AtomicBoolean());
        assertEquals(id, extract(layout));
    }

    private static Object extract(Object layout) throws Exception {
        Method method = JeiPatternQuickEncodeButtons.class.getDeclaredMethod("extractRecipeId", IRecipeLayoutDrawable.class);
        method.setAccessible(true);
        return method.invoke(null, layout);
    }

    private static Object layout(Object recipe, ResourceLocation categoryId, AtomicBoolean categoryRead) {
        Object category = Proxy.newProxyInstance(IRecipeCategory.class.getClassLoader(),
                new Class<?>[] { IRecipeCategory.class }, (proxy, method, args) -> {
                    if (method.getName().equals("getRegistryName")) {
                        categoryRead.set(true);
                        return categoryId;
                    }
                    throw new AssertionError("Unexpected category call: " + method.getName());
                });
        return Proxy.newProxyInstance(IRecipeLayoutDrawable.class.getClassLoader(),
                new Class<?>[] { IRecipeLayoutDrawable.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "getRecipe" -> recipe;
                    case "getRecipeCategory" -> category;
                    default -> throw new AssertionError("Unexpected layout call: " + method.getName());
                });
    }

    private record IdOnlyRecipe(ResourceLocation id) implements Recipe<Container> {
        @Override public boolean matches(Container input, Level level) { throw new AssertionError(); }
        @Override public ItemStack assemble(Container input, RegistryAccess registry) { throw new AssertionError(); }
        @Override public boolean canCraftInDimensions(int width, int height) { throw new AssertionError(); }
        @Override public ItemStack getResultItem(RegistryAccess registry) { throw new AssertionError(); }
        @Override public ResourceLocation getId() { return id; }
        @Override public RecipeSerializer<?> getSerializer() { throw new AssertionError(); }
        @Override public RecipeType<?> getType() { throw new AssertionError(); }
    }
}
