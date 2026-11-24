package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import org.junit.jupiter.api.Test;

import static com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuKerrOrbit.*;
import static org.junit.jupiter.api.Assertions.*;

class GuixuKerrOrbitTest {
    @Test
    void accretionMovesTheTwoTracksInOppositeDirections() {
        assertEquals(100, accrete(98, Track.INNER));
        assertEquals(0, accrete(2, Track.OUTER));
        assertTrue(radius(Track.INNER, 90) < radius(Track.INNER, 20));
        assertTrue(radius(Track.OUTER, 90) > radius(Track.OUTER, 20));
    }

    @Test
    void theSameFeedYieldsDifferentProductsDependingOnSpinAndFieldHistory() {
        assertEquals(Product.PREFORM, run(Track.INNER, 20, 100, 100).product());
        assertEquals(Product.PREFORM, run(Track.OUTER, 20, 100, 100).product());
        assertEquals(Product.CORE, run(Track.INNER, 80, 100, 100).product());
        assertEquals(Product.LAMINATE, run(Track.OUTER, 80, 100, 100).product());
        assertEquals(Product.FRACTURED_CORE, run(Track.INNER, 80, 35, 100).product());
        assertEquals(Product.LAMINATE, run(Track.OUTER, 80, 35, 100).product());
    }

    @Test
    void completionIsFrozenWhileTheOutputIsBlocked() {
        Batch finished = run(Track.INNER, 80, 100, 100);
        assertTrue(finished.complete());
        assertSame(finished, finished.advance(0, 1));
    }

    @Test
    void thePanelForecastPreservesDeviationAlreadyAccumulated() {
        Batch batch = Batch.start(Track.INNER);
        for (int step = 0; step < STEPS / 2; step++) batch = batch.advance(80, 35);
        Batch forecast = batch.forecast(80, 100);
        assertEquals(Product.FRACTURED_CORE, forecast.product());
        assertTrue(forecast.recoveryMargin() < 0);
        assertEquals(run(Track.INNER, 80, 35, 100).recoveryMargin(), forecast.recoveryMargin(), 0.0001);
        assertEquals(Product.CORE, Batch.start(Track.INNER).forecast(accrete(50, Track.INNER), 100).product());
    }

    @Test
    void penroseGateUsesTheExistingFieldAndCollapseStates() {
        var field = new GuixuContainmentField();
        assertFalse(canProcess(field, false));
        field.open(false);
        assertTrue(canProcess(field, false));
        assertFalse(canProcess(field, true));
        for (int i = 0; i < 100; i++) field.tickSecond(false);
        assertFalse(canProcess(field, false));
        field.shutDown();
        field.open(true);
        assertTrue(canProcess(field, false));
    }

    private static Batch run(Track track, int spin, int firstHalfStability, int secondHalfStability) {
        Batch batch = Batch.start(track);
        for (int step = 0; step < STEPS; step++) {
            batch = batch.advance(spin, step < STEPS / 2 ? firstHalfStability : secondHalfStability);
        }
        return batch;
    }
}
