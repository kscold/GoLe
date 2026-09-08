package com.gole.api.design.application.port.out;

import com.gole.api.design.domain.model.DesignRevision;
import java.util.*;

public interface DesignRepositoryPort {
    DesignRevision current();

    Optional<DesignRevision> find(long revision);

    List<DesignRevision> history(long before);

    void append(DesignRevision revision);
}
