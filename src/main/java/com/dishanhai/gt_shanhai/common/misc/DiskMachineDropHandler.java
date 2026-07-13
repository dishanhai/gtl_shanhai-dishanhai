package com.dishanhai.gt_shanhai.common.misc;

import com.dishanhai.gt_shanhai.common.machine.misc.SingularityDataHubMachine;
import com.dishanhai.gt_shanhai.common.machine.misc.ProxyExecutorMachine;
import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.dishanhai.gt_shanhai.common.machine.part.MEDiskHatchPartMachine;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;

import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** 保护自定义磁盘槽：机器被挖掉时主动掉落内部磁盘。 */
public class DiskMachineDropHandler {
    private static boolean initialized = false;

    public static void init() {
        if (initialized) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.register(new DiskMachineDropHandler());
    }

    @SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof Level level) || level.isClientSide) return;
        MetaMachine machine = MetaMachine.getMachine(level, event.getPos());
        if (machine instanceof MEDiskHatchPartMachine hatch) {
            dropDiskHatch(level, hatch);
        } else if (machine instanceof SingularityDataHubMachine hub) {
            dropDataHub(level, hub);
        } else if (machine instanceof ProxyExecutorMachine proxy) {
            dropProxyExecutor(level, proxy);
        } else if (machine instanceof DShanhaiMaintenanceHatchMachine hub) {
            dropMaintenanceHatch(level, hub);
        }
    }

    private static void dropDiskHatch(Level level, MEDiskHatchPartMachine hatch) {
        hatch.forcePersistAll();
        int slots = hatch.getDiskSlots().getSlots();
        for (int i = 0; i < slots; i++) {
            ItemStack stack = hatch.getDiskSlots().getStackInSlot(i);
            if (stack.isEmpty()) continue;
            Containers.dropItemStack(level, hatch.getPos().getX() + 0.5, hatch.getPos().getY() + 0.5, hatch.getPos().getZ() + 0.5, stack.copy());
            hatch.getDiskSlots().storage.setStackInSlot(i, ItemStack.EMPTY);
        }
        hatch.markDirty();
    }

    private static void dropDataHub(Level level, SingularityDataHubMachine hub) {
        ItemStack stack = hub.diskArraySlot.getStackInSlot(0);
        if (stack.isEmpty()) return;
        Containers.dropItemStack(level, hub.getPos().getX() + 0.5, hub.getPos().getY() + 0.5, hub.getPos().getZ() + 0.5, stack.copy());
        hub.diskArraySlot.storage.setStackInSlot(0, ItemStack.EMPTY);
        hub.markDirty();
    }

    private static void dropProxyExecutor(Level level, ProxyExecutorMachine proxy) {
        dropSlot(level, proxy, proxy.targetMachineSlot.storage);
        dropSlot(level, proxy, proxy.boostSlot.storage);
        dropSlot(level, proxy, proxy.threadBoostSlot.storage);
        proxy.markDirty();
    }

    private static void dropMaintenanceHatch(Level level, DShanhaiMaintenanceHatchMachine hub) {
        dropSlot(level, hub, hub.getModuleSlot());
        dropSlot(level, hub, hub.getAstralSlot());
        dropSlot(level, hub, hub.getTearSlot());
        dropSlot(level, hub, hub.getThreadBoostSlot());
        hub.markDirty();
    }

    private static void dropSlot(Level level, MetaMachine machine, ItemStackTransfer transfer) {
        ItemStack stack = transfer.getStackInSlot(0);
        if (stack.isEmpty()) return;
        Containers.dropItemStack(level, machine.getPos().getX() + 0.5, machine.getPos().getY() + 0.5, machine.getPos().getZ() + 0.5, stack.copy());
        transfer.setStackInSlot(0, ItemStack.EMPTY);
    }
}
