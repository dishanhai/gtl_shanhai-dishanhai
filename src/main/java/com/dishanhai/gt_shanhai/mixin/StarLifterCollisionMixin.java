package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterWorld;
import com.google.common.collect.Iterables;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Level.class)
public abstract class StarLifterCollisionMixin implements CollisionGetter {
    @Override
    public Iterable<VoxelShape> getBlockCollisions(Entity entity, AABB query) {
        Level level = (Level) (Object) this;
        // 使用 CollisionGetter 默认实现同款的原版迭代器，避免 Mixin 重写接口 super 调用。
        Iterable<VoxelShape> vanilla = () -> new BlockCollisions<>(level, entity, query, false, (pos, shape) -> shape);
        var extra = StarLifterWorld.collisions(level, entity, query);
        return extra.isEmpty() ? vanilla : Iterables.concat(vanilla, extra);
    }
}
