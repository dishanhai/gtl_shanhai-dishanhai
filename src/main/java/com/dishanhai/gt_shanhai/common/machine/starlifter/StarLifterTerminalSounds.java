package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/** Client-only terminal feedback. The sounds are local and are never broadcast in the world. */
enum StarLifterTerminalSounds {
    NAVIGATE("navigate", .48f), OPEN("open", .45f), CLOSE("close", .45f),
    ADJUST("adjust", .43f), FOCUS("focus", .46f),
    ENABLE("enable", .48f), DISABLE("disable", .48f),
    SUBMIT("submit", .45f), SUCCESS("success", .60f), REJECT("reject", .55f),
    BOOT("boot", .54f);

    private final SoundEvent sound;
    private final float volume;

    StarLifterTerminalSounds(String name, float volume) {
        sound = SoundEvent.createVariableRangeEvent(new ResourceLocation(GTDishanhaiMod.MOD_ID, "star_lifter.ui." + name));
        this.volume = volume;
    }

    static StarLifterTerminalSounds toggle(boolean enabled) {
        return enabled ? ENABLE : DISABLE;
    }

    void play() {
        Minecraft.getInstance().getSoundManager().play(instance());
    }

    SimpleSoundInstance instance() {
        return SimpleSoundInstance.forUI(sound, 1f, volume);
    }
}
