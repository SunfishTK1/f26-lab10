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

**Green.** I applied the patch with `git apply proposed-fix.diff`, and
`git diff --stat` showed `BookingService.java | 7 +++++++`. A still parked
after its first check and B still booked bk-1. But when A was released, the
patch's re-check saw B's booking and rejected A.

```
2026-11-06T15:42:19.204Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T15:42:19.209Z INFO booking.rejected room=WEH-5409 start=540 end=600 reason=slot already held
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.030 s -- in edu.cmu.cs214.lab10.harness.RaceTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**The re-aimed run.** Method name, call index, the pasted result, and one line
on why that aim tests the patch rather than sidestepping it.

**Aim: `hasOverlap`, call 2** (`RaceTest.onlyOneBookingIsConfirmedWhenTheRecheckGoesStale`).
A runs alone until it parks, so call 1 is A's original check and call 2 is the
patch's re-check. A parks right after the re-check answers "free", in the gap
that still exists between the re-check and the insert. B then makes calls 3
and 4, both answer "free", and B inserts. A is released and inserts on its
stale re-check. Red on both runs against the patched code. (The patch builds
the `Booking` before its re-check, so this time A got bk-1 and B got bk-2.)

**Why this aim tests the patch rather than sidestepping it:** A goes *through*
the patch's re-check. The re-check runs, answers "free" (`hasOverlap calls=4`,
two per thread), and the double booking happens anyway. The patch's own defense
runs and fails. The test does not route around it.

Each run executed both tests. The first two log lines are the re-aimed test
(red), and the last two are the milestone-1 test (green).

Run 1 (`mvn -B test -Dtest=RaceTest`, patch applied):

```
2026-11-06T15:42:34.407Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=540 end=600
2026-11-06T15:42:34.410Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T15:42:34.422Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T15:42:34.424Z INFO booking.rejected room=WEH-5409 start=540 end=600 reason=slot already held
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.037 s <<< FAILURE! -- in edu.cmu.cs214.lab10.harness.RaceTest
[ERROR] edu.cmu.cs214.lab10.harness.RaceTest.onlyOneBookingIsConfirmedWhenTheRecheckGoesStale -- Time elapsed: 0.023 s <<< FAILURE!
org.opentest4j.AssertionFailedError: a room may hold at most one CONFIRMED booking for any minute (gate fired=true, hasOverlap calls=4; A got CONFIRMED bk-1, B got CONFIRMED bk-2) ==> expected: <1> but was: <2>
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

Run 2 (`mvn -B test -Dtest=RaceTest`, patch applied):

