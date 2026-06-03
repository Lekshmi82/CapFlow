package com.tactos.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Field;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable value object representing a single confirmed allocation transaction.
 *
 * <p>Instances of this class are stored as nested sub-documents within the
 * {@link StartupRound#getAllocationTransactions()} list. They are never updated
 * after creation — this is an append-only audit trail, providing a full,
 * tamper-evident history of capital deployment for compliance and back-office use.
 *
 * <p>Each transaction is assigned a unique {@code transactionId} (UUID) at creation
 * time to support idempotency checks and cross-system reconciliation.
 *
 * @author TactosLedger Engineering
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AllocationTransaction {

    /**
     * Globally unique identifier for this transaction.
     * Auto-generated as a UUID string at construction time.
     * Useful for idempotency enforcement and external audit correlation.
     */
    @Builder.Default
    @Field("transaction_id")
    private String transactionId = UUID.randomUUID().toString();

    /**
     * The canonical name of the investing entity (individual or institution).
     * Must be non-null and non-blank; validated at the service layer before persistence.
     */
    @Field("investor_name")
    private String investorName;

    /**
     * The exact capital amount committed by the investor in this transaction.
     *
     * <p>Uses {@link BigDecimal} to guarantee arbitrary precision arithmetic,
     * completely avoiding the floating-point rounding errors that plague
     * {@code double}-based financial calculations (e.g., the classic
     * {@code 0.1 + 0.2 != 0.3} problem).
     *
     * <p>Stored as a Decimal128 type in MongoDB for full precision on the wire.
     */
    @Field("amount")
    private BigDecimal amount;

    /**
     * UTC timestamp of when this allocation was atomically committed to the database.
     * Captured server-side in the service layer to ensure consistency regardless
     * of client clock drift.
     */
    @Field("timestamp")
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * The source channel through which this transaction was submitted.
     * Useful for distinguishing manual ops entries from webhook-driven API events.
     * e.g., "MANUAL_OPS", "BANKING_WEBHOOK", "INTERNAL_TRANSFER"
     */
    @Field("source_channel")
    @Builder.Default
    private String sourceChannel = "MANUAL_OPS";

    /**
     * Factory method providing a clean, readable construction API
     * for the most common case (manual ops entry).
     *
     * @param investorName the name of the investor
     * @param amount       the capital amount being allocated
     * @return a new, fully initialized AllocationTransaction
     */
    public static AllocationTransaction of(String investorName, BigDecimal amount) {
        return AllocationTransaction.builder()
                .investorName(investorName)
                .amount(amount)
                .timestamp(Instant.now())
                .sourceChannel("MANUAL_OPS")
                .build();
    }

    /**
     * Factory method for webhook-originated transactions.
     *
     * @param investorName the name of the investor
     * @param amount       the capital amount being allocated
     * @return a new AllocationTransaction tagged as a banking webhook event
     */
    public static AllocationTransaction ofWebhook(String investorName, BigDecimal amount) {
        return AllocationTransaction.builder()
                .investorName(investorName)
                .amount(amount)
                .timestamp(Instant.now())
                .sourceChannel("BANKING_WEBHOOK")
                .build();
    }
}
