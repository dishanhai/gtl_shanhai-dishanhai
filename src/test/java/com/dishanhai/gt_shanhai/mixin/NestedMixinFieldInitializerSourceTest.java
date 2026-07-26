package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：嵌套 {@code @Mixin} 类里不得给实例字段写初始化器。
 *
 * <p>背景（ERR-20260726-001）：Mixin 对 {@code @Unique} 实例字段初始化器的「合并进目标类构造器」
 * 是尽力而为的，失败时只记日志不报错，字段会停在 JVM 默认值 {@code null}。
 * 本项目 15 处带初始化器的 mixin 字段里，14 个 top-level mixin 都正常合并，
 * 唯独嵌套 mixin {@code CraftingPlanVirtualMarkerMixins.Entry} 没合并——
 * 合成计划同步时 {@code gtShanhai$recursionKind.ordinal()} 直接 NPE 崩服。
 *
 * <p>所以约定收在嵌套 mixin 上：字段不写初值，改为在 getter / 使用点做 null 兜底。
 * {@code static} 字段走 {@code <clinit>}，不受影响，不在管辖范围。
 */
class NestedMixinFieldInitializerSourceTest {

    private static final Path MIXIN_DIR = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin");

    /** 缩进过的 {@code @Mixin} 才是嵌套 mixin；顶层 mixin 的注解顶格写。 */
    private static final Pattern NESTED_MIXIN_ANNOTATION = Pattern.compile("(?m)^[ \\t]+@Mixin\\b");

    /**
     * 带初始化器的实例字段。判别依据：Java 局部变量不能带访问修饰符，
     * 所以「以 private/protected/public 开头 + 等号前没有左括号」必是字段而非局部变量或方法。
     */
    private static final Pattern INSTANCE_FIELD_INITIALIZER =
            Pattern.compile("(?m)^[ \\t]+(?:private|protected|public)\\s+(?!.*\\bstatic\\b)[^(=;\\n]+=");

    @Test
    void nestedMixinsDeclareNoInstanceFieldInitializers() throws IOException {
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.walk(MIXIN_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (!NESTED_MIXIN_ANNOTATION.matcher(source).find()) continue;

                Matcher matcher = INSTANCE_FIELD_INITIALIZER.matcher(source);
                while (matcher.find()) {
                    offenders.add(file.getFileName() + " → " + matcher.group().trim());
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "嵌套 @Mixin 类的实例字段初始化器不保证被 Mixin 合并进目标构造器，字段会停在 null。"
                        + "请去掉初值并在 getter / 使用点做兜底（详见 ERR-20260726-001）：" + offenders);
    }

    /** 自检：证明上面的检测器确实抓得住这次真实回归，避免守卫「永远绿」。 */
    @Test
    void detectorCatchesTheKnownRegression() {
        String regressed = """
                public final class CraftingPlanVirtualMarkerMixins {

                    private static final int MAX_SYNCED_PATH = 6;

                    @Mixin(value = CraftingPlanSummaryEntry.class, remap = false)
                    public static class Entry implements CraftingPlanVirtualMarkerAccess {

                        @Unique
                        private boolean gtShanhai$virtualPresence;

                        @Unique
                        private CraftingRecursionDetector.Kind gtShanhai$recursionKind = \
                CraftingRecursionDetector.Kind.NONE;

                        private void write(FriendlyByteBuf buffer) {
                            int count = Math.min(MAX_SYNCED_PATH, 1);
                        }
                    }
                }
                """;

        assertTrue(NESTED_MIXIN_ANNOTATION.matcher(regressed).find(), "应识别出这是嵌套 mixin 文件");
        assertTrue(INSTANCE_FIELD_INITIALIZER.matcher(regressed).find(),
                "应抓到 gtShanhai$recursionKind 的初始化器");
        assertFalse(INSTANCE_FIELD_INITIALIZER.matcher(regressed).results()
                .anyMatch(r -> r.group().contains("MAX_SYNCED_PATH")),
                "static 字段走 <clinit>，不该被误报");
        assertFalse(INSTANCE_FIELD_INITIALIZER.matcher(regressed).results()
                .anyMatch(r -> r.group().contains("count")),
                "方法体内的局部变量不该被误报");
    }

    /** 真实修复必须留在原地：读取点得有 null 兜底，而不是靠字段初值。 */
    @Test
    void craftingPlanEntryFallsBackToNoneWhenFieldsStayNull() throws IOException {
        String source = Files.readString(MIXIN_DIR.resolve("CraftingPlanVirtualMarkerMixins.java"));

        assertTrue(source.contains(
                "return gtShanhai$recursionKind == null ? CraftingRecursionDetector.Kind.NONE : gtShanhai$recursionKind;"),
                "getRecursionKind 必须把 null 兜成 NONE");
        assertTrue(source.contains("return gtShanhai$recursionPath == null ? List.of() : gtShanhai$recursionPath;"),
                "getRecursionPath 必须把 null 兜成空列表");
        assertTrue(source.contains("buffer.writeVarInt(gtShanhai$getRecursionKind().ordinal());"),
                "write 必须走 getter 取值，直接读字段会在未赋值条目上 NPE");
        assertFalse(source.contains("gtShanhai$recursionPath.size()"),
                "write 不得直接对字段调 size()，同样会 NPE");
    }
}
