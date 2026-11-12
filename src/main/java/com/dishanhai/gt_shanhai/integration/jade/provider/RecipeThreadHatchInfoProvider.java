package com.dishanhai.gt_shanhai.integration.jade.provider;

// Author: dishanhai
import com.dishanhai.gt_shanhai.api.machine.IRecipeThreadDisplay;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeThreadHatchPartMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.gtlcore.gtlcore.utils.NumberUtils;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElement;

/** 山海：配方執行緒倉本體 Jade 顯示（能力開關、槽內執行緒物、批次容量、連接控制器與匯總在途狀態）。 */
public enum RecipeThreadHatchInfoProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation UID = new ResourceLocation("gt_shanhai", "recipe_thread_hatch_info");
    private static final String DATA_KEY = "recipeThreadHatch";

    public static String getDataKey() {
        return DATA_KEY;
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        RecipeThreadHatchPartMachine hatch = getMachine(accessor);
        if (hatch == null) return;

        CompoundTag tag = new CompoundTag();
        tag.putBoolean("enabled", hatch.isRecipeThreadsEnabled());
        tag.putLong("capacity", hatch.getRecipeThreadCapacity());

        ItemStack stack = hatch.getThreadSlot().getStackInSlot(0);
        if (!stack.isEmpty()) {
            tag.put("item", stack.save(new CompoundTag()));
            tag.putInt("itemCount", stack.getCount());
            tag.putLong("itemBoost", PrimordialOmegaEngineModuleBase.getRecipeThreadBoostForStack(stack));
        }

        var owners = hatch.getControllers();
        tag.putInt("owners", owners.size());
        long running = 0L, waiting = 0L, reserved = 0L, parallel = 0L;
        for (var owner : owners) {
            if (owner instanceof IRecipeLogicMachine machine
                    && machine.getRecipeLogic() instanceof IRecipeThreadDisplay display
                    && display.isRecipeThreadDisplayAvailable()) {
                CompoundTag snapshot = display.getRecipeThreadDisplay(0, 0);
                running = saturatedAdd(running, snapshot.getInt("running"));
                waiting = saturatedAdd(waiting, snapshot.getInt("waiting"));
                reserved = saturatedAdd(reserved, snapshot.getLong("reserved"));
                parallel = saturatedAdd(parallel, snapshot.getLong("parallelCapacity"));
            }
        }
        tag.putLong("running", running);
        tag.putLong("waiting", waiting);
        tag.putLong("reserved", reserved);
        tag.putLong("parallel", parallel);
        data.put(DATA_KEY, tag);
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (accessor == null || !accessor.getServerData().contains(DATA_KEY)) return;
        CompoundTag data = accessor.getServerData().getCompound(DATA_KEY);
        if (data.isEmpty()) return;

        var helper = tooltip.getElementHelper();
        tooltip.add(helper.text(Component.translatable("gt_shanhai.recipe_threads.ui.title")));
        tooltip.add(helper.text(Component.translatable("gt_shanhai.recipe_threads.ui.state",
                Component.translatable(data.getBoolean("enabled")
                        ? "gt_shanhai.recipe_threads.enabled"
                        : "gt_shanhai.recipe_threads.disabled"))));
        tooltip.add(helper.text(Component.translatable("gt_shanhai.recipe_threads.ui.capacity",
                capacityText(data.getLong("capacity")))));

        if (data.contains("item", 10)) {
            ItemStack stack = ItemStack.of(data.getCompound("item"));
            IElement icon = stack.isEmpty() ? null : helper.smallItem(stack);
            Component itemLine = Component.translatable("gt_shanhai.recipe_threads.jade.item",
                    stack.getHoverName(),
                    data.getInt("itemCount"),
                    boostText(data.getLong("itemBoost")));
            if (icon != null) {
                tooltip.add(new PrimordialRecipeThreadText(itemLine, helper.text(itemLine), icon));
            } else {
                tooltip.add(helper.text(itemLine));
            }
        } else {
            tooltip.add(helper.text(Component.translatable("gt_shanhai.recipe_threads.jade.item_empty")));
        }

        tooltip.add(helper.text(Component.translatable("gt_shanhai.recipe_threads.ui.owners",
                data.getInt("owners"))));
        tooltip.add(helper.text(Component.translatable("gt_shanhai.recipe_threads.ui.activity",
                NumberUtils.formatLong(data.getLong("running")),
                NumberUtils.formatLong(data.getLong("waiting")))));
        tooltip.add(helper.text(Component.translatable("gt_shanhai.recipe_threads.ui.parallel",
                NumberUtils.formatLong(data.getLong("reserved")),
                capacityText(data.getLong("parallel")))));
    }

    private static RecipeThreadHatchPartMachine getMachine(BlockAccessor accessor) {
        if (accessor == null || !(accessor.getBlockEntity() instanceof MetaMachineBlockEntity mbe)) return null;
        return mbe.getMetaMachine() instanceof RecipeThreadHatchPartMachine hatch ? hatch : null;
    }

    private static Object capacityText(long capacity) {
        return capacity == Long.MAX_VALUE
                ? Component.translatable("gt_shanhai.primordial.threads.unlimited")
                : NumberUtils.formatLong(capacity);
    }

    private static Object boostText(long boost) {
        return boost >= Long.MAX_VALUE - 1L
                ? Component.translatable("gt_shanhai.primordial.threads.unlimited")
                : "+" + NumberUtils.formatLong(boost);
    }

    private static long saturatedAdd(long a, long b) {
        return b <= 0L ? a : a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }
}
