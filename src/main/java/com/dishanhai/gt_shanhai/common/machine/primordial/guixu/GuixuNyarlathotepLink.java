package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import com.dishanhai.gt_shanhai.common.machine.preview.ShanhaiNyarlathotepPreviewMachine;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** 山海：奈亞獨立的雙向跨維度綁定；地址解析一律不載入區塊。 */
public final class GuixuNyarlathotepLink {
    private GuixuNyarlathotepLink() {}

    @Nullable
    public static MetaMachine machineAt(@Nullable MinecraftServer server, String dimension, BlockPos pos) {
        if (server == null || dimension == null || dimension.isEmpty()) return null;
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        if (id == null) return null;
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null || !level.hasChunkAt(pos)) return null;
        return MetaMachine.getMachine(level, pos);
    }

    /** 每次授予增益都查實際端點，不信任客戶端的就緒同步位。 */
    @Nullable
    public static GuixuVisionCoreMachine resolveHost(ShanhaiNyarlathotepPreviewMachine target) {
        Level level = target.getLevel();
        if (level == null || level.isClientSide || !target.isNyarlathotepLinkLoaded()
                || target.isInValid() || !target.isFormed() || !target.hasGuixuLink()) return null;
        MinecraftServer server = level.getServer();
        if (machineAt(server, GuixuEngineLink.dimensionOf(level), target.getPos()) != target) return null;
        MetaMachine resolved = machineAt(server, target.getLinkedGuixuDim(), target.getLinkedGuixuPos());
        if (!(resolved instanceof GuixuVisionCoreMachine host) || host.isInValid()
                || !host.isNyarlathotepLinkLoaded() || !host.isFormed()) return null;
        return host.isLinkedToNyarlathotep(GuixuEngineLink.dimensionOf(level), target.getPos())
                && target.isLinkedToGuixu(GuixuEngineLink.dimensionOf(host.getLevel()), host.getPos()) ? host : null;
    }

    @Nullable
    public static ShanhaiNyarlathotepPreviewMachine resolveTarget(GuixuVisionCoreMachine host) {
        Level level = host.getLevel();
        if (level == null || level.isClientSide || !host.hasNyarlathotepLink()) return null;
        MetaMachine machine = machineAt(level.getServer(), host.getLinkedNyarlathotepDim(), host.getLinkedNyarlathotepPos());
        return machine instanceof ShanhaiNyarlathotepPreviewMachine target && resolveHost(target) == host ? target : null;
    }

    /** 服務端同一執行緒原子建立兩端地址；已有綁定必須先顯式解除。 */
    public static GuixuNyarlathotepBindPolicy.Result bind(GuixuVisionCoreMachine host, ShanhaiNyarlathotepPreviewMachine target) {
        Level hostLevel = host.getLevel(), targetLevel = target.getLevel();
        if (hostLevel == null || targetLevel == null || hostLevel.isClientSide || targetLevel.isClientSide
                || !host.isFormed() || !target.isFormed() || host.isInValid() || target.isInValid()
                || !host.isNyarlathotepLinkLoaded() || !target.isNyarlathotepLinkLoaded()
                || hostLevel.getServer() != targetLevel.getServer()
                || machineAt(hostLevel.getServer(), GuixuEngineLink.dimensionOf(hostLevel), host.getPos()) != host
                || machineAt(targetLevel.getServer(), GuixuEngineLink.dimensionOf(targetLevel), target.getPos()) != target) {
            return GuixuNyarlathotepBindPolicy.Result.UNAVAILABLE;
        }
        var hostAddress = new GuixuNyarlathotepBindPolicy.Address(GuixuEngineLink.dimensionOf(hostLevel), host.getPos().asLong());
        var targetAddress = new GuixuNyarlathotepBindPolicy.Address(GuixuEngineLink.dimensionOf(targetLevel), target.getPos().asLong());
        var hostBinding = host.hasNyarlathotepLink()
                ? new GuixuNyarlathotepBindPolicy.Address(host.getLinkedNyarlathotepDim(), host.getLinkedNyarlathotepPos().asLong()) : null;
        var targetBinding = target.hasGuixuLink()
                ? new GuixuNyarlathotepBindPolicy.Address(target.getLinkedGuixuDim(), target.getLinkedGuixuPos().asLong()) : null;
        var result = GuixuNyarlathotepBindPolicy.decide(true, hostBinding, targetAddress, targetBinding, hostAddress);
        if (result != GuixuNyarlathotepBindPolicy.Result.LINKED) return result;
        host.setNyarlathotepLink(GuixuEngineLink.dimensionOf(targetLevel), target.getPos());
        target.setGuixuLink(GuixuEngineLink.dimensionOf(hostLevel), host.getPos());
        reconcileHost(host);
        return result;
    }

    public static void reconcileHost(GuixuVisionCoreMachine host) {
        host.setNyarlathotepLinkReady(resolveTarget(host) != null);
    }

    /** 卸載／結構失效先撤銷有效性，地址留待載入後恢復。 */
    public static void pauseHost(GuixuVisionCoreMachine host) {
        host.setNyarlathotepLinkReady(false);
        detachLoadedTarget(host, false);
    }

    public static void unlinkHost(GuixuVisionCoreMachine host) {
        if (host.isRemote()) return;
        detachLoadedTarget(host, true);
        host.clearNyarlathotepLink();
    }

    public static void unlinkTarget(ShanhaiNyarlathotepPreviewMachine target) {
        Level level = target.getLevel();
        if (level == null || level.isClientSide) return;
        MetaMachine resolved = machineAt(level.getServer(), target.getLinkedGuixuDim(), target.getLinkedGuixuPos());
        if (resolved instanceof GuixuVisionCoreMachine host
                && host.isLinkedToNyarlathotep(GuixuEngineLink.dimensionOf(level), target.getPos())) {
            host.removeModule(target);
            host.clearNyarlathotepLink();
        }
        target.setHost(null);
        target.clearGuixuLink();
    }

    private static void detachLoadedTarget(GuixuVisionCoreMachine host, boolean clearAddress) {
        Level level = host.getLevel();
        if (level == null || level.isClientSide || !host.hasNyarlathotepLink()) return;
        MetaMachine resolved = machineAt(level.getServer(), host.getLinkedNyarlathotepDim(), host.getLinkedNyarlathotepPos());
        if (resolved instanceof ShanhaiNyarlathotepPreviewMachine target
                && target.isLinkedToGuixu(GuixuEngineLink.dimensionOf(level), host.getPos())) {
            target.setHost(null);
            host.removeModule(target);
            if (clearAddress) target.clearGuixuLink();
        }
    }
}
