# Allocation concurrency in PostgreSQL 16 and Spring Boot 3

Research note for issue 05 (`.scratch/design-doc/issues/05-allocation-concurrency-in-postgres-and-spring.md`), feeding tickets 09 (schema) and 10 (allocation serialization). Researched 2026-10-05.

## Question and versions

The SRS asks for two invariants under concurrent submissions: an Exclusive instance is never booked twice for overlapping windows, and a Metered type never exceeds its Pool Capacity in any hour, with Holds counting exactly like allocations. A per-Team Quota has the same shape as the Pool check. When a Request is allocated, its Holds commit and its Charges post in one transaction. The SRS also says that "conflict detection and allocation shall be serialized per Resource Type in the database" (quality attribute `correct`), and that tests "shall run against H2 in memory with the same migrations" (constraint CO `db` and interface SI `db`).

The two invariants have different shapes, and that difference drives most of the choices below.

- **Exclusive** is a *pairwise* rule: no two live rows (Held or Allocated) on the same instance may overlap. A PostgreSQL exclusion constraint states exactly this kind of rule.
- **Metered** is an *aggregate* rule: for every hour h, the sum of units of live lines on the type that overlap h, plus the requested units, must be ≤ Pool Capacity. No PostgreSQL constraint can state a sum over many rows. A CHECK sees only the row being written, and an EXCLUDE compares rows two at a time.[[S2]][[S3]] The application therefore has to serialize the check and the write, or it has to store the sum in rows where a row-local CHECK can see it.

Versions assumed: Spring Boot 3.5 manages Spring Framework 6.2.19, Hibernate ORM 6.6.53, Flyway 11.7.2, Liquibase 4.31.1, H2 2.3.232, the PostgreSQL JDBC driver 42.7.11, Testcontainers 1.21.4, and Spring Retry 2.0.13.[[S41]] That page also points to Spring Boot 4.1 as the current line. This note stays on Boot 3 because the SRS names it.

Claims marked **checked locally** come from two concurrent `psql` sessions against a throwaway PostgreSQL 16.15 cluster, or from the H2 2.3.232 shell. They are observations, not documentation. The scripts are summarized under "Local checks" at the end.

## 1. Range types and an exclusion constraint

**Declaration.**

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE exclusive_occurrence (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  line_id     bigint      NOT NULL REFERENCES request_line,
  resource_id bigint      NOT NULL REFERENCES resource,
  starts_at   timestamptz NOT NULL,
  ends_at     timestamptz NOT NULL,
  state       text        NOT NULL,          -- HELD, ALLOCATED, RELEASED, BUMPED, EXPIRED
  CHECK (starts_at < ends_at),
  CONSTRAINT exclusive_occurrence_no_overlap EXCLUDE USING gist (
    resource_id WITH =,
    tstzrange(starts_at, ends_at, '[)') WITH &&
  ) WHERE (state IN ('HELD', 'ALLOCATED'))
);
```

- **The rule itself.** An exclusion constraint "guarantees that if any two rows are compared on the specified column(s) or expression(s) using the specified operator(s), not all of these comparisons will return TRUE". It is implemented by an index, which in practice means GiST or SP-GiST.[[S3]] The PostgreSQL range-type chapter uses this exact pattern for room reservations: `EXCLUDE USING GIST (room WITH =, during WITH &&)`.[[S1]]
- **`btree_gist`.** The `resource_id WITH =` part needs `btree_gist`, which supplies GiST operator classes for plain scalar types (int8, uuid, text, timestamps and others). The extension is "trusted", so a non-superuser with `CREATE` privilege on the database can install it.[[S1]][[S4]] A Flyway migration running as the application's owner role can therefore create it.
- **Partial exclusion.** `WHERE (predicate)` makes the constraint cover only a subset of rows; "internally this creates a partial index".[[S3]] Released, Bumped and Expired rows stay in the table as history and block nothing. This was checked locally: a `RELEASED` row overlapping a `HELD` row was accepted.
- **Expression instead of a range column.** An exclusion element can be `( expression )`, not only a column.[[S3]] Each range type has a constructor function of the same name, and its two-argument form produces `[)` bounds.[[S1]] Index expressions must use only immutable functions.[[S5]] PostgreSQL accepted `tsrange(starts_at, ends_at, '[)')` and `tstzrange(...)` in the constraint (checked locally). A NULL bound makes a range unbounded on that side, so both columns must be `NOT NULL`.[[S1]]
- **Half-open windows.** With `[)` bounds, back-to-back windows (09:00–12:00 and 12:00–13:00) do not overlap. Checked locally: both were accepted.
- **`tstzrange` or `tsrange`.** PostgreSQL stores all time-zone-aware values internally in UTC.[[S6]] The firm uses one time zone, but a plain `timestamp` holding local wall-clock time repeats an hour at the autumn daylight-saving change, so two different instants would compare equal. This is an inference, not a documented warning. Use `timestamptz` with `tstzrange`, and do Price Band arithmetic in the firm's zone in Java.

**Exclusive guarantee.** The constraint is complete and declarative. Every code path, including a forgotten one, a scheduled job, or hand-written SQL, is checked at the database. If a conflicting row was written by a transaction that has not yet committed, the inserting transaction waits for it and then repeats the check. The PostgreSQL documentation describes this for unique indexes.[[S7]] Checked locally for the exclusion constraint: session B blocked for about 1.5 s on session A's uncommitted overlapping row, then failed with 23P01 when A committed, and succeeded when A rolled back.

**Weekly recurrence.** Store one row per occurrence, all with the same `resource_id`, and insert them in the allocating transaction. If any occurrence overlaps a live row, the statement fails and the transaction rolls back, so nothing is booked. Checked locally: an `INSERT ... SELECT` of three weekly occurrences in which one collided inserted no rows. This is exactly the SRS rule that every occurrence must be free on the same instance.

A one-row-per-line alternative with a `tstzmultirange` column also works with GiST and `&&`.[[S1]] Checked locally, it was rejected on overlap with one occurrence. However, hypersistence-utils ships only a range type, not a multirange type.[[S23]] A partial Release that removes occurrences would also become an update of the multirange. One row per occurrence is simpler, and it matches per-hour pricing and the dashboard.

**Metered guarantee.** None. A sum over many rows is not a pairwise rule (see "Question and versions").

**Hibernate and Spring.**

- *No native range mapping in Hibernate 6.* The 6.6 `SqlTypes` constants include JSON, INET, INTERVAL_SECOND, GEOMETRY, arrays and vectors, but no range type.[[S19]] The 6.6 PostgreSQL dialect has no range handling.[[S20]] `@JdbcTypeCode` only selects a `JdbcType` that Hibernate already has.[[S17]] Three options exist:
  1. *(recommended)* Map two `Instant` fields and leave the range inside the constraint expression. Hibernate maps `Instant` to `TIMESTAMP_UTC`, which the PostgreSQL dialect renders as `timestamp with time zone`.[[S17]][[S20]] The table then holds only ordinary columns, `ddl-auto=validate` has nothing unusual to check, and Java code never handles a range object.
  2. Use hypersistence-utils' `PostgreSQLRangeType`: `@Type(PostgreSQLRangeType.class) Range<ZonedDateTime> during` maps to `tstzrange`, and `Range<LocalDateTime>` maps to `tsrange`. The artifact for Hibernate 6.3 to 6.6 is `io.hypersistence:hypersistence-utils-hibernate-63`.[[S22]][[S23]]
  3. Write a custom `org.hibernate.usertype.UserType` and apply it with `@Type`.[[S17]]
- *Flush order breaks the Bump.* Hibernate does not run SQL in the order the code changed entities. Its action queue runs inserts before updates.[[S17]] A Bump changes the old rows to `BUMPED` and persists the Priority row. If both changes are flushed together, the INSERT reaches a NOT DEFERRABLE constraint first and fails. Checked locally: that order fails with an immediate constraint and succeeds with `DEFERRABLE INITIALLY DEFERRED`. Exclusion constraints accept `DEFERRABLE`.[[S3]] There are two fixes:
  - call `entityManager.flush()`, or run a JPQL bulk `update`, after the state change and before the persist; or
  - declare the constraint deferred and accept that the error then appears at commit.

  The first fix keeps the failure inside the method body.
- *A violation ends the transaction.* After any exception from the EntityManager, Hibernate says to "immediately rollback the database transaction and close the current EntityManager".[[S17]] `JpaTransactionManager` notes that "JPA itself does not support nested transactions".[[S31]] After a failed insert, the same transaction cannot go on to "try the next instance". Instance choice must therefore happen before the insert, under a lock (Section 2). Alternatively, the whole transaction is retried.

**Failure the application sees.** SQLSTATE `23P01 exclusion_violation`.[[S14]] Hibernate's SQLState conversion turns any class-23 state into `org.hibernate.exception.ConstraintViolationException`.[[S21]] Spring translates persistence exceptions into its `DataAccessException` hierarchy for `@Repository` beans and at transaction boundaries.[[S28]] Its `HibernateJpaDialect` turns this one into `DataIntegrityViolationException`, and the message includes the constraint name.[[S30]] If the constraint is deferred, the error comes from `JpaTransactionManager.doCommit`, which applies the same translation, so it is thrown by the `@Transactional` proxy after the method body has returned.[[S31]] PostgreSQL says 23P01 can be retried in some cases but "might represent persistent error conditions".[[S12]]

**Cost.** One extension and one GiST index. Maintenance is negligible at 200 resources and about 10,000 requests a year. None of it runs on H2 (Section 8).

## 2. `SELECT ... FOR UPDATE` on the Resource Type row

**Declaration.**

```java
public interface ResourceTypeRepository extends JpaRepository<ResourceType, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ResourceType t where t.id = :id")
    Optional<ResourceType> lockById(@Param("id") Long id);          // select ... for update
}

