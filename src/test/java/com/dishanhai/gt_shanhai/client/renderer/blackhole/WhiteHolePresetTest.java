package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.dishanhai.gt_shanhai.api.whitehole.RadiationModel;
import com.dishanhai.gt_shanhai.api.whitehole.StandardSpacetime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WhiteHolePresetTest {
    @TempDir Path temp;

    @Test
    void cinematicSessionResumesWithoutCountingTimeInAnotherMode() {
        var session = new WhiteHoleRenderState.Session(23);
        session.applyCinematicPreset();
        assertEquals(WhiteHolePreset.CINEMATIC_WHITE_HOLE.settings(23), WhiteHolePreset.Settings.capture(session));
        assertEquals(1.25f, session.sample(10, false).jets());
        var last = session.sample(10.2, false);
        session.suspend();
        var restored = session.sample(10.5, false);
        assertEquals(last.time(), restored.time());
        assertEquals(1f, restored.mix());
    }

    @Test
    void roundTripsPhysicalAndVisualSettingsWithAnExactLongSeed() throws Exception {
        long seed = Long.MAX_VALUE - 2;
        var preset = WhiteHolePreset.QUANTUM_REBIRTH.settings(seed);
        Path file = temp.resolve("config/white_hole_preview.json");
        preset.save(file);
        var loaded = WhiteHolePreset.Settings.load(file);
        assertEquals(preset, loaded);
        var preview = new WhiteHoleRenderState.Session(17);
        preview.engine.setPaused(true);
        loaded.apply(preview);
        assertEquals(seed, preview.engine.parameters().seed());
        assertEquals(StandardSpacetime.QUANTUM_BOUNCE, preview.engine.parameters().spacetime());
        assertEquals(RadiationModel.Profile.QUANTUM_TRANSITION, preview.engine.parameters().radiation());
        assertTrue(preview.engine.isPaused());
        assertEquals(preset, WhiteHolePreset.Settings.capture(preview));
    }

    @Test
    void invalidImportDoesNotReplaceTheRunningPreset() throws Exception {
        var preview = new WhiteHoleRenderState.Session(-308);
        var original = WhiteHolePreset.Settings.capture(preview);
        Path file = temp.resolve("invalid.json");
        original.save(file);
        Files.writeString(file, Files.readString(file).replace("\"version\": 1", "\"version\": 17"));
        assertThrows(RuntimeException.class, () -> WhiteHolePreset.Settings.load(file).apply(preview));
        assertEquals(original, WhiteHolePreset.Settings.capture(preview));
    }
}
