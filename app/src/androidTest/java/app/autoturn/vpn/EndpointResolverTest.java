package app.autoturn.vpn;

import android.content.Context;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Cache and bootstrap fixtures never edit real profiles or connect to a real resolver. */
public final class EndpointResolverTest extends InstrumentationTestCase {
    private File directory;
    private Context context;
    @Override protected void setUp() throws Exception {
        super.setUp();directory=new File(getInstrumentation().getTargetContext().getCacheDir(),"endpoint-tests");directory.mkdirs();
        for(File file:directory.listFiles())file.delete();
        context=new android.content.ContextWrapper(getInstrumentation().getTargetContext()) {
            @Override public Context getApplicationContext(){return this;}
            @Override public File getFilesDir(){return directory;}
        };
    }
    @Override protected void tearDown() throws Exception {
        for(File file:directory.listFiles())file.delete();directory.delete();super.tearDown();
    }
    private EndpointResolver offline(){return new EndpointResolver(context,()->true,host->{throw new IOException("No encrypted DNS");});}
    private interface Checked {void run() throws Exception;}
    private void rejects(Checked action) throws Exception {
        try{action.run();fail("Expected rejected response/lookup");}catch(IOException expected){}
    }
    public void testFreshCachePersistsEncryptedAndAvoidsNetwork() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        EndpointResolver resolver=new EndpointResolver(context,()->true,host->{calls.incrementAndGet();return new BootstrapDns.Answer(new byte[]{1,2,3,4},60);});
        assertEquals("1.2.3.4",resolver.resolve("Endpoint.Example."));assertEquals(1,calls.get());
        assertEquals("1.2.3.4",offline().resolve("endpoint.example"));
        String encrypted=Files.readString(new File(directory,"endpoint-cache.enc").toPath());
        assertFalse(encrypted.contains("endpoint.example"));assertFalse(encrypted.contains("1.2.3.4"));
        EndpointCache.Entry read=new EndpointCache(context).get("endpoint.example");assertNotNull(read);assertTrue(read.fresh(System.currentTimeMillis()));
    }
    public void testExpiredTtlUsesSavedIpOnlyWhenEncryptedDnsFails() throws Exception {
        EndpointCache cache=new EndpointCache(context);cache.put("endpoint.example","1.2.3.4",0,System.currentTimeMillis()-10000);
        assertEquals("1.2.3.4",offline().resolve("endpoint.example"));
        EndpointResolver refreshed=new EndpointResolver(context,()->true,host->new BootstrapDns.Answer(new byte[]{5,6,7,8},120));
        refreshed.refresh("endpoint.example",true);
        assertEquals("5.6.7.8",refreshed.resolve("endpoint.example"));
        assertEquals("5.6.7.8",offline().resolve("endpoint.example"));
    }
    public void testMissingAndCorruptCacheFailClosedButOldHintsRemainUseful() throws Exception {
        rejects(()->offline().resolve("missing.example"));
        new EndpointCache(context).put("old.example","1.2.3.4",0,System.currentTimeMillis()-30L*24*60*60*1000);
        assertEquals("A static VPS hint remains usable while DNS is blocked","1.2.3.4",offline().resolve("old.example"));
        Files.writeString(new File(directory,"endpoint-cache.enc").toPath(),"corrupted");
        rejects(()->offline().resolve("old.example"));
    }
    public void testCancellationNeverUsesEvenFreshCache() throws Exception {
        new EndpointCache(context).put("endpoint.example","1.2.3.4",60,System.currentTimeMillis());
        rejects(()->new EndpointResolver(context,()->false).resolve("endpoint.example"));
        java.util.concurrent.atomic.AtomicBoolean current=new java.util.concurrent.atomic.AtomicBoolean(true);
        EndpointResolver cancelled=new EndpointResolver(context,current::get,host->{current.set(false);throw new IOException("Cancelled");});
        new EndpointCache(context).put("endpoint.example","1.2.3.4",0,System.currentTimeMillis()-1000);
        rejects(()->cancelled.refresh("endpoint.example",true));
    }
    public void testNumericEndpointNeedsNoDnsAndSniIsPreserved() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        EndpointResolver resolver=new EndpointResolver(context,()->true,host->{calls.incrementAndGet();return new BootstrapDns.Answer(new byte[]{1,2,3,4},60);});
        assertEquals("127.0.0.1",resolver.resolve("127.0.0.1"));assertEquals(0,calls.get());
        JSONObject outbound=Hysteria2Config.outbound("hy2://secret@endpoint.example:443?sni=tls.example");
        resolver.resolveXray(outbound);
        assertEquals("1.2.3.4",outbound.getJSONObject("settings").getString("address"));
        assertEquals("tls.example",outbound.getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("serverName"));
        JSONObject vless=new JSONObject("{settings:{vnext:[{address:'endpoint.example',port:443}]},streamSettings:{tlsSettings:{}}}");
        resolver.resolveXray(vless);
        assertEquals("endpoint.example",vless.getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("serverName"));
    }
    public void testSavedIpIsFirstAndFailedRefreshCannotDeleteIt() throws Exception {
        new EndpointCache(context).put("endpoint.example","1.2.3.4",0,System.currentTimeMillis()-10000);
        AtomicInteger calls=new AtomicInteger();
        EndpointResolver resolver=new EndpointResolver(context,()->true,host->{calls.incrementAndGet();throw new IOException("DNS blocked");});
        assertEquals("1.2.3.4",resolver.resolve("endpoint.example"));assertEquals("Connection does not query DNS",0,calls.get());
        rejects(()->resolver.refresh("endpoint.example",true));assertEquals(1,calls.get());
        assertEquals("1.2.3.4",resolver.resolve("endpoint.example"));
    }
    public void testOrdinaryDnsCannotReplaceProtectedHint() throws Exception {
        long now=System.currentTimeMillis();
        new EndpointCache(context,true).put("endpoint.example","1.2.3.4",60,now);
        new EndpointCache(context,false).put("endpoint.example","5.6.7.8",60,now);
        assertEquals("1.2.3.4",new EndpointResolver(context,()->true,true).resolve("endpoint.example"));
        assertEquals("5.6.7.8",new EndpointResolver(context,()->true,false).resolve("endpoint.example"));
    }
    public void testRefreshOnlyResolvesTheVpnEndpoint() throws Exception {
        java.util.List<String> queried=new java.util.ArrayList<>();
        EndpointResolver resolver=new EndpointResolver(context,()->true,host->{queried.add(host);return new BootstrapDns.Answer(new byte[]{1,2,3,4},60);});
        Profile profile=new Profile("Fixture",Profile.VLESS,
                "{outbounds:[{protocol:'vless',settings:{vnext:[{address:'vpn.example',port:443}]}},"+
                "{protocol:'dns',settings:{address:'application-dns.example'}}]}");
        resolver.refreshProfile(profile,true);
        assertEquals(java.util.Collections.singletonList("vpn.example"),queried);
    }
    public void testNativeBootstrapConfigUsesNetworkDnsAndCleansUp() throws Exception {
        BootstrapNetworkDns config=new BootstrapNetworkDns(context,directory,"fixture");
        File file=new File(config.argument().substring("network:".length()));
        try {
            assertTrue(file.isFile());String text=Files.readString(file.toPath());
            assertFalse("Connected physical network supplies bootstrap DNS",text.isEmpty());
            assertFalse(text.contains("https://"));
            for(String server:text.trim().split("\\s+"))assertTrue(server.endsWith(":53"));
        } finally {config.close();}
        assertFalse("Private bootstrap file is removed with its transport",file.exists());
    }
}
