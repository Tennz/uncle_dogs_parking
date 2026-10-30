package storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Local CSV implementation for classroom/LAN testing. Online deployment should use PostgreSQL. */
public final class FileRecordStorage implements RecordStorage {
    private final Path dataDir;
    private final List<SlotData> slots = new ArrayList<>();
    private final List<UserData> users = new ArrayList<>();
    private final List<ReservationData> reservations = new ArrayList<>();
    private final List<SessionData> sessions = new ArrayList<>();
    private final List<EntryIntent> intents = new ArrayList<>();
    private final List<FeedbackData> feedback = new ArrayList<>();
    private long nextUser = 1, nextReservation = 1, nextSession = 1, nextFeedback = 1;
    private int nextSlotNumber = 1;

    public FileRecordStorage(Path dataDir) { this.dataDir = dataDir; }

    @Override public synchronized void initialize(int initialSlotCount) throws Exception {
        Files.createDirectories(dataDir);
        boolean slotFileExisted = Files.exists(dataDir.resolve("slots.csv"));
        loadSlots(); loadUsers(); loadReservations(); loadSessions(); loadIntents(); loadFeedback();
        if (!slotFileExisted) {
            for (int n = 1; n <= initialSlotCount; n++) slots.add(new SlotData(n, "ANY", true));
            saveSlots();
        }
        int minimumNext = slots.stream().mapToInt(SlotData::number).max().orElse(0) + 1;
        Path counter = dataDir.resolve("slot_counter.txt");
        if (Files.exists(counter)) {
            try { nextSlotNumber = Math.max(minimumNext, Integer.parseInt(Files.readString(counter, StandardCharsets.UTF_8).trim())); }
            catch (RuntimeException ignored) { nextSlotNumber = minimumNext; }
        } else nextSlotNumber = minimumNext;
        saveSlotCounter();
    }

    @Override public boolean isRemoteDatabase() { return false; }

    @Override public synchronized List<SlotData> listSlots() {
        return slots.stream().sorted(Comparator.comparingInt(SlotData::number)).toList();
    }
    @Override public synchronized SlotData getSlot(int n) { return slots.stream().filter(s -> s.number() == n).findFirst().orElse(null); }
    @Override public synchronized SlotData addSlot(String compatibility) throws Exception {
        String c = normalizeType(compatibility);
        int n = Math.max(nextSlotNumber, slots.stream().mapToInt(SlotData::number).max().orElse(0) + 1);
        if (n == Integer.MAX_VALUE) throw new IllegalStateException("No more slot numbers are available.");
        SlotData item = new SlotData(n, c, true); slots.add(item); nextSlotNumber = n + 1; saveSlots(); saveSlotCounter(); return item;
    }
    @Override public synchronized SlotData setSlotEnabled(int n, boolean enabled) throws Exception {
        SlotData current = getSlot(n);
        if (current == null) throw new IllegalArgumentException("Slot not found.");
        if (!enabled && sessions.stream().anyMatch(s -> s.slotNumber() == n && s.active()))
            throw new IllegalStateException("Check out the parked vehicle before disabling this slot.");
        if (!enabled && reservations.stream().anyMatch(r -> r.slotNumber()==n && r.status().equals("RESERVED")))
            throw new IllegalStateException("Cancel or wait for pending reservations before disabling this slot.");
        SlotData updated = new SlotData(n, current.compatibility(), enabled);
        slots.remove(current); slots.add(updated); saveSlots(); return updated;
    }

    @Override public synchronized void deleteSlot(int n) throws Exception {
        SlotData current = getSlot(n);
        if (current == null) throw new IllegalArgumentException("Slot not found.");
        if (sessions.stream().anyMatch(s -> s.slotNumber() == n && s.active()))
            throw new IllegalStateException("This slot is occupied. Check out the vehicle before deleting it.");
        if (reservations.stream().anyMatch(r -> r.slotNumber() == n && r.status().equals("RESERVED")))
            throw new IllegalStateException("This slot has a pending reservation. Cancel it before deleting the slot.");
        if (sessions.stream().anyMatch(s -> s.slotNumber() == n) || reservations.stream().anyMatch(r -> r.slotNumber() == n))
            throw new IllegalStateException("This slot has booking or visit history. Disable it instead so the records are preserved.");
        if (intents.stream().anyMatch(i -> i.slotNumber() == n && i.status().equals("PREPARED") && i.expiresAt().isAfter(Instant.now())))
            throw new IllegalStateException("A driver has a pending entry for this slot. Try again after the pending entry expires.");
        intents.removeIf(i -> i.slotNumber() == n);
        slots.removeIf(s -> s.number() == n);
        saveSlots();
    }

