package service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AppConfig {
    private final Path path;
    private final Map<String,String> values=new LinkedHashMap<>();
    private AppConfig(Path path){this.path=path;}
    public static AppConfig load(Path path)throws IOException{
        AppConfig c=new AppConfig(path);
        if(Files.exists(path))for(String line:Files.readAllLines(path,StandardCharsets.UTF_8)){String v=line.trim();if(v.isEmpty()||v.startsWith("#")||!v.contains("="))continue;int p=v.indexOf('=');c.values.put(v.substring(0,p).trim(),v.substring(p+1).trim());}
        return c;
    }
    public String get(String key,String fallback){String env=System.getenv(key);if(env!=null&&!env.isBlank())return env.trim();String v=values.get(key);return v==null?fallback:v;}
    public int getInt(String key,int fallback){try{return Integer.parseInt(get(key,""+fallback));}catch(NumberFormatException e){return fallback;}}
    public boolean getBoolean(String key,boolean fallback){return Boolean.parseBoolean(get(key,""+fallback));}
    private boolean generatedAdminKeyNow;
    public synchronized String getOrCreateAdminKey()throws IOException{
        String fromEnv=System.getenv("ADMIN_KEY");if(fromEnv!=null&&!fromEnv.isBlank())return fromEnv.trim();
        String current=values.get("ADMIN_KEY");if(current!=null&&!current.isBlank())return current;
        Path keyFile=Path.of(get("DATA_DIR","data")).resolve("admin.key");
        if(Files.exists(keyFile)){
            current=Files.readString(keyFile,StandardCharsets.UTF_8).trim();
            if(!current.isBlank())return current;
        }
        byte[] bytes=new byte[24];new SecureRandom().nextBytes(bytes);current=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Files.createDirectories(keyFile.getParent());Files.writeString(keyFile,current+"\n",StandardCharsets.UTF_8);generatedAdminKeyNow=true;return current;
    }
    public boolean wasAdminKeyGeneratedNow(){return generatedAdminKeyNow;}
    public String baseUrl(){
        String configured=get("BASE_URL","");
        if(!configured.isBlank())return normalizeUrl(configured);
        String vercelEnv=System.getenv("VERCEL_ENV");
        String vercelHost;
        if("production".equalsIgnoreCase(vercelEnv)){
            vercelHost=System.getenv("VERCEL_PROJECT_PRODUCTION_URL");
            if(vercelHost==null||vercelHost.isBlank())vercelHost=System.getenv("VERCEL_URL");
        }else{
            vercelHost=System.getenv("VERCEL_URL");
            if(vercelHost==null||vercelHost.isBlank())vercelHost=System.getenv("VERCEL_BRANCH_URL");
            if(vercelHost==null||vercelHost.isBlank())vercelHost=System.getenv("VERCEL_PROJECT_PRODUCTION_URL");
        }
        if(vercelHost!=null&&!vercelHost.isBlank())return normalizeUrl(vercelHost);
        return "";
    }
    public static boolean isVercelDeployment(){
        String target=System.getenv("DEPLOYMENT_TARGET");
        return "vercel".equalsIgnoreCase(target)||"1".equals(System.getenv("VERCEL"))||System.getenv("VERCEL_ENV")!=null;
    }
    private static String normalizeUrl(String value){
        String v=stripTrailingSlash(value.trim());
        if(v.startsWith("https://")||v.startsWith("http://"))return v;
        return "https://"+v;
    }
    public void save()throws IOException{Files.writeString(path,"# Uncle Dogs Parking configuration\n# Keep ADMIN_KEY and database credentials private.\n"+values.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).reduce("",(a,b)->a+b+"\n"),StandardCharsets.UTF_8);}
    private static String stripTrailingSlash(String s){String v=s.trim();while(v.endsWith("/"))v=v.substring(0,v.length()-1);return v;}
}
