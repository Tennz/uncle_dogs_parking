package model;

// [INHERITANCE] Motorcycle reuses shared plate validation from Vehicle.
public final class Motorcycle extends Vehicle {
    public Motorcycle(String plateNumber) { super(plateNumber); }

    // [POLYMORPHISM] Motorcycle supplies its own vehicle type.
    @Override public String getVehicleType() { return "MOTORCYCLE"; }
}
