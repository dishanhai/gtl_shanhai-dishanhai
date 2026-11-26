package com.dishanhai.gt_shanhai.api.whitehole;

/** Past-horizon orientation, independent of the emitting plasma distribution. */
public record HorizonModel(double radiusRg, Kind kind) {
    public enum Kind {
        PAST_EVENT_HORIZON,
        LOCAL_TRAPPING_SCALE
    }

    public enum Crossing {
        INTERIOR_TO_EXTERIOR,
        EXTERIOR_TO_INTERIOR
    }

    public HorizonModel {
        if (!(radiusRg > 0) || !Double.isFinite(radiusRg) || kind == null) {
            throw new IllegalArgumentException("invalid horizon radius or kind");
        }
    }

    /** Tests causal orientation at a stationary past horizon, not at an arbitrary exterior radius. */
    public boolean allowsFutureCrossing(Crossing crossing) {
        if (kind != Kind.PAST_EVENT_HORIZON) {
            throw new IllegalStateException("a local trapping scale does not specify a global event horizon");
        }
        return switch (crossing) {
            case INTERIOR_TO_EXTERIOR -> true;
            case EXTERIOR_TO_INTERIOR -> false;
        };
    }
}