```
2026-11-06T15:42:35.816Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=540 end=600
2026-11-06T15:42:35.818Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T15:42:35.830Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T15:42:35.833Z INFO booking.rejected room=WEH-5409 start=540 end=600 reason=slot already held
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.036 s <<< FAILURE! -- in edu.cmu.cs214.lab10.harness.RaceTest
[ERROR] edu.cmu.cs214.lab10.harness.RaceTest.onlyOneBookingIsConfirmedWhenTheRecheckGoesStale -- Time elapsed: 0.021 s <<< FAILURE!
org.opentest4j.AssertionFailedError: a room may hold at most one CONFIRMED booking for any minute (gate fired=true, hasOverlap calls=4; A got CONFIRMED bk-1, B got CONFIRMED bk-2) ==> expected: <1> but was: <2>
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

**Your verdict.** Accept or reject, and what your milestone-1 result does and
does not show about the patch.

**Reject.** The patch turns check-then-act into check-then-check-then-act. The
re-check and the insert are still two separate store calls, and the store's
lock is released between them, so the re-check's answer can already be stale
when the insert runs. The patch comment's claim that "there is no window left"
is false. The window is narrower, not gone. I reverted the patch with
`git apply -R proposed-fix.diff`, and it is not merged.

What my milestone-1 result shows: going green on the patch shows only that the
patch closes the one schedule that test pins (a stale first check followed by
an insert), because the patch put a re-check inside that exact gap. What it
does not show: that the race is gone. Moving the same pause one call later, to
the re-check, gives a double booking every time.

The teammate's evidence doesn't hold up either. The suite is single-threaded,
and "no bad rounds" from the stress runner just means the narrower window is
hit less often. I ran the stress runner on the patched code: 0, 1, and 1 bad
rounds out of 2000 each, against 24 and 2 out of 100 before the patch. Rarer is
not fixed.

**Note on the re-aimed test against the shipped code.** After reverting, the
full suite runs 13 tests with 1 failure: the milestone-1 test, red as it should
be. The re-aimed test passes on the shipped code, but only by accident. The
shipped `book()` makes one `hasOverlap` call, so A never makes call 2.
`awaitPaused()` times out after 2 s, B's check becomes call 2 and parks *after*
it has already seen A's booking, and B's join times out after another 2 s
(`RaceTest` took 4.071 s). A green race test only shows that the one schedule
it aims at is safe in the code it ran against, because the aim is tied to that
code's call shape.

---

## Milestone 3: the fix you would ship

**Which option, and what you changed.** Name the lock or the atomic operation
and the two operations it now covers. Paste the diff of your change and the
passing run of your milestone-1 test and the full suite.

**Option 3: an explicit `ReentrantLock`**, `bookingLock`, a field on
`BookingService`. I chose it because it locks the check and the write together.
If several requests come in at once, the others simply wait their turn. Safety
matters more here than throughput.

What it covers:

- **`book()`: the `hasOverlap` check and the `insert`.** These are the two
  operations from the ticket. They now run as one step inside
  `confirmIfFree()`.
- **`cancelBooking()`: the `cancelAndPromote` call.** A promotion is the only
  other way to create a CONFIRMED booking, so it must not land between a
  booking's check and its insert either (suspect row 3).

I made the fix in two steps to show the second one is needed. I added a third
race test, `aPromotionCannotLandBetweenABookingsCheckAndItsInsert`. A books
[570, 600) and parks after its check, while C cancels X [540, 570), which
promotes the waitlisted W [540, 600).

- **Shipped code:** red. W was promoted and A was confirmed on top of it.
- **Lock on `book()` only** (commit `8670460`): the milestone-1 test went
  green, but the cancel test stayed red. `C done before release=true` means the
  cancel went straight past the lock.
- **Lock on both** (commit `8c6412b`): all green.

Logging and stats stay outside the lock, so it is held only around store
calls. I also corrected the class javadoc, which claimed the service was thread
safe because the store is.

Diff, shipped code to fix (`git diff a8dc8f5 HEAD -- src/main/java/edu/cmu/cs214/lab10/service/BookingService.java`):

```diff
diff --git a/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java b/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java
index 514c3e1..b26532c 100644
--- a/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java
+++ b/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java
@@ -12,6 +12,7 @@ import java.util.List;
 import java.util.Map;
 import java.util.Optional;
 import java.util.concurrent.atomic.AtomicLong;
