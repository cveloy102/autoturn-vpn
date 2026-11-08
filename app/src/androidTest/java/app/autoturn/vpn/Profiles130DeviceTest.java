package app.autoturn.vpn;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;

/** Integration checks for multiple home networks and the persisted live failover order. */
public final class Profiles130DeviceTest extends InstrumentationTestCase {
    private Context context;private byte[] original;private SecureStore baseline;
    private void report(String text){android.os.Bundle b=new android.os.Bundle();b.putString("stream",text+"\n");getInstrumentation().sendStatus(0,b);}
    private String shell(String command)throws Exception{
        try(android.os.ParcelFileDescriptor fd=getInstrumentation().getUiAutomation().executeShellCommand(command);
            java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){
            return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();
        }
    }
    private void refresh(){context.startForegroundService(new Intent(context,AutomationService.class).setAction(AutomationService.ACTION_REFRESH));}
    private void mode(String m)throws Exception{new SecureStore(context).setControlMode(m);refresh();}
    private void waitState(String desired,long timeout)throws Exception{
        long until=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<until){
            if(desired.equals(StatusStore.state(context)))return;
            if(QwdttState.pending(context,new SecureStore(context).activeId))fail("CAPTCHA requested; left for user");
            Thread.sleep(200);
        }
        fail("Expected "+desired+"; actual="+StatusStore.state(context)+" "+StatusStore.detail(context));
    }
    @Override protected void setUp()throws Exception{
        super.setUp();context=getInstrumentation().getTargetContext();
        original=Files.readAllBytes(new File(context.getFilesDir(),"settings.enc").toPath());baseline=new SecureStore(context);
        mode(SecureStore.MANUAL_OFF);waitState(StatusStore.OFF,15000);
    }
    private java.util.List<Profile> qwdtts(){java.util.List<Profile> out=new java.util.ArrayList<>();for(Profile p:baseline.profiles)if(Profile.QWDTT.equals(p.type))out.add(p);return out;}
    private void probe()throws Exception{
        shell("run-as app.autoturn.vpn.test rm -f files/routing-probe.json");
        shell("am start -n app.autoturn.vpn.test/app.autoturn.vpn.RoutingProbeActivity --ez wake true --ez udp true");
        long until=SystemClock.elapsedRealtime()+25000;JSONObject result=null;
        while(SystemClock.elapsedRealtime()<until){String raw=shell("run-as app.autoturn.vpn.test cat files/routing-probe.json");
            if(raw.startsWith("{")){result=new JSONObject(raw);break;}Thread.sleep(250);}
        report("Independent app: "+result);assertNotNull(result);assertTrue(result.optBoolean("vpn"));
        assertEquals(200,result.optInt("http"));assertTrue("UDP response",result.optBoolean("udp"));
    }
    private void allApps(SecureStore s){s.appMode=AppPolicy.ALL;s.selectedPackages.clear();s.connectionGuard=true;s.autoReturnHours=0;}
    public void testMultipleHomeNetworksAndManualOverrides()throws Exception{
        android.net.wifi.WifiInfo info=context.getSystemService(android.net.wifi.WifiManager.class).getConnectionInfo();
        String home=info.getSSID();assertNotNull(home);assertFalse(android.net.wifi.WifiManager.UNKNOWN_SSID.equals(home));
        if(home.startsWith("\"")&&home.endsWith("\""))home=home.substring(1,home.length()-1);
        Profile q=qwdtts().get(0);SecureStore s=new SecureStore(context);
        s.profiles.clear();s.profiles.add(q);s.primaryId=q.id;s.activeId=q.id;allApps(s);
        s.homeSsids.clear();s.homeSsids.add("QA different home");s.homeSsids.add(home);s.save();
        mode(SecureStore.MANUAL_AUTO);waitState(StatusStore.OFF,10000);
        assertTrue(StatusStore.detail(context).startsWith("Домашний Wi"));
        report("PASS actual home recognized as SECOND entry in home list");
        mode(SecureStore.MANUAL_ON);waitState(StatusStore.ON,180000);probe();
        report("PASS manual ON overrides any home match");
        mode(SecureStore.MANUAL_AUTO);waitState(StatusStore.OFF,10000);
        new SecureStore(context).removeHomeSsid(home);refresh();waitState(StatusStore.ON,180000);probe();
        report("PASS removing current home starts VPN automatically");
        new SecureStore(context).addHomeSsid(home);refresh();waitState(StatusStore.OFF,10000);
        report("PASS adding current home stops VPN automatically");
        mode(SecureStore.MANUAL_OFF);new SecureStore(context).removeHomeSsid(home);refresh();Thread.sleep(1200);
        assertEquals(StatusStore.OFF,StatusStore.state(context));report("PASS manual OFF overrides away network");
    }
    public void testLiveFailoverFollowsBothReorderedGroups()throws Exception{
        Profile awg=null;for(Profile p:baseline.profiles)if(Profile.AMNEZIA.equals(p.type)){awg=p;break;}assertNotNull(awg);
        String dead=awg.config.replaceAll("(?im)^\\s*Endpoint\\s*=.*$","Endpoint = 127.0.0.1:9");assertFalse(dead.equals(awg.config));
        Profile main=new Profile("QA primary unavailable",Profile.AMNEZIA,dead),a=new Profile("QA ordinary A",Profile.AMNEZIA,dead),
                b=new Profile("QA ordinary B",Profile.AMNEZIA,dead);
        java.util.List<Profile> qs=qwdtts();assertTrue(qs.size()>=2);Profile c=qs.get(0),d=qs.get(1);
        SecureStore s=new SecureStore(context);s.profiles.clear();
        for(Profile p:new Profile[]{main,a,b,c,d})s.profiles.add(p);s.primaryId=main.id;s.activeId=main.id;allApps(s);s.save();
        s.reorderProfiles(false,java.util.Arrays.asList(b.id,a.id));s.reorderProfiles(true,java.util.Arrays.asList(d.id,c.id));
        java.util.List<String> visited=new java.util.ArrayList<>();mode(SecureStore.MANUAL_ON);
        long until=SystemClock.elapsedRealtime()+240000;
        while(SystemClock.elapsedRealtime()<until){
            SecureStore now=new SecureStore(context);
            if(visited.isEmpty()||!visited.get(visited.size()-1).equals(now.activeId))visited.add(now.activeId);
            if(d.id.equals(now.activeId)&&StatusStore.ON.equals(StatusStore.state(context)))break;
            Thread.sleep(100);
        }
        assertEquals(java.util.Arrays.asList(main.id,b.id,a.id,d.id),visited);
        assertEquals(StatusStore.ON,StatusStore.state(context));assertEquals(main.id,new SecureStore(context).primaryId);probe();
        report("PASS live queue: primary -> ordinary B -> ordinary A -> last D; HTTPS + UDP; primary preserved");
        mode(SecureStore.MANUAL_OFF);waitState(StatusStore.OFF,10000);mode(SecureStore.MANUAL_ON);Thread.sleep(500);
        assertEquals(main.id,new SecureStore(context).activeId);report("PASS next session starts with primary again");
    }
    @Override protected void tearDown()throws Exception{
        try{
            mode(SecureStore.MANUAL_OFF);waitState(StatusStore.OFF,15000);
            SecureStore stopped=new SecureStore(context);stopped.automation=false;stopped.manualMode=SecureStore.MANUAL_AUTO;stopped.save();refresh();Thread.sleep(1200);
            Files.write(new File(context.getFilesDir(),"settings.enc").toPath(),original);refresh();Thread.sleep(1000);
            assertTrue(java.util.Arrays.equals(original,Files.readAllBytes(new File(context.getFilesDir(),"settings.enc").toPath())));
            report("RESTORED original user settings byte-for-byte");
        }finally{super.tearDown();}
    }
}
