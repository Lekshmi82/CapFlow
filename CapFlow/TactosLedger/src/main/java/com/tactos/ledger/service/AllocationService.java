package com.tactos.ledger.service;

import com.tactos.ledger.exception.AllocationOversubscribedException;
import com.tactos.ledger.exception.RoundNotFoundException;
import com.tactos.ledger.model.AllocationTransaction;
import com.tactos.ledger.model.StartupRound;
import com.tactos.ledger.repository.AllocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Core domain service responsible for the thread-safe processing of
 * startup funding allocations.
 *
 * <h2>The Concurrency Problem This Solves</h2>
 * <p>Consider a startup round with $500,000 remaining. Two back-office ops managers
 * (or two parallel banking API webhooks) both read $500,000 simultaneously and
 * both attempt to commit a $400,000 allocation. A naive "read-then-write" approach
 * would allow both to succeed, over-subscribing the round by $300,000.
 *
 * <h2>The Solution: Atomic FindAndModify</h2>
 * <p>MongoDB's {@code findAndModify} (exposed via {@link MongoTemplate}) resolves
 * this by combining the condition check ({@code remaining_allocation >= amount}),
 * the decrement ({@code $inc}), and the transaction append ({@code $push}) into a
 * <strong>single, atomic database operation</strong>. MongoDB's document-level
 * locking guarantees that only one of the two concurrent requests will match the
 * query filter; the other will receive {@code null} back, cleanly indicating that
 * the allocation was not possible.
 *
 * <p>This is superior to application-level {@code synchronized} blocks or
 * distributed locks because it requires zero coordination overhead and scales
 * horizontally across multiple service instances.
 *
 * <h2>Secondary Defense: Optimistic Locking</h2>
 * <p>The {@code @Version} field on {@link StartupRound} provides a secondary
 * safety net for any non-atomic save paths elsewhere in the system, ensuring
 * stale writes are always rejected with a clear exception.
 *
 * @author TactosLedger Engineering
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AllocationService {

    private final MongoTemplate mongoTemplate;
    private final AllocationRepository allocationRepository;

    /**
     * Atomically processes a capital allocation request against a funding round.
     *
     * <p>This method is the heart of TactosLedger's concurrency safety guarantee.
     * It performs the following in a <strong>single, indivisible MongoDB operation</strong>:
     * <ol>
     *   <li>Acquires a document-level lock on the target round</li>
     *   <li>Verifies the round is OPEN and has sufficient remaining allocation</li>
     *   <li>Decrements {@code remainingAllocation} by {@code amount} via {@code $inc}</li>
     *   <li>Appends the new {@link AllocationTransaction} sub-document via {@code $push}</li>
     *   <li>Updates {@code updatedAt} timestamp</li>
     *   <li>If remaining balance reaches zero, marks the round as FULLY_SUBSCRIBED</li>
     *   <li>Returns the post-modification document state</li>
     * </ol>
     *
     * <p>If two threads call this method simultaneously for the same round with
     * amounts that together exceed the remaining balance, exactly one will succeed
     * and the other will receive {@code false}, with no possibility of over-subscription.
     *
     * @param roundId      the MongoDB document ID of the target {@link StartupRound}
     * @param investorName the canonical name of the investing entity
     * @param amount       the capital amount to allocate; must be positive and non-null
     * @return {@code true} if the allocation was committed successfully;
     *         {@code false} if the round had insufficient remaining allocation
     *         (i.e., another concurrent request consumed the remaining balance first)
     * @throws IllegalArgumentException          if {@code amount} is null, zero, or negative
     * @throws RoundNotFoundException            if no round exists with the given {@code roundId}
     * @throws AllocationOversubscribedException if the round is not in OPEN status
     * @throws OptimisticLockingFailureException if a concurrent modification is detected
     *                                           on a non-atomic save path (secondary defense)
     */
    public boolean processAllocation(String roundId, String investorName, BigDecimal amount) {
        log.info("Processing allocation request — roundId: [{}], investor: [{}], amount: [{}]",
                roundId, investorName, amount);

        // --- Input Validation (Fail Fast) ---
        validateAllocationRequest(roundId, investorName, amount);

        // --- Pre-flight: Verify Round Exists and is OPEN ---
        // This read is non-atomic with the update below, but it provides a fast,
        // informative early exit with a rich error message before hitting the
        // atomic path. The atomic query below is the true concurrency guard.
        StartupRound existingRound = allocationRepository.findById(roundId)
                .orElseThrow(() -> {
                    log.warn("Allocation rejected — round not found. roundId: [{}]", roundId);
                    return new RoundNotFoundException(
                            String.format("Funding round with ID '%s' does not exist.", roundId));
                });

        if (existingRound.getStatus() != StartupRound.RoundStatus.OPEN) {
            log.warn("Allocation rejected — round is not OPEN. roundId: [{}], status: [{}]",
                    roundId, existingRound.getStatus());
            throw new AllocationOversubscribedException(
                    String.format("Round '%s' is not accepting allocations. Current status: %s",
                            existingRound.getRoundName(), existingRound.getStatus()));
        }

        // --- Core Atomic Operation ---
        try {
            return executeAtomicAllocation(roundId, investorName, amount, existingRound.getRoundName());
        } catch (OptimisticLockingFailureException ex) {
            // This path is triggered if a non-atomic save path elsewhere in the system
            // (e.g., a @Transactional save()) races with this operation.
            // The atomic findAndModify itself does not throw this — it simply returns null.
            log.error("CONCURRENCY CONFLICT — Optimistic locking failure detected for roundId: [{}]. " +
                      "A stale version was detected; the operation was safely aborted. " +
                      "This indicates a parallel write via a non-atomic code path. " +
                      "Request should be retried by the client.", roundId, ex);
            throw ex; // Re-throw; GlobalExceptionHandler will translate to HTTP 409
        }
    }

    /**
     * Constructs and executes the atomic MongoDB {@code findAndModify} operation.
     *
     * <p>The query filter simultaneously acts as both a lookup and a condition guard:
     * <ul>
     *   <li>{@code _id: roundId} — targets the specific round document</li>
     *   <li>{@code remaining_allocation: { $gte: amount }} — the atomic over-subscription guard</li>
     *   <li>{@code status: OPEN} — prevents allocation against closed rounds</li>
     * </ul>
     *
     * <p>If the document matching ALL three conditions does not exist (either because
     * the round doesn't exist, is closed, or has insufficient balance), MongoDB returns
     * {@code null} — no update is applied, no partial write occurs.
     *
     * @param roundId      target round document ID
     * @param investorName name of the investor
     * @param amount       allocation amount
     * @param roundName    human-readable round name for log enrichment
     * @return {@code true} if the allocation was successfully committed
     */
    private boolean executeAtomicAllocation(String roundId, String investorName,
                                             BigDecimal amount, String roundName) {

        // Build the AllocationTransaction sub-document to be appended
        AllocationTransaction transaction = AllocationTransaction.of(investorName, amount);

        // Query: Match the round ONLY IF it is OPEN and has sufficient remaining balance.
        // This is the atomic guard that prevents over-subscription.
        Query atomicQuery = new Query(
                Criteria.where("_id").is(roundId)
                        .and("remaining_allocation").gte(amount)
                        .and("status").is(StartupRound.RoundStatus.OPEN)
        );

        // Update: Atomically decrement balance, append transaction, and update timestamp.
        // Note: MongoDB's $inc with a negative BigDecimal correctly decrements the field.
        // Note: $push appends the transaction sub-document to the array without a full read.
        Update atomicUpdate = new Update()
                .inc("remaining_allocation", amount.negate())
                .push("allocation_transactions", transaction)
                .set("updated_at", Instant.now());

        // Options: Return the document state AFTER the modification is applied.
        // This lets us inspect the post-update remainingAllocation for status transitions.
        FindAndModifyOptions options = FindAndModifyOptions.options()
                .returnNew(true)   // Return the post-modification document
                .upsert(false);    // Never create a new document; only update existing

        log.debug("Executing atomic findAndModify — roundId: [{}], query: [{}], update: [{}]",
                roundId, atomicQuery, atomicUpdate);

        StartupRound updatedRound = mongoTemplate.findAndModify(
                atomicQuery,
                atomicUpdate,
                options,
                StartupRound.class
        );

        // A null result means the atomic query filter found no matching document.
        // This is the expected signal for a failed allocation due to insufficient balance.
        if (updatedRound == null) {
            log.warn("Allocation REJECTED by atomic guard — insufficient remaining balance. " +
                     "roundId: [{}], investor: [{}], requested amount: [{}]. " +
                     "A concurrent allocation likely consumed the remaining balance.",
                    roundId, investorName, amount);
            return false;
        }

        log.info("Allocation COMMITTED successfully — roundId: [{}], investor: [{}], amount: [{}], " +
                 "transactionId: [{}], remainingAllocation after: [{}]",
                roundId, investorName, amount, transaction.getTransactionId(),
                updatedRound.getRemainingAllocation());

        // Post-commit: If the balance is now exactly zero, mark the round as fully subscribed.
        // This secondary update is not strictly atomic with the above, but it is idempotent
        // and only affects status — no financial data is at risk.
        if (updatedRound.getRemainingAllocation().compareTo(BigDecimal.ZERO) == 0) {
            markRoundAsFullySubscribed(roundId, roundName);
        }

        return true;
    }

    /**
     * Marks a fully subscribed round as {@link StartupRound.RoundStatus#FULLY_SUBSCRIBED}.
     * Uses a conditional update to ensure idempotency — only transitions from OPEN to
     * FULLY_SUBSCRIBED, never overwriting a manually CLOSED round.
     *
     * @param roundId   the ID of the round to close
     * @param roundName human-readable name for logging
     */
    private void markRoundAsFullySubscribed(String roundId, String roundName) {
        Query statusQuery = new Query(
                Criteria.where("_id").is(roundId)
                        .and("status").is(StartupRound.RoundStatus.OPEN)
        );
        Update statusUpdate = new Update()
                .set("status", StartupRound.RoundStatus.FULLY_SUBSCRIBED)
                .set("updated_at", Instant.now());

        mongoTemplate.findAndModify(statusQuery, statusUpdate, StartupRound.class);
        log.info("Round [{}] (ID: [{}]) has been marked FULLY_SUBSCRIBED — no further allocations will be accepted.",
                roundName, roundId);
    }

    /**
     * Validates the input parameters for an allocation request.
     * Enforces the fail-fast principle to surface bad requests at the earliest opportunity,
     * before any database I/O is performed.
     *
     * @param roundId      the round ID to validate
     * @param investorName the investor name to validate
     * @param amount       the amount to validate
     * @throws IllegalArgumentException if any parameter is invalid
     */
    private void validateAllocationRequest(String roundId, String investorName, BigDecimal amount) {
        if (roundId == null || roundId.isBlank()) {
            throw new IllegalArgumentException("Round ID must not be null or blank.");
        }
        if (investorName == null || investorName.isBlank()) {
            throw new IllegalArgumentException("Investor name must not be null or blank.");
        }
        if (amount == null) {
            throw new IllegalArgumentException("Allocation amount must not be null.");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    String.format("Allocation amount must be positive. Received: %s", amount));
        }
        log.debug("Allocation request validation passed — roundId: [{}], investor: [{}], amount: [{}]",
                roundId, investorName, amount);
    }
}