@Transactional   // ISOLATION_DEFAULT, which is READ COMMITTED on PostgreSQL
public AllocationOutcome allocate(long requestId) {
    List<Long> typeIds = lines.typeIdsOf(requestId).stream().distinct().sorted().toList();
    typeIds.forEach(id -> resourceTypes.lockById(id).orElseThrow()); // ascending ids, before any capacity read
    // check every line, insert Holds or allocations, post Charges
}
```

- **The lock.** Spring Data JPA applies a lock mode to a query method through `@Lock`.[[S24]] Hibernate maps `PESSIMISTIC_WRITE` to an explicit database lock.[[S17]] The PostgreSQL dialect renders it as `for update`, or `for update nowait` / `skip locked` for the two special timeout values.[[S20]]
- **What it blocks.** `FOR UPDATE` blocks other transactions that `UPDATE`, `DELETE` or `SELECT ... FOR UPDATE/SHARE` the same row until the holder ends. It does not block plain `SELECT`s.[[S8]]

**Exclusive guarantee.** The lock is complete for every path that takes it. All allocation on the type becomes serial, so choosing "the free instance with the fewest allocated hours" and inserting it cannot race with another transaction. The exclusion constraint stays in place as a backstop, and a 23P01 then signals a bug, not normal control flow.

Locking the Resource row instead of the type row would serialize per instance, but only after the instance is chosen. Choosing among instances needs the type-level lock, and that matches the SRS wording ("serialized per Resource Type").

**Metered guarantee.** The lock is complete, with one condition: the transaction must run at READ COMMITTED, and the hourly sum must be read by a statement that runs after the lock statement. At READ COMMITTED each statement sees rows committed before that statement began. A `SELECT FOR UPDATE` that waited returns the updated row.[[S10]]

Under REPEATABLE READ "a repeatable read transaction's snapshot is actually frozen at the start of its first query".[[S11]] When that first query is the lock statement itself, the snapshot is older than the commit the transaction just waited for. Checked locally, with two sessions each adding 5 units to a pool of 8:

- READ COMMITTED plus the lock: the second session saw the first session's row and refused (5 units total).
- REPEATABLE READ plus the same lock: both sessions passed the check, and the pool was oversold at 10 of 8.
- SERIALIZABLE plus the lock: the second session waited and then failed with 40001, so this combination pays the cost of both mechanisms.

Spring's default isolation is `ISOLATION_DEFAULT`[[S25]], and PostgreSQL's default is Read Committed.[[S10]] The rule for the design is: do not raise the isolation level on this path.

**Hibernate and Spring.**

- *Stale entities.* The Hibernate Session "acts as a transaction-scoped cache providing repeatable reads for lookup by identifier and queries that result in loading entities".[[S17]] Take the lock as the first statement in the transaction, compute sums with aggregate queries that return scalars, and do not reuse booking entities loaded before the lock.
- *Lock timeouts.* The JPA hint `jakarta.persistence.lock.timeout` is ignored when the dialect cannot honor it.[[S17]] Hibernate 6.6's PostgreSQL dialect uses only the values for `nowait` and `skip locked` and drops any other value.[[S20]] For a bounded wait, set PostgreSQL's `lock_timeout` (for example `SET LOCAL lock_timeout = '5s'`), which aborts a statement that waits too long for a row lock.[[S15]]
- *Convention, not enforcement.* The lock binds only code that takes it. Every path that consumes capacity or reduces it must call the same helper: place a Hold, allocate, Bump, promote from the waitlist, reduce Pool Capacity or retire a Resource (`Inventory.Pool.Reduce`). Paths that only free capacity (Release, Hold expiry) are safe without the lock, but they take it anyway if they then promote waitlisted lines.

**Several types in one transaction.** PostgreSQL detects deadlocks and aborts one of the transactions involved. "The best defense against deadlocks is generally to avoid them by being certain that all applications using a database acquire locks on multiple objects in a consistent order."[[S8]] A Request that touches several types therefore locks them in ascending id order, one statement per id from Java, as in the snippet.

The single-statement form `... where id in (:ids) order by id for update` also sorts before locking. However, the SELECT documentation warns that at READ COMMITTED, `ORDER BY` with a locking clause can return rows out of order when the ordering columns change. Ids do not change, so this is safe in practice, but the per-id loop makes the order obvious.[[S9]]

**Failure the application sees.** Normally none: the second transaction waits. The possible failures are:

| Failure | SQLSTATE | Hibernate exception | Spring exception |
| --- | --- | --- | --- |
| Deadlock (mis-ordered locks) | `40P01` | `LockAcquisitionException`[[S20]] | `CannotAcquireLockException`, a `PessimisticLockingFailureException`[[S30]] |
| `lock_timeout` reached | `55P03` | `PessimisticLockException`[[S20]] | `PessimisticLockingFailureException`[[S30]] |

**Cost.** One row lock per type for the life of the allocation transaction, which includes ledger posting. All allocation on a type is serial. At 10,000 requests a year that is negligible.

## 3. `pg_advisory_xact_lock` keyed by Resource Type id

**Declaration.**

```java
@Query(value = "select 1 from pg_advisory_xact_lock(:key)", nativeQuery = true)
Integer lockTypeForAllocation(@Param("key") long resourceTypeId);
```

- **The function.** `pg_advisory_xact_lock(bigint)` and `pg_advisory_xact_lock(int, int)` obtain "an exclusive transaction-level advisory lock, waiting if necessary". They return `void`, and the one-key and two-key spaces do not overlap.[[S13]] Transaction-level advisory locks are "automatically released at the end of the transaction" and have no explicit unlock.[[S8]]
- **Why `select 1 from`.** Selecting a constant from the function, instead of selecting the `void` result, keeps JPA from having to map a `void` column. Checked locally: the statement returns `1`, and `pg_locks` shows an `advisory` `ExclusiveLock` until commit.
- **Plain JDBC.** Plain JDBC (`JdbcClient` or `JdbcTemplate`) on the same `DataSource` also works. Spring lets `JpaTransactionManager` "expose a JPA transaction to JDBC access code that accesses the same DataSource" when the JPA dialect can hand over the connection, which the Hibernate dialect does.[[S29]]

**Exclusive and Metered guarantees.** These are the same as Section 2, with the same READ COMMITTED condition. Checked locally:

- READ COMMITTED plus the advisory lock refused the second 5-unit line.
- REPEATABLE READ plus the advisory lock oversold the pool to 10 of 8.

The calling `SELECT` is itself the first query, so it freezes the snapshot before it waits.

**Differences from the row lock.**

- An advisory lock needs no row. It can guard a type that is being created in the same transaction.
- Its meaning is "application-defined"[[S8]], so it is invisible in the schema, and a reader of the data model cannot see what it protects.
- Advisory locks share the lock table sized by `max_locks_per_transaction` and `max_connections`.[[S8]]
- The documentation warns that with `LIMIT`, the lock function may run on more rows than are returned.[[S8]]

Flyway itself uses a transactional advisory lock on PostgreSQL to serialize migrations, so the mechanism is well exercised.[[S42]]

**Hibernate and Spring.** There is no JPA API for advisory locks, so this is native SQL. When run through the EntityManager, a native query always triggers a flush first.[[S17]] That is harmless when the lock is the first statement.

**Failure the application sees.** The same as Section 2: a wait, then `40P01` if the lock order is wrong, or `55P03` with `lock_timeout`.

**Cost.** The cheapest lock in code and in the database. It costs a little clarity compared with the row lock, and it cannot run on H2 (Section 8).

## 4. `SERIALIZABLE` isolation with retry on 40001

**Declaration.**

```java
@Configuration @EnableRetry class RetryConfig { }   // needs spring-retry and spring-boot-starter-aop

