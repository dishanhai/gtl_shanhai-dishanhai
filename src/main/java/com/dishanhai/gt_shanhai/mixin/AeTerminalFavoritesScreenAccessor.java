package com.dishanhai.gt_shanhai.mixin;

import appeng.client.gui.me.common.MEStorageScreen;
import appeng.client.gui.me.common.Repo;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = MEStorageScreen.class, remap = false)
public interface AeTerminalFavoritesScreenAccessor {

    @Accessor("repo")
    Repo gtShanhai$getRepo();
}
