package service;

import model.Car;
import model.Motorcycle;
import model.ParkingSlot;
import model.Reservation;
import model.Vehicle;
import storage.ParkingStore;
import storage.RecordStorage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Coordinates business rules while keeping persistence behind the RecordStorage abstraction. */
public final class ParkingLot {
    public static final int SLOT_COUNT = 20;
    public static final int RESERVATION_GRACE_MINUTES = 90;
    private static final int ENTRY_INTENT_MINUTES = 10;
    // Reservation holders may check in up to three hours early; the late grace remains 90 minutes.
    public static final int EARLY_CHECKIN_MINUTES = 180;
    private final RecordStorage storage;

    // [POLYMORPHISM] FileRecordStorage and PostgresRecordStorage are used through this interface.
    public ParkingLot(RecordStorage storage) { this.storage = storage; }
    public RecordStorage getStorage() { return storage; }

    private volatile boolean initialized = false;
    // Prevents hammering the database with rapid retry attempts when it is unavailable.
    private volatile long lastInitAttemptMs = 0;
    private static final long DB_RETRY_COOLDOWN_MS = 20_000;

    public void initialize() throws Exception { storage.initialize(SLOT_COUNT); storage.expireReservations(Instant.now()); initialized = true; }
    public boolean isInitialized() { return initialized; }
    /**
     * Retries database initialization if the first attempt failed at startup.
     * Thread-safe. Enforces a 20-second cooldown between attempts so a temporarily
     * unreachable database does not receive a new connection attempt on every request.
     */
    public synchronized void ensureInitialized() throws Exception {
        if (initialized) return;
        long now = System.currentTimeMillis();
        long elapsed = now - lastInitAttemptMs;
        if (lastInitAttemptMs > 0 && elapsed < DB_RETRY_COOLDOWN_MS) {
            long waitSec = (DB_RETRY_COOLDOWN_MS - elapsed) / 1000 + 1;
            throw new IllegalStateException(
                "The database connection is not yet ready. Please try again in " + waitSec + " seconds.");
        }
        lastInitAttemptMs = now;
        System.out.println("[DB] Retrying database initialization...");
        initialize();
        System.out.println("[DB] Database initialized on retry. Service is now fully ready.");
    }

    public List<Map<String,Object>> slotViews() throws Exception {
        Instant now=Instant.now(); storage.expireReservations(now);
        List<ParkingStore.SlotData> slots=storage.listSlots();
        List<ParkingStore.SessionData> active=storage.listActiveSessions();
        List<ParkingStore.ReservationData> reservations=storage.listReservations(5000);
        List<Map<String,Object>> result=new ArrayList<>();
        for(ParkingStore.SlotData storedSlot:slots){
            // [ENCAPSULATION] Rehydrate persisted values through the validated slot model.
            ParkingSlot slotModel=new ParkingSlot(storedSlot.number(),storedSlot.compatibility(),storedSlot.enabled());
            ParkingStore.SessionData session=active.stream().filter(s->s.slotNumber()==slotModel.getNumber()).findFirst().orElse(null);
            // [STATUS] Show slots with any still-active future/current reservation as Reserved.
            // The booking API separately checks time overlap, so users may still reserve this slot for a non-overlapping period.
            ParkingStore.ReservationData booking=reservations.stream()
                .filter(r->r.slotNumber()==slotModel.getNumber()&&r.status().equals("RESERVED"))
                .min(Comparator.comparing(ParkingStore.ReservationData::reservedAt)).orElse(null);
            String status=session!=null?"OCCUPIED":!slotModel.isEnabled()?"DISABLED":booking!=null?"RESERVED":"AVAILABLE";
            Map<String,Object> view=new LinkedHashMap<>();
            view.put("number",slotModel.getNumber());view.put("compatibility",slotModel.getCompatibility());view.put("enabled",slotModel.isEnabled());view.put("status",status);
            if(session!=null){view.put("plate",session.plate());view.put("vehicleType",session.vehicleType());view.put("entryTime",session.entryTime().toString());}
            if(booking!=null){view.put("reservedAt",booking.reservedAt().toString());view.put("reservedForCurrentUserOnly",true);}
            result.add(view);
        }
        return result;
    }