    @Override public synchronized UserData createUser(String email, String name, String salt, String hash) throws Exception {
        if (users.stream().anyMatch(u -> u.email().equalsIgnoreCase(email))) throw new IllegalArgumentException("An account with that email already exists.");
        UserData u = new UserData(nextUser++, email, name, salt, hash, "USER"); users.add(u); saveUsers(); return u;
    }
    @Override public synchronized UserData findUserByEmail(String email) {
        return users.stream().filter(u -> u.email().equalsIgnoreCase(email)).findFirst().orElse(null);
    }
    @Override public synchronized UserData findUserById(long id) { return users.stream().filter(u -> u.id() == id).findFirst().orElse(null); }

    @Override public synchronized ReservationData createReservation(long userId, int slotNumber, String plate,
            String vehicleType, Instant reservedAt, int duration, Instant now) throws Exception {
        expireReservations(now);
        SlotData slot = requireSlot(slotNumber);
        if (!slot.enabled()) throw new IllegalStateException("This slot is temporarily unavailable.");
        if (!slotAccepts(slot, vehicleType)) throw new IllegalStateException("This slot is not suitable for that vehicle type.");
        if (sessions.stream().anyMatch(s -> s.slotNumber() == slotNumber && s.active())) throw new IllegalStateException("This slot is occupied right now.");
        if (reservedAt.isBefore(now.minusSeconds(15L * 60)) || reservedAt.isAfter(now.plusSeconds(90L * 24 * 3600)))
            throw new IllegalArgumentException("Choose a reservation time from now to 90 days ahead.");
        Instant end = reservedAt.plusSeconds(duration * 60L);
        boolean conflict = reservations.stream().filter(r -> r.slotNumber() == slotNumber && (r.status().equals("RESERVED") || r.status().equals("ACTIVE")))
            .anyMatch(r -> overlaps(reservedAt, end, r.reservedAt(), r.reservedAt().plusSeconds(r.durationMinutes() * 60L)));
        if (conflict) throw new IllegalStateException("That slot is already reserved for an overlapping time. Please choose another time or slot.");
        ReservationData r = new ReservationData(nextReservation++, userId, slotNumber, plate, vehicleType, reservedAt, duration, "RESERVED", now);
        reservations.add(r); saveReservations(); return r;
    }
    @Override public synchronized List<ReservationData> listReservationsForUser(long userId, int limit) {
        return reservations.stream().filter(r -> r.userId() == userId).sorted(Comparator.comparing(ReservationData::createdAt).reversed()).limit(limit).toList();
    }
    @Override public synchronized List<ReservationData> listReservations(int limit) {
        return reservations.stream().sorted(Comparator.comparing(ReservationData::createdAt).reversed()).limit(limit).toList();
    }
    @Override public synchronized boolean cancelReservation(long id, long userId, Instant now) throws Exception {
        expireReservations(now);
        for (int i = 0; i < reservations.size(); i++) {
            ReservationData r = reservations.get(i);
            if (r.id() == id && r.userId() == userId && r.status().equals("RESERVED")) {
                reservations.set(i, copyReservationStatus(r, "CANCELLED")); saveReservations(); return true;
            }
        }
        return false;
    }
    @Override public synchronized boolean adminCancelReservation(long id, Instant now) throws Exception {
        for (int i=0;i<reservations.size();i++) {
            ReservationData r=reservations.get(i);
            if (r.id()==id && r.status().equals("RESERVED")) { reservations.set(i,copyReservationStatus(r,"CANCELLED")); saveReservations(); return true; }
        }
        return false;
    }

    @Override public synchronized EntryIntent prepareEntry(long userId, int n, String plate, String type, int duration, Instant expiresAt) throws Exception {
        SlotData slot = requireSlot(n);
        if (!slot.enabled()) throw new IllegalStateException("This slot is temporarily unavailable.");
        if (!slotAccepts(slot, type)) throw new IllegalStateException("This slot is not suitable for that vehicle type.");
        intents.removeIf(i -> i.userId() == userId && i.status().equals("PREPARED"));
        EntryIntent intent = new EntryIntent(userId, n, plate, type, duration, expiresAt);
        intents.add(intent); saveIntents(); return intent;
    }

