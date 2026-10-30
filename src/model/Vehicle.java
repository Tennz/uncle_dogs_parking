package model;

// [ABSTRACTION] Common vehicle behavior is defined without creating a generic vehicle directly.
public abstract class Vehicle {
    // [ENCAPSULATION] Keep vehicle details private and validate through the constructor.
    private final String plateNumber;

    protected Vehicle(String plateNumber) {
        String normalized = plateNumber == null ? "" : plateNumber.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9 -]{2,15}")) {
            throw new IllegalArgumentException("Enter a valid plate number (2–15 letters, numbers, spaces, or hyphens).");
        }
        this.plateNumber = normalized;
    }

    public final String getPlateNumber() { return plateNumber; }
    public abstract String getVehicleType();
}
