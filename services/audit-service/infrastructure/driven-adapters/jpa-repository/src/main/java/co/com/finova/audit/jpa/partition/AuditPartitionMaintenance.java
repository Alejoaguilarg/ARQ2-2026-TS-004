package co.com.finova.audit.jpa.partition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Keeps the monthly partitions of audit_records ahead of time (ADR 005): the partition of the current
 * month and of the next one always exist, so a record never lands in audit_records_default. The SQL
 * function runs as the schema owner (SECURITY DEFINER), because audit_rw cannot create tables.
 */
@Component
public class AuditPartitionMaintenance {

    private static final Logger LOG = LoggerFactory.getLogger(AuditPartitionMaintenance.class);

    private final JdbcClient jdbc;
    private final Clock clock;

    public AuditPartitionMaintenance(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${adapters.audit.partitions.initial-delay:PT30S}",
            fixedDelayString = "${adapters.audit.partitions.check-interval:PT6H}")
    public void ensureUpcomingPartitions() {
        LocalDate thisMonth = LocalDate.now(clock).withDayOfMonth(1);
        for (LocalDate month : List.of(thisMonth, thisMonth.plusMonths(1))) {
            try {
                String partition = jdbc.sql("SELECT audit_ensure_month_partition(:month)")
                        .param("month", month)
                        .query(String.class)
                        .single();
                LOG.debug("audit partition {} is in place", partition);
            } catch (DataAccessException e) {
                LOG.error("could not create the audit_records partition of {}; records of that month would"
                        + " land in audit_records_default", month, e);
            }
        }
    }
}
