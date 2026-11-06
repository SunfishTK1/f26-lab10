# RACE.md

One section per milestone. Fill each one in order as you go.

The prediction in milestone 1 is written BEFORE you run your race test. A TA
may ask about it. Commit it on its own so the order is visible.

Keep it short and specific. Point at methods, call sites, and the invariant.

---

## Milestone 1: from the ticket to a test that fails every time

### Suspect list

Everything your review turned up that touches shared state, with a one-line
verdict for each. Add or remove rows as needed.

| # | What you found (method, field, or class) | Verdict, in one line |
|---|---|---|
| 1 | `BookingService.book`: `store.hasOverlap(...)` (line 70), then separately `store.insert(...)` (line 74) | **The bug.** Check-then-act across two store calls: each call is atomic, but the store's lock is released between them, so two callers can both see "free" and both insert CONFIRMED. |
| 2 | `InMemoryBookingStore`: every method `synchronized` on the store, private `ArrayList` of immutable `Booking` records, copies handed out | Safe per call, exactly as `BookingStore` promises. But that promise covers one method at a time, so it cannot protect an invariant that spans two calls. The service javadoc's "since the store is thread safe, the operations built on top of it are too" is the false step. |
| 3 | `InMemoryBookingStore.cancelAndPromote` + `overlapsConfirmed` | Safe: cancel, re-check, and promote all run under one lock. A promotion could land inside row 1's gap (same root cause), but the ticket had no cancellations or promotions. |
| 4 | `BookingService.joinWaitlist` → one `store.insert` of a WAITLISTED entry | Safe: a single store call with no check before it, and WAITLISTED entries don't count toward the invariant. |
| 5 | `BookingStats` counters: plain `long`, `++` with no lock | A real data race (lost updates, stale reads), but documented and accepted. Nothing reads the counters, they aren't booking data, and the ticket rules out the dashboard. |
| 6 | `BookingService.sequence`: `AtomicLong.incrementAndGet()` in `nextId()` | Safe within one service, and ids are unique (the ticket confirms different ids). It is per instance, though, so two services sharing one store would both issue `bk-1`. That is a separate bug, not a timing one. |
| 7 | `BookingService.catalog`: `HashMap` built in the constructor, held in a `final` field, never written again | Safe: published through a final field and read-only after construction. |
| 8 | `BookingLog`: `System.out.println` with a shared `DateTimeFormatter` | Safe: `PrintStream` locks each line and the formatter is immutable. It logs after `insert` and outside any lock, so log order is not proof of store order. |

**Which one explains the ticket, and how you know.** Row 1. The code assumes
that because every store method is thread-safe, the operations built on top of
them are thread-safe too. That assumption is wrong. `synchronized` makes each
store call atomic on its own, but the lock is released between calls, so
nothing protects the gap between them. In `BookingService.book()`, two threads
can run their `hasOverlap` checks back to back and both see the slot as free.
Then both freely `insert` a CONFIRMED booking.

I know it's this one because of the invariant the ticket names: a room has at
most one CONFIRMED booking covering any minute. Only two code paths ever create
a CONFIRMED booking: `book()` inserts one, and `cancelAndPromote` promotes one.
The ticket rules out the second. Neither booking was cancelled or waitlisted,
both were "issued as confirmed", and the log shows two `booking.confirmed` lines
with no `booking.cancelled` or `booking.promoted` between them. That leaves
`book()`, whose only protection for the invariant is the `hasOverlap` check on
line 70. That check is only true while the store's lock is held, not by the
time of the `insert` on line 74. The rest of the ticket fits:

- It happened in the first minute after a slot opened, when many people are
  racing for the same slot.
- The two confirmations are 1 ms apart, with different ids and no errors.
- The suite is single-threaded, so it never interleaves two calls.
- Two people on a count of three are milliseconds apart, but the gap between
  the check and the insert is microseconds wide.

### Predicted interleaving (write this before you run your race test)

Two threads, one row per step, in the form from lecture. Describe what each
thread does at that step and leave the other column empty. Add or remove rows
as your prediction needs.

Setup: a fresh store with WEH-5409 free. Thread A and Thread B each call
`book("WEH-5409", 540, 600)`. Planned aim: `PausingStore` on `hasOverlap`, call
1. A starts first and parks right after its check returns. B starts only once A
is parked.

