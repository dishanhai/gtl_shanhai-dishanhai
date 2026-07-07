package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@link MEPatternBufferPartMachine} 的 private 字段 {@code slot2PatternMap}（slot → 样板身份）。
 * 供 {@code PatternIdentityGuardMixin} 在选槽阶段读取某槽当前样板，做「样板身份驱动」的防串配方校验。
 */
@Mixin(value = MEPatternBufferPartMachine.class, remap = false)
public interface MEPatternBufferSlot2PatternAccessor {

    @Accessor("slot2PatternMap")
    Int2ObjectMap<IPatternDetails> gtShanhai$getSlot2PatternMap();
}