@Retryable(retryFor = PessimisticLockingFailureException.class,   // 40001 and 40P01 after translation
           maxAttempts = 5, backoff = @Backoff(delay = 50, maxDelay = 500, random = true))
@Transactional(isolation = Isolation.SERIALIZABLE)
public AllocationOutcome allocate(long requestId) { /* check, then write */ }
```

- **Spring Retry setup.** `@EnableRetry` plus the AOP starter is the documented setup.[[S35]] Spring Retry defaults to three attempts.[[S35]]
- **Retry wraps the transaction.** `@EnableRetry`'s advice order defaults to `Ordered.LOWEST_PRECEDENCE - 1` "to make sure the advice is applied before other advices with `Ordered#LOWEST_PRECEDENCE` order (e.g. an advice responsible for `@Transactional` behavior)". The retry therefore wraps the transaction even when both annotations sit on the same method.[[S36]]
- **Project status.** The Spring Retry README now opens with "Spring Retry is no longer maintained as an open-source project" and says it "has been superseded by Spring Framework 7".[[S35]] Framework 7.0 has `@Retryable` and `@EnableResilientMethods` in core.[[S34]] Boot 3.5 still manages Spring Retry 2.0.13.[[S41]] On Boot 3 the choices are therefore the archived library or a hand-written loop around a `TransactionTemplate`.
- **The isolation level must start the transaction.** The `isolation` attribute "applies only to propagation values of REQUIRED or REQUIRES_NEW".[[S25]] A method that joins an existing transaction "silently ignor[es] the local isolation level" unless the transaction manager's `validateExistingTransaction` is set.[[S26]]

**Metered guarantee.** Full, and this is the one mechanism that catches write skew without a lock. Serializable "emulates serial transaction execution for all committed transactions".[[S10]] The documentation's own example is the Metered pattern: transaction A sums `value` for `class = 1` and inserts into class 2, while B does the reverse. Under Serializable, one commits and the other is rolled back with "could not serialize access due to read/write dependencies among transactions".[[S10]] Checked locally: two sessions each adding 5 units to a pool of 8 with no lock gave one commit and one 40001 ("Canceled on identification as a pivot, during write").

The guarantee needs every writer to be serializable: "If the Serializable transaction isolation level is used for all writes and for all reads which need a consistent view of the data, no other effort is required".[[S11]] A single READ COMMITTED code path that writes bookings reopens the hole.

**Exclusive guarantee.** Full under the same condition. The exclusion constraint is still the cheaper and clearer guard for that case.

**False positives and cost.**

- Serializable "doesn't always prevent errors from being raised that would not occur in true serial execution".[[S10]]
- "A sequential scan will always necessitate a relation-level predicate lock", which raises the failure rate.[[S10]] Checked locally on near-empty tables: two transactions on *different* Metered types still produced one 40001.
- At OpenBench's scale the planner will often scan small tables sequentially, so retries will be routine, not rare. This is an inference from the documentation and the local check.
- Every read in the transaction becomes part of the conflict graph, including pricing and ledger reads.

