// Author: dishanhai
package com.dishanhai.gt_shanhai.common.block;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.sh_common_lib.api.ulk.fumo.FumoPack;
import com.dishanhai.sh_common_lib.api.ulk.fumo.Fumos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

public final class DShanhaiFumos {
    private static final String DGYJH02804 = "dgyjh02804_fumo";
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, GTDishanhaiMod.MOD_ID);
    private static FumoPack pack;

    private DShanhaiFumos() {}

    public static void init(IEventBus bus) {
        pack = Fumos.register(bus, GTDishanhaiMod.BLOCKS, GTDishanhaiMod.ITEMS, BLOCK_ENTITIES,
                "fumo_dance", DGYJH02804);
        BLOCK_ENTITIES.register(bus);
    }

    public static Item dgyjh02804Item() {
        return pack.variant(DGYJH02804).item().get();
    }

    @Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT)
    public static final class Client {
        @SubscribeEvent
        public static void onTooltip(ItemTooltipEvent event) {
            if (event.getItemStack().is(dgyjh02804Item())) {
                event.getToolTip().add(Component.translatable("tooltip.gt_shanhai.dgyjh02804_fumo.skin_note")
                        .withStyle(ChatFormatting.GRAY));
            }
        }
    }
}
