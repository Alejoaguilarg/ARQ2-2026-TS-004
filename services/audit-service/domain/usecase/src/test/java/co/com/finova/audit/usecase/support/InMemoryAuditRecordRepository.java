package co.com.finova.audit.usecase.support;

import co.com.finova.audit.model.query.AuditPage;
import co.com.finova.audit.model.query.AuditQuery;
import co.com.finova.audit.model.record.AuditRecord;
import co.com.finova.audit.model.record.ChainLink;
import co.com.finova.audit.model.record.gateways.AuditRecordRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;

/** audit_db in memory. tamper() and remove() stand for someone who bypasses the database guards. */
public class InMemoryAuditRecordRepository implements AuditRecordRepository {

    private final TreeMap<Long, AuditRecord> bySeq = new TreeMap<>();
    private final List<String> calls = new ArrayList<>();

    @Override
    public void lockChain() {
        calls.add("lockChain");
    }

    @Override
    public boolean contains(UUID eventId) {
        calls.add("contains");
        return stored(eventId);
    }

    @Override
    public Optional<ChainLink> findLastLink() {
        return bySeq.isEmpty() ? Optional.empty() : Optional.of(bySeq.lastEntry().getValue().link());
    }

    @Override
    public Optional<ChainLink> findLink(long seq) {
        return Optional.ofNullable(bySeq.get(seq)).map(AuditRecord::link);
    }

    @Override
    public void append(AuditRecord record) {
        calls.add("append");
        if (bySeq.containsKey(record.seq()) || stored(record.attempt().eventId())) {
            throw new IllegalStateException("duplicate key");
        }
        bySeq.put(record.seq(), record);
    }

    @Override
    public AuditPage find(AuditQuery query) {
        Predicate<AuditRecord> matches = r -> (query.transferId() == null
                || query.transferId().equals(r.attempt().transferId()))
                && (query.userId() == null || query.userId().equals(r.attempt().userId()))
                && (query.from() == null || !r.attempt().occurredAt().isBefore(query.from()))
                && (query.to() == null || !r.attempt().occurredAt().isAfter(query.to()));
        List<AuditRecord> all = bySeq.values().stream().filter(matches).toList();
        List<AuditRecord> page = all.stream().skip(query.offset()).limit(query.size()).toList();
        return new AuditPage(query.page(), query.size(), all.size(), page);
    }

    @Override
    public List<AuditRecord> findRange(long fromSeq, long toSeq, int limit) {
        return bySeq.subMap(fromSeq, true, toSeq, true).values().stream().limit(limit).toList();
    }

    private boolean stored(UUID eventId) {
        return bySeq.values().stream().anyMatch(r -> r.attempt().eventId().equals(eventId));
    }

    public void tamper(AuditRecord replacement) {
        bySeq.put(replacement.seq(), replacement);
    }

    public void remove(long seq) {
        bySeq.remove(seq);
    }

    public AuditRecord get(long seq) {
        return bySeq.get(seq);
    }

    public Map<Long, AuditRecord> all() {
        return Map.copyOf(bySeq);
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }
}