**Retry rules.** PostgreSQL says to retry `40001` and `40P01` unconditionally, and to retry "the complete transaction, including all logic that decides which SQL to issue and/or which values to use".[[S12]] Anything non-transactional done inside the method, such as sending a notification, would repeat on retry, so it must happen after commit.

**Hibernate and Spring.**

- 40001 can surface at a flush inside the method or at commit, after the method returns. The retry must therefore wrap the transactional proxy, not run inside it.
- Hibernate converts 40001 to `LockAcquisitionException`[[S21]], which Spring translates to `CannotAcquireLockException`.[[S30]] At commit, `JpaTransactionManager` translates through the dialect.[[S31]] Spring's SQLState fallback maps class 40 to `PessimisticLockingFailureException`, and 40001 specifically to `CannotAcquireLockException`.[[S32]] Spring's PostgreSQL error-code table lists 40001 under `cannotSerializeTransactionCodes`.[[S33]]
- On rollback, `JpaTransactionManager` clears an EntityManager that was bound outside the transaction ("to avoid inconsistent state").[[S31]] Boot binds one per web request by default ("Open EntityManager in View"). Setting `spring.jpa.open-in-view=false` keeps each attempt clean.[[S38]]

**Failure the application sees.** `PessimisticLockingFailureException` after the configured attempts are used up. Exclusion and CHECK violations stay `DataIntegrityViolationException`.

## 5. A single-writer application queue

**Declaration.**

```java
private final ExecutorService allocator = Executors.newSingleThreadExecutor();

public AllocationOutcome submit(long requestId) throws Exception {
    return allocator.submit(() -> allocationService.allocate(requestId))   // allocate() is @Transactional
                    .get(30, TimeUnit.SECONDS);
}
```

`newSingleThreadExecutor()` guarantees that "tasks are guaranteed to execute sequentially, and no more than one task will be active at any given time".[[S56]] Spring transactions are thread-bound and do "not propagate to newly started threads". The transaction must therefore begin inside the task, on the executor thread, by calling a `@Transactional` bean method.[[S27]]

**Exclusive and Metered guarantees.** Full for every path routed through the queue, inside one JVM, at any isolation level. It also works on H2, because there is nothing for the database to arbitrate.

**What is lost.** The database stops being the arbiter, and the guarantee holds only while all of these stay true:

- the process is the only writer: no second instance during a redeploy, and no administrative SQL;
- every capacity-changing path is routed through the queue, including the scheduled jobs for Hold expiry and Objection Window lapse, waitlist promotion, and Pool Capacity changes.

The SRS currently states one process with no high availability, so the first condition holds today. But the SRS's own correctness attribute says allocation is serialized "in the database". A queue alone contradicts that wording, and it leaves nothing behind that a later reader of the schema can see.

**Failure the application sees.** No race failures. Instead there are queue timeouts and backlog. A crash loses only work that had not committed.

**Cost.** The least code and the simplest tests. It serializes all types, not one type at a time, which is irrelevant at this load. It combines well with the exclusion constraint as a database backstop.

## 6. The Metered hourly sum: hour buckets or a computed sum

### 6a. Computed at check time under the type lock

```sql
-- after lockById(typeId); windows assumed to start and end on the hour
SELECT h.hour_start, coalesce(sum(b.units), 0) AS used
FROM generate_series(:starts_at, :ends_at - interval '1 hour', interval '1 hour') AS h(hour_start)
LEFT JOIN metered_line_booking b
       ON b.resource_type_id = :type_id
      AND b.state IN ('HELD', 'ALLOCATED')
      AND tstzrange(b.starts_at, b.ends_at, '[)') && tstzrange(h.hour_start, h.hour_start + interval '1 hour', '[)')
GROUP BY h.hour_start
HAVING coalesce(sum(b.units), 0) + :units > :pool_capacity
ORDER BY h.hour_start
LIMIT 1;        -- no row: the line fits; one row: the earliest failing hour and, from used, the shortfall
```

- **What the query uses.** `generate_series(timestamptz, timestamptz, interval)` produces the hours.[[S16]] A partial GiST index on `(resource_type_id, tstzrange(starts_at, ends_at, '[)')) WHERE state IN ('HELD','ALLOCATED')` serves the overlap test; GiST range indexes support `&&`.[[S1]]
- **Quota.** The Quota check is the same query with `AND b.team_id = :team_id` and the Quota in place of Pool Capacity.
- **Inventory reductions.** `Inventory.Pool.Reduce` needs "the earliest such hour and the shortfall", and this query returns it directly.
- **Correctness.** It holds only under the type lock at READ COMMITTED (Section 2, checked locally). Pool Capacity (the sum of unretired units) is read in the same transaction, after the lock.
- **Hibernate.** It runs as a native query, because JPQL has no `generate_series` or range operators.

### 6b. An hour-bucket table with a CHECK

```sql
CREATE TABLE metered_hour (
  resource_type_id bigint      NOT NULL REFERENCES resource_type,
  hour_start       timestamptz NOT NULL,
  capacity         integer     NOT NULL,          -- copy of Pool Capacity: a CHECK sees only this row
  used             integer     NOT NULL DEFAULT 0, -- allocated + held units
  PRIMARY KEY (resource_type_id, hour_start),
  CONSTRAINT metered_hour_within_capacity CHECK (used >= 0 AND used <= capacity)
);
INSERT INTO metered_hour (resource_type_id, hour_start, capacity)
SELECT :type_id, h, :pool_capacity
FROM generate_series(:starts_at, :ends_at - interval '1 hour', interval '1 hour') AS h
ON CONFLICT (resource_type_id, hour_start) DO NOTHING;
UPDATE metered_hour SET used = used + :units
WHERE resource_type_id = :type_id AND hour_start >= :starts_at AND hour_start < :ends_at;
```

**How CHECK constraints and row locks interact.**

- **The CHECK sees one row.** PostgreSQL "does not support CHECK constraints that reference table data other than the new or updated row being checked". It examines CHECKs "only when rows are inserted or updated".[[S2]] CHECK expressions "cannot contain subqueries", and CHECK constraints cannot be deferred.[[S3]] Capacity (and, in a second `(type, team, hour)` table, the Quota) must therefore be copied into each bucket row.
- **Row locks serialize the updates.** The `UPDATE` row-locks each bucket. At READ COMMITTED, a concurrent updater waits, then "attempt[s] to apply its operation to the updated version of the row".[[S10]] The CHECK is then evaluated on that new version.
- **Checked locally.** The second session waited for the first, then failed with `23514`: `new row for relation "metered_hour" violates check constraint`, failing row `(2, 2026-12-01 09:00, 8, 10)`. Lowering `capacity` to 4 under a booked 5 failed the same way, so the CHECK also enforces the "refuse a reduction below what is booked" rule.

