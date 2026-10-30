import storage.FileRecordStorage;
import storage.ParkingStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Regression test for the exact 90-minute no-show expiry boundary. */
public final class ReservationExpiryTest {
    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("udp-expiry-test-");
        try {
            FileRecordStorage storage = new FileRecordStorage(data);
            storage.initialize(1);
            ParkingStore.UserData user = storage.createUser("expiry@example.test", "Expiry Test", "salt", "hash");
            Instant reservedAt = Instant.parse("2026-01-01T00:00:00Z");
            ParkingStore.ReservationData created = storage.createReservation(
                    user.id(), 1, "EXP1234", "CAR", reservedAt, 60, reservedAt);

            storage.expireReservations(reservedAt.plusSeconds(90 * 60L - 1));
            String beforeCutoff = storage.listReservationsForUser(user.id(), 10).stream()
                    .filter(r -> r.id() == created.id()).findFirst().orElseThrow().status();
            if (!"RESERVED".equals(beforeCutoff)) throw new AssertionError("Reservation expired before 90 minutes: " + beforeCutoff);

            storage.expireReservations(reservedAt.plusSeconds(90 * 60L));
            String atCutoff = storage.listReservationsForUser(user.id(), 10).stream()
                    .filter(r -> r.id() == created.id()).findFirst().orElseThrow().status();
            if (!"EXPIRED".equals(atCutoff)) throw new AssertionError("Reservation was not expired at the 90-minute cutoff: " + atCutoff);

            System.out.println("PASS: reservation stays active before the 90-minute deadline and expires exactly at the deadline.");
        } finally {
            try (var paths = Files.walk(data)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }
}
