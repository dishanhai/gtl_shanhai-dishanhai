package com.dishanhai.gt_shanhai.client.renderer.preview;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterInstallation;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterVisualState;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT)
public final class StarLifterAudio {
    private static final double RANGE = 120;
    private static final double STAR_RANGE = 108;
    private static final SoundEvent IGNITION = sound("ignition");
    private static final SoundEvent HUM = sound("hum");
    private static final SoundEvent ROTATION = sound("rotation");
    private static final SoundEvent STAR = sound("stellar_resonance");
    private static final SoundEvent RING_PASS = sound("ring_pass");
    private static final SoundEvent SHUTDOWN = sound("shutdown");
    private static final SoundEvent FAULT = sound("fault");
    private static ClientLevel audioLevel;
    private static Layer currentHum;
    private static Layer currentRotation;
    private static Layer currentStar;
    private static long nextRingPassTick = Long.MIN_VALUE;

    private StarLifterAudio() {}

    private static SoundEvent sound(String cue) {
        return SoundEvent.createVariableRangeEvent(new ResourceLocation(GTDishanhaiMod.MOD_ID, "star_lifter." + cue));
    }

    static void onSnapshot(Minecraft game, List<StarLifterInstallation> before, List<StarLifterInstallation> after) {
        if (before == null || game.player == null) return; // Initial world sync is not an operating transition.
        var listener = game.player.getEyePosition();
        for (var next : after) {
            if (!next.isController() || listener.distanceToSqr(next.center()) >= RANGE * RANGE) continue;
            var prior = before.stream().filter(old -> old.name().equals(next.name())).findFirst().orElse(null);
            var state = next.visual();
            long age = game.level.getGameTime() - state.since();
            if (age < -20 || age > 25) continue;
            var cue = transition(prior == null ? StarLifterVisualState.IDLE : prior.visual(), state);
            if (cue != null) play(game, cue, next.center());
        }
    }

    static SoundEvent transition(StarLifterVisualState before, StarLifterVisualState after) {
        if (before.mode() == after.mode()) return null;
        if (after.mode() == StarLifterVisualState.Mode.FAULT) return FAULT;
        if (!before.extracting() && after.extracting()) return IGNITION;
        if (before.extracting() && !after.extracting()) return SHUTDOWN;
        return null;
    }

    private static void play(Minecraft game, SoundEvent cue, Vec3 center) {
        game.getSoundManager().play(new SimpleSoundInstance(cue, SoundSource.BLOCKS, 0.72f, 1,
                SoundInstance.createUnseededRandom(), center.x, center.y, center.z));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var game = Minecraft.getInstance();
        var level = game.level;
        if (level == null || game.player == null) {
            clear();
            return;
        }
        if (audioLevel != level) {
            clear();
            audioLevel = level;
        }
        if (game.isPaused()) return;

        var listener = game.player.getEyePosition();
        StarLifterInstallation nearestRotation = null;
        StarLifterInstallation nearestHum = null;
        StarLifterInstallation nearestStar = null;
        double rotationDistance = RANGE * RANGE;
        double humDistance = RANGE * RANGE;
        double starDistance = STAR_RANGE * STAR_RANGE;
        if (game.options.getSoundSourceVolume(SoundSource.BLOCKS) > 0
                && game.options.getSoundSourceVolume(SoundSource.MASTER) > 0) {
            for (var installation : StarLifterWorld.installations(level)) {
                double distance = listener.distanceToSqr(installation.center());
                if (distance >= RANGE * RANGE) continue;
                double rotationScore = sourceScore(installation, currentRotation, distance);
                if (rotationScore < rotationDistance) {
                    rotationDistance = rotationScore;
                    nearestRotation = installation;
                }
                if (distance < STAR_RANGE * STAR_RANGE) {
                    double starScore = sourceScore(installation, currentStar, distance);
                    if (starScore < starDistance) {
                        starDistance = starScore;
                        nearestStar = installation;
                    }
                }
                if (!installation.isController() || !installation.visual().extracting()) continue;
                double humScore = sourceScore(installation, currentHum, distance);
                if (humScore < humDistance) {
                    humDistance = humScore;
                    nearestHum = installation;
                }
            }
        }
        if (nearestRotation != null && level.getGameTime() >= nextRingPassTick) {
            Vec3 pass = StarLifterRingPass.crossing(nearestRotation, listener, level.getGameTime());
            if (pass != null) {
                play(game, RING_PASS, pass);
                nextRingPassTick = level.getGameTime() + 29;
            }
        }
        currentRotation = updateLayer(game, currentRotation, nearestRotation, ROTATION);
        currentHum = updateLayer(game, currentHum, nearestHum, HUM);
        currentStar = updateLayer(game, currentStar, nearestStar, STAR);
    }