**What the buckets cost.**

- **A second copy of the truth.** The buckets duplicate the booking rows and must be adjusted on every Hold, allocation, partial Release, Bump, expiry and capacity change. A missed adjustment silently drifts.
- **No earliest hour.** The CHECK error names one failing row, not the earliest one, so the SRS's "earliest hour and shortfall" still needs a query.
- **Deadlock risk.** The lock order of a multi-row `UPDATE` is not documented, so two overlapping windows can deadlock (40P01). This is avoided by taking the type lock first, which makes the buckets a backstop and not the serializer.
- **Abort on violation.** As with the exclusion constraint, a violation aborts the transaction, and JPA cannot roll back to a savepoint.[[S31]] The "doesn't fit, so join the waitlist" decision therefore still needs the query from 6a beforehand.
- **Size.** One type needs 8,760 rows per year.

**Verdict.** Use the computed sum under the type lock (6a). Add buckets only if the design document wants the database itself to reject an oversell from a code path that skipped the lock. That is the one thing 6b gives that 6a does not.

## 7. Migrations at start-up: Flyway or Liquibase

| | Flyway | Liquibase |
| --- | --- | --- |
| Classpath (Boot 3.5) | `org.flywaydb:flyway-core`, plus a database module such as `org.flywaydb:flyway-database-postgresql` for PostgreSQL[[S37]][[S42]] | `org.liquibase:liquibase-core`[[S37]] |
| Script location | `classpath:db/migration`, files `V<VERSION>__<NAME>.sql`[[S37]] | `db/changelog/db.changelog-master.yaml` by default; YAML, XML, JSON or formatted SQL[[S37]] |
| Database-specific scripts | `spring.flyway.locations=classpath:db/migration/{vendor}` selects a folder per database[[S37]] | `--changeset author:id dbms:postgresql` (or `!h2`) per change set[[S43]] |
| Test-only data | `src/test/resources/db/migration/V9999__test-data.sql` runs only in tests[[S37]] | contexts, for example `context:@test`[[S37]] |
| PostgreSQL notes | runs migrations under a transactional advisory lock, which conflicts with `CREATE INDEX CONCURRENTLY` (set `flyway.postgresql.transactional.lock=false` if needed)[[S42]] | |

- **Running order.** Boot detects `Flyway`, `FlywayMigrationInitializer` and `SpringLiquibase` as database initializers. The `EntityManagerFactory` is a bean that "depends upon database initialization", so migrations run before Hibernate starts.[[S37]]
- **`ddl-auto`.** It defaults to `create-drop` only for an embedded database with no schema manager. "In all other cases, it defaults to `none`."[[S37]] Set `spring.jpa.hibernate.ddl-auto=validate` explicitly. Hibernate's `VALIDATE` action is "Validate the database schema".[[S18]] It compares the mapping with tables and columns. Hibernate has no annotation for an exclusion constraint, so nothing in the mapping refers to it, and the migrations alone own it (inference).
- **Choice.** Use Flyway. The critical DDL (`CREATE EXTENSION`, `EXCLUDE`, partial GiST index) is PostgreSQL SQL in any case, so Liquibase's database-neutral change types buy nothing, and plain versioned SQL is easier to review in the design document.

## 8. H2 versus PostgreSQL for tests

**What H2's documentation says.**

- **Compatibility modes.** H2's compatibility modes emulate "only a small subset of the differences between databases".[[S44]] The PostgreSQL-mode list (`LIMIT/OFFSET`, `SERIAL`, `ON CONFLICT DO NOTHING`, and a few function quirks) mentions no range types, exclusion constraints, extensions or advisory locks.[[S44]]
- **Data types.** H2's data-type list (character, binary, numeric, date/time, `INTERVAL`, `JAVA_OBJECT`, `ENUM`, `GEOMETRY`, `JSON`, `UUID`, `ARRAY`, `ROW`) has no range type.[[S45]]
- **Constraints.** A table constraint is `PRIMARY KEY`, `UNIQUE`, referential, or `CHECK`; there is no `EXCLUDE`.[[S46]] H2's CHECK "can reference objects that exist while the statement is executed", which is a different rule from PostgreSQL's row-only CHECK.[[S46]]
- **Serializable.** H2's Serializable "currently doesn't ensure equivalence of concurrent and serializable execution of transactions that perform write operations".[[S47]]
- **What H2 does support.** `SELECT ... FOR UPDATE`, with NOWAIT and SKIP LOCKED.[[S48]]

**Checked locally on H2 2.3.232** with `MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH`, as H2 recommends for PostgreSQL mode:

| Statement | H2 result |
| --- | --- |
| `CREATE EXTENSION IF NOT EXISTS btree_gist` | syntax error 42001 |
| column of type `tsrange` | `Unknown data type: "TSRANGE"` (50004) |
| `CONSTRAINT ... EXCLUDE USING gist (...) WHERE (...)` | syntax error 42001 |
| `SELECT pg_advisory_xact_lock(2)` | `Function "pg_advisory_xact_lock" not found` (90022) |
| `tsrange(a, b, '[)')` | function not found (90022) |
| `generate_series(ts, ts, interval '1 hour')` | `Cannot parse "INTERVAL" constant "1 hour"` |
| `INSERT ... ON CONFLICT (cols) DO NOTHING` | syntax error (only the target-less form is accepted) |
| a column named `hour` | syntax error (reserved word in H2) |
| `SELECT ... FOR UPDATE` | accepted |

**Verdict.** H2 cannot run the migrations that declare these guarantees. It cannot run the native SQL of the computed-sum check. It cannot reproduce PostgreSQL's concurrency behavior: its Serializable is documented as incomplete, and nothing in its documentation says its READ COMMITTED lock-then-read behaves like PostgreSQL's.

There are only two ways to keep H2:

- *Fork the migrations* (Flyway `{vendor}` folders or Liquibase `dbms:` filters). That breaks "the same migrations" in substance, and the guarantee goes untested.
- *Restrict the schema to H2's subset.* That gives up the exclusion constraint and the computed check.

Either way, the tests that matter most (two concurrent allocations on one type) cannot run on H2. CO `db` and SI `db` in the SRS should change from H2 to PostgreSQL 16.

**Alternatives.**

