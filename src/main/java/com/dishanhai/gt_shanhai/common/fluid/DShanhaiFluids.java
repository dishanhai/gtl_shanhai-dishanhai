package com.dishanhai.gt_shanhai.common.fluid;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.item.MiracleFlowBucketItem;
import com.tterrag.registrate.util.entry.FluidEntry;
import com.tterrag.registrate.util.entry.ItemEntry;
import com.tterrag.registrate.util.nullness.NonNullBiConsumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fluids.ForgeFlowingFluid;

import static com.dishanhai.gt_shanhai.GTDishanhaiRegistration.REGISTRATE;

/** 山海：供配方與儲槽使用的獨立流體。 */
public final class DShanhaiFluids {
    public static FluidEntry<ForgeFlowingFluid.Flowing> MIRACLE_FLOW;
    public static ItemEntry<MiracleFlowBucketItem> MIRACLE_FLOW_BUCKET;

    public static void init() {
        var texture = new ResourceLocation(GTDishanhaiMod.MOD_ID, "block/fluid/miracle_flow");
        var fluid = REGISTRATE.fluid("miracle_flow", texture, texture)
                .source(ForgeFlowingFluid.Source::new)
                .noBlock()
                // 保留桶供應器，只移除不存在的世界流體方塊供應器。
                .fluidProperties(properties -> properties.block(null));
        MIRACLE_FLOW_BUCKET = fluid.bucket(MiracleFlowBucketItem::new)
                .model(NonNullBiConsumer.noop())
                .register();
        MIRACLE_FLOW = fluid.register();
    }

    private DShanhaiFluids() {}
}
