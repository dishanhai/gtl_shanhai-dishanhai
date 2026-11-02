package com.dishanhai.gt_shanhai.client.loading;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

/** 山海：世界資料專用串行工作者；不搬動渲染、JEI 或遊戲 tick。 */
public final class ShanhaiLoadingAsync {
    private ShanhaiLoadingAsync() {}

    /** 只替換等待方式，沿用 ForkJoinTask.get 的例外與中斷語義。 */
    public static <T> T await(ForkJoinTask<T> task, Runnable pump) throws ExecutionException, InterruptedException {
        while (!task.isDone()) {
            pump.run();
            try { return task.get(50, TimeUnit.MILLISECONDS); }
            catch (TimeoutException waiting) { /* 下一個視窗事件與補幀時間片。 */ }
        }
        return task.get();
    }

    public static <T> CompletableFuture<T> start(
            BiFunction<Executor, Executor, CompletableFuture<T>> load,
            Executor prepare, Executor originalApply, boolean enabled) {
        if (!enabled) return load.apply(prepare, originalApply);
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        var worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "shanhai-world-data");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY);
            thread.setContextClassLoader(context);
            return thread;
        });
        // 不在此執行緒 join：後續 apply 仍須在同一個工作者上依序執行。
        var result = CompletableFuture.supplyAsync(() -> load.apply(prepare, worker), worker)
                .thenCompose(future -> future);
        result.whenComplete((value, error) -> worker.shutdown());
        // 呼叫端取消等待時，原資料管線仍須跑完原生回收，不可提前拒絕其排隊工作。
        return result.thenApply(value -> value);
    }
}
