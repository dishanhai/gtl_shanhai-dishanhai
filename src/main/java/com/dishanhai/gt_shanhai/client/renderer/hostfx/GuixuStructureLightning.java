package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiInstabilityGeometry;
import com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiInstabilityGeometry.Point;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuHostGeometry;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreStructure;
import com.gtladd.gtladditions.common.machine.multiblock.structure.StructureResourceLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/** 山海：正式結構表面樣本與客戶端實際方塊核對；只查至多 256 個節點，不掃描世界體積。 */
public final class GuixuStructureLightning {
    private GuixuStructureLightning() {}
    private static final ShanhaiInstabilityGeometry.Frame EMPTY = new ShanhaiInstabilityGeometry.Frame(List.of(), 0);
    private static GuixuAccretionLightning model;
    private static boolean loadAttempted;

    public static void reset() { model = null; loadAttempted = false; }

    private static GuixuAccretionLightning model() {
        if (!loadAttempted) {
            loadAttempted = true;
            try {
                var slices = StructureResourceLoader.INSTANCE.loadShapeInfoSlices(GuixuVisionCoreStructure.RESOURCE_PATH,
                        GuixuVisionCoreStructure.RESOURCE_ID, (col, aisle, row, symbol) -> symbol);
                model = GuixuAccretionLightning.fromSlices(slices, GuixuHostGeometry.TEMPLATE_MIN_X,
                        GuixuHostGeometry.TEMPLATE_SIZE_Y - 1 + GuixuHostGeometry.TEMPLATE_MIN_Y - HostFxGeometry.RING_Y,
                        GuixuHostGeometry.TEMPLATE_MIN_Z);
            } catch (RuntimeException error) {
                GTDishanhaiMod.LOGGER.error("[山海歸墟閃電] 結構表面採樣載入失敗", error);
            }
        }
        return model;
    }

    public static ShanhaiInstabilityGeometry.Frame frame(Level world, BlockPos controller, Direction facing,
                                                         double seconds, float spinSeconds) {
        if (world == null) return EMPTY;
        var mesh = model();
        if (mesh == null) return EMPTY;
        var present = new boolean[mesh.nodes().size()];
        var centers = new Point[present.length];
        var candidates = new HashMap<Character, Set<Block>>();
        var ledger = HostFxHide.ledger(controller);
        for (int i = 0; i < present.length; i++) {
            var node = mesh.nodes().get(i);
            BlockPos at = HostFxGeometry.worldFromGenerator(controller, facing,
                    node.x(), node.y() + HostFxGeometry.RING_Y, node.z());
            if (!world.hasChunkAt(at)) continue;
            var state = world.getBlockState(at);
            boolean hidden = state.isAir() && ledger != null && ledger.containsKey(at.asLong());
            if (hidden) state = ledger.get(at.asLong());
            if (!candidates.computeIfAbsent(node.symbol(), GuixuVisionCoreStructure::previewBlocks).contains(state.getBlock()))
                continue;
            present[i] = true;
            float x = node.x() + node.nx() * .55F, y = node.y() + node.ny() * .55F, z = node.z() + node.nz() * .55F;
            if (hidden) {
                float[] rotated = HostFxGeometry.rotateYDeg(HostFxGeometry.groupAngleDeg(spinSeconds,
                        HostFxGeometry.groupOf(node.y())), x, y, z);
                x = rotated[0]; y = rotated[1]; z = rotated[2];
            }
            centers[i] = new Point(x, y, z);
        }
        return mesh.frame(seconds, controller.asLong(), i -> present[i], i -> centers[i]);
    }
}
