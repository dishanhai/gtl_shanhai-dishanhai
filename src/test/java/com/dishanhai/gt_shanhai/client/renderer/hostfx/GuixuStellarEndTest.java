package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class GuixuStellarEndTest {
    private static final double CAPTURE_END = GuixuStellarEnd.AFTERGLOW_END_SECONDS;
    private static final double QUASAR_FULLY_VISIBLE = GuixuStellarEnd.QUASAR_ENTRY_SECONDS + 4.0;

    @Test
    void horizonExpandsFromTheConfiguredRadiusAndSettlesWithinTheRenderLimit() {
        assertEquals(5.2F, GuixuStellarEnd.horizonRadius(5.2F, 0));
        assertTrue(GuixuStellarEnd.horizonRadius(5.2F, 8) > 5.2F);
        assertTrue(GuixuStellarEnd.horizonRadius(12, 30) > 12);
        assertTrue(GuixuStellarEnd.horizonRadius(12, 30) <= GuixuStellarEnd.MAX_HORIZON_RADIUS);
        assertEquals(GuixuStellarEnd.horizonRadius(5.2F, CAPTURE_END), GuixuStellarEnd.horizonRadius(5.2F, 300));
    }

    @Test
    void matterLeadsTheDimmingStarToTheHorizonAndTheStarNeverRespawns() {
        for (int i = 0; i < GuixuStellarEnd.STAR_COUNT; i++) {
            boolean quasar = GuixuStellarEnd.star(i, 0, 42).kind() == GuixuStellarEnd.BodyKind.QUASAR;
            double arrival = quasar ? QUASAR_FULLY_VISIBLE : 3;
            var before = GuixuStellarEnd.star(i, arrival, 42);
            assertTrue(before.brightness() > 0);
            assertEquals(0, before.body());
            assertEquals(0, before.leadingMatter());
            boolean siphoned = false;
            double previousBody = before.body();
            for (double seconds = arrival + .35; seconds < CAPTURE_END; seconds += .25) {
                var current = GuixuStellarEnd.star(i, seconds, 42);
                assertTrue(current.body() >= previousBody);
                assertTrue(current.leadingMatter() >= current.body());
                siphoned |= current.leadingMatter() > current.body() && current.matterBrightness() > 0;
                previousBody = current.body();
            }
            assertTrue(siphoned);
            var consumed = GuixuStellarEnd.star(i, CAPTURE_END, 42);
            assertEquals(0, consumed.brightness());
            assertEquals(0, consumed.matterBrightness());
            assertEquals(0, GuixuStellarEnd.star(i, 300, 42).brightness());
            var point = GuixuStellarEnd.position(consumed, 1, 8);
            assertEquals(8, Math.hypot(point.x(), point.z()), 1e-6);
            assertEquals(0, point.y(), 1e-6);
        }
    }

    @Test
    void finaleReachesPillarsRingsAndStationInOrderAndLeavesThemLit() {
        double pillarBase = GuixuStellarEnd.structureArrival(5, 26, 0);
        double pillarCap = GuixuStellarEnd.structureArrival(5, 67, 0);
        double innerFeed = GuixuStellarEnd.structureArrival(50, 0, 0);
        double outerFeed = GuixuStellarEnd.structureArrival(60, 0, 0);
        double outerCorner = GuixuStellarEnd.structureArrival(60, 0, 60);
        double station = GuixuStellarEnd.structureArrival(72, 11, 0);
        assertTrue(0 < pillarBase && pillarBase < pillarCap);
        assertTrue(pillarBase < innerFeed && innerFeed < outerFeed);
        assertTrue(outerFeed < outerCorner && outerCorner < station);
        assertEquals(pillarBase, GuixuStellarEnd.structureArrival(5, -26, 0), 1e-6);
        assertEquals(0, GuixuStellarEnd.structureCharge(0, pillarBase));
        assertTrue(GuixuStellarEnd.structureCharge(innerFeed + .5, innerFeed) > 0);
        assertEquals(0, GuixuStellarEnd.structureCharge(innerFeed + .5, station));
        assertEquals(1, GuixuStellarEnd.structureCharge(station + 3, station));
    }

    @Test
    void sampledFrameHandsOffFromInfallToQuasarAfterglowAndStructure() {
        double entry = GuixuStellarEnd.QUASAR_ENTRY_SECONDS;
        double impact = GuixuStellarEnd.QUASAR_CAPTURE_SECONDS;
        double finale = GuixuStellarEnd.STRUCTURE_START_SECONDS;
        for (boolean simple : new boolean[]{false, true}) {
            var infall = GuixuStellarEnd.sample(3, 15, simple);
            assertEquals(GuixuStellarEnd.Stage.INFALL, infall.stage());
            assertFalse(infall.stars().isEmpty());
            assertTrue(infall.stars().stream().noneMatch(star -> star.kind() == GuixuStellarEnd.BodyKind.QUASAR));

            var quasar = GuixuStellarEnd.sample(entry, 15, simple);
            assertEquals(GuixuStellarEnd.Stage.QUASAR, quasar.stage());
            assertTrue(quasar.stars().stream().anyMatch(star ->
                    star.kind() == GuixuStellarEnd.BodyKind.QUASAR && star.solidRadius() > 0));

            var afterglow = GuixuStellarEnd.sample(impact + .25, 15, simple);
            assertEquals(GuixuStellarEnd.Stage.AFTERGLOW, afterglow.stage());
            assertEquals(1, afterglow.waves().size());
            assertTrue(afterglow.accretionFeed() > 0);
            assertTrue(GuixuStellarEnd.sample(CAPTURE_END, 15, simple).waves().isEmpty());

            var structure = GuixuStellarEnd.sample(finale, 15, simple);
            assertEquals(GuixuStellarEnd.Stage.STRUCTURE, structure.stage());
            assertEquals(0, structure.structureAge());
            assertEquals(0F, structure.accretionFeed());
            assertTrue(structure.stars().isEmpty());
            assertTrue(structure.waves().isEmpty());
        }
    }

    @Test
    void simplifiedFramesKeepPhysicalFeedAndHiddenHorizonStillAllowsTheFinale() {
        for (double seconds = 3; seconds < CAPTURE_END; seconds += .25) {
            var full = GuixuStellarEnd.sample(seconds, 15, false);
            var simple = GuixuStellarEnd.sample(seconds, 15, true);
            assertEquals(full.accretionFeed(), simple.accretionFeed());
            assertEquals(full.waves(), simple.waves());
            assertEquals(full.outflow(), simple.outflow());
            assertTrue(full.stars().containsAll(simple.stars()));
        }
        double finale = GuixuStellarEnd.STRUCTURE_START_SECONDS;
        var hidden = GuixuStellarEnd.sample(finale + 6, 0, false);
        assertEquals(6, hidden.structureAge(), 1e-9);
        assertEquals(GuixuStellarEnd.Stage.STRUCTURE, hidden.stage());
        assertEquals(0F, hidden.horizonGlow());
        assertTrue(hidden.stars().isEmpty());
    }

    @Test
    void largeBodiesAreStillBeingConsumedAtThirtySeconds() {
        assertTrue(CAPTURE_END >= 45);
        int stillFalling = 0;
        for (int i = 0; i < GuixuStellarEnd.STAR_COUNT; i += 4) {
            var star = GuixuStellarEnd.star(i, 30, 45);
            if (star.body() > 0 && star.body() < 1) stillFalling++;
        }
        assertTrue(stillFalling >= 8);
    }

    @Test
    void simplifiedModeRetainsAllThirteenKindsOfSolidBodyWithinTheExistingBudget() {
        var kinds = EnumSet.noneOf(GuixuStellarEnd.BodyKind.class);
        int solids = 0;
        for (int i = 0; i < GuixuStellarEnd.STAR_COUNT; i += 2) {
            var star = GuixuStellarEnd.star(i, 3, 45);
            if (star.kind() == GuixuStellarEnd.BodyKind.GLINT || !GuixuStellarEnd.drawsSource(i, true)) continue;
            if (star.kind() == GuixuStellarEnd.BodyKind.QUASAR) {
                star = GuixuStellarEnd.star(i, QUASAR_FULLY_VISIBLE, 45);
            }
            kinds.add(star.kind());
            assertTrue(star.solidRadius() >= 1);
            solids++;
        }
        assertEquals(EnumSet.of(GuixuStellarEnd.BodyKind.STAR, GuixuStellarEnd.BodyKind.ROCKY,
                GuixuStellarEnd.BodyKind.GAS_GIANT, GuixuStellarEnd.BodyKind.RINGED_GIANT,
                GuixuStellarEnd.BodyKind.NEUTRON_STAR, GuixuStellarEnd.BodyKind.PULSAR,
                GuixuStellarEnd.BodyKind.WHITE_DWARF, GuixuStellarEnd.BodyKind.BLACK_HOLE,
                GuixuStellarEnd.BodyKind.QUASAR, GuixuStellarEnd.BodyKind.WHITE_HOLE,
                GuixuStellarEnd.BodyKind.THORNE_ZYTKOW, GuixuStellarEnd.BodyKind.ANTISTAR,
                GuixuStellarEnd.BodyKind.BLACK_DWARF), kinds);
        assertEquals(64, GuixuStellarEnd.STAR_COUNT);
        assertEquals(16, solids);
    }

    @Test
    void whiteHoleEjectaExpandsAndDilutesBeforeFading() {
        double innerAge = .3, outerAge = .9;
        float innerRadius = GuixuStellarEnd.whiteHoleShellRadius(innerAge);
        float outerRadius = GuixuStellarEnd.whiteHoleShellRadius(outerAge);
        float innerLight = GuixuStellarEnd.whiteHoleShellLight(innerAge);
        float outerLight = GuixuStellarEnd.whiteHoleShellLight(outerAge);
        assertTrue(outerRadius > innerRadius);
        assertTrue(innerLight > outerLight && outerLight > 0);
        assertEquals(innerLight * innerRadius * innerRadius, outerLight * outerRadius * outerRadius, 1e-6);
        assertEquals(0, GuixuStellarEnd.whiteHoleShellLight(0));
        assertEquals(0, GuixuStellarEnd.whiteHoleShellLight(1.6));
        assertTrue(GuixuStellarEnd.BodyKind.WHITE_HOLE.effectExtent()
                > GuixuStellarEnd.whiteHoleShellRadius(1.6));
    }

    @Test
    void antistarGammaEmissionDependsOnMatterContact() {
        assertEquals(0, GuixuStellarEnd.antistarAnnihilation(1F, 0F));
        assertEquals(0, GuixuStellarEnd.antistarAnnihilation(-1F, 1F));
        assertTrue(GuixuStellarEnd.antistarAnnihilation(1F, .7F) > GuixuStellarEnd.antistarAnnihilation(.5F, .7F));
        assertTrue(GuixuStellarEnd.antistarAnnihilation(1F, .7F) > GuixuStellarEnd.antistarAnnihilation(1F, .2F));
    }

    @Test
    void whiteHoleOutflowDimsAsItsSourceApproachesTheMainHorizon() {
        assertEquals(1F, GuixuStellarEnd.whiteHoleShift(5, 0));
        assertTrue(GuixuStellarEnd.whiteHoleShift(11, 8) < GuixuStellarEnd.whiteHoleShift(22, 8));
        assertEquals(0F, GuixuStellarEnd.whiteHoleShift(8, 8));
        assertEquals(0F, GuixuStellarEnd.captureFlash(atCapture(32, .1)));
        assertTrue(GuixuStellarEnd.captureLight(atCapture(32, .3)) > 0);
        assertEquals(0F, GuixuStellarEnd.captureLight(atCapture(32, 1)));
    }

    @Test
    void thorneZytkowShedsItsEnvelopeAndExposesASurvivingCore() {
        var giant = atCapture(36, -19);
        var stripping = atCapture(36, -13);
        var exposed = atCapture(36, -8);
        assertEquals(0F, GuixuStellarEnd.tzoCoreExposure(giant));
        assertTrue(GuixuStellarEnd.tzoCoreExposure(stripping) > .30F);
        assertEquals(1F, GuixuStellarEnd.tzoCoreExposure(exposed));
        assertTrue(exposed.solidRadius() > .5F && exposed.solidRadius() < giant.solidRadius() * .4F);
        assertTrue(exposed.matterBrightness() > stripping.matterBrightness());
    }

    @Test
    void antistarContactDuringStrippingPrecedesTheColdDwarfsFaintDebris() {
        var anti = atCapture(44, -13);
        assertTrue(anti.solidRadius() > 0 && anti.matterBrightness() > .5F);
        assertEquals(0F, GuixuStellarEnd.captureFlash(atCapture(44, .1)));
        assertEquals(0F, atCapture(56, -12).matterBrightness());
        assertTrue(atCapture(56, -3).matterBrightness() > .04F);
        assertTrue(atCapture(56, -3).matterBrightness() < atCapture(52, -3).matterBrightness());
        assertTrue(atCapture(56, -3).tidalStress() < atCapture(52, -3).tidalStress());
    }

    private static GuixuStellarEnd.Star atCapture(int index, double offset) {
        double capture = -GuixuStellarEnd.star(index, 0, 45).captureAge();
        return GuixuStellarEnd.star(index, capture + offset, 45);
    }

    @Test
    void exoticStellarSurfacesKeepTheCoreHiddenAndTheCooledDwarfDark() {
        var giant = GuixuStellarEnd.BodyKind.THORNE_ZYTKOW;
        var antistar = GuixuStellarEnd.BodyKind.ANTISTAR;
        var cold = GuixuStellarEnd.BodyKind.BLACK_DWARF;
        assertTrue(giant.radius > GuixuStellarEnd.BodyKind.STAR.radius);
        assertTrue(giant.luminousSurface());
        assertEquals(0, giant.jetLength());
        assertEquals(GuixuStellarEnd.BodyKind.STAR.surfaceTile(true), antistar.surfaceTile(true));
        assertFalse(cold.luminousSurface());
        assertFalse(cold.hasAccretionDisk());
    }

    @Test
    void theSingleQuasarEntersAfterOtherCapturesAndIsMuchLarger() {
        GuixuStellarEnd.Star quasar = null;
        float nextLargest = 0;
        double latestOtherImpact = 0;
        double lateArrival = QUASAR_FULLY_VISIBLE;
        for (int i = 0; i < GuixuStellarEnd.STAR_COUNT; i += 4) {
            var star = GuixuStellarEnd.star(i, 3, 45);
            if (star.kind() == GuixuStellarEnd.BodyKind.QUASAR) {
                assertNull(quasar);
                assertEquals(0, star.solidRadius());
                assertEquals(0, GuixuStellarEnd.star(i, GuixuStellarEnd.QUASAR_ENTRY_SECONDS - .05, 45).brightness());
                quasar = GuixuStellarEnd.star(i, lateArrival, 45);
            } else {
                nextLargest = Math.max(nextLargest, star.solidRadius() * star.kind().effectExtent());
                latestOtherImpact = Math.max(latestOtherImpact, 3 - star.captureAge());
            }
        }
        assertNotNull(quasar);
        assertTrue(quasar.solidRadius() * quasar.kind().effectExtent() >= nextLargest * 4);
        assertTrue(quasar.kind().diskWidth() >= 1.8F);
        assertTrue(quasar.kind().jetLength() >= 7.5F);
        assertTrue(lateArrival - quasar.captureAge() > latestOtherImpact);
        assertEquals(CAPTURE_END, lateArrival - quasar.captureAge() + GuixuStellarEnd.CAPTURE_PULSE_SECONDS, 1e-6);
    }

    @Test
    void quasarDiskAppearsWhenTheLastOtherBodyDisappearsWithoutWaitingForItsAfterglow() {
        double lastSink = 0;
        int quasar = -1;
        int last = -1;
        for (int i = 0; i < GuixuStellarEnd.STAR_COUNT; i++) {
            var star = GuixuStellarEnd.star(i, 0, 45);
            if (star.kind() == GuixuStellarEnd.BodyKind.QUASAR) {
                quasar = i;
            } else {
                double sink = -star.captureAge() + (star.kind() == GuixuStellarEnd.BodyKind.BLACK_HOLE ? .72 : 0);
                if (sink > lastSink) {
                    lastSink = sink;
                    last = i;
                }
            }
        }
        assertEquals(lastSink, GuixuStellarEnd.QUASAR_ENTRY_SECONDS, 1e-6);
        assertTrue(GuixuStellarEnd.drawsSource(last, true));
        assertEquals(0, GuixuStellarEnd.star(quasar, lastSink - .05, 45).brightness());
        assertTrue(GuixuStellarEnd.star(quasar, lastSink, 45).brightness() > .15F);
        assertTrue(GuixuStellarEnd.quasarIgnition(lastSink).disk() > .5F);
        assertEquals(0, GuixuStellarEnd.quasarIgnition(lastSink).jetReach());
    }

    @Test
    void quasarAccretionDiskLightsBeforeTheJetBurstExpandsBipolarly() {
        double entrance = GuixuStellarEnd.QUASAR_ENTRY_SECONDS;
        var dark = GuixuStellarEnd.quasarIgnition(entrance - .1);
        var disk = GuixuStellarEnd.quasarIgnition(entrance + .95);
        var launch = GuixuStellarEnd.quasarIgnition(entrance + 1.68);
        var settled = GuixuStellarEnd.quasarIgnition(entrance + 3.1);
        assertEquals(0, dark.disk());
        assertTrue(disk.disk() > .85F);
        assertEquals(0, disk.jetReach());
        assertEquals(0, disk.flare());
        assertTrue(launch.jetReach() > 0 && launch.jetReach() < 1);
        assertTrue(launch.flare() > .8F);
        assertEquals(1, settled.jetReach());
        assertEquals(0, settled.flare());
    }

    @Test
    void quasarAndPulsarJetPulsesTravelFromCoreToTipAndRepeat() {
        var pulsar = GuixuStellarEnd.BodyKind.PULSAR;
        var quasar = GuixuStellarEnd.BodyKind.QUASAR;
        double stable = GuixuStellarEnd.QUASAR_ENTRY_SECONDS + 2.78;
        assertEquals(0, GuixuStellarEnd.jetPulse(quasar, stable - .25, 0).light());

        var pulsarCore = GuixuStellarEnd.jetPulse(pulsar, 1.86, 0);
        var pulsarOuter = GuixuStellarEnd.jetPulse(pulsar, 2.65, 0);
        assertTrue(pulsarCore.position() < .25F && pulsarCore.light() > .65F);
        assertTrue(pulsarOuter.position() > .65F && pulsarOuter.light() > .65F);
        assertEquals(pulsarCore.position(), GuixuStellarEnd.jetPulse(pulsar, 3.46, 0).position(), 1e-6);

        var quasarCore = GuixuStellarEnd.jetPulse(quasar, stable + .38, 0);
        var quasarOuter = GuixuStellarEnd.jetPulse(quasar, stable + 1.62, 0);
        assertTrue(quasarCore.position() < .25F && quasarCore.light() > .65F);
        assertTrue(quasarOuter.position() > .65F && quasarOuter.light() > .65F);
        assertEquals(quasarCore.position(), GuixuStellarEnd.jetPulse(quasar, stable + 2.78, 0).position(), 1e-6);
        assertEquals(0, GuixuStellarEnd.jetPulse(quasar, stable + 2.21, 0).light());
    }

    @Test
    void waveBendsBackgroundStarlightOnlyAsTheFrontCrossesTheRay() {
        float[] camera = {0, 70, 0};
        var wave = new GuixuStellarEnd.GravityWave(24, 2, 1, 0);
        var bend = wave.bend(camera, 48, -70, 0);
        assertNotNull(bend);
        assertTrue(bend.x() > 1);
        assertEquals(0, bend.y(), 1e-6);
        assertTrue(bend.strength() > .75F);
        assertNull(wave.bend(camera, 80, -70, 0));
        assertNull(wave.bend(camera, 48, 35, 0));
        assertNull(new GuixuStellarEnd.GravityWave(36, 2, 1, 0).bend(camera, 48, -70, 0));
        assertNull(new GuixuStellarEnd.GravityWave(24, 2, 0, 0).bend(camera, 48, -70, 0));
    }

    @Test
    void stellarBodiesAreSubmittedAfterTheLensAndUseTheHorizonDepthMask() throws IOException {
        Path renderer = Path.of("src/main/java/com/dishanhai/gt_shanhai/client/renderer");
        String host = Files.readString(renderer.resolve("hostfx/GuixuHostFxRenderer.java"));
        String lens = Files.readString(renderer.resolve("blackhole/BlackHoleLensRenderer.java"));
        String stellar = Files.readString(renderer.resolve("hostfx/GuixuStellarEndRenderer.java"));
        String celestial = Files.readString(renderer.resolve("hostfx/GuixuCelestialRenderer.java"));
        assertTrue(host.contains("GuixuStellarEndRenderer.capture("));
        assertEquals(2, lens.split("GuixuStellarEndRenderer.flushAfterLens\\(", -1).length - 1);
        assertTrue(lens.contains("GuixuStellarEndRenderer.hasDeferredBodies(renderFrameSerial)"));
        assertTrue(lens.contains("writeCapturedDepth(f, shader)"));
        assertFalse(celestial.contains("drawHorizonDepth"));
        assertTrue(stellar.contains("celestial.drawBodies("));
    }

    @Test
    void stellarEndReplacesBackgroundInfallWithoutSwitchingOffTheBlackHole() throws IOException {
        Path hostfx = Path.of("src/main/java/com/dishanhai/gt_shanhai/client/renderer/hostfx");
        String host = Files.readString(hostfx.resolve("GuixuHostFxRenderer.java"));
        String stars = Files.readString(hostfx.resolve("GuixuStarRenderer.java"));
        assertTrue(host.contains("backgroundInfall = drawBlackHole && !stellarEndActive"));
        assertTrue(host.contains("backgroundInfall ? GuixuStarField.accretionFeed("));
        assertTrue(host.contains("jets, drawBlackHole, backgroundInfall,"));
        assertTrue(stars.contains("backgroundInfall && i < GuixuStarField.PARTICLE_COUNT"));
        assertTrue(stars.contains("if (backgroundInfall) {\n            drawAccretionTrails("));
        assertTrue(stars.contains("if (blackHoleEnabled && jets >"));
    }

    @Test
    void stellarBodiesAreLargerWhileAllPlanetRadiiStayTheSame() {
        assertTrue(GuixuStellarEnd.BodyKind.STAR.radius > 2.6F);
        assertTrue(GuixuStellarEnd.BodyKind.NEUTRON_STAR.radius > 1.34F);
        assertTrue(GuixuStellarEnd.BodyKind.PULSAR.radius > 1.40F);
        assertTrue(GuixuStellarEnd.BodyKind.WHITE_DWARF.radius > 1.62F);
        assertTrue(GuixuStellarEnd.BodyKind.BLACK_HOLE.radius > 1.97F);
        assertTrue(GuixuStellarEnd.BodyKind.QUASAR.radius > 7.55F);
        assertEquals(1.55F, GuixuStellarEnd.BodyKind.ROCKY.radius);
        assertEquals(3.45F, GuixuStellarEnd.BodyKind.GAS_GIANT.radius);
        assertEquals(3.15F, GuixuStellarEnd.BodyKind.RINGED_GIANT.radius);
    }

    @Test
    void companionKeepsItsShadowThroughContactAndLaunchesTheMergerWave() {
        int companion = 20;
        float horizon = 15;
        double impact = -GuixuStellarEnd.star(companion, 0, 45).captureAge();
        var approach = GuixuStellarEnd.star(companion, impact - 3, 45);
        var merging = GuixuStellarEnd.star(companion, impact, 45);
        var gone = GuixuStellarEnd.star(companion, impact + 1, 45);
        var approachCenter = GuixuStellarEnd.bodyPosition(approach, horizon);
        var mergerCenter = GuixuStellarEnd.bodyPosition(merging, horizon);
        assertTrue(approach.solidRadius() > 1);
        assertTrue(radius(approachCenter) > horizon);
        assertTrue(merging.solidRadius() > .25F);
        assertTrue(radius(mergerCenter) < horizon);
        assertEquals(0, gone.solidRadius());
        assertEquals(0, GuixuStellarEnd.captureLight(merging));
        assertEquals(0, GuixuStellarEnd.gravityWave(approach, horizon).light());
        assertTrue(GuixuStellarEnd.mergerRebound(impact + .28) > .95F);
        assertEquals(0, GuixuStellarEnd.mergerRebound(impact + 3));
        var first = GuixuStellarEnd.gravityWave(GuixuStellarEnd.star(companion, impact + .25, 45), horizon);
        var late = GuixuStellarEnd.gravityWave(GuixuStellarEnd.star(companion, impact + 2.5, 45), horizon);
        assertTrue(GuixuStellarEnd.sample(impact + .25, horizon, false).waves().contains(first));
        assertTrue(GuixuStellarEnd.sample(impact + 3.5, horizon, false).waves().stream()
                .noneMatch(wave -> Math.abs(wave.azimuth() - first.azimuth()) < 1e-6));
        assertTrue(first.light() > late.light() && late.light() > 0);
        assertTrue(late.radius() > first.radius() + 25);
        assertEquals(first.azimuth(), late.azimuth(), 1e-6);
        assertEquals(0, GuixuStellarEnd.gravityWave(GuixuStellarEnd.star(companion, impact + 3.5, 45), horizon).light());
    }

    @Test
    void quasarPassageStillLaunchesAnExpandingWave() {
        for (int i = 0; i < GuixuStellarEnd.STAR_COUNT; i++) {
            var source = GuixuStellarEnd.star(i, 0, 45);
            if (source.kind() == GuixuStellarEnd.BodyKind.BLACK_HOLE) continue;
            double impact = -source.captureAge();
            var first = GuixuStellarEnd.gravityWave(GuixuStellarEnd.star(i, impact + .25, 45), 15);
            if (source.kind() != GuixuStellarEnd.BodyKind.QUASAR) {
                assertEquals(0, first.light(), source.kind().name());
                continue;
            }
            var late = GuixuStellarEnd.gravityWave(GuixuStellarEnd.star(i, impact + 1.5, 45), 15);
            assertEquals(0, GuixuStellarEnd.gravityWave(GuixuStellarEnd.star(i, impact - .05, 45), 15).light());
            assertTrue(first.light() > .35F, source.kind().name());
            assertTrue(late.radius() > first.radius() + 9);
            assertTrue(late.light() > 0 && late.light() < first.light());
            assertEquals(first.azimuth(), late.azimuth(), 1e-6);
            assertTrue(GuixuStellarEnd.sample(impact + .25, 15, false).waves().contains(first), source.kind().name());
            assertTrue(GuixuStellarEnd.sample(impact - .05, 15, false).waves().stream()
                    .noneMatch(wave -> Math.abs(wave.azimuth() - first.azimuth()) < 1e-6));
            assertEquals(0, GuixuStellarEnd.gravityWave(GuixuStellarEnd.star(i, impact + 2.4, 45), 15).light(), 1e-6);
        }
        assertTrue(GuixuStellarEnd.sample(CAPTURE_END, 15, false).waves().isEmpty());
        assertTrue(GuixuStellarEnd.sample(CAPTURE_END - 1, 0, false).waves().isEmpty());
    }

    @Test
    void flybysDoNotAddImpactWavefrontsToTheCompanionMerger() {
        boolean mergerWaveAppears = false;
        for (double seconds = 24; seconds < GuixuStellarEnd.QUASAR_ENTRY_SECONDS; seconds += .125) {
            int waves = GuixuStellarEnd.sample(seconds, 15, false).waves().size();
            assertTrue(waves <= 1);
            mergerWaveAppears |= waves == 1;
        }
        assertTrue(mergerWaveAppears);
    }

    @Test
    void giantMatterReachesTheHorizonEarlierAndPersistsLongerThanRockyDebris() {
        int giant = 8, rocky = 16;
        double giantStart = -GuixuStellarEnd.star(giant, 0, 45).captureAge() - 24;
        double rockyStart = -GuixuStellarEnd.star(rocky, 0, 45).captureAge() - 24;
        assertTrue(GuixuStellarEnd.star(giant, giantStart + 10, 45).leadingMatter()
                > GuixuStellarEnd.star(rocky, rockyStart + 10, 45).leadingMatter());
        assertTrue(GuixuStellarEnd.star(giant, giantStart + 20, 45).matterBrightness()
                > GuixuStellarEnd.star(rocky, rockyStart + 20, 45).matterBrightness());
    }

    @Test
    void diffuseEnvelopesStripWhileTheRockyCrustStillHolds() {
        var cloud = atCapture(8, -20);
        var rock = atCapture(16, -20);
        assertEquals(0, cloud.body());
        assertEquals(0, rock.body());
        assertTrue(cloud.solidRadius() < .95F * atCapture(8, -24).solidRadius());
        assertEquals(atCapture(16, -24).solidRadius(), rock.solidRadius());
        var fractured = atCapture(16, -8);
        double unbrokenRadius = atCapture(16, -24).solidRadius() * (1 - fractured.body());
        assertTrue(fractured.solidRadius() < .80 * unbrokenRadius);
    }

    @Test
    void compactStarsResistDeformationUntilTheLateInfall() {
        var plasma = atCapture(0, -14);
        var neutron = atCapture(4, -14);
        var pulsar = atCapture(28, -8);
        assertEquals(plasma.body(), neutron.body(), 1e-12);
        assertTrue(plasma.solidRadius() > 0);
        assertTrue(plasma.transverseCompression() > .38F);
        assertEquals(0, neutron.transverseCompression());
        assertTrue(neutron.tidalStress() < plasma.tidalStress() * .20F);
        assertEquals(0, neutron.disruption(), 1e-6);
        assertTrue(pulsar.disruption() > neutron.disruption());
        var near = atCapture(4, -2);
        assertTrue(near.disruption() > .85F);
        assertTrue(near.solidRadius() > 2 * atCapture(4, -24).solidRadius() * (1 - near.body()));
    }

    @Test
    void stellarRemnantsRoundTheBlackHoleAndDepartBeforeFading() {
        var remnants = EnumSet.of(GuixuStellarEnd.BodyKind.NEUTRON_STAR, GuixuStellarEnd.BodyKind.PULSAR,
                GuixuStellarEnd.BodyKind.WHITE_DWARF, GuixuStellarEnd.BodyKind.BLACK_DWARF,
                GuixuStellarEnd.BodyKind.THORNE_ZYTKOW);
        for (int i = 0; i < GuixuStellarEnd.STAR_COUNT; i += 4) {
            var kind = atCapture(i, -20).kind();
            if (!remnants.contains(kind)) continue;
            for (float horizon : new float[] {1, 5.2F, GuixuStellarEnd.MAX_HORIZON_RADIUS}) {
                double nearest = Double.POSITIVE_INFINITY;
                double departing = 0;
                for (double age = -20; age <= 0; age += .125) {
                    var star = atCapture(i, age);
                    if (star.solidRadius() <= .025F) continue;
                    double distance = radius(GuixuStellarEnd.bodyPosition(star, horizon));
                    nearest = Math.min(nearest, distance);
                    departing = Math.max(departing, distance - nearest);
                    assertTrue(distance - star.solidRadius() * (1 + star.tidalStress()) > 1.27 * horizon,
                            kind + " crossed the inner disk at " + age);
                }
                assertTrue(departing > 3, kind + " did not visibly move back out after periapsis");
                var oldImpact = atCapture(i, .15);
                assertEquals(0F, GuixuStellarEnd.captureFlash(oldImpact), kind.name());
                assertEquals(0F, GuixuStellarEnd.captureLight(oldImpact), kind.name());
                assertEquals(0F, GuixuStellarEnd.gravityWave(oldImpact, horizon).light(), kind.name());
            }
        }
    }

    @Test
    void stellarStreamsConnectTheRemainingPhotosphereToTheDisk() {
        for (int index : new int[] {0, 44}) {
            final float horizon = GuixuStellarEnd.MAX_HORIZON_RADIUS;
            var star = atCapture(index, index == 0 ? -14 : -11);
            assertTrue(star.matterBrightness() > .4F);
            double bodyRadius = radius(GuixuStellarEnd.bodyPosition(star, horizon));
            var origin = GuixuStellarEnd.streamPosition(star, star.body(), horizon);
            assertTrue(radius(origin) > 1.6 * horizon);
            assertEquals(star.solidRadius() * (1 + star.tidalStress()), bodyRadius - radius(origin), 1e-6);
            assertEquals(1.27 * horizon, radius(GuixuStellarEnd.streamPosition(star, star.leadingMatter(), horizon)), 1e-6);
        }
    }

    @Test
    void inwardStreamsStopBeforeTheirSourcesDisappear() {
        for (int i = 0; i < GuixuStellarEnd.STAR_COUNT; i++) {
            boolean sawStream = false;
            for (double seconds = .025; seconds <= CAPTURE_END; seconds += .025) {
                var star = GuixuStellarEnd.star(i, seconds, 45);
                boolean visible = star.kind() == GuixuStellarEnd.BodyKind.GLINT
                        ? star.brightness() > GuixuStellarEnd.MIN_GLINT_LIGHT
                        : star.solidRadius() > GuixuStellarEnd.MIN_SOLID_RADIUS;
                if (visible) {
                    sawStream |= star.matterBrightness() > .001F;
                } else {
                    assertEquals(0F, star.matterBrightness(), star.kind() + " #" + i + " at " + seconds);
                    assertEquals(0F, GuixuStellarEnd.stellarOutflow(star), star.kind() + " #" + i + " at " + seconds);
                }
            }
            assertTrue(sawStream, "Missing stream for source " + i);
        }
    }

    @Test
    void ordinaryStarDisruptsFarOutsideTheHorizonAndTheDebrisFeedsTheInnerDisk() {
        final float horizon = GuixuStellarEnd.MAX_HORIZON_RADIUS;
        var intact = atCapture(0, -24);
        var stripped = atCapture(0, -14.4);
        var dispersed = atCapture(0, -12);
        assertTrue(stripped.solidRadius() > 0);
        assertTrue(stripped.solidRadius() < .5F * intact.solidRadius());
        assertEquals(0, dispersed.solidRadius());
        assertEquals(0, dispersed.brightness());
        assertTrue(radius(GuixuStellarEnd.bodyPosition(dispersed, horizon)) > 1.6 * horizon);
        assertEquals(0F, dispersed.matterBrightness());
        assertTrue(stripped.matterBrightness() > .8F);
        assertEquals(1, stripped.leadingMatter());
        var disk = GuixuStellarEnd.streamPosition(stripped, stripped.leadingMatter(), horizon);
        assertEquals(1.27 * horizon, radius(disk), 1e-6);
        assertEquals(0, disk.y(), 1e-6);
        var oldImpact = atCapture(0, .15);
        assertEquals(0, GuixuStellarEnd.captureFlash(oldImpact));
        assertEquals(0, GuixuStellarEnd.captureLight(oldImpact));
        assertEquals(0, GuixuStellarEnd.gravityWave(oldImpact, horizon).light());
    }

    @Test
    void ordinaryStarOutflowStartsAfterTheDebrisArrivesAndDiesWithTheSupply() {
        var falling = atCapture(0, -15.5);
        var firstReturn = atCapture(0, -14.7);
        assertTrue(falling.leadingMatter() < 1);
        assertEquals(1, firstReturn.leadingMatter());
        assertEquals(0, GuixuStellarEnd.stellarOutflow(falling));
        assertEquals(0, GuixuStellarEnd.stellarOutflow(firstReturn));
        float rising = GuixuStellarEnd.stellarOutflow(atCapture(0, -13.7));
        float peak = GuixuStellarEnd.stellarOutflow(atCapture(0, -13.45));
        float tail = GuixuStellarEnd.stellarOutflow(atCapture(0, -12.8));
        assertTrue(rising > 0 && rising < peak);
        assertTrue(peak > .95F);
        assertTrue(tail > 0 && tail < peak);
        assertEquals(0, GuixuStellarEnd.stellarOutflow(atCapture(0, -12)));
        assertEquals(0, GuixuStellarEnd.stellarOutflow(atCapture(4, -12)));
        assertTrue(GuixuStellarEnd.stellarOutflow(atCapture(4, -4.8)) > .10F);
        assertTrue(GuixuStellarEnd.stellarOutflow(atCapture(4, -4.8))
                < GuixuStellarEnd.stellarOutflow(atCapture(36, -8.3)));
        assertEquals(0F, GuixuStellarEnd.stellarOutflow(atCapture(4, 1.3)));
    }

    @Test
    void stellarDebrisCrestReachesItsDiskEntryBeforeTheAfterglowSweepsAround() {
        double arrival = -14.75;
        assertNull(GuixuStellarEnd.debrisSurge(atCapture(0, arrival - 1.5)));
        var upstream = GuixuStellarEnd.debrisSurge(atCapture(0, arrival - .9));
        var incoming = GuixuStellarEnd.debrisSurge(atCapture(0, arrival - .3));
        assertTrue(upstream.progress() < incoming.progress());
        assertTrue(incoming.streamLightAt(incoming.progress()) > .6F);
        assertEquals(0F, incoming.streamLightAt(0));
        assertEquals(0F, incoming.flash());
        assertEquals(0F, incoming.wake().light());

        var star = atCapture(0, arrival);
        var entry = GuixuStellarEnd.streamPosition(star, 1, 15);
        var flash = GuixuStellarEnd.debrisSurge(atCapture(0, arrival + .13));
        var afterglow = GuixuStellarEnd.debrisSurge(atCapture(0, arrival + 1.2));
        assertEquals(Math.atan2(entry.z(), entry.x()), Math.atan2(Math.sin(flash.impact()), Math.cos(flash.impact())), 1e-9);
        assertEquals(flash.impact(), afterglow.impact(), 1e-9);
        assertTrue(flash.flash() > .95F);
        assertEquals(0F, afterglow.flash());
        assertTrue(afterglow.wake().head() > flash.wake().head());
        assertTrue(afterglow.wake().light() > .4F);
        assertNull(GuixuStellarEnd.debrisSurge(atCapture(0, arrival + 2.5)));

        var strippedCore = GuixuStellarEnd.debrisSurge(atCapture(4, -7 + .13));
        assertTrue(strippedCore.flash() > 0 && strippedCore.flash() < flash.flash() * .2F);
        assertNull(GuixuStellarEnd.debrisSurge(atCapture(8, .13)));
    }

    @Test
    void compactSurfaceSpinKeepsPulsarHotspotsInPhaseWithTheBeam() {
        var pulsar = GuixuStellarEnd.BodyKind.PULSAR;
        var neutron = GuixuStellarEnd.BodyKind.NEUTRON_STAR;
        var dwarf = GuixuStellarEnd.BodyKind.WHITE_DWARF;
        var rocky = GuixuStellarEnd.BodyKind.ROCKY;
        double phase = 1.17;
        double turn = GuixuStellarEnd.PULSAR_ROTATION_SECONDS;
        assertEquals(phase, pulsar.surfaceSpin(0, phase), 1e-6);
        assertEquals(Math.PI / 2, pulsar.surfaceSpin(turn / 4, phase) - phase, 1e-6);
        assertEquals(Math.TAU, pulsar.surfaceSpin(turn, phase) - phase, 1e-6);
        assertTrue(neutron.surfaceSpin(1, phase) > dwarf.surfaceSpin(1, phase));
        assertTrue(dwarf.surfaceSpin(1, phase) > rocky.surfaceSpin(1, phase));
        assertTrue(rocky.surfaceSpin(1, phase) > phase);
        assertEquals(phase, GuixuStellarEnd.BodyKind.BLACK_HOLE.surfaceSpin(30, phase), 1e-6);
        assertEquals(phase, GuixuStellarEnd.BodyKind.QUASAR.surfaceSpin(30, phase), 1e-6);
    }

    @Test
    void whiteDwarfLightsOnApproachAndFeedsAStreamOnTheWayBackOut() {
        for (int i = 0; i < GuixuStellarEnd.STAR_COUNT; i += 4) {
            var source = GuixuStellarEnd.star(i, 0, 45);
            if (source.kind() != GuixuStellarEnd.BodyKind.WHITE_DWARF) continue;
            double impact = -source.captureAge();
            var far = GuixuStellarEnd.star(i, impact - 12, 45);
            var approach = GuixuStellarEnd.star(i, impact - 5, 45);
            var near = GuixuStellarEnd.star(i, impact - 2, 45);
            var gone = GuixuStellarEnd.star(i, impact + .25, 45);
            assertEquals(0, GuixuStellarEnd.whiteDwarfTidalLight(far));
            assertTrue(GuixuStellarEnd.whiteDwarfTidalLight(approach) > .25F);
            assertTrue(GuixuStellarEnd.whiteDwarfTidalLight(near) > .85F);
            assertTrue(near.matterBrightness() > .06F);
            assertEquals(0, gone.matterBrightness());
            assertEquals(0, gone.solidRadius());
        }
    }

    @Test
    void celestialAtlasKeepsHighResolutionTilesCompactAndSeamless() throws IOException {
        try (var data = getClass().getResourceAsStream("/assets/gt_shanhai/textures/guixu/celestial_surfaces.png")) {
            assertNotNull(data);
            byte[] png = data.readAllBytes();
            var atlas = ImageIO.read(new ByteArrayInputStream(png));
            int tileWidth = 640, tileHeight = 320;
            assertEquals(4 * tileWidth, atlas.getWidth());
            assertEquals(5 * tileHeight, atlas.getHeight());
            assertTrue(png.length <= 1024 * 1024, "The shared atlas must fit within 1 MiB");
            for (int tile = 0; tile < 20; tile++) {
                int x = (tile % 4) * tileWidth, y = (tile / 4) * tileHeight;
                for (int row = y; row < y + tileHeight; row++) {
                    assertEquals(atlas.getRGB(x, row), atlas.getRGB(x + tileWidth - 1, row));
                }
                for (int col = x + 1; col < x + tileWidth; col++) {
                    assertEquals(atlas.getRGB(x, y), atlas.getRGB(col, y));
                    assertEquals(atlas.getRGB(x, y + tileHeight - 1), atlas.getRGB(col, y + tileHeight - 1));
                }
            }
            for (var kind : EnumSet.of(GuixuStellarEnd.BodyKind.BLACK_HOLE, GuixuStellarEnd.BodyKind.QUASAR,
                    GuixuStellarEnd.BodyKind.WHITE_HOLE)) {
                int tile = kind.surfaceTile(true);
                int[] horizon = atlas.getRGB(tile % 4 * tileWidth, tile / 4 * tileHeight,
                        tileWidth, tileHeight, null, 0, tileWidth);
                assertTrue(Arrays.stream(horizon).allMatch(pixel -> pixel == 0xff000000), kind.name());
            }
            var smallBodies = new HashSet<Integer>();
            for (int tile : new int[]{GuixuPlanetSystems.ROCK_TILE, GuixuPlanetSystems.ICE_TILE,
                    GuixuPlanetSystems.ASTEROID_TILE}) {
                int[] pixels = atlas.getRGB(tile % 4 * tileWidth, tile / 4 * tileHeight,
                        tileWidth, tileHeight, null, 0, tileWidth);
                assertTrue(Arrays.stream(pixels).distinct().count() >= 8, "Missing surface detail " + tile);
                assertTrue(smallBodies.add(Arrays.hashCode(pixels)), "Small bodies need distinct maps");
            }
        }
    }

    @Test
    void tidalStrippingPrecedesShrinkageAndSolidBodiesStayOutsideTheHorizon() {
        for (int index = 0; index < GuixuStellarEnd.STAR_COUNT; index += 4) {
            var initial = GuixuStellarEnd.star(index, 3, 45);
            if (initial.kind() == GuixuStellarEnd.BodyKind.BLACK_HOLE) continue;
            double arrival = initial.kind() == GuixuStellarEnd.BodyKind.QUASAR ? QUASAR_FULLY_VISIBLE : 3;
            var intact = GuixuStellarEnd.star(index, arrival, 45);
            boolean deformedBeforeInfall = false;
            for (double seconds = arrival + .35; seconds < CAPTURE_END; seconds += .25) {
                var star = GuixuStellarEnd.star(index, seconds, 45);
                deformedBeforeInfall |= star.tidalStress() > 0 && star.body() == 0;
                assertTrue(star.solidRadius() <= intact.solidRadius());
                var center = GuixuStellarEnd.bodyPosition(star, 15);
                double distance = Math.sqrt(center.x() * center.x() + center.y() * center.y() + center.z() * center.z());
                assertTrue(distance - star.solidRadius() * (1 + star.tidalStress()) >= 15 - 1e-5);
            }
            assertTrue(deformedBeforeInfall);
            assertEquals(0, GuixuStellarEnd.star(index, CAPTURE_END, 45).solidRadius());
        }
    }

    @Test
    void solidBodyTracksKeepTidalSurfacesApartThroughTheEntireInfall() {
        int count = GuixuStellarEnd.STAR_COUNT / 4;
        var low = new GuixuStellarEnd.Point[count];
        var high = new GuixuStellarEnd.Point[count];
        var stars = new GuixuStellarEnd.Star[count];
        for (int frame = 0; frame <= CAPTURE_END * 40; frame++) {
            double seconds = frame / 40.0;
            for (int i = 0; i < count; i++) {
                var star = GuixuStellarEnd.star(i * 4, seconds, 45);
                stars[i] = star;
                if (star.solidRadius() <= .025F) continue;
                low[i] = GuixuStellarEnd.bodyPosition(star, 1);
                high[i] = GuixuStellarEnd.bodyPosition(star, GuixuStellarEnd.MAX_HORIZON_RADIUS);
            }
            for (int i = 0; i < count; i++) {
                if (stars[i].solidRadius() <= .025F) continue;
                for (int j = i + 1; j < count; j++) {
                    if (stars[j].solidRadius() <= .025F) continue;
                    // Fixed-time positions are linear in horizon radius. Check the closest point
                    // over the full supported horizon range, including interior minima.
                    double x = low[i].x() - low[j].x(), y = low[i].y() - low[j].y(), z = low[i].z() - low[j].z();
                    double dx = high[i].x() - high[j].x() - x;
                    double dy = high[i].y() - high[j].y() - y;
                    double dz = high[i].z() - high[j].z() - z;
                    double span = dx * dx + dy * dy + dz * dz;
                    double fraction = span == 0 ? 0 : Math.clamp(-(x * dx + y * dy + z * dz) / span, 0, 1);
                    double closest = (x + dx * fraction) * (x + dx * fraction)
                            + (y + dy * fraction) * (y + dy * fraction) + (z + dz * fraction) * (z + dz * fraction);
                    double extent = .1 + stars[i].solidRadius() * (1 + stars[i].tidalStress())
                            + stars[j].solidRadius() * (1 + stars[j].tidalStress());
                    if (closest < extent * extent) {
                        fail(stars[i].kind() + " #" + i * 4 + " intersects " + stars[j].kind() + " #" + j * 4
                                + " at " + seconds + "s with horizon " + (1 + 14 * fraction));
                    }
                }
            }
        }
    }

    @Test
    void matterStreamStartsOnTheStretchedSurfaceAndEndsOnTheHorizon() {
        final double horizon = 8;
        var star = GuixuStellarEnd.star(12, 14, 45);
        var body = GuixuStellarEnd.position(star, star.body(), horizon);
        var root = GuixuStellarEnd.streamPosition(star, star.body(), horizon);
        assertTrue(star.solidRadius() > 0);
        assertEquals(star.solidRadius() * (1 + star.tidalStress()), radius(body) - radius(root), 1e-5);
        double previousRadius = radius(root);
        for (int i = 1; i <= 32; i++) {
            double progress = star.body() + (1 - star.body()) * i / 32;
            double currentRadius = radius(GuixuStellarEnd.streamPosition(star, progress, horizon));
            assertTrue(currentRadius >= horizon - 1e-6);
            assertTrue(currentRadius < previousRadius);
            previousRadius = currentRadius;
        }
        assertEquals(horizon, radius(GuixuStellarEnd.streamPosition(star, 1, horizon)), 1e-6);
    }

    @Test
    void directCapturesTriggerOneShortPulseAfterTheStreamArrives() {
        for (int index = 0; index < GuixuStellarEnd.STAR_COUNT; index++) {
            int pulses = 0;
            boolean glowing = false;
            double azimuth = Double.NaN;
            for (double time = 0; time <= CAPTURE_END + 1; time += .04) {
                var star = GuixuStellarEnd.star(index, time, 45);
                boolean active = GuixuStellarEnd.captureLight(star) > 0;
                if (active) {
                    assertEquals(1, star.leadingMatter(), 1e-6);
                    if (!glowing) {
                        pulses++;
                        azimuth = GuixuStellarEnd.captureAzimuth(star);
                    }
                    assertEquals(azimuth, GuixuStellarEnd.captureAzimuth(star), 1e-6);
                }
                glowing = active;
            }
            var kind = GuixuStellarEnd.star(index, 0, 45).kind();
            int expected = switch (kind) {
                case GLINT, STAR, BLACK_HOLE, NEUTRON_STAR, PULSAR, WHITE_DWARF, BLACK_DWARF, THORNE_ZYTKOW, ANTISTAR -> 0;
                default -> 1;
            };
            assertEquals(expected, pulses, kind.name());
            assertFalse(glowing);
        }
    }

    @Test
    void giantImpactHasABriefFlashAndALongerAfterglow() {
        var star = GuixuStellarEnd.star(8, 0, 45);
        double impact = -star.captureAge();
        assertEquals(0, GuixuStellarEnd.captureFlash(GuixuStellarEnd.star(8, impact - .05, 45)));
        assertTrue(GuixuStellarEnd.captureFlash(GuixuStellarEnd.star(8, impact + .10, 45)) > .8);
        assertEquals(0, GuixuStellarEnd.captureFlash(GuixuStellarEnd.star(8, impact + .6, 45)));
        assertTrue(GuixuStellarEnd.captureLight(GuixuStellarEnd.star(8, impact + 1.5, 45)) > 0);
    }

    @Test
    void captureWakeSweepsForwardFromImpactAndFadesAtBothEnds() {
        int index = 8;
        double impact = -GuixuStellarEnd.star(index, 0, 45).captureAge();
        var before = GuixuStellarEnd.captureWake(GuixuStellarEnd.star(index, impact - .1, 45));
        var hit = GuixuStellarEnd.star(index, impact, 45);
        double origin = GuixuStellarEnd.captureAzimuth(hit);
        var early = GuixuStellarEnd.captureWake(GuixuStellarEnd.star(index, impact + .35, 45));
        var late = GuixuStellarEnd.captureWake(GuixuStellarEnd.star(index, impact + 1.65, 45));
        var done = GuixuStellarEnd.captureWake(GuixuStellarEnd.star(index, impact + 2.5, 45));

        assertEquals(origin, before.angleAt(1), 1e-6);
        assertEquals(0, before.lightAt(.8));
        assertTrue(origin <= early.angleAt(0) && early.angleAt(0) < early.angleAt(1));
        assertTrue(early.angleAt(1) < late.angleAt(0));
        assertTrue(late.angleAt(1) - late.angleAt(0) > early.angleAt(1) - early.angleAt(0));
        assertEquals(0, early.lightAt(0));
        assertEquals(0, early.lightAt(1));
        assertTrue(early.lightAt(.8) > early.lightAt(.3));
        assertTrue(early.lightAt(.8) > late.lightAt(.8));
        assertEquals(0, done.lightAt(.8));
        assertEquals(0, GuixuStellarEnd.captureWake(GuixuStellarEnd.star(9, impact + .35, 45)).lightAt(.8));
    }

    @Test
    void ringTearOpensOnTheInwardOuterEdgeAndPreservesTheFarSide() {
        assertEquals(1, GuixuStellarEnd.ringTransmission(0, 1, 1), 1e-6);
        assertEquals(0, GuixuStellarEnd.ringTransmission(.75F, .70F, 1), 1e-6);
        assertTrue(GuixuStellarEnd.ringTransmission(.75F, .70F, 0) > .5);
        assertEquals(1, GuixuStellarEnd.ringTransmission(.75F, -1, 1), 1e-6);
    }

    @Test
    void atmosphereOutlineIsTangentToTheSphereForNearAndFarCameras() {
        for (float distance : new float[]{3.5F, 8, 80}) {
            float radius = 3;
            float offset = GuixuStellarEnd.limbOffset(radius, distance);
            float outline = GuixuStellarEnd.limbRadius(radius, distance);
            assertEquals(radius, Math.hypot(offset, outline), 1e-5);
            assertEquals(0, offset * (distance - offset) - outline * outline, 1e-5);
        }
    }

    private static double radius(GuixuStellarEnd.Point point) {
        return Math.sqrt(point.x() * point.x() + point.y() * point.y() + point.z() * point.z());
    }
}