    public List<Map<String,Object>> myReservations(long userId) throws Exception {
        List<Map<String,Object>> result=new ArrayList<>();
        for(ParkingStore.ReservationData r:storage.listReservationsForUser(userId,100)){
            Map<String,Object> m=new LinkedHashMap<>();m.put("id",r.id());m.put("slotNumber",r.slotNumber());m.put("plate",r.plate());m.put("vehicleType",r.vehicleType());m.put("reservedAt",r.reservedAt().toString());m.put("durationMinutes",r.durationMinutes());m.put("status",r.status());result.add(m);
        }
        return result;
    }

    public ParkingStore.ReservationData reserve(long userId,int slot,String plateRaw,String plateConfirm,String vehicleType,Instant reservedAt,int duration) throws Exception {
        Vehicle vehicle=vehicle(vehicleType,plateRaw);
        String confirm=normalizePlate(plateConfirm);
        if(!vehicle.getPlateNumber().equals(confirm))throw new IllegalArgumentException("The plate numbers do not match. Please enter them again.");
        if(duration<15||duration>720)throw new IllegalArgumentException("Choose a parking duration from 15 minutes to 12 hours.");
        if(reservedAt==null)throw new IllegalArgumentException("Choose a reservation date and time.");
        // [ENCAPSULATION] Validate booking fields through the Reservation model before persistence.
        Reservation requested=new Reservation(0,userId,slot,vehicle.getPlateNumber(),vehicle.getVehicleType(),reservedAt,duration,"RESERVED");
        return storage.createReservation(userId,requested.getSlotNumber(),requested.getPlateNumber(),requested.getVehicleType(),requested.getReservedAt(),requested.getDurationMinutes(),Instant.now());
    }

    public void cancelReservation(long userId,long reservationId) throws Exception {
        if(!storage.cancelReservation(reservationId,userId,Instant.now()))throw new IllegalStateException("This reservation cannot be cancelled. It may already be active, completed, or expired.");
    }
    public void adminCancelReservation(long reservationId) throws Exception {
        if(!storage.adminCancelReservation(reservationId,Instant.now()))throw new IllegalStateException("This reservation cannot be cancelled. It may already be active, completed, or expired.");
    }
    public void processExpirations() throws Exception { if (!initialized) return; storage.expireReservations(Instant.now()); }

    public void prepareWalkIn(long userId,int slot,String plateRaw,String plateConfirm,String vehicleType,int duration) throws Exception {
        Vehicle vehicle=vehicle(vehicleType,plateRaw);
        if(!vehicle.getPlateNumber().equals(normalizePlate(plateConfirm)))throw new IllegalArgumentException("The plate numbers do not match. Please enter them again.");
        if(duration<15||duration>720)throw new IllegalArgumentException("Choose a parking duration from 15 minutes to 12 hours.");
        storage.prepareEntry(userId,slot,vehicle.getPlateNumber(),vehicle.getVehicleType(),duration,Instant.now().plusSeconds(ENTRY_INTENT_MINUTES*60L));
    }

    public ParkingStore.SessionData enter(long userId,int slot) throws Exception { return storage.enter(userId,slot,Instant.now()); }
    public ParkingStore.SessionData leave(long userId,int slot) throws Exception { return storage.leave(userId,slot,Instant.now()); }