+import java.util.concurrent.locks.ReentrantLock;
 
 /**
  * The room reservation API that the web front end and the kiosk both call.
@@ -19,12 +20,16 @@ import java.util.concurrent.atomic.AtomicLong;
  * <p>Times are raw minutes from midnight and ranges are half-open, matching
  * {@link Booking}.
  *
- * <p>Thread safety: safe for concurrent use. The service owns no mutable
- * booking state of its own. The room catalog is built once in the constructor
- * and never written again, and every read and write of booking data goes
- * through {@link BookingStore}, whose methods are each synchronized. Since the
- * store is thread safe, the operations built on top of it are too. The only
- * other field that changes after construction is {@link BookingStats}, and its
+ * <p>Thread safety: safe for concurrent use through one service instance. The
+ * room catalog is built once in the constructor and never written again, and
+ * booking data lives in {@link BookingStore}, which makes each single call
+ * atomic. That is not enough on its own: an operation that checks the store
+ * and then writes based on the answer spans two calls, and the store's lock is
+ * released in between. So every path that can create a CONFIRMED booking takes
+ * {@code bookingLock} around its check and its write: {@link #book}'s overlap
+ * check and insert, and {@link #cancelBooking}'s cancel-and-promote. The lock
+ * belongs to this instance, so two services pointed at the same store do not
+ * exclude each other. {@link BookingStats} is still unsynchronized, and its
  * numbers are documented as approximate.
  */
 public final class BookingService {
@@ -34,6 +39,13 @@ public final class BookingService {
     private final BookingStats stats = new BookingStats();
     private final AtomicLong sequence = new AtomicLong();
 
+    /**
+     * Guards every path that can create a CONFIRMED booking, so a check of the
+     * store and the write that depends on it happen as one step. Held only
+     * around store calls, never around logging or stats.
+     */
+    private final ReentrantLock bookingLock = new ReentrantLock();
+
     /**
      * @param store the backing store, shared with any other service instance
      *     pointed at the same data
@@ -67,16 +79,36 @@ public final class BookingService {
         if (startMinute < 0 || endMinute <= startMinute) {
             return reject(roomId, startMinute, endMinute, "empty or reversed slot");
         }
-        if (store.hasOverlap(roomId, startMinute, endMinute)) {
+        Optional<Booking> confirmed = confirmIfFree(roomId, startMinute, endMinute);
+        if (confirmed.isEmpty()) {
             return reject(roomId, startMinute, endMinute, "slot already held");
         }
-        Booking booking = new Booking(nextId(), roomId, startMinute, endMinute, BookingStatus.CONFIRMED);
-        store.insert(booking);
+        Booking booking = confirmed.get();
         stats.recordConfirmed();
         BookingLog.confirmed(booking);
         return BookingResult.confirmed(booking);
     }
 
+    /**
+     * The check and the insert as one step: no other caller holding
+     * {@link #bookingLock} can change the room between them.
+     *
+     * @return the new CONFIRMED booking, or empty when the slot is already held
+     */
+    private Optional<Booking> confirmIfFree(String roomId, int startMinute, int endMinute) {
+        bookingLock.lock();
+        try {
+            if (store.hasOverlap(roomId, startMinute, endMinute)) {
+                return Optional.empty();
+            }
+            Booking booking = new Booking(nextId(), roomId, startMinute, endMinute, BookingStatus.CONFIRMED);
+            store.insert(booking);
+            return Optional.of(booking);
+        } finally {
+            bookingLock.unlock();
+        }
+    }
+
     /**
      * Queues the caller behind whoever currently holds the slot.
      *
@@ -111,7 +143,15 @@ public final class BookingService {
      *     promoted
      */
     public Optional<Booking> cancelBooking(String bookingId) {
-        Optional<Booking> promoted = store.cancelAndPromote(bookingId);
+        Optional<Booking> promoted;
+        // A promotion creates a CONFIRMED booking too, so it must not land
+        // between a book() call's check and its insert.
+        bookingLock.lock();
+        try {
+            promoted = store.cancelAndPromote(bookingId);
+        } finally {
+            bookingLock.unlock();
+        }
         stats.recordCancelled();
         BookingLog.cancelled(bookingId);
         promoted.ifPresent(BookingLog::promoted);
```

Milestone-1 test, passing
(`mvn -B test -Dtest='RaceTest#onlyOneOfTwoBookingsForTheSameSlotIsConfirmed'`):

```
2026-11-06T15:51:15.240Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T15:51:15.240Z INFO booking.rejected room=WEH-5409 start=540 end=600 reason=slot already held
[race] aim=hasOverlap#1 gate fired=true A parked=true B done before release=false hasOverlap calls=2; A got CONFIRMED bk-1, B got REJECTED
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.042 s -- in edu.cmu.cs214.lab10.harness.RaceTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Full suite, passing (`mvn -B test`; the `[race]` lines are one per race test):

```
[race] aim=hasOverlap#1 (cancel) gate fired=true A parked=true C done before release=false; A got CONFIRMED bk-1, W ended WAITLISTED
[race] aim=hasOverlap#2 gate fired=true A parked=false B done before release=false hasOverlap calls=2; A got CONFIRMED bk-1, B got REJECTED
[race] aim=hasOverlap#1 gate fired=true A parked=true B done before release=false hasOverlap calls=2; A got CONFIRMED bk-1, B got REJECTED
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.093 s -- in edu.cmu.cs214.lab10.harness.RaceTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.020 s -- in edu.cmu.cs214.lab10.service.BookingServiceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.004 s -- in edu.cmu.cs214.lab10.domain.BookingTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**Is your milestone-1 test still testing anything?** After your fix, describe
what that test now exercises. Did `PausingStore` pause a call during the green run? How do
you know?

**Yes.** It drives the same schedule as before: A parks right after its
`hasOverlap` call. The difference is that A is now parked while holding
`bookingLock`. B's `book()` can't reach its check. It blocks on the lock until A
has inserted, then checks, sees A's booking, and is rejected. So the test now
exercises the lock itself: a second booker cannot get between the first
booker's check and insert. Remove the lock and it goes red again, as it was on
the shipped code.

**Yes, `PausingStore` paused a call during the green run.** The `[race]` line
shows `gate fired=true A parked=true hasOverlap calls=2`. `B done before
release=false` means B was still blocked when its bounded join ran out. The
timing agrees: the test took 2.04 s, which is that join's length
(`PausingStore.TIMEOUT_MILLIS`), because B waited behind the parked A for all
of it. If nothing had been paused, the line would show `A parked=false`.

**Test adjustment:** the assertion is unchanged (exactly one CONFIRMED booking
in the real store). I added the `[race]` line, printed but not asserted, so a
green run shows what the gate did. A now gets bk-1, because B never reaches
`nextId()` while A holds the lock.

**The milestone-2 test** (`hasOverlap`, call 2) is green on this code but
tests nothing. It shows `A parked=false`, because the shipped `book()` makes
only one `hasOverlap` call. I kept it as the evidence for the milestone-2
verdict. It only means something against the patch.

**What runs one at a time now that ran in parallel before.** Name concretely which callers
queue behind which. "Nothing, it is just correct now" does not count as an
answer.

On one `BookingService` instance, every `book()` that passes validation and
every `cancelBooking()` now queue behind one another on `bookingLock`:

- **Bookers wait behind other bookers, even for a different room.** A
  WEH-5409 booking queues behind a GHC-4301 booking, though the two can never
  conflict. The lock is per service, not per room.
- **Bookers and cancellers wait behind each other.** A cancel waits for an
  in-progress booking's check and insert, and a booking waits for a cancel's
  promotion.

Before the fix, each single store call already ran one at a time (the store is
`synchronized`), but two bookers' check-and-insert pairs could interleave. Now
a whole check-plus-insert, or cancel-plus-promote, finishes before the next one
starts.

These never take the lock: `joinWaitlist`, `listBookings`, `rooms`, `room`,
`stats`, rejects for an unknown room or a bad slot, and all logging and stats
updates.

**Cost:** the lock is held for about two scans of an in-memory list, so the
wait is microseconds in normal use. The test shows how the cost grows: B waited
2 s because A stalled while holding the lock. If the store ever became a remote
database, every booking and cancel in the service would queue behind a network
round trip.

**What you left unfixed.** For anything your review flagged that you chose not
to fix, one line each on why.

- **`BookingStats` (row 5):** still a data race. The counts are documented as
  approximate, nothing reads them to make decisions, and exact counting would
  add contention just for a dashboard tile.
- **Two service instances sharing one store (row 6, and this lock's scope):**
  each instance has its own lock and its own id sequence, so they don't exclude
  each other and could both issue `bk-1`. I left it because the ticket's ids
  (bk-8412, bk-8413) are consecutive, which suggests one instance issued both.
  A proper fix moves the check-and-insert into the store (option 2) and shares
  one id source, which is a bigger change than this ticket needs.
- **Log order (row 8):** not a correctness bug. Logging happens outside the
  lock, so lines can come out of store order. In my 15:50:11 and 15:50:15 runs,
  B's `booking.rejected` printed before A's `booking.confirmed`, even though A
  must have inserted first for B to be rejected.
