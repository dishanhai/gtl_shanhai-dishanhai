package com.dishanhai.gt_shanhai.client.loading;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：加载界面与 JEI 进度不得绕过语言资源。 */
class ShanhaiLoadingLocaleWiringTest {
    @Test void loadingUiAndJeiCheckpointsHaveNoHardcodedChineseStrings() throws Exception {
        Pattern literal = Pattern.compile("\"[^\"\\n]*[\\p{IsHan}][^\"\\n]*\"");
        Path root = Path.of("src/main/java/com/dishanhai/gt_shanhai");
        for (String name : new String[]{"client/loading/ShanhaiLoadingClient", "client/loading/ShanhaiLoadingRenderer",
                "mixin/ShanhaiLoadingJeiSearchStorageMixin", "mixin/ShanhaiLoadingJeiSearchMixin",
                "mixin/ShanhaiLoadingJeiRecipesMixin", "mixin/ShanhaiLoadingJeiPluginsMixin", "mixin/ShanhaiLoadingJeiIndexMixin"}) {
            String source = Files.readString(root.resolve(name + ".java"));
            source = source.replaceAll("(?m)^\\s*//.*$", "").replaceAll("(?m)^.*LogManager.*$", "");
            assertFalse(literal.matcher(source).find(), name);
        }
    }
}
