package com.gole.api.design;

import static org.assertj.core.api.Assertions.*;

import com.gole.api.common.exception.ConflictException;
import com.gole.api.design.adapter.out.persistence.MongoDesignAdapter;
import com.gole.api.design.application.service.DesignService;
import com.gole.api.design.domain.model.*;
import com.mongodb.client.MongoClients;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class DesignPersistenceIntegrationTest {
    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Test
    void persistsAcrossInstancesAndFencesConcurrentPublicationWithAudit() throws Exception {
        try (var client = MongoClients.create(MONGO.getReplicaSetUrl())) {
            var template = new MongoTemplate(client, "design_test");
            var first = new MongoDesignAdapter(template);
            assertThat(first.current().revision()).isZero();
            var published =
                    new DesignService(first).publish(0, DesignSchema.defaults(), "initial publication", "admin-1");
            var second = new MongoDesignAdapter(new MongoTemplate(client, "design_test"));
            assertThat(second.current()).isEqualTo(published);
            var next = new DesignRevision(
                    2, DesignSchema.defaults(), "admin-2", "concurrent", "PUBLISH", java.time.Instant.now());
            var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                Callable<Boolean> append = () -> {
                    start.await();
                    try {
                        second.append(next);
                        return true;
                    } catch (ConflictException e) {
                        return false;
                    }
                };
                var a = pool.submit(append);
                var b = pool.submit(append);
                start.countDown();
                assertThat(java.util.List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(true, false);
            }
            var history = second.history(Long.MAX_VALUE);
            assertThat(history).hasSize(2);
            assertThat(history.getFirst().actorId()).isEqualTo("admin-2");
            assertThat(history.getFirst().reason()).isEqualTo("concurrent");
            assertThat(second.history(2)).hasSize(1);
            var restored = new DesignService(second).restore(2, 1, "restore initial", "admin-3");
            assertThat(restored.revision()).isEqualTo(3);
            assertThat(second.find(1).orElseThrow()).isEqualTo(published);
        }
    }
}
