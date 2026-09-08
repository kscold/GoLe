package com.gole.api.design;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gole.api.common.exception.ConflictException;
import com.gole.api.design.adapter.out.persistence.MongoDesignAdapter;
import com.gole.api.design.adapter.out.persistence.MongoDesignAdapter.RevisionDocument;
import com.gole.api.design.domain.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

class MongoDesignAdapterTest {
    @Test
    void insertPersistsPublicationAndAuditTogetherAndDuplicateIsConflict() {
        var mongo = mock(MongoTemplate.class);
        var adapter = new MongoDesignAdapter(mongo);
        var r = new DesignRevision(1, DesignSchema.defaults(), "admin", "reason", "PUBLISH", java.time.Instant.EPOCH);
        adapter.append(r);
        var captor = org.mockito.ArgumentCaptor.forClass(RevisionDocument.class);
        verify(mongo).insert(captor.capture());
        assertThat(captor.getValue().tokens()).isEqualTo(r.tokens());
        assertThat(captor.getValue().actorId()).isEqualTo("admin");
        assertThat(captor.getValue().revision()).isEqualTo(1);
        when(mongo.insert(any(RevisionDocument.class))).thenThrow(new DuplicateKeyException("duplicate"));
        assertThatThrownBy(() -> adapter.append(r)).isInstanceOf(ConflictException.class);
    }

    @Test
    void readsStoredRevisionAfterNewAdapterInstance() {
        var mongo = mock(MongoTemplate.class);
        when(mongo.findById(2L, RevisionDocument.class))
                .thenReturn(new RevisionDocument(
                        2, DesignSchema.defaults(), "admin", "why", "RESET", java.time.Instant.EPOCH));
        var loaded = new MongoDesignAdapter(mongo).find(2).orElseThrow();
        assertThat(loaded.revision()).isEqualTo(2);
        assertThat(loaded.reason()).isEqualTo("why");
    }
}
