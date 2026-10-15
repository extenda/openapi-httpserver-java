# Graceful Stop Drain

**Status:** Approved
**Date:** 2026-10-15
**Author:** thced

## Goal

Make `OpenApiServer.stop(delay)` return as soon as no request is in flight, and give callers the
in-flight count. Today `stop` passes straight to `HttpServer.stop(delay)`, which in JDK 25 can wait
out the whole delay with nothing running. Consumers see it as slow shutdowns and 5 s stalls in
tests; the connector engine had 11-13 tests at about 5 s each.

## Root cause

`ServerImpl.stop(delay)` sets `terminating`, posts `Event.StopRequested`, closes the listening
socket and waits on `finishedLatch` for at most `delay`. The latch is counted down in only two
places, both in `Dispatcher.handleEvent`: on `StopRequested` when `getExchangeCount() == 0 &&
reqConnections.isEmpty()`, and on `WriteFinished` when `terminating` and the same holds.

When a client closes an idle keep-alive connection, the socket turns readable (EOF) and the
dispatcher adds the connection to `reqConnections` through `requestStarted`. The exchange task then
finds no request line and calls `closeConnection`, which removes the connection again but does not
count the latch down. If `StopRequested` is handled between those two steps, it sees a non-empty
`reqConnections` and logs "Some requests are still pending"; no `WriteFinished` follows, and the
latch is never released. `stop` sleeps the whole delay with every collection empty. The response
doesn't matter (HEAD, 204, 404 and 200 all hit it).

A plain-JDK repro (`Race.java`: raw socket connect and close, park 0-400 us, `stop(2)`) stalled in
39 of 150 rounds, about 1 in 4. Re-issuing `stop(2)` every 10 ms while the first call waits gave 0
of 150. Through the engine and a real `HttpClient`, about 0.7 % of rounds stalled.

## Approaches considered

1. **A poke:** run `HttpServer.stop(delay)` on a thread and re-call it every ~10 ms. Each call
    re-posts `StopRequested`, which re-checks the idle condition. Small, but it polls, relies on JDK
    internals for correctness, and adds nothing to the API.
2. **Count in-flight requests in the library.** Every `HttpContext` is created by `OpenApiServer`,
    so a `Filter` on each can keep the count. The wait depends on our own state, and the count is
    useful to callers on its own.
3. **A test-side workaround** (sleep, or stop on a short delay). Fixes the symptom in one
    consumer's tests; production shutdowns stay slow and the library stays wrong.

Option 2 is chosen. It uses only public JDK API for the wait, and gives callers
`inFlightRequests()`.

### The first design, and why it changed

The first version waited on the counter and then called `stop(0)`. The listener therefore stayed
open during the drain, and a request that arrived meanwhile was answered `503`. Review flagged this
as a client-visible behaviour change: clients rarely retry a 503 on a `POST`, and today `stop`
refuses new connections at once, which a client or load balancer can retry elsewhere. The
developer chose to keep refusing new connections, which is why the JDK's own stop now runs
alongside the drain (see "Behaviour").

## API

```java
public int inFlightRequests();
```

A snapshot of the server's own count, readable at any time, for logs and metrics. A request counts
from the first filter on its context until its after-response runnables have run. To drain before
shutdown, call `stop(int)`; it waits for the count itself.

`stop(int delaySeconds)` keeps its signature and its `IllegalArgumentException` for a negative
delay.

## Behaviour

### Counting

`InFlightCounter` (internal) holds the count behind a `ReentrantLock` and a `Condition`.
`filter()` returns one shared `Filter` that increments before `chain.doFilter` and decrements in a
`finally`, signalling when the count reaches `0`. `OpenApiServer` adds it first on every context it
creates: each spec binding and the `/` extras context, ahead of `RequestPreparationFilter` and
`ExceptionFilter`. The counted window therefore covers the handler, the rendering of the response
and the after-response hooks, which run inside the same filter chain.

`awaitIdle(timeout, unit)` waits until the count is `0` or the timeout passes and returns whether it
is `0`. An interrupt ends the wait and sets the flag again.

### Stop

The first call to `stop(delay)`:

1. starts `HttpServer.stop(delay)` on a virtual thread (`http-stop`). That closes the listening
    socket at once, so new connections are refused as before;
2. calls `counter.awaitIdle(delay)`;
3. clears the thread's interrupt flag, calls `httpServer.stop(0)`, joins the `http-stop` thread for
    at most 5 s (logging a warning if it is still running), and restores the flag.