| | Testcontainers PostgreSQL | embedded-postgres (zonky) |
| --- | --- | --- |
| What runs | the real `postgres:16` image in Docker; the official image builds and installs `contrib`, which includes `btree_gist`[[S52]] | native PostgreSQL binaries started from the test JVM, with "no external dependencies"[[S53]] |
| Requirement | "a Docker-API compatible container runtime"[[S51]]; Docker Desktop on Mac or Windows, Docker on Linux, or an alternative runtime | none beyond the jar; only amd64 binaries are included by default, so Apple Silicon needs the `darwin-arm64v8` artifact[[S54]] |
| Boot 3 wiring | `@Testcontainers` + `@Container @ServiceConnection static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");` with `spring-boot-testcontainers` as a test dependency[[S39]]; `@ServiceConnection` arrived in Boot 3.1[[S40]]; `org.testcontainers:postgresql` and `junit-jupiter` 1.21.4 are managed by Boot 3.5[[S41]]; or the JDBC URL `jdbc:tc:postgresql:16:///openbench`[[S50]][[S49]] | `embedded-database-spring-test` with `@AutoConfigureEmbeddedDatabase(provider = ZONKY)`; its default provider is Docker[[S55]] |
| Version | any image tag | default PostgreSQL 14; set the binaries BOM to a 16.x release[[S53]] |
| `btree_gist` | in the image | present in the 16.15.0 linux-amd64 artifact on Maven Central[[S57]], checked locally |

Recommendation for tests: Testcontainers with `postgres:16-alpine`, the same Flyway migrations, and one integration test class that runs two allocations concurrently, the way the local checks below do. The SRS already ships the application as a Docker image, so Docker on developer machines is not a new requirement. Plain unit tests of pricing or state machines need no database at all.

## Comparison

| Mechanism | Exclusive (instance × window) | Metered (Σ units per hour ≤ Pool, and Quota) | Complexity | Testable on H2? |
| --- | --- | --- | --- | --- |
| Exclusion constraint, `tstzrange` expression, `btree_gist`, partial on live states | Complete and declarative; holds for every code path | None (a sum is not pairwise) | Low DDL; watch Hibernate's insert-before-update flush order; no range type in Hibernate | No: extension, type and syntax rejected |
| `FOR UPDATE` on Resource Type row, READ COMMITTED | Complete for paths that take it | Complete for paths that take it; **oversells at REPEATABLE READ** (checked) | Low; a convention; ascending lock order | Statement runs, but H2's concurrency semantics are not PostgreSQL's |
| `pg_advisory_xact_lock(type_id)`, READ COMMITTED | Same as the row lock | Same as the row lock, including the REPEATABLE READ trap (checked) | Low; native SQL; invisible in schema | No: function not found |
| SERIALIZABLE + retry on 40001 | Complete if every writer is SERIALIZABLE | Complete: catches the write skew (checked) | Medium: retry wrapper outside the transaction, after-commit side effects, false positives (checked across two types); Spring Retry archived | No: H2 Serializable does not guarantee it |
| Single-writer queue | Complete inside one JVM for queued paths | Same | Lowest code; guarantee lives outside the database; contradicts "serialized in the database" | Yes |
| Hour buckets + CHECK | Not needed (the constraint is better) | Complete per stored hour, if every path maintains the buckets; reduction refusal for free | High: second representation, copied capacity and Quota, lock order | DDL yes; the surrounding PostgreSQL SQL no |
| Computed hourly sum under type lock | n/a | Complete with the lock; gives the earliest failing hour | Low to medium: one native query | No: native range SQL |

## Recommendation

**Exclusive.** One row per occurrence in an `exclusive_occurrence` table:

- map `starts_at` and `ends_at` as `Instant` (`timestamptz`), with no range column;
- add `EXCLUDE USING gist (resource_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&) WHERE (state IN ('HELD','ALLOCATED'))` and `CREATE EXTENSION btree_gist` in a Flyway migration;
- keep the constraint NOT DEFERRABLE, and in the Bump path call `flush()` (or a bulk JPQL update) after marking rows `BUMPED`, before persisting the Priority occurrence.

The constraint is the database's guarantee. The allocator still chooses instances under the type lock below, so a 23P01 means a bug, not a normal outcome.

**Metered, the transaction boundary and the lock.** Use the type-level row lock with the computed hourly sum, and no SERIALIZABLE, buckets or queue. One `@Transactional` method per allocation attempt (`allocate(requestId)` at the default isolation, which is READ COMMITTED on PostgreSQL) does the following, in order:

1. Lock each `resource_type` row the Request touches with `@Lock(PESSIMISTIC_WRITE)`, in ascending id, before any capacity read.
2. Run the native hourly-sum query for Pool Capacity and for Quota.
3. Insert Holds or allocations.
4. Post Charges.
5. Commit.

Every other path that consumes or reduces capacity takes the same lock through the same helper: waitlist promotion, Bump, and Pool Capacity reduction or retirement. Notifications run after commit. Set `spring.jpa.open-in-view=false` and `ddl-auto=validate`. Map `PessimisticLockingFailureException` (deadlock, lock timeout) to a single retry or a 503, since with ordered locks it should not occur.

The same reasoning applies to a Team's credit balance if ticket 11 checks affordability by summing ledger entries: lock the Team's account row in the same transaction, after the type locks.

**One-sentence guarantee for the design document:** "Every allocation decision runs in one PostgreSQL READ COMMITTED transaction that first locks the row of each Resource Type it touches, in ascending id order, so checks and writes for the same type never interleave, and an exclusion constraint additionally makes it impossible for two Held or Allocated occurrences on the same Exclusive instance to overlap." The design document should also record that CO `db` and SI `db` change from H2 to PostgreSQL 16 under Testcontainers, because H2 cannot run these migrations or reproduce this behavior.

## Local checks

These were run on 2026-10-05, on PostgreSQL 16.15 (nixpkgs build, `aarch64-darwin`) with two `psql` sessions. Session A ran its check, slept 2 s, then wrote. Session B started 0.5 s later. Each Metered scenario books 5 units twice against a pool of 8 (two Resources of 4 units).

| Scenario | Outcome |
| --- | --- |
| E1 overlapping `HELD` insert, same instance | 23P01 at once |
| E2 B inserts overlap while A uncommitted; A commits | B waits about 1.5 s, then 23P01 |
| E3 same, A rolls back | B waits, then succeeds |
| E4 three weekly occurrences, one collides | 23P01, no occurrence inserted |
| E5 insert Priority row, then mark old row `BUMPED`, same transaction | fails on NOT DEFERRABLE; succeeds with `DEFERRABLE INITIALLY DEFERRED`; a real overlap then fails at `COMMIT` |
| M1 READ COMMITTED, no lock | both commit: 10 of 8 (oversold) |
| M2 SERIALIZABLE, no lock | A fails 40001; 5 of 8 |
| M3 READ COMMITTED + `FOR UPDATE` on type row | B waits, sees A, refuses; 5 of 8 |
| M4 REPEATABLE READ + `FOR UPDATE` on type row | both commit: 10 of 8 (oversold) |
| M5 READ COMMITTED + `pg_advisory_xact_lock(2)` | B waits, refuses; 5 of 8 |
| M6 REPEATABLE READ + `pg_advisory_xact_lock(2)` | both commit: 10 of 8 (oversold) |
| M7 SERIALIZABLE + `FOR UPDATE` on type row | B waits, then 40001; 5 of 8 |
| M8 SERIALIZABLE, A on type 2 and B on type 3 (no real conflict) | A fails 40001 anyway |
| B1 hour buckets, concurrent `UPDATE used = used + 5` | B waits, then 23514 |
| B2 lower bucket `capacity` to 4 under `used` 5 | 23514 |

