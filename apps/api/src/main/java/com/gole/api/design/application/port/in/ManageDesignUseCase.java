package com.gole.api.design.application.port.in;

import com.gole.api.design.domain.model.DesignRevision;
import java.util.*;

public interface ManageDesignUseCase {
    DesignRevision current();

    List<DesignRevision> history(long before);

    DesignRevision publish(long expectedRevision, Map<String, String> tokens, String reason, String actorId);

    DesignRevision restore(long expectedRevision, long sourceRevision, String reason, String actorId);
}
