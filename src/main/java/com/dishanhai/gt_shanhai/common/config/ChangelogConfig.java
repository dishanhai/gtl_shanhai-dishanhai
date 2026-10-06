package com.dishanhai.gt_shanhai.common.config;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 config/gt_shanhai/changelog/ 读取更新公告 Markdown。
 *
 * <p>文件名必须含有三段版本号，例如 Update-2.2.0.md。当前公告永远取文件名版本号最大的文件，
 * 其余公告保留在历史列表中。</p>
 *
 * <p>Markdown 文件可用 front matter 标注 enabled、version、title：</p>
 * <pre>
 * ---
 * enabled: true
 * version: 2.2.0
 * title: GregTech Leisure-增强版：2.2.0
 * ---
 * # 更新内容
 * - 修复某问题
 * </pre>
 */
public final class ChangelogConfig {

    private static final Path CHANGELOG_DIR = FMLPaths.CONFIGDIR.get()
            .resolve(GTDishanhaiMod.MOD_ID).resolve("changelog");
    private static final Path SEEN_FILE = CHANGELOG_DIR.resolve("changelog_seen.txt");
    private static final Pattern VERSION_IN_FILE_NAME = Pattern.compile(
            "(?:^|[-_])([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:[-+]([0-9A-Za-z.-]+))?\\.md$",
            Pattern.CASE_INSENSITIVE);

    private static List<ChangelogDocument> loaded;

    private ChangelogConfig() {
    }

    public enum LineKind {
        HEADING,
        BODY,
        BLANK
    }

    public record MarkdownLine(String text, LineKind kind) {
        public MarkdownLine(List<MarkdownSpan> spans, LineKind kind) {
            this(joinSpans(spans), kind);
        }

        public MutableComponent asComponent() {
            MutableComponent component = Component.empty();
            for (MarkdownSpan span : parseInline(this.text)) {
                MutableComponent part = Component.literal(span.text());
                if (span.bold()) {
                    part.withStyle(style -> style.withBold(true));
                }
                if (span.italic()) {
                    part.withStyle(style -> style.withItalic(true));
                }
                if (span.code()) {
                    part.withStyle(style -> style.withColor(0x55FFFF));
                }
                component.append(part);
            }
            return component;
        }

        private static String joinSpans(List<MarkdownSpan> spans) {
            StringBuilder builder = new StringBuilder();
            for (MarkdownSpan span : spans) {
                builder.append(span.text());
            }
            return builder.toString();
        }
    }

    public record MarkdownSpan(String text, boolean bold, boolean italic, boolean code) {
    }

    public record ChangelogDocument(
            String version,
            String title,
            boolean enabled,
            Path source,
            String fileVersion,
            List<MarkdownLine> lines) {

        public boolean hasContent() {
            for (MarkdownLine line : this.lines) {
                if (line.kind() != LineKind.BLANK && !line.text().isBlank()) {
                    return true;
                }
            }
            return !this.title.isBlank();
        }
    }

    public static ChangelogDocument getLatest() {
        List<ChangelogDocument> documents = getAll();
        return documents.isEmpty() ? null : documents.get(0);
    }

    public static List<ChangelogDocument> getHistory() {
        List<ChangelogDocument> documents = getAll();
        if (documents.size() <= 1) {
            return List.of();
        }
        return documents.subList(1, documents.size());
    }

    public static List<ChangelogDocument> getAll() {
        if (loaded == null) {
            loaded = load();
        }
        return loaded;
    }

    public static boolean shouldShow() {
        ChangelogDocument latest = getLatest();
        return latest != null
                && latest.enabled()
                && latest.hasContent()
                && !latest.version().equals(readSeenVersion());
    }

