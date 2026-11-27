package com.dishanhai.gt_shanhai.api.whitehole;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

import static com.dishanhai.gt_shanhai.api.whitehole.SimulationClock.STEP_SECONDS;
import static com.dishanhai.gt_shanhai.api.whitehole.SpacetimeModel.C_SQUARED;

/** One loader-independent simulation. Mutate and sample an instance on its owning simulation thread. */
public final class WhiteHoleEngine {
    /** Active shells supported by the compositor's primary slot and two packed burst uniforms. */
    public static final int MAX_ACTIVE_BURSTS = 5;
    private static final double PULSE_LIFETIME = 14;
    private static final double PROFILE_TRANSITION_SECONDS = .35;
    private static final double PARAMETER_RESPONSE = -Math.expm1(-STEP_SECONDS / .25);

    private final SimulationClock clock = new SimulationClock();
    private final List<Burst> bursts = new ArrayList<>(MAX_ACTIVE_BURSTS);
    private final List<Consumer<LifecycleEvent>> listeners = new ArrayList<>();
    private final LongConsumer updateStep = this::update;
    private long burstCount;
    private WhiteHoleParameters parameters;
    private double spin, previousSpin, charge, previousCharge, beta, previousBeta;
    private double temperature, previousTemperature;
    private double luminosity, previousLuminosity, requestedLuminosity, requestedRestMassFlow;
    private double massLossRate;
    private double radiationMassEquivalent, matterRestMass, matterKineticMassEquivalent;
    private Activity activity = Activity.STABLE_EMISSION;
    private double transitionStart, transitionSeconds, transitionOriginGain = activity.emissionMultiplier();
    private double profileTransitionStart = -PROFILE_TRANSITION_SECONDS;
    private double profileOriginGain;

    public WhiteHoleEngine(WhiteHoleParameters parameters) {
        this.parameters = Objects.requireNonNull(parameters);
        reset();
    }

    public static WhiteHoleEngine defaults(long seed) {
        return new WhiteHoleEngine(WhiteHoleParameters.defaults(seed));
    }

    public WhiteHoleParameters parameters() {
        return parameters;
    }

    /** A new reference mass establishes a new energy budget. Other edits preserve released energy. */
    public void setParameters(WhiteHoleParameters next) {
        Objects.requireNonNull(next);
        if (parameters.radiation() != next.radiation()) {
            double now = clock.completedTime();
            curve(next.radiation(), now);
            profileOriginGain = profileGain(now);
            profileTransitionStart = now;
        }
        if (parameters.referenceMassKg() != next.referenceMassKg()) clearEnergyBudget();
        if (parameters.spacetime() != next.spacetime()) {
            // The geometry samples belong to different models. A renderer can crossfade the images.
            spin = previousSpin = next.dimensionlessSpin();
            charge = previousCharge = next.dimensionlessCharge();
        }
        parameters = next;
    }

    public void advance(double realDeltaSeconds) {
        clock.advance(realDeltaSeconds, updateStep);
    }

    public void setTimeScale(double multiplier) {
        clock.setTimeScale(multiplier);
    }

    public void setPaused(boolean paused) {
        clock.setPaused(paused);
    }

    public boolean isPaused() {
        return clock.isPaused();
    }

    public void stepOnce() {
        clock.stepOnce(updateStep);
    }

    public void reset() {
        double initialGain = curve(parameters.radiation(), 0);
        clock.reset();
        clearEnergyBudget();
        bursts.clear();
        burstCount = 0;
        activity = Activity.STABLE_EMISSION;
        transitionStart = 0;
        transitionSeconds = 0;
        transitionOriginGain = activity.emissionMultiplier();
        profileTransitionStart = -PROFILE_TRANSITION_SECONDS;
        profileOriginGain = initialGain;
        spin = previousSpin = parameters.dimensionlessSpin();
        charge = previousCharge = parameters.dimensionlessCharge();
        beta = previousBeta = parameters.outflowBeta();
        temperature = previousTemperature = parameters.plasmaTemperatureKelvin();
        requestedLuminosity = parameters.luminosityWatts();
        requestedRestMassFlow = parameters.restMassFlowKgPerSecond();
        luminosity = previousLuminosity = remainingBudgetKg() > 0 ? requestedLuminosity * initialGain : 0;
        if (!listeners.isEmpty()) publish(new LifecycleEvent.Reset());
    }

