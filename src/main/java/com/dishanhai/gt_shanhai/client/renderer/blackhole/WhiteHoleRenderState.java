package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.dishanhai.gt_shanhai.api.whitehole.WhiteHoleEngine;
import com.dishanhai.gt_shanhai.api.whitehole.WhiteHoleParameters;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreMachine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;

import java.util.Map;
import java.util.WeakHashMap;

/** Client-owned simulation state for each loaded host and for the local preview. */
final class WhiteHoleRenderState {
    static final double PREVIEW_REFERENCE_MASS = WhiteHoleParameters.defaults(0).referenceMassKg();
    static final double HOST_VISUAL_SCALE = 1.35;
    private static final Map<GuixuVisionCoreMachine, Session> HOSTS = new WeakHashMap<>();
    private static ClientLevel level;
    private static Session preview = new Session(20261126L);
    private static Session visibleSession;
    private static boolean previewEnabled;
    private static boolean demonstrating;
    private static double[] previewCenter;

    private WhiteHoleRenderState() {}

    static void setLevel(ClientLevel current) {
        if (level == current) return;
        level = current;
        BlackHoleTuning.clearHostWhiteHoleOverride();
        HOSTS.clear();
        visibleSession = null;
        preview = new Session(20261126L);
        previewEnabled = false;
        demonstrating = false;
        previewCenter = null;
    }

    static Session preview() { return preview; }

    static void setPreviewEnabled(boolean enabled) {
        if (enabled && (!previewEnabled || demonstrating)) {
            var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
            var eye = camera.getPosition();
            var direction = camera.getLookVector();
            double distance = 20 * BlackHoleLensRenderer.rsBlocks();
            previewCenter = new double[]{eye.x + distance * direction.x, eye.y + distance * direction.y,
                    eye.z + distance * direction.z};
        }
        previewEnabled = enabled;
        demonstrating = false;
    }

    static boolean previewEnabled() { return previewEnabled; }
    static boolean isDemonstrating() { return demonstrating; }

    static void startDemonstration() {
        preview.applyCinematicPreset();
        preview.engine.setPaused(false);
        preview.debugMode = 0;
        previewEnabled = true;
        demonstrating = true;
    }

    static double[] previewCenter() { return previewEnabled ? previewCenter : null; }

    static Frame sample(GuixuVisionCoreMachine host, boolean enabled, double worldSeconds, boolean paused) {
        Session session = null;
        if (enabled) {
            session = host == null ? preview : HOSTS.computeIfAbsent(host, machine -> {
                var created = new Session(machine.getPos().asLong());
                created.applyCinematicPreset();
                return created;
            });
        }
        if (visibleSession != session) {
            if (visibleSession != null) visibleSession.suspend();
            visibleSession = session;
        }
        return session == null ? Frame.NONE : session.sample(worldSeconds, paused);
    }

    static final class Session {
        WhiteHoleEngine engine;
        int spectrum;
        float jetGain = .85f;
        float diskDensity = 1;
        float diskThickness = 1;
        float coreGain = 1;
        float observerBeta;
        boolean automaticExposure;
        int debugMode;
        double rate = 1;
        private double lastWorldTime = Double.NaN;
        private float renderedJets = jetGain;
        private float renderedDensity = diskDensity;
        private float renderedThickness = diskThickness;
        private float renderedCore = coreGain;
        private float renderedObserverBeta;
        private double renderedMass = 1;

        Session(long seed) { engine = WhiteHoleEngine.defaults(seed); }

        void applyCinematicPreset() {
            WhiteHolePreset.CINEMATIC_WHITE_HOLE.settings(engine.parameters().seed()).apply(this);
        }

        void reseed(long seed) {
            var replacement = new WhiteHoleEngine(engine.parameters().withSeed(seed));
            replacement.setPaused(engine.isPaused());
            replacement.setTimeScale(rate);
            engine = replacement;
        }

        void setRate(double value) {
            engine.setTimeScale(value);
            rate = value;
        }

        void suspend() { lastWorldTime = Double.NaN; }

