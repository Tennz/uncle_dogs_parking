import service.AppConfig;
import service.ParkingLot;
import storage.FileRecordStorage;
import storage.PostgresRecordStorage;
import storage.RecordStorage;
import web.ParkingServer;
import gui.AdminWindow;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Main {
    public static void main(String[] args) {
        try {
            AppConfig config=AppConfig.load(Path.of("config.txt"));
            boolean vercelDeployment=AppConfig.isVercelDeployment();
            String databaseUrl=config.get("DATABASE_URL","");
            if(vercelDeployment&&databaseUrl.isBlank())throw new IllegalStateException("Vercel deployment requires DATABASE_URL. Configure your existing Supabase PostgreSQL connection in Vercel Environment Variables.");
            if(vercelDeployment&&config.get("ADMIN_KEY","").isBlank())throw new IllegalStateException("Vercel deployment requires ADMIN_KEY in Vercel Environment Variables. Do not rely on a local admin.key file.");
            String adminKey=config.getOrCreateAdminKey();
            int port=config.getInt("PORT",8080);
            RecordStorage storage;
            if(!databaseUrl.isBlank()){
                try { Class.forName("org.postgresql.Driver"); } catch(ClassNotFoundException e) { throw new IllegalStateException("PostgreSQL JDBC driver was not found. Run using run.bat or run.sh."); }
                storage=new PostgresRecordStorage(databaseUrl,config.get("DATABASE_USER",""),config.get("DATABASE_PASSWORD",""));
            } else {
                storage=new FileRecordStorage(Path.of(config.get("DATA_DIR","data")));
                System.out.println("Storage mode: local CSV. Add DATABASE_URL, DATABASE_USER, and DATABASE_PASSWORD for shared Supabase PostgreSQL.");
            }
            ParkingLot initializedLot = new ParkingLot(storage);
            try { initializedLot.initialize(); }
            catch(Exception storageError) {
                boolean allowLocalFallback = config.getBoolean("ALLOW_LOCAL_CSV_FALLBACK", false);
                if(storage.isRemoteDatabase() && allowLocalFallback && !vercelDeployment) {
                    System.err.println();
                    System.err.println("WARNING: Supabase is unreachable. Starting LOCAL CSV MODE because ALLOW_LOCAL_CSV_FALLBACK=true.");
                    System.err.println("WARNING: Accounts, reservations, and visits created in this mode will NOT sync with Supabase.");
                    storage = new FileRecordStorage(Path.of(config.get("DATA_DIR","data")));
                    initializedLot = new ParkingLot(storage);
                    initializedLot.initialize();
                } else if(storage.isRemoteDatabase() && vercelDeployment) {
                    System.err.println("WARNING: Database not reachable at startup: "+storageError.getMessage());
                    System.err.println("The HTTP server will start in degraded mode. API routes requiring the database will return HTTP 503 until the connection is restored.");
                } else if(storage.isRemoteDatabase()) {
                    throw new IllegalStateException(
                        "Could not connect to Supabase, so the app did not start. For Supabase shared-pooler URLs, the connection layer attempts Session Pooler port 5432 and tries Transaction Pooler port 6543 only after a network/SSL connection failure. Run check-supabase.bat and use the exact host from Supabase Connect. No CSV fallback was used, to avoid splitting shared reservations. For a temporary LAN-only UI test, set ALLOW_LOCAL_CSV_FALLBACK=true in config.txt; this mode does not sync with Supabase.", storageError);
                } else throw storageError;
            }
            final ParkingLot lot = initializedLot;
            List<String> lanIps=detectLanIps();
            String lanIp=lanIps.isEmpty()?"127.0.0.1":lanIps.get(0);
            String lanUrl="http://"+lanIp+":"+port;
            String baseUrl=config.baseUrl();
            if(baseUrl.isBlank()&&!vercelDeployment)baseUrl=lanUrl;
            ParkingServer server=new ParkingServer(lot,port,baseUrl,adminKey);
            server.start();
            if(!vercelDeployment)startReservationExpiryMaintenance(lot);
            System.out.println();
            System.out.println("===============================================");
            System.out.println(" Uncle Dogs Parking is running · Version 1.8.3");
            if(baseUrl.isBlank()&&vercelDeployment)System.out.println(" Public URL: detected from the HTTPS request hostname");
            else System.out.println(" Website base URL: "+baseUrl);
            if(!vercelDeployment){
                System.out.println(" Local URL: http://localhost:"+port);
                if(lanIps.isEmpty())System.out.println(" Network URL: No private IPv4 address detected; check your Wi-Fi/Ethernet adapter.");
                else {
                    System.out.println(" Network URL (try this on your phone): "+lanUrl);
                    for(int i=1;i<lanIps.size();i++)System.out.println(" Other LAN URL: http://"+lanIps.get(i)+":"+port);
                }
                System.out.println(" Phone help: connect both devices to the same non-guest Wi-Fi. If the phone cannot load the URL, run allow-lan.bat once and approve the Windows UAC prompt.");
                System.out.println(" Health check: open http://"+lanIp+":"+port+"/healthz from the phone; it should display {\"status\":\"ok\"}.");
            }
            if(config.wasAdminKeyGeneratedNow()) System.out.println(" New admin key (save it privately): "+adminKey);
            else System.out.println(" Admin key: configured privately; it is not printed again.");
            System.out.println(" Storage: "+(storage.isRemoteDatabase()?"Supabase/PostgreSQL":"local CSV files"));
            System.out.println(" Reservation expiry: 90-minute late-arrival grace; local cleanup runs every 30 seconds.");
            if(vercelDeployment)System.out.println(" Cloud expiry: run database/setup_reservation_expiry.sql once in the existing Supabase project for scheduled expiry when Vercel is idle.");
            System.out.println("===============================================");
            System.out.println("Keep this window open while the server is in use.");
            if(!GraphicsEnvironment.isHeadless() && config.getBoolean("SHOW_SWING",true)){
                String finalBaseUrl=baseUrl.isBlank()?lanUrl:baseUrl;
                javax.swing.SwingUtilities.invokeLater(()->new AdminWindow(lot,finalBaseUrl,port).setVisible(true));
            }
            if(config.getBoolean("OPEN_BROWSER",false)&&Desktop.isDesktopSupported())Desktop.getDesktop().browse(URI.create("http://localhost:"+port));
        } catch(Exception e){
            System.err.println("Uncle Dogs Parking could not start: "+e.getMessage());
            e.printStackTrace(System.err);
            System.err.println("Check config.txt and README.txt. Do not share database passwords or the admin key publicly.");
            System.exit(1);
        }
    }
    private record LanCandidate(String address, int score) {}

    /** Prefer real private Wi-Fi/Ethernet addresses over VPN and virtual adapters. */
    private static List<String> detectLanIps(){
        List<LanCandidate> candidates=new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
            while(interfaces!=null && interfaces.hasMoreElements()){
                NetworkInterface ni=interfaces.nextElement();
                if(!ni.isUp()||ni.isLoopback()||ni.isPointToPoint())continue;
                String label=(ni.getName()+" "+ni.getDisplayName()).toLowerCase(Locale.ROOT);
                int interfaceScore=0;
                if(label.contains("wi-fi")||label.contains("wifi")||label.contains("wireless")||label.contains("wlan"))interfaceScore+=60;
                if(label.contains("ethernet"))interfaceScore+=45;
                if(label.contains("virtual")||label.contains("vmware")||label.contains("virtualbox")||label.contains("hyper-v")||label.contains("docker")||label.contains("wsl")||label.contains("tailscale")||label.contains("wireguard")||label.contains("vpn")||label.contains("tap"))interfaceScore-=100;
                if(ni.isVirtual())interfaceScore-=60;
                Enumeration<InetAddress> addresses=ni.getInetAddresses();
                while(addresses.hasMoreElements()){
                    InetAddress address=addresses.nextElement();
                    if(!(address instanceof Inet4Address)||address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isMulticastAddress())continue;
                    if(!address.isSiteLocalAddress())continue;
                    String ip=address.getHostAddress();
                    int score=interfaceScore+100;
                    if(ip.startsWith("192.168."))score+=20;
                    else if(ip.startsWith("10."))score+=15;
                    else if(ip.matches("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*"))score+=10;
                    candidates.add(new LanCandidate(ip,score));
                }
            }
        }catch(Exception ignored){}
        candidates.sort(Comparator.comparingInt(LanCandidate::score).reversed().thenComparing(LanCandidate::address));
        LinkedHashSet<String> unique=new LinkedHashSet<>();for(LanCandidate candidate:candidates)unique.add(candidate.address());
        return new ArrayList<>(unique);
    }

    /** Local/serverless processes do not run indefinitely on Vercel, so Vercel uses DB cron instead. */
    private static void startReservationExpiryMaintenance(ParkingLot lot){
        ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"udp-reservation-expiry");t.setDaemon(true);return t;});
        scheduler.scheduleWithFixedDelay(()->{
            try{lot.processExpirations();}
            catch(Exception e){System.err.println("Reservation expiry check failed; it will retry: "+e.getClass().getSimpleName()+": "+e.getMessage());}
        },30,30,TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(scheduler::shutdownNow,"udp-reservation-expiry-shutdown"));
    }

}
