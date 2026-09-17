package com.webhook.platform.api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Starts a replay once its session row has committed, on the replay executor.
 *
 * <p>A bean of its own because the replay has to leave the request thread. ReplayService used to
 * call its own async method, which ran the whole replay — up to the per-session event cap — on
 * the request thread, inside the request's transaction: cancel and the concurrent-session cap
 * could not see the uncommitted session, and one failing batch marked that transaction
 * rollback-only and took the session down with it.
 *
 * <p>After commit, so the replay never starts on a session that is then rolled back. The replay
 * executor carries the request's tenant scope across.
 *
 * <p>Submitted by hand rather than through {@code @Async}, because a full executor has to be
 * answered here. It used to fall back to CallerRunsPolicy, which ran the replay on the request
 * thread inside this after-commit callback, where every write that joins the surrounding
 * transaction joins one that has already committed: the session never left PENDING while its
 * deliveries were created, and the request did not return until the replay was over.
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
