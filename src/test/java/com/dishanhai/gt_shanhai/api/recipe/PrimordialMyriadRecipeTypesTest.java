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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialMyriadRecipeTypesTest {

    private static final Path RECIPE_TYPES_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "api", "recipe", "PrimordialMyriadRecipeTypes.java");
    private static final Path DISHANHAI_RECIPE_TYPES_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "api", "recipe", "DShanhaiRecipeTypes.java");

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

        Constructor<?>[] constructors = type.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertTrue(Modifier.isPrivate(constructors[0].getModifiers()));
    }

    @Test
    void myriadAscensionTypesAreRegisteredInJava() throws IOException, ReflectiveOperationException {
        Field tier2 = DShanhaiRecipeTypes.class.getField("PRIMORDIAL_MYRIAD_ASCENSION_TIER_2");
        Field tier1 = DShanhaiRecipeTypes.class.getField("PRIMORDIAL_MYRIAD_ASCENSION_TIER_1");
        assertEquals(GTRecipeType.class, tier2.getType());
        assertEquals(GTRecipeType.class, tier1.getType());

        String registrations = Files.readString(DISHANHAI_RECIPE_TYPES_SOURCE).replace("\r\n", "\n");
        assertTrue(registrations.contains(
                "PRIMORDIAL_MYRIAD_ASCENSION_TIER_2 = GTRecipeTypes.register(\"primordial_myriad_ascension_tier_2\", \"multiblock\")\n" +
                        "                .setMaxIOSize(4, 0, 4, 0)"));
        assertTrue(registrations.contains(
                "PRIMORDIAL_MYRIAD_ASCENSION_TIER_1 = GTRecipeTypes.register(\"primordial_myriad_ascension_tier_1\", \"multiblock\")\n" +
                        "                .setMaxIOSize(4, 0, 4, 0)"));

        String accessors = Files.readString(RECIPE_TYPES_SOURCE);
        assertTrue(accessors.contains("return DShanhaiRecipeTypes.PRIMORDIAL_MYRIAD_ASCENSION_TIER_2;"));
        assertTrue(accessors.contains("return DShanhaiRecipeTypes.PRIMORDIAL_MYRIAD_ASCENSION_TIER_1;"));
        assertFalse(accessors.contains("GTRegistries"));
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
