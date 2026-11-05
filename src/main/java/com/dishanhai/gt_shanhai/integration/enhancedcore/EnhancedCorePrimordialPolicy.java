package com.dishanhai.gt_shanhai.integration.enhancedcore;

// Author: dishanhai
/** Pure ownership and capacity rules shared by the optional cloud adapter. */
public final class EnhancedCorePrimordialPolicy {
    public enum Owner { LOCAL, DRAIN_LOCAL, CLOUD }
    private EnhancedCorePrimordialPolicy() {}

    public static Owner owner(boolean cloud, long paidLocalBatches) {
        return !cloud ? Owner.LOCAL : paidLocalBatches > 0 ? Owner.DRAIN_LOCAL : Owner.CLOUD;
    }

}
