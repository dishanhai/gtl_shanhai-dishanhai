package com.dishanhai.gt_shanhai.common.machine.preview;

import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuEngineLink;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuNyarlathotepLink;
import org.jetbrains.annotations.Nullable;

/** 山海：回響單側保存奈亞地址；既有載入狀態及實例身分失效時只降級，不強載區塊。 */
public final class ShanhaiNyarlathotepEchoLink {
    private ShanhaiNyarlathotepEchoLink() {}

    @Nullable public static ShanhaiNyarlathotepPreviewMachine resolveTarget(ShanhaiNyarlathotepEchoMachine echo) {
        var level = echo.getLevel();
        if (level == null || level.isClientSide || !echo.isEchoLoaded() || echo.isInValid() || !echo.isFormed()
                || !echo.hasNyarlathotepLink()) return null;
        if (GuixuNyarlathotepLink.machineAt(level.getServer(), GuixuEngineLink.dimensionOf(level), echo.getPos()) != echo)
            return null;
        var target = GuixuNyarlathotepLink.machineAt(level.getServer(), echo.getLinkedNyarlathotepDim(), echo.getLinkedNyarlathotepPos());
        return target instanceof ShanhaiNyarlathotepPreviewMachine nyarlathotep && !nyarlathotep.isInValid()
                && nyarlathotep.isFormed() && nyarlathotep.isNyarlathotepLinkLoaded() ? nyarlathotep : null;
    }
}