    public static void markSeen() {
        ChangelogDocument latest = getLatest();
        if (latest == null) {
            return;
        }
        try {
            Files.createDirectories(SEEN_FILE.getParent());
            Files.writeString(SEEN_FILE, latest.version(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            GTDishanhaiMod.LOGGER.error("[Changelog] 写入已读版本失败: {}", SEEN_FILE, exception);
        }
    }

    private static List<ChangelogDocument> load() {
        try {
            Files.createDirectories(CHANGELOG_DIR);
            List<ChangelogDocument> documents = new ArrayList<>();
            try (var paths = Files.walk(CHANGELOG_DIR)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                        .forEach(path -> {
                            ChangelogDocument document = parseMarkdown(path);
                            if (document != null) {
                                documents.add(document);
                            }
                        });
            }
            documents.sort((left, right) -> {
                int versionResult = compareVersions(right.fileVersion(), left.fileVersion());
                if (versionResult != 0) {
                    return versionResult;
                }
                return right.source().toString().compareToIgnoreCase(left.source().toString());
            });
            return Collections.unmodifiableList(documents);
        } catch (IOException exception) {
            GTDishanhaiMod.LOGGER.error("[Changelog] 读取公告目录失败: {}", CHANGELOG_DIR, exception);
            return List.of();
        }
    }

    /**
     * 按文件名提取版本并解析 front matter；正文只保留全屏公告所需的基础 Markdown。
     */
    private static ChangelogDocument parseMarkdown(Path path) {
        Matcher matcher = VERSION_IN_FILE_NAME.matcher(path.getFileName().toString());
        if (!matcher.find()) {
            GTDishanhaiMod.LOGGER.warn("[Changelog] 忽略无效公告文件名（需要 x.y.z）: {}", path);
            return null;
        }
        String fileVersion = matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3);
        Map<String, String> frontMatter = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<String> body = new ArrayList<>();
        try {
            List<String> rawLines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int bodyStart = 0;
            if (!rawLines.isEmpty() && rawLines.get(0).trim().equals("---")) {
                bodyStart = readFrontMatter(rawLines, frontMatter);
            }
            for (int i = bodyStart; i < rawLines.size(); i++) {
                body.add(rawLines.get(i));
            }
        } catch (Exception exception) {
            GTDishanhaiMod.LOGGER.error("[Changelog] 读取公告失败: {}", path, exception);
            return null;
        }

        boolean enabled = !"false".equalsIgnoreCase(frontMatter.getOrDefault("enabled", "true").trim());
        String version = frontMatter.getOrDefault("version", fileVersion).trim();
        String title = frontMatter.getOrDefault("title", "更新公告 " + fileVersion).trim();
        return new ChangelogDocument(
                version.isEmpty() ? fileVersion : version,
                title,
                enabled,
                path,
                fileVersion,
                parseMarkdown(body));
    }

    private static int readFrontMatter(List<String> rawLines, Map<String, String> target) {
        for (int i = 1; i < rawLines.size(); i++) {
            String line = rawLines.get(i).trim();
            if (line.equals("---")) {
                return i + 1;
            }
            int separator = line.indexOf(':');
            if (separator > 0) {
                target.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
        }
        return 0;
    }

    private static List<MarkdownLine> parseMarkdown(List<String> body) {
        List<MarkdownLine> result = new ArrayList<>();
        for (String raw : body) {
            String line = raw.trim();
            if (line.isEmpty()) {
                result.add(new MarkdownLine("", LineKind.BLANK));
            } else if (line.startsWith("#")) {
                result.add(new MarkdownLine(line.replaceFirst("^#+\\s*", ""), LineKind.HEADING));
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                result.add(new MarkdownLine("• " + line.substring(2).trim(), LineKind.BODY));
            } else {
                result.add(new MarkdownLine(line, LineKind.BODY));
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 解析公告正文的行内 Markdown。未闭合标记会按普通文字保留，不会丢失原文。
     */
    private static List<MarkdownSpan> parseInline(String text) {
        List<MarkdownSpan> spans = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            String marker = null;
            boolean bold = false;
            boolean italic = false;
            boolean code = false;
            if (text.startsWith("**", index) || text.startsWith("__", index)) {
                marker = text.substring(index, index + 2);
                bold = true;
            } else if (text.charAt(index) == '`') {
                marker = "`";
                code = true;
            } else if (text.charAt(index) == '*' || text.charAt(index) == '_') {
                marker = text.substring(index, index + 1);
                italic = true;
            }

            if (marker == null) {
                plain.append(text.charAt(index++));
                continue;
            }
            int end = text.indexOf(marker, index + marker.length());
            if (end <= index + marker.length()) {
                plain.append(marker);
                index += marker.length();
                continue;
            }
            if (plain.length() > 0) {
                spans.add(new MarkdownSpan(plain.toString(), false, false, false));
                plain.setLength(0);
            }
            spans.add(new MarkdownSpan(
                    text.substring(index + marker.length(), end), bold, italic, code));
            index = end + marker.length();
        }
        if (plain.length() > 0) {
            spans.add(new MarkdownSpan(plain.toString(), false, false, false));
        }
        return spans;
    }

    private static String readSeenVersion() {
        try {
            if (Files.exists(SEEN_FILE)) {
                return Files.readString(SEEN_FILE, StandardCharsets.UTF_8).trim();
            }
        } catch (IOException exception) {
            GTDishanhaiMod.LOGGER.error("[Changelog] 读取已读版本失败: {}", SEEN_FILE, exception);
        }
        return "";
    }

    static int compareVersions(String left, String right) {
        Version leftVersion = Version.parse(left);
        Version rightVersion = Version.parse(right);
        if (leftVersion == null || rightVersion == null) {
            return left.compareToIgnoreCase(right);
        }
        return leftVersion.compareTo(rightVersion);
    }

    private record Version(int major, int minor, int patch) implements Comparable<Version> {

        static Version parse(String value) {
            if (value == null) {
                return null;
            }
            String[] parts = value.trim().split("\\.");
            if (parts.length < 3) {
                return null;
            }
            try {
                return new Version(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        @Override
        public int compareTo(Version other) {
            int majorResult = Integer.compare(this.major, other.major);
            if (majorResult != 0) {
                return majorResult;
            }
            int minorResult = Integer.compare(this.minor, other.minor);
            return minorResult != 0 ? minorResult : Integer.compare(this.patch, other.patch);
        }
    }
}
