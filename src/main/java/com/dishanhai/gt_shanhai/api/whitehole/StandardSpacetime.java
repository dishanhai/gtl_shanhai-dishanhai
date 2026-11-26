package com.dishanhai.gt_shanhai.api.whitehole;

import static com.dishanhai.gt_shanhai.api.whitehole.HorizonModel.Kind.LOCAL_TRAPPING_SCALE;
import static com.dishanhai.gt_shanhai.api.whitehole.HorizonModel.Kind.PAST_EVENT_HORIZON;

public enum StandardSpacetime implements SpacetimeModel {
    SCHWARZSCHILD,
    KERR,
    OUTGOING_VAIDYA,
    REISSNER_NORDSTROM,
    /** Stationary Kerr exterior paired with a speculative transition profile. */
    QUANTUM_BOUNCE;

    public static final double SCHWARZSCHILD_CRITICAL_IMPACT_RG = 3 * Math.sqrt(3);

    @Override
    public Geometry geometry(double spin, double charge) {
        return switch (this) {
            case SCHWARZSCHILD, OUTGOING_VAIDYA -> {
                if (spin != 0 || charge != 0) throw new IllegalArgumentException("spherical neutral model");
                yield new Geometry(new HorizonModel(2,
                        this == OUTGOING_VAIDYA ? LOCAL_TRAPPING_SCALE : PAST_EVENT_HORIZON), 3, 3);
            }
            case KERR, QUANTUM_BOUNCE -> {
                if (charge != 0) throw new IllegalArgumentException("Kerr exterior is neutral");
                yield new Geometry(new HorizonModel(SpacetimeModel.kerrOuterHorizon(spin), PAST_EVENT_HORIZON),
                        SpacetimeModel.kerrEquatorialPhotonOrbit(spin, 1),
                        SpacetimeModel.kerrEquatorialPhotonOrbit(spin, -1));
            }
            case REISSNER_NORDSTROM -> {
                if (spin != 0 || !(Math.abs(charge) <= 1)) {
                    throw new IllegalArgumentException("Reissner–Nordström requires zero spin and |q*| ≤ 1");
                }
                double q = Math.abs(charge);
                double outer = 1 + Math.sqrt((1 - q) * (1 + q));
                double photon = .5 * (3 + Math.sqrt(9 - 8 * q * q));
                yield new Geometry(new HorizonModel(outer, PAST_EVENT_HORIZON), photon, photon);
            }
        };
    }

    /** Outgoing Vaidya g_uu with Bondi mass M(u), in fixed units GM_ref/c² and GM_ref/c³.
     * The remaining radial coefficients are g_ur = -1 and g_rr = 0. */
    public static double outgoingVaidyaGuu(double radiusReferenceRg, double bondiMassOverReferenceMass) {
        if (!(radiusReferenceRg > 0) || !Double.isFinite(radiusReferenceRg)
                || !(bondiMassOverReferenceMass > 0 && bondiMassOverReferenceMass <= 1)) {
            throw new IllegalArgumentException("invalid Vaidya radius or Bondi mass ratio");
        }
        return 2 * bondiMassOverReferenceMass / radiusReferenceRg - 1;
    }
}
