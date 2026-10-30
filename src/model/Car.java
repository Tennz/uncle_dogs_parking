package model;

// [INHERITANCE] Car reuses shared plate validation from Vehicle.
public final class Car extends Vehicle {
    public Car(String plateNumber) { super(plateNumber); }

    // [POLYMORPHISM] Car supplies its own vehicle type.
    @Override public String getVehicleType() { return "CAR"; }
}
