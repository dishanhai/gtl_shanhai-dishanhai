package com.dishanhai.gt_shanhai.client.loading;

import com.dishanhai.gt_shanhai.mixin.ShanhaiLoadingDataPackMixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：直接執行正式 Mixin handler，驗證 supplier 呼叫數與原始物件保留。 */
class ShanhaiLoadingDataPackTest {
    @Test void namespacesIncludeGeneratedIdsWithoutMutatingOriginalDomainsOrRetainingOldReloads() throws Exception {
        var field = ShanhaiLoadingDataPackMixin.class.getDeclaredField("DATA"); field.setAccessible(true);
        Object previous = field.get(null);
        var method = ShanhaiLoadingDataPackMixin.class.getDeclaredMethod("shanhai$namespaces", PackType.class, CallbackInfoReturnable.class);
        method.setAccessible(true);
        var data = new java.util.HashMap<ResourceLocation, byte[]>();
        data.put(new ResourceLocation("custom_namespace", "recipes/a.json"), new byte[]{1});
        Set<String> original = Set.of("gtceu", "minecraft");
        try {
            field.set(null, data);
            var result = new CallbackInfoReturnable<Set<String>>("fixture", true, original);
            method.invoke(new ShanhaiLoadingDataPackMixin() {}, PackType.SERVER_DATA, result);
            assertEquals(Set.of("gtceu", "minecraft", "custom_namespace"), result.getReturnValue());
            assertEquals(Set.of("gtceu", "minecraft"), original);
            data.clear();
            result = new CallbackInfoReturnable<>("fixture", true, original);
            method.invoke(new ShanhaiLoadingDataPackMixin() {}, PackType.SERVER_DATA, result);
            assertEquals(original, result.getReturnValue());
            result = new CallbackInfoReturnable<>("fixture", true, original);
            method.invoke(new ShanhaiLoadingDataPackMixin() {}, PackType.CLIENT_RESOURCES, result);
            assertSame(original, result.getReturnValue()); assertFalse(result.isCancelled());
        } finally { field.set(null, previous); }
    }

    @SuppressWarnings("unchecked")
    private static void enumerate(List<ResourceLocation> resources, String namespace, Consumer<ResourceLocation> output) throws Exception {
        Method method = ShanhaiLoadingDataPackMixin.class.getDeclaredMethod("shanhai$namespace", Stream.class,
                Consumer.class, Operation.class, PackType.class, String.class, String.class, PackResources.ResourceOutput.class);
        method.setAccessible(true);
        Operation<Void> original = args -> {
            ((Stream<ResourceLocation>) args[0]).forEach((Consumer<ResourceLocation>) args[1]); return null;
        };
        method.invoke(new ShanhaiLoadingDataPackMixin() {}, resources.stream(), output, original,
                PackType.SERVER_DATA, namespace, "recipes/", null);
    }

    @Test void eachResourceIsEmittedExactlyOnceAcrossNamespacesAndReloadUsesFreshEntries() throws Exception {
        var first = new ResourceLocation("gtceu", "recipes/a.json");
        var second = new ResourceLocation("gt_shanhai", "recipes/a.json");
        var entries = new ArrayList<>(List.of(first, second));
        var emitted = new ArrayList<ResourceLocation>();
        for (String namespace : List.of("gtceu", "minecraft", "forge", "c", "gt_shanhai"))
            enumerate(entries, namespace, emitted::add);
        assertEquals(List.of(first, second), emitted);
        assertSame(first, emitted.getFirst()); assertSame(second, emitted.getLast());
        entries.clear(); entries.add(new ResourceLocation("gtceu", "recipes/b.json"));
        emitted.clear(); enumerate(entries, "gtceu", emitted::add);
        assertEquals(entries, emitted, "重載後不可回傳前一次資料或複製的舊內容");
    }

    @Test void fiftyThousandResourcesAcrossTenNamespacesOnlyCreateFiftyThousandSuppliers() throws Exception {
        var resources = java.util.stream.IntStream.range(0, 50_000)
                .mapToObj(i -> new ResourceLocation("namespace" + i % 10, "recipes/r" + i + ".json")).toList();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        for (int n = 0; n < 10; n++) {
            String namespace = "namespace" + n;
            enumerate(resources, namespace, id -> { assertEquals(namespace, id.getNamespace()); calls.incrementAndGet(); });
        }
        assertEquals(50_000, calls.get(), "原版無 namespace 篩選會呼叫 500,000 次");
    }
}
