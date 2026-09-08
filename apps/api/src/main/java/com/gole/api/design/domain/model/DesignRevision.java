package com.gole.api.design.domain.model;

import java.time.Instant;
import java.util.Map;

/** Immutable publication and audit event, persisted atomically as one Mongo document. */
public record DesignRevision(
        long revision, Map<String, String> tokens, String actorId, String reason, String action, Instant publishedAt) {
    public DesignRevision {
        tokens = Map.copyOf(tokens);
    }

    public static DesignRevision initial() {
        return new DesignRevision(0, DesignSchema.defaults(), "", "", "DEFAULT", Instant.EPOCH);
    }
}
