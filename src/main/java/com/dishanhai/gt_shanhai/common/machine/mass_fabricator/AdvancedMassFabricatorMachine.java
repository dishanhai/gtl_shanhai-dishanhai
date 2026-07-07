package com.dishanhai.gt_shanhai.common.machine.mass_fabricator;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import net.minecraft.nbt.CompoundTag;

/**
 * 进阶质量发生器 - 移植自 GTNL (GTNH附属模组)
 * 
 * 核心特性（简化版）：
 * 1. 基础结构：5x5x5 多方块
 * 2. 配方类型：质量发生器配方
 * 3. 并行倍增：16x（待实现）
 * 4. 加速/能耗优化：待实现
 * 
 * TODO:
 * - 实现并行控制核心仓
 * - 实现配方修饰符（加速 + 能耗折扣）
 * - 实现无线模式
 */
public class AdvancedMassFabricatorMachine extends WorkableElectricMultiblockMachine {
    
    // 并行控制核心提供的并行等级（mParallelTier）
    private int parallelTier = 0;
    
    // 无线升级状态
    private boolean wirelessUpgrade = false;
    
    // 无线模式状态（只有安装无线升级后才能启用）
    private boolean wirelessMode = false;
    
    public AdvancedMassFabricatorMachine(IMachineBlockEntity holder) {
        super(holder);
    }
    
    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        
        // 重置并行等级
        resetParallelTier();
        
        // 如果没有能源仓且安装了无线升级，自动启用无线模式
        // TODO: 实现能源仓检测
        wirelessMode = false;
    }
    
    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        wirelessMode = false;
    }
    
    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        tag.putInt("parallelTier", parallelTier);
        tag.putBoolean("wirelessUpgrade", wirelessUpgrade);
        tag.putBoolean("wirelessMode", wirelessMode);
    }
    
    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        parallelTier = tag.getInt("parallelTier");
        wirelessUpgrade = tag.getBoolean("wirelessUpgrade");
        wirelessMode = tag.getBoolean("wirelessMode");
    }
    
    /**
     * 重置并行等级
     * 从并行控制核心仓读取等级
     */
    private void resetParallelTier() {
        // TODO: 从自定义 ParallelControllerHatch 读取等级
        // 暂时硬编码为 0（后续实现自定义 Hatch）
        parallelTier = 0;
    }
    
    /**
     * 获取能耗折扣
     * 公式：(wirelessUpgrade ? 0.4 : 0.6) - (tier / 50.0)
     */
    public double getEUtDiscount() {
        return (wirelessUpgrade ? 0.4 : 0.6) - (parallelTier / 50.0);
    }
    
    /**
     * 获取时间倍率
     * 公式：(1 / (wirelessUpgrade ? 10 : 5)) * 0.75^tier
     */
    public double getDurationModifier() {
        return (1.0 / (wirelessUpgrade ? 10.0 : 5.0)) * Math.pow(0.75, parallelTier);
    }
    
    // ===== Getter/Setter =====
    
    public int getParallelTier() {
        return parallelTier;
    }
    
    public void setParallelTier(int tier) {
        this.parallelTier = tier;
    }
    
    public boolean isWirelessUpgrade() {
        return wirelessUpgrade;
    }
    
    public void setWirelessUpgrade(boolean wirelessUpgrade) {
        this.wirelessUpgrade = wirelessUpgrade;
    }
    
    public boolean isWirelessMode() {
        return wirelessMode;
    }
    
    public void setWirelessMode(boolean wirelessMode) {
        if (wirelessUpgrade) {
            this.wirelessMode = wirelessMode;
        } else {
            this.wirelessMode = false;
        }
    }
}
