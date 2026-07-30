package com.dishanhai.gt_shanhai.common.compat.eaep;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EaepProviderWarningMetadataSourceTest {

    private static final Path ROOT = Path.of("src", "main");
    private static final Path ACCESS = javaPath("common", "compat", "eaep",
            "EaepProviderRecipeTypesPacketAccess.java");
    private static final Path REQUEST = javaPath("mixin", "EaepRequestProvidersListRecipeTypesMixin.java");
    private static final Path PACKET = javaPath("mixin", "EaepProvidersListRecipeTypesMixin.java");
    private static final Path ACCESSOR = javaPath("mixin", "EaepProviderUploadPatternAccessor.java");
    private static final Path MIXIN_CONFIG = ROOT.resolve("resources").resolve("gt_shanhai.mixin.json");

    @Test
    void providerPacketCarriesKnownStellarFlagsAndExactUploadType() throws IOException {
        String access = Files.readString(ACCESS);
        String packet = Files.readString(PACKET);

        assertTrue(access.contains("gtShanhai$getStellarProviders"));
        assertTrue(access.contains("gtShanhai$getUploadRecipeTypeId"));
        assertTrue(access.contains("gtShanhai$isWarningMetadataKnown"));
        assertTrue(packet.contains("buf.writeBoolean(access.gtShanhai$isWarningMetadataKnown())"));
        assertTrue(packet.contains("buf.writeBoolean(Boolean.TRUE.equals(stellar))"));
        assertTrue(packet.contains("buf.writeUtf(access.gtShanhai$getUploadRecipeTypeId(), 128)"));
        assertTrue(packet.contains("if (buf.readableBytes() <= 0)"),
                "缺少山海尾部字段的旧封包必须保持可解码");
    }

    @Test
    void providerRequestReadsPendingPatternAndMarksStellarContainers() throws IOException {
        assertTrue(Files.exists(ACCESSOR));
        String accessor = Files.readString(ACCESSOR);
        String request = Files.readString(REQUEST);
        String config = Files.readString(MIXIN_CONFIG);

        assertTrue(accessor.contains("@Invoker(\"getPendingCtrlQPattern\")"));
        assertTrue(request.contains("PatternRecipeTypeHelper.readRecipeTypeId(uploadPattern)"));
        assertTrue(request.contains("container instanceof RecipeTypePatternBufferPartMachine"));
        assertTrue(request.contains("gtShanhai$setWarningMetadataKnown(true)"));
        assertTrue(config.contains("\"EaepProviderUploadPatternAccessor\""));
    }

    private static Path javaPath(String... parts) {
        Path path = ROOT.resolve("java").resolve("com").resolve("dishanhai").resolve("gt_shanhai");
        for (String part : parts) {
            path = path.resolve(part);
        }
        return path;
    }
}
