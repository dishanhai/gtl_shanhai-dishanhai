package com.dishanhai.gt_shanhai.common.saveedit;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ShanhaiPlayerDataEditorTransactionTest {
    @TempDir Path world;

    @Test
    void failedLevelDatReadRollsBackTheAlreadyWrittenPlayerData() throws Exception {
        Path player = Files.createDirectories(world.resolve("playerdata")).resolve("player.dat");
        CompoundTag original = new CompoundTag();
        original.putFloat("Health", 10f);
        NbtIo.writeCompressed(original, player.toFile());
        byte[] before = Files.readAllBytes(player);
        Files.writeString(world.resolve("level.dat"), "not a gzipped level file");
        ShanhaiPlayerDataEdit edit = new ShanhaiPlayerDataEdit();
        edit.health = 15f;

        var result = ShanhaiPlayerDataEditor.apply(player, edit, world.resolve("backup"), "run");

        assertFalse(result.ok());
        assertTrue(result.error().contains("leveldat_player_drop_failed"));
        assertTrue(result.error().contains("rollback_complete"));
        assertArrayEquals(before, Files.readAllBytes(player));
    }
}
