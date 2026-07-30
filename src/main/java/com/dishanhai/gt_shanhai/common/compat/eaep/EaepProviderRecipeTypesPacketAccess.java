package com.dishanhai.gt_shanhai.common.compat.eaep;

import java.util.List;

public interface EaepProviderRecipeTypesPacketAccess {

    List<List<String>> gtShanhai$getProviderRecipeTypeIds();

    void gtShanhai$setProviderRecipeTypeIds(List<List<String>> providerRecipeTypeIds);

    List<Boolean> gtShanhai$getStellarProviders();

    void gtShanhai$setStellarProviders(List<Boolean> stellarProviders);

    String gtShanhai$getUploadRecipeTypeId();

    void gtShanhai$setUploadRecipeTypeId(String uploadRecipeTypeId);

    boolean gtShanhai$isWarningMetadataKnown();

    void gtShanhai$setWarningMetadataKnown(boolean warningMetadataKnown);
}
