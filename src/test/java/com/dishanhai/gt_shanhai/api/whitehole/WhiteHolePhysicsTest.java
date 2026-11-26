package com.dishanhai.gt_shanhai.api.whitehole;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static com.dishanhai.gt_shanhai.api.whitehole.SpacetimeModel.C_SQUARED;
import static org.junit.jupiter.api.Assertions.*;

class WhiteHolePhysicsTest {
    @Test
    void schwarzschildScaleAndPastHorizonOrientation() {
        double mass = 1.98847e30;
        double rg = SpacetimeModel.gravitationalRadiusMetres(mass);
        assertEquals(1476.6697, rg, .01);
        SpacetimeModel.Geometry geometry = StandardSpacetime.SCHWARZSCHILD.geometry(0, 0);
        assertEquals(2, geometry.horizon().radiusRg());
        assertEquals(3, geometry.positivePhotonOrbitRg());
        assertEquals(3, geometry.negativePhotonOrbitRg());
        assertEquals(5.196152422706632, StandardSpacetime.SCHWARZSCHILD_CRITICAL_IMPACT_RG, 1e-15);
        assertTrue(geometry.horizon().allowsFutureCrossing(HorizonModel.Crossing.INTERIOR_TO_EXTERIOR));
        assertFalse(geometry.horizon().allowsFutureCrossing(HorizonModel.Crossing.EXTERIOR_TO_INTERIOR));
    }

    @Test
    void extremalKerrHasDistinctPhotonOrbitsAndMirrorsWhenSpinChangesSign() {
        var forward = StandardSpacetime.KERR.geometry(1, 0);
        var reverse = StandardSpacetime.KERR.geometry(-1, 0);
        assertEquals(1, forward.horizon().radiusRg());
        assertEquals(1, forward.positivePhotonOrbitRg(), 1e-14);
        assertEquals(4, forward.negativePhotonOrbitRg(), 1e-14);
        assertEquals(forward.positivePhotonOrbitRg(), reverse.negativePhotonOrbitRg());
        assertEquals(forward.negativePhotonOrbitRg(), reverse.positivePhotonOrbitRg());
        double mass = 4.8e37;
        assertEquals(-.95, SpacetimeModel.dimensionlessSpin(mass, SpacetimeModel.angularMomentumSi(mass, -.95)), 2e-16);
        assertThrows(IllegalArgumentException.class, () -> StandardSpacetime.KERR.geometry(1.001, 0));
        assertThrows(IllegalArgumentException.class, () -> StandardSpacetime.SCHWARZSCHILD.geometry(.3, 0));
    }

    @Test
    void chargedAndDynamicalModelsReportAppropriateCharacteristicScales() {
        var charged = StandardSpacetime.REISSNER_NORDSTROM.geometry(0, 1);
        assertEquals(1, charged.horizon().radiusRg());
        assertEquals(2, charged.positivePhotonOrbitRg());
        assertEquals(charged, StandardSpacetime.REISSNER_NORDSTROM.geometry(0, -1));
        assertEquals(0, StandardSpacetime.outgoingVaidyaGuu(1.4, .7));
        assertEquals(-.65, StandardSpacetime.outgoingVaidyaGuu(4, .7));
        var trapping = StandardSpacetime.OUTGOING_VAIDYA.geometry(0, 0).horizon();
        assertEquals(HorizonModel.Kind.LOCAL_TRAPPING_SCALE, trapping.kind());
        assertThrows(IllegalStateException.class,
                () -> trapping.allowsFutureCrossing(HorizonModel.Crossing.INTERIOR_TO_EXTERIOR));
    }

    @Test
    void geodesicQuadratureConvergesAndApproachesTheAnalyticWeakFieldLimit() {
        // Independent high-precision adaptive quadrature of the Schwarzschild radial potential.
        double strongReference = 1.7193883102301686;
        double coarse = RelativisticRayTracer.schwarzschildDeflectionRadians(6, 8);
        double fine = RelativisticRayTracer.schwarzschildDeflectionRadians(6, 256);
        assertTrue(Math.abs(fine - strongReference) < Math.abs(coarse - strongReference) / 100);
        assertEquals(strongReference, fine, 1e-9);
        assertEquals(4e-6, RelativisticRayTracer.schwarzschildDeflectionRadians(1e6, 256), 2e-11);
        assertThrows(IllegalArgumentException.class, () -> RelativisticRayTracer.closestApproachRg(5.0));
        double radius = RelativisticRayTracer.closestApproachRg(StandardSpacetime.SCHWARZSCHILD_CRITICAL_IMPACT_RG + 1e-9);
        assertTrue(radius > 3 && radius < 3.0001);
    }

