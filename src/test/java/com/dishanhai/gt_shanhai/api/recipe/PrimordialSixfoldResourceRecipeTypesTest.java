package com.dishanhai.gt_shanhai.api.recipe;

import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialSixfoldResourceRecipeTypesTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "api", "recipe", "PrimordialSixfoldResourceRecipeTypes.java");

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

        Method injectableAccessor = type.getDeclaredMethod("requireLargeVoidPump", Function.class);
        assertTrue(Modifier.isStatic(injectableAccessor.getModifiers()));
        assertFalse(Modifier.isPublic(injectableAccessor.getModifiers()));

        Constructor<?>[] constructors = type.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertTrue(Modifier.isPrivate(constructors[0].getModifiers()));
    }

    @Test
    void injectableRequireLargeVoidPumpLooksUpTheExactGtceuIdAndReturnsTheResolvedType() {
        Object token = new Object();
        AtomicReference<ResourceLocation> lookedUpId = new AtomicReference<>();

        Object result = PrimordialSixfoldResourceRecipeTypes.requireLargeVoidPump(id -> {
            lookedUpId.set(id);
            return token;
        });

        assertEquals(new ResourceLocation("gtceu:large_void_pump"), lookedUpId.get());
        assertSame(token, result);
    }

    @Test
    void injectableRequireLargeVoidPumpRejectsMissingTypeInsteadOfReturningNull() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PrimordialSixfoldResourceRecipeTypes.requireLargeVoidPump(id -> null));

        assertEquals("缺少运行时配方类型: gtceu:large_void_pump", error.getMessage());
    }

    @Test
    void publicAccessorDelegatesRegistryLookupToTheTestedResolver() throws Exception {
        String source = Files.readString(SOURCE);
        String body = extractBlock(source, "public static GTRecipeType requireLargeVoidPump() {");
        Pattern registryDelegation = Pattern.compile("""
                return\\s+requireLargeVoidPump\\(\\s*id\\s*->\\s*
                GTRegistries\\.RECIPE_TYPES\\.get\\(id\\)\\s*\\)\\s*;
                """, Pattern.COMMENTS);

        assertTrue(registryDelegation.matcher(body).find(),
                "公开入口必须把 GT recipe type 注册表查找委托给可注入 resolver");
    }

    private static String extractBlock(String source, String declaration) {
        int declarationStart = source.indexOf(declaration);
        assertTrue(declarationStart >= 0, "缺少方法声明: " + declaration);
        int openBrace = source.indexOf('{', declarationStart);
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) return source.substring(openBrace, i + 1);
        }
        throw new AssertionError("方法体未闭合: " + declaration);
    }
}
