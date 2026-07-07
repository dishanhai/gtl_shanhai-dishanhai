package com.dishanhai.gt_shanhai.common.machine.wave;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.common.data.GTRecipeCapabilities;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 引力波广播管理器 — 全局单例
 * <p>
 * 追踪所有活动中的广播源，提供范围查询和全局效果。
 * <p>
 * 效果分发：
 * <ul>
 *   <li><b>加速</b>：由 {@link com.dishanhai.gt_shanhai.mixin.BroadcastEffectMixin} 在目标机器
 *       {@code setupRecipe} 时通过 {@link #getPowerLevel} 查询加速比率</li>
 *   <li><b>复制</b>：由本类的 {@link #tickDuplication} 每 100 tick 扫描范围内机器，
 *       检测是否有未复制的已完成配方</li>
 *   <li><b>怪物阻止</b>：由 {@link GravitationalWaveSpawnHandler} 在生物生成时查询 {@link #isInRange}</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class GravitationalWaveBroadcastManager {

    private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai:broadcast_mgr");
    public static final GravitationalWaveBroadcastManager INSTANCE = new GravitationalWaveBroadcastManager();

    private final Map<ResourceKey<Level>, Map<BlockPos, BroadcastSource>> sources = new HashMap<>();
    private int duplicationTickCounter = 0;

    private GravitationalWaveBroadcastManager() {}

    public static class BroadcastSource {
        public final BlockPos pos;
        public final int radius;
        public final int powerLevel;
        public final int lensCount;
        public final int fixedOutputMultiplier;

        public BroadcastSource(BlockPos pos, int radius, int powerLevel, int lensCount) {
            this(pos, radius, powerLevel, lensCount, 0);
        }

        public BroadcastSource(BlockPos pos, int radius, int powerLevel, int lensCount, int fixedOutputMultiplier) {
            this.pos = pos;
            this.radius = radius;
            this.powerLevel = powerLevel;
            this.lensCount = lensCount;
            this.fixedOutputMultiplier = fixedOutputMultiplier;
        }
    }

    public void addSource(ServerLevel level, BlockPos pos, int radius, int powerLevel) {
        addSource(level, pos, radius, powerLevel, 0);
    }

    public void addSource(ServerLevel level, BlockPos pos, int radius, int powerLevel, int lensCount) {
        addSource(level, pos, radius, powerLevel, lensCount, 0);
    }

    public void addSource(ServerLevel level, BlockPos pos, int radius, int powerLevel, int lensCount, int fixedOutputMultiplier) {
        sources.computeIfAbsent(level.dimension(), k -> new HashMap<>())
               .put(pos, new BroadcastSource(pos, radius, Math.min(100, Math.max(0, powerLevel)), lensCount, fixedOutputMultiplier));
        LOG.info("Source added: dim={}, pos={}, radius={}, power={}, lenses={}, fixedMultiplier={}",
                level.dimension().location(), pos, radius, powerLevel, lensCount, fixedOutputMultiplier);
    }

    public int getLensCount(ServerLevel level, BlockPos pos) {
        var dimMap = sources.get(level.dimension());
        if (dimMap == null || dimMap.isEmpty()) return 0;
        int maxLenses = 0;
        for (var source : dimMap.values()) {
            long radiusSqr = (long) source.radius * source.radius;
            if (pos.distSqr(source.pos) <= radiusSqr && source.lensCount > maxLenses) {
                maxLenses = source.lensCount;
            }
        }
        return maxLenses;
    }

    public void removeSource(ServerLevel level, BlockPos pos) {
        var dimMap = sources.get(level.dimension());
        if (dimMap != null) {
            dimMap.remove(pos);
            if (dimMap.isEmpty()) sources.remove(level.dimension());
        }
    }

    public boolean isInRange(ServerLevel level, BlockPos pos) {
        var dimMap = sources.get(level.dimension());
        if (dimMap == null || dimMap.isEmpty()) return false;
        for (var source : dimMap.values()) {
            if (pos.distSqr(source.pos) <= (long) (source.radius + 1) * (source.radius + 1)) return true;
        }
        return false;
    }

    public int getPowerLevel(ServerLevel level, BlockPos pos) {
        var dimMap = sources.get(level.dimension());
        if (dimMap == null || dimMap.isEmpty()) return 0;
        int maxPower = 0;
        for (var source : dimMap.values()) {
            double distSqr = pos.distSqr(source.pos);
            long radiusSqr = (long) source.radius * source.radius;
            if (distSqr <= radiusSqr) {
                double dist = Math.sqrt(distSqr);
                double factor = 1.0 - (dist / source.radius);
                int power = (int) (source.powerLevel * factor);
                if (power > maxPower) maxPower = power;
            }
        }
        return maxPower;
    }

    public int getFixedOutputMultiplier(ServerLevel level, BlockPos pos) {
        var dimMap = sources.get(level.dimension());
        if (dimMap == null || dimMap.isEmpty()) return 0;
        int maxMultiplier = 0;
        for (var source : dimMap.values()) {
            if (source.fixedOutputMultiplier <= 1) continue;
            if (pos.equals(source.pos)) continue;
            if (pos.distSqr(source.pos) <= (long) source.radius * source.radius) {
                maxMultiplier = Math.max(maxMultiplier, source.fixedOutputMultiplier);
            }
        }
        return maxMultiplier;
    }

    public Optional<BroadcastSource> getNearestSource(ServerLevel level, BlockPos pos) {
        var dimMap = sources.get(level.dimension());
        if (dimMap == null || dimMap.isEmpty()) return Optional.empty();
        BroadcastSource nearest = null;
        double nearestDistSqr = Double.MAX_VALUE;
        for (var source : dimMap.values()) {
            double distSqr = pos.distSqr(source.pos);
            long radiusSqr = (long) source.radius * source.radius;
            if (distSqr <= radiusSqr && distSqr < nearestDistSqr) {
                nearest = source;
                nearestDistSqr = distSqr;
            }
        }
        return Optional.ofNullable(nearest);
    }

    public void clearDimension(ResourceKey<Level> dimension) {
        sources.remove(dimension);
    }

    public void clearAll() {
        sources.clear();
    }

    // ========== 全局复制兜底 tick ==========

    /**
     * 每 tick 处理复制效果（实际每 100 tick 扫描一次）。
     * 作为 {@link BroadcastEffectMixin#onRecipeFinish} 的兜底：
     * 当目标机器使用 gtladditions/gtlcore 配方逻辑时，其 {@code onRecipeFinish}
     * 不调用父类方法，导致 mixin 注入代码被绕过。
     * 本方法通过扫描范围内机器的 {@code lastRecipe} 来尝试复制产物。
     */
    // 产出倍率已统一到 BroadcastEffectMixin.setupRecipe
    // @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        INSTANCE.duplicationTickCounter++;
        if (INSTANCE.duplicationTickCounter % 100 != 0) return; // 每 5 秒扫描一次
        INSTANCE.tickDuplication();
    }

    @SuppressWarnings("unchecked")
    private void tickDuplication() {
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        for (var dimEntry : sources.entrySet()) {
            ResourceKey<Level> dimension = (ResourceKey<Level>) dimEntry.getKey();
            ServerLevel level = server.getLevel(dimension);
            if (level == null) continue;

            Map<BlockPos, BroadcastSource> dimSources = (Map<BlockPos, BroadcastSource>) dimEntry.getValue();
            if (dimSources == null || dimSources.isEmpty()) continue;

            for (BroadcastSource source : dimSources.values()) {
                tryDuplicationForSource(level, source);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void tryDuplicationForSource(ServerLevel level, BroadcastSource source) {
        // 透镜复制倍率：有透镜时 3x 概率 = lensCount/16，否则固定 2x
        int multiplier;
        if (source.lensCount > 0) {
            float chance3x = Math.min(1.0f, source.lensCount * (1.0f / 16));
            multiplier = level.getRandom().nextFloat() < chance3x ? 300 : 200;
        } else {
            multiplier = 200;
        }

        int minX = source.pos.getX() - source.radius;
        int maxX = source.pos.getX() + source.radius;
        int minZ = source.pos.getZ() - source.radius;
        int maxZ = source.pos.getZ() + source.radius;
        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                var chunk = level.getChunk(cx, cz);
                for (var entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos targetPos = (BlockPos) entry.getKey();
                    double distSqr = targetPos.distSqr(source.pos);
                    long radiusSqr = (long) source.radius * source.radius;
                    if (distSqr > radiusSqr) continue;

                    // 有透镜时无条件复制；无透镜时按 power 概率复制
                    if (source.lensCount <= 0) {
                        double dist = Math.sqrt(distSqr);
                        double chance = (1.0 - dist / source.radius) * source.powerLevel * 0.005;
                        if (chance <= 0) continue;
                        if (level.getRandom().nextDouble() >= chance) continue;
                    }

                    BlockEntity be = (BlockEntity) entry.getValue();
                    if (!(be instanceof IMachineBlockEntity)) continue;
                    MetaMachine machine = ((IMachineBlockEntity) be).getMetaMachine();
                    tryDuplicateForMachine(machine, multiplier);
                }
            }
        }
    }

    private void tryDuplicateForMachine(MetaMachine machine, int multiplier) {
        try {
            Class<?> clazz = machine.getClass();
            java.lang.reflect.Field logicField = null;
            while (clazz != null && logicField == null) {
                try {
                    logicField = clazz.getDeclaredField("recipeLogic");
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            if (logicField == null) return;
            logicField.setAccessible(true);
            Object logic = logicField.get(machine);
            if (!(logic instanceof RecipeLogic rl)) return;

            var lastRecipeField = RecipeLogic.class.getDeclaredField("lastRecipe");
            lastRecipeField.setAccessible(true);
            GTRecipe lastRecipe = (GTRecipe) lastRecipeField.get(rl);
            if (lastRecipe == null) return;

            // 使用透镜倍率复制产出
            for (var capEntry : lastRecipe.outputs.entrySet()) {
                for (Content c : capEntry.getValue()) {
                    if (c.content instanceof ItemStack stack && !stack.isEmpty()) {
                        ItemStack copy = stack.copy();
                        copy.setCount(Math.min(copy.getCount() * multiplier / 100, Integer.MAX_VALUE / 2));
                        insertItem(machine, copy);
                    } else if (c.content instanceof FluidStack fluid && !fluid.isEmpty()) {
                        FluidStack fCopy = fluid.copy();
                        fCopy.setAmount(fCopy.getAmount() * multiplier / 100);
                        insertFluid(machine, fCopy);
                    } else if (c.content instanceof com.lowdragmc.lowdraglib.side.fluid.FluidStack ldFluid && !ldFluid.isEmpty()) {
                        var fCopy = ldFluid.copy();
                        fCopy.setAmount(fCopy.getAmount() * multiplier / 100);
                        // LDLib fluid cannot be inserted via Forge cap, skip fluid insert for now
                    }
                }
            }
        } catch (Exception ignored) {
            // 反射失败或机器不支持，忽略
        }
    }

    private static void insertItem(MetaMachine machine, ItemStack stack) {
        try {
            var holder = machine.getHolder();
            if (holder instanceof BlockEntity be) {
                var cap = be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER);
                var handler = cap.resolve().orElse(null);
                if (handler != null) {
                    ItemHandlerHelper.insertItem(handler, stack, false);
                    return;
                }
            }
        } catch (Exception ignored) { }
        if (machine.getLevel() != null) {
            Block.popResource(machine.getLevel(), machine.getPos(), stack);
        }
    }

    private static void insertFluid(MetaMachine machine, FluidStack stack) {
        try {
            var holder = machine.getHolder();
            if (holder instanceof BlockEntity be) {
                var cap = be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER);
                var handler = cap.resolve().orElse(null);
                if (handler != null) {
                    handler.fill(stack, IFluidHandler.FluidAction.EXECUTE);
                }
            }
        } catch (Exception ignored) { }
    }
}
