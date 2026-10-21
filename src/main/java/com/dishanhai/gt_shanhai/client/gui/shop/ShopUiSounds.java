package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.UiSoundThrottle;
import com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * 商店界面音效。全用原版声音，不新增资源；统一过「商店音效」开关（{@link ClientShopUiSettings#uiSounds()}）
 * 和按频道节流（{@link UiSoundThrottle}），连发、连点时不会叠成一片。
 */
public final class ShopUiSounds {

    private static final UiSoundThrottle THROTTLE = new UiSoundThrottle();

    private ShopUiSounds() {}

    /** 任何按钮按下。 */
    public static void click() {
        play("click", 40L, SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.25f);
    }

    /** 步进连发的滴答；音高随连发次数上扬，约 16 次后到顶。 */
    public static void step(int repeatIndex) {
        float pitch = 1.0f + 0.6f * Math.min(1f, repeatIndex / 16f);
        play("step", 45L, SoundEvents.NOTE_BLOCK_HAT.value(), pitch, 0.18f);
    }

    /** 数量到了 1 或上限，再按不动。 */
    public static void limit() {
        play("limit", 250L, SoundEvents.NOTE_BLOCK_BASS.value(), 0.70f, 0.30f);
    }

    /** 还在等回执或去抖窗口内又点了一下，这一下被吞掉。 */
    public static void busy() {
        play("busy", 120L, SoundEvents.NOTE_BLOCK_HAT.value(), 0.70f, 0.20f);
    }

    /** 全部成交。买和卖用不同的声音。 */
    public static void success(boolean sell) {
        if (sell) {
            play("result", 80L, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 0.30f);
        } else {
            play("result", 80L, SoundEvents.NOTE_BLOCK_PLING.value(), 1.5f, 0.30f);
        }
    }

    /** 部分成交。 */
    public static void partial() {
        play("result", 80L, SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 0.28f);
    }

    /** 未成交，或本地预检就过不去。 */
    public static void fail() {
        play("fail", 150L, SoundEvents.NOTE_BLOCK_BASS.value(), 0.55f, 0.35f);
    }

    /** 危险操作进入二次确认（删除商品第一次点）。 */
    public static void warn() {
        play("warn", 150L, SoundEvents.NOTE_BLOCK_BIT.value(), 0.90f, 0.25f);
    }

    private static void play(String channel, long gapMs, SoundEvent sound, float pitch, float volume) {
        if (!ClientShopUiSettings.uiSounds()) return;
        if (!THROTTLE.allow(channel, gapMs, System.currentTimeMillis())) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }
}