    /** [VALIDATION] Confirm a plate against the signed-in user's reservation or a prepared walk-in. */
    public ParkingStore.SessionData enterByPlate(long userId,int slot,String rawPlate) throws Exception {
        Instant now=Instant.now();String plate=plateKey(rawPlate);storage.expireReservations(now);
        ParkingStore.SlotData slotData=storage.getSlot(slot);
        if(slotData==null)throw new IllegalArgumentException("Slot not found.");
        if(!slotData.enabled())throw new IllegalStateException("This slot is unavailable.");
        ParkingStore.SessionData active=storage.listActiveSessions().stream().filter(x->x.slotNumber()==slot).findFirst().orElse(null);
        if(active!=null)throw new IllegalStateException("Slot "+slot+" is already occupied.");
        List<ParkingStore.ReservationData> bookings=storage.listReservations(5000).stream()
                .filter(r->r.userId()==userId&&r.slotNumber()==slot&&r.status().equals("RESERVED")
                        &&!now.isBefore(r.reservedAt().minusSeconds(EARLY_CHECKIN_MINUTES*60L))&&!now.isAfter(r.reservedAt().plusSeconds(90*60L)))
                .sorted(Comparator.comparing(ParkingStore.ReservationData::reservedAt)).toList();
        // Match the exact earliest eligible booking that the storage layer will consume.
        // This prevents the plate for a later booking from being recorded against an older pending reservation.
        if(!bookings.isEmpty()){
            ParkingStore.ReservationData earliest=bookings.get(0);
            if(!plateKey(earliest.plate()).equals(plate))throw new IllegalArgumentException("Plate number does not match your earliest eligible reservation for this slot. Contact an administrator if an earlier booking needs to be cancelled.");
            return storage.enter(userId,slot,now);
        }
        ParkingStore.EntryIntent intent=storage.findPreparedEntry(userId,slot,now);
        if(intent!=null){
            if(!plateKey(intent.plate()).equals(plate))throw new IllegalArgumentException("Plate number does not match the prepared vehicle record.");
            return storage.enter(userId,slot,now);
        }
        List<ParkingStore.ReservationData> ownPending=storage.listReservationsForUser(userId,1000).stream()
                .filter(r->r.slotNumber()==slot&&r.status().equals("RESERVED")).toList();
        ParkingStore.ReservationData ownPlateBooking=ownPending.stream().filter(r->plateKey(r.plate()).equals(plate))
                .min(Comparator.comparing(ParkingStore.ReservationData::reservedAt)).orElse(null);
        if(ownPlateBooking!=null && ownPlateBooking.reservedAt().isAfter(now.plusSeconds(EARLY_CHECKIN_MINUTES*60L)))
            throw new IllegalStateException("Your reservation is scheduled for "+ownPlateBooking.reservedAt()+". Entry opens up to 3 hours before the selected arrival time.");
        if(!ownPending.isEmpty())throw new IllegalArgumentException("Plate number does not match your reservation. Check the plate and try again.");
        boolean anotherUserMatch=storage.listReservations(5000).stream().anyMatch(r->r.slotNumber()==slot&&r.status().equals("RESERVED")&&plateKey(r.plate()).equals(plate));
        if(anotherUserMatch)throw new SecurityException("This reservation belongs to another account. Sign in with the account that created it.");
        throw new IllegalStateException("No active reservation or prepared entry matches this slot and plate. Please reserve the slot first.");
    }

