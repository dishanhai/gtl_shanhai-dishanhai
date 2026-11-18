package com.dishanhai.gt_shanhai.common.saveedit;

/** Permission policy shared by save-editor commands. */
public final class ShanhaiSaveEditAccess {
    private ShanhaiSaveEditAccess() {}

    public static boolean allowed(boolean sourceHasPermission, boolean playerPresent,
                                  boolean playerHasPermission, boolean worldOwner, boolean dedicated) {
        return sourceHasPermission && playerPresent && playerHasPermission && worldOwner && !dedicated;
    }
}
