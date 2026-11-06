# Campus Room Reservations

This is the service behind the room booking page. People hold a room for a
slot, queue behind whoever holds it, and cancel. One Maven module, Java 21,
JUnit 5.

Times are raw minutes from midnight on the booking day, so 540 is 09:00. Ranges
are half-open, `[start, end)`, so a booking ending at 600 and one starting at
600 don't overlap.

## Code map

Package root `edu.cmu.cs214.lab10`.

- `domain/`
  - `Booking.java`: one hold on one room for one range, plus the overlap
    check the rest of the code uses.
  - `Room.java`, `BookingStatus.java`, `BookingResult.java`.
- `store/`
  - `BookingStore.java`: the storage interface, and its javadoc states what
    each method promises a caller.
  - `InMemoryBookingStore.java`: the implementation the service runs against.
- `service/`
  - `BookingService.java`: the API the web front end and the kiosk call.
  - `BookingStats.java`: dashboard counters. See the note below.
  - `BookingLog.java`: the event log, one line per event on standard output.
- `harness/`
  - `PausingStore.java`: a store wrapper that pauses one chosen call so a test
    can drive a specific interleaving.
  - `StressRunner.java`: a main class that runs the same two-customer round
    many times and reports the end state of each round.
- `src/test/java/`
  - `domain/BookingTest.java`, `service/BookingServiceTest.java`: the suite.
  - `harness/RaceTest.java`: empty except for the harness wiring in
    comments. You fill it in.

## Build and test

From this directory:

```
mvn -B test
```

The stress runner is not part of the suite and CI does not run it. Start it by
hand:

```
mvn -q compile
java -cp target/classes edu.cmu.cs214.lab10.harness.StressRunner
java -cp target/classes edu.cmu.cs214.lab10.harness.StressRunner 2000
```

## Dashboard counters are approximate

`BookingStats` keeps plain `long` counters and updates them without any lock.
Concurrent increments lose updates, so the numbers it reports can read low.
That is intended and documented in the class. The counters feed a dashboard
tile and a weekly summary, nothing in the service reads them, and no decision
depends on them. Count the bookings in the store when you need an exact number.

## Other files here

- `TICKET.md`: an open incident report from the operations team.
- `proposed-fix.diff`: a patch a teammate sent over. It isn't applied. To try
  it, run `git apply proposed-fix.diff` from this directory (the one holding
  `pom.xml`). Undo it with `git apply -R proposed-fix.diff`. To check that it
  applied, run `git diff --stat`. It should show `BookingService.java`
  changed. If it doesn't, this directory sits inside another git repository,
  so use `patch -p1 < proposed-fix.diff` instead (undo with
  `patch -R -p1 < proposed-fix.diff`).
- `RACE.md`: where your answers go.
- `SETUP.md`: toolchain and first build.

See the Lab 10 handout on the course page for the three milestones you show a
TA.

## Tools used for Lab 10

Claude Code (CLI) with Claude Opus 5.5 (`claude-opus-5-5`), for setup, code
investigation, writing the race tests, and applying fixes.
