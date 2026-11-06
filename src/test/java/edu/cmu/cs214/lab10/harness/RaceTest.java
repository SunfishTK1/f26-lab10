package edu.cmu.cs214.lab10.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import edu.cmu.cs214.lab10.domain.BookingResult;
import edu.cmu.cs214.lab10.domain.BookingStatus;
import edu.cmu.cs214.lab10.domain.Room;
import edu.cmu.cs214.lab10.service.BookingService;
import edu.cmu.cs214.lab10.store.InMemoryBookingStore;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Concurrency tests that pin one interleaving with {@link PausingStore}
 * instead of hoping for it.
 */
class RaceTest {

    private static final String WEAN = "WEH-5409";
    private static final List<Room> ROOMS = List.of(
            new Room(WEAN, "Wean 5409", 12),
            new Room("GHC-4301", "Gates 4301", 30));

    /**
     * Milestone 1: two customers book the same room and slot, and both check
     * for an overlap before either one inserts.
     *
     * <p>Aim: {@code hasOverlap}, call 1. Thread A makes that call and parks
     * right after the store answers "free", before {@code book()} can act on
     * the answer. Thread B then runs {@code book()} start to finish. Its
     * {@code hasOverlap} is call 2, so it passes straight through, and it also
     * sees "free" because A has not inserted yet. Releasing A lets it insert
     * on its stale answer.
     */
    @Test
    void onlyOneOfTwoBookingsForTheSameSlotIsConfirmed() throws InterruptedException {
        assertOneConfirmedWhenFirstBookerParksAt("hasOverlap", 1);
    }

    /**
     * Milestone 2: the same race, re-aimed at the teammate's patch, which
     * re-checks {@code hasOverlap} right before {@code insert}.
     *
     * <p>Aim: {@code hasOverlap}, call 2. A runs alone until it parks, so calls
     * 1 and 2 are both A's: its original check, then the patch's re-check. A
     * parks right after the re-check answers "free", in the gap between that
     * re-check and the insert. B then makes both of its checks (calls 3 and
     * 4), both answer "free", and B inserts. Releasing A lets it insert on its
     * stale re-check.
     */
    @Test
    void onlyOneBookingIsConfirmedWhenTheRecheckGoesStale() throws InterruptedException {
        assertOneConfirmedWhenFirstBookerParksAt("hasOverlap", 2);
    }

    /**
     * Two customers book WEH-5409 [540, 600) through a store gated at the given
     * aim. A starts first and runs until the gate parks it. B starts only then
     * and books. Then A is released. Asserts only the end state: the room holds
     * exactly one CONFIRMED booking for the slot.
     */
    private static void assertOneConfirmedWhenFirstBookerParksAt(String method, int call)
            throws InterruptedException {
        InMemoryBookingStore real = new InMemoryBookingStore();
        PausingStore gated = new PausingStore(real, method, call);
        BookingService service = new BookingService(gated, ROOMS);
        AtomicReference<BookingResult> resultA = new AtomicReference<>();
        AtomicReference<BookingResult> resultB = new AtomicReference<>();

        Thread a = new Thread(() -> resultA.set(service.book(WEAN, 540, 600)), "customer-A");
        a.start();
        gated.awaitPaused();

        Thread b = new Thread(() -> resultB.set(service.book(WEAN, 540, 600)), "customer-B");
        b.start();
        b.join(PausingStore.TIMEOUT_MILLIS);

        gated.release();
        a.join(PausingStore.TIMEOUT_MILLIS);
        b.join(PausingStore.TIMEOUT_MILLIS); // B may still be running if it was blocked behind A

        long confirmed = real.bookingsForRoom(WEAN).stream()
                .filter(booking -> booking.status() == BookingStatus.CONFIRMED)
                .filter(booking -> booking.overlaps(540, 600))
                .count();
        assertEquals(1, confirmed, () -> "a room may hold at most one CONFIRMED booking for any minute"
                + " (gate fired=" + gated.fired()
                + ", hasOverlap calls=" + gated.targetCallCount()
                + "; A got " + describe(resultA.get())
                + ", B got " + describe(resultB.get()) + ")");
    }

    private static String describe(BookingResult result) {
        if (result == null) {
            return "no result";
        }
        return result.outcome() + result.booking().map(booking -> " " + booking.id()).orElse("");
    }

    // ------------------------------------------------------------------
    // Wiring example 1: wrap the store
    //
    // PausingStore is a BookingStore that wraps another BookingStore. Build the
    // real store, wrap it, and hand the wrapper to the service. The service
    // cannot tell the difference.
    //
    //   InMemoryBookingStore real = new InMemoryBookingStore();
    //   PausingStore gated = new PausingStore(real, "bookingsForRoom", 1);
    //   BookingService service = new BookingService(gated, ROOMS);
    //
    // (ROOMS is the room list, as in BookingServiceTest.)
    //
    // Assert against `real`, not `gated`: you want the end state of the store,
    // and reading through the wrapper counts as a call.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Wiring example 2: aim the gate
    //
    // The two constructor arguments after the delegate are a method name and a
    // call index counting from 1 across all threads:
    //
    //   new PausingStore(real, "bookingsForRoom", 1)   // park call 1
    //   new PausingStore(real, "insert", 3)            // park call 3 of insert
    //
    // Valid method names are the four on BookingStore: hasOverlap, insert,
    // bookingsForRoom, cancelAndPromote. On the chosen call the wrapper
    // delegates, then parks the calling thread before returning the answer it
    // already computed. The gate fires once and once only.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Wiring example 3: the two-thread pattern
    //
    // Every wait below is bounded, so this pattern cannot hang no matter what
    // the code under test does. Do not add a sleep to it; a sleep is a guess,
    // and the latches are not. (Your test method needs
    // `throws InterruptedException` for the joins.)
    //
    // The scenario here is deliberately harmless: it parks a reader in
    // bookingsForRoom (the aim from example 1) while a writer books a
    // different room. It exists to show the choreography, not a bug.
    //
    //   Thread reader = new Thread(() -> service.listBookings("WEH-5409"));
    //   reader.start();
    //
    //   gated.awaitPaused();          // returns when a thread is parked, or
    //                                 // false after PausingStore.TIMEOUT_MILLIS
    //                                 // if the gated call never happened
    //
    //   Thread writer = new Thread(() -> service.book("GHC-4301", 720, 780));
    //   writer.start();
    //   writer.join(PausingStore.TIMEOUT_MILLIS);
    //
    //   gated.release();              // the parked reader returns and finishes
    //   reader.join(PausingStore.TIMEOUT_MILLIS);
    //                                 // note the writer needed no release: it
    //                                 // was never blocked by the parked reader
    //
    // Then assert on the end state, never on the order things happened in. For
    // the harmless scenario above, the writer's booking is stored whatever the
    // reader saw:
    //
    //   assertEquals(1, real.bookingsForRoom("GHC-4301").size());
    //
    // An end-state assertion still means something after you change the code
    // underneath it. A schedule assertion does not.
    // ------------------------------------------------------------------
}
