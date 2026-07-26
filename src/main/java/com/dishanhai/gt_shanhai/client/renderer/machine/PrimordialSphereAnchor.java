package com.dishanhai.gt_shanhai.client.renderer.machine;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import com.gtladd.gtladditions.utils.CommonUtils;

/**
 * 球体锚点：两种渲染风格必须落在同一个世界坐标上，否则切换时球心会跳。
 * 偏移量与轨道环（{@link AbstractRingRenderer#renderAllRings}）保持一致。
 */
final class PrimordialSphereAnchor {

    /** 基准朝向 EAST 下，球心相对控制器方块的 X 偏移。 */
    static final double OFFSET_X = -122.0D;

    private static final Direction BASE_DIRECTION = Direction.EAST;

    private PrimordialSphereAnchor() {}

    static Vec3 center(Direction facing) {
        return CommonUtils.INSTANCE.getRotatedRenderPosition(BASE_DIRECTION, facing, OFFSET_X, 0.0D, 0.0D);
    }
}
