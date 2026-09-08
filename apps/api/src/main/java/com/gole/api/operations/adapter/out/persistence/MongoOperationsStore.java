package com.gole.api.operations.adapter.out.persistence;

import com.gole.api.operations.application.port.out.OperationsStore;
import com.gole.api.operations.domain.OperationRun;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
public class MongoOperationsStore implements OperationsStore {
    private static final String RUNS = "operations_runs";
    private static final String LOCKS = "operations_locks";
    private final MongoTemplate mongo;

    public MongoOperationsStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public boolean acquire(String jobId, String runId) {
        try {
            mongo.insert(new Document("_id", jobId).append("runId", runId), LOCKS);
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    public void release(String jobId, String runId) {
        mongo.remove(Query.query(Criteria.where("_id").is(jobId).and("runId").is(runId)), LOCKS);
    }

    public void save(OperationRun run) {
        mongo.save(run, RUNS);
    }

    public Optional<OperationRun> find(String id) {
        return Optional.ofNullable(mongo.findById(id, OperationRun.class, RUNS));
    }

    public List<OperationRun> recent() {
        return mongo.find(
                new Query().with(Sort.by(Sort.Direction.DESC, "startedAt")).limit(100), OperationRun.class, RUNS);
    }
}
