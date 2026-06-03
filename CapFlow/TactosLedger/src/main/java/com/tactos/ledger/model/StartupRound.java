package com.tactos.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB document entity representing a single startup funding round.
 *
 * <p>Concurrency Safety: The {@code @Version} field enables Spring Data MongoDB's
 * Optimistic Locking. On every save/update operation, Spring will compare the
 * in-memory version against the persisted version. If they differ (i.e., another
 * thread mutated the document between our read and write), Spring throws an
 * {@link org.springframework.dao.OptimisticLockingFailureException}, which is
 * caught and handled gracefully in {@link com.tactos.ledger.service.AllocationService}.
 *
 * <p>Note: For the atomic {@code findAndModify} flow in AllocationService, the
 * {@code @Version} field acts as an additional safety net for any non-atomic
 * save paths, ensuring no silent data corruption can occur anywhere in the system.
 *
 * @author TactosLedger Engineering
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "startup_rounds")
public class StartupRound {

    @Id
    private String id;

    /**
     * Human-readable name of the funding round, e.g. "Series A - Acme Corp".
     * Indexed for efficient lookup by name if required by future query patterns.
     */
    @Indexed
    @Field("round_name")
    private String roundName;

    /**
     * The name of the startup company this round belongs to.
     */
    @Field("company_name")
    private String companyName;

    /**
     * The absolute maximum capital that can be allocated across this round.
     * Immutable after round creation; serves as the ceiling for all allocation checks.
     * Stored as a String in MongoDB to preserve BigDecimal precision without BSON loss.
     */
    @Field("total_allocation_cap")
    private BigDecimal totalAllocationCap;

    /**
     * The real-time remaining capital available for new allocations.
     * This field is the subject of all atomic decrement operations via {@code $inc}.
     * It must never fall below zero; that invariant is enforced at the service layer.
     */
    @Field("remaining_allocation")
    private BigDecimal remainingAllocation;

    /**
     * An append-only audit ledger of every confirmed allocation transaction.
     * New entries are atomically appended via MongoDB's {@code $push} operator.
     * Initialized as an empty list to prevent NullPointerExceptions on first push.
     */
    @Builder.Default
    @Field("allocation_transactions")
    private List<AllocationTransaction> allocationTransactions = new ArrayList<>();

    /**
     * The round's current status lifecycle.
     */
    @Field("status")
    @Builder.Default
    private RoundStatus status = RoundStatus.OPEN;

    /**
     * Timestamp of document creation for auditing purposes.
     */
    @Field("created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();

    /**
     * Timestamp of the last modification; updated on every write.
     */
    @Field("updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /**
     * Spring Data MongoDB Optimistic Locking version vector.
     *
     * <p>This field is managed exclusively by the Spring Data framework.
     * It is auto-incremented on every document save. Any concurrent thread
     * holding a stale version will trigger an OptimisticLockingFailureException
     * upon attempting a conflicting write, preventing lost updates.
     *
     * <p><strong>Do not set this field manually.</strong>
     */
    @Version
    private Long version;

    /**
     * Represents the lifecycle state of a funding round.
     */
    public enum RoundStatus {
        /** Round is accepting new allocations. */
        OPEN,
        /** Round has been fully subscribed; no further allocations permitted. */
        FULLY_SUBSCRIBED,
        /** Round has been administratively closed before full subscription. */
        CLOSED
    }
}
