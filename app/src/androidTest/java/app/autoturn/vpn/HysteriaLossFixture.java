package app.autoturn.vpn;

import android.content.Context;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/** Relays the test tunnel's encrypted UDP to its configured peer until the test drops packets. */
final class HysteriaLossFixture implements AutoCloseable {
    private final Selector selector;
    private final DatagramChannel local;
    private final DatagramChannel remote;
    private final Thread relay;
    private volatile boolean dropping;
    private volatile boolean closed;
    private volatile IOException failure;
    final Profile profile;

    HysteriaLossFixture(Context context, Profile original) throws Exception {
        JSONObject outbound = XrayVpnService.outbounds(original.config, original.type).getJSONObject(0);
        JSONObject peer = outbound.getJSONObject("settings");
        String host = peer.getString("address");
        JSONObject tls = outbound.getJSONObject("streamSettings").getJSONObject("tlsSettings");
        if (tls.optString("serverName").isEmpty()) tls.put("serverName", host);
        InetAddress address = InetAddress.getByName(new EndpointResolver(context, () -> true, false).resolve(host));
        selector = Selector.open();
        local = DatagramChannel.open();
        remote = DatagramChannel.open();
        try {
            EndpointResolver.physicalNetwork(context).bindSocket(remote.socket());
            remote.connect(new InetSocketAddress(address, peer.getInt("port")));
            local.bind(new InetSocketAddress("127.0.0.1", 0));
            for (DatagramChannel channel : new DatagramChannel[]{local, remote}) {
                channel.configureBlocking(false);
                channel.register(selector, SelectionKey.OP_READ);
            }
            peer.put("address", "127.0.0.1").put("port", local.socket().getLocalPort());
            profile = new Profile("QA Hysteria", Profile.HYSTERIA2,
                    new JSONObject().put("outbounds", new JSONArray().put(outbound)).toString());
            relay = new Thread(this::relay, "QA-Hysteria-UDP");
            relay.start();
        } catch (Exception e) {
            local.close(); remote.close(); selector.close();
            throw e;
        }
    }

    private void relay() {
        SocketAddress client = null;
        ByteBuffer packet = ByteBuffer.allocate(65535);
        try {
            while (!closed) {
                selector.select();
                Iterator<SelectionKey> ready = selector.selectedKeys().iterator();
                while (ready.hasNext()) {
                    DatagramChannel source = (DatagramChannel) ready.next().channel();
                    ready.remove();
                    packet.clear();
                    SocketAddress sender = source.receive(packet);
                    if (sender == null || dropping) continue;
                    packet.flip();
                    if (source == local) {
                        client = sender;
                        remote.write(packet);
                    } else if (client != null) local.send(packet, client);
                }
            }
        } catch (IOException e) { failure = e; }
    }

    void dropTraffic() throws IOException {
        if (failure != null) throw failure;
        dropping = true;
    }

    @Override public void close() throws Exception {
        closed = true;
        selector.wakeup();
        try { relay.join(3000); }
        finally { local.close(); remote.close(); selector.close(); }
        if (failure != null) throw failure;
    }
}