    @Override public synchronized EntryIntent findPreparedEntry(long userId, int n, Instant now) {
        return intents.stream().filter(i -> i.userId() == userId && i.slotNumber() == n
                && i.status().equals("PREPARED") && i.expiresAt().isAfter(now)).findFirst().orElse(null);
    }

    @Override public synchronized SessionData enter(long userId, int n, Instant now) throws Exception {
        expireReservations(now);
        SlotData slot = requireSlot(n);
        if (!slot.enabled()) throw new IllegalStateException("This slot is temporarily unavailable.");

        ReservationData reservation = reservations.stream().filter(r -> r.userId() == userId && r.slotNumber() == n && r.status().equals("RESERVED")
                && !now.isBefore(r.reservedAt().minusSeconds(180 * 60L)) && !!now.isBefore(r.reservedAt().plusSeconds(90 * 60L)))
                .findFirst().orElse(null);
        SessionData occupied = sessions.stream().filter(s -> s.slotNumber() == n && s.active()).findFirst().orElse(null);
        if (occupied != null) {
            if (reservation != null && occupied.userId() != userId) throw new IllegalStateException("Your reservation is currently blocked by another parked vehicle. An administrator can see this conflict; please contact them.");
            throw new IllegalStateException("This slot is already occupied.");
        }
        String plate, type; Long reservationId = null; int duration = 60;
        if (reservation != null) {
            Instant plannedEnd = now.plusSeconds(reservation.durationMinutes() * 60L);
            boolean nextConflict = reservations.stream().filter(r -> r.slotNumber() == n && r.id() != reservation.id() && r.status().equals("RESERVED"))
                    .anyMatch(r -> overlaps(now, plannedEnd, r.reservedAt(), r.reservedAt().plusSeconds(r.durationMinutes() * 60L)));
            if (nextConflict) throw new IllegalStateException("You arrived too late to finish the planned parking duration before the next reservation. Contact an administrator for help.");
            plate = reservation.plate(); type = reservation.vehicleType(); duration = reservation.durationMinutes(); reservationId = reservation.id();
            updateReservation(reservation.id(), "ACTIVE");
        } else {
            boolean reservedForAnother = reservations.stream().anyMatch(r -> r.slotNumber() == n && r.status().equals("RESERVED")
                    && r.userId() != userId && !!now.isBefore(r.reservedAt().plusSeconds(90 * 60L))
                    && !now.isBefore(r.reservedAt().minusSeconds(15 * 60L)));
            if (reservedForAnother) throw new IllegalStateException("This slot is reserved for another driver.");
            EntryIntent intent = intents.stream().filter(i -> i.userId() == userId && i.slotNumber() == n && i.expiresAt().isAfter(now)).findFirst().orElse(null);
            if (intent == null) throw new IllegalStateException("Select this slot and enter your plate and vehicle type first, then scan its ENTER QR.");
            plate = intent.plate(); type = intent.vehicleType(); duration = intent.durationMinutes();
            Instant end = now.plusSeconds(intent.durationMinutes() * 60L);
            boolean conflict = reservations.stream().filter(r -> r.slotNumber() == n && r.status().equals("RESERVED"))
                    .anyMatch(r -> overlaps(now, end, r.reservedAt(), r.reservedAt().plusSeconds(r.durationMinutes() * 60L)));
            if (conflict) throw new IllegalStateException("This slot is reserved during your expected parking time.");
        }
        if (!slotAccepts(slot, type)) throw new IllegalStateException("This slot is not suitable for your vehicle type.");
        SessionData record = new SessionData(nextSession++, n, userId, plate, type, now, null, reservationId);
        sessions.add(record);
        int preparedIndex = -1;
        for (int i=0;i<intents.size();i++) { EntryIntent it=intents.get(i); if(it.userId()==userId&&it.slotNumber()==n&&it.status().equals("PREPARED")&&it.expiresAt().isAfter(now)){preparedIndex=i;break;} }
        EntryIntent entered = new EntryIntent(userId, n, plate, type, duration, now.minusMillis(1), "ENTERED", now);
        if(preparedIndex>=0)intents.set(preparedIndex,entered);else intents.add(entered);
        saveIntents(); saveSessions(); saveReservations(); return record;
    }
    @Override public synchronized SessionData leave(long userId, int n, Instant now) throws Exception {
        SessionData active = sessions.stream().filter(s -> s.slotNumber() == n && s.active()).findFirst().orElse(null);
        if (active == null) throw new IllegalStateException("This slot is already free.");
        if (active.userId() != userId) throw new IllegalStateException("This parking session belongs to another account. Please contact an administrator.");
        SessionData ended = copySessionExit(active, now);
        replaceSession(ended);
        if (ended.reservationId() != null) updateReservation(ended.reservationId(), "COMPLETED");
        for (int i=0;i<intents.size();i++) { EntryIntent it=intents.get(i); if(it.userId()==ended.userId()&&it.slotNumber()==n&&it.status().equals("ENTERED")&&ended.entryTime().equals(it.enteredAt())) intents.set(i,new EntryIntent(it.userId(),it.slotNumber(),it.plate(),it.vehicleType(),it.durationMinutes(),it.expiresAt(),"COMPLETED",it.enteredAt())); }
        saveIntents(); saveSessions(); saveReservations(); return ended;
    }
    @Override public synchronized SessionData manualCheckout(int n, Instant now) throws Exception {
        SessionData active = sessions.stream().filter(s -> s.slotNumber() == n && s.active()).findFirst().orElse(null);
        if (active == null) throw new IllegalStateException("There is no vehicle parked in that slot.");
        SessionData ended = copySessionExit(active, now); replaceSession(ended);
        if (ended.reservationId() != null) updateReservation(ended.reservationId(), "COMPLETED");
        for (int i=0;i<intents.size();i++) { EntryIntent it=intents.get(i); if(it.userId()==ended.userId()&&it.slotNumber()==n&&it.status().equals("ENTERED")&&ended.entryTime().equals(it.enteredAt())) intents.set(i,new EntryIntent(it.userId(),it.slotNumber(),it.plate(),it.vehicleType(),it.durationMinutes(),it.expiresAt(),"COMPLETED",it.enteredAt())); }
        saveIntents(); saveSessions(); saveReservations(); return ended;
    }
    @Override public synchronized List<SessionData> listSessions(int limit) {
        return sessions.stream().sorted(Comparator.comparing(SessionData::entryTime).reversed()).limit(limit).toList();
    }
    @Override public synchronized List<SessionData> listActiveSessions() {
        return sessions.stream().filter(SessionData::active).sorted(Comparator.comparingInt(SessionData::slotNumber)).toList();
    }
    @Override public synchronized FeedbackData submitFeedback(long userId, String category, int rating, String message, Instant now) throws Exception {
        UserData user = findUserById(userId);
        if (user == null) throw new IllegalArgumentException("Please sign in before sending feedback.");
        String safeCategory = normalizeFeedbackCategory(category);
        String safeMessage = message == null ? "" : message.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("Choose a rating from 1 to 5.");
        if (safeMessage.length() < 5 || safeMessage.length() > 1000) throw new IllegalArgumentException("Feedback must be between 5 and 1000 characters.");
        FeedbackData row = new FeedbackData(nextFeedback++, userId, user.email(), user.displayName(), safeCategory, rating, safeMessage, "NEW", "", now, now);
        feedback.add(row); saveFeedback(); return row;
    }

