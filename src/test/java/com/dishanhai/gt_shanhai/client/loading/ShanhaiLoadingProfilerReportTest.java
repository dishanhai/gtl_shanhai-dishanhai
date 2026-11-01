package com.dishanhai.gt_shanhai.client.loading;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：報告語義與 JDK 內建錄製的實際 dump/read 冒煙測試。 */
class ShanhaiLoadingProfilerReportTest {
    @Test void jsonAndTextKeepObservedAndMeasuredDataDistinct() {
        var metrics = new ShanhaiLoadingMetrics("startup", () -> 10, 100);
        metrics.record("Forge stage", "Forge observed", 100, -1, -1, 100, false);
        metrics.record("ModelManager", "apply", 50, 30, 1024, 120, false);
        var report = new ShanhaiLoadingProfiler.ProfileReport(2, Instant.now().toString(), metrics.finish("ready", 90),
                -1, 4, 0, 0, new ShanhaiLoadingJfr.Summary("disabled", List.of(), List.of()));
        var copy = new Gson().fromJson(new Gson().toJson(report), ShanhaiLoadingProfiler.ProfileReport.class);
        assertEquals(report, copy);
        String text = ShanhaiLoadingProfiler.text(copy);
        assertTrue(text.contains("Forge observed"));
        assertTrue(text.contains("CPU N/A"));
        assertTrue(text.contains("-10 bytes"));
        assertTrue(text.contains("並行／巢狀工作不可相加"));
        assertTrue(text.contains("不是精確 CPU 耗時"));
    }

    @Test void jfrCanStartDumpAndReadWithoutMinecraft(@TempDir Path temporary) throws Exception {
        var sampler = ShanhaiLoadingJfr.start(true);
        var result = sampler.finish(temporary.resolve("loading.jfr"), Instant.now());
        assertTrue(result.status().startsWith("complete"), result.status());
        assertTrue(java.nio.file.Files.size(temporary.resolve("loading.jfr")) > 0);
        sampler.close();
    }
}