    @Test
    void outgoingVaidyaNullFlowStaysRegularAtTheLocalTrappingScale() {
        RelativisticRayTracer.VaidyaMass mass = u -> new RelativisticRayTracer.VaidyaMass.Sample(1 - .015 * u, -.015);
        var radial = new RelativisticRayTracer.VaidyaRay(0, 1.9, 0, -1, 0, 0);
        var exit = RelativisticRayTracer.advanceOutgoingVaidya(radial, mass, .5, 8);
        assertEquals(0, exit.retardedTime());
        assertEquals(2.4, exit.radiusRg(), 1e-14);
        assertEquals(0, RelativisticRayTracer.vaidyaNullHamiltonian(exit, mass));

        var oblique = new RelativisticRayTracer.VaidyaRay(0, 2, 0, -.125, -1, 1);
        var coarse = RelativisticRayTracer.advanceOutgoingVaidya(oblique, mass, 1, 12);
        var fine = RelativisticRayTracer.advanceOutgoingVaidya(oblique, mass, 1, 96);
        double coarseError = Math.abs(RelativisticRayTracer.vaidyaNullHamiltonian(coarse, mass));
        double fineError = Math.abs(RelativisticRayTracer.vaidyaNullHamiltonian(fine, mass));
        assertTrue(fineError < coarseError / 100);
        assertEquals(0, fineError, 1e-12);
        assertTrue(fine.radiusRg() > 2);
        assertTrue(fine.timeMomentum() < oblique.timeMomentum());
        assertEquals(oblique.angularMomentum(), fine.angularMomentum());
    }

    @Test
    void frequencyShiftIncludesGravitationalRedshiftAndBothDopplerSigns() {
        double gravity = RelativisticRadiation.schwarzschildFrequencyRatio(4, Double.POSITIVE_INFINITY);
        assertEquals(Math.sqrt(.5), gravity, 1e-15);
        assertTrue(gravity * RelativisticRadiation.dopplerFactor(.55, 1) > 1);
        assertTrue(gravity * RelativisticRadiation.dopplerFactor(.55, -1) < 1);
        assertEquals(.25, RelativisticRadiation.observedSpecificIntensity(2, .5));
        assertTrue(Double.isFinite(RelativisticRadiation.schwarzschildFrequencyRatio(Math.nextUp(2.0), 500)));
        assertThrows(IllegalArgumentException.class, () -> RelativisticRadiation.schwarzschildFrequencyRatio(2, 500));
        assertThrows(IllegalArgumentException.class, () -> RelativisticRadiation.lorentzFactor(1));
    }

    @Test
    void energyBudgetCountsRadiationRestMassAndKineticEnergyExactlyOnce() {
        var parameters = WhiteHoleParameters.defaults(92).withMass(100, .1)
                .withEmission(RadiationModel.Profile.CONSTANT, C_SQUARED, 3, .8, 35_000);
        var engine = new WhiteHoleEngine(parameters);
        engine.advance(2);
        var state = engine.snapshot();
        assertEquals(88, state.massKg(), 1e-12);
        assertEquals(6, state.massLossKgPerSecond(), 1e-14);
        assertEquals(2, state.radiatedJoules() / C_SQUARED, 1e-13);
        assertEquals(6, state.releasedRestEnergyJoules() / C_SQUARED, 1e-13);
        assertEquals(4, state.releasedKineticEnergyJoules() / C_SQUARED, 1e-13);

        engine.advance(5);
        engine.advance(5);
        engine.advance(5);
        state = engine.snapshot();
        double total = state.radiatedJoules() + state.releasedRestEnergyJoules() + state.releasedKineticEnergyJoules();
        assertEquals(90, total / C_SQUARED, 1e-12);
        assertEquals(10, state.massKg(), 1e-12);
        assertEquals(0, state.massLossKgPerSecond());
        assertFalse(engine.triggerBurst(1));
    }

    @Test
    void fixedSimulationStepsMatchAcrossFrameRatesAndInstancesRemainIndependent() {
        var compactFrames = WhiteHoleEngine.defaults(77);
        var slowerFrames = WhiteHoleEngine.defaults(77);
        var other = WhiteHoleEngine.defaults(81);
        assertTrue(compactFrames.triggerBurst(1.2));
        assertTrue(slowerFrames.triggerBurst(1.2));
        for (int i = 0; i < 144; i++) compactFrames.advance(1.0 / 144);
        for (int i = 0; i < 30; i++) slowerFrames.advance(1.0 / 30);
        assertEquals(compactFrames.snapshot(), slowerFrames.snapshot());
        assertEquals(120, compactFrames.snapshot().completedSteps());
        assertEquals(0, other.snapshot().completedSteps());
        assertEquals(81, other.snapshot().seed());
        assertTrue(other.snapshot().bursts().isEmpty());
    }

