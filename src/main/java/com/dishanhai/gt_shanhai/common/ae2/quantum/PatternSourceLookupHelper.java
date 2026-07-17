package com.dishanhai.gt_shanhai.common.ae2.quantum;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.menu.me.crafting.CraftingStatusMenu;

import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.dishanhai.gt_shanhai.mixin.CraftingStatusMenuAccessor;
import com.dishanhai.gt_shanhai.network.PatternSourceResponsePacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;

import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.PacketDistributor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * "合成状态"详情页物品溯源：给一批 AEKey，找出玩家当前打开的合成状态菜单绑定的量子CPU
 * 所在网络内，哪些样板总成的样板输出里含有它，回传坐标 + 配方类型。
 * 扫描目标是 {@link MEPatternBufferPartMachineBase}（GTLCore 全系样板总成基类），而不只是星律
 * （{@code RecipeTypePatternBufferPartMachine}）——网络里还可能有 GTLCore 库存/普通样板总成、
 * 通配符样板总成（{@code MEWildcardPatternBufferPartMachine}）、gtladditions 超级样板总成
 * （{@code MESuperPatternBufferPartMachine}）等同族兄弟类，只扫星律会漏掉挂在这些总成上的样板，
 * 导致"有的配方没法被反射"。
 * 只读查询，不改任何机器/样板状态；每次调用只读 {@link MEPatternBufferPartMachineBase#getAvailablePatterns()}
 * （星律实例的重写会自动带上通配符展开），复用星律自身"AE2身份只读"纪律同款的 {@link PatternRecipeTypeHelper#peekRecipeTypeId}。
 */
public final class PatternSourceLookupHelper {

    private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai:pattern_source");

    private PatternSourceLookupHelper() {
    }

    public static void resolveAndReply(ServerPlayer player, List<AEKey> keys) {
        long queryStart = System.nanoTime();
        if (!(player.containerMenu instanceof CraftingStatusMenu statusMenu)) return;
        ICraftingCPU cpu = ((CraftingStatusMenuAccessor) statusMenu).gtShanhai$getSelectedCpuRaw();
        if (!(cpu instanceof QuantumCraftingCPU quantumCpu)) return;
        IGrid grid = quantumCpu.getGrid();
        Level level = quantumCpu.getLevel();
        if (grid == null || level == null) return;

        List<AEKey> foundKeys = new ArrayList<>();
        List<BlockPos> foundPositions = new ArrayList<>();
        List<String> foundTypes = new ArrayList<>();

        long bufferStart = System.nanoTime();
        Set<MEPatternBufferPartMachineBase> buffers = grid.getMachines(MEPatternBufferPartMachineBase.class);
        long bufferElapsed = System.nanoTime() - bufferStart;
        LOG.info("[PatternSource] request={} buffersFound={} discoverMs={}",
                keys.size(), buffers.size(), bufferElapsed / 1_000_000.0);

        for (MEPatternBufferPartMachineBase buffer : buffers) {
            long bufPatStart = System.nanoTime();
            var patterns = buffer.getAvailablePatterns();
            long bufPatElapsed = System.nanoTime() - bufPatStart;
            LOG.debug("[PatternSource] scan pos={} class={} patterns={} fetchMs={}",
                    buffer.getPos(), buffer.getClass().getSimpleName(), patterns.size(), bufPatElapsed / 1_000_000.0);
            for (IPatternDetails pattern : patterns) {
                GenericStack[] outputs = pattern.getOutputs();
                if (outputs == null) continue;
                for (GenericStack output : outputs) {
                    if (output == null || output.what() == null) continue;
                    AEKey what = output.what();
                    if (foundKeys.contains(what) || !keys.contains(what)) continue;
                    ItemStack patternStack = pattern.getDefinition().toStack();
                    String typeId = PatternRecipeTypeHelper.peekRecipeTypeId(patternStack, level);
                    if (typeId.isEmpty()) continue;
                    LOG.info("[PatternSource] match what={} pos={} type={}", what, buffer.getPos(), typeId);
                    foundKeys.add(what);
                    foundPositions.add(buffer.getPos());
                    foundTypes.add(typeId);
                }
            }
            if (foundKeys.size() >= keys.size()) break;
        }

        if (foundKeys.isEmpty()) {
            LOG.warn("[PatternSource] no match found");
            return;
        }
        long totalElapsed = System.nanoTime() - queryStart;
        LOG.info("[PatternSource] done totalMs={} replied={}", totalElapsed / 1_000_000.0, foundKeys.size());
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PatternSourceResponsePacket(foundKeys, foundPositions, foundTypes));
    }
}
