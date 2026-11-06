package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：使用中的原件先撤下與排空，不能因租約永久阻擋取回。 */
class StellarCloudWithdrawalRequestTest {
    private static boolean request(StellarCloudCatalog catalog, UUID entry) {
        Method method = assertDoesNotThrow(() -> StellarCloudCatalog.class.getMethod("requestWithdrawal", UUID.class));
        return (boolean) assertDoesNotThrow(() -> method.invoke(catalog, entry));
    }
    private static boolean requested(StellarCloudCatalog.Row row) {
        Method method = assertDoesNotThrow(() -> StellarCloudCatalog.Row.class.getMethod("withdrawRequested"));
        return (boolean) assertDoesNotThrow(() -> method.invoke(row));
    }
    private static UUID add(StellarCloudCatalog catalog, String id) {
        CompoundTag original = new CompoundTag(); original.putString("id", id); original.putByte("Count", (byte)1);
        return catalog.upload(UUID.randomUUID(), original, new CompoundTag(), Set.of("x")).entry();
    }
    @Test void leasedRequestStopsCandidatesAndNewClaimsButPreservesAuthorityAndOriginal() {
        var catalog = new StellarCloudCatalog(); UUID entry = add(catalog, "pattern"), executor = UUID.randomUUID();
        assertTrue(catalog.claim(executor, entry)); assertTrue(request(catalog, entry));
        assertTrue(requested(catalog.entry(entry))); assertTrue(catalog.owns(executor, entry));
        assertTrue(catalog.candidates(Set.of("x")).isEmpty()); assertFalse(catalog.claim(executor, entry));
        assertFalse(catalog.claim(UUID.randomUUID(), entry)); assertNull(catalog.withdraw(entry));
        assertEquals("pattern", catalog.entry(entry).original().getString("id"));
    }
    @Test void requestSurvivesReloadAndCanBeWithdrawnExactlyOnceAfterDrain() {
        var catalog = new StellarCloudCatalog(); UUID entry = add(catalog, "pattern"), executor = UUID.randomUUID();
        catalog.claim(executor, entry); assertTrue(request(catalog, entry));
        var loaded = StellarCloudCatalog.load(catalog.save());
        assertTrue(requested(loaded.entry(entry))); assertTrue(loaded.owns(executor, entry));
        loaded.release(UUID.randomUUID(), entry); assertNull(loaded.withdraw(entry));
        loaded.release(executor, entry); assertNotNull(loaded.withdraw(entry)); assertNull(loaded.withdraw(entry));
    }
    @Test void repeatedRequestDoesNotInvalidateAgainOrAffectOtherEntries() {
        var catalog = new StellarCloudCatalog(); UUID entry = add(catalog, "pattern_a"), other = add(catalog, "pattern_b");
        assertTrue(request(catalog, entry)); long revision = catalog.revision();
        assertTrue(request(catalog, entry)); assertEquals(revision, catalog.revision());
        assertEquals(java.util.List.of(other), catalog.candidates(Set.of("x")));
        assertTrue(catalog.claim(UUID.randomUUID(), other)); assertFalse(request(catalog, UUID.randomUUID()));
    }
    @Test void recipeReclassificationCannotRepublishARequestedOriginal() {
        var catalog = new StellarCloudCatalog(); UUID entry = add(catalog, "pattern");
        assertTrue(request(catalog, entry)); catalog.reclassify(entry, Set.of("new_type"));
        assertTrue(requested(catalog.entry(entry))); assertTrue(catalog.candidates(Set.of("new_type")).isEmpty());
    }
}
