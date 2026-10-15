# Graceful Stop Drain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `OpenApiServer.stop(delay)` return as soon as no request is in flight instead of waiting out the delay when the JDK's finished latch is never released, and expose the in-flight count as `inFlightRequests()`.

**Architecture:** A new internal `InFlightCounter` supplies one `Filter`, added first on every `HttpContext` the server creates, that counts each exchange through its handler, response rendering and after-response runnables. `stop(delay)` runs `HttpServer.stop(delay)` on a virtual thread (which closes the listener at once), waits on the counter for at most the delay, then calls `stop(0)` to release the JDK's latch and joins the background thread. The first caller owns a `CompletableFuture`; later and concurrent callers wait on it.

**Tech Stack:** Java 25, JDK `com.sun.net.httpserver`, JUnit 5, AssertJ, Maven.

**Spec:** `docs/superpowers/specs/2026-10-15-graceful-stop-drain-design.md`

---

## File Structure

**Create:**
- `src/main/java/com/retailsvc/http/internal/InFlightCounter.java` — the shared counting `Filter`, `inFlight()` and `awaitIdle(...)`.
- `src/test/java/com/retailsvc/http/GracefulStopIT.java` — end-to-end behaviour of `stop` against a live `OpenApiServer`.

**Modify:**
- `src/main/java/com/retailsvc/http/OpenApiServer.java` — counter field and wiring, `inFlightRequests()`, `stop`, `drainAndStop`, `awaitStopped`.
- `README.md` — "Graceful shutdown" section.
- `CLAUDE.md` — request-flow step 3 names the counter filter.

