package com.dishanhai.gt_shanhai.common.machine.starlifter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static com.dishanhai.gt_shanhai.common.machine.starlifter.StellarResonanceLinks.Result.*;
import static org.junit.jupiter.api.Assertions.*;

final class StellarResonanceLinksTest {
    private static final GlobalPos STAR = GlobalPos.of(Level.OVERWORLD, new BlockPos(10, 80, 20));
    private static final GlobalPos MODULE = GlobalPos.of(Level.NETHER, new BlockPos(10, 80, 20));
    private static final GlobalPos OTHER_STAR = GlobalPos.of(Level.OVERWORLD, new BlockPos(18, 80, 20));
    private static final GlobalPos OTHER_MODULE = GlobalPos.of(Level.END, new BlockPos(10, 80, 20));

    @Test
    void keepsBothEndsExclusiveAcrossDimensionsAndRestarts() {
        var links = new StellarResonanceLinks();
        assertEquals(LINKED, links.connect(STAR, MODULE));
        assertEquals(ALREADY_LINKED, links.connect(STAR, MODULE));
        assertEquals(STAR_OCCUPIED, links.connect(STAR, OTHER_MODULE));
        assertEquals(MODULE_OCCUPIED, links.connect(OTHER_STAR, MODULE));

        var reloaded = StellarResonanceLinks.load(links.save(new CompoundTag()));
        assertEquals(STAR, reloaded.starFor(MODULE));
        assertEquals(MODULE, reloaded.moduleFor(STAR));

        assertTrue(reloaded.unlink(MODULE));
        assertNull(reloaded.starFor(MODULE));
        assertNull(reloaded.moduleFor(STAR));
        assertEquals(LINKED, reloaded.connect(STAR, OTHER_MODULE));
        assertTrue(reloaded.unlink(STAR));
        assertFalse(reloaded.unlink(OTHER_MODULE));
    }
}
