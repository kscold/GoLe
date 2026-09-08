package com.gole.api.design.adapter.out.persistence;

import com.gole.api.common.exception.ConflictException;
import com.gole.api.design.application.port.out.DesignRepositoryPort;
import com.gole.api.design.domain.model.DesignRevision;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class MongoDesignAdapter implements DesignRepositoryPort {
    private final MongoTemplate mongo;

    public MongoDesignAdapter(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Document("design_revisions")
    public record RevisionDocument(
            @Id long revision,
            Map<String, String> tokens,
            String actorId,
            String reason,
            String action,
            Instant publishedAt) {
        DesignRevision model() {
            return new DesignRevision(revision, tokens, actorId, reason, action, publishedAt);
        }
    }

    public DesignRevision current() {
        var d = mongo.findOne(
                new Query().with(Sort.by(Sort.Direction.DESC, "_id")).limit(1), RevisionDocument.class);
        return d == null ? DesignRevision.initial() : d.model();
    }

    public Optional<DesignRevision> find(long revision) {
        return Optional.ofNullable(mongo.findById(revision, RevisionDocument.class))
                .map(RevisionDocument::model);
    }

    public List<DesignRevision> history(long before) {
        return mongo
                .find(
                        Query.query(Criteria.where("_id").lt(before))
                                .with(Sort.by(Sort.Direction.DESC, "_id"))
                                .limit(25),
                        RevisionDocument.class)
                .stream()
                .map(RevisionDocument::model)
                .toList();
    }

    public void append(DesignRevision r) {
        try {
            // Unique _id is the compare-and-set fence, including concurrent first publication.
            // Publication and its audit cannot diverge: both are the same immutable insert.
            mongo.insert(new RevisionDocument(
                    r.revision(), r.tokens(), r.actorId(), r.reason(), r.action(), r.publishedAt()));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("DESIGN_REVISION_CONFLICT", "다른 관리자가 먼저 게시했습니다. 최신 값을 불러와 주세요");
        }
    }
}