    /** [VALIDATION] Public QR links identify the booking by slot and plate, without trusting link previews. */
    public ParkingStore.SessionData enterByPlate(int slot,String rawPlate) throws Exception {
        Instant now=Instant.now();String plate=plateKey(rawPlate);storage.expireReservations(now);
        ParkingStore.SlotData slotData=storage.getSlot(slot);
        if(slotData==null)throw new IllegalArgumentException("Slot not found.");
        if(!slotData.enabled())throw new IllegalStateException("This slot is unavailable.");
        if(storage.listActiveSessions().stream().anyMatch(x->x.slotNumber()==slot))throw new IllegalStateException("Slot "+slot+" is already occupied.");
        List<ParkingStore.ReservationData> pendingForSlot=storage.listReservations(5000).stream()
                .filter(r->r.slotNumber()==slot&&r.status().equals("RESERVED")).toList();
        List<ParkingStore.ReservationData> eligible=pendingForSlot.stream()
                .filter(r->!now.isBefore(r.reservedAt().minusSeconds(EARLY_CHECKIN_MINUTES*60L))
                        &&!now.isAfter(r.reservedAt().plusSeconds(90*60L)))
                .toList();
        ParkingStore.ReservationData match=eligible.stream().filter(r->plateKey(r.plate()).equals(plate))
                .min(Comparator.comparing(ParkingStore.ReservationData::reservedAt)).orElse(null);
        if(match==null){
            ParkingStore.ReservationData plateBooking=pendingForSlot.stream().filter(r->plateKey(r.plate()).equals(plate))
                    .min(Comparator.comparing(ParkingStore.ReservationData::reservedAt)).orElse(null);
            if(plateBooking!=null && plateBooking.reservedAt().isAfter(now.plusSeconds(EARLY_CHECKIN_MINUTES*60L)))
                throw new IllegalStateException("Reservation found for Slot "+slot+", but entry opens up to 3 hours before the scheduled arrival time. Please scan again closer to your arrival.");
            if(!eligible.isEmpty())throw new IllegalArgumentException("Plate number does not match the reservation for Slot "+slot+".");
            if(!pendingForSlot.isEmpty() && plateBooking==null)throw new IllegalArgumentException("That plate does not match a pending reservation for Slot "+slot+".");
            throw new IllegalStateException("No active reservation is available for this slot. Check that the reservation has not expired or been cancelled.");
        }
        ParkingStore.ReservationData earliestForOwner=eligible.stream().filter(r->r.userId()==match.userId())
                .min(Comparator.comparing(ParkingStore.ReservationData::reservedAt)).orElse(match);
        if(!plateKey(earliestForOwner.plate()).equals(plate))throw new IllegalStateException("This account has an earlier unresolved reservation for Slot "+slot+". Ask an administrator to resolve it before entering.");
        return storage.enter(match.userId(),slot,now);
    }

    /** [VALIDATION] Leaving requires an active session for the scanned slot and the same vehicle plate. */
    public ParkingStore.SessionData leaveByPlate(long userId,int slot,String rawPlate) throws Exception {
        String plate=plateKey(rawPlate);ParkingStore.SessionData active=storage.listActiveSessions().stream().filter(x->x.slotNumber()==slot).findFirst().orElse(null);
        if(active==null)throw new IllegalStateException("Slot "+slot+" is already free.");
        if(!plateKey(active.plate()).equals(plate))throw new IllegalArgumentException("Plate number does not match the vehicle currently parked in Slot "+slot+".");
        if(active.userId()!=userId)throw new SecurityException("This parking session belongs to another account. Use the browser QR link and verify the vehicle plate, or contact an administrator.");
        return storage.leave(userId,slot,Instant.now());
    }

    /** Plate-verified LEAVE flow for Google Lens or another external QR reader. */
    public ParkingStore.SessionData leaveByPlate(int slot,String rawPlate) throws Exception {
        String plate=plateKey(rawPlate);ParkingStore.SessionData active=storage.listActiveSessions().stream().filter(x->x.slotNumber()==slot).findFirst().orElse(null);
        if(active==null)throw new IllegalStateException("Slot "+slot+" is already free.");
        if(!plateKey(active.plate()).equals(plate))throw new IllegalArgumentException("Plate number does not match the vehicle currently parked in Slot "+slot+".");
        return storage.leave(active.userId(),slot,Instant.now());
    }
    public ParkingStore.SessionData adminCheckout(int slot) throws Exception { return storage.manualCheckout(slot,Instant.now()); }
    public ParkingStore.SlotData addSlot(String compatibility) throws Exception { return storage.addSlot(compatibility); }
    public ParkingStore.SlotData setSlotEnabled(int slot,boolean enabled) throws Exception { return storage.setSlotEnabled(slot,enabled); }
    public void deleteSlot(int slot) throws Exception { storage.deleteSlot(slot); }

