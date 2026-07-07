package com.dishanhai.gt_shanhai.common.machine.spacetime;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.pattern.MultiblockWorldSavedData;
import net.minecraft.server.level.ServerLevel;
import com.gtladd.gtladditions.api.machine.IThreadModifierMachine;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;
import com.gtladd.gtladditions.common.machine.multiblock.structure.RingStructure;
import com.google.common.primitives.Ints;
import com.gtladd.gtladditions.utils.StarGradient;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SpacetimeWaveMatrixMachine extends GTLAddWirelessWorkableElectricMultipleRecipesMachine implements IThreadModifierMachine {

    private static final long MAX_PARALLEL = 9223372036854775807L;

    private long runningSecs;
    private TickableSubscription runningSecSubs;
    private double idleDecayAccum;

    public SpacetimeWaveMatrixMachine(IMachineBlockEntity holder) {
        super(holder);
    }

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean saveConfig) {
        super.saveCustomPersistedData(tag, saveConfig);
        tag.putLong("runningSecs", runningSecs);
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (tag.contains("runningSecs")) {
            runningSecs = tag.getLong("runningSecs");
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (runningSecs > 0) {
            updateRunningSecSubscription();
        }
    }

    @Override
    public void asyncCheckPattern(long period) {
        // 同步结构检测，避免 29×29×29 巨构导致 async thread 与服务端线程的 patternLock 死锁
        if (!getMultiblockState().hasError() && isFormed()) return;
        if ((getHolder().getOffset() + period) % 4 != 0) return;
        if (checkPattern() && getLevel() instanceof ServerLevel serverLevel) {
            setFlipped(getMultiblockState().isNeededFlip());
            onStructureFormed();
            MultiblockWorldSavedData.getOrCreate(serverLevel).addMapping(getMultiblockState());
            MultiblockWorldSavedData.getOrCreate(serverLevel).removeAsyncLogic(this);
        }
    }

    @Override
    public SpacetimeWaveMatrixRecipeLogic createRecipeLogic(Object... args) {
        return new SpacetimeWaveMatrixRecipeLogic(this);
    }

    @Override
    public int getMaxParallel() {
        return Ints.saturatedCast(MAX_PARALLEL);
    }

    @Override
    public int getAdditionalThread() {
        return 2147483647;
    }

    @Override
    protected void addParallelDisplay(List<Component> textList) {
        var inf = DShanhaiTextUtil.createUltimateRainbow("无限");
        textList.add(Component.literal("")
                .append(Component.literal("同时处理至多"))
                .append(inf)
                .append(Component.literal("个配方")));
        textList.add(Component.translatable("gtladditions.multiblock.threads", inf));
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        super.addDisplayText(textList);
        if (!isFormed()) return;
        textList.add(Component.literal("终焉创始现实修改矩阵")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        textList.add(Component.literal("运行时间: " + formatRunningTime(runningSecs / 20))
                .withStyle(ChatFormatting.AQUA));
    }

    @Override
    public boolean onWorking() {
        if (runningSecs == 0) {
            runningSecs = 1;
            updateRunningSecSubscription();
        }
        return super.onWorking();
    }

    public long getRunningSecs() {
        return runningSecs;
    }

    public int getRgbFromTime() {
        double t = Math.max(0, Math.min(1, 1 - Math.exp(-runningSecs / 14400.0)));
        return StarGradient.INSTANCE.getRGBFromTime(t);
    }

    public float getRadiusMultiplier() {
        double progress = 1.0 - Math.exp(-runningSecs / 14400.0);
        return (float) (1.0 + 1.7 * progress);
    }

    private void updateRunningSecSubscription() {
        if (runningSecs > 0) {
            runningSecSubs = subscribeServerTick(runningSecSubs, this::tickRunningSecs);
        } else if (runningSecSubs != null) {
            runningSecSubs.unsubscribe();
            runningSecSubs = null;
        }
    }

    private static String formatRunningTime(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    private void tickRunningSecs() {
        if (recipeLogic.isWorking()) {
            runningSecs = Math.max(0, runningSecs + 1);
            idleDecayAccum = 0;
        } else {
            // 递减系统：越低温度衰减越快，越高衰减越慢
            // 最高衰减 = 当前值的 30%/分钟，随 fullness 升高线性递减至约 3%/分钟
            double fullness = Math.min(1.0, (double) runningSecs / 14400.0);
            double ratePerMinute = 0.30 * (1.0 - 0.9 * fullness); // 30% → 3%
            idleDecayAccum += runningSecs * ratePerMinute / 1200.0;
            if (idleDecayAccum >= 1.0) {
                long decay = (long) idleDecayAccum;
                runningSecs = Math.max(0, runningSecs - decay);
                idleDecayAccum -= decay;
            }
        }
        updateRunningSecSubscription();
    }
}
