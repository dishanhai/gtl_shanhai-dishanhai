package com.dishanhai.gt_shanhai.client.loading;

import java.util.LinkedHashMap;
import java.util.List;

/** 山海：不依賴遊戲註冊表的載入會話，工作執行緒只提交資料。 */
public final class ShanhaiLoadingState {
    private final LinkedHashMap<String, Task> tasks = new LinkedHashMap<>();
    private long session;
    private long started;
    private long finished = -1;
    private boolean active;

    public synchronized long begin(long now) {
        session++;
        started = now;
        finished = -1;
        active = true;
        tasks.clear();
        return session;
    }

    public synchronized long session() { return session; }
    public synchronized boolean active() { return active; }

    public synchronized void update(String key, String label, String detail, int current, int total) {
        update(session, key, label, detail, current, total);
    }

    public synchronized void update(long owner, String key, String label, String detail, int current, int total) {
        if (!active || owner != session) return;
        int count = total > 0 ? Math.min(Math.max(0, current), total) : Math.max(0, current);
        tasks.put(key, new Task(label, detail, count, total > 0 ? total : -1, false));
        if (tasks.size() > 8) tasks.remove(tasks.keySet().iterator().next());
    }

    public synchronized void complete(long owner, String key, String label, String detail, int count) {
        if (!active || owner != session) return;
        tasks.put(key, new Task(label, detail, Math.max(0, count), count < 0 ? -1 : count, true));
        if (tasks.size() > 8) tasks.remove(tasks.keySet().iterator().next());
    }

    public synchronized void finish(long now) {
        if (!active) return;
        active = false;
        finished = now;
    }

    public synchronized void reset() {
        session++;
        active = false;
        finished = -1;
        tasks.clear();
    }

    public synchronized Frame snapshot(long now, boolean reduceMotion) {
        float opacity = active ? 1f : finished < 0 || reduceMotion ? 0f
                : Math.max(0f, Math.min(1f, 1f - (now - finished) / 350f));
        return new Frame(active, Math.max(0, (finished >= 0 ? finished : now) - started), opacity,
                List.copyOf(tasks.values()));
    }

    public record Task(String label, String detail, int current, int total, boolean done) {
        public float fraction() { return done ? 1f : total <= 0 ? -1f : Math.max(0f, Math.min(1f, (float) current / total)); }
    }

    public record Frame(boolean active, long elapsedMs, float opacity, List<Task> tasks) {}
}
