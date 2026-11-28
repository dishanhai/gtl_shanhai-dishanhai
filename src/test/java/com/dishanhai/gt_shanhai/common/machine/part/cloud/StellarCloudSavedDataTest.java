package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：授權與隔離資料必須經過真實持久化往返。 */
class StellarCloudSavedDataTest {
    @Test void ownerReusesOneLibraryAndMembersSurviveReload() {
        var data = new StellarCloudSavedData(); UUID owner = UUID.randomUUID(), member = UUID.randomUUID();
        var library = data.defaultLibrary(owner); library.grant(member);
        assertEquals(library.id(), data.defaultLibrary(owner).id());
        assertNotEquals(library.id(), data.defaultLibrary(UUID.randomUUID()).id());
        assertTrue(library.authorized(owner)); assertTrue(library.authorized(member)); assertFalse(library.authorized(null));
        var loaded = StellarCloudSavedData.load(data.save(new CompoundTag()));
        assertTrue(loaded.healthy()); assertTrue(loaded.library(library.id()).authorized(member));
        loaded.library(library.id()).revoke(member); assertFalse(loaded.library(library.id()).authorized(member));
    }
    @Test void futureDataIsRetainedExactlyAndCannotCreateEmptyReplacement() {
        CompoundTag future = new CompoundTag(); future.putInt("schema", 9); future.putString("valuable", "retained");
        var loaded = StellarCloudSavedData.load(future);
        assertFalse(loaded.healthy()); assertEquals(future, loaded.save(new CompoundTag()));
        assertThrows(IllegalStateException.class, () -> loaded.defaultLibrary(UUID.randomUUID()));
        assertNull(loaded.library(UUID.randomUUID()));
    }
    @Test void malformedCatalogQuarantinesWholeFileWithoutDiscardingOriginals() {
        var data = new StellarCloudSavedData(); var library = data.defaultLibrary(UUID.randomUUID());
        CompoundTag original = new CompoundTag(); original.putString("id", "ae2:processing_pattern"); original.putByte("Count", (byte)1);
        library.catalog().upload(UUID.randomUUID(), original, new CompoundTag(), Set.of("gtceu:lathe"));
        CompoundTag saved = data.save(new CompoundTag());
        saved.getList("libraries", 10).getCompound(0).getCompound("catalog").putInt("schema", 99);
        var loaded = StellarCloudSavedData.load(saved);
        assertFalse(loaded.healthy()); assertEquals(saved, loaded.save(new CompoundTag()));
    }

    @Test void cleanRemovalReleasesAStoredLeaseWithoutConnectingToTheAeGrid() {
        var data = new StellarCloudSavedData();
        var library = data.defaultLibrary(UUID.randomUUID());
        var original = new CompoundTag();
        original.putString("id", "ae2:processing_pattern");
        original.putByte("Count", (byte) 1);
        var entry = library.catalog().upload(UUID.randomUUID(), original, new CompoundTag(), Set.of("lathe")).entry();
        UUID old = UUID.randomUUID();
        assertTrue(library.catalog().claim(old, entry));

        // Simulate breaking a persisted total assembly before its source resolves.
        var loaded = StellarCloudSavedData.load(data.save(new CompoundTag()));
        assertTrue(loaded.releaseDetached("gt_shanhai:" + library.id(), old, new Object(), Set.of(entry)));
        assertTrue(loaded.isDirty());
        var reopened = StellarCloudSavedData.load(loaded.save(new CompoundTag())).library(library.id()).catalog();
        UUID replacement = UUID.randomUUID();
        assertTrue(reopened.claim(replacement, entry));
        reopened.release(replacement, entry);
        assertEquals(original, reopened.withdraw(entry));
    }

    @Test void detachedDrainUnlocksAnAlreadyQueuedOriginalWithoutRepublishingIt() {
        var data = new StellarCloudSavedData();
        var library = data.defaultLibrary(UUID.randomUUID());
        var original = new CompoundTag();
        original.putString("id", "ae2:processing_pattern");
        original.putByte("Count", (byte) 1);
        var catalog = library.catalog();
        var entry = catalog.upload(UUID.randomUUID(), original, new CompoundTag(), Set.of("lathe")).entry();
        UUID old = UUID.randomUUID();
        assertTrue(catalog.claim(old, entry));
        assertTrue(catalog.requestWithdrawal(entry));
        assertTrue(data.releaseDetached("gt_shanhai:" + library.id(), old, new Object(), Set.of(entry)));
        assertFalse(catalog.candidates(Set.of("lathe")).contains(entry));
        assertEquals(original, catalog.withdraw(entry));
        assertNull(catalog.withdraw(entry));
    }

