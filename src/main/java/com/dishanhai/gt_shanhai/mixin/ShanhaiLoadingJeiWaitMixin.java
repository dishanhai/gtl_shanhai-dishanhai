package com.dishanhai.gt_shanhai.mixin;

import org.spongepowered.asm.mixin.Mixin;

/** 山海：可選 Optimizer 等待接入標記；精確修改由 postApply 在 handler 合併後執行。 */
@Mixin(targets = {"mezz.jei.gui.search.ElementSearch", "mezz.jei.library.load.PluginCaller"}, remap = false)
public abstract class ShanhaiLoadingJeiWaitMixin {}