    private static double sourceScore(StarLifterInstallation installation, Layer current, double distance) {
        // Favor the current source to avoid swapping back and forth between adjacent installations.
        return current != null && current.matches(installation) ? distance * 0.85 : distance;
    }

    private static Layer updateLayer(Minecraft game, Layer current, StarLifterInstallation next, SoundEvent event) {
        if (current != null && (next == null || !current.matches(next))) {
            current.released = true;
            current = null;
        }
        if (next == null) return null;
        if (current == null || (current.age > 12 && !game.getSoundManager().isActive(current))) {
            current = new Layer(game.level, next, event);
            game.getSoundManager().play(current);
        }
        if (event == STAR) {
            current.targetStellarVolume = stellarVolume(next.visual().reserveFraction(game.level.getGameTime()));
        } else if (event == HUM) {
            current.highLoad = next.visual().mode() == StarLifterVisualState.Mode.HIGH_LOAD;
        }
        return current;
    }

    static float stellarVolume(float reserveFraction) {
        return 0.15f + 0.28f * reserveFraction;
    }

    static float rotationSpin(long elapsedTicks) {
        float t = Math.clamp(elapsedTicks / 48f, 0f, 1f);
        return t * t * (3 - 2 * t);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    private static void clear() {
        audioLevel = null;
        var sounds = Minecraft.getInstance().getSoundManager();
        if (currentHum != null) sounds.stop(currentHum);
        if (currentRotation != null) sounds.stop(currentRotation);
        if (currentStar != null) sounds.stop(currentStar);
        currentHum = null;
        currentRotation = null;
        currentStar = null;
        nextRingPassTick = Long.MIN_VALUE;
    }

    private static final class Layer extends AbstractTickableSoundInstance {
        private final ClientLevel level;
        private final String name;
        private final Vec3 center;
        private final long startTick;
        private final boolean rotation;
        private final boolean stellar;
        private int age;
        private boolean released;
        private boolean highLoad;
        private float targetStellarVolume;

        Layer(ClientLevel level, StarLifterInstallation installation, SoundEvent event) {
            super(event, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
            this.level = level;
            name = installation.name();
            center = installation.center();
            startTick = installation.startTick();
            rotation = event == ROTATION;
            stellar = event == STAR;
            x = center.x;
            y = center.y;
            z = center.z;
            volume = 0.02f;
            if (rotation) pitch = 0.64f + 0.36f * rotationSpin(level.getGameTime() - startTick);
            looping = true;
        }

        boolean matches(StarLifterInstallation installation) {
            return name.equals(installation.name()) && center.equals(installation.center())
                    && startTick == installation.startTick();
        }

        @Override
        public void tick() {
            if (Minecraft.getInstance().level != level) {
                stop();
                return;
            }
            age++;
            float spin = rotation ? rotationSpin(level.getGameTime() - startTick) : 1;
            float targetVolume = stellar ? targetStellarVolume : rotation ? 0.62f * spin : highLoad ? 0.68f : 0.48f;
            float targetPitch = rotation ? 0.64f + 0.36f * spin : highLoad ? 1.08f : 1f;
            if (released) {
                targetVolume = 0;
                if (rotation) targetPitch = 0.54f;
            }
            volume += (targetVolume - volume) * (stellar ? 0.06f : released ? (rotation ? 0.12f : 0.24f) : 0.085f);
            pitch += (targetPitch - pitch) * 0.06f;
            if (released && volume < 0.012f) stop();
        }
    }
}
