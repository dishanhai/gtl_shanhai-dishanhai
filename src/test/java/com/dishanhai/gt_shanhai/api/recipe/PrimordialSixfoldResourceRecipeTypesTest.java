package com.dishanhai.gt_shanhai.api.recipe;

import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialSixfoldResourceRecipeTypesTest {

    @Test
    void apiExposesOnlyTheRequiredDynamicRecipeTypeContract() throws ReflectiveOperationException {
        Class<PrimordialSixfoldResourceRecipeTypes> type = PrimordialSixfoldResourceRecipeTypes.class;

        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));

        Field id = type.getField("LARGE_VOID_PUMP_ID");
        assertTrue(Modifier.isPublic(id.getModifiers()));
        assertTrue(Modifier.isStatic(id.getModifiers()));
        assertTrue(Modifier.isFinal(id.getModifiers()));
        assertEquals(ResourceLocation.class, id.getType());
        assertEquals(new ResourceLocation("gtceu:large_void_pump"), id.get(null));

        Method accessor = type.getMethod("requireLargeVoidPump");
        assertTrue(Modifier.isPublic(accessor.getModifiers()));
        assertTrue(Modifier.isStatic(accessor.getModifiers()));
        assertEquals(GTRecipeType.class, accessor.getReturnType());

        Constructor<?>[] constructors = type.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertTrue(Modifier.isPrivate(constructors[0].getModifiers()));
    }

    @Test
    void requireResolvedLooksUpTheExactGtceuIdAndReturnsTheResolvedType() {
        Object token = new Object();
        AtomicReference<ResourceLocation> lookedUpId = new AtomicReference<>();

        Object result = PrimordialSixfoldResourceRecipeTypes.requireResolved(id -> {
            lookedUpId.set(id);
            return token;
        });

        assertEquals(new ResourceLocation("gtceu:large_void_pump"), lookedUpId.get());
        assertSame(token, result);
    }

    @Test
    void requireResolvedRejectsMissingLargeVoidPumpInsteadOfReturningNull() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PrimordialSixfoldResourceRecipeTypes.requireResolved(id -> null));

        assertEquals("缺少运行时配方类型: gtceu:large_void_pump", error.getMessage());
    }
}