**Untouched but worth a glance:**
- `ExceptionFilter`, `RequestPreparationFilter`, `SecurityFilter` — the counter sits ahead of them, so their ordering is unchanged.
- `OpenApiServerIT` — the `Filters` hang is a separate problem (see the spec's "Out of scope").

---

## Task 1: Repro test

**Files:** Create `GracefulStopIT.java`

- [x] **Step 1: Write the failing test**
  - `stopsPromptlyAfterAClientDropsItsConnection` — 100 rounds: start a server on port 0, connect a raw `Socket` and close it, park 0-400 us, assert `stop(2)` returns in under 1 s.
  - Helpers: `start(Map<String, RequestHandler>)` over `ServerBaseTest.stubAllHandlers` with `/alive` as an extra route, `stopMillis(server[, delay])`, `loadSpec()` from `/openapi.json`.
- [x] **Step 2:** `mvn verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=GracefulStopIT` — fails on the unchanged `stop` with a round taking about 2000 ms (about one round in four).
- [x] **Step 3:** Add `stopsPromptlyAfterAClientClosesItsKeepAliveConnection`, the same race through a real `HttpClient`. It stalls far less often, so it is only a secondary guard.

## Task 2: `InFlightCounter` and `inFlightRequests()`

**Files:** Create `internal/InFlightCounter.java`; modify `OpenApiServer.java`; tests in `GracefulStopIT.java`

- [x] **Step 1: Write failing tests** (they do not compile until Step 2 adds `inFlightRequests()`)
  - `waitsForARequestInFlightAndNotLonger` — a handler blocks on a latch; `inFlightRequests()` is 1; `stop(2)` on another thread stays pending for 300 ms; after the release it returns in under 2 s; the response is 200 and the count is 0.
  - `givesUpOnARequestInFlightAtTheDelay` — `stop(1)` with the handler still blocked takes between 900 ms and 2 s.
  - `countsBackToZeroWhenAHandlerThrows` — a throwing handler gives 500 and the count returns to 0.
  - `waitsForAfterResponseRunnables` — the response is out, a `Request.afterResponse` runnable is blocked, the count is still 1 and `stop` waits for it.
- [x] **Step 2: Implement `InFlightCounter`**
  - A `ReentrantLock` with an `idle` `Condition` guards an `int inFlight`.
  - `filter()` returns one shared `CountingFilter`: `enter()` before `chain.doFilter`, `exit()` in a `finally`, `signalAll` when the count reaches 0. `description()` returns `"In-flight counter"`.
  - `inFlight()` reads under the lock; `awaitIdle(timeout, unit)` loops on `awaitNanos` until the count is 0 or the time is up, sets the interrupt flag again on `InterruptedException`, and returns `inFlight() == 0`.
- [x] **Step 3: Wire it in**
  - `OpenApiServer` creates the counter before wiring and passes it to `wireBindings`, `wireBinding` and `wireExtras`.
  - `ctx.getFilters().add(counter.filter())` is the first filter on each binding context and on the extras context (ahead of `ExceptionFilter`).
  - `public int inFlightRequests()` returns `counter.inFlight()`; javadoc: snapshot for logs and metrics, malformed requests the JDK rejects are not counted.
- [x] **Step 4:** `mvn verify ... -Dit.test=GracefulStopIT` — the counting tests pass; the Task 1 tests still stall.

## Task 3: Rework `stop`

**Files:** Modify `OpenApiServer.java`; tests in `GracefulStopIT.java`

- [x] **Step 1: Write failing tests**
  - `refusesNewConnectionsWhileDraining` — with a request in flight and `stop(2)` running, a new `Socket` connect is refused within 5 s; the count is still 1.
  - `servesAnOpenKeepAliveConnectionWhileDraining` — a raw keep-alive socket that did one `GET /alive` still gets a 204 for a second one after the listener has closed.
- [x] **Step 2: Implement `drainAndStop(int delaySeconds)`**
  - Start `httpServer.stop(delaySeconds)` on `ofVirtual().name("http-stop")`.
  - `counter.awaitIdle(delaySeconds, SECONDS)`.
  - Call `httpServer.stop(0)`, which releases the JDK's latch, then `background.join(Duration.ofSeconds(STOP_JOIN_SECONDS))` with `STOP_JOIN_SECONDS = 5`; warn if the thread is still running.
  - The reasoning for why re-entering `ServerImpl.stop` is safe is in the spec; the first version carried it as a javadoc comment, and the review trimmed it to one line.
- [x] **Step 3:** `stop(int)` keeps its negative-delay check and calls `drainAndStop`. Javadoc: the listener closes at once, open keep-alive connections are served until the JDK sees no exchange in flight, the wait ends at a count of 0 or at the delay, and the wait is on the server's own count.
- [x] **Step 4:** `mvn verify ... -Dit.test=GracefulStopIT` — the Task 1 and 2 tests pass.

## Task 4: Repeated and concurrent stops

**Files:** Modify `OpenApiServer.java`; tests in `GracefulStopIT.java`

- [x] **Step 1: Write failing tests**
  - `keepsEveryStopWaitingWhileTheFirstDrains` — two concurrent `stop(2)` calls with a request in flight: both stay pending for 300 ms, both return after the release, and a third call returns in under 1 s.
  - `returnsAtOnceWhenAlreadyStopped` — `stop(0)` then `stop(2)` takes under 1 s.
- [x] **Step 2: Implement**
  - Fields: `AtomicBoolean stopping`, `CompletableFuture<Void> stopped`.
  - `stop`: `stopping.compareAndSet(false, true)` wins and owns the future; on success `stopped.complete(null)`; on `RuntimeException` `completeExceptionally(e)` and rethrow; a `finally` completes it exceptionally with `IllegalStateException("The stop was aborted")` if it is still open (an `Error`), so no later call waits forever.
  - Others call `awaitStopped()`: `stopped.get()`; `ExecutionException` becomes `IllegalStateException("The first stop failed", e)`; `InterruptedException` sets the flag and returns.
- [x] **Step 3:** `mvn verify ... -Dit.test=GracefulStopIT` — green.

## Task 5: Interrupt handling

**Files:** Modify `OpenApiServer.java`; test in `GracefulStopIT.java`

- [x] **Step 1: Write the failing test** — `keepsTheInterruptFlagOfAThreadInterruptedWhileStopping`: a platform thread calls `stop(2)` with a request in flight; after 300 ms it is interrupted; it must finish within 5 s with its interrupt flag still set. Fails on the old `stop`, where the JDK swallows the interrupt.
- [x] **Step 2: Implement**
  - `awaitIdle` already ends the wait and re-sets the flag.
  - `drainAndStop` takes the flag with `Thread.interrupted()` before `httpServer.stop(0)` (the JDK clears it), and sets it again at the end; an interrupt during the join sets it directly.
- [x] **Step 3:** `mvn verify ... -Dit.test=GracefulStopIT` — all 11 tests green, about 3 s.

## Task 6: Docs

**Files:** Modify `README.md`, `CLAUDE.md`

- [x] **Step 1:** README "Graceful shutdown": the listener closes at once; open keep-alive connections are served until the JDK sees no exchange in flight; the wait ends at `inFlightRequests() == 0` or the delay; what counts as in flight (through after-response runnables, not malformed requests); only the first `stop` stops the server, others wait and see `IllegalStateException` if it failed; an interrupted waiter keeps its flag; `inFlightRequests()` is a snapshot.
- [x] **Step 2:** `CLAUDE.md` request-flow step 3: every context starts with the `InFlightCounter` filter.

## Task 7: Review and Sonar fixes

Two review rounds, then the Sonar gate.

- [x] **Step 1: Review 1 (design, blocker).** The first version waited on the counter and called `stop(0)`, leaving the listener open during the drain and answering new requests with 503, a client-visible change that clients rarely retry on a `POST`. The developer chose to keep refusing new connections: start `HttpServer.stop(delay)` on a virtual thread first (Task 3) and add the `refusesNewConnectionsWhileDraining` and `servesAnOpenKeepAliveConnectionWhileDraining` tests.
- [x] **Step 2: Review 2.** `inFlightRequests()` is documented as a snapshot for logs and metrics, not as a drain primitive; a failed first stop reaches later callers as `IllegalStateException`; `GracefulStopIT.ignoresASecondStop` is renamed `returnsAtOnceWhenAlreadyStopped`; README rewrapped.
- [x] **Step 3: Sonar gate.** S1181 (no catch of `Error`; the `finally` completes the future), S2142 (interrupt restored in the join), S1166 (keep the `ExecutionException` as cause), S3398 (`enter` / `exit` moved into `CountingFilter`), S2925 (`Thread.sleep` in `eventually` becomes `LockSupport.parkNanos`).

## Task 8: Final verify and commit

- [x] **Step 1:** `pre-commit run --files <changed files>`.
- [x] **Step 2:** `mvn -B verify` — unit and integration tests green. `OpenApiServerIT$Filters` can hang on a shadowed port; it hangs at the same rate on `master` and is not part of this change.
- [x] **Step 3:** SonarLint sweep (`sonar_analyze_file`) over the changed main and test files.
- [x] **Step 4:** Commits on `fix/stop-inflight-counter`, both signed: `feat: Drain in-flight requests on stop` (ef20528) and `feat: Address review and Sonar findings on stop draining` (84c50c3). Push and open PR #133 (`feat`: new public API).

## Follow-ups (not in this plan)

- [ ] Bind and call `127.0.0.1` in the integration tests, so `OpenApiServerIT$Filters` cannot reach a process that holds the port on `127.0.0.1`.
- [ ] Close the virtual-thread executor on stop (predates this change).
- [ ] After the release, bump the library version in hiiretail-payment-connector-engine and drop its 5 s test stalls.
