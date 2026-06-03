# TactosLedger

> **Concurrent-safe startup funding allocation ledger**  
> Built to prevent over-subscription in high-throughput, parallel back-office environments.

---

## The Problem This Solves

In a private wealth / venture studio context, multiple back-office ops managers or parallel banking API webhooks may attempt to commit allocations against a startup's remaining funding round **at the exact same millisecond**.

A naive "read balance → check → write" approach creates a **race condition**: if two requests both read `$500,000 remaining` and both commit `$400,000`, the round becomes over-subscribed by `$300,000` — a critical financial integrity failure.

**TactosLedger eliminates this entirely.**

---

## Solution Architecture

### Primary Defense: Atomic `findAndModify`

The core of the solution is MongoDB's `findAndModify` operation, executed via `MongoTemplate`. It combines:

1. **Condition check** — `remaining_allocation >= requested_amount` AND `status == OPEN`
2. **Decrement** — `$inc: { remaining_allocation: -amount }`
3. **Audit append** — `$push: { allocation_transactions: { ... } }`

...into a **single, indivisible database operation**. MongoDB's document-level write lock guarantees only one concurrent request will match the filter; all others receive `null`, cleanly indicating rejection — no over-subscription possible.

### Secondary Defense: Optimistic Locking

The `@Version` field on `StartupRound` provides a version vector managed by Spring Data. Any non-atomic save path that attempts to write a stale document version is rejected with `OptimisticLockingFailureException`, which the `GlobalExceptionHandler` translates to a **HTTP 409 Conflict**.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.3 |
| Database | MongoDB (via Spring Data + MongoTemplate) |
| Frontend | React.js |
| Build | Maven |
| Utilities | Lombok, SLF4J |

---

## Project Structure

```
src/main/java/com/tactos/ledger/
├── model/
│   ├── StartupRound.java           # MongoDB @Document entity with @Version
│   └── AllocationTransaction.java  # Nested sub-document (audit log entry)
├── repository/
│   └── AllocationRepository.java   # Spring Data MongoRepository interface
├── service/
│   └── AllocationService.java      # Core atomic allocation logic (MongoTemplate)
└── exception/
    ├── GlobalExceptionHandler.java        # @RestControllerAdvice — HTTP error translation
    ├── RoundNotFoundException.java        # 404 — Round does not exist
    └── AllocationOversubscribedException.java  # 409 — Round not OPEN
```

---

## Key Design Decisions

**Why `findAndModify` instead of `@Transactional`?**  
MongoDB multi-document transactions carry significant coordination overhead and require a replica set. `findAndModify` achieves atomic single-document semantics with zero overhead — ideal for this pattern where all relevant state lives in one document.

**Why `BigDecimal` for monetary amounts?**  
`double` and `float` are IEEE 754 floating-point types with well-known precision limitations (`0.1 + 0.2 != 0.3`). `BigDecimal` provides arbitrary-precision arithmetic required for accurate financial calculations.

**Why `@Version` when `findAndModify` already handles concurrency?**  
Defense in depth. `findAndModify` covers the hot path. `@Version` ensures that any future developer who adds a naive `repository.save()` call elsewhere cannot introduce a silent race condition.

**Why a dedicated `GlobalExceptionHandler`?**  
Separates error-handling from business logic (SRP). Guarantees consistent, sanitized error payloads — internal stack traces and MongoDB field names are never exposed to the client.

---

## Running Locally

```bash
# Prerequisites: Java 17+, Maven, MongoDB running on localhost:27017

git clone https://github.com/your-username/tactos-ledger.git
cd tactos-ledger

# Start the backend
./mvnw spring-boot:run

# Or with a custom MongoDB URI
MONGODB_URI=mongodb://localhost:27017/tactos_ledger ./mvnw spring-boot:run
```

The API will be available at `http://localhost:8080`.

---

## Example API Usage

```http
POST /api/v1/rounds/{roundId}/allocate
Content-Type: application/json

{
  "investorName": "Sequoia Capital",
  "amount": "150000.00"
}
```

**Success (200 OK):**
```json
{ "success": true, "transactionId": "a3f1c2d4-..." }
```

**Rejected — Insufficient balance (409 Conflict):**
```json
{
  "status": 409,
  "error": "ALLOCATION_REJECTED",
  "message": "Round 'Series A' is not accepting allocations. Current status: FULLY_SUBSCRIBED",
  "resolution": "This round is no longer accepting allocations. Please verify the round status in the dashboard.",
  "path": "/api/v1/rounds/abc123/allocate",
  "timestamp": "2024-07-15T10:30:00.123Z"
}
```
