package com.webhook.platform.api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Starts a replay after its session commits. Submitted by hand, not @Async, so a full executor
 * fails the session rather than running it in a finished transaction.
 */
@Component
@Slf4j
public class ReplaySessionLauncher {

    static final String EXECUTOR_FULL =
            "Too many replays are running on this server. Try again once one of them has finished.";

    private final ReplayService replayService;
    private final Executor replayTaskExecutor;

    public ReplaySessionLauncher(ReplayService replayService,
                                 @Qualifier("replayTaskExecutor") Executor replayTaskExecutor) {
        this.replayService = replayService;
        this.replayTaskExecutor = replayTaskExecutor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void launch(ReplaySessionCreated created) {
        try {
            replayTaskExecutor.execute(() -> replayService.run(created.sessionId()));
        } catch (RejectedExecutionException e) {
            log.warn("Replay executor is full; failing replay session {}", created.sessionId());
            replayService.failToStart(created.sessionId(), EXECUTOR_FULL);
        }
    }
}
