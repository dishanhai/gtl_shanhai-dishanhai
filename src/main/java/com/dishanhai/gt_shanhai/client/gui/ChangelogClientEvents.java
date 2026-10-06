package com.dishanhai.gt_shanhai.client.gui;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.config.ChangelogConfig;

import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 主菜单初始化完成后显示当前最高版本的更新公告。
 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT)
public final class ChangelogClientEvents {

    private static boolean shownThisLaunch;

    private ChangelogClientEvents() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (shownThisLaunch || !(event.getScreen() instanceof TitleScreen titleScreen)) {
            return;
        }
        shownThisLaunch = true;
        if (ChangelogConfig.shouldShow()) {
            ChangelogScreen.open(titleScreen);
        }
    }
}
