package com.dishanhai.gt_shanhai.common.machine.wave;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 引力波威慑 — 怪物生成阻止事件处理器
 * <p>
 * 广播模式激活时，范围内禁止一切敌对怪物生成。
 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class GravitationalWaveSpawnHandler {

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        // 仅拦截敌对生物（MobCategory.MONSTER）
        if (event.getEntity().getType().getCategory() != MobCategory.MONSTER) return;
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;

        BlockPos pos = event.getEntity().blockPosition();
        if (GravitationalWaveBroadcastManager.INSTANCE.isInRange(serverLevel, pos)) {
            event.setCanceled(true);
        }
    }
}