## Sources

1. [S1] PostgreSQL 16, 8.17 Range Types (8.17.6 Constructing Ranges, 8.17.9 Indexing, 8.17.10 Constraints on Ranges): <https://www.postgresql.org/docs/16/rangetypes.html>
2. [S2] PostgreSQL 16, 5.4 Constraints (5.4.1 Check Constraints, 5.4.6 Exclusion Constraints): <https://www.postgresql.org/docs/16/ddl-constraints.html>
3. [S3] PostgreSQL 16, CREATE TABLE (EXCLUDE, DEFERRABLE, CHECK): <https://www.postgresql.org/docs/16/sql-createtable.html>
4. [S4] PostgreSQL 16, F.8 btree_gist: <https://www.postgresql.org/docs/16/btree-gist.html>
5. [S5] PostgreSQL 16, CREATE INDEX (immutable functions in index expressions): <https://www.postgresql.org/docs/16/sql-createindex.html>
6. [S6] PostgreSQL 16, 8.5 Date/Time Types: <https://www.postgresql.org/docs/16/datatype-datetime.html>
7. [S7] PostgreSQL 16, 64.5 Index Uniqueness Checks: <https://www.postgresql.org/docs/16/index-unique-checks.html>
8. [S8] PostgreSQL 16, 13.3 Explicit Locking (13.3.2 Row-Level Locks, 13.3.4 Deadlocks, 13.3.5 Advisory Locks): <https://www.postgresql.org/docs/16/explicit-locking.html>
9. [S9] PostgreSQL 16, SELECT, The Locking Clause: <https://www.postgresql.org/docs/16/sql-select.html>
10. [S10] PostgreSQL 16, 13.2 Transaction Isolation: <https://www.postgresql.org/docs/16/transaction-iso.html>
11. [S11] PostgreSQL 16, 13.4 Data Consistency Checks at the Application Level: <https://www.postgresql.org/docs/16/applevel-consistency.html>
12. [S12] PostgreSQL 16, 13.5 Serialization Failure Handling: <https://www.postgresql.org/docs/16/mvcc-serialization-failure-handling.html>
13. [S13] PostgreSQL 16, 9.27.10 Advisory Lock Functions: <https://www.postgresql.org/docs/16/functions-admin.html>
14. [S14] PostgreSQL 16, Appendix A Error Codes: <https://www.postgresql.org/docs/16/errcodes-appendix.html>
15. [S15] PostgreSQL 16, 20.11 Client Connection Defaults (`lock_timeout`): <https://www.postgresql.org/docs/16/runtime-config-client.html>
16. [S16] PostgreSQL 16, 9.25 Set Returning Functions (`generate_series`): <https://www.postgresql.org/docs/16/functions-srf.html>
17. [S17] Hibernate ORM 6.6 User Guide (3.2.22 Instant, 3.2.45 Custom type mapping, 6.16 Exception handling, 7.1.3 AUTO flush on native SQL query, 7.5 Flush operation order, 9.1 Physical Transactions, 11.2–11.4 Pessimistic locking and hints): <https://docs.hibernate.org/orm/6.6/userguide/html_single/>
18. [S18] Hibernate ORM 6.6 Javadoc, `org.hibernate.tool.schema.Action`: <https://docs.hibernate.org/orm/6.6/javadocs/org/hibernate/tool/schema/Action.html>
19. [S19] Hibernate ORM 6.6 source, `SqlTypes.java`: <https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/type/SqlTypes.java>
20. [S20] Hibernate ORM 6.6 source, `PostgreSQLDialect.java`: <https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/dialect/PostgreSQLDialect.java>
21. [S21] Hibernate ORM 6.6 source, `SQLStateConversionDelegate.java`: <https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/exception/internal/SQLStateConversionDelegate.java>
22. [S22] Hypersistence Utils README: <https://github.com/vladmihalcea/hypersistence-utils/blob/master/README.md>
23. [S23] Hypersistence Utils source, `PostgreSQLRangeType.java` (hibernate-63 module): <https://github.com/vladmihalcea/hypersistence-utils/blob/master/hypersistence-utils-hibernate-63/src/main/java/io/hypersistence/utils/hibernate/type/range/PostgreSQLRangeType.java>
24. [S24] Spring Data JPA reference, Locking: <https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html>
25. [S25] Spring Framework reference, Using `@Transactional`: <https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html>
26. [S26] Spring Framework reference, Transaction Propagation: <https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html>
27. [S27] Spring Framework reference, Understanding the Spring Framework's Declarative Transaction Implementation: <https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-decl-explained.html>
28. [S28] Spring Framework reference, DAO Support (exception translation): <https://docs.spring.io/spring-framework/reference/data-access/dao.html>
29. [S29] Spring Framework reference, JPA: <https://docs.spring.io/spring-framework/reference/data-access/orm/jpa.html>
30. [S30] Spring Framework 6.2.x source, `HibernateJpaDialect.java`: <https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-orm/src/main/java/org/springframework/orm/jpa/vendor/HibernateJpaDialect.java>
31. [S31] Spring Framework 6.2.x source and Javadoc, `JpaTransactionManager.java`: <https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-orm/src/main/java/org/springframework/orm/jpa/JpaTransactionManager.java>
32. [S32] Spring Framework 6.2.x source, `SQLStateSQLExceptionTranslator.java`: <https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-jdbc/src/main/java/org/springframework/jdbc/support/SQLStateSQLExceptionTranslator.java>
33. [S33] Spring Framework 6.2.x source, `sql-error-codes.xml`: <https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-jdbc/src/main/resources/org/springframework/jdbc/support/sql-error-codes.xml>
34. [S34] Spring Framework reference, Resilience Features (7.0): <https://docs.spring.io/spring-framework/reference/core/resilience.html>
35. [S35] Spring Retry README: <https://github.com/spring-projects/spring-retry/blob/main/README.md>
36. [S36] Spring Retry source, `EnableRetry.java`: <https://github.com/spring-projects/spring-retry/blob/main/src/main/java/org/springframework/retry/annotation/EnableRetry.java>
37. [S37] Spring Boot 3.5, Database Initialization how-to: <https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html>
38. [S38] Spring Boot 3.5, SQL Databases (Open EntityManager in View): <https://docs.spring.io/spring-boot/3.5/reference/data/sql.html>
39. [S39] Spring Boot 3.5, Testcontainers: <https://docs.spring.io/spring-boot/3.5/reference/testing/testcontainers.html>
40. [S40] Spring Boot 3.1 Release Notes: <https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.1-Release-Notes>
41. [S41] Spring Boot 3.5, Managed Dependency Coordinates: <https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html>
42. [S42] Redgate Flyway documentation, PostgreSQL Database: <https://documentation.red-gate.com/fd/postgresql-database-277579325.html>
43. [S43] Liquibase OSS reference 4.33, `dbms` attribute: <https://docs.liquibase.com/oss/reference-guide-4-33/changelog-attributes/dbms>
44. [S44] H2 documentation, Features: Compatibility and Compatibility Modes: <https://www.h2database.com/html/features.html#compatibility_modes>
45. [S45] H2 documentation, Data Types: <https://www.h2database.com/html/datatypes.html>
46. [S46] H2 documentation, SQL Grammar (Table Constraint Definition): <https://www.h2database.com/html/grammar.html#table_constraint_definition>
47. [S47] H2 documentation, Advanced: Transaction Isolation: <https://www.h2database.com/html/advanced.html#transaction_isolation>
48. [S48] H2 documentation, Commands: SELECT: <https://www.h2database.com/html/commands.html#select>
49. [S49] Testcontainers for Java, Postgres Module: <https://java.testcontainers.org/modules/databases/postgres/>
50. [S50] Testcontainers for Java, JDBC support: <https://java.testcontainers.org/modules/databases/jdbc/>
51. [S51] Testcontainers for Java, Supported Docker environment: <https://java.testcontainers.org/supported_docker_environment/>
52. [S52] Docker official image `postgres`, 16 Alpine Dockerfile (`make -C contrib install`): <https://github.com/docker-library/postgres/blob/master/16/alpine3.24/Dockerfile>
53. [S53] Zonky embedded-postgres README: <https://github.com/zonkyio/embedded-postgres>
54. [S54] Zonky embedded-postgres-binaries README: <https://github.com/zonkyio/embedded-postgres-binaries>
55. [S55] Zonky embedded-database-spring-test README: <https://github.com/zonkyio/embedded-database-spring-test>
56. [S56] Java SE 17 API, `java.util.concurrent.Executors`: <https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Executors.html>
57. [S57] Maven Central, `io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64:16.15.0`: <https://repo1.maven.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-linux-amd64/16.15.0/>

