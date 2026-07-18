package com.dishanhai.gt_shanhai.api.recipe;

import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialMyriadRecipeTypesTest {

    private static final Path RECIPE_TYPES_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "api", "recipe", "PrimordialMyriadRecipeTypes.java");

    @Test
    void recipeTypeApiKeepsRequiredVisibilityAndSignatures() throws ReflectiveOperationException {
        Class<PrimordialMyriadRecipeTypes> type = PrimordialMyriadRecipeTypes.class;

        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));

        assertRecipeTypeIdField(type.getField("TIER_2_ID"),
                new ResourceLocation("gtceu:primordial_myriad_ascension_tier_2"));
        assertRecipeTypeIdField(type.getField("TIER_1_ID"),
                new ResourceLocation("gtceu:primordial_myriad_ascension_tier_1"));

        assertRecipeTypeAccessor(type.getMethod("requireTier2"));
        assertRecipeTypeAccessor(type.getMethod("requireTier1"));

        Method require = type.getDeclaredMethod("require", ResourceLocation.class);
        assertTrue(Modifier.isPrivate(require.getModifiers()));
        assertTrue(Modifier.isStatic(require.getModifiers()));
        assertEquals(GTRecipeType.class, require.getReturnType());

        Constructor<?>[] constructors = type.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertTrue(Modifier.isPrivate(constructors[0].getModifiers()));
    }

    @Test
    void javaDoesNotRegisterRecipeTypes() throws IOException {
        String source = Files.readString(RECIPE_TYPES_SOURCE);

        assertFalse(source.contains("GTRecipeTypes.register"));
    }

    @Test
    void requireResolvedUsesRequestedIdAndReturnsLookupResult() {
        Object token = new Object();
        AtomicReference<ResourceLocation> lookedUpId = new AtomicReference<>();

        Object result = PrimordialMyriadRecipeTypes.requireResolved(
                PrimordialMyriadRecipeTypes.TIER_2_ID,
                id -> {
                    lookedUpId.set(id);
                    return token;
                });

        assertEquals(PrimordialMyriadRecipeTypes.TIER_2_ID, lookedUpId.get());
        assertSame(token, result);
    }

    @Test
    void requireResolvedRejectsMissingRecipeTypeWithFullId() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PrimordialMyriadRecipeTypes.requireResolved(
                        PrimordialMyriadRecipeTypes.TIER_1_ID,
                        id -> null));

        assertEquals("缺少启动期配方类型: " + PrimordialMyriadRecipeTypes.TIER_1_ID, error.getMessage());
    }

    private static void assertRecipeTypeIdField(Field field, ResourceLocation expected) throws IllegalAccessException {
        assertTrue(Modifier.isPublic(field.getModifiers()));
        assertTrue(Modifier.isStatic(field.getModifiers()));
        assertTrue(Modifier.isFinal(field.getModifiers()));
        assertEquals(ResourceLocation.class, field.getType());
        assertEquals(expected, field.get(null));
    }

    private static void assertRecipeTypeAccessor(Method method) {
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertTrue(Modifier.isStatic(method.getModifiers()));
        assertEquals(GTRecipeType.class, method.getReturnType());
    }
}
