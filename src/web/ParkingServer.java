package web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import service.ParkingLot;
import service.PasswordUtil;
import storage.ParkingStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class ParkingServer {
    private static final int MAX_BODY=16*1024;
    private static final Duration SESSION_LIFETIME=Duration.ofHours(12);
    private final ParkingLot lot;
    private final int port;
    private final String baseUrl;
    private final byte[] adminKey;
    /** Separate HMAC key for user session tokens. Falls back to adminKey when SESSION_SECRET is not set. */
    private final byte[] sessionKey;
    private final ConcurrentHashMap<String,FailureWindow> failures=new ConcurrentHashMap<>();
    private HttpServer server;

    public ParkingServer(ParkingLot lot,int port,String baseUrl,String adminKey){
        this(lot,port,baseUrl,adminKey,null);
    }
    public ParkingServer(ParkingLot lot,int port,String baseUrl,String adminKey,String sessionSecret){
        this.lot=lot;this.port=port;this.baseUrl=baseUrl;
        this.adminKey=(adminKey==null?"":adminKey).getBytes(StandardCharsets.UTF_8);
        if(this.adminKey.length<16)throw new IllegalArgumentException("ADMIN_KEY must contain at least 16 characters.");
        byte[] sk=(sessionSecret==null||sessionSecret.isBlank())?null:sessionSecret.getBytes(StandardCharsets.UTF_8);
        this.sessionKey=(sk!=null&&sk.length>=16)?sk:this.adminKey;
    }

    public void start() throws IOException {
        server=HttpServer.create(new InetSocketAddress("0.0.0.0",port),0);
        server.createContext("/",this::handle);
        ThreadFactory factory=r->{Thread t=new Thread(r,"uncle-dogs-http");t.setDaemon(true);return t;};
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4,Runtime.getRuntime().availableProcessors()),factory));
        server.start();
    }

    private void handle(HttpExchange ex){
        try {
            String path=ex.getRequestURI().getPath();String method=ex.getRequestMethod().toUpperCase();
            if(path.equals("/healthz")||path.equals("/api/health")){json(ex,200,map("status","ok","time",Instant.now().toString()));return;}
            if(path.equals("/readyz")){handleReadyz(ex);return;}
            if(path.equals("/assets/logo.jpg")){serveLogo(ex);return;}
            if(path.startsWith("/enter/")||path.startsWith("/leave/")){
                if(method.equals("GET")){handleQrAction(ex,path);return;}
                if(method.equals("POST")){handleQrActionPost(ex,path);return;}
                ex.getResponseHeaders().set("Allow","GET, POST");
                qrPage(ex,405,"Unsupported QR request","Open the QR link in your browser and follow the on-screen instructions.","Open parking website","/",false);return;
            }
            if(path.equals("/")&&method.equals("GET")){if(lot.isInitialized()){try{lot.processExpirations();}catch(Exception ignored){}}html(ex,200,PageBuilder.build(requestBaseUrl(ex)));return;}
            if(!path.startsWith("/api/")){json(ex,404,map("message","Page not found."));return;}
            if(path.startsWith("/api/")&&isPrefetch(ex)&&path.equals("/api/scan")){json(ex,200,map("ignored",true,"message","Preview requests do not perform parking actions. Open the site and use Scan QR."));return;}
            if(!lot.isInitialized()){try{lot.ensureInitialized();}catch(Exception dbError){System.err.println("[DB] Request blocked â€” database not initialized: "+dbError.getClass().getSimpleName()+": "+dbError.getMessage());safeJson(ex,503,map("message","The database is not yet available. Please try again in a moment.","retryable",true));return;}}
            try{lot.processExpirations();}catch(Exception ignored){}
            routeApi(ex,path,method);
        } catch (IllegalArgumentException e) {safeJson(ex,400,map("message",friendly(e)));}
          catch (SecurityException e) {safeJson(ex,401,map("message",friendly(e)));}
          catch (IllegalStateException e) {safeJson(ex,409,map("message",friendly(e)));}
          catch (Exception e) {if(isDatabaseError(e)){safeJson(ex,503,map("message","The database is temporarily unavailable. Please try again in a moment."));}else{System.err.println("Request failed: "+ex.getRequestMethod()+" "+ex.getRequestURI().getPath()+" :: "+e.getClass().getSimpleName()+": "+e.getMessage());safeJson(ex,500,map("message","The server could not complete that request. Please try again; your action was not confirmed."));}}
        finally {ex.close();}
    }

    private void routeApi(HttpExchange ex,String path,String method)throws Exception {
        if(path.equals("/api/admin/export/visits.csv")&&method.equals("GET")){exportVisitsCsv(ex);return;}
        if(path.equals("/api/state")&&method.equals("GET")){
            List<Map<String,Object>> safe=new ArrayList<>();for(Map<String,Object> s:lot.slotViews()){Map<String,Object> item=new LinkedHashMap<>(s);item.remove("plate");item.remove("vehicleType");item.remove("entryTime");item.remove("reservedAt");item.remove("reservedForCurrentUserOnly");safe.add(item);}
            json(ex,200,map("slots",safe,"baseUrl",requestBaseUrl(ex),"storageMode",lot.getStorage().isRemoteDatabase()?"postgresql":"local-csv"));return;
        }
        if(path.equals("/api/me")&&method.equals("GET")){UserSession s=currentUser(ex);json(ex,200,map("user",s==null?null:userPublic(lot.getStorage().findUserById(s.userId()))));return;}
        if(path.equals("/api/auth/register")&&method.equals("POST")){register(ex);return;}
        if(path.equals("/api/auth/login")&&method.equals("POST")){login(ex);return;}
        if(path.equals("/api/auth/logout")&&method.equals("POST")){setCookie(ex,"UDP_SESSION","",0);json(ex,200,map("ok",true));return;}
        if(path.equals("/api/my-reservations")&&method.equals("GET")){UserSession s=requireUser(ex);json(ex,200,map("reservations",lot.myReservations(s.userId())));return;}
        if(path.equals("/api/reserve")&&method.equals("POST")){UserSession s=requireUser(ex);Map<String,String> f=form(ex);ParkingStore.ReservationData r=lot.reserve(s.userId(),integer(f,"slotNumber"),f.get("plate"),f.get("plateConfirm"),f.get("vehicleType"),Instant.parse(required(f,"reservedAt")),integer(f,"durationMinutes"));json(ex,201,map("reservation",reservationMap(r)));return;}
        if(path.equals("/api/cancel")&&method.equals("POST")){UserSession s=requireUser(ex);Map<String,String> f=form(ex);lot.cancelReservation(s.userId(),longInteger(f,"reservationId"));json(ex,200,map("ok",true));return;}
        if(path.equals("/api/prepare-entry")&&method.equals("POST")){UserSession s=requireUser(ex);Map<String,String> f=form(ex);lot.prepareWalkIn(s.userId(),integer(f,"slotNumber"),f.get("plate"),f.get("plateConfirm"),f.get("vehicleType"),integer(f,"durationMinutes"));json(ex,200,map("ok",true,"expiresInMinutes",10));return;}
        if(path.equals("/api/scan")&&method.equals("POST")){UserSession s=requireUser(ex);Map<String,String> f=form(ex);String action=required(f,"action").toUpperCase();int slot=integer(f,"slotNumber");String plate=required(f,"plate");ParkingStore.SessionData record;if(action.equals("ENTER"))record=lot.enterByPlate(s.userId(),slot,plate);else if(action.equals("LEAVE"))record=lot.leaveByPlate(s.userId(),slot,plate);else throw new IllegalArgumentException("Unknown QR action.");json(ex,200,map("record",sessionMap(record,Instant.now())));return;}
        if(path.equals("/api/feedback")&&method.equals("POST")){UserSession s=requireUser(ex);Map<String,String> f=form(ex);int rating=integer(f,"rating");ParkingStore.FeedbackData item=lot.getStorage().submitFeedback(s.userId(),required(f,"category"),rating,required(f,"message"),Instant.now());json(ex,201,map("feedback",feedbackMap(item)));return;}
        if(path.equals("/api/admin/feedback")&&method.equals("GET")){requireAdmin(ex);List<Map<String,Object>> items=new ArrayList<>();for(ParkingStore.FeedbackData item:lot.getStorage().listFeedback(200))items.add(feedbackMap(item));json(ex,200,map("feedback",items));return;}
        if(path.equals("/api/admin/feedback/update")&&method.equals("POST")){requireAdmin(ex);Map<String,String> f=form(ex);ParkingStore.FeedbackData item=lot.getStorage().updateFeedback(longInteger(f,"feedbackId"),required(f,"status"),f.getOrDefault("adminNote",""),Instant.now());json(ex,200,map("feedback",feedbackMap(item)));return;}
        if(path.equals("/api/admin/unlock")&&method.equals("POST")){adminUnlock(ex);return;}
        if(path.equals("/api/admin/lock")&&method.equals("POST")){setCookie(ex,"UDP_ADMIN","",0);json(ex,200,map("ok",true));return;}
        if(path.equals("/api/admin/state")&&method.equals("GET")){requireAdmin(ex);json(ex,200,lot.adminSummary());return;}
        if(path.equals("/api/admin/checkout")&&method.equals("POST")){requireAdmin(ex);Map<String,String> f=form(ex);ParkingStore.SessionData r=lot.adminCheckout(integer(f,"slotNumber"));json(ex,200,map("record",sessionMap(r,Instant.now())));return;}
        if(path.equals("/api/admin/cancel-reservation")&&method.equals("POST")){requireAdmin(ex);Map<String,String> f=form(ex);lot.adminCancelReservation(longInteger(f,"reservationId"));json(ex,200,map("ok",true));return;}
        if(path.equals("/api/admin/add-slot")&&method.equals("POST")){requireAdmin(ex);Map<String,String> f=form(ex);ParkingStore.SlotData s=lot.addSlot(f.getOrDefault("compatibility","ANY"));json(ex,201,map("slot",map("number",s.number(),"compatibility",s.compatibility(),"enabled",s.enabled())));return;}
        if(path.equals("/api/admin/slot")&&method.equals("POST")){requireAdmin(ex);Map<String,String> f=form(ex);ParkingStore.SlotData s=lot.setSlotEnabled(integer(f,"slotNumber"),Boolean.parseBoolean(required(f,"enabled")));json(ex,200,map("slot",map("number",s.number(),"compatibility",s.compatibility(),"enabled",s.enabled())));return;}
        if(path.equals("/api/admin/delete-slot")&&method.equals("POST")){requireAdmin(ex);Map<String,String> f=form(ex);int n=integer(f,"slotNumber");lot.deleteSlot(n);json(ex,200,map("ok",true,"slotNumber",n));return;}
        json(ex,404,map("message","API endpoint not found."));
    }

    /** Admin-only full export: one CSV row per parking visit, including active and completed sessions. */
    private void exportVisitsCsv(HttpExchange ex)throws Exception {
        requireAdmin(ex);
        Instant now=Instant.now();
        List<ParkingStore.SessionData> visits=lot.getStorage().listSessions(Integer.MAX_VALUE);
        Map<Long,ParkingStore.UserData> users=new LinkedHashMap<>();
        StringBuilder csv=new StringBuilder(4096);
        csv.append("visit_id,slot_number,user_id,user_name,user_email,plate_number,vehicle_type,entry_time_utc,exit_time_utc,duration_seconds,duration_minutes,session_status,reservation_id\r\n");
        for(ParkingStore.SessionData visit:visits){
            if(!users.containsKey(visit.userId())) users.put(visit.userId(),lot.getStorage().findUserById(visit.userId()));
            ParkingStore.UserData user=users.get(visit.userId());
            long seconds=visit.durationSeconds(now);
            csv.append(visit.id()).append(',')
               .append(visit.slotNumber()).append(',')
               .append(visit.userId()).append(',')
               .append(csvField(user==null?"":user.displayName())).append(',')
               .append(csvField(user==null?"":user.email())).append(',')
               .append(csvField(visit.plate())).append(',')
               .append(csvField(visit.vehicleType())).append(',')
               .append(csvField(visit.entryTime().toString())).append(',')
               .append(csvField(visit.exitTime()==null?"":visit.exitTime().toString())).append(',')
               .append(seconds).append(',')
               .append(seconds/60).append(',')
               .append(visit.active()?"ACTIVE":"COMPLETED").append(',')
               .append(visit.reservationId()==null?"":visit.reservationId()).append("\r\n");
        }
        // UTF-8 BOM improves compatibility with Excel while keeping the file standard CSV.
        byte[] bytes=("\uFEFF"+csv).toString().getBytes(StandardCharsets.UTF_8);
        String date=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Manila")).toString();
        ex.getResponseHeaders().set("Content-Type","text/csv; charset=UTF-8");
        ex.getResponseHeaders().set("Content-Disposition","attachment; filename=\"uncle-dogs-parking-visits-"+date+".csv\"");
        ex.getResponseHeaders().set("Cache-Control","no-store, max-age=0");
        ex.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        ex.sendResponseHeaders(200,bytes.length);
        try(java.io.OutputStream out=ex.getResponseBody()){out.write(bytes);}
    }

    /** Quote CSV values and neutralize spreadsheet formulas in user-controlled fields. */
    private static String csvField(String value){
        String safe=value==null?"":value;
        String trimmed=safe.stripLeading();
        if(!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0))>=0) safe="'"+safe;
        return "\""+safe.replace("\"","\"\"")+"\"";
    }

    private void register(HttpExchange ex)throws Exception {
        String ip=clientIp(ex);checkRate(ip,"auth");Map<String,String> f=form(ex);String name=required(f,"displayName").trim();String email=required(f,"email").trim().toLowerCase();String password=required(f,"password");
        if(name.length()<2||name.length()>80)throw new IllegalArgumentException("Enter a name between 2 and 80 characters.");
        if(!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$" )||email.length()>254)throw new IllegalArgumentException("Enter a valid email address.");
        if(password.length()<10||password.length()>128)throw new IllegalArgumentException("Use a password between 10 and 128 characters.");
        PasswordUtil.Hash hash=PasswordUtil.hash(password.toCharArray());
        ParkingStore.UserData user=lot.getStorage().createUser(email,name,hash.salt(),hash.value());
        establishUserSession(ex,user.id());json(ex,201,map("user",userPublic(user)));
    }
    private void login(HttpExchange ex)throws Exception {
        String ip=clientIp(ex);checkRate(ip,"auth");Map<String,String> f=form(ex);String email=required(f,"email").trim().toLowerCase();String password=required(f,"password");
        ParkingStore.UserData user=lot.getStorage().findUserByEmail(email);
        boolean valid=user!=null&&PasswordUtil.verify(password.toCharArray(),user.salt(),user.passwordHash());
        if(!valid){fail(ip,"auth");throw new SecurityException("Email or password is incorrect.");}
        clearFailures(ip,"auth");establishUserSession(ex,user.id());json(ex,200,map("user",userPublic(user)));
    }
    private void adminUnlock(HttpExchange ex)throws Exception {
        String ip=clientIp(ex);checkRate(ip,"admin");Map<String,String> f=form(ex);byte[] candidate=required(f,"key").getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(adminKey,candidate)){fail(ip,"admin");throw new SecurityException("The admin key is incorrect.");}
        clearFailures(ip,"admin");Instant expires=Instant.now().plus(SESSION_LIFETIME);String token=signToken("ADMIN",Long.toString(expires.getEpochSecond()));setCookie(ex,"UDP_ADMIN",token,SESSION_LIFETIME.toSeconds());json(ex,200,map("ok",true));
    }
    // Signed cookies keep authentication valid across stateless Vercel instances without in-memory sessions.
    private void establishUserSession(HttpExchange ex,long userId){Instant expires=Instant.now().plus(SESSION_LIFETIME);String token=signSessionToken("USER",userId+":"+expires.getEpochSecond());setCookie(ex,"UDP_SESSION",token,SESSION_LIFETIME.toSeconds());}
    private UserSession currentUser(HttpExchange ex){
        String payload=verifySessionToken("USER",cookie(ex,"UDP_SESSION"));if(payload==null)return null;
        String[] parts=payload.split(":",-1);if(parts.length!=2)return null;
        try{long userId=Long.parseLong(parts[0]);Instant expires=Instant.ofEpochSecond(Long.parseLong(parts[1]));if(userId<1||!Instant.now().isBefore(expires))return null;return new UserSession(userId,expires);}catch(RuntimeException invalid){return null;}
    }
    private UserSession requireUser(HttpExchange ex){UserSession s=currentUser(ex);if(s==null)throw new SecurityException("Sign in to continue.");return s;}
    private void requireAdmin(HttpExchange ex){String payload=verifyToken("ADMIN",cookie(ex,"UDP_ADMIN"));try{if(payload==null||!Instant.now().isBefore(Instant.ofEpochSecond(Long.parseLong(payload))))throw new SecurityException("Unlock Admin to continue.");}catch(NumberFormatException|java.time.DateTimeException invalid){throw new SecurityException("Unlock Admin to continue.");}}
    private String signToken(String purpose,String payload){
        String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String message=purpose+"."+encoded;
        String signature=Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(adminKey,message));
        return encoded+"."+signature;
    }
    /** Signs user session tokens using SESSION_SECRET (or adminKey as fallback). */
    private String signSessionToken(String purpose,String payload){
        String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String message=purpose+"."+encoded;
        String signature=Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(sessionKey,message));
        return encoded+"."+signature;
    }
    private String verifyToken(String purpose,String token){
        if(token==null||token.length()>512)return null;
        String[] parts=token.split("\\.",-1);if(parts.length!=2)return null;
        try{
            byte[] supplied=Base64.getUrlDecoder().decode(parts[1]);byte[] expected=hmac(adminKey,purpose+"."+parts[0]);
            if(!MessageDigest.isEqual(supplied,expected))return null;
            return new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8);
        }catch(IllegalArgumentException invalid){return null;}
    }
    /** Verifies user session tokens using SESSION_SECRET (or adminKey as fallback). */
    private String verifySessionToken(String purpose,String token){
        if(token==null||token.length()>512)return null;
        String[] parts=token.split("\\.",-1);if(parts.length!=2)return null;
        try{
            byte[] supplied=Base64.getUrlDecoder().decode(parts[1]);byte[] expected=hmac(sessionKey,purpose+"."+parts[0]);
            if(!MessageDigest.isEqual(supplied,expected))return null;
            return new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8);
        }catch(IllegalArgumentException invalid){return null;}
    }
    private static byte[] hmac(byte[] key,String message){
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));}
        catch(java.security.GeneralSecurityException e){throw new IllegalStateException("Secure session verification is unavailable.",e);}
    }
    private String requestBaseUrl(HttpExchange ex){
        String directHost=firstHeaderValue(ex,"Host");
        // Local pages opened from a phone should generate QR links using that same reachable LAN host.
        // When the PC opens localhost, keep the detected LAN URL so printed codes work on phones too.
        if(!service.AppConfig.isVercelDeployment() && baseUrl!=null && baseUrl.startsWith("http://") && isPrivateIpv4Host(directHost))
            return "http://"+directHost;
        if(baseUrl!=null&&!baseUrl.isBlank())return baseUrl;
        String host=firstHeaderValue(ex,"X-Forwarded-Host");
        if(host==null||host.isBlank())host=directHost;
        if(host==null||!host.matches("[A-Za-z0-9][A-Za-z0-9.:-]{0,250}"))return "http://localhost:"+port;
        String proto=firstHeaderValue(ex,"X-Forwarded-Proto");
        if(proto==null||(!proto.equalsIgnoreCase("http")&&!proto.equalsIgnoreCase("https")))proto=service.AppConfig.isVercelDeployment()?"https":"http";
        return proto.toLowerCase(java.util.Locale.ROOT)+"://"+host;
    }
    private static String firstHeaderValue(HttpExchange ex,String name){
        String value=ex.getRequestHeaders().getFirst(name);
        if(value!=null&&value.contains(","))value=value.split(",",2)[0].trim();
        return value==null?null:value.trim();
    }
    private static boolean isPrivateIpv4Host(String host){
        if(host==null)return false;
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("^((?:[0-9]{1,3}\\.){3}[0-9]{1,3})(?::([0-9]{1,5}))?$").matcher(host);
        if(!m.matches())return false;
        try{
            String ip=m.group(1);String[] octets=ip.split("\\.");
            for(String octet:octets){int n=Integer.parseInt(octet);if(n<0||n>255)return false;}
            int a=Integer.parseInt(octets[0]),b=Integer.parseInt(octets[1]);
            if(m.group(2)!=null){int port=Integer.parseInt(m.group(2));if(port<1||port>65535)return false;}
            return a==10 || (a==192&&b==168) || (a==172&&b>=16&&b<=31);
        }catch(NumberFormatException invalid){return false;}
    }
    private void setCookie(HttpExchange ex,String name,String value,long maxAge){boolean secure=requestBaseUrl(ex).startsWith("https://")||"https".equalsIgnoreCase(ex.getRequestHeaders().getFirst("X-Forwarded-Proto"));String cookie=name+"="+value+"; Path=/; HttpOnly; SameSite=Lax; Max-Age="+maxAge+(secure?"; Secure":"");ex.getResponseHeaders().add("Set-Cookie",cookie);}
    private static String cookie(HttpExchange ex,String name){String all=ex.getRequestHeaders().getFirst("Cookie");if(all==null)return null;for(String part:all.split(";")){String[] kv=part.trim().split("=",2);if(kv.length==2&&kv[0].equals(name))return kv[1];}return null;}
    private void checkRate(String ip,String kind){FailureWindow f=failures.get(ip+":"+kind);if(f!=null&&f.blockedUntil!=null&&Instant.now().isBefore(f.blockedUntil))throw new SecurityException("Too many attempts. Wait a few minutes and try again.");}
    private void fail(String ip,String kind){String key=ip+":"+kind;failures.compute(key,(k,v)->{Instant now=Instant.now();if(v==null||now.isAfter(v.windowStart.plus(Duration.ofMinutes(15))))v=new FailureWindow(now,0,null);v.count++;if(v.count>=8)v.blockedUntil=now.plus(Duration.ofMinutes(15));return v;});}
    private void clearFailures(String ip,String kind){failures.remove(ip+":"+kind);}
    private static String clientIp(HttpExchange ex){String f=ex.getRequestHeaders().getFirst("X-Forwarded-For");if(f!=null&&!f.isBlank())return f.split(",")[0].trim();return ex.getRemoteAddress().getAddress().getHostAddress();}

    private static Map<String,String> form(HttpExchange ex)throws IOException {
        String type=ex.getRequestHeaders().getFirst("Content-Type");if(type==null||!type.toLowerCase().startsWith("application/x-www-form-urlencoded"))throw new IllegalArgumentException("Invalid request format.");
        byte[] data=readLimited(ex,MAX_BODY);String raw=new String(data,StandardCharsets.UTF_8);Map<String,String> out=new LinkedHashMap<>();if(raw.isBlank())return out;
        for(String part:raw.split("&")){int p=part.indexOf('=');String k=decode(p<0?part:part.substring(0,p));String v=decode(p<0?"":part.substring(p+1));if(k.length()>100||v.length()>4096)throw new IllegalArgumentException("Request field is too long.");out.put(k,v);}return out;
    }
    private static byte[] readLimited(HttpExchange ex,int max)throws IOException{try(var in=ex.getRequestBody();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n,total=0;while((n=in.read(b))!=-1){total+=n;if(total>max)throw new IOException("Request body is too large.");out.write(b,0,n);}return out.toByteArray();}}
    private static String decode(String s){return URLDecoder.decode(s,StandardCharsets.UTF_8);}
    private static String required(Map<String,String> f,String key){String v=f.get(key);if(v==null||v.isBlank())throw new IllegalArgumentException("Missing required field: "+key+".");return v;}
    private static int integer(Map<String,String> f,String key){try{return Integer.parseInt(required(f,key));}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid number for "+key+".");}}
    private static long longInteger(Map<String,String> f,String key){try{return Long.parseLong(required(f,key));}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid number for "+key+".");}}

    private static Map<String,Object> userPublic(ParkingStore.UserData u){if(u==null)return null;return map("id",u.id(),"email",u.email(),"displayName",u.displayName(),"role",u.role());}
    private static Map<String,Object> feedbackMap(ParkingStore.FeedbackData f){return map("id",f.id(),"userId",f.userId(),"email",f.email(),"displayName",f.displayName(),"category",f.category(),"rating",f.rating(),"message",f.message(),"status",f.status(),"adminNote",f.adminNote(),"createdAt",f.createdAt().toString(),"updatedAt",f.updatedAt().toString());}
    private static Map<String,Object> reservationMap(ParkingStore.ReservationData r){return map("id",r.id(),"userId",r.userId(),"slotNumber",r.slotNumber(),"plate",r.plate(),"vehicleType",r.vehicleType(),"reservedAt",r.reservedAt().toString(),"durationMinutes",r.durationMinutes(),"status",r.status(),"createdAt",r.createdAt().toString());}
    private static Map<String,Object> sessionMap(ParkingStore.SessionData s,Instant now){return map("id",s.id(),"slotNumber",s.slotNumber(),"userId",s.userId(),"plate",s.plate(),"vehicleType",s.vehicleType(),"entryTime",s.entryTime().toString(),"exitTime",s.exitTime()==null?null:s.exitTime().toString(),"durationSeconds",s.durationSeconds(now),"active",s.active());}
    private static Map<String,Object> map(Object... pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i+1<pairs.length;i+=2)m.put(String.valueOf(pairs[i]),pairs[i+1]);return m;}
    private static String friendly(Exception e){String m=e.getMessage();return m==null||m.isBlank()?"The request could not be completed.":m;}
    private static void json(HttpExchange ex,int status,Object value)throws IOException{byte[] b=toJson(value).getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");securityHeaders(ex);ex.sendResponseHeaders(status,b.length);ex.getResponseBody().write(b);}
    private static void safeJson(HttpExchange ex,int status,Object value){try{json(ex,status,value);}catch(Exception ignored){}}
    private static void html(HttpExchange ex,int status,String content)throws IOException{byte[] b=content.getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");securityHeaders(ex);ex.sendResponseHeaders(status,b.length);ex.getResponseBody().write(b);}
    private static void securityHeaders(HttpExchange ex){ex.getResponseHeaders().set("X-Content-Type-Options","nosniff");ex.getResponseHeaders().set("Referrer-Policy","strict-origin-when-cross-origin");ex.getResponseHeaders().set("X-Frame-Options","DENY");ex.getResponseHeaders().set("Permissions-Policy","camera=(self), microphone=(), geolocation=()");}
    private void serveLogo(HttpExchange ex)throws IOException{Path path=Path.of("assets","logo.jpg");if(!Files.exists(path)){json(ex,404,map("message","Logo not installed."));return;}byte[] b=Files.readAllBytes(path);ex.getResponseHeaders().set("Content-Type","image/jpeg");ex.getResponseHeaders().set("Cache-Control","public, max-age=300");securityHeaders(ex);ex.sendResponseHeaders(200,b.length);ex.getResponseBody().write(b);}
    private void handleQrAction(HttpExchange ex,String path)throws IOException {
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("^/(enter|leave)/(\\d+)/?$").matcher(path);
        if(!m.matches()){qrPage(ex,404,"QR not recognized","This parking QR link is invalid.","Open website","/",false);return;}
        String action=m.group(1).equalsIgnoreCase("enter")?"ENTER":"LEAVE";
        int slot;try{slot=Integer.parseInt(m.group(2));}catch(NumberFormatException e){qrPage(ex,400,"QR not recognized","This slot number is invalid.","Open website","/",false);return;}
        if(isPrefetch(ex)){qrPage(ex,200,"Preview ignored","This QR link was previewed. No parking action was performed. Open the link directly and enter the vehicle plate to continue.","Open parking website","/",false);return;}
        try {
            lot.processExpirations();
            qrPlateForm(ex,action,slot,action.equals("ENTER")
                    ?"Enter the plate number registered on the reservation for this slot."
                    :"Enter the plate number recorded when the vehicle entered.","");
        } catch(Exception e) {
            qrPage(ex,500,"Parking system unavailable","The server could not load the slot right now. No parking action was recorded.","Open parking website","/",false);
        }
    }

    /** Google Lens and browser QR flow: only the plate is requested; the database supplies the rest. */
    private void handleQrActionPost(HttpExchange ex,String path)throws IOException {
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("^/(enter|leave)/(\\d+)/?$").matcher(path);
        if(!m.matches()){qrPage(ex,404,"QR not recognized","This parking QR link is invalid.","Open website","/",false);return;}
        String action=m.group(1).equalsIgnoreCase("enter")?"ENTER":"LEAVE";
        int slot;try{slot=Integer.parseInt(m.group(2));}catch(NumberFormatException e){qrPage(ex,400,"QR not recognized","This slot number is invalid.","Open website","/",false);return;}
        String requester=clientIp(ex);checkRate(requester,"qr");
        try {
            Map<String,String> f=form(ex);String plate=required(f,"plate");lot.processExpirations();
            ParkingStore.SessionData record=action.equals("ENTER")?lot.enterByPlate(slot,plate):lot.leaveByPlate(slot,plate);
            clearFailures(requester,"qr");
            String title=action.equals("ENTER")?"Entry Success":"Leave Success";
            String detail=action.equals("ENTER")
                    ?"Slot "+slot+" is now occupied by plate "+record.plate()+" ("+record.vehicleType()+")."
                    :"Slot "+slot+" is now free. Plate "+record.plate()+" parked for "+durationLabel(record.durationSeconds(Instant.now()))+".";
            qrPage(ex,200,title,detail,"Open parking dashboard","/",true);
        } catch(IllegalArgumentException|IllegalStateException e) {
            fail(requester,"qr");
            qrPlateForm(ex,action,slot,action.equals("ENTER")
                    ?"Enter the plate number registered on the reservation for this slot."
                    :"Enter the plate number recorded when the vehicle entered.",e.getMessage());
        } catch(SecurityException e) {
            fail(requester,"qr");
            qrPlateForm(ex,action,slot,action.equals("ENTER")
                    ?"Enter the plate number registered on the reservation for this slot."
                    :"Enter the plate number recorded when the vehicle entered.",e.getMessage());
        } catch(Exception e) {
            System.err.println("QR action failed: "+e.getClass().getSimpleName()+": "+e.getMessage());
            qrPlateForm(ex,action,slot,"The server could not confirm the action. Check the plate and try again.","No success was recorded. Please try again.");
        }
    }

    private static void qrPlateForm(HttpExchange ex,String action,int slot,String help,String error)throws IOException {
        boolean enter=action.equals("ENTER");
        String safeHelp=htmlEscape(help==null?"":help),safeError=htmlEscape(error==null?"":error);
        String title=enter?"Enter Slot "+slot:"Leave Slot "+slot;
        String button=enter?"Confirm Entry":"Confirm Leave";
        String page="<!doctype html><html lang='en'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1,viewport-fit=cover'><meta name='theme-color' content='#111111'><title>"+title+" Â· Uncle Dogs Parking v1.8.4</title><style>*{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;background:#111;color:#f7f7f7;font:16px/1.5 system-ui,-apple-system,'Segoe UI',sans-serif;padding:16px}.card{max-width:470px;width:100%;background:#1d1d20;border:1px solid #3d3d42;border-radius:24px;padding:clamp(20px,5vw,30px);box-shadow:0 20px 70px #0008;text-align:center}.logo{width:92px;height:92px;object-fit:contain;border-radius:18px;margin-bottom:12px}.mark{font-size:12px;letter-spacing:.12em;color:#bbb;text-transform:uppercase}.title{font-size:clamp(27px,6vw,34px);line-height:1.12;margin:12px 0;color:#f4f4f5}.message{color:#dedee2;background:#26262b;border:1px solid #44444b;border-radius:12px;padding:12px;margin:14px 0;text-align:left;font-size:14px}.error{color:#ffd4d4;background:#421f24;border-color:#74333c}.field{text-align:left;margin:15px 0}.field label{display:block;font-weight:750;font-size:14px;margin-bottom:7px}.field input{width:100%;padding:14px;border-radius:12px;border:1px solid #555;background:#111;color:#fff;font:inherit;text-transform:uppercase}.btn{display:block;width:100%;border:0;background:#f5f5f5;color:#111;text-decoration:none;font-weight:850;border-radius:12px;padding:14px 18px;margin-top:18px;font:inherit;cursor:pointer}.sub{font-size:12px;color:#aaa;margin-top:16px}.back{display:inline-block;margin-top:15px;color:#ddd}</style><main class='card'><img class='logo' src='/assets/logo.jpg' alt='Uncle Dogs Parking'><div class='mark'>Uncle Dogs Parking Â· Version 1.8.4</div><h1 class='title'>"+htmlEscape(title)+"</h1><p class='message'>"+safeHelp+"</p>"+(safeError.isBlank()?"":"<p class='message error' role='alert'>"+safeError+"</p>")+"<form method='post' action='/"+action.toLowerCase()+"/"+slot+"' autocomplete='on'><div class='field'><label for='plate'>Vehicle plate number</label><input id='plate' name='plate' required maxlength='15' autocomplete='off' placeholder='e.g. ABC 1234' pattern='[A-Za-z0-9 -]{2,15}' autocapitalize='characters'></div><button class='btn' type='submit'>"+button+"</button></form><div class='sub'>The server verifies the plate against the reservation or active parking record. No action is saved until it matches.</div><a class='back' href='/'>Open parking dashboard</a></main></html>";
        html(ex,200,page);
    }
    private static String durationLabel(long seconds) {
        long total=Math.max(0,seconds/60),hours=total/60,minutes=total%60;
        return hours>0?hours+"h "+minutes+"m":minutes+"m";
    }

    private static void qrPage(HttpExchange ex,int status,String title,String message,String linkLabel,String link,boolean success)throws IOException{
        String safeTitle=htmlEscape(title),safeMessage=htmlEscape(message),safeLabel=htmlEscape(linkLabel),safeLink=htmlEscape(link);
        String color=success?"#177245":"#b4232b";
        String page="<!doctype html><html lang='en'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1,viewport-fit=cover'><meta name='theme-color' content='#111111'><title>"+safeTitle+" Â· Uncle Dogs Parking v1.8.4</title><style>*{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;background:#111;color:#f7f7f7;font:16px/1.55 system-ui,-apple-system,'Segoe UI',sans-serif;padding:22px}.card{max-width:520px;width:100%;background:#1d1d20;border:1px solid #3d3d42;border-radius:24px;padding:28px;box-shadow:0 20px 70px #0008;text-align:center}.logo{width:100px;height:100px;object-fit:contain;border-radius:18px;margin-bottom:12px}.mark{font-size:12px;letter-spacing:.15em;color:#bbb;text-transform:uppercase}.title{font-size:clamp(27px,6vw,36px);line-height:1.12;margin:12px 0;color:"+color+"}.message{color:#dedee2;margin:12px 0 22px}.btn{display:inline-block;background:#f5f5f5;color:#111;text-decoration:none;font-weight:800;border-radius:12px;padding:12px 18px}.sub{font-size:12px;color:#aaa;margin-top:18px}</style><main class='card'><img class='logo' src='/assets/logo.jpg' alt='Uncle Dogs Parking'><div class='mark'>Uncle Dogs Parking Â· Version 1.8.4</div><h1 class='title'>"+safeTitle+"</h1><p class='message'>"+safeMessage+"</p><a class='btn' href='"+safeLink+"'>"+safeLabel+"</a><div class='sub'>Parking records are updated only after the server confirms the action.</div></main></html>";
        html(ex,status,page);
    }
    private static String htmlEscape(String s){return String.valueOf(s==null?"":s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    private static void neutralLegacyPage(HttpExchange ex)throws IOException{html(ex,200,"<!doctype html><html><meta name='viewport' content='width=device-width, initial-scale=1'><title>Uncle Dogs Parking</title><body style='font:16px system-ui;max-width:540px;margin:48px auto;padding:18px'><h1>Uncle Dogs Parking</h1><p>Use the latest QR labels from Version 1.8.4. The new ENTER and LEAVE codes open this website directly.</p><p><a href='/'>Open the parking website</a></p></body></html>");}
    private void handleReadyz(HttpExchange ex) throws IOException {
        if(!lot.isInitialized()){try{lot.ensureInitialized();}catch(Exception e){json(ex,503,map("status","unavailable","db","unreachable"));return;}}
        try{lot.processExpirations();json(ex,200,map("status","ready","db",lot.getStorage().isRemoteDatabase()?"postgresql":"csv","time",Instant.now().toString()));}
        catch(Exception e){json(ex,503,map("status","degraded","db","unreachable"));}
    }
    private static boolean isDatabaseError(Exception e) {
        for(Throwable t=e;t!=null;t=t.getCause()){
            if(t instanceof java.sql.SQLException)return true;
            if(t instanceof java.net.SocketTimeoutException)return true;
            if(t instanceof java.net.ConnectException)return true;
            if(t instanceof java.net.UnknownHostException)return true;
        }
        return false;
    }

    private static boolean isPrefetch(HttpExchange ex){String[] headers={"Purpose","Sec-Purpose","X-Moz"};for(String h:headers){String v=ex.getRequestHeaders().getFirst(h);if(v!=null&&(v.toLowerCase().contains("prefetch")||v.toLowerCase().contains("preview")||v.toLowerCase().contains("prerender")))return true;}
        String ua=ex.getRequestHeaders().getFirst("User-Agent");if(ua==null)return false;String v=ua.toLowerCase();return v.contains("bot")||v.contains("crawler")||v.contains("spider")||v.contains("preview")||v.contains("facebookexternalhit")||v.contains("slackbot")||v.contains("whatsapp")||v.contains("discordbot");}

    private static String toJson(Object value){if(value==null)return "null";if(value instanceof String||value instanceof Character||value instanceof java.time.temporal.TemporalAccessor)return quote(String.valueOf(value));if(value instanceof Number||value instanceof Boolean)return String.valueOf(value);if(value instanceof Map<?,?> m){StringBuilder b=new StringBuilder("{");boolean first=true;for(Map.Entry<?,?> e:m.entrySet()){if(!first)b.append(',');first=false;b.append(quote(String.valueOf(e.getKey()))).append(':').append(toJson(e.getValue()));}return b.append('}').toString();}if(value instanceof Iterable<?> it){StringBuilder b=new StringBuilder("[");boolean first=true;for(Object item:it){if(!first)b.append(',');first=false;b.append(toJson(item));}return b.append(']').toString();}if(value.getClass().isArray()){StringBuilder b=new StringBuilder("[");int n=java.lang.reflect.Array.getLength(value);for(int i=0;i<n;i++){if(i>0)b.append(',');b.append(toJson(java.lang.reflect.Array.get(value,i)));}return b.append(']').toString();}return quote(String.valueOf(value));}
    private static String quote(String s){StringBuilder b=new StringBuilder("\"");for(char c:s.toCharArray()){switch(c){case '"'->b.append("\\\"");case '\\'->b.append("\\\\");case '\b'->b.append("\\b");case '\f'->b.append("\\f");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->{if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}}return b.append('"').toString();}

    private record UserSession(long userId,Instant expiresAt){}
    private static final class FailureWindow{final Instant windowStart;int count;Instant blockedUntil;FailureWindow(Instant start,int count,Instant blocked){windowStart=start;this.count=count;blockedUntil=blocked;}}
}