    @Test void relocationDrainsTheOldCatalogWithoutTouchingTheNewOne() {
        var data = new StellarCloudSavedData();
        var from = data.defaultLibrary(UUID.randomUUID());
        var to = data.defaultLibrary(UUID.randomUUID());
        String oldSource = "gt_shanhai:" + from.id(), newSource = "gt_shanhai:" + to.id();
        UUID executor = UUID.randomUUID();
        var original = new CompoundTag();
        original.putString("id", "ae2:processing_pattern");
        original.putByte("Count", (byte) 1);
        UUID entry = from.catalog().upload(UUID.randomUUID(), original, new CompoundTag(), Set.of()).entry();
        UUID other = to.catalog().upload(UUID.randomUUID(), original, new CompoundTag(), Set.of()).entry();
        assertTrue(from.catalog().claim(executor, entry));
        assertTrue(to.catalog().claim(executor, other));
        assertTrue(from.catalog().requestWithdrawal(entry));
        Object token = new Object();

        assertFalse(data.drainRetiredSlot(true, null, null, oldSource, executor, token, entry, true));
        assertFalse(data.drainRetiredSlot(true, null, oldSource, oldSource, executor, token, entry, true));
        assertTrue(data.drainRetiredSlot(true, null, newSource, oldSource, executor, token, entry, false));
        assertTrue(from.catalog().owns(executor, entry));
        assertTrue(data.drainRetiredSlot(true, null, newSource, oldSource, executor, token, entry, true));
        assertFalse(from.catalog().owns(executor, entry));
        assertTrue(to.catalog().owns(executor, other));
        assertEquals(original, from.catalog().withdraw(entry));
    }

    @Test void relocationCannotDrainASecondLiveExecutor() {
        var data = new StellarCloudSavedData();
        var from = data.defaultLibrary(UUID.randomUUID());
        var to = data.defaultLibrary(UUID.randomUUID());
        UUID executor = UUID.randomUUID();
        var original = new CompoundTag();
        original.putString("id", "ae2:processing_pattern");
        original.putByte("Count", (byte) 1);
        UUID entry = from.catalog().upload(UUID.randomUUID(), original, new CompoundTag(), Set.of()).entry();
        assertTrue(from.catalog().claim(executor, entry));
        Object live = new Object();
        assertTrue(data.bindExecutor(executor, live));
        assertFalse(data.drainRetiredSlot(true, null, "gt_shanhai:" + to.id(),
                "gt_shanhai:" + from.id(), executor, new Object(), entry, true));
        assertTrue(data.executorActive(executor, live));
        assertTrue(from.catalog().owns(executor, entry));
    }

    @Test void detachedReleaseCannotClearAnotherLiveExecutorOrAnUnrelatedLibrary() {
        var data = new StellarCloudSavedData();
        var library = data.defaultLibrary(UUID.randomUUID());
        var otherLibrary = data.defaultLibrary(UUID.randomUUID());
        var original = new CompoundTag();
        original.putString("id", "ae2:processing_pattern");
        original.putByte("Count", (byte) 1);
        var entry = library.catalog().upload(UUID.randomUUID(), original, new CompoundTag(), Set.of()).entry();
        UUID old = UUID.randomUUID();
        assertTrue(library.catalog().claim(old, entry));
        Object live = new Object();
        assertTrue(data.bindExecutor(old, live));
        assertFalse(data.releaseDetached("gt_shanhai:" + library.id(), old, new Object(), Set.of(entry)));
        assertTrue(data.releaseDetached("gt_shanhai:" + otherLibrary.id(), old, live, Set.of(entry)));
        assertTrue(data.releaseDetached("gt_shanhai:" + library.id(), UUID.randomUUID(), new Object(), Set.of(entry)));
        assertTrue(library.catalog().owns(old, entry));
        data.unloadExecutor(old, live);
        assertTrue(library.catalog().owns(old, entry));
    }
}