    @Override public synchronized List<FeedbackData> listFeedback(int limit) {
        return feedback.stream().sorted(Comparator.comparing(FeedbackData::createdAt).reversed()).limit(Math.max(1, Math.min(limit, 500))).toList();
    }

    @Override public synchronized FeedbackData updateFeedback(long feedbackId, String status, String adminNote, Instant now) throws Exception {
        String next = normalizeFeedbackStatus(status);
        String note = adminNote == null ? "" : adminNote.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (note.length() > 1000) throw new IllegalArgumentException("Admin note must be 1000 characters or fewer.");
        for (int i=0;i<feedback.size();i++) {
            FeedbackData old = feedback.get(i);
            if (old.id() == feedbackId) {
                FeedbackData updated = new FeedbackData(old.id(), old.userId(), old.email(), old.displayName(), old.category(), old.rating(), old.message(), next, note, old.createdAt(), now);
                feedback.set(i, updated); saveFeedback(); return updated;
            }
        }
        throw new IllegalArgumentException("Feedback item not found.");
    }

    private static String normalizeFeedbackCategory(String value) {
        String c = value == null ? "" : value.trim();
        if (!List.of("Suggestion", "Bug report", "User experience", "Other").contains(c)) throw new IllegalArgumentException("Choose a valid feedback category.");
        return c;
    }
    private static String normalizeFeedbackStatus(String value) {
        String s = value == null ? "" : value.trim().toUpperCase();
        if (!List.of("NEW", "REVIEWING", "RESOLVED", "DISMISSED").contains(s)) throw new IllegalArgumentException("Choose a valid feedback status.");
        return s;
    }

