package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterWorld;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/** Radium 用区块扫描替代了 Level 的碰撞查询，仍须把动态装置交给原有的移动求解器。 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.lithium.common.entity.LithiumEntityCollisions", remap = false)
public abstract class StarLifterRadiumCollisionMixin {
    @Inject(method = "getBlockCollisions", at = @At("RETURN"), cancellable = true, require = 0)
    private static void shanhai$appendStarLifterShapes(Level level, Entity entity, AABB box,
                                                      CallbackInfoReturnable<List<VoxelShape>> cir) {
        var extra = StarLifterWorld.collisions(level, entity, box);
        if (!extra.isEmpty()) {
            var combined = new ArrayList<>(cir.getReturnValue());
            combined.addAll(extra);
            cir.setReturnValue(combined);
        }
    }

    @Inject(method = "doesBoxCollideWithBlocks", at = @At("RETURN"), cancellable = true, require = 0)
    private static void shanhai$testStarLifterShapes(Level level, Entity entity, AABB box,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && !StarLifterWorld.collisions(level, entity, box).isEmpty()) {
            cir.setReturnValue(true);
        }
    }
}