[S1]: https://www.postgresql.org/docs/16/rangetypes.html
[S2]: https://www.postgresql.org/docs/16/ddl-constraints.html
[S3]: https://www.postgresql.org/docs/16/sql-createtable.html
[S4]: https://www.postgresql.org/docs/16/btree-gist.html
[S5]: https://www.postgresql.org/docs/16/sql-createindex.html
[S6]: https://www.postgresql.org/docs/16/datatype-datetime.html
[S7]: https://www.postgresql.org/docs/16/index-unique-checks.html
[S8]: https://www.postgresql.org/docs/16/explicit-locking.html
[S9]: https://www.postgresql.org/docs/16/sql-select.html
[S10]: https://www.postgresql.org/docs/16/transaction-iso.html
[S11]: https://www.postgresql.org/docs/16/applevel-consistency.html
[S12]: https://www.postgresql.org/docs/16/mvcc-serialization-failure-handling.html
[S13]: https://www.postgresql.org/docs/16/functions-admin.html
[S14]: https://www.postgresql.org/docs/16/errcodes-appendix.html
[S15]: https://www.postgresql.org/docs/16/runtime-config-client.html
[S16]: https://www.postgresql.org/docs/16/functions-srf.html
[S17]: https://docs.hibernate.org/orm/6.6/userguide/html_single/
[S18]: https://docs.hibernate.org/orm/6.6/javadocs/org/hibernate/tool/schema/Action.html
[S19]: https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/type/SqlTypes.java
[S20]: https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/dialect/PostgreSQLDialect.java
[S21]: https://github.com/hibernate/hibernate-orm/blob/6.6/hibernate-core/src/main/java/org/hibernate/exception/internal/SQLStateConversionDelegate.java
[S22]: https://github.com/vladmihalcea/hypersistence-utils/blob/master/README.md
[S23]: https://github.com/vladmihalcea/hypersistence-utils/blob/master/hypersistence-utils-hibernate-63/src/main/java/io/hypersistence/utils/hibernate/type/range/PostgreSQLRangeType.java
[S24]: https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html
[S25]: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html
[S26]: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html
[S27]: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-decl-explained.html
[S28]: https://docs.spring.io/spring-framework/reference/data-access/dao.html
[S29]: https://docs.spring.io/spring-framework/reference/data-access/orm/jpa.html
[S30]: https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-orm/src/main/java/org/springframework/orm/jpa/vendor/HibernateJpaDialect.java
[S31]: https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-orm/src/main/java/org/springframework/orm/jpa/JpaTransactionManager.java
[S32]: https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-jdbc/src/main/java/org/springframework/jdbc/support/SQLStateSQLExceptionTranslator.java
[S33]: https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-jdbc/src/main/resources/org/springframework/jdbc/support/sql-error-codes.xml
[S34]: https://docs.spring.io/spring-framework/reference/core/resilience.html
[S35]: https://github.com/spring-projects/spring-retry/blob/main/README.md
[S36]: https://github.com/spring-projects/spring-retry/blob/main/src/main/java/org/springframework/retry/annotation/EnableRetry.java
[S37]: https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html
[S38]: https://docs.spring.io/spring-boot/3.5/reference/data/sql.html
[S39]: https://docs.spring.io/spring-boot/3.5/reference/testing/testcontainers.html
[S40]: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.1-Release-Notes
[S41]: https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html
[S42]: https://documentation.red-gate.com/fd/postgresql-database-277579325.html
[S43]: https://docs.liquibase.com/oss/reference-guide-4-33/changelog-attributes/dbms
[S44]: https://www.h2database.com/html/features.html#compatibility_modes
[S45]: https://www.h2database.com/html/datatypes.html
[S46]: https://www.h2database.com/html/grammar.html#table_constraint_definition
[S47]: https://www.h2database.com/html/advanced.html#transaction_isolation
[S48]: https://www.h2database.com/html/commands.html#select
[S49]: https://java.testcontainers.org/modules/databases/postgres/
[S50]: https://java.testcontainers.org/modules/databases/jdbc/
[S51]: https://java.testcontainers.org/supported_docker_environment/
[S52]: https://github.com/docker-library/postgres/blob/master/16/alpine3.24/Dockerfile
[S53]: https://github.com/zonkyio/embedded-postgres
[S54]: https://github.com/zonkyio/embedded-postgres-binaries
[S55]: https://github.com/zonkyio/embedded-database-spring-test
[S56]: https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Executors.html
[S57]: https://repo1.maven.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-linux-amd64/16.15.0/