    @Override public synchronized void expireReservations(Instant now) throws Exception {
        boolean changed = false;
        for (int i = 0; i < reservations.size(); i++) {
            ReservationData r = reservations.get(i);
            if (r.status().equals("RESERVED") && !now.isBefore(r.reservedAt().plusSeconds(90 * 60L))) {
                reservations.set(i, copyReservationStatus(r, "EXPIRED")); changed = true;
            }
        }
        boolean intentsChanged = false;
        for (int i=0;i<intents.size();i++) { EntryIntent it=intents.get(i); if(it.status().equals("PREPARED")&&!it.expiresAt().isAfter(now)){intents.set(i,new EntryIntent(it.userId(),it.slotNumber(),it.plate(),it.vehicleType(),it.durationMinutes(),it.expiresAt(),"EXPIRED",it.enteredAt()));intentsChanged=true;} }
        if (changed) saveReservations(); if (intentsChanged) saveIntents();
    }

    private SlotData requireSlot(int n) { return slots.stream().filter(s -> s.number() == n).findFirst().orElseThrow(() -> new IllegalArgumentException("Slot not found.")); }
    private static String normalizeType(String s) { String t = s == null ? "ANY" : s.trim().toUpperCase(); if (!List.of("ANY","CAR","MOTORCYCLE").contains(t)) throw new IllegalArgumentException("Choose Any, Car, or Motorcycle."); return t; }
    private static boolean slotAccepts(SlotData slot, String vehicleType) { return slot.compatibility().equals("ANY") || slot.compatibility().equalsIgnoreCase(vehicleType); }
    private static boolean overlaps(Instant a1, Instant a2, Instant b1, Instant b2) { return a1.isBefore(b2) && b1.isBefore(a2); }
    private void updateReservation(long id, String status) { for (int i=0;i<reservations.size();i++) if (reservations.get(i).id()==id) reservations.set(i,copyReservationStatus(reservations.get(i),status)); }
    private static ReservationData copyReservationStatus(ReservationData r, String status) { return new ReservationData(r.id(),r.userId(),r.slotNumber(),r.plate(),r.vehicleType(),r.reservedAt(),r.durationMinutes(),status,r.createdAt()); }
    private static SessionData copySessionExit(SessionData r, Instant time) { return new SessionData(r.id(),r.slotNumber(),r.userId(),r.plate(),r.vehicleType(),r.entryTime(),time,r.reservationId()); }
    private void replaceSession(SessionData updated) { for (int i=0;i<sessions.size();i++) if (sessions.get(i).id()==updated.id()) { sessions.set(i,updated); return; } }