    /** Starts an outgoing radiation shell. Returns false for zero strength, an exhausted budget or a full shell list. */
    public boolean triggerBurst(double strength) {
        WhiteHoleParameters.requireRange(strength, 0, 8, "burst strength");
        if (strength == 0 || remainingBudgetKg() <= 0 || bursts.size() >= MAX_ACTIVE_BURSTS) return false;
        Burst burst = new Burst(++burstCount, clock.renderTime(), strength);
        bursts.add(burst);
        if (!listeners.isEmpty()) publish(new LifecycleEvent.BurstStarted(burst));
        return true;
    }

    /** Smoothly redirects emission from the currently mixed state into the new state. */
    public void transitionTo(Activity target, double durationSeconds) {
        Objects.requireNonNull(target);
        WhiteHoleParameters.requireRange(durationSeconds, STEP_SECONDS, 60, "transition duration");
        double now = clock.completedTime();
        transitionOriginGain = activityGain(now);
        transitionStart = now;
        transitionSeconds = durationSeconds;
        activity = target;
        if (!listeners.isEmpty()) publish(new LifecycleEvent.TransitionStarted(now, target, durationSeconds));
    }

    public Subscription subscribe(Consumer<LifecycleEvent> listener) {
        Objects.requireNonNull(listener);
        Consumer<LifecycleEvent> subscription = listener::accept;
        listeners.add(subscription);
        return () -> listeners.remove(subscription);
    }

    private void publish(LifecycleEvent event) {
        for (Consumer<LifecycleEvent> listener : List.copyOf(listeners)) listener.accept(event);
    }

    public Snapshot snapshot() {
        double interpolation = clock.interpolation();
        double renderTime = clock.renderTime();
        double displaySpin = lerp(previousSpin, spin, interpolation);
        double displayCharge = lerp(previousCharge, charge, interpolation);
        double displayLuminosity = lerp(previousLuminosity, luminosity, interpolation);
        double currentMass = Math.max(1e-100, parameters.referenceMassKg() - releasedMassEquivalentKg());
        SpacetimeModel.Geometry geometry = parameters.spacetime().geometry(displaySpin, displayCharge);
        Burst latest = bursts.isEmpty() ? null : bursts.get(bursts.size() - 1);
        return new Snapshot(clock.step(), renderTime, currentMass, displaySpin, displayCharge,
                lerp(previousBeta, beta, interpolation), lerp(previousTemperature, temperature, interpolation),
                massLossRate, displayLuminosity, Math.min(16, displayLuminosity / WhiteHoleParameters.NOMINAL_LUMINOSITY_WATTS),
                parameters.seed(), activity, geometry, radiationMassEquivalent * C_SQUARED,
                matterRestMass * C_SQUARED, matterKineticMassEquivalent * C_SQUARED,
                latest == null ? -1 : renderTime - latest.startSeconds(), latest == null ? 0 : latest.strength(),
                List.copyOf(bursts));
    }

    private void update(long stepNumber) {
        double sampleTime = (stepNumber - .5) * STEP_SECONDS;
        double emissionGain = profileGain(sampleTime) * activityGain(sampleTime);
        double pulseGain = 0;
        for (int index = 0; index < bursts.size(); index++) {
            Burst burst = bursts.get(index);
            double age = sampleTime - burst.startSeconds();
            // Compact support avoids a discontinuity or residual watts when a shell expires.
            double tail = Math.max(0, Math.min(1, (PULSE_LIFETIME - age) / 2));
            pulseGain += burst.strength() * RadiationModel.pulse(age, .20, 1.35) * tail * tail * (3 - 2 * tail);
        }

        previousSpin = spin;
        previousCharge = charge;
        previousBeta = beta;
        previousTemperature = temperature;
        previousLuminosity = luminosity;
        spin = approach(spin, parameters.dimensionlessSpin());
        charge = approach(charge, parameters.dimensionlessCharge());
        beta = approach(beta, parameters.outflowBeta());
        temperature = approach(temperature, parameters.plasmaTemperatureKelvin());
        requestedLuminosity = approach(requestedLuminosity, parameters.luminosityWatts());
        requestedRestMassFlow = approach(requestedRestMassFlow, parameters.restMassFlowKgPerSecond());

        // Radiative power is measured at infinity. Matter loss counts rest energy plus kinetic energy once.
        double requestedPower = requestedLuminosity * emissionGain + WhiteHoleParameters.NOMINAL_LUMINOSITY_WATTS * pulseGain;
        double radiant = requestedPower * (STEP_SECONDS / C_SQUARED);
        double rest = requestedRestMassFlow * emissionGain * STEP_SECONDS;
        double gamma = RelativisticRadiation.lorentzFactor(beta);
        double kinetic = rest * (beta * beta * gamma * gamma / (gamma + 1));
        double requestedMass = radiant + rest + kinetic;
        double factor = requestedMass > 0 ? Math.min(1, remainingBudgetKg() / requestedMass) : 0;
        radiationMassEquivalent += radiant * factor;
        matterRestMass += rest * factor;
        matterKineticMassEquivalent += kinetic * factor;
        luminosity = radiant * factor * (C_SQUARED / STEP_SECONDS);
        massLossRate = requestedMass * factor / STEP_SECONDS;

        double displayTime = (stepNumber - 1) * STEP_SECONDS;
        while (!bursts.isEmpty() && displayTime >= bursts.get(0).startSeconds() + PULSE_LIFETIME) {
            bursts.remove(0);
        }
    }

