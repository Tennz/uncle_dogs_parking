package storage;

import java.sql.*;
import java.time.Instant;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** Hosted PostgreSQL storage. All state-changing operations use database transactions. */
public final class PostgresRecordStorage implements RecordStorage {
    private final String url;
    private final String transactionFallbackUrl;
    private final String user;
    private final String password;
    private volatile String activeUrl;
    private volatile boolean announcedTransactionFallback;

    public PostgresRecordStorage(String url, String user, String password) {
        ParsedConnection parsed = parseConnection(url, user, password);
        this.url = parsed.url();
        this.transactionFallbackUrl = buildTransactionFallback(this.url);
        this.activeUrl = this.url;
        this.user = parsed.user();
        this.password = parsed.password();
    }

    private static ParsedConnection parseConnection(String raw, String user, String password) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("DATABASE_URL is empty.");
        String value = raw.trim();
        String actualUser = user == null ? "" : user.trim();
        String actualPassword = password == null ? "" : password;
        if (value.startsWith("postgresql://")) {
            try {
                java.net.URI uri = java.net.URI.create(value);
                if ((actualUser.isBlank() || actualPassword.isBlank()) && uri.getUserInfo() != null) {
                    String[] credential = uri.getUserInfo().split(":", 2);
                    if (actualUser.isBlank()) actualUser = java.net.URLDecoder.decode(credential[0].replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
                    if (actualPassword.isBlank() && credential.length > 1) actualPassword = java.net.URLDecoder.decode(credential[1].replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
                }
                String host = uri.getHost();
                if (host == null) throw new IllegalArgumentException("DATABASE_URL does not contain a valid database host.");
                String path = uri.getRawPath() == null ? "/postgres" : uri.getRawPath();
                String query = uri.getRawQuery();
                value = "jdbc:postgresql://" + host + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + path + (query == null ? "" : "?" + query);
            } catch (RuntimeException ex) {
                if (ex instanceof IllegalArgumentException && ex.getMessage()!=null && ex.getMessage().startsWith("DATABASE_URL")) throw ex;
                throw new IllegalArgumentException("DATABASE_URL is not a valid Supabase PostgreSQL connection URL.");
            }
        }
        if (!value.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException("DATABASE_URL must be a PostgreSQL or JDBC PostgreSQL URL from Supabase.");
        if (!value.contains("sslmode=")) value += value.contains("?") ? "&sslmode=require" : "?sslmode=require";
        // Transaction pooler (PgBouncer port 6543) requires disabling JDBC server-side prepared statements.
        if (!value.contains("prepareThreshold=") && isTransactionPoolerUrl(value)) {
            value += value.contains("?") ? "&prepareThreshold=0" : "?prepareThreshold=0";
        }
        if (!value.contains("connectTimeout=")) value += value.contains("?") ? "&connectTimeout=10" : "?connectTimeout=10";
        if (!value.contains("socketTimeout=")) value += value.contains("?") ? "&socketTimeout=30" : "?socketTimeout=30";
        if (!value.contains("tcpKeepAlive=")) value += value.contains("?") ? "&tcpKeepAlive=true" : "?tcpKeepAlive=true";
        return new ParsedConnection(value, actualUser, actualPassword);
    }

    private static boolean isTransactionPoolerUrl(String jdbcUrl) {
        int authorityStart = "jdbc:postgresql://".length();
        int pathStart = jdbcUrl.indexOf('/', authorityStart);
        if (pathStart < 0) return false;
        String authority = jdbcUrl.substring(authorityStart, pathStart);
        return authority.toLowerCase(java.util.Locale.ROOT).contains(".pooler.supabase.com")
                && authority.endsWith(":6543");
    }

    static String buildTransactionFallback(String jdbcUrl) {
        // If the Supabase session pooler (5432) is blocked, try the transaction pooler (6543).
        // Only constructed for Supabase shared pooler; never rewrites a direct database host.
        int authorityStart = "jdbc:postgresql://".length();
        int pathStart = jdbcUrl.indexOf('/', authorityStart);
        if (!jdbcUrl.startsWith("jdbc:postgresql://") || pathStart < 0) return null;
        String authority = jdbcUrl.substring(authorityStart, pathStart);
        if (!authority.toLowerCase(java.util.Locale.ROOT).contains(".pooler.supabase.com") || !authority.endsWith(":5432")) return null;
        String hostWithoutPort = authority.substring(0, authority.length() - ":5432".length());
        String alternate = jdbcUrl.substring(0, authorityStart) + hostWithoutPort + ":6543" + jdbcUrl.substring(pathStart);
        if (!alternate.contains("prepareThreshold=")) alternate += alternate.contains("?") ? "&prepareThreshold=0" : "?prepareThreshold=0";
        return alternate;
    }

    private record ParsedConnection(String url, String user, String password) {}

    private Connection openConnection(String connectionUrl) throws SQLException {
        if (user.isBlank() && password.isBlank()) return DriverManager.getConnection(connectionUrl);
        return DriverManager.getConnection(connectionUrl, user, password);
    }

    private Connection connection() throws SQLException {
        String selected = activeUrl;
        try {
            return openConnection(selected);
        } catch (SQLException firstFailure) {
            logConnectionFailure(selected, firstFailure);
            if (!selected.equals(url) || transactionFallbackUrl == null || !isConnectivityFailure(firstFailure)) {
                throw firstFailure;
            }
            System.err.println("[DB] Session pooler connection failed. Retrying on shared transaction pooler (port 6543).");
            System.err.println("[DB] Transaction mode requires prepareThreshold=0; this is configured automatically.");
            try {
                Connection connection = openConnection(transactionFallbackUrl);
                activeUrl = transactionFallbackUrl;
                if (!announcedTransactionFallback) {
                    announcedTransactionFallback = true;
                    System.out.println("[DB] Connected via transaction pooler (port 6543). prepareThreshold=0 is active.");
                }
                return connection;
            } catch (SQLException fallbackFailure) {
                logConnectionFailure(transactionFallbackUrl, fallbackFailure);
                firstFailure.addSuppressed(fallbackFailure);
                throw new SQLException(
                        "Supabase could not be reached on either shared pooler port 5432 or 6543. " +
                        "Verify the host and username in Supabase Connect > Connection String > URI, " +
                        "confirm the Supabase project is not paused (Dashboard > Settings > Paused), " +
                        "then check Vercel Environment Variables for DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD. " +
                        "The password was not validated because the network or SSL handshake did not complete.",
                        firstFailure.getSQLState(), firstFailure.getErrorCode(), firstFailure);
            }
        }
    }

    /**
     * Logs a categorized connection failure. Never prints passwords, admin keys, or session secrets.
     * Categories: DNS, TCP, SSL, Authentication, Connection limit, Schema, Other.
     */
    private static void logConnectionFailure(String jdbcUrl, SQLException e) {
        String safeHost = extractSafeHost(jdbcUrl);
        System.err.println("[DB] Connection failed to " + safeHost);
        // Walk the cause chain to find the network-level failure first.
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException uhe) {
                System.err.println("[DB] DIAGNOSIS: DNS resolution failed — the database hostname could not be resolved.");
                System.err.println("[DB] Check that DATABASE_URL contains the exact Supabase pooler hostname (e.g. aws-0-ap-southeast-1.pooler.supabase.com).");
                System.err.println("[DB] DNS failure detail: " + uhe.getMessage());
                return;
            }
            if (t instanceof ConnectException ce) {
                System.err.println("[DB] DIAGNOSIS: TCP connection refused or timed out — the host is reachable but the port is blocked or closed.");
                System.err.println("[DB] Verify the port in DATABASE_URL. Transaction pooler = 6543; Session pooler = 5432.");
                System.err.println("[DB] TCP detail: " + ce.getMessage());
                return;
            }
            if (t instanceof SocketTimeoutException ste) {
                System.err.println("[DB] DIAGNOSIS: TCP socket timeout — no response within the connect/socket timeout.");
                System.err.println("[DB] The Supabase project may be paused. Check Dashboard > Settings > Paused Projects.");
                System.err.println("[DB] Timeout detail: " + ste.getMessage());
                return;
            }
            if (t instanceof NoRouteToHostException nrhe) {
                System.err.println("[DB] DIAGNOSIS: No route to host — network path to Supabase is blocked.");
                System.err.println("[DB] Detail: " + nrhe.getMessage());
                return;
            }
        }
        // No network exception in chain; inspect the SQLState for the cause category.
        String state = e.getSQLState();
        String msg = e.getMessage() == null ? "(no message)" : e.getMessage();
        if ("28P01".equals(state) || "28000".equals(state)) {
            System.err.println("[DB] DIAGNOSIS: Authentication failed — DATABASE_USER or DATABASE_PASSWORD is wrong.");
            System.err.println("[DB] Use the Supabase pooler username (e.g. postgres.abcdefgh) from Dashboard > Connect > URI.");
            System.err.println("[DB] SQLState: " + state);
        } else if ("3D000".equals(state)) {
            System.err.println("[DB] DIAGNOSIS: Database does not exist — check the database name in DATABASE_URL (usually 'postgres').");
        } else if ("53300".equals(state)) {
            System.err.println("[DB] DIAGNOSIS: Too many connections — Supabase pooler connection limit reached. Wait and retry.");
        } else if ("53200".equals(state)) {
            System.err.println("[DB] DIAGNOSIS: Out of memory on the database server.");
        } else if (state != null && state.startsWith("08")) {
            System.err.println("[DB] DIAGNOSIS: Network/connection protocol error (SQLState " + state + ").");
            System.err.println("[DB] This may indicate an SSL negotiation failure. Ensure sslmode=require is in DATABASE_URL.");
            System.err.println("[DB] Detail: " + msg);
        } else if (state != null && state.startsWith("42")) {
            System.err.println("[DB] DIAGNOSIS: SQL schema or object error (SQLState " + state + "): " + msg);
            System.err.println("[DB] The database schema may be incomplete. Re-run the application to re-initialize.");
        } else if (state != null && (state.startsWith("57") || state.startsWith("58"))) {
            System.err.println("[DB] DIAGNOSIS: Database initialization or system error (SQLState " + state + "): " + msg);
        } else {
            System.err.println("[DB] DIAGNOSIS: Unclassified database error (SQLState=" + state + "): " + msg);
        }
    }