    private void loadSlots() throws IOException { slots.clear(); for(String line:read("slots.csv")) { if(line.startsWith("number,"))continue; List<String> c=CsvUtil.parse(line); try{slots.add(new SlotData(Integer.parseInt(c.get(0)),c.get(1),Boolean.parseBoolean(c.get(2))));}catch(RuntimeException ignored){} } }
    private void loadUsers() throws IOException { users.clear(); for(String line:read("users.csv")) { if(line.startsWith("id,"))continue; List<String> c=CsvUtil.parse(line); try{UserData u=new UserData(Long.parseLong(c.get(0)),c.get(1),c.get(2),c.get(3),c.get(4),c.get(5));users.add(u);nextUser=Math.max(nextUser,u.id()+1);}catch(RuntimeException ignored){} } }
    private void loadReservations() throws IOException { reservations.clear(); for(String line:read("reservations.csv")) { if(line.startsWith("id,"))continue; List<String> c=CsvUtil.parse(line); try{ReservationData r=new ReservationData(Long.parseLong(c.get(0)),Long.parseLong(c.get(1)),Integer.parseInt(c.get(2)),c.get(3),c.get(4),Instant.parse(c.get(5)),Integer.parseInt(c.get(6)),c.get(7),Instant.parse(c.get(8)));reservations.add(r);nextReservation=Math.max(nextReservation,r.id()+1);}catch(RuntimeException ignored){} } }
    private void loadSessions() throws IOException { sessions.clear(); for(String line:read("sessions.csv")) { if(line.startsWith("id,"))continue; List<String> c=CsvUtil.parse(line); try{sessions.add(new SessionData(Long.parseLong(c.get(0)),Integer.parseInt(c.get(1)),Long.parseLong(c.get(2)),c.get(3),c.get(4),Instant.parse(c.get(5)),c.get(6).isBlank()?null:Instant.parse(c.get(6)),c.get(7).isBlank()?null:Long.parseLong(c.get(7))));nextSession=Math.max(nextSession,Long.parseLong(c.get(0))+1);}catch(RuntimeException ignored){} } }
    private void loadIntents() throws IOException { intents.clear(); for(String line:read("entry_intents.csv")) { if(line.startsWith("userId,"))continue; List<String> c=CsvUtil.parse(line); try{String status=c.size()>6&&!c.get(6).isBlank()?c.get(6):"PREPARED";Instant entered=c.size()>7&&!c.get(7).isBlank()?Instant.parse(c.get(7)):null;intents.add(new EntryIntent(Long.parseLong(c.get(0)),Integer.parseInt(c.get(1)),c.get(2),c.get(3),Integer.parseInt(c.get(4)),Instant.parse(c.get(5)),status,entered));}catch(RuntimeException ignored){} } }
    private void loadFeedback() throws IOException {
        feedback.clear();
        for (String line : read("feedback.csv")) {
            if (line.startsWith("id,")) continue;
            List<String> c = CsvUtil.parse(line);
            try {
                long id=Long.parseLong(c.get(0)), userId=Long.parseLong(c.get(1));
                UserData user=findUserById(userId); if(user==null) continue;
                Instant created=Instant.parse(c.get(7)), updated=Instant.parse(c.get(8));
                feedback.add(new FeedbackData(id,userId,user.email(),user.displayName(),c.get(2),Integer.parseInt(c.get(3)),c.get(4),c.get(5),c.get(6),created,updated));
                nextFeedback=Math.max(nextFeedback,id+1);
            } catch (RuntimeException ignored) { }
        }
    }
    private List<String> read(String file) throws IOException { Path p=dataDir.resolve(file); if(!Files.exists(p))return List.of(); return Files.readAllLines(p,StandardCharsets.UTF_8); }
    private void write(String file, String header, List<String> body) throws IOException { List<String> lines=new ArrayList<>();lines.add(header);lines.addAll(body);Files.write(dataDir.resolve(file),lines,StandardCharsets.UTF_8); }
    private void saveSlots() throws IOException {write("slots.csv","number,compatibility,enabled",slots.stream().map(s->CsvUtil.row(""+s.number(),s.compatibility(),""+s.enabled())).toList());}
    private void saveSlotCounter() throws IOException { Files.writeString(dataDir.resolve("slot_counter.txt"), Integer.toString(nextSlotNumber)+"\n", StandardCharsets.UTF_8); }
    private void saveUsers() throws IOException {write("users.csv","id,email,displayName,salt,passwordHash,role",users.stream().map(u->CsvUtil.row(""+u.id(),u.email(),u.displayName(),u.salt(),u.passwordHash(),u.role())).toList());}
    private void saveReservations() throws IOException {write("reservations.csv","id,userId,slotNumber,plate,vehicleType,reservedAt,durationMinutes,status,createdAt",reservations.stream().map(r->CsvUtil.row(""+r.id(),""+r.userId(),""+r.slotNumber(),r.plate(),r.vehicleType(),r.reservedAt().toString(),""+r.durationMinutes(),r.status(),r.createdAt().toString())).toList());}
    private void saveSessions() throws IOException {write("sessions.csv","id,slotNumber,userId,plate,vehicleType,entryTime,exitTime,reservationId",sessions.stream().map(s->CsvUtil.row(""+s.id(),""+s.slotNumber(),""+s.userId(),s.plate(),s.vehicleType(),s.entryTime().toString(),s.exitTime()==null?"":s.exitTime().toString(),s.reservationId()==null?"":s.reservationId().toString())).toList());}
    private void saveIntents() throws IOException {write("entry_intents.csv","userId,slotNumber,plate,vehicleType,durationMinutes,expiresAt,status,enteredAt",intents.stream().map(i->CsvUtil.row(""+i.userId(),""+i.slotNumber(),i.plate(),i.vehicleType(),""+i.durationMinutes(),i.expiresAt().toString(),i.status(),i.enteredAt()==null?"":i.enteredAt().toString())).toList());}
    private void saveFeedback() throws IOException {write("feedback.csv","id,userId,category,rating,message,status,adminNote,createdAt,updatedAt",feedback.stream().map(f->CsvUtil.row(""+f.id(),""+f.userId(),f.category(),""+f.rating(),f.message(),f.status(),f.adminNote(),f.createdAt().toString(),f.updatedAt().toString())).toList());}
    @Override public void close() {}
}
