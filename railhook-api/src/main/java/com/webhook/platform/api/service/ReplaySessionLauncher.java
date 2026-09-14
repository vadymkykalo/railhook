package com.webhook.platform.api.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Starts a replay once its session row has committed, on the replay executor.
 *
 * <p>A bean of its own because {@code @Async} only applies to calls that come through the proxy.
 * ReplayService used to call its own async method, which ran the whole replay — up to the
 * per-session event cap — on the request thread, inside the request's transaction: cancel and
 * the concurrent-session cap could not see the uncommitted session, and one failing batch marked
 * that transaction rollback-only and took the session down with it.
 *
 * <p>After commit, so the replay never starts on a session that is then rolled back. The replay
 * executor carries the request's tenant scope across.
 */
@Component
public class ReplaySessionLauncher {

    private final ReplayService replayService;

    public ReplaySessionLauncher(ReplayService replayService) {
        this.replayService = replayService;
    }

    @Async("replayTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void launch(ReplaySessionCreated created) {
        replayService.run(created.sessionId());
    }
}