    /** Extracts the host:port from a JDBC URL without exposing credentials. */
    private static String extractSafeHost(String jdbcUrl) {
        try {
            int start = "jdbc:postgresql://".length();
            int slash = jdbcUrl.indexOf('/', start);
            String authority = slash > 0 ? jdbcUrl.substring(start, slash) : jdbcUrl.substring(start);
            int at = authority.lastIndexOf('@');
            return at >= 0 ? authority.substring(at + 1) : authority;
        } catch (Exception ignored) {
            return "(unknown host)";
        }
    }

    private static boolean isConnectivityFailure(SQLException error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof ConnectException ||
                    t instanceof UnknownHostException || t instanceof NoRouteToHostException) return true;
        }
        String state = error.getSQLState();
        return state != null && state.startsWith("08");
    }

    @Override public boolean isRemoteDatabase() { return true; }

    @Override public void initialize(int initialSlotCount) throws Exception {
        System.out.println("[DB] Initializing database schema...");
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS udp_slots (slot_number INTEGER PRIMARY KEY CHECK (slot_number > 0), compatibility VARCHAR(16) NOT NULL DEFAULT 'ANY' CHECK (compatibility IN ('ANY','CAR','MOTORCYCLE')), enabled BOOLEAN NOT NULL DEFAULT TRUE)");
            s.execute("CREATE TABLE IF NOT EXISTS udp_slot_counter (id INTEGER PRIMARY KEY CHECK (id=1), next_slot INTEGER NOT NULL CHECK (next_slot > 0), initialized BOOLEAN NOT NULL DEFAULT FALSE)");
            s.execute("CREATE TABLE IF NOT EXISTS udp_users (id BIGSERIAL PRIMARY KEY, email VARCHAR(254) NOT NULL UNIQUE, display_name VARCHAR(80) NOT NULL, salt VARCHAR(128) NOT NULL, password_hash VARCHAR(256) NOT NULL, role VARCHAR(16) NOT NULL DEFAULT 'USER', created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
            s.execute("CREATE TABLE IF NOT EXISTS udp_reservations (id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES udp_users(id), slot_number INTEGER NOT NULL REFERENCES udp_slots(slot_number), plate VARCHAR(15) NOT NULL, vehicle_type VARCHAR(16) NOT NULL CHECK (vehicle_type IN ('CAR','MOTORCYCLE')), reserved_at TIMESTAMPTZ NOT NULL, duration_minutes INTEGER NOT NULL CHECK (duration_minutes BETWEEN 15 AND 720), status VARCHAR(16) NOT NULL DEFAULT 'RESERVED', created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
            s.execute("CREATE INDEX IF NOT EXISTS udp_reservations_slot_time_idx ON udp_reservations(slot_number, reserved_at, status)");
            s.execute("CREATE INDEX IF NOT EXISTS udp_reservations_user_idx ON udp_reservations(user_id, created_at DESC)");
            s.execute("CREATE TABLE IF NOT EXISTS udp_sessions (id BIGSERIAL PRIMARY KEY, slot_number INTEGER NOT NULL REFERENCES udp_slots(slot_number), user_id BIGINT NOT NULL REFERENCES udp_users(id), plate VARCHAR(15) NOT NULL, vehicle_type VARCHAR(16) NOT NULL CHECK (vehicle_type IN ('CAR','MOTORCYCLE')), entry_time TIMESTAMPTZ NOT NULL, exit_time TIMESTAMPTZ NULL, reservation_id BIGINT NULL REFERENCES udp_reservations(id))");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS udp_one_active_session_per_slot ON udp_sessions(slot_number) WHERE exit_time IS NULL");
            s.execute("CREATE INDEX IF NOT EXISTS udp_sessions_history_idx ON udp_sessions(entry_time DESC)");
            s.execute("CREATE TABLE IF NOT EXISTS udp_entry_intents (id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES udp_users(id) ON DELETE CASCADE, slot_number INTEGER NOT NULL REFERENCES udp_slots(slot_number), plate VARCHAR(15) NOT NULL, vehicle_type VARCHAR(16) NOT NULL CHECK (vehicle_type IN ('CAR','MOTORCYCLE')), duration_minutes INTEGER NOT NULL, expires_at TIMESTAMPTZ NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PREPARED', entered_at TIMESTAMPTZ NULL)");
            s.execute("ALTER TABLE udp_entry_intents ADD COLUMN IF NOT EXISTS id BIGSERIAL");
            s.execute("ALTER TABLE udp_entry_intents ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'PREPARED'");
            s.execute("ALTER TABLE udp_entry_intents ADD COLUMN IF NOT EXISTS entered_at TIMESTAMPTZ NULL");
            s.execute("DO $$ DECLARE pk_cols TEXT; BEGIN SELECT string_agg(a.attname, ',' ORDER BY k.ord) INTO pk_cols FROM pg_constraint c JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON TRUE JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.attnum WHERE c.conrelid='udp_entry_intents'::regclass AND c.contype='p'; IF pk_cols IS DISTINCT FROM 'id' THEN ALTER TABLE udp_entry_intents DROP CONSTRAINT IF EXISTS udp_entry_intents_pkey; ALTER TABLE udp_entry_intents ADD CONSTRAINT udp_entry_intents_pkey PRIMARY KEY (id); END IF; END $$");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS udp_entry_intents_one_prepared_per_user ON udp_entry_intents(user_id) WHERE status='PREPARED'");
            s.execute("CREATE TABLE IF NOT EXISTS udp_feedback (id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES udp_users(id) ON DELETE CASCADE, category VARCHAR(32) NOT NULL CHECK (category IN ('Suggestion','Bug report','User experience','Other')), rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5), message VARCHAR(1000) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','REVIEWING','RESOLVED','DISMISSED')), admin_note VARCHAR(1000) NOT NULL DEFAULT '', created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now())");
            s.execute("CREATE INDEX IF NOT EXISTS udp_feedback_created_idx ON udp_feedback(created_at DESC)");
            s.execute("CREATE INDEX IF NOT EXISTS udp_feedback_status_idx ON udp_feedback(status, created_at DESC)");
        }
        System.out.println("[DB] Schema DDL complete. Initializing slot counter...");
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement p = c.prepareStatement("INSERT INTO udp_slot_counter(id,next_slot,initialized) VALUES (1,?,FALSE) ON CONFLICT (id) DO NOTHING")) {
                    p.setInt(1, initialSlotCount + 1); p.executeUpdate();
                }
                boolean initialized;
                try (PreparedStatement p = c.prepareStatement("SELECT initialized FROM udp_slot_counter WHERE id=1 FOR UPDATE"); ResultSet r = p.executeQuery()) {
                    if (!r.next()) throw new IllegalStateException("Slot counter could not be initialized.");
                    initialized = r.getBoolean(1);
                }
                if (!initialized) {
                    int existing;
                    try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM udp_slots"); ResultSet r = p.executeQuery()) { r.next(); existing = r.getInt(1); }
                    if (existing == 0) {
                        try (PreparedStatement p = c.prepareStatement("INSERT INTO udp_slots(slot_number,compatibility,enabled) VALUES (?, 'ANY', TRUE) ON CONFLICT (slot_number) DO NOTHING")) {
                            for (int n = 1; n <= initialSlotCount; n++) { p.setInt(1, n); p.addBatch(); }
                            p.executeBatch();
                        }
                    }
                    try (Statement st = c.createStatement()) {
                        st.executeUpdate("UPDATE udp_slot_counter SET initialized=TRUE, next_slot=GREATEST(next_slot, COALESCE((SELECT MAX(slot_number)+1 FROM udp_slots),1)) WHERE id=1");
                    }
                } else {
                    try (Statement st = c.createStatement()) {
                        st.executeUpdate("UPDATE udp_slot_counter SET next_slot=GREATEST(next_slot, COALESCE((SELECT MAX(slot_number)+1 FROM udp_slots),1)) WHERE id=1");
                    }
                }
                c.commit();
                System.out.println("[DB] Database initialization complete.");
            } catch (Exception e) { c.rollback(); throw e; }
        }
    }

    @Override public List<SlotData> listSlots() throws Exception {
        List<SlotData> out = new ArrayList<>();
        try (Connection c=connection(); PreparedStatement p=c.prepareStatement("SELECT slot_number,compatibility,enabled FROM udp_slots ORDER BY slot_number"); ResultSet r=p.executeQuery()) {
            while(r.next()) out.add(new SlotData(r.getInt(1),r.getString(2),r.getBoolean(3)));
        } return out;
    }
    @Override public SlotData getSlot(int n) throws Exception {
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT slot_number,compatibility,enabled FROM udp_slots WHERE slot_number=?")) {
            p.setInt(1,n);try(ResultSet r=p.executeQuery()){return r.next()?new SlotData(r.getInt(1),r.getString(2),r.getBoolean(3)):null;}
        }
    }
    @Override public SlotData addSlot(String compatibility) throws Exception {
        String type=normalizeCompatibility(compatibility);
        try(Connection c=connection()) { c.setAutoCommit(false); try {
            try(PreparedStatement lock=c.prepareStatement("SELECT pg_advisory_xact_lock(-1)")){lock.execute();}
            int n; try(PreparedStatement p=c.prepareStatement("SELECT next_slot FROM udp_slot_counter WHERE id=1 FOR UPDATE");ResultSet r=p.executeQuery()){if(!r.next())throw new IllegalStateException("Slot counter is missing. Restart the application to initialize it.");n=r.getInt(1);}
            if(n==Integer.MAX_VALUE)throw new IllegalStateException("No more slot numbers are available.");
            try(PreparedStatement p=c.prepareStatement("INSERT INTO udp_slots(slot_number,compatibility,enabled) VALUES(?,?,TRUE)")){p.setInt(1,n);p.setString(2,type);p.executeUpdate();}
            try(PreparedStatement p=c.prepareStatement("UPDATE udp_slot_counter SET next_slot=? WHERE id=1")){p.setInt(1,n+1);p.executeUpdate();}
            c.commit();return new SlotData(n,type,true);
        } catch(Exception e){c.rollback();throw e;} }
    }
    @Override public SlotData setSlotEnabled(int n, boolean enabled) throws Exception {
        try(Connection c=connection()) {c.setAutoCommit(false);try{lockSlot(c,n);if(!enabled && hasActiveSession(c,n))throw new IllegalStateException("Check out the parked vehicle before disabling this slot.");
            if(!enabled){try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM udp_reservations WHERE slot_number=? AND status='RESERVED' LIMIT 1")){p.setInt(1,n);try(ResultSet r=p.executeQuery()){if(r.next())throw new IllegalStateException("Cancel or wait for pending reservations before disabling this slot.");}}}
            try(PreparedStatement p=c.prepareStatement("UPDATE udp_slots SET enabled=? WHERE slot_number=?")){p.setBoolean(1,enabled);p.setInt(2,n);if(p.executeUpdate()==0)throw new IllegalArgumentException("Slot not found.");}
            SlotData result;try(PreparedStatement p=c.prepareStatement("SELECT slot_number,compatibility,enabled FROM udp_slots WHERE slot_number=?")){p.setInt(1,n);try(ResultSet r=p.executeQuery()){r.next();result=new SlotData(r.getInt(1),r.getString(2),r.getBoolean(3));}}
            c.commit();return result;
        }catch(Exception e){c.rollback();throw e;}}
    }

    @Override public void deleteSlot(int n) throws Exception {
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            try {
                lockSlot(c, n);
                if (hasActiveSession(c, n))
                    throw new IllegalStateException("This slot is occupied. Check out the vehicle before deleting it.");
                try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM udp_reservations WHERE slot_number=? AND status='RESERVED' LIMIT 1")) {
                    p.setInt(1, n);
                    try (ResultSet r = p.executeQuery()) {
                        if (r.next()) throw new IllegalStateException("This slot has a pending reservation. Cancel it before deleting the slot.");
                    }
                }
                try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM udp_sessions WHERE slot_number=? UNION ALL SELECT 1 FROM udp_reservations WHERE slot_number=? LIMIT 1")) {
                    p.setInt(1, n); p.setInt(2, n);
                    try (ResultSet r = p.executeQuery()) {
                        if (r.next()) throw new IllegalStateException("This slot has booking or visit history. Disable it instead so the records are preserved.");
                    }
                }
                try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM udp_entry_intents WHERE slot_number=? AND status='PREPARED' AND expires_at>now() LIMIT 1")) {
                    p.setInt(1, n);
                    try (ResultSet r = p.executeQuery()) {
                        if (r.next()) throw new IllegalStateException("A driver has a pending entry for this slot. Try again after the pending entry expires.");
                    }
                }
                try (PreparedStatement p = c.prepareStatement("DELETE FROM udp_entry_intents WHERE slot_number=?")) { p.setInt(1, n); p.executeUpdate(); }
                try (PreparedStatement p = c.prepareStatement("DELETE FROM udp_slots WHERE slot_number=?")) {
                    p.setInt(1, n);
                    if (p.executeUpdate() != 1) throw new IllegalArgumentException("Slot not found.");
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    @Override public UserData createUser(String email, String name, String salt, String hash) throws Exception {
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            try {
                UserData user;
                try (PreparedStatement p = c.prepareStatement(
                        "INSERT INTO udp_users(email,display_name,salt,password_hash,role) " +
                        "VALUES(?,?,?,?,'USER') RETURNING id,email,display_name,salt,password_hash,role")) {
                    p.setString(1, email); p.setString(2, name); p.setString(3, salt); p.setString(4, hash);
                    try (ResultSet r = p.executeQuery()) {
                        if (!r.next()) throw new SQLException("User INSERT did not return a row.");
                        user = user(r);
                    }
                }
                c.commit();
                return user;
            } catch (SQLException e) {
                c.rollback();
                if ("23505".equals(e.getSQLState())) throw new IllegalArgumentException("An account with that email already exists.");
                throw e;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    @Override public UserData findUserByEmail(String email) throws Exception {
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT id,email,display_name,salt,password_hash,role FROM udp_users WHERE lower(email)=lower(?)")){p.setString(1,email);try(ResultSet r=p.executeQuery()){return r.next()?user(r):null;}}
    }
    @Override public UserData findUserById(long id) throws Exception {
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT id,email,display_name,salt,password_hash,role FROM udp_users WHERE id=?")){p.setLong(1,id);try(ResultSet r=p.executeQuery()){return r.next()?user(r):null;}}
    }
    private static UserData user(ResultSet r)throws SQLException{return new UserData(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6));}

    @Override public ReservationData createReservation(long userId,int slot,String plate,String type,Instant reservedAt,int duration,Instant now)throws Exception {
        if(duration<15||duration>720)throw new IllegalArgumentException("Parking duration must be from 15 minutes to 12 hours.");
        if(reservedAt.isBefore(now.minusSeconds(15*60L))||reservedAt.isAfter(now.plusSeconds(90L*24*3600)))throw new IllegalArgumentException("Choose a reservation time from now to 90 days ahead.");
        try(Connection c=connection()){c.setAutoCommit(false);try{
            lockSlot(c,slot);expireReservations(c,now);
            SlotData s=slot(c,slot);if(s==null)throw new IllegalArgumentException("Slot not found.");if(!s.enabled())throw new IllegalStateException("This slot is temporarily unavailable.");if(!accepts(s,type))throw new IllegalStateException("This slot is not suitable for that vehicle type.");
            if(hasActiveSession(c,slot))throw new IllegalStateException("This slot is occupied right now.");
            Instant end=reservedAt.plusSeconds(duration*60L);
            try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM udp_reservations WHERE slot_number=? AND status IN ('RESERVED','ACTIVE') AND reserved_at < ? AND reserved_at + (duration_minutes * interval '1 minute') > ? LIMIT 1")){
                p.setInt(1,slot);p.setTimestamp(2,Timestamp.from(end));p.setTimestamp(3,Timestamp.from(reservedAt));try(ResultSet r=p.executeQuery()){if(r.next())throw new IllegalStateException("That slot is already reserved for an overlapping time. Choose another time or slot.");}}
            ReservationData created;
            try(PreparedStatement p=c.prepareStatement("INSERT INTO udp_reservations(user_id,slot_number,plate,vehicle_type,reserved_at,duration_minutes,status,created_at) VALUES(?,?,?,?,?,?, 'RESERVED', ?) RETURNING id,user_id,slot_number,plate,vehicle_type,reserved_at,duration_minutes,status,created_at")){
                p.setLong(1,userId);p.setInt(2,slot);p.setString(3,plate);p.setString(4,type);p.setTimestamp(5,Timestamp.from(reservedAt));p.setInt(6,duration);p.setTimestamp(7,Timestamp.from(now));try(ResultSet r=p.executeQuery()){r.next();created=reservation(r);}}
            c.commit();return created;
        }catch(Exception e){c.rollback();throw e;}}
    }
    @Override public List<ReservationData> listReservationsForUser(long userId,int limit)throws Exception{return queryReservations("SELECT id,user_id,slot_number,plate,vehicle_type,reserved_at,duration_minutes,status,created_at FROM udp_reservations WHERE user_id=? ORDER BY created_at DESC LIMIT ?",userId,limit);}
    @Override public List<ReservationData> listReservations(int limit)throws Exception{return queryReservations("SELECT id,user_id,slot_number,plate,vehicle_type,reserved_at,duration_minutes,status,created_at FROM udp_reservations ORDER BY created_at DESC LIMIT ?",null,limit);}
    private List<ReservationData> queryReservations(String sql,Long userId,int limit)throws Exception{List<ReservationData> out=new ArrayList<>();try(Connection c=connection();PreparedStatement p=c.prepareStatement(sql)){int i=1;if(userId!=null)p.setLong(i++,userId);p.setInt(i,limit);try(ResultSet r=p.executeQuery()){while(r.next())out.add(reservation(r));}}return out;}
    private static ReservationData reservation(ResultSet r)throws SQLException{return new ReservationData(r.getLong(1),r.getLong(2),r.getInt(3),r.getString(4),r.getString(5),r.getTimestamp(6).toInstant(),r.getInt(7),r.getString(8),r.getTimestamp(9).toInstant());}
    @Override public boolean cancelReservation(long id,long userId,Instant now)throws Exception{try(Connection c=connection()){c.setAutoCommit(false);try{Integer slotNumber=null;try(PreparedStatement p=c.prepareStatement("SELECT slot_number FROM udp_reservations WHERE id=? AND user_id=?")){p.setLong(1,id);p.setLong(2,userId);try(ResultSet r=p.executeQuery()){if(r.next())slotNumber=r.getInt(1);}}
            if(slotNumber==null){c.commit();return false;}lockSlot(c,slotNumber);
            int changed;try(PreparedStatement p=c.prepareStatement("UPDATE udp_reservations SET status='CANCELLED' WHERE id=? AND user_id=? AND status='RESERVED'")){p.setLong(1,id);p.setLong(2,userId);changed=p.executeUpdate();}c.commit();return changed>0;
        }catch(Exception e){c.rollback();throw e;}}}

    @Override public boolean adminCancelReservation(long id,Instant now)throws Exception{
        try(Connection c=connection()){c.setAutoCommit(false);try{Integer slotNumber=null;try(PreparedStatement p=c.prepareStatement("SELECT slot_number FROM udp_reservations WHERE id=? AND status='RESERVED'")){p.setLong(1,id);try(ResultSet r=p.executeQuery()){if(r.next())slotNumber=r.getInt(1);}}
            if(slotNumber==null){c.commit();return false;}lockSlot(c,slotNumber);
            int changed;try(PreparedStatement p=c.prepareStatement("UPDATE udp_reservations SET status='CANCELLED' WHERE id=? AND status='RESERVED'")){p.setLong(1,id);changed=p.executeUpdate();}c.commit();return changed>0;
        }catch(Exception e){c.rollback();throw e;}}
    }
    @Override public EntryIntent prepareEntry(long userId,int slot,String plate,String type,int duration,Instant expiresAt)throws Exception{
        SlotData s=getSlot(slot);if(s==null)throw new IllegalArgumentException("Slot not found.");if(!s.enabled())throw new IllegalStateException("This slot is temporarily unavailable.");if(!accepts(s,type))throw new IllegalStateException("This slot is not suitable for that vehicle type.");
        try(Connection c=connection()){c.setAutoCommit(false);try{
            int updated;try(PreparedStatement p=c.prepareStatement("UPDATE udp_entry_intents SET slot_number=?,plate=?,vehicle_type=?,duration_minutes=?,expires_at=?,status='PREPARED',entered_at=NULL WHERE user_id=? AND status='PREPARED'")){p.setInt(1,slot);p.setString(2,plate);p.setString(3,type);p.setInt(4,duration);p.setTimestamp(5,Timestamp.from(expiresAt));p.setLong(6,userId);updated=p.executeUpdate();}
            if(updated==0)try(PreparedStatement p=c.prepareStatement("INSERT INTO udp_entry_intents(user_id,slot_number,plate,vehicle_type,duration_minutes,expires_at,status,entered_at) VALUES(?,?,?,?,?,?,'PREPARED',NULL)")){p.setLong(1,userId);p.setInt(2,slot);p.setString(3,plate);p.setString(4,type);p.setInt(5,duration);p.setTimestamp(6,Timestamp.from(expiresAt));p.executeUpdate();}
            c.commit();
        }catch(Exception e){c.rollback();throw e;}}
        return new EntryIntent(userId,slot,plate,type,duration,expiresAt);
    }
    @Override public EntryIntent findPreparedEntry(long userId,int slot,Instant now)throws Exception {
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT user_id,slot_number,plate,vehicle_type,duration_minutes,expires_at,status,entered_at FROM udp_entry_intents WHERE user_id=? AND slot_number=? AND status='PREPARED' AND expires_at>? ORDER BY expires_at DESC LIMIT 1")) {
            p.setLong(1,userId);p.setInt(2,slot);p.setTimestamp(3,Timestamp.from(now));
            try(ResultSet r=p.executeQuery()) { if(!r.next())return null;Timestamp entered=r.getTimestamp(8);return new EntryIntent(r.getLong(1),r.getInt(2),r.getString(3),r.getString(4),r.getInt(5),r.getTimestamp(6).toInstant(),r.getString(7),entered==null?null:entered.toInstant()); }
        }
    }

    @Override public SessionData enter(long userId,int slot,Instant now)throws Exception{
        try(Connection c=connection()){c.setAutoCommit(false);try{
            lockSlot(c,slot);expireReservations(c,now);SlotData s=slot(c,slot);if(s==null)throw new IllegalArgumentException("Slot not found.");if(!s.enabled())throw new IllegalStateException("This slot is temporarily unavailable.");
            ReservationData own=findEligibleReservation(c,userId,slot,now);
            if(hasActiveSession(c,slot)){
                SessionData occupied=activeSession(c,slot);
                if(own!=null&&occupied!=null&&occupied.userId()!=userId)throw new IllegalStateException("Your reservation is currently blocked by another parked vehicle. An administrator can see this conflict; please contact them.");
                throw new IllegalStateException("This slot is already occupied.");
            }
            String plate,type;Long reservationId=null;int duration=60;
            if(own!=null){
                Instant plannedEnd=now.plusSeconds(own.durationMinutes()*60L);
                try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM udp_reservations WHERE slot_number=? AND id<>? AND status='RESERVED' AND reserved_at < ? AND reserved_at + (duration_minutes * interval '1 minute') > ? LIMIT 1")){
                    p.setInt(1,slot);p.setLong(2,own.id());p.setTimestamp(3,Timestamp.from(plannedEnd));p.setTimestamp(4,Timestamp.from(now));try(ResultSet r=p.executeQuery()){if(r.next())throw new IllegalStateException("You arrived too late to finish the planned parking duration before the next reservation. Contact an administrator for help.");}}
                plate=own.plate();type=own.vehicleType();duration=own.durationMinutes();reservationId=own.id();try(PreparedStatement p=c.prepareStatement("UPDATE udp_reservations SET status='ACTIVE' WHERE id=? AND status='RESERVED'")){p.setLong(1,own.id());if(p.executeUpdate()!=1)throw new IllegalStateException("This reservation has already been used or cancelled.");}}
            else {
                try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM udp_reservations WHERE slot_number=? AND user_id<>? AND status='RESERVED' AND reserved_at >= ? AND reserved_at <= ? LIMIT 1")){
                    p.setInt(1,slot);p.setLong(2,userId);p.setTimestamp(3,Timestamp.from(now.minusSeconds(90*60L)));p.setTimestamp(4,Timestamp.from(now.plusSeconds(15*60L)));try(ResultSet r=p.executeQuery()){if(r.next())throw new IllegalStateException("This slot is reserved for another driver.");}}
                EntryIntent intent=findIntent(c,userId,slot,now);if(intent==null)throw new IllegalStateException("Select this slot and enter your plate and vehicle type first, then scan its ENTER QR.");
                plate=intent.plate();type=intent.vehicleType();duration=intent.durationMinutes();
                try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM udp_reservations WHERE slot_number=? AND status='RESERVED' AND reserved_at < ? AND reserved_at + (duration_minutes * interval '1 minute') > ? LIMIT 1")){p.setInt(1,slot);p.setTimestamp(2,Timestamp.from(now.plusSeconds(duration*60L)));p.setTimestamp(3,Timestamp.from(now));try(ResultSet r=p.executeQuery()){if(r.next())throw new IllegalStateException("This slot is reserved during your expected parking time.");}}
            }
            if(!accepts(s,type))throw new IllegalStateException("This slot is not suitable for your vehicle type.");
            SessionData result;try(PreparedStatement p=c.prepareStatement("INSERT INTO udp_sessions(slot_number,user_id,plate,vehicle_type,entry_time,reservation_id) VALUES(?,?,?,?,?,?) RETURNING id,slot_number,user_id,plate,vehicle_type,entry_time,exit_time,reservation_id")){p.setInt(1,slot);p.setLong(2,userId);p.setString(3,plate);p.setString(4,type);p.setTimestamp(5,Timestamp.from(now));if(reservationId==null)p.setNull(6,Types.BIGINT);else p.setLong(6,reservationId);try(ResultSet r=p.executeQuery()){r.next();result=session(r);}}
            int marked;try(PreparedStatement p=c.prepareStatement("UPDATE udp_entry_intents SET plate=?,vehicle_type=?,duration_minutes=?,expires_at=?,status='ENTERED',entered_at=? WHERE user_id=? AND slot_number=? AND status='PREPARED' AND expires_at>?")){p.setString(1,plate);p.setString(2,type);p.setInt(3,duration);p.setTimestamp(4,Timestamp.from(now.minusMillis(1)));p.setTimestamp(5,Timestamp.from(now));p.setLong(6,userId);p.setInt(7,slot);p.setTimestamp(8,Timestamp.from(now));marked=p.executeUpdate();}
            if(marked==0)try(PreparedStatement p=c.prepareStatement("INSERT INTO udp_entry_intents(user_id,slot_number,plate,vehicle_type,duration_minutes,expires_at,status,entered_at) VALUES(?,?,?,?,?,?, 'ENTERED', ?)")){p.setLong(1,userId);p.setInt(2,slot);p.setString(3,plate);p.setString(4,type);p.setInt(5,duration);p.setTimestamp(6,Timestamp.from(now.minusMillis(1)));p.setTimestamp(7,Timestamp.from(now));p.executeUpdate();}
            c.commit();return result;
        }catch(Exception e){c.rollback();throw e;}}
    }
    private ReservationData findEligibleReservation(Connection c,long userId,int slot,Instant now)throws Exception{try(PreparedStatement p=c.prepareStatement("SELECT id,user_id,slot_number,plate,vehicle_type,reserved_at,duration_minutes,status,created_at FROM udp_reservations WHERE user_id=? AND slot_number=? AND status='RESERVED' AND reserved_at >= ? AND reserved_at <= ? ORDER BY reserved_at LIMIT 1")){p.setLong(1,userId);p.setInt(2,slot);p.setTimestamp(3,Timestamp.from(now.minusSeconds(90*60L)));p.setTimestamp(4,Timestamp.from(now.plusSeconds(180*60L)));try(ResultSet r=p.executeQuery()){return r.next()?reservation(r):null;}}}
    private EntryIntent findIntent(Connection c,long userId,int slot,Instant now)throws Exception{try(PreparedStatement p=c.prepareStatement("SELECT user_id,slot_number,plate,vehicle_type,duration_minutes,expires_at,status,entered_at FROM udp_entry_intents WHERE user_id=? AND slot_number=? AND status='PREPARED' AND expires_at>? FOR UPDATE")){p.setLong(1,userId);p.setInt(2,slot);p.setTimestamp(3,Timestamp.from(now));try(ResultSet r=p.executeQuery()){if(!r.next())return null;Timestamp entered=r.getTimestamp(8);return new EntryIntent(r.getLong(1),r.getInt(2),r.getString(3),r.getString(4),r.getInt(5),r.getTimestamp(6).toInstant(),r.getString(7),entered==null?null:entered.toInstant());}}}
    @Override public SessionData leave(long userId,int slot,Instant now)throws Exception{return finishSession(userId,slot,now,false);}
    @Override public SessionData manualCheckout(int slot,Instant now)throws Exception{return finishSession(0,slot,now,true);}
    private SessionData finishSession(long userId,int slot,Instant now,boolean admin)throws Exception{try(Connection c=connection()){c.setAutoCommit(false);try{lockSlot(c,slot);SessionData active=null;try(PreparedStatement p=c.prepareStatement("SELECT id,slot_number,user_id,plate,vehicle_type,entry_time,exit_time,reservation_id FROM udp_sessions WHERE slot_number=? AND exit_time IS NULL FOR UPDATE")){p.setInt(1,slot);try(ResultSet r=p.executeQuery()){if(r.next())active=session(r);}}
            if(active==null)throw new IllegalStateException("This slot is already free.");if(!admin&&active.userId()!=userId)throw new IllegalStateException("This parking session belongs to another account. Please contact an administrator.");
            try(PreparedStatement p=c.prepareStatement("UPDATE udp_sessions SET exit_time=? WHERE id=? AND exit_time IS NULL")){p.setTimestamp(1,Timestamp.from(now));p.setLong(2,active.id());p.executeUpdate();}
            if(active.reservationId()!=null)try(PreparedStatement p=c.prepareStatement("UPDATE udp_reservations SET status='COMPLETED' WHERE id=? AND status='ACTIVE'")){p.setLong(1,active.reservationId());p.executeUpdate();}
            try(PreparedStatement p=c.prepareStatement("UPDATE udp_entry_intents SET status='COMPLETED' WHERE user_id=? AND slot_number=? AND status='ENTERED' AND entered_at=?")){p.setLong(1,active.userId());p.setInt(2,slot);p.setTimestamp(3,Timestamp.from(active.entryTime()));p.executeUpdate();}
            SessionData result=new SessionData(active.id(),active.slotNumber(),active.userId(),active.plate(),active.vehicleType(),active.entryTime(),now,active.reservationId());c.commit();return result;
        }catch(Exception e){c.rollback();throw e;}}}
    @Override public List<SessionData> listSessions(int limit)throws Exception{return querySessions("SELECT id,slot_number,user_id,plate,vehicle_type,entry_time,exit_time,reservation_id FROM udp_sessions ORDER BY entry_time DESC LIMIT ?",limit);}
    @Override public List<SessionData> listActiveSessions()throws Exception{List<SessionData> out=new ArrayList<>();try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT id,slot_number,user_id,plate,vehicle_type,entry_time,exit_time,reservation_id FROM udp_sessions WHERE exit_time IS NULL ORDER BY slot_number");ResultSet r=p.executeQuery()){while(r.next())out.add(session(r));}return out;}
    private List<SessionData> querySessions(String sql,int limit)throws Exception{List<SessionData> out=new ArrayList<>();try(Connection c=connection();PreparedStatement p=c.prepareStatement(sql)){p.setInt(1,limit);try(ResultSet r=p.executeQuery()){while(r.next())out.add(session(r));}}return out;}
    private static SessionData session(ResultSet r)throws SQLException{long res=r.getLong(8);Long reservation=r.wasNull()?null:res;Timestamp exit=r.getTimestamp(7);return new SessionData(r.getLong(1),r.getInt(2),r.getLong(3),r.getString(4),r.getString(5),r.getTimestamp(6).toInstant(),exit==null?null:exit.toInstant(),reservation);}
    @Override public void expireReservations(Instant now)throws Exception{try(Connection c=connection()){expireReservations(c,now);}}
    private void expireReservations(Connection c,Instant now)throws SQLException{try(PreparedStatement p=c.prepareStatement("UPDATE udp_reservations SET status='EXPIRED' WHERE status='RESERVED' AND reserved_at + interval '90 minutes' <= ?")){p.setTimestamp(1,Timestamp.from(now));p.executeUpdate();}try(PreparedStatement p=c.prepareStatement("UPDATE udp_entry_intents SET status='EXPIRED' WHERE status='PREPARED' AND expires_at <= ?")){p.setTimestamp(1,Timestamp.from(now));p.executeUpdate();}}
    private static void lockSlot(Connection c,int slot)throws SQLException{try(PreparedStatement p=c.prepareStatement("SELECT pg_advisory_xact_lock(?)")){p.setLong(1,slot);p.execute();}}
    private static boolean hasActiveSession(Connection c,int slot)throws SQLException{try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM udp_sessions WHERE slot_number=? AND exit_time IS NULL LIMIT 1")){p.setInt(1,slot);try(ResultSet r=p.executeQuery()){return r.next();}}}
    private static SessionData activeSession(Connection c,int slot)throws SQLException{try(PreparedStatement p=c.prepareStatement("SELECT id,slot_number,user_id,plate,vehicle_type,entry_time,exit_time,reservation_id FROM udp_sessions WHERE slot_number=? AND exit_time IS NULL LIMIT 1")){p.setInt(1,slot);try(ResultSet r=p.executeQuery()){return r.next()?session(r):null;}}}
    private static SlotData slot(Connection c,int slot)throws SQLException{try(PreparedStatement p=c.prepareStatement("SELECT slot_number,compatibility,enabled FROM udp_slots WHERE slot_number=?")){p.setInt(1,slot);try(ResultSet r=p.executeQuery()){return r.next()?new SlotData(r.getInt(1),r.getString(2),r.getBoolean(3)):null;}}}
    private static boolean accepts(SlotData s,String type){return s.compatibility().equals("ANY")||s.compatibility().equalsIgnoreCase(type);}
    private static String normalizeCompatibility(String raw){String t=raw==null?"ANY":raw.trim().toUpperCase();if(!List.of("ANY","CAR","MOTORCYCLE").contains(t))throw new IllegalArgumentException("Choose Any, Car, or Motorcycle.");return t;}
    @Override public FeedbackData submitFeedback(long userId, String category, int rating, String message, Instant now) throws Exception {
        String c = normalizeFeedbackCategory(category);
        String text = message == null ? "" : message.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("Choose a rating from 1 to 5.");
        if (text.length() < 5 || text.length() > 1000) throw new IllegalArgumentException("Feedback must be between 5 and 1000 characters.");
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                long newId;
                try (PreparedStatement p = connection.prepareStatement("INSERT INTO udp_feedback(user_id,category,rating,message,status,admin_note,created_at,updated_at) VALUES(?,?,?,?, 'NEW','',?,?) RETURNING id")) {
                    p.setLong(1, userId); p.setString(2, c); p.setInt(3, rating); p.setString(4, text); p.setTimestamp(5, Timestamp.from(now)); p.setTimestamp(6, Timestamp.from(now));
                    try (ResultSet r = p.executeQuery()) { if (!r.next()) throw new SQLException("Feedback insert did not return an id."); newId = r.getLong(1); }
                }
                FeedbackData result = getFeedback(connection, newId);
                connection.commit();
                return result;
            } catch (Exception e) { connection.rollback(); throw e; }
        }
    }

    @Override public List<FeedbackData> listFeedback(int limit) throws Exception {
        List<FeedbackData> out = new ArrayList<>();
        int safeLimit = Math.max(1, Math.min(limit, 500));
        String sql = "SELECT f.id,f.user_id,u.email,u.display_name,f.category,f.rating,f.message,f.status,f.admin_note,f.created_at,f.updated_at FROM udp_feedback f JOIN udp_users u ON u.id=f.user_id ORDER BY f.created_at DESC LIMIT ?";
        try (Connection c = connection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setInt(1, safeLimit); try (ResultSet r = p.executeQuery()) { while (r.next()) out.add(feedback(r)); }
        }
        return out;
    }

    @Override public FeedbackData updateFeedback(long feedbackId, String status, String adminNote, Instant now) throws Exception {
        String next = normalizeFeedbackStatus(status);
        String note = adminNote == null ? "" : adminNote.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (note.length() > 1000) throw new IllegalArgumentException("Admin note must be 1000 characters or fewer.");
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement p = c.prepareStatement("UPDATE udp_feedback SET status=?,admin_note=?,updated_at=? WHERE id=?")) {
                    p.setString(1, next); p.setString(2, note); p.setTimestamp(3, Timestamp.from(now)); p.setLong(4, feedbackId);
                    if (p.executeUpdate() != 1) throw new IllegalArgumentException("Feedback item not found.");
                }
                FeedbackData result = getFeedback(c, feedbackId);
                c.commit();
                return result;
            } catch (Exception e) { c.rollback(); throw e; }
        }
    }

    private static FeedbackData getFeedback(Connection c, long id) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT f.id,f.user_id,u.email,u.display_name,f.category,f.rating,f.message,f.status,f.admin_note,f.created_at,f.updated_at FROM udp_feedback f JOIN udp_users u ON u.id=f.user_id WHERE f.id=?")) {
            p.setLong(1, id); try (ResultSet r = p.executeQuery()) { if (!r.next()) throw new SQLException("Feedback record could not be loaded."); return feedback(r); }
        }
    }
    private static FeedbackData feedback(ResultSet r) throws SQLException {
        return new FeedbackData(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getString(5),r.getInt(6),r.getString(7),r.getString(8),r.getString(9),r.getTimestamp(10).toInstant(),r.getTimestamp(11).toInstant());
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

    @Override public void close() {}
}