    @Test
    void burstCapacityPreservesEveryShellUntilItExpires() {
        var engine = WhiteHoleEngine.defaults(77);
        assertTrue(engine.triggerBurst(1));
        engine.advance(1);
        for (int i = 1; i < 5; i++) assertTrue(engine.triggerBurst(1));
        var accepted = engine.snapshot().bursts();

        assertFalse(engine.triggerBurst(1));
        assertEquals(accepted, engine.snapshot().bursts());

        for (int i = 0; i < 13; i++) engine.advance(1);
        engine.advance(.1);
        assertEquals(accepted.subList(1, 5), engine.snapshot().bursts());
        assertTrue(engine.triggerBurst(1));
        assertEquals(6, engine.snapshot().bursts().get(4).id());
    }

    @Test
    void pauseStepAndResumeDoNotRewindRenderTime() {
        var engine = WhiteHoleEngine.defaults(1234);
        engine.setPaused(true);
        engine.advance(1);
        assertEquals(0, engine.snapshot().simulationTimeSeconds());
        engine.stepOnce();
        double before = engine.snapshot().simulationTimeSeconds();
        assertEquals(1.0 / 120, before);
        engine.setPaused(false);
        engine.advance(.001);
        assertEquals(before, engine.snapshot().simulationTimeSeconds());
        engine.advance(.02);
        assertTrue(engine.snapshot().simulationTimeSeconds() > before);
        engine.reset();
        assertEquals(0, engine.snapshot().radiatedJoules());
        assertEquals(0, engine.snapshot().simulationTimeSeconds());
        assertEquals(1234, engine.snapshot().seed());
    }

    @Test
    void interruptedEmissionChangesRemainContinuousAndNotifyOnlyActiveSubscribers() {
        var engine = WhiteHoleEngine.defaults(27);
        var events = new ArrayList<WhiteHoleEngine.LifecycleEvent>();
        try (var subscription = engine.subscribe(events::add)) {
            engine.transitionTo(WhiteHoleEngine.Activity.HIGH_ACTIVITY, 1);
            engine.advance(.4);
            double before = engine.snapshot().radianceGain();
            assertTrue(before > 1 && before < 1.5);
            engine.transitionTo(WhiteHoleEngine.Activity.DORMANT, 1);
            engine.setParameters(engine.parameters().withEmission(time -> .1,
                    WhiteHoleParameters.NOMINAL_LUMINOSITY_WATTS, 0, .42, 30_000));
            engine.advance(.009);
            assertEquals(before, engine.snapshot().radianceGain(), .03);
            engine.advance(1.1);
            assertEquals(.004, engine.snapshot().radianceGain(), 1e-13);
            assertTrue(engine.triggerBurst(1));
            assertInstanceOf(WhiteHoleEngine.LifecycleEvent.TransitionStarted.class, events.get(0));
            assertInstanceOf(WhiteHoleEngine.LifecycleEvent.TransitionStarted.class, events.get(1));
            assertInstanceOf(WhiteHoleEngine.LifecycleEvent.BurstStarted.class, events.get(2));
        }
        engine.reset();
        assertEquals(3, events.size());
    }

    @Test
    void extensionsAndInvalidBoundaryInputs() {
        var api = new WhiteHoleAPI();
        var isolatedApi = new WhiteHoleAPI();
        api.registerRadiationModel("test:periodic", time -> time < 1 ? 1 : .5);
        assertFalse(isolatedApi.radiationModels().containsKey("test:periodic"));
        assertThrows(IllegalArgumentException.class, () -> api.registerRadiationModel("test:periodic", time -> 1));
        var params = WhiteHoleParameters.defaults(0);
        assertThrows(IllegalArgumentException.class, () -> params.withMass(Double.NaN, .3));
        assertThrows(IllegalArgumentException.class, () -> params.withMass(1e200, .3));
        assertThrows(IllegalArgumentException.class, () -> params.withEmission(time -> 1, 1, 1, 1, 800));
        assertThrows(IllegalArgumentException.class, () -> api.create(params.withEmission(time -> -1, 1, 1, .4, 800)));
    }
}
