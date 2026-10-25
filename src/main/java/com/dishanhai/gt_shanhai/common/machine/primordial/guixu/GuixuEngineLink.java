package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineMachine;
import com.gregtechceu.gtceu.api.machine.MetaMachine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

/**
 * 歸墟超維度鏈接：把「維度 + 座標」解析成一台<b>已載入、已成型</b>的原始終焉引擎。
 *
 * <p>三態不能混成一個 null：
 * <ul>
 *   <li>{@link State#UNLOADED}：維度或區塊沒載入 ⇒ 什麼都不動（不強制載入區塊，避免卡服）；</li>
 *   <li>{@link State#MISSING}：區塊已載入但那裡沒有（成型的）原始終焉引擎 ⇒ 歸墟該把接管的模組放回去；</li>
 *   <li>{@link State#READY}：可以接管。</li>
 * </ul>
 * 目標必須是<b>純</b>原始終焉引擎：{@link GuixuVisionCoreMachine} 也繼承它，但不能被另一台歸墟接管。
 */
public final class GuixuEngineLink {

    public enum State { UNLOADED, MISSING, READY }

    public record Resolved(State state, @Nullable PrimordialOmegaEngineMachine engine) {
        static final Resolved UNLOADED = new Resolved(State.UNLOADED, null);
        static final Resolved MISSING = new Resolved(State.MISSING, null);
    }

    private GuixuEngineLink() {}

    /** 目標機器是否是可被接管的「純」原始終焉引擎。 */
    public static boolean isLinkableEngine(@Nullable MetaMachine machine) {
        return machine instanceof PrimordialOmegaEngineMachine && !(machine instanceof GuixuVisionCoreMachine);
    }

    public static Resolved resolve(@Nullable MinecraftServer server, String dimension, long packedPos) {
        if (server == null || dimension == null || dimension.isEmpty()) return Resolved.MISSING;
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        if (id == null) return Resolved.MISSING;
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null) return Resolved.UNLOADED;
        BlockPos pos = BlockPos.of(packedPos);
        if (!level.hasChunkAt(pos)) return Resolved.UNLOADED;
        MetaMachine machine = MetaMachine.getMachine(level, pos);
        if (isLinkableEngine(machine) && ((PrimordialOmegaEngineMachine) machine).isFormed()) {
            return new Resolved(State.READY, (PrimordialOmegaEngineMachine) machine);
        }
        return Resolved.MISSING;
    }

    public static String dimensionOf(Level level) {
        return level.dimension().location().toString();
    }
}
