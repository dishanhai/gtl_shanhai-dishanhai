package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：原件與租約以真實 NBT 目錄驗證，不需啟動遊戲。 */
class StellarCloudCatalogTest {
    private CompoundTag pattern(String name) {
        CompoundTag tag = new CompoundTag(); tag.putString("id", name); tag.putInt("Count", 1); return tag;
    }
    @Test void uploadIsIdempotentAndCopiesOriginal() {
        var catalog = new StellarCloudCatalog(); var op = UUID.randomUUID(); var original = pattern("ae2:processing_pattern");
        var first = catalog.upload(op, original, new CompoundTag(), Set.of("gtceu:lathe"));
        original.putInt("Count", 99);
        assertTrue(first.inserted()); assertEquals(1, catalog.entry(first.entry()).original().getInt("Count"));
        assertFalse(catalog.upload(op, original, new CompoundTag(), Set.of()).inserted());
        assertEquals(1, catalog.size());
    }
    @Test void duplicateDoesNotConsumeAnotherOriginal() {
        var catalog = new StellarCloudCatalog(); var tag = pattern("ae2:processing_pattern");
        var a = catalog.upload(UUID.randomUUID(), tag, new CompoundTag(), Set.of("gtceu:lathe"));
        var b = catalog.upload(UUID.randomUUID(), tag, new CompoundTag(), Set.of("gtceu:lathe"));
        assertFalse(b.inserted()); assertEquals(a.entry(), b.entry()); assertEquals(1, catalog.size());
    }
    @Test void exclusiveLeaseBlocksWithdrawalAndForeignRelease() {
        var catalog = new StellarCloudCatalog(); UUID entry = catalog.upload(UUID.randomUUID(), pattern("a"), new CompoundTag(), Set.of("x")).entry();
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
        assertTrue(catalog.claim(owner, entry)); assertTrue(catalog.claim(owner, entry)); assertFalse(catalog.claim(other, entry));
        assertNull(catalog.withdraw(entry)); catalog.release(other, entry); assertTrue(catalog.owns(owner, entry));
        catalog.release(owner, entry); assertNotNull(catalog.withdraw(entry)); assertEquals(0, catalog.size());
    }
    @Test void roundTripKeepsLeasesTypesAndUploadReceipts() {
        var catalog = new StellarCloudCatalog(); UUID operation = UUID.randomUUID(), owner = UUID.randomUUID();
        UUID entry = catalog.upload(operation, pattern("a"), new CompoundTag(), Set.of("x", "y")).entry();
        catalog.claim(owner, entry); var loaded = StellarCloudCatalog.load(catalog.save());
        assertTrue(loaded.owns(owner, entry)); assertEquals(Set.of("x", "y"), loaded.entry(entry).types());
        assertFalse(loaded.upload(operation, pattern("b"), new CompoundTag(), Set.of()).inserted());
        assertEquals(1, loaded.size());
    }
    @Test void rejectsUnknownSchemaAndMalformedLease() {
        CompoundTag bad = new CompoundTag(); bad.putInt("schema", 100);
        assertThrows(IllegalArgumentException.class, () -> StellarCloudCatalog.load(bad));
        var catalog = new StellarCloudCatalog(); catalog.upload(UUID.randomUUID(), pattern("a"), new CompoundTag(), Set.of("x"));
        CompoundTag malformed = catalog.save(); malformed.getList("entries", 10).getCompound(0).putString("executor", "invalid");
        assertThrows(IllegalArgumentException.class, () -> StellarCloudCatalog.load(malformed));
    }
    @Test void typeIndexIncludesUnclassifiedButNotUnrelatedPatterns() {
        var catalog = new StellarCloudCatalog();
        UUID a = catalog.upload(UUID.randomUUID(), pattern("a"), new CompoundTag(), Set.of("x")).entry();
        UUID b = catalog.upload(UUID.randomUUID(), pattern("b"), new CompoundTag(), Set.of()).entry();
        catalog.upload(UUID.randomUUID(), pattern("c"), new CompoundTag(), Set.of("y"));
        assertEquals(Set.of(a,b), Set.copyOf(catalog.candidates(Set.of("x"))));
        assertTrue(catalog.candidates(Set.of()).isEmpty());
    }
    @Test void exposedNbtCannotMutateAuthorityAndWithdrawalDoesNotReplayUpload() {
        var catalog = new StellarCloudCatalog(); UUID op = UUID.randomUUID();
        var upload = catalog.upload(op, pattern("a"), new CompoundTag(), Set.of("x"));
        catalog.entry(upload.entry()).original().putString("id", "tampered");
        assertEquals("a", catalog.entry(upload.entry()).original().getString("id"));
        assertNotNull(catalog.withdraw(upload.entry()));
        assertFalse(catalog.upload(op, pattern("a"), new CompoundTag(), Set.of("x")).inserted());
        assertEquals(0, catalog.size());
    }
    @Test void reclassificationMovesTypeIndexWithoutChangingOriginalOrLease() {
        var catalog = new StellarCloudCatalog(); UUID owner = UUID.randomUUID();
        UUID id = catalog.upload(UUID.randomUUID(), pattern("a"), new CompoundTag(), Set.of("old")).entry();
        catalog.claim(owner, id); long revision = catalog.revision();
        catalog.reclassify(id, Set.of("new"));
        assertTrue(catalog.revision() > revision); assertTrue(catalog.candidates(Set.of("old")).isEmpty());
        assertEquals(java.util.List.of(id), catalog.candidates(Set.of("new")));
        assertTrue(catalog.owns(owner, id)); assertEquals("a", catalog.entry(id).original().getString("id"));
    }
    @Test void leaseChangesKeepCandidateContentRevisionStable() {
        var catalog = new StellarCloudCatalog(); UUID owner = UUID.randomUUID();
        UUID id = catalog.upload(UUID.randomUUID(), pattern("a"), new CompoundTag(), Set.of("x")).entry();
        long revision = catalog.revision(), leases = catalog.leaseRevision();
        assertTrue(catalog.claim(owner, id)); assertEquals(revision, catalog.revision());
        assertTrue(catalog.leaseRevision() > leases);
        catalog.release(owner, id); assertEquals(revision, catalog.revision());
    }
}
