package model;

public final class ParkingSlot {
    private final int number;
    private final String compatibility;
    private final boolean enabled;

    // [ENCAPSULATION] The slot validates its own identity and compatibility.
    public ParkingSlot(int number, String compatibility, boolean enabled) {
        if (number < 1) throw new IllegalArgumentException("Slot number must be positive.");
        String value = compatibility == null ? "ANY" : compatibility.trim().toUpperCase();
        if (!value.equals("ANY") && !value.equals("CAR") && !value.equals("MOTORCYCLE")) {
            throw new IllegalArgumentException("Slot type must be Any, Car, or Motorcycle.");
        }
        this.number = number;
        this.compatibility = value;
        this.enabled = enabled;
    }

    public int getNumber() { return number; }
    public String getCompatibility() { return compatibility; }
    public boolean isEnabled() { return enabled; }
    public boolean accepts(String vehicleType) {
        return compatibility.equals("ANY") || compatibility.equalsIgnoreCase(vehicleType);
    }
}
