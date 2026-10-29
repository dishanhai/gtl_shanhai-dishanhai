package com.dishanhai.gt_shanhai.common.cover;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：攜帶綁定器放置覆蓋板時自動套用；座標快照不可共用可變 NBT。 */
class ShanhaiAeSteamBindingTest {
    @Test
    void rightClickTogglesBindingThenUnbindingEvenIfSelectedNetworkChanges() throws Exception {
        var method = ShanhaiAeSteamBinderBehavior.class.getDeclaredMethod(
                "toggleBinding", CompoundTag.class, CompoundTag.class);
        method.setAccessible(true);
        CompoundTag selected = binding(22);
        CompoundTag first = (CompoundTag) method.invoke(null, new CompoundTag(), selected);
        assertEquals(selected, first);
        assertNotSame(selected, first);
        CompoundTag second = (CompoundTag) method.invoke(null, first, binding(33));
        assertTrue(second.isEmpty(), "已綁定覆蓋板再次右鍵必須解除，不可直接覆寫成另一網路");
        CompoundTag third = (CompoundTag) method.invoke(null, second, binding(33));
        assertEquals(33L, third.getLong("pos"));
        assertTrue(((CompoundTag) method.invoke(null, new CompoundTag(), new CompoundTag())).isEmpty());
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/cover/ShanhaiAeSteamBinderBehavior.java"));
        assertTrue(source.contains("toggleBinding(current, selected)"), "右鍵實際路徑必須使用切換規則");
    }

    @Test
    void carriedBinderOverridesOldCoverBindingAndCopiesItsCoordinates() throws Exception {
        CompoundTag old = binding(11);
        CompoundTag carried = binding(22);
        CompoundTag selected = choose(old, List.of(carried));
        assertEquals(22L, selected.getLong("pos"));
        assertNotSame(carried, selected);
        carried.putLong("pos", 33);
        assertEquals(22L, selected.getLong("pos"));
        assertEquals(11L, old.getLong("pos"));
    }

    @Test
    void invalidCarriedTargetsAreSkippedAndSavedBindingSurvivesWithoutBinder() throws Exception {
        CompoundTag invalid = binding(1);
        invalid.putString("dimension", "invalid dimension");
        CompoundTag wrongPositionType = binding(2);
        wrongPositionType.putString("pos", "123");
        CompoundTag wrongSide = binding(3);
        wrongSide.putByte("side", (byte) 6);
        assertEquals(9L, choose(binding(7), List.of(invalid, wrongPositionType, wrongSide, binding(9)))
                .getLong("pos"));
        CompoundTag saved = binding(7);
        CompoundTag result = choose(saved, List.of());
        assertEquals(saved, result);
        assertNotSame(saved, result);
        assertTrue(choose(new CompoundTag(), List.of(invalid)).isEmpty());
    }

    @Test
    void blockNodeAndAutomaticPlacementAreWiredIntoNativeLifecycle() throws Exception {
        Path root = Path.of("src/main/java/com/dishanhai/gt_shanhai");
        Path block = root.resolve("common/machine/steam/ShanhaiAeSteamBinderMachine.java");
        assertTrue(Files.exists(block), "需要獨立可接線的綁定方塊");
        String machine = Files.readString(block);
        assertTrue(machine.contains("new GridNodeHolder(this)"));
        assertTrue(machine.contains("EnumSet.allOf(Direction.class)"));
        assertTrue(machine.contains("@Persisted"));
        assertTrue(Files.readString(root.resolve("common/machine/DShanhaiMachines.java"))
                .contains("ShanhaiAeSteamBinderMachine.register()"));
        String cover = Files.readString(root.resolve("common/cover/ShanhaiAeSteamCover.java"));
        assertTrue(cover.contains("ShanhaiAeSteamBinderBehavior.placementBinding(player, stack)"));
        String binder = Files.readString(root.resolve("common/cover/ShanhaiAeSteamBinderBehavior.java"));
        assertTrue(binder.contains("instanceof ShanhaiAeSteamBinderMachine"));
        assertTrue(binder.contains("DShanhaiItems.AE_STEAM_BINDER"));
        assertTrue(binder.contains("getInventory()"));
        assertFalse(binder.contains("GridHelper.getExposedNode"), "新選取只記錄專用綁定方塊");
    }

    private static CompoundTag binding(long pos) {
        CompoundTag target = new CompoundTag();
        target.putString("dimension", "minecraft:overworld");
        target.putLong("pos", pos);
        target.putByte("side", (byte) 2);
        return target;
    }

    private static CompoundTag choose(CompoundTag saved, List<CompoundTag> carried) throws Exception {
        var method = ShanhaiAeSteamBinderBehavior.class.getDeclaredMethod(
                "choosePlacementBinding", CompoundTag.class, List.class);
        method.setAccessible(true);
        return (CompoundTag) method.invoke(null, saved, carried);
    }
}
