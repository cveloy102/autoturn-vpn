package app.autoturn.vpn;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import org.amnezia.awg.config.Config;
import org.amnezia.awg.config.InetEndpoint;
import org.amnezia.awg.config.Peer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Saved endpoint addresses, not an application DNS resolver. */
final class EndpointResolver {
    interface Lookup { BootstrapDns.Answer resolve(String host) throws Exception; }
    private final Context context;
    private final BooleanSupplier current;
    private final EndpointCache cache;
    private final boolean protectedCache;
    private final Lookup injected;

    EndpointResolver(Context context,BooleanSupplier current){this(context,current,true,null);}
    EndpointResolver(Context context,BooleanSupplier current,boolean encrypted){this(context,current,encrypted,null);}
    EndpointResolver(Context context,BooleanSupplier current,Lookup lookup){this(context,current,true,lookup);}
    private EndpointResolver(Context context,BooleanSupplier current,boolean encrypted,Lookup lookup) {
        this.context=context;this.current=current;this.protectedCache=encrypted;injected=lookup;
        cache=new EndpointCache(context,encrypted);
    }
    private void checkCurrent() throws InterruptedIOException {
        if(Thread.currentThread().isInterrupted() || !current.getAsBoolean())throw new InterruptedIOException("Endpoint lookup cancelled");
    }
    private static String name(String host) throws IOException {
        String result=IDN.toASCII(host,IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        if(result.endsWith("."))result=result.substring(0,result.length()-1);
        BootstrapDns.query(result,0);return result;
    }
    private static String numeric(String host) {
        String value=host.startsWith("[") && host.endsWith("]")?host.substring(1,host.length()-1):host;
        try{return android.net.InetAddresses.parseNumericAddress(value).getHostAddress();}
        catch(IllegalArgumentException domain){return null;}
    }
    String resolve(String host) throws Exception {
        checkCurrent();String literal=numeric(host);if(literal!=null)return literal;
        String key=name(host);EndpointCache.Entry previous=cache.get(key);
        if(previous==null && !protectedCache) {
            EndpointCache.Entry secure=new EndpointCache(context,true).get(key);
            if(secure!=null && secure.usable(System.currentTimeMillis())) {
                cache.put(key,secure.ip,0,secure.savedAt);previous=secure;
            }
        }
        // This is a connection hint, not a fresh DNS answer. Authentication still uses
        // the original SNI/certificate or AWG key. Never wait for DNS if an IP is known.
        if(previous!=null && previous.usable(System.currentTimeMillis()))return previous.ip;
        return lookupAndSave(key);
    }
    private String lookupAndSave(String host) throws Exception {
        BootstrapDns.Answer answer=boundedLookup(host);checkCurrent();
        InetAddress address=InetAddress.getByAddress(answer.address);
        if(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress())
            throw new IOException("Invalid VPN endpoint address");
        String ip=address.getHostAddress();cache.put(host,ip,answer.ttlSeconds,System.currentTimeMillis());return ip;
    }
    void refresh(String host,boolean force) throws Exception {
        checkCurrent();if(numeric(host)!=null)return;
        String key=name(host);EndpointCache.Entry previous=cache.get(key);
        if(!force && (previous==null || previous.fresh(System.currentTimeMillis())))return;
        lookupAndSave(key); // A failure leaves the old address intact.
    }
    void refreshProfile(Profile profile,boolean force) throws Exception {
        for(String host:hosts(profile))refresh(host,force);
    }
    private BootstrapDns.Answer boundedLookup(String host) throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"endpoint-lookup");t.setDaemon(true);return t;});
        Future<BootstrapDns.Answer> task=worker.submit(()->{
            if(injected!=null)return injected.resolve(host);
            for(InetAddress address:physicalNetwork(context).getAllByName(host))
                if(address instanceof Inet4Address)return new BootstrapDns.Answer(address.getAddress(),3600);
            throw new IOException("VPN endpoint has no IPv4 address");
        });
        long deadline=android.os.SystemClock.elapsedRealtime()+6500;
        try {
            while(android.os.SystemClock.elapsedRealtime()<deadline) {
                checkCurrent();try{return task.get(100,TimeUnit.MILLISECONDS);}catch(java.util.concurrent.TimeoutException pending){}
            }
            throw new IOException("Endpoint DNS timed out");
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Endpoint lookup cancelled");}
        catch(java.util.concurrent.ExecutionException e){throw new IOException("Endpoint DNS unavailable",e.getCause());}
        finally {
            task.cancel(true);
            worker.shutdownNow();
        }
    }
    static Network physicalNetwork(Context context) throws IOException {
        ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);
        Network active=cm.getActiveNetwork(),best=null;int score=-1;
        for(Network network:cm.getAllNetworks()) {
            if(!ConnectionProbe.isUnderlyingInternet(context,cm,network))continue;
            NetworkCapabilities caps=cm.getNetworkCapabilities(network);if(caps==null)continue;
            int weight=(network.equals(active)?16:0)+(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?8:0)
                    +(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?4:0);
            if(weight>score){best=network;score=weight;}
        }
        if(best==null)throw new IOException("No physical network for endpoint DNS");return best;
    }
    private static Set<String> hosts(Profile profile) throws Exception {
        Set<String> result=new java.util.LinkedHashSet<>();
        if(Profile.AMNEZIA.equals(profile.type)) {
            Config config=Config.parse(new java.io.BufferedReader(new java.io.StringReader(profile.config)));
            for(Peer peer:config.getPeers())if(peer.getEndpoint().isPresent())result.add(peer.getEndpoint().get().getHost());
        }else if(Profile.QWDTT.equals(profile.type)) {
            result.add(new java.net.URI("udp://"+QwdttConfig.parse(profile.config).peer).getHost());
        }else if(!profile.config.trim().startsWith("{")) {
            String host=android.net.Uri.parse(profile.config).getHost();if(host!=null)result.add(host);
        }else {
            JSONArray outbounds=new JSONObject(profile.config).optJSONArray("outbounds");
            if(outbounds!=null)for(int i=0;i<outbounds.length();i++) {
                JSONObject outbound=outbounds.getJSONObject(i);String protocol=outbound.optString("protocol");
                if(!(Profile.VLESS.equals(profile.type) && protocol.equalsIgnoreCase("vless")) &&
                        !(Profile.HYSTERIA2.equals(profile.type) && protocol.equalsIgnoreCase("hysteria")))continue;
                JSONObject settings=outbound.optJSONObject("settings");if(settings==null)continue;
                if(settings.has("address"))result.add(settings.getString("address"));
                JSONArray peers=settings.optJSONArray("vnext");
                if(peers!=null)for(int j=0;j<peers.length();j++)result.add(peers.getJSONObject(j).getString("address"));
            }
        }
        return result;
    }
    void resolveXray(JSONObject outbound) throws Exception {
        JSONObject settings=outbound.getJSONObject("settings");
        if(settings.has("address"))replaceAddress(outbound,settings,"address");
        for(String list:new String[]{"vnext","servers"}) {
            JSONArray peers=settings.optJSONArray(list);if(peers==null)continue;
            for(int i=0;i<peers.length();i++)replaceAddress(outbound,peers.getJSONObject(i),"address");
        }
    }
    private void replaceAddress(JSONObject outbound,JSONObject peer,String key) throws Exception {
        String original=peer.getString(key),ip=resolve(original);
        if(!ip.equals(original)) {
            JSONObject stream=outbound.optJSONObject("streamSettings");
            if(stream!=null) {
                for(String setting:new String[]{"tlsSettings","realitySettings"}) {
                    JSONObject tls=stream.optJSONObject(setting);
                    String security=stream.optString("security");
                    if(tls==null && ((setting.equals("tlsSettings") && security.equals("tls")) ||
                            (setting.equals("realitySettings") && security.equals("reality")))) {
                        tls=new JSONObject();stream.put(setting,tls);
                    }
                    if(tls!=null && tls.optString("serverName").isEmpty())tls.put("serverName",original);
                }
                JSONObject ws=stream.optJSONObject("wsSettings");
                if(ws!=null) {JSONObject headers=ws.optJSONObject("headers");if(headers==null){headers=new JSONObject();ws.put("headers",headers);}
                    if(!headers.has("Host"))headers.put("Host",original);}
                for(String setting:new String[]{"httpupgradeSettings","xhttpSettings"}) {
                    JSONObject http=stream.optJSONObject(setting);if(http!=null && http.optString("host").isEmpty())http.put("host",original);
                }
                JSONObject grpc=stream.optJSONObject("grpcSettings");if(grpc!=null && grpc.optString("authority").isEmpty())grpc.put("authority",original);
            }
        }
        peer.put(key,ip);
    }
    Config resolveAwg(Config source) throws Exception {
        Config.Builder result=new Config.Builder().setInterface(source.getInterface());
        for(Peer peer:source.getPeers()) {
            InetEndpoint remote=peer.getEndpoint().orElseThrow(()->new IllegalArgumentException("Missing endpoint"));
            String ip=resolve(remote.getHost());if(ip.contains(":"))ip="["+ip+"]";
            List<String> lines=new ArrayList<>();
            for(String line:peer.toAwgQuickString().split("\\r?\\n")) {
                if(line.trim().startsWith("Endpoint"))line="Endpoint = "+ip+":"+remote.getPort();lines.add(line);
            }
            result.addPeer(Peer.parse(lines));
        }
        return result.build();
    }
}
