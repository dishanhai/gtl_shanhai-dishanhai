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
}
