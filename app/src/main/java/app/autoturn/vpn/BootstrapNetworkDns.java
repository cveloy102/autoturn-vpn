package app.autoturn.vpn;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkRequest;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/** Private native bootstrap configuration. No listener and no application DNS forwarding. */
final class BootstrapNetworkDns implements AutoCloseable {
    private final Context context;
    private final ConnectivityManager connectivity;
    private final File file;
    private boolean closed, registered;
    private String lastConfig;
    private final ConnectivityManager.NetworkCallback callback=new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network){update();}
        @Override public void onLost(Network network){update();}
        @Override public void onCapabilitiesChanged(Network network,android.net.NetworkCapabilities capabilities){update();}
        @Override public void onLinkPropertiesChanged(Network network,LinkProperties properties){update();}
    };
    BootstrapNetworkDns(Context context,File directory,String session) throws Exception {
        this.context=context;connectivity=context.getSystemService(ConnectivityManager.class);
        file=new File(directory,"bootstrap-dns-"+session+".conf");
        connectivity.registerNetworkCallback(new NetworkRequest.Builder().build(),callback);registered=true;
        update();
    }
    String argument(){return "network:"+file.getAbsolutePath();}
    private synchronized void update() {
        if(closed)return;
        StringBuilder addresses=new StringBuilder();
        try {
            LinkProperties links=connectivity.getLinkProperties(EndpointResolver.physicalNetwork(context));
            int count=0;
            if(links!=null)for(InetAddress address:links.getDnsServers()) {
                if(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress())continue;
                if(count++==8)break;
                String ip=address.getHostAddress();addresses.append(ip.contains(":")?"["+ip+"]":ip).append(":53\n");
            }
        }catch(Exception ignored){/* Empty file means temporarily no bootstrap DNS, with no public fallback. */}
        String text=addresses.toString();if(text.equals(lastConfig) && file.isFile())return;
        AtomicFile atomic=new AtomicFile(file);FileOutputStream out=null;
        try {out=atomic.startWrite();out.write(text.getBytes(StandardCharsets.US_ASCII));atomic.finishWrite(out);lastConfig=text;}
        catch(Exception ignored){if(out!=null)atomic.failWrite(out);}
    }
    @Override public synchronized void close() {
        closed=true;if(registered){try{connectivity.unregisterNetworkCallback(callback);}catch(IllegalArgumentException ignored){}registered=false;}
        new AtomicFile(file).delete();
    }
}
