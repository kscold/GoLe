package com.gole.api.operations.domain;

import java.time.Instant;

/** Persisted audit: no exception messages, credentials, email or user-entered text. */
public record OperationRun(
        String id,
        String jobId,
        String actorId,
        String reasonCode,
        String retryOf,
        String status,
        Instant startedAt,
        Instant finishedAt,
        String resultCode) {
    public OperationRun finish(String status, String resultCode, Instant now) {
        return new OperationRun(id, jobId, actorId, reasonCode, retryOf, status, startedAt, now, resultCode);
    }
}
