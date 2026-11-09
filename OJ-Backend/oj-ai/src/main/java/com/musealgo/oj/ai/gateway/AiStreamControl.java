package com.musealgo.oj.ai.gateway;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** SSE 生命周期可直接取消上游 HTTP，避免等到下一块内容才发现用户已经离开。 */
public final class AiStreamControl implements Consumer<String> {
    private final Consumer<String> downstream;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Runnable> cancelCall = new AtomicReference<>(() -> { });
    public AiStreamControl(Consumer<String> downstream) { this.downstream = downstream; }
    public boolean isCancelled() { return cancelled.get(); }
    public void onCancel(Runnable action) {
        cancelCall.set(action);
        if (cancelled.get()) action.run();
    }
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) cancelCall.get().run();
    }
    @Override public void accept(String text) {
        if (cancelled.get()) throw new Cancelled();
        downstream.accept(text);
    }
    public static final class Cancelled extends RuntimeException {
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }
}
