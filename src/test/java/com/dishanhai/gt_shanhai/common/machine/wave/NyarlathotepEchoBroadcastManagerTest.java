package com.dishanhai.gt_shanhai.common.machine.wave;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：以真實廣播源驗證範圍、疊加上限與禁止源自身增幅。 */
class NyarlathotepEchoBroadcastManagerTest {
    private GravitationalWaveBroadcastManager.BroadcastSource echo(BlockPos pos, int radius, int output) throws Exception {
        Constructor<GravitationalWaveBroadcastManager.BroadcastSource> constructor =
                GravitationalWaveBroadcastManager.BroadcastSource.class.getConstructor(
                        BlockPos.class, int.class, int.class, int.class, int.class, boolean.class);
        return constructor.newInstance(pos, radius, 100, 64, output, true);
    }

    private int select(String method, List<GravitationalWaveBroadcastManager.BroadcastSource> sources, BlockPos pos) throws Exception {
        Method selection = GravitationalWaveBroadcastManager.class.getDeclaredMethod(method, Iterable.class, BlockPos.class);
        selection.setAccessible(true);
        return (int) selection.invoke(null, sources, pos);
    }

    @Test void overlappingSourcesChooseMaximumRatherThanMultiplying() throws Exception {
        var legacy = new GravitationalWaveBroadcastManager.BroadcastSource(new BlockPos(0, 0, 0), 320, 100, 16, 3);
        var echo = echo(new BlockPos(0, 0, 1), 1200, 5);
        assertEquals(5, select("fixedMultiplierAt", List.of(legacy, echo), new BlockPos(10, 0, 0)));
        assertEquals(5, select("fixedMultiplierAt", List.of(legacy, echo), new BlockPos(1199, 0, 1)));
        assertEquals(0, select("fixedMultiplierAt", List.of(echo), new BlockPos(1201, 0, 1)));
    }

    @Test void echoAccelerationDoesNotAlterLegacySourceBehaviorOrBoostItself() throws Exception {
        BlockPos center = BlockPos.ZERO;
        var legacy = new GravitationalWaveBroadcastManager.BroadcastSource(center, 320, 100, 16, 3);
        assertEquals(0, select("echoPowerAt", List.of(legacy), new BlockPos(1, 0, 0)));
        var echo = echo(center, 1200, 5);
        assertEquals(0, select("echoPowerAt", List.of(echo), center));
        assertEquals(0, select("fixedMultiplierAt", List.of(echo), center));
        assertEquals(50, select("echoPowerAt", List.of(echo), new BlockPos(600, 0, 0)));
        assertEquals(0, select("echoPowerAt", List.of(echo), new BlockPos(1201, 0, 0)));
    }
}