    private double activityGain(double seconds) {
        if (transitionSeconds == 0) return activity.emissionMultiplier();
        return lerp(transitionOriginGain, activity.emissionMultiplier(), easing((seconds - transitionStart) / transitionSeconds));
    }

    private double profileGain(double seconds) {
        double target = curve(parameters.radiation(), seconds);
        return lerp(profileOriginGain, target, easing((seconds - profileTransitionStart) / PROFILE_TRANSITION_SECONDS));
    }

    private static double curve(RadiationModel model, double seconds) {
        double gain = model.multiplier(seconds);
        WhiteHoleParameters.requireRange(gain, 0, 16, "radiation curve output");
        return gain;
    }

    private static double easing(double phase) {
        double t = Math.max(0, Math.min(1, phase));
        return t * t * (3 - 2 * t);
    }

    private double releasedMassEquivalentKg() {
        return radiationMassEquivalent + matterRestMass + matterKineticMassEquivalent;
    }

    private double remainingBudgetKg() {
        double referenceMass = parameters.referenceMassKg();
        double remnant = Math.max(1e-100, referenceMass * parameters.minimumMassFraction());
        return Math.max(0, referenceMass - remnant - releasedMassEquivalentKg());
    }

    private void clearEnergyBudget() {
        radiationMassEquivalent = 0;
        matterRestMass = 0;
        matterKineticMassEquivalent = 0;
        massLossRate = 0;
    }

    private static double approach(double current, double target) {
        return lerp(current, target, PARAMETER_RESPONSE);
    }

    private static double lerp(double from, double to, double fraction) {
        return from + (to - from) * fraction;
    }

    public record Burst(long id, double startSeconds, double strength) {}

    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    public sealed interface LifecycleEvent {
        record Reset() implements LifecycleEvent {}
        record BurstStarted(Burst burst) implements LifecycleEvent {}
        record TransitionStarted(double startSeconds, Activity target, double durationSeconds) implements LifecycleEvent {}
    }

    public record Activity(String id, double emissionMultiplier) {
        public static final Activity DORMANT = new Activity("dormant", .04);
        public static final Activity ACTIVATION = new Activity("activation", .35);
        public static final Activity EXPANSION = new Activity("expansion", .70);
        public static final Activity STABLE_EMISSION = new Activity("stable_emission", 1);
        public static final Activity HIGH_ACTIVITY = new Activity("high_activity", 1.5);
        public static final Activity BURST = new Activity("burst", 3);
        public static final Activity DECAY = new Activity("decay", .30);
        public static final Activity DISSIPATION = new Activity("dissipation", 0);
        public static final Activity QUANTUM_TRANSITION = new Activity("quantum_transition", .8);

        public Activity {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("activity id is required");
            WhiteHoleParameters.requireRange(emissionMultiplier, 0, 8, "state emission multiplier");
        }
    }

    public record Snapshot(long completedSteps, double simulationTimeSeconds, double massKg,
                           double dimensionlessSpin, double dimensionlessCharge, double outflowBeta,
                           double plasmaTemperatureKelvin, double massLossKgPerSecond, double luminosityWatts,
                           double radianceGain, long seed, Activity activity, SpacetimeModel.Geometry geometry,
                           double radiatedJoules, double releasedRestEnergyJoules, double releasedKineticEnergyJoules,
                           double burstAgeSeconds, double burstStrength, List<Burst> bursts) {
        public double gravitationalRadiusMetres() {
            return SpacetimeModel.gravitationalRadiusMetres(massKg);
        }

        public double angularMomentumSi() {
            return SpacetimeModel.angularMomentumSi(massKg, dimensionlessSpin);
        }

        public double chargeCoulombs() {
            return SpacetimeModel.chargeCoulombs(massKg, dimensionlessCharge);
        }
    }
}
