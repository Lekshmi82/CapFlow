package com.tactos.ledger.repository;

import com.tactos.ledger.model.StartupRound;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data MongoDB repository for {@link StartupRound} documents.
 *
 * <p>This interface follows the Repository pattern (DDD), providing a clean
 * abstraction over the MongoDB persistence layer. Spring Data generates the
 * implementation at runtime via its dynamic proxy infrastructure.
 *
 * <p>For the core allocation logic, this repository is intentionally bypassed
 * in favor of {@link org.springframework.data.mongodb.core.MongoTemplate#findAndModify},
 * which provides the atomic read-modify-write semantics required for
 * concurrent allocation safety. This repository handles all non-critical
 * read paths and administrative write operations.
 *
 * @author TactosLedger Engineering
 * @see com.tactos.ledger.service.AllocationService
 */
@Repository
public interface AllocationRepository extends MongoRepository<StartupRound, String> {

    /**
     * Finds a funding round by its human-readable name.
     * Used for deduplication checks at round creation time.
     *
     * @param roundName the name of the round to search for
     * @return an Optional containing the round if found, empty otherwise
     */
    Optional<StartupRound> findByRoundName(String roundName);

    /**
     * Retrieves all funding rounds associated with a specific company.
     * Supports the portfolio overview screen in the React frontend.
     *
     * @param companyName the name of the startup company
     * @return a list of rounds for the company, potentially empty
     */
    List<StartupRound> findByCompanyName(String companyName);

    /**
     * Retrieves all rounds currently in a given lifecycle status.
     * Used for dashboard aggregation (e.g., count of OPEN rounds).
     *
     * @param status the target lifecycle status
     * @return all rounds matching the given status
     */
    List<StartupRound> findByStatus(StartupRound.RoundStatus status);

    /**
     * Finds rounds where the remaining allocation falls below a defined threshold.
     * Useful for triggering operational alerts when a round is nearly fully subscribed.
     *
     * <p>Uses a raw MongoDB query for clarity and to avoid Spring Data
     * method name parsing ambiguity with BigDecimal comparisons.
     *
     * @param threshold the alert threshold; rounds with remaining allocation
     *                  below this value will be returned
     * @return a list of rounds approaching full subscription
     */
    @Query("{ 'remaining_allocation': { $lt: ?0 }, 'status': 'OPEN' }")
    List<StartupRound> findRoundsApproachingFullSubscription(BigDecimal threshold);

    /**
     * Checks whether a round with the given name already exists.
     * Supports idempotent round creation logic in the service layer.
     *
     * @param roundName the round name to check
     * @return true if a round with this name exists, false otherwise
     */
    boolean existsByRoundName(String roundName);
}
