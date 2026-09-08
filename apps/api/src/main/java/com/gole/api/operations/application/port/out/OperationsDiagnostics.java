package com.gole.api.operations.application.port.out;

public interface OperationsDiagnostics {
    /** Returns only bounded, non-sensitive diagnostic codes. Never sends notifications. */
    String inspect(String jobId);
}
