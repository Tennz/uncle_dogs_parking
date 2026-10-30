package model;

import java.time.Duration;
import java.time.Instant;

public final class ParkingRecord {
    private final long id;
    private final int slotNumber;
    private final long userId;
    private final String plateNumber;
    private final String vehicleType;
    private final Instant entryTime;
    private final Instant exitTime;
    private final Long reservationId;

    // [ENCAPSULATION] A record is an immutable snapshot of one parking session.
    public ParkingRecord(long id, int slotNumber, long userId, String plateNumber, String vehicleType,
                         Instant entryTime, Instant exitTime, Long reservationId) {
        if (slotNumber < 1 || entryTime == null) throw new IllegalArgumentException("Invalid parking record.");
        this.id = id;
        this.slotNumber = slotNumber;
        this.userId = userId;
        this.plateNumber = plateNumber;
        this.vehicleType = vehicleType;
        this.entryTime = entryTime;
        this.exitTime = exitTime;
        this.reservationId = reservationId;
    }

    public long getId() { return id; }
    public int getSlotNumber() { return slotNumber; }
    public long getUserId() { return userId; }
    public String getPlateNumber() { return plateNumber; }
    public String getVehicleType() { return vehicleType; }
    public Instant getEntryTime() { return entryTime; }
    public Instant getExitTime() { return exitTime; }
    public Long getReservationId() { return reservationId; }
    public boolean isActive() { return exitTime == null; }
    public long getDurationSeconds(Instant now) {
        return Math.max(0, Duration.between(entryTime, exitTime == null ? now : exitTime).getSeconds());
    }
}
