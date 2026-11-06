package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：持久 UUID 不能讓兩個同時載入的星律共用執行權。 */
class StellarCloudExecutorRuntimeTest {
    private static boolean bind(StellarCloudSavedData data, UUID executor, Object token) {
        Method method = assertDoesNotThrow(() -> StellarCloudSavedData.class.getMethod("bindExecutor", UUID.class, Object.class));
        return (boolean) assertDoesNotThrow(() -> method.invoke(data, executor, token));
    }
    private static boolean active(StellarCloudSavedData data, UUID executor, Object token) {
        Method method = assertDoesNotThrow(() -> StellarCloudSavedData.class.getMethod("executorActive", UUID.class, Object.class));
        return (boolean) assertDoesNotThrow(() -> method.invoke(data, executor, token));
    }
    private static void unload(StellarCloudSavedData data, UUID executor, Object token) {
        Method method = assertDoesNotThrow(() -> StellarCloudSavedData.class.getMethod("unloadExecutor", UUID.class, Object.class));
        assertDoesNotThrow(() -> method.invoke(data, executor, token));
    }
    @Test void duplicateUuidCannotClaimAnAlreadyActiveEndpoint() {
        var data = new StellarCloudSavedData(); UUID executor = UUID.randomUUID(); Object a = new Object(), b = new Object();
        assertTrue(bind(data, executor, a)); assertTrue(bind(data, executor, a));
        assertFalse(bind(data, executor, b)); assertTrue(active(data, executor, a)); assertFalse(active(data, executor, b));
    }
    @Test void foreignUnloadCannotRemoveAnotherEndpointsToken() {
        var data = new StellarCloudSavedData(); UUID executor = UUID.randomUUID(); Object a = new Object(), b = new Object();
        assertTrue(bind(data, executor, a)); unload(data, executor, b);
        assertTrue(active(data, executor, a)); assertFalse(bind(data, executor, b));
    }
    @Test void legitimateUnloadAllowsTheReloadedEndpointToBindWithoutReleasingLeases() {
        var data = new StellarCloudSavedData(); UUID executor = UUID.randomUUID(); Object a = new Object(), b = new Object();
        var catalog = data.defaultLibrary(UUID.randomUUID()).catalog(); CompoundTag original = new CompoundTag(); original.putString("id", "pattern");
        UUID entry = catalog.upload(UUID.randomUUID(), original, new CompoundTag(), java.util.Set.of()).entry();
        assertTrue(catalog.claim(executor, entry)); assertTrue(bind(data, executor, a));
        unload(data, executor, a); assertFalse(active(data, executor, a));
        assertTrue(bind(data, executor, b)); assertTrue(catalog.owns(executor, entry));
    }
    @Test void runtimeTokensDoNotTravelThroughSavedDataSnapshots() {
        var data = new StellarCloudSavedData(); UUID executor = UUID.randomUUID(); Object token = new Object();
        assertTrue(bind(data, executor, token));
        var loaded = StellarCloudSavedData.load(data.save(new CompoundTag()));
        assertFalse(active(loaded, executor, token)); assertTrue(bind(loaded, executor, new Object()));
    }
}
