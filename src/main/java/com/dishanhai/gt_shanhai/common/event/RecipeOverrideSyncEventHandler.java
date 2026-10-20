package com.dishanhai.gt_shanhai.common.event;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.network.RecipeSyncPacket;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 玩家登入時補發配方覆寫內容（山海署名）。
 *
 * <p>覆寫只存在伺服器的 {@code config/gt_shanhai/recipe/recipe_overrides.json}。
 * 專用伺服器的客戶端沒有這份檔案，全量刷新只發類型 id，客戶端用本地 lookup
 * 重新產生 wrapper，於是只能看到 strip/replace 的結果，看不到 override。</p>
 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecipeOverrideSyncEventHandler {

    private RecipeOverrideSyncEventHandler() {}

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        RecipeSyncPacket.syncOverridesTo(player);
    }
}
