package storage;

import java.time.Instant;
import java.util.List;

/** Persistence boundary. Implementations may use CSV files or hosted PostgreSQL. */
public interface ParkingStore extends AutoCloseable {
    record SlotData(int number, String compatibility, boolean enabled) {}
    record UserData(long id, String email, String displayName, String salt, String passwordHash, String role) {}
    record ReservationData(long id, long userId, int slotNumber, String plate, String vehicleType,
                           Instant reservedAt, int durationMinutes, String status, Instant createdAt) {}
    record SessionData(long id, int slotNumber, long userId, String plate, String vehicleType,
                       Instant entryTime, Instant exitTime, Long reservationId) {
        public boolean active() { return exitTime == null; }
        public long durationSeconds(Instant now) {
            return Math.max(0, java.time.Duration.between(entryTime, exitTime == null ? now : exitTime).getSeconds());
        }
    }
    record EntryIntent(long userId, int slotNumber, String plate, String vehicleType,
                       int durationMinutes, Instant expiresAt, String status, Instant enteredAt) {
        public EntryIntent(long userId, int slotNumber, String plate, String vehicleType,
                           int durationMinutes, Instant expiresAt) {
            this(userId, slotNumber, plate, vehicleType, durationMinutes, expiresAt, "PREPARED", null);
        }
    }
    /** Feedback text is user-authored; status/adminNote can only be changed by an administrator. */
    record FeedbackData(long id, long userId, String email, String displayName, String category,
                        int rating, String message, String status, String adminNote,
                        Instant createdAt, Instant updatedAt) {}

    void initialize(int initialSlotCount) throws Exception;
    boolean isRemoteDatabase();
    List<SlotData> listSlots() throws Exception;
    SlotData getSlot(int slotNumber) throws Exception;
    SlotData addSlot(String compatibility) throws Exception;
    SlotData setSlotEnabled(int slotNumber, boolean enabled) throws Exception;
    /** Permanently removes a slot only when it has no bookings, visits, or pending entry intents. */
    void deleteSlot(int slotNumber) throws Exception;
    UserData createUser(String email, String displayName, String salt, String passwordHash) throws Exception;
    UserData findUserByEmail(String email) throws Exception;
    UserData findUserById(long userId) throws Exception;
    ReservationData createReservation(long userId, int slotNumber, String plate, String vehicleType,
                                      Instant reservedAt, int durationMinutes, Instant now) throws Exception;
    List<ReservationData> listReservationsForUser(long userId, int limit) throws Exception;
    List<ReservationData> listReservations(int limit) throws Exception;
    boolean cancelReservation(long reservationId, long userId, Instant now) throws Exception;
    boolean adminCancelReservation(long reservationId, Instant now) throws Exception;
    EntryIntent prepareEntry(long userId, int slotNumber, String plate, String vehicleType,
                             int durationMinutes, Instant expiresAt) throws Exception;
    EntryIntent findPreparedEntry(long userId, int slotNumber, Instant now) throws Exception;
    SessionData enter(long userId, int slotNumber, Instant now) throws Exception;
    SessionData leave(long userId, int slotNumber, Instant now) throws Exception;
    SessionData manualCheckout(int slotNumber, Instant now) throws Exception;
    List<SessionData> listSessions(int limit) throws Exception;
    List<SessionData> listActiveSessions() throws Exception;
    void expireReservations(Instant now) throws Exception;

    FeedbackData submitFeedback(long userId, String category, int rating, String message, Instant now) throws Exception;
    List<FeedbackData> listFeedback(int limit) throws Exception;
    FeedbackData updateFeedback(long feedbackId, String status, String adminNote, Instant now) throws Exception;

    @Override void close();
}
