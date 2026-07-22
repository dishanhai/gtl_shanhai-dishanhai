package com.dishanhai.gt_shanhai.common.item.terminal;

import net.minecraft.core.BlockPos;

public final class ShanhaiStructureBuildHeightValidator {

    private static final int GTCEU_MAX_BUILD_HEIGHT = 320;

    public record Result(
            int minBuildHeight,
            int maxBuildHeight,
            int violationCount,
            BlockPos firstViolation,
            boolean upperLimitExceeded) {

        public boolean valid() {
            return violationCount == 0;
        }

        public int maxBuildY() {
            return maxBuildHeight - 1;
        }
    }

    private ShanhaiStructureBuildHeightValidator() {}

    public static Result validateForGtceu(ShanhaiStructurePlan plan, int minBuildHeight,
                                          int worldMaxBuildHeight) {
        return validatePositions(plan.entries().stream()
                .filter(entry -> !entry.candidates().isEmpty())
                .map(ShanhaiStructurePlan.Entry::pos).toList(), minBuildHeight,
                effectiveMaxBuildHeight(worldMaxBuildHeight));
    }

    static int effectiveMaxBuildHeight(int worldMaxBuildHeight) {
        return Math.min(worldMaxBuildHeight, GTCEU_MAX_BUILD_HEIGHT);
    }

    static Result validatePositions(Iterable<BlockPos> positions,
                                    int minBuildHeight, int maxBuildHeight) {
        if (maxBuildHeight <= minBuildHeight) {
            throw new IllegalArgumentException("无效的世界建造高度范围");
        }

        int violationCount = 0;
        BlockPos firstViolation = null;
        boolean upperLimitExceeded = false;
        for (BlockPos pos : positions) {
            int y = pos.getY();
            if (y < minBuildHeight || y >= maxBuildHeight) {
                violationCount++;
                if (firstViolation == null) firstViolation = pos;
                if (y >= maxBuildHeight) upperLimitExceeded = true;
            }
        }
        return new Result(minBuildHeight, maxBuildHeight, violationCount,
                firstViolation, upperLimitExceeded);
    }
}
