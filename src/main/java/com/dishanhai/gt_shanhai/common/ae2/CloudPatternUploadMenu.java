package com.dishanhai.gt_shanhai.common.ae2;

/** An upload request sent through the open AE2 pattern encoding menu. */
public interface CloudPatternUploadMenu {

    /** An empty recipe ID uploads from the terminal rather than encoding a JEI recipe. */
    void gtShanhai$requestCloudUpload(String recipeId);

    record Request(long sequence, String recipeId, int expectedOutput) {}
}
