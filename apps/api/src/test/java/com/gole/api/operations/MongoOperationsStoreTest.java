package com.gole.api.operations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.gole.api.operations.adapter.out.persistence.MongoOperationsStore;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class MongoOperationsStoreTest {
    @Test
    void databaseUniqueIdEnforcesCrossInstanceLock() {
        var mongo = mock(MongoTemplate.class);
        when(mongo.insert(any(Document.class), eq("operations_locks")))
                .thenThrow(new DuplicateKeyException("duplicate"));
        assertFalse(new MongoOperationsStore(mongo).acquire("exception-queue", "run-2"));
        verify(mongo)
                .insert(
                        argThat((Document doc) -> doc.getString("_id").equals("exception-queue")
                                && doc.getString("runId").equals("run-2")),
                        eq("operations_locks"));
    }

    @Test
    void lockReleaseMustMatchOwner() {
        var mongo = mock(MongoTemplate.class);
        new MongoOperationsStore(mongo).release("exception-queue", "run-1");
        verify(mongo)
                .remove(
                        argThat((Query query) -> query.getQueryObject()
                                        .getString("_id")
                                        .equals("exception-queue")
                                && query.getQueryObject().getString("runId").equals("run-1")),
                        eq("operations_locks"));
    }
}