| Step | Thread A | Thread B |
|---|---|---|
| t1 | `hasOverlap(WEH-5409, 540, 600)` returns `false` (store is empty); parked before `book()` sees the answer | |
| t2 | | `hasOverlap(WEH-5409, 540, 600)` returns `false` (call 2 passes through the spent gate; A has not inserted) |
| t3 | | `nextId()` gives `bk-1`; `insert(bk-1 CONFIRMED)`; logs `booking.confirmed booking=bk-1`; returns CONFIRMED |
| t4 | released, acts on its stale `false`: `nextId()` gives `bk-2`; `insert(bk-2 CONFIRMED)`; logs `booking.confirmed booking=bk-2`; returns CONFIRMED | |
| | **End state:** two CONFIRMED bookings for WEH-5409 [540, 600): bk-1 (B) and bk-2 (A). No `booking.rejected` line. The B line is logged before the A line. An "exactly one confirmed" assertion fails: expected 1, was 2. | |

If the run turned out different from your prediction, add one line on what
you missed. Leave the table as you first wrote it.

**Run vs. prediction:** it matched on every point. B got bk-1 and logged first,
A got bk-2, there was no `booking.rejected` line, and the result was expected
1, was 2. Nothing missed.

### Your race test

**How you aimed `PausingStore`, and the result.** Method name and call index, why
that pair pins the schedule you predicted, and the output of two separate
runs (assertion failure and log lines).

**Aim: `hasOverlap`, call 1.** The race lives in the gap between `book()`'s
check and its insert, so I park a thread inside that gap. `PausingStore` parks
*after* the real call returns. Thread A makes call 1, so it is frozen holding
the answer "free" before `book()` can act on it. The test starts B only after
`awaitPaused()` returns, so B's check is always call 2. Call 2 passes through
the spent gate, and B also sees an empty store. B finishes completely, and only
then do I release A, which inserts on its stale answer. No step is left to the
scheduler, which is why it fails every time. Aiming at `insert`, call 1 would
not work: A's booking would already be in the store when B checks, so B would
be correctly rejected.

The test (`RaceTest.onlyOneOfTwoBookingsForTheSameSlotIsConfirmed`) asserts
only the end state of the real store: exactly one CONFIRMED booking for
WEH-5409 [540, 600). Its failure message also reports whether the gate fired
and what each thread got.

**Result: red on both runs, the same way.** The gate fired, `hasOverlap` was
called twice, and both threads got CONFIRMED. Each run took about 0.03 s, which
also shows that no bounded wait timed out. If the gate had never fired,
`awaitPaused()` alone would have taken 2 s.

Run 1 (`mvn -B test -Dtest=RaceTest`):

```
2026-11-06T15:37:29.326Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T15:37:29.329Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=540 end=600
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.038 s <<< FAILURE! -- in edu.cmu.cs214.lab10.harness.RaceTest
[ERROR] edu.cmu.cs214.lab10.harness.RaceTest.onlyOneOfTwoBookingsForTheSameSlotIsConfirmed -- Time elapsed: 0.028 s <<< FAILURE!
org.opentest4j.AssertionFailedError: a room may hold at most one CONFIRMED booking for any minute (gate fired=true, hasOverlap calls=2; A got CONFIRMED bk-2, B got CONFIRMED bk-1) ==> expected: <1> but was: <2>
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

Run 2 (`mvn -B test -Dtest=RaceTest`):

```
2026-11-06T15:37:30.643Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T15:37:30.645Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=540 end=600
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.031 s <<< FAILURE! -- in edu.cmu.cs214.lab10.harness.RaceTest
[ERROR] edu.cmu.cs214.lab10.harness.RaceTest.onlyOneOfTwoBookingsForTheSameSlotIsConfirmed -- Time elapsed: 0.022 s <<< FAILURE!
org.opentest4j.AssertionFailedError: a room may hold at most one CONFIRMED booking for any minute (gate fired=true, hasOverlap calls=2; A got CONFIRMED bk-2, B got CONFIRMED bk-1) ==> expected: <1> but was: <2>
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

---

## Milestone 2: the patch a teammate sent

**Your milestone-1 test on the patched code.** Green or red. Paste the result.

**The re-aimed run.** Method name, call index, the pasted result, and one line
on why that aim tests the patch rather than sidestepping it.

**Your verdict.** Accept or reject, and what your milestone-1 result does and
does not show about the patch.

---

## Milestone 3: the fix you would ship

**Which option, and what you changed.** Name the lock or the atomic operation
and the two operations it now covers. Paste the diff of your change and the
passing run of your milestone-1 test and the full suite.

**Is your milestone-1 test still testing anything?** After your fix, describe
what that test now exercises. Did `PausingStore` pause a call during the green run? How do
you know?

**What runs one at a time now that ran in parallel before.** Name concretely which callers
queue behind which. "Nothing, it is just correct now" does not count as an
answer.

**What you left unfixed.** For anything your review flagged that you chose not
to fix, one line each on why.
