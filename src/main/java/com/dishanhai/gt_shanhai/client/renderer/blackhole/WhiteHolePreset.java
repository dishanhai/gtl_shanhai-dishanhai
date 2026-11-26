package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.dishanhai.gt_shanhai.api.whitehole.RadiationModel.Profile;
import com.dishanhai.gt_shanhai.api.whitehole.StandardSpacetime;
import com.dishanhai.gt_shanhai.api.whitehole.WhiteHoleEngine;
import com.dishanhai.gt_shanhai.api.whitehole.WhiteHoleParameters;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;

import static com.dishanhai.gt_shanhai.api.whitehole.WhiteHoleParameters.NOMINAL_LUMINOSITY_WATTS;

enum WhiteHolePreset {
    SCHWARZSCHILD(StandardSpacetime.SCHWARZSCHILD, 0, Profile.CONSTANT, .28, .7f, 1, 0),
    KERR(StandardSpacetime.KERR, .65, Profile.CONSTANT, .42, 1, 1, 0),
    CELESTIAL_FOUNTAIN(StandardSpacetime.KERR, .38, Profile.PERIODIC, .28, .65f, 1.6f, 1),
    RELATIVISTIC_VORTEX(StandardSpacetime.KERR, .95, Profile.CONSTANT, .64, 1.3f, .6f, 1),
    RADIANT_NOVA(StandardSpacetime.OUTGOING_VAIDYA, 0, Profile.TRANSIENT, .48, .8f, 0, 2),
    PLATINUM_SINGULARITY(StandardSpacetime.SCHWARZSCHILD, 0, Profile.CONSTANT, .19, .5f, 0, 4),
    BIPOLAR_ERUPTION(StandardSpacetime.KERR, .83, Profile.PERIODIC, .62, .55f, 2, 3),
    QUANTUM_REBIRTH(StandardSpacetime.QUANTUM_BOUNCE, .74, Profile.QUANTUM_TRANSITION, .52, 1.2f, 1, 3),
    CINEMATIC_WHITE_HOLE(StandardSpacetime.KERR, .72, Profile.CONSTANT, .36, 1.35f, 1.25f, 0),
    SCIENTIFIC_VISUALIZATION(StandardSpacetime.SCHWARZSCHILD, 0, Profile.CONSTANT, .22, .55f, 0, 5);

    private final StandardSpacetime model;
    private final double spin;
    private final Profile profile;
    private final double beta;
    private final float density, jets;
    private final int spectrum;

    WhiteHolePreset(StandardSpacetime model, double spin, Profile profile, double beta, float density, float jets,
                    int spectrum) {
        this.model = model;
        this.spin = spin;
        this.profile = profile;
        this.beta = beta;
        this.density = density;
        this.jets = jets;
        this.spectrum = spectrum;
    }

    String key() { return "preset." + name().toLowerCase(Locale.ROOT); }

    Settings settings(long seed) {
        var defaults = WhiteHoleParameters.defaults(seed);
        float core = this == CINEMATIC_WHITE_HOLE ? .48f : 1f;
        return new Settings(1, model, profile, defaults.referenceMassKg(), defaults.minimumMassFraction(),
                spin, 0, NOMINAL_LUMINOSITY_WATTS, defaults.restMassFlowKgPerSecond(), beta,
                defaults.plasmaTemperatureKelvin(), seed, 1, jets, density, 1, spectrum, core, 0, false);
    }

    record Settings(int version, StandardSpacetime model, Profile radiation, double massKg, double minimumMassFraction,
                    double spin, double charge, double luminosityWatts, double restMassFlowKgPerSecond, double beta,
                    double temperatureKelvin, long seed, double rate, float jets, float diskDensity, float diskThickness,
                    int spectrum, float coreGain, float observerBeta, boolean automaticExposure) {
        private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

        Settings {
            if (version != 1) throw new IllegalArgumentException("unsupported white hole preset version");
            Objects.requireNonNull(model);
            Objects.requireNonNull(radiation);
            if (model == StandardSpacetime.REISSNER_NORDSTROM) {
                throw new IllegalArgumentException("charged optics unavailable in this renderer");
            }
            range(rate, .125, 4);
            range(jets, 0, 2);
            range(diskDensity, .05f, 4);
            range(diskThickness, .25, 3);
            range(spectrum, 0, 5);
            range(coreGain, 0, 2);
            range(observerBeta, -.85f, .85f);
            new WhiteHoleParameters(model, radiation, massKg, minimumMassFraction, spin, charge,
                    luminosityWatts, restMassFlowKgPerSecond, beta, temperatureKelvin, seed);
        }

        private static void range(double input, double min, double max) {
            if (!(input >= min && input <= max)) throw new IllegalArgumentException("preset value out of range");
        }

        static Settings capture(WhiteHoleRenderState.Session session) {
            var p = session.engine.parameters();
            return new Settings(1, (StandardSpacetime) p.spacetime(), (Profile) p.radiation(), p.referenceMassKg(),
                    p.minimumMassFraction(), p.dimensionlessSpin(), p.dimensionlessCharge(), p.luminosityWatts(),
                    p.restMassFlowKgPerSecond(), p.outflowBeta(), p.plasmaTemperatureKelvin(), p.seed(), session.rate,
                    session.jetGain, session.diskDensity, session.diskThickness, session.spectrum, session.coreGain,
                    session.observerBeta, session.automaticExposure);
        }

        void apply(WhiteHoleRenderState.Session session) {
            boolean paused = session.engine.isPaused();
            session.engine = new WhiteHoleEngine(new WhiteHoleParameters(model, radiation, massKg, minimumMassFraction,
                    spin, charge, luminosityWatts, restMassFlowKgPerSecond, beta, temperatureKelvin, seed));
            session.engine.setPaused(paused);
            session.setRate(rate);
            session.jetGain = jets;
            session.diskDensity = diskDensity;
            session.diskThickness = diskThickness;
            session.spectrum = spectrum;
            session.coreGain = coreGain;
            session.observerBeta = observerBeta;
            session.automaticExposure = automaticExposure;
        }

        void save(Path path) throws IOException {
            Files.createDirectories(path.getParent());
            Path temporary = Files.createTempFile(path.getParent(), "white-hole-", ".json");
            try {
                Files.writeString(temporary, JSON.toJson(this));
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        static Settings load(Path path) throws IOException {
            try (var reader = Files.newBufferedReader(path)) {
                return Objects.requireNonNull(JSON.fromJson(reader, Settings.class));
            }
        }
    }
}
