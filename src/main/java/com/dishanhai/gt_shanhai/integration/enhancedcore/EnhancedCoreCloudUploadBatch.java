package com.dishanhai.gt_shanhai.integration.enhancedcore;

import java.util.List;

/** 山海：逐張付款、產生實體來源，再交原生上傳交易；已提交項目不倒退。 */
public final class EnhancedCoreCloudUploadBatch {

    public enum Upload { COMMITTED, RETAINED, REVIEW }
    public enum Status { COMPLETE, OUTPUT_OCCUPIED, MISSING_BLANK, UPLOAD_FAILED, REVIEW }
    public record Result(int uploaded, int duplicates, int encoded, Status status) {}

    public interface Port<T> {
        boolean duplicate(T pattern);
        boolean outputEmpty();
        boolean consumeBlank();
        void stage(T pattern);
        Upload upload(T pattern);
    }

    private EnhancedCoreCloudUploadBatch() {}

    public static <T> Result run(List<T> patterns, Port<T> terminal) {
        int uploaded = 0;
        int duplicates = 0;
        int encoded = 0;
        for (T pattern : patterns) {
            if (terminal.duplicate(pattern)) { duplicates++; continue; }
            if (!terminal.outputEmpty()) return new Result(uploaded, duplicates, encoded, Status.OUTPUT_OCCUPIED);
            if (!terminal.consumeBlank()) return new Result(uploaded, duplicates, encoded, Status.MISSING_BLANK);
            encoded++;
            terminal.stage(pattern);
            Upload result = terminal.upload(pattern);
            if (result == Upload.COMMITTED) { uploaded++; continue; }
            return new Result(uploaded, duplicates, encoded,
                    result == Upload.REVIEW ? Status.REVIEW : Status.UPLOAD_FAILED);
        }
        return new Result(uploaded, duplicates, encoded, Status.COMPLETE);
    }
}
