package model;

import java.time.Instant;

public final class Reservation {
    private final long id;
    private final long userId;
    private final int slotNumber;
    private final String plateNumber;
    private final String vehicleType;
    private final Instant reservedAt;
    private final int durationMinutes;
    private final String status;

    // [ENCAPSULATION] Booking details are immutable and validated on creation.
    public Reservation(long id, long userId, int slotNumber, String plateNumber, String vehicleType,
                       Instant reservedAt, int durationMinutes, String status) {
        if (userId < 1 || slotNumber < 1 || reservedAt == null) throw new IllegalArgumentException("Invalid reservation.");
        if (durationMinutes < 15 || durationMinutes > 720) throw new IllegalArgumentException("Duration must be 15 minutes to 12 hours.");
        this.id = id;
        this.userId = userId;
        this.slotNumber = slotNumber;
        this.plateNumber = plateNumber;
        this.vehicleType = vehicleType;
        this.reservedAt = reservedAt;
        this.durationMinutes = durationMinutes;
        this.status = status;
    }

    public long getId() { return id; }
    public long getUserId() { return userId; }
    public int getSlotNumber() { return slotNumber; }
    public String getPlateNumber() { return plateNumber; }
    public String getVehicleType() { return vehicleType; }
    public Instant getReservedAt() { return reservedAt; }
    public int getDurationMinutes() { return durationMinutes; }
    public String getStatus() { return status; }
}