    public Map<String,Object> adminSummary() throws Exception {
        Instant now=Instant.now();storage.expireReservations(now);
        List<ParkingStore.SessionData> active=storage.listActiveSessions();
        List<ParkingStore.SlotData> slots=storage.listSlots();
        long available=0,reserved=0,occupied=0,disabled=0;
        List<Map<String,Object>> slotViews=slotViews();
        for(Map<String,Object> s:slotViews){switch((String)s.get("status")){case "AVAILABLE"->available++;case "RESERVED"->reserved++;case "OCCUPIED"->occupied++;default->disabled++;}}
        Map<String,Object> out=new LinkedHashMap<>();out.put("total",slots.size());out.put("available",available);out.put("reserved",reserved);out.put("occupied",occupied);out.put("disabled",disabled);
        List<Map<String,Object>> vehicles=new ArrayList<>();
        for(ParkingStore.SessionData s:active){Map<String,Object> m=sessionMap(s,now);m.put("userEmail",emailFor(s.userId()));vehicles.add(m);}
        List<Map<String,Object>> history=new ArrayList<>();for(ParkingStore.SessionData s:storage.listSessions(100)){Map<String,Object> m=sessionMap(s,now);m.put("userEmail",emailFor(s.userId()));history.add(m);}
        List<ParkingStore.ReservationData> allBookings=storage.listReservations(5000);
        List<Map<String,Object>> bookings=new ArrayList<>();for(ParkingStore.ReservationData r:allBookings.stream().limit(100).toList()){Map<String,Object> m=new LinkedHashMap<>();m.put("id",r.id());m.put("userId",r.userId());m.put("slotNumber",r.slotNumber());m.put("plate",r.plate());m.put("vehicleType",r.vehicleType());m.put("reservedAt",r.reservedAt().toString());m.put("durationMinutes",r.durationMinutes());m.put("status",r.status());bookings.add(m);}
        List<Map<String,Object>> conflicts=new ArrayList<>();
        for(ParkingStore.SessionData parked:active){for(ParkingStore.ReservationData booking:allBookings){if(booking.slotNumber()==parked.slotNumber()&&booking.status().equals("RESERVED")&&booking.userId()!=parked.userId()&&booking.reservedAt().plusSeconds(RESERVATION_GRACE_MINUTES*60L).isAfter(now)){
            Map<String,Object> m=new LinkedHashMap<>();m.put("slotNumber",parked.slotNumber());m.put("reservationId",booking.id());m.put("reservedAt",booking.reservedAt().toString());m.put("reservedPlate",booking.plate());m.put("activePlate",parked.plate());m.put("activeEntryTime",parked.entryTime().toString());m.put("message","Slot is occupied while another user's reservation is still pending. Verify and resolve before their arrival window ends.");conflicts.add(m);
        }}}
        out.put("vehicles",vehicles);out.put("history",history);out.put("reservations",bookings);out.put("conflicts",conflicts);out.put("slots",slotViews);return out;
    }
    private String emailFor(long userId) { try {var u=storage.findUserById(userId);return u==null?"":u.email();}catch(Exception e){return "";} }
    private static Map<String,Object> sessionMap(ParkingStore.SessionData s,Instant now){
        // [ENCAPSULATION] The domain record owns duration calculation from entry/exit timestamps.
        model.ParkingRecord record=new model.ParkingRecord(s.id(),s.slotNumber(),s.userId(),s.plate(),s.vehicleType(),s.entryTime(),s.exitTime(),s.reservationId());
        Map<String,Object> m=new LinkedHashMap<>();m.put("id",record.getId());m.put("slotNumber",record.getSlotNumber());m.put("userId",record.getUserId());m.put("plate",record.getPlateNumber());m.put("vehicleType",record.getVehicleType());m.put("entryTime",record.getEntryTime().toString());m.put("exitTime",record.getExitTime()==null?null:record.getExitTime().toString());m.put("durationSeconds",record.getDurationSeconds(now));m.put("active",record.isActive());return m;
    }

    private static Vehicle vehicle(String type,String plate){String t=type==null?"":type.trim().toUpperCase();return switch(t){case "CAR"->new Car(plate);case "MOTORCYCLE"->new Motorcycle(plate);default->throw new IllegalArgumentException("Choose Car or Motorcycle.");};}
    private static String normalizePlate(String raw){return (raw==null?"":raw.trim().toUpperCase());}
    private static String plateKey(String raw){
        String normalized=normalizePlate(raw);
        if(!normalized.matches("[A-Z0-9 -]{2,15}"))throw new IllegalArgumentException("Enter a valid plate number.");
        return normalized.replaceAll("[\\s-]","");
    }
}