The wait in step 2 ends when `inFlightRequests()` reads `0`, or after the delay. Step 3 closes every
connection, which cuts off whatever is still running at the deadline. Keep-alive connections that
are already open are served until the JDK sees no exchange in flight, as before.

#### Why the second `HttpServer.stop` is safe

`ServerImpl.stop` is re-entrant. Each call only sets `terminating`, posts another `StopRequested`,
closes the already closed socket and awaits the latch. Its `finally` counts the latch down for every
waiter and clears connections and timers, all of which tolerate repeats; it also joins the
dispatcher thread unless it is the current thread. `stop(0)` returns from `await(0)` at once and
runs that `finally`, which releases the latch the background call is waiting on. This is the same
re-entrancy the poke relied on, used once and with a deadline instead of a loop.

The JDK swallows an `InterruptedException` in `stop` and clears the flag, so the caller's flag is
taken before `stop(0)` and set again at the end.

### Repeated and concurrent stops

The first caller wins a `compareAndSet` on `stopping` and owns a `CompletableFuture<Void>`
(`stopped`). A later or concurrent call ignores its own delay and waits on the future:

- on success it returns;
- if the first stop threw, it throws `IllegalStateException("The first stop failed", cause)`;
- if it is interrupted it stops waiting and keeps its interrupt flag.

A `finally` completes the future exceptionally when an `Error` left it open, so no later call
waits forever.

## Compatibility

- `feat`: `inFlightRequests()` is new public API.
- `stop` also waits for after-response runnables and hooks, up to the delay. A hook that takes
  longer than the delay is cut off, as a handler would be.
- A second `stop` waits for the first instead of returning at once. `close()` is unchanged.
- New connections are still refused from the moment `stop` is called; the response to a request on
  an open connection is unchanged.
- No new builder option; `shutdownTimeoutSeconds` still sets the delay used by `close()`.

## Constraints

- The count is the server's own. A request the JDK rejects before it dispatches it, such as a
  malformed one, never reaches a filter and is not counted.
- The JDK can still end its own wait before the last request's after-response runnables finish;
  the library's wait continues until the count is `0` or the delay elapses.
- Draining ends at the delay for good: `stop(0)` closes every connection, so a request still
  running at the deadline loses its connection.
- A thread that calls `stop` and is interrupted gives up the drain early but still stops the server.
- The virtual-thread executor is not closed on stop. This predates the change.

## Testing

`GracefulStopIT` (11 tests, about 3 s), against a live `OpenApiServer`:

- `stopsPromptlyAfterAClientDropsItsConnection` — 100 rounds of a raw `Socket` connect and close,
  random park up to 400 us, `stop(2)` in under 1 s. The main guard; fails on the old `stop` in
  about one round in four.
- `stopsPromptlyAfterAClientClosesItsKeepAliveConnection` — the same race through a real
  `HttpClient`; it hits far less often, so it only backs up the one above.
- `waitsForARequestInFlightAndNotLonger`, `givesUpOnARequestInFlightAtTheDelay`.
- `keepsEveryStopWaitingWhileTheFirstDrains`, `returnsAtOnceWhenAlreadyStopped`.
- `refusesNewConnectionsWhileDraining`, `servesAnOpenKeepAliveConnectionWhileDraining`.
- `countsBackToZeroWhenAHandlerThrows`, `waitsForAfterResponseRunnables`.
- `keepsTheInterruptFlagOfAThreadInterruptedWhileStopping` — fails on the old `stop`, which
  swallowed the flag.

Only the raw-socket loop and the interrupt test fail on the old `stop`; the rest specify behaviour
the change must keep. Not covered: the `IllegalStateException` for a failed first stop, and a
unit test of `InFlightCounter` apart from the server.

## Out of scope

- Closing the virtual-thread executor on stop.
- `OpenApiServerIT$Filters` can hang, on `master` at the same rate (3 in 12,000 runs, 1 in 12,000
  on this branch). The server binds the wildcard address on a random port, and another local
  process may already hold that port on `127.0.0.1`, so `localhost` reaches it (`Shadow.java`
  reproduces this). Binding and calling `127.0.0.1` in the tests would fix it, as a separate change.
- Consumers (hiiretail-payment-connector-engine) bump the library version after the release; no
  change is made there.
