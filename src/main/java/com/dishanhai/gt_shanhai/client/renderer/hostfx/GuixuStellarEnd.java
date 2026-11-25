package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuHostGeometry;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/** 群星终焉的单次吞噬时间轴与环心坐标。 */
final class GuixuStellarEnd {
    static final int STAR_COUNT = 64;
    static final float MAX_HORIZON_RADIUS = 15.0F;
    static final float MIN_SOLID_RADIUS = .025F;
    static final float MIN_GLINT_LIGHT = .001F;
    private static final double LAST_SIPHON_START = 22;
    private static final double INFALL_SECONDS = 24;
    private static final double SOURCE_FIELD_RADIUS = 45;
    private static final double STELLAR_DEBRIS_RETURN_SECONDS = 9.25;
    private static final double STELLAR_STREAM_TURN = 2.05;
    static final double STELLAR_STREAM_INNER_RADIUS = 1.27;
    static final double CAPTURE_PULSE_SECONDS = 2.4;
    private static final double CAPTURE_FEED_SECONDS = 2.9;
    static final double PULSAR_ROTATION_SECONDS = 1.6;
    static final double QUASAR_ENTRY_SECONDS;
    private static final double QUASAR_SIPHON_START;
    private static final double QUASAR_INFALL_SECONDS = 18;
    private static final double QUASAR_JETS_EXTENDED_AGE = 2.78;
    static final double QUASAR_CAPTURE_SECONDS;
    static final double AFTERGLOW_END_SECONDS;
    private static final double MERGER_WAVE_SECONDS = 3.4;
    private static final double COMPANION_SINK_SECONDS = .72;
    static final double STRUCTURE_START_SECONDS;
    static final double STRUCTURE_SHAFT_FEED = 2.25;
    static final double STRUCTURE_RING_FEED = 3.8;
    static final int PILLAR_BASE = GuixuHostGeometry.UPPER_PILLAR_BASE_Y - GuixuHostGeometry.RING_Y;
    private static final double GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));
    private static final double ORBIT_SPEED = .025;
    private static final BodyKind[] SOLID_KINDS = {
            BodyKind.STAR, BodyKind.NEUTRON_STAR, BodyKind.GAS_GIANT, BodyKind.RINGED_GIANT,
            BodyKind.ROCKY, BodyKind.BLACK_HOLE, BodyKind.WHITE_DWARF, BodyKind.PULSAR,
            BodyKind.WHITE_HOLE, BodyKind.THORNE_ZYTKOW, BodyKind.GAS_GIANT, BodyKind.ANTISTAR,
            BodyKind.NEUTRON_STAR, BodyKind.WHITE_DWARF, BodyKind.BLACK_DWARF, BodyKind.QUASAR
    };
    private static final Source[] SOURCES = new Source[STAR_COUNT];
    private static final double BLACK_HOLE_IMPACT;
    private static final int LAST_BODY_INDEX;

    static {
        var random = new SplittableRandom(0x57E11A2L);
        double blackHoleImpact = 0;
        double lastSink = 0;
        int lastBodyIndex = 0;
        for (int i = 0; i < SOURCES.length; i++) {
            BodyKind kind = i % 4 == 0 ? SOLID_KINDS[i / 4] : BodyKind.GLINT;
            boolean warm = switch (kind) {
                case GLINT -> i % 5 < 2;
                case NEUTRON_STAR, PULSAR, WHITE_DWARF, QUASAR, WHITE_HOLE, BLACK_DWARF -> false;
                case BLACK_HOLE, THORNE_ZYTKOW, ANTISTAR -> true;
                default -> (i / 16 + i / 4) % 2 == 0;
            };
            double radius = random.nextDouble(.62, 1.12), siphonAt = random.nextDouble(5, LAST_SIPHON_START);
            // 第一颗白矮星提前掠过，给小视界下仍保持尺寸的伴星黑洞留出合并空间。
            if (i == 24) siphonAt -= 1.05;
            // 错开会在入射或掠过时相交的轨道，剥离物质共用天体所在的方位。
            double azimuth = i * GOLDEN_ANGLE + Math.toRadians(switch (i) {
                case 24 -> -15; // 第一颗白矮星
                case 28 -> -10; // 脉冲星
                case 36 -> -15; // 索恩–祖特阔夫天体
                case 52 -> 30;  // 第二颗白矮星
                case 56 -> 10;  // 黑矮星
                default -> 0;
            });
            boolean quasar = kind == BodyKind.QUASAR;
            if (kind == BodyKind.BLACK_HOLE) {
                blackHoleImpact = siphonAt + INFALL_SECONDS;
            }
            double sink = siphonAt + INFALL_SECONDS + (kind == BodyKind.BLACK_HOLE ? COMPANION_SINK_SECONDS : 0);
            if (!quasar && sink > lastSink) {
                lastSink = sink;
                lastBodyIndex = i;
            }
            SOURCES[i] = new Source(azimuth, quasar ? .45 : 1 - 2 * (i + .5) / STAR_COUNT,
                    quasar ? 2.35 : radius, siphonAt, random.nextDouble(0, Math.TAU),
                    (float) random.nextDouble(.28, .55) * (i % 9 == 0 ? 1.7F : 1F), warm, kind);
        }
        BLACK_HOLE_IMPACT = blackHoleImpact;
        LAST_BODY_INDEX = lastBodyIndex;
        QUASAR_ENTRY_SECONDS = lastSink;
        QUASAR_SIPHON_START = lastSink + 4.5;
        QUASAR_CAPTURE_SECONDS = QUASAR_SIPHON_START + QUASAR_INFALL_SECONDS;
        AFTERGLOW_END_SECONDS = QUASAR_CAPTURE_SECONDS + CAPTURE_PULSE_SECONDS;
        STRUCTURE_START_SECONDS = QUASAR_CAPTURE_SECONDS + CAPTURE_FEED_SECONDS;
    }

    private GuixuStellarEnd() {}

    enum Stage {
        INFALL, QUASAR, AFTERGLOW, STRUCTURE
    }

    record Frame(Stage stage, double seconds, double structureAge, float horizon, boolean simple,
                 List<Star> stars, List<GravityWave> waves, float accretionFeed, float outflow,
                 float horizonGlow) {}

    /** 主机、背景星空和透镜后的绘制共用本帧采样。 */
    static Frame sample(double seconds, float horizon, boolean simple) {
        Stage stage = stageAt(seconds);
        double structureAge = seconds - STRUCTURE_START_SECONDS;
        if (horizon <= 0 || seconds <= 0) {
            return new Frame(stage, seconds, structureAge, horizon, simple, List.of(), List.of(), 0, 0, 0);
        }

        float feed = 0, flash = 0, outflow = 0;
        List<Star> stars = List.of();
        List<GravityWave> waves = List.of();
        if (stage != Stage.STRUCTURE) {
            var visibleStars = new ArrayList<Star>();
            var activeWaves = new ArrayList<GravityWave>();
            boolean drawCelestials = seconds < AFTERGLOW_END_SECONDS;
            for (int i = 0; i < SOURCES.length; i++) {
                Source source = SOURCES[i];
                BodyKind kind = source.kind();
                if (kind == BodyKind.QUASAR && stage == Stage.INFALL) continue;
                double age = source.siphonAge(seconds);
                double captureAge = age - source.infallDuration();
                boolean draw = drawCelestials && drawsSource(i, simple);
                if (kind == BodyKind.GLINT && (!draw || captureAge >= 0)) continue;
                if (captureAge >= Math.max(CAPTURE_FEED_SECONDS, gravityWaveSeconds(kind))) continue;

                Star star = star(i, seconds, SOURCE_FIELD_RADIUS, age);
                feed += accretionFeed(star, age);
                if (!draw) continue;
                visibleStars.add(star);
                flash = Math.max(flash, captureFlash(star));
                outflow = Math.max(outflow, stellarOutflow(star));
                if (kind.gravityWaveStrength() > 0 && captureAge > 0 && captureAge < gravityWaveSeconds(kind)) {
                    GravityWave wave = gravityWave(star, horizon);
                    if (wave.light() > .001F) activeWaves.add(wave);
                }
            }
            stars = List.copyOf(visibleStars);
            waves = List.copyOf(activeWaves);
        }
        float rebound = mergerRebound(seconds);
        float release = .53F * (float) Math.sin(Math.PI * Math.clamp(structureAge / 1.25, 0, 1));
        float glow = Math.min(1, horizonLight(seconds) + flash * .25F + release + rebound * .42F);
        return new Frame(stage, seconds, structureAge, horizon, simple, stars, waves, feed, outflow,
                glow);
    }

    private static Stage stageAt(double seconds) {
        if (seconds < QUASAR_ENTRY_SECONDS) return Stage.INFALL;
        if (seconds < QUASAR_CAPTURE_SECONDS) return Stage.QUASAR;
        if (seconds < STRUCTURE_START_SECONDS) return Stage.AFTERGLOW;
        return Stage.STRUCTURE;
    }

    static float horizonRadius(float base, double seconds) {
        double progress = .58 * ease(0, 13, seconds) + .42 * ease(13, 45, seconds);
        double expanded = Math.min(MAX_HORIZON_RADIUS, base * 1.47);
        double rebound = mergerRebound(seconds);
        return (float) (base + (expanded - base) * (progress + (1 - progress) * rebound));
    }

    static float mergerRebound(double seconds) {
        double age = seconds - BLACK_HOLE_IMPACT;
        return (float) (ease(-.60, .28, age) * (1 - ease(.28, 2.6, age)));
    }

    static QuasarIgnition quasarIgnition(double seconds) {
        double age = seconds - QUASAR_ENTRY_SECONDS;
        float disk = age < 0 ? 0 : (float) (.66 + .34 * ease(0, .93, age));
        float reach = (float) (.25 * ease(1.43, 1.67, age) + .75 * ease(1.67, QUASAR_JETS_EXTENDED_AGE, age));
        float flare = (float) (ease(1.43, 1.68, age) * (1 - ease(1.68, 2.94, age)));
        return new QuasarIgnition(disk, reach, flare);
    }

    static JetPulse jetPulse(BodyKind kind, double seconds, double phase) {
        double cycle;
        if (kind == BodyKind.PULSAR) {
            cycle = seconds / PULSAR_ROTATION_SECONDS + phase / Math.TAU;
        } else {
            double age = seconds - QUASAR_ENTRY_SECONDS - QUASAR_JETS_EXTENDED_AGE;
            if (age < 0) return new JetPulse(0, 0);
            cycle = age / 2.4;
        }
        float position = (float) Math.min(1, (cycle - Math.floor(cycle)) / .90);
        float light = (float) (ease(.015, .14, position) * (1 - ease(.83, .99, position)));
        return new JetPulse(position, light);
    }

    private static float horizonLight(double seconds) {
        double ignition = ease(0, 3, seconds);
        double feeding = ease(5, 14, seconds) * (1 - ease(39, QUASAR_ENTRY_SECONDS, seconds));
        feeding += ease(QUASAR_SIPHON_START, QUASAR_SIPHON_START + 6, seconds)
                * (1 - ease(AFTERGLOW_END_SECONDS - 6, AFTERGLOW_END_SECONDS, seconds));
        return (float) (ignition * (.28 + .49 * feeding) * (.89 + .11 * Math.sin(seconds * 2.6)));
    }

    static Star star(int index, double seconds, double outerRadius) {
        return star(index, seconds, outerRadius, SOURCES[index].siphonAge(seconds));
    }

    private static Star star(int index, double seconds, double outerRadius, double age) {
        Source source = SOURCES[index];
        BodyKind kind = source.kind();
        boolean quasar = kind == BodyKind.QUASAR;
        StellarPass passage = kind.stellarPass;
        double infall = source.infallDuration();
        double captureAge = age - infall;
        float strength = kind.siphonStrength();
        double streamTravel = streamTravel(kind);
        double leadingMatter = ease(0, streamTravel, age);
        double body = ease(4.8, infall, age);
        double arrival = ease(0, 2.5, seconds);
        if (quasar) {
            double entranceAge = seconds - QUASAR_ENTRY_SECONDS;
            arrival = entranceAge < 0 ? 0 : .32 + .68 * ease(0, 1.38, entranceAge);
        }
        double twinkle = .79 + .21 * Math.sin(seconds * (1.5 + .03 * index) + source.phase());
        if (quasar) twinkle = .85 + .15 * twinkle;
        float brightness = (float) (arrival * twinkle * (1 - ease(.08, 1, body)));
        double streamFade = .74 + .07 * (strength - 1);
        double streamIgnition = ease(0, 3.6 / strength, age);
        float matter = (float) (streamIgnition * (1 - ease(streamFade, 1, body)));
        float disruption = kind.disruption(captureAge);
        float tidalStress = (float) (.22 * ease(0, 14.5, age) + .53 * body);
        float surfaceLoss = switch (kind) {
            case GAS_GIANT, RINGED_GIANT -> .38F;
            case ROCKY -> .36F;
            default -> 0F;
        };
        double retainedRadius = (1 - body) * (1 - surfaceLoss * disruption);
        tidalStress *= switch (kind) {
            case ROCKY -> .22F + .43F * disruption;
            case NEUTRON_STAR, PULSAR -> .13F + .15F * disruption;
            case WHITE_DWARF -> .21F + .32F * disruption;
            case BLACK_DWARF -> .13F + .25F * disruption;
            case THORNE_ZYTKOW -> 1F - .78F * disruption;
            default -> 1F;
        };
        if (kind == BodyKind.ROCKY) matter *= .44F + .56F * disruption;
        if (passage != null) {
            // 本体在最近点解体或掠过，光带在本体消失前逐渐收暗。
            double fade = passage.survives() ? 1 - ease(passage.periapsisAge + .8, 0, captureAge) : 1;
            retainedRadius = (1 - passage.radiusLoss * disruption * disruption) * fade;
            brightness = (float) (arrival * twinkle * fade * (passage.survives() ? 1 : retainedRadius));
            float stripped = passage.survives() ? disruption : .32F + .68F * disruption;
            matter = (float) (streamIgnition * passage.matterYield * stripped * (1 - ease(-6, 0, captureAge)));
        }
        if (kind == BodyKind.BLACK_HOLE) {
            // 伴星暗核保留到接触，吸积物质先被抽离，随后伴星本身掠入主视界。
            double swallowed = ease(-.9, COMPANION_SINK_SECONDS, captureAge);
            retainedRadius = (1 - .28 * body) * (1 - swallowed);
            brightness = (float) (arrival * twinkle * (1 - swallowed));
            matter *= (float) (1 - ease(-7.5, -4.8, captureAge));
            tidalStress *= .40F;
        }
        if (kind == BodyKind.WHITE_HOLE) matter *= .68F;
        float solidRadius = kind.radius * (float) (arrival * retainedRadius * (1.09 - .13 * Math.cos(source.phase())));
        matter *= sourceFade(kind, solidRadius, brightness);
        return new Star(source.angle() + seconds * ORBIT_SPEED, source.vertical(), outerRadius * source.radiusScale(),
                source.phase(), source.size(), source.warm(), body, leadingMatter, brightness, matter,
                kind, solidRadius, tidalStress, disruption, captureAge);
    }

    private static float sourceFade(BodyKind kind, float solidRadius, float brightness) {
        return (float) (kind == BodyKind.GLINT ? ease(MIN_GLINT_LIGHT, .06F, brightness)
                : ease(MIN_SOLID_RADIUS, .25F, solidRadius));
    }

    static boolean drawsSource(int index, boolean simple) {
        // 简化档仍保留最后没入的那个星点，让类星体在同一时刻接上。
        return !simple || index % 2 == 0 || index == LAST_BODY_INDEX;
    }

    static Point position(Star star, double progress, double horizon) {
        double remaining = 1 - progress;
        double angle = star.angle() + infallTurn(progress);
        double radius = horizon + (star.radius() - horizon) * remaining;
        return orbitPoint(angle, radius, star.vertical() * remaining * remaining);
    }

    static Point bodyPosition(Star star, double horizon) {
        var passage = star.kind().stellarPass;
        if (passage != null) {
            double approach = stellarApproach(star, passage);
            return orbitPoint(star.angle() + infallTurn(star.body()), stellarRadius(star, passage, horizon, approach),
                    stellarLatitude(star, approach));
        }
        if (star.kind() != BodyKind.BLACK_HOLE) return position(star, star.body(), horizon);
        double remaining = 1 - star.body();
        double age = star.captureAge();
        double angle = star.angle() + infallTurn(star.body()) + 1.28 * ease(-4.8, .45, age);
        double radius = horizon + (star.radius() - horizon) * remaining
                - horizon * .26 * ease(-.55, COMPANION_SINK_SECONDS, age);
        return orbitPoint(angle, radius, star.vertical() * remaining * remaining);
    }

    private static Point orbitPoint(double angle, double radius, double vertical) {
        double horizontal = radius * Math.sqrt(1 - vertical * vertical);
        return new Point(Math.cos(angle) * horizontal, radius * vertical, Math.sin(angle) * horizontal);
    }

    private static double infallTurn(double progress) {
        return progress * (.65 + 4.15 * progress);
    }

    private static double stellarApproach(Star star, StellarPass passage) {
        double closest = ease(4.8 - INFALL_SECONDS, 0, passage.periapsisAge);
        double approach = ease(4.8 - INFALL_SECONDS, passage.periapsisAge, star.captureAge());
        double retreat = passage.survives() ? .70 * ease(passage.periapsisAge, 0, star.captureAge()) : 0;
        return approach * (1 - retreat) * closest;
    }

    private static double stellarRadius(Star star, StellarPass passage, double horizon, double approach) {
        return star.radius() - (star.radius() - horizon * 1.20) * (1 - passage.orbitGap) * approach;
    }

    private static double stellarLatitude(Star star, double approach) {
        double inclination = 1 - approach;
        return star.vertical() * inclination * inclination;
    }

    /** 物质从拉伸球面剥离，恒星碎屑与残骸的轨迹在此分开。 */
    static Point streamPosition(Star star, double progress, double horizon) {
        var point = new Vector3d();
        streamPosition(star, progress, horizon, point);
        return new Point(point.x, point.y, point.z);
    }

    static void streamPosition(Star star, double progress, double horizon, Vector3d point) {
        var passage = star.kind().stellarPass;
        if (passage != null) {
            double approach = stellarApproach(star, passage);
            double travel = streamFraction(star, progress);
            double remaining = 1 - travel;
            double origin = stellarRadius(star, passage, horizon, approach) - star.solidRadius() * (1 + star.tidalStress());
            double disk = STELLAR_STREAM_INNER_RADIUS * horizon;
            double radius = disk + (origin - disk) * remaining * remaining;
            double vertical = stellarLatitude(star, approach) * remaining * remaining;
            double horizontal = radius * Math.sqrt(1 - vertical * vertical);
            double angle = star.angle() + infallTurn(star.body()) + STELLAR_STREAM_TURN * travel;
            point.set(Math.cos(angle) * horizontal, radius * vertical, Math.sin(angle) * horizontal);
            return;
        }
        double remaining = 1 - progress;
        double angle = star.angle() + infallTurn(progress);
        double radius = horizon + (star.radius() - horizon) * remaining;
        double vertical = star.vertical() * remaining * remaining;
        double horizontal = radius * Math.sqrt(1 - vertical * vertical);
        double scale = 1;
        if (star.solidRadius() != 0 && progress < 1) {
            float stretch = star.kind() == BodyKind.QUASAR ? 0 : star.tidalStress();
            double offset = star.solidRadius() * (1 + stretch) * (remaining / (1 - star.body()));
            scale -= offset / radius;
        }
        point.set(Math.cos(angle) * horizontal * scale, radius * vertical * scale, Math.sin(angle) * horizontal * scale);
    }

    static double streamFraction(Star star, double progress) {
        return progress >= 1 ? 1 : (progress - star.body()) / (1 - star.body());
    }

    /** 同一批碎屑先沿流束卷入，再在撞击点点亮主吸积盘。 */
    static DebrisSurge debrisSurge(Star star) {
        var passage = star.kind().stellarPass;
        if (passage == null) return null;
        double returnTime = streamTravel(star.kind());
        double age = star.captureAge() - (returnTime - INFALL_SECONDS);
        if (age <= -1.4 || age >= 2.4) return null;

        double progress = ease(-1.4, 0, age);
        float streamLight = star.matterBrightness() * capturePulse(age + 1.4, 1.03, 1.62);
        float flash = passage.matterYield * capturePulse(age, .13, .72);
        float heat = passage.matterYield * capturePulse(age, .35, 2.4);
        double impact = star.angle() - age * ORBIT_SPEED + infallTurn(ease(4.8, INFALL_SECONDS, returnTime))
                + STELLAR_STREAM_TURN;
        double advance = 4.2 * ease(0, 2.4, age);
        return new DebrisSurge(progress, streamLight, impact, flash,
                new CaptureWake(impact + advance * .30, impact + advance, heat));
    }

    static float stellarOutflow(Star star) {
        var passage = star.kind().stellarPass;
        if (passage == null) return 0F;
        double firstReturn = streamTravel(star.kind()) - INFALL_SECONDS;
        double peak = firstReturn + 1.25;
        double age = star.captureAge();
        // 首批碎屑入盘后出现喷流，与本次恒星供料一起退场。
        return passage.matterYield * sourceFade(star.kind(), star.solidRadius(), star.brightness())
                * (float) (ease(firstReturn + .35, peak, age) * (1 - ease(Math.max(-7, peak), 0, age)));
    }

    static float whiteDwarfTidalLight(Star star) {
        return star.disruption();
    }

    static float whiteHoleShellRadius(double age) {
        return 1.08F + 1.4F * (float) age;
    }

    static float whiteHoleShellLight(double age) {
        if (age <= 0 || age >= 1.6) return 0;
        float radius = whiteHoleShellRadius(age);
        // 演出以球对称出射壳层表现白洞，单位面积通量随球面面积扩大而下降。
        return (float) (ease(0, .15, age) * (1 - ease(1.0, 1.6, age))) / (radius * radius);
    }

    static float whiteHoleShift(float radius, float horizon) {
        // 取主视界外静态发射源的 Schwarzschild 红移因子作为演出近似。
        return radius <= horizon ? 0F : (float) Math.sqrt(1F - horizon / radius);
    }

    static float tzoCoreExposure(Star star) {
        return star.disruption();
    }

    static float antistarAnnihilation(float facing, float matterSupply) {
        float exposed = Math.max(0F, facing);
        return matterSupply * exposed * exposed;
    }

    private static double streamTravel(BodyKind kind) {
        if (kind == BodyKind.STAR) return STELLAR_DEBRIS_RETURN_SECONDS;
        if (kind.stellarPass != null) return INFALL_SECONDS + kind.stellarPass.periapsisAge - 2.5;
        return 14.5 / Math.sqrt(kind.siphonStrength());
    }

    /** 只累计已经回落到内盘的物质，天体还在外侧瓦解时不预热。 */
    private static float accretionFeed(Star star, double age) {
        BodyKind kind = star.kind();
        if (kind == BodyKind.GLINT) return 0;
        double arrival = streamTravel(kind);
        double captureAge = star.captureAge();
        float strength = kind.siphonStrength();
        float feed = 0;
        if (age > arrival && captureAge < 0) {
            feed = (float) (.046 * strength * star.matterBrightness() * ease(arrival, arrival + .85, age));
        }
        if (kind.stellarPass == null && kind != BodyKind.BLACK_HOLE) {
            feed += (float) (.16 * strength * capturePulse(captureAge, .42, CAPTURE_FEED_SECONDS));
        }
        return feed;
    }

    static float captureLight(Star star) {
        double age = star.captureAge();
        if (age <= 0 || age >= CAPTURE_PULSE_SECONDS) return 0;
        var profile = star.kind().captureProfile();
        return captureStrength(star) * profile.light * capturePulse(age, profile.peak, profile.duration);
    }

    static float captureFlash(Star star) {
        double age = star.captureAge();
        if (age <= 0 || age >= .49) return 0;
        var profile = star.kind().captureProfile();
        return captureStrength(star) * profile.flash * capturePulse(age, profile.flashPeak, profile.flashEnd);
    }

    private static float capturePulse(double age, double peak, double end) {
        return (float) (ease(0, peak, age) * (1 - ease(peak, end, age)));
    }

    private static float captureStrength(Star star) {
        if (star.kind().stellarPass != null) return 0F;
        return switch (star.kind()) {
            case GLINT, BLACK_HOLE -> 0F;
            default -> Math.min(1F, star.kind().radius / BodyKind.GAS_GIANT.radius);
        };
    }

    static GravityWave gravityWave(Star star, float horizon) {
        double age = star.captureAge();
        boolean merger = star.kind() == BodyKind.BLACK_HOLE;
        double duration = gravityWaveSeconds(star.kind());
        float strength = star.kind().gravityWaveStrength();
        float travel = (float) Math.clamp(age / duration, 0, 1);
        float light = strength * (float) (ease(0, .18, age) * (1 - ease(.38, duration, age)));
        float extent = .45F + .55F * strength;
        float radius = horizon * (1.05F + .8F * travel * extent) + 36F * travel * extent;
        float width = (.36F + horizon * .085F + travel * 2.65F) * extent;
        double azimuth = captureAzimuth(star) + (merger ? 1.28 * ease(-4.8, .45, 0) : 0);
        return new GravityWave(radius, width, light, azimuth);
    }

    private static double gravityWaveSeconds(BodyKind kind) {
        return kind == BodyKind.BLACK_HOLE ? MERGER_WAVE_SECONDS : CAPTURE_PULSE_SECONDS;
    }

    /** 波纹固定在该天体的入射方位，余辉不随尚未落尽的天体继续绕行。 */
    static double captureAzimuth(Star star) {
        return star.angle() - star.captureAge() * ORBIT_SPEED + infallTurn(1);
    }

    static CaptureWake captureWake(Star star) {
        return captureWake(star, captureLight(star));
    }

    static CaptureWake captureWake(Star star, float light) {
        double origin = captureAzimuth(star);
        var profile = star.kind().captureProfile();
        double age = Math.clamp(star.captureAge() / profile.duration, 0, 1);
        double advance = age * (3.625 - 1.125 * age) * profile.sweep;
        double span = advance * (.81 - .43 * age);
        return new CaptureWake(origin + advance - span, origin + advance, light);
    }

    static float ringTransmission(float stress, float facingHole, float outerFraction) {
        double opening = ease(.17, .68, stress) * (.24 + .76 * outerFraction);
        return (float) (1 - ease(1 - 1.15 * opening, 1.08 - .83 * opening, facingHole));
    }

    static float limbOffset(float radius, float distance) {
        return radius * radius / distance;
    }

    static float limbRadius(float radius, float distance) {
        float ratio = radius / distance;
        return radius * (float) Math.sqrt(1 - ratio * ratio);
    }

    /** 从环心供能，按模板坐标给各段分配独立的到达时间。 */
    static double structureArrival(double x, double y, double z) {
        double radius = Math.hypot(x, z), height = Math.abs(y);
        double alongSide = Math.min(Math.abs(x), Math.abs(z));
        int outer = GuixuHostGeometry.RING_HALF;
        if (radius <= GuixuHostGeometry.PILLAR_MODULE_OUTWARD + 4 && height >= PILLAR_BASE - 3) {
            return STRUCTURE_SHAFT_FEED + Math.max(0, height - PILLAR_BASE) * .105;
        }
        if (x > outer + 5 && Math.abs(z) < 25) {
            return 11.0 + (x - outer - 5) * .15 + (Math.abs(z) + height) * .036;
        }
        if (Math.max(Math.abs(x), Math.abs(z)) >= outer - 3) {
            return 5.8 + alongSide * .072 + Math.min(10, height) * .065;
        }
        return .55 + radius * .075 + alongSide * .043 + Math.min(12, height) * .042;
    }

    static double structureCharge(double elapsed, double arrival) {
        return ease(0, .84, elapsed - arrival);
    }

    static double ease(double start, double end, double value) {
        double t = Math.clamp((value - start) / (end - start), 0, 1);
        return t * t * (3 - 2 * t);
    }

    enum BodyKind {
        GLINT(0, -1), STAR(3.5F, 0, new StellarPass(-12, .48, 1F, 1F)),
        ROCKY(1.55F, 1), GAS_GIANT(3.45F, 2), RINGED_GIANT(3.15F, 3),
        NEUTRON_STAR(1.75F, 8, new StellarPass(-4.5, .27, .08F, .14F)),
        PULSAR(1.85F, 9, new StellarPass(-5.0, .29, .12F, .18F)),
        WHITE_DWARF(2.15F, 10, new StellarPass(-6.0, .33, .34F, .40F)),
        BLACK_HOLE(2.6F, 11), QUASAR(9.1F, 12), WHITE_HOLE(2.55F, 13),
        THORNE_ZYTKOW(5.2F, 14, new StellarPass(-8, .38, .73F, .76F)),
        ANTISTAR(3.3F, 0, new StellarPass(-9, .47, 1F, .84F)),
        BLACK_DWARF(1.9F, 15, new StellarPass(-5.5, .30, .17F, .21F));

        final float radius;
        final int surfaceTile;
        final StellarPass stellarPass;

        BodyKind(float radius, int surfaceTile) {
            this(radius, surfaceTile, null);
        }

        BodyKind(float radius, int surfaceTile, StellarPass stellarPass) {
            this.radius = radius;
            this.surfaceTile = surfaceTile;
            this.stellarPass = stellarPass;
        }

        int surfaceTile(boolean warm) {
            return surfaceTile < 4 && surfaceTile >= 0 && !warm ? surfaceTile + 4 : surfaceTile;
        }

        boolean hasAccretionDisk() {
            return this == BLACK_HOLE || this == QUASAR;
        }

        boolean hasPlanetaryRings() {
            return this == GAS_GIANT || this == RINGED_GIANT;
        }

        CaptureProfile captureProfile() {
            return this == WHITE_HOLE ? CaptureProfile.OUTFLOW : CaptureProfile.STANDARD;
        }

        boolean luminousSurface() {
            return switch (this) {
                case STAR, NEUTRON_STAR, PULSAR, WHITE_DWARF, THORNE_ZYTKOW, ANTISTAR -> true;
                default -> false;
            };
        }

        // 时间已压缩为演出节奏。弥散包层先剥离，致密物质到达近地点时才显著受潮汐作用。
        float disruption(double captureAge) {
            return (float) switch (this) {
                case GAS_GIANT, RINGED_GIANT -> ease(-23.5, -11, captureAge);
                case STAR -> ease(-22.5, -12, captureAge);
                case ROCKY -> ease(-15, -4, captureAge);
                case NEUTRON_STAR -> ease(-9, -4.5, captureAge);
                case PULSAR -> ease(-11.5, -5, captureAge);
                case WHITE_DWARF -> ease(-11.5, -6, captureAge);
                case BLACK_DWARF -> ease(-8, -4, captureAge);
                case BLACK_HOLE -> ease(-12, -4.8, captureAge);
                case QUASAR -> ease(-17, -1.2, captureAge);
                case THORNE_ZYTKOW -> ease(-18, -8, captureAge);
                case ANTISTAR -> ease(-20, -9, captureAge);
                case GLINT, WHITE_HOLE -> 0;
            };
        }

        float surfaceSpin(double seconds, double phase) {
            // 演出内压缩转动周期，脉冲星表面与磁轴使用同一个相位。
            double turnsPerSecond = switch (this) {
                case STAR, ANTISTAR -> 1.0 / 14;
                case ROCKY -> 1.0 / 19;
                case GAS_GIANT -> 1.0 / 11;
                case RINGED_GIANT -> 1.0 / 12;
                case WHITE_DWARF, BLACK_DWARF -> 1.0 / 8;
                case THORNE_ZYTKOW -> 1.0 / 31;
                case NEUTRON_STAR -> 1.0 / 3.2;
                case PULSAR -> 1.0 / PULSAR_ROTATION_SECONDS;
                default -> 0;
            };
            return (float) (phase + seconds * Math.TAU * turnsPerSecond);
        }

        float gravityWaveStrength() {
            return switch (this) {
                case BLACK_HOLE -> 1F;
                case QUASAR -> .86F;
                default -> 0F;
            };
        }

        float siphonStrength() {
            return switch (this) {
                case STAR, ANTISTAR -> 1.75F;
                case GAS_GIANT, RINGED_GIANT -> 1.65F;
                case QUASAR -> 2.65F;
                case THORNE_ZYTKOW -> 2.05F;
                default -> 1F;
            };
        }

        float diskWidth() {
            return this == QUASAR ? 1.93F : .82F;
        }

        float jetLength() {
            return switch (this) {
                case PULSAR -> 3.6F;
                case QUASAR -> 7.75F;
                default -> 0;
            };
        }

        float effectExtent() {
            return switch (this) {
                case PULSAR, QUASAR -> jetLength() + .12F;
                case GAS_GIANT -> 3.2F;
                case RINGED_GIANT -> 3.75F;
                case BLACK_HOLE -> 3.2F; // 含吸积盘厚度与潮汐偏移。
                case WHITE_HOLE -> 3.52F;
                default -> 2.2F;
            };
        }
    }

    record StellarPass(double periapsisAge, double orbitGap, float radiusLoss, float matterYield) {
        boolean survives() {
            return radiusLoss < 1F;
        }
    }

    enum CaptureProfile {
        STANDARD(CAPTURE_PULSE_SECONDS, .23, 1F, .09, .49, 1F, 1),
        OUTFLOW(.92, .20, .56F, .09, .49, 0F, .72);

        private final double duration, peak, flashPeak, flashEnd, sweep;
        private final float light, flash;

        CaptureProfile(double duration, double peak, float light, double flashPeak, double flashEnd, float flash, double sweep) {
            this.duration = duration;
            this.peak = peak;
            this.light = light;
            this.flashPeak = flashPeak;
            this.flashEnd = flashEnd;
            this.flash = flash;
            this.sweep = sweep;
        }
    }

    private record Source(double angle, double vertical, double radiusScale, double siphonAt, double phase,
                          float size, boolean warm, BodyKind kind) {
        double siphonAge(double seconds) {
            return seconds - (kind == BodyKind.QUASAR ? QUASAR_SIPHON_START : siphonAt);
        }

        double infallDuration() {
            return kind == BodyKind.QUASAR ? QUASAR_INFALL_SECONDS : INFALL_SECONDS;
        }
    }

    record Star(double angle, double vertical, double radius, double phase, float size, boolean warm,
                double body, double leadingMatter, float brightness, float matterBrightness,
                BodyKind kind, float solidRadius, float tidalStress, float disruption, double captureAge) {
        float transverseCompression() {
            return disruption * switch (kind) {
                case STAR -> .46F;
                case GAS_GIANT, RINGED_GIANT -> .34F;
                case ROCKY -> .12F;
                case THORNE_ZYTKOW -> .24F * (1 - disruption);
                default -> 0F;
            };
        }
    }

    record DebrisSurge(double progress, float streamLight, double impact, float flash, CaptureWake wake) {
        float streamLightAt(double fraction) {
            return streamLight * (float) (1 - ease(0, .24, Math.abs(fraction - progress)));
        }
    }

    record CaptureWake(double tail, double head, float light) {
        double angleAt(double fraction) {
            return tail + (head - tail) * fraction;
        }

        float lightAt(double fraction) {
            return lightAt(light, fraction);
        }

        static float lightAt(float light, double fraction) {
            return light * (float) (ease(0, .8, fraction) * (1 - ease(.8, 1, fraction)));
        }
    }

    record QuasarIgnition(float disk, float jetReach, float flare) {}
    record JetPulse(float position, float light) {}

    record GravityWave(float radius, float width, float light, double azimuth) {
        StarBend bend(float[] camera, double x, double y, double z) {
            if (light <= .001F) return null;
            double dx = x - camera[0], dy = y - camera[1], dz = z - camera[2];
            double rayLength = dx * dx + dy * dy + dz * dz;
            if (rayLength < 1e-8) return null;
            double nx = .342 * Math.sin(azimuth), ny = .94, nz = -.342 * Math.cos(azimuth);
            double altitude = nx * camera[0] + ny * camera[1] + nz * camera[2];
            double rayAngle = nx * dx + ny * dy + nz * dz;
            // 求视线和波环平面的交点。平视接近共面时，用视线离环心最近的点。
            double depth = Math.abs(altitude) < .30
                    ? -(camera[0] * dx + camera[1] * dy + camera[2] * dz) / rayLength : -altitude / rayAngle;
            if (!(depth > 0 && depth <= 1)) return null;
            double px = camera[0] + dx * depth, py = camera[1] + dy * depth, pz = camera[2] + dz * depth;
            double distance = Math.sqrt(px * px + py * py + pz * pz);
            double offset = (distance - radius) / (2.75 * width);
            if (Math.abs(offset) >= 1 || distance < 1e-6) return null;
            float strength = (float) (light * (1 - offset * offset) * (1 - offset * offset));
            double scale = width * strength * Math.min(3.4, 1 / depth) / distance;
            double radial = .80 - 2.30 * offset * offset, twist = .58 * offset;
            return new StarBend(scale * (radial * px + twist * (ny * pz - nz * py)),
                    scale * (radial * py + twist * (nz * px - nx * pz)),
                    scale * (radial * pz + twist * (nx * py - ny * px)), strength);
        }
    }

    record StarBend(double x, double y, double z, float strength) {}

    record Point(double x, double y, double z) {}
}
