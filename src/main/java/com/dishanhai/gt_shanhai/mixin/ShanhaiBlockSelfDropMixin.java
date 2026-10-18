package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraftforge.registries.ForgeRegistries;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * gt_shanhai 的方块没有战利品表时，破坏后什么都不掉。
 * 这里在原版查表之前补上「掉自己」。已经写了战利品表的方块仍走原版。
 *
 * <p>1.20.1 的掉落收口是 {@code BlockBehaviour.BlockStateBase.getDrops(LootParams.Builder)}
 * （SRG {@code m_287290_}），它再转发到 {@code BlockBehaviour.getDrops(BlockState, LootParams.Builder)}。
 * GTCEu 的 {@code MetaMachineBlock} 覆写了后者，只挂 {@code BlockBehaviour} 会漏掉机器方块，
 * 所以这里挂 BlockStateBase，一次覆盖普通方块和机器方块。
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public class ShanhaiBlockSelfDropMixin {

    // 本项目无 refmap：BlockStateBase.getDrops 在产线是 m_287290_，必须 SRG 名 + remap=false。
    @Inject(method = "m_287290_", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$dropSelfWhenLootMissing(LootParams.Builder builder,
            CallbackInfoReturnable<List<ItemStack>> cir) {
        Block block = ((BlockState) (Object) this).getBlock();
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
        if (id == null || !GTDishanhaiMod.MOD_ID.equals(id.getNamespace())) {
            return;
        }
        if (builder.getLevel() != null && builder.getLevel().getServer() != null) {
            ResourceLocation lootFile = new ResourceLocation(id.getNamespace(),
                    "loot_tables/blocks/" + id.getPath() + ".json");
            if (builder.getLevel().getServer().getResourceManager().getResource(lootFile).isPresent()) {
                return;
            }
        }
        Item item = block.asItem();
        if (item == null || item == Items.AIR) {
            item = ForgeRegistries.ITEMS.getValue(id);
        }
        if (item == null || item == Items.AIR) {
            return;
        }
        List<ItemStack> drops = new ArrayList<>(1);
        drops.add(new ItemStack(item));
        cir.setReturnValue(drops);
    }
}