        Frame sample(double worldSeconds, boolean paused) {
            boolean firstSample = !Double.isFinite(lastWorldTime);
            double elapsed = worldSeconds - lastWorldTime;
            if (!Double.isFinite(elapsed) || elapsed > 1) elapsed = 0;
            elapsed = Math.clamp(elapsed, 0, .25);
            lastWorldTime = worldSeconds;
            double response = firstSample ? 1 : -Math.expm1(-elapsed / .17);
            if (!paused) engine.advance(elapsed);
            var snapshot = engine.snapshot();
            renderedMass = Math.fma(Math.clamp(snapshot.massKg() / PREVIEW_REFERENCE_MASS, .00001, 100)
                    - renderedMass, response, renderedMass);
            float weight = (float) response;
            renderedJets = Math.fma(jetGain - renderedJets, weight, renderedJets);
            renderedDensity = Math.fma(diskDensity - renderedDensity, weight, renderedDensity);
            renderedThickness = Math.fma(diskThickness - renderedThickness, weight, renderedThickness);
            renderedCore = Math.fma(coreGain - renderedCore, weight, renderedCore);
            renderedObserverBeta = Math.fma(observerBeta - renderedObserverBeta, weight, renderedObserverBeta);
            var bursts = snapshot.bursts();
            int previous = bursts.size() - 2;
            return new Frame(1, (float) snapshot.outflowBeta(), (float) snapshot.radianceGain(),
                    snapshot.seed(), snapshot.simulationTimeSeconds(),
                    (float) snapshot.burstAgeSeconds(), (float) snapshot.burstStrength(),
                    renderedJets, renderedDensity, renderedThickness, spectrum, renderedCore, renderedObserverBeta,
                    automaticExposure, debugMode,
                    snapshot.dimensionlessSpin(), renderedMass,
                    (float) (snapshot.plasmaTemperatureKelvin() * (9400.0 / 30000)),
                    Burst.sample(snapshot, previous), Burst.sample(snapshot, previous - 1),
                    Burst.sample(snapshot, previous - 2), Burst.sample(snapshot, previous - 3));
        }
    }

    private record Burst(float age, float strength) {
        private static final Burst NONE = new Burst(-1, 0);

        static Burst sample(WhiteHoleEngine.Snapshot snapshot, int index) {
            if (index < 0) return NONE;
            var burst = snapshot.bursts().get(index);
            return new Burst((float) (snapshot.simulationTimeSeconds() - burst.startSeconds()), (float) burst.strength());
        }
    }

    record Frame(float mix, float beta, float gain, long particleSeed, double time, float burstAge, float burstStrength,
                 float jets, float diskDensity, float diskThickness, float spectrum, float coreGain, float observerBeta,
                 boolean automaticExposure, int debugMode, double spin,
                 double massRatio, float temperature, Burst second, Burst third, Burst fourth, Burst fifth) {
        static final Frame NONE = new Frame(0, 0, 0, 0, 0, -1, 0, 0, 1, 1, 0, 1,
                0, false, 0, 0, 1, 9400, Burst.NONE, Burst.NONE, Burst.NONE, Burst.NONE);

        void bind(ShaderInstance program) {
            program.safeGetUniform("uWhiteHole").set(mix, beta, gain, Math.floorMod(particleSeed, 65536));
            program.safeGetUniform("uWhiteTime").set((float) time, burstAge, jets, burstStrength);
            program.safeGetUniform("uWhiteStyle").set(diskDensity, diskThickness, spectrum, coreGain);
            program.safeGetUniform("uWhiteExtraBursts0").set(second.age, second.strength, third.age, third.strength);
            program.safeGetUniform("uWhiteExtraBursts1").set(fourth.age, fourth.strength, fifth.age, fifth.strength);
            program.safeGetUniform("uWhiteObserverBeta").set(observerBeta * mix);
        }

        boolean canReuseHostFrame(Frame previous) {
            return mix == previous.mix && particleSeed == previous.particleSeed
                    && burstAge < 0 && previous.burstAge < 0
                    && time >= previous.time && time - previous.time <= .075
                    && beta == previous.beta && jets == previous.jets
                    && diskDensity == previous.diskDensity && diskThickness == previous.diskThickness
                    && spectrum == previous.spectrum && coreGain == previous.coreGain
                    && observerBeta == previous.observerBeta
                    && Math.abs(gain - previous.gain) <= .015f && Math.abs(temperature - previous.temperature) <= 1f;
        }

        boolean canReproject(Frame previous) {
            return (mix > 0) == (previous.mix > 0) && particleSeed == previous.particleSeed
                    && Math.abs(mix - previous.mix) < .025f
                    && Math.abs(gain - previous.gain) < .06f
                    && Math.abs(temperature - previous.temperature) < Math.max(100, .04 * temperature)
                    && Math.abs(observerBeta - previous.observerBeta) < .002f
                    && Math.abs(time - previous.time) < .12f
                    && (burstAge >= 0) == (previous.burstAge >= 0)
                    && Math.abs(burstAge - previous.burstAge) < .12f
                    && burstStrength == previous.burstStrength && beta == previous.beta && jets == previous.jets
                    && spectrum == previous.spectrum && diskDensity == previous.diskDensity
                    && diskThickness == previous.diskThickness && coreGain == previous.coreGain;
        }
    }
}
