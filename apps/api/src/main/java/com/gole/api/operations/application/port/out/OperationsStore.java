package com.gole.api.operations.application.port.out;

import com.gole.api.operations.domain.OperationRun;
import java.util.List;
import java.util.Optional;

public interface OperationsStore {
    boolean acquire(String jobId, String runId);

    void release(String jobId, String runId);

    void save(OperationRun run);

    Optional<OperationRun> find(String runId);

    List<OperationRun> recent();
}
