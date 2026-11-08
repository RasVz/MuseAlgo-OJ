package com.musealgo.oj.contest.sse;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SseRegistryTest {

    @Test
    void invalidationIsBoardScopedAndContainsNoRankingRows() {
        SseRegistry registry = new SseRegistry();
        RecordingEmitter official = new RecordingEmitter();
        RecordingEmitter global = new RecordingEmitter();
        registry.register(4L, ContestRankBoardType.OFFICIAL, official);
        registry.register(4L, ContestRankBoardType.GLOBAL, global);

        registry.broadcastInvalidation(4L, ContestRankBoardType.GLOBAL);

        assertTrue(official.frames.isEmpty());
        assertEquals(1, global.frames.size());
        String frame = global.frames.get(0);
        assertTrue(frame.contains("ranking-invalidated"), frame);
        assertTrue(frame.contains("global"), frame);
        assertFalse(frame.contains("username"), frame);
        assertFalse(frame.contains("score"), frame);
        assertEquals(2, registry.activeSubscriptions().size());
    }

    @Test
    void removingOldConnectionCannotDropConcurrentSameBoardReplacement() throws Exception {
        CountDownLatch removalCheckedEmpty = new CountDownLatch(1);
        CountDownLatch allowRemovalToFinish = new CountDownLatch(1);
        BlockingList blockingList = new BlockingList(removalCheckedEmpty, allowRemovalToFinish);
        AtomicInteger listCount = new AtomicInteger();
        Supplier<List<SseEmitter>> listFactory = () -> listCount.getAndIncrement() == 0
                ? blockingList
                : new CopyOnWriteArrayList<>();
        SseRegistry registry = new SseRegistry(listFactory);
        SseEmitter oldConnection = mock(SseEmitter.class);
        RecordingEmitter replacement = new RecordingEmitter();
        registry.register(4L, ContestRankBoardType.OFFICIAL, oldConnection);

        ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
        verify(oldConnection).onCompletion(completion.capture());
        Thread remover = new Thread(completion.getValue(), "rank-sse-remove");
        remover.start();
        assertTrue(removalCheckedEmpty.await(1, TimeUnit.SECONDS), "removal must reach its empty-list boundary");

        CountDownLatch registrationStarted = new CountDownLatch(1);
        AtomicReference<Throwable> registrationFailure = new AtomicReference<>();
        Thread registrar = new Thread(() -> {
            registrationStarted.countDown();
            try {
                registry.register(4L, ContestRankBoardType.OFFICIAL, replacement);
            } catch (Throwable failure) {
                registrationFailure.set(failure);
            }
        }, "rank-sse-register");
        registrar.start();
        assertTrue(registrationStarted.await(1, TimeUnit.SECONDS));
        Thread.sleep(25); // Let registration contend with the paused last-connection removal.
        allowRemovalToFinish.countDown();
        remover.join(1000);
        registrar.join(1000);

        assertFalse(remover.isAlive(), "removal thread did not finish");
        assertFalse(registrar.isAlive(), "registration thread did not finish");
        if (registrationFailure.get() != null) throw new AssertionError(registrationFailure.get());
        registry.broadcastInvalidation(4L, ContestRankBoardType.OFFICIAL);

        assertEquals(1, replacement.frames.size());
        assertTrue(replacement.frames.get(0).contains("ranking-invalidated"));
    }

    @Test
    void anAlreadyCompletedEmitterDoesNotInterruptOtherSubscribers() {
        SseRegistry registry = new SseRegistry();
        IllegalStateEmitter completed = new IllegalStateEmitter();
        RecordingEmitter live = new RecordingEmitter();
        registry.register(4L, ContestRankBoardType.GLOBAL, completed);
        registry.register(4L, ContestRankBoardType.GLOBAL, live);

        registry.broadcastInvalidation(4L, ContestRankBoardType.GLOBAL);

        assertEquals(1, live.frames.size());
        assertTrue(live.frames.get(0).contains("ranking-invalidated"));
        assertFalse(registry.activeSubscriptions().isEmpty());
    }

    private static final class RecordingEmitter extends SseEmitter {
        private final List<String> frames = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            frames.add(builder.build().stream()
                    .map(part -> String.valueOf(part.getData()))
                    .collect(Collectors.joining()));
        }
    }

    private static final class IllegalStateEmitter extends SseEmitter {
        @Override
        public void send(SseEventBuilder builder) {
            throw new IllegalStateException("emitter is already complete");
        }
    }

    private static final class BlockingList extends CopyOnWriteArrayList<SseEmitter> {
        private final CountDownLatch checkedEmpty;
        private final CountDownLatch continueRemoval;
        private final AtomicBoolean pauseNextEmptyCheck = new AtomicBoolean(true);

        private BlockingList(CountDownLatch checkedEmpty, CountDownLatch continueRemoval) {
            this.checkedEmpty = checkedEmpty;
            this.continueRemoval = continueRemoval;
        }

        @Override
        public boolean isEmpty() {
            boolean empty = super.isEmpty();
            if (empty && pauseNextEmptyCheck.compareAndSet(true, false)) {
                checkedEmpty.countDown();
                try {
                    if (!continueRemoval.await(1, TimeUnit.SECONDS)) {
                        throw new AssertionError("test did not release paused removal");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            return empty;
        }
    }
}
