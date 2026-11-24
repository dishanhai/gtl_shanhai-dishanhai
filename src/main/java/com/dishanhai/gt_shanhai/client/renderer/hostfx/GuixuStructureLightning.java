package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiInstabilityGeometry;
import com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiInstabilityGeometry.Link;
import com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiInstabilityGeometry.Node;
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
        var surface = surface(world, controller, facing, spinSeconds);
        return surface == null ? EMPTY : model().frame(seconds, controller.asLong(),
                i -> surface.centers()[i] != null, i -> surface.centers()[i]);
    }

    /** 共用成型结构的可见表面，包含藏块后按当前组角旋转的位置。 */
    static Surface surface(Level world, BlockPos controller, Direction facing, float spinSeconds) {
        if (world == null) return null;
        var mesh = model();
        if (mesh == null) return null;
        var centers = new Point[mesh.nodes().size()];
        var candidates = new HashMap<Character, Set<Block>>();
        var ledger = HostFxHide.ledger(controller);
        for (int i = 0; i < centers.length; i++) {
            var node = mesh.nodes().get(i);
            BlockPos at = HostFxGeometry.worldFromGenerator(controller, facing,
                    node.x(), node.y() + HostFxGeometry.RING_Y, node.z());
            if (!world.hasChunkAt(at)) continue;
            var state = world.getBlockState(at);
            boolean hidden = state.isAir() && ledger != null && ledger.containsKey(at.asLong());
            if (hidden) state = ledger.get(at.asLong());
            if (!candidates.computeIfAbsent(node.symbol(), GuixuVisionCoreStructure::previewBlocks).contains(state.getBlock()))
                continue;
            float x = node.x() + node.nx() * .55F, y = node.y() + node.ny() * .55F, z = node.z() + node.nz() * .55F;
            if (hidden) {
                float[] rotated = HostFxGeometry.rotateYDeg(HostFxGeometry.groupAngleDeg(spinSeconds,
                        HostFxGeometry.groupOf(node.y())), x, y, z);
                x = rotated[0]; y = rotated[1]; z = rotated[2];
            }
            centers[i] = new Point(x, y, z);
        }
        return new Surface(centers, mesh.nodes(), mesh.links());
    }

    record Surface(Point[] centers, List<Node> nodes, List<Link> links) {}
}
