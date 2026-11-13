package app.autoturn.vpn;

/** Explicit one-release device check. Uses the real public API and DownloadManager. */
public final class PublicationUpdateTest extends android.test.InstrumentationTestCase {
    private void report(String text){android.os.Bundle b=new android.os.Bundle();b.putString("stream",text+"\n");getInstrumentation().sendStatus(0,b);}
    public void testActualCheckButtonReturnsCurrent() throws Exception {
        android.content.Context context=getInstrumentation().getTargetContext();
        MainActivity main=(MainActivity)getInstrumentation().startActivitySync(new android.content.Intent(context,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            getInstrumentation().runOnMainSync(()->{
                main.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                try {java.lang.reflect.Method nav=MainActivity.class.getDeclaredMethod("navigateTo",int.class);nav.setAccessible(true);nav.invoke(main,2);}
                catch(Exception e){throw new RuntimeException(e);}
            });Thread.sleep(500);getInstrumentation().waitForIdleSync();
            java.util.ArrayList<android.view.View> matches=new java.util.ArrayList<>();
            main.getWindow().getDecorView().findViewsWithText(matches,"Проверить обновления",android.view.View.FIND_VIEWS_WITH_TEXT);
            assertFalse(matches.isEmpty());android.view.View button=matches.get(0);
            getInstrumentation().runOnMainSync(button::performClick);
            long until=android.os.SystemClock.elapsedRealtime()+45000;
            while(AppUpdater.get(context).state().phase==AppUpdater.Phase.CHECKING && android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(200);
            Thread.sleep(400);getInstrumentation().waitForIdleSync();
            assertEquals(AppUpdater.Phase.CURRENT,AppUpdater.get(context).state().phase);
            java.lang.reflect.Field ui=MainActivity.class.getDeclaredField("updateUi");ui.setAccessible(true);Object helper=ui.get(main);
            java.lang.reflect.Field dialogField=UpdateUi.class.getDeclaredField("dialog");dialogField.setAccessible(true);
            android.app.AlertDialog dialog=(android.app.AlertDialog)dialogField.get(helper);assertTrue(dialog.isShowing());
            android.widget.TextView message=dialog.findViewById(android.R.id.message);assertNotNull(message);
            assertTrue(message.getText().toString().contains("актуальная"));report("ACTUAL BUTTON: "+message.getText());
        }finally{getInstrumentation().runOnMainSync(main::finish);}
    }
    public void testDiagnoseUpdateRead() throws Exception {
        java.net.HttpURLConnection c=(java.net.HttpURLConnection)new java.net.URL(UpdateRelease.API).openConnection();
        c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("User-Agent","AutoTurn-update-diagnostic");
        try {
            int code=c.getResponseCode();
            report("API HTTP="+code+"; remaining="+c.getHeaderField("X-RateLimit-Remaining")+
                    "; limit="+c.getHeaderField("X-RateLimit-Limit")+"; reset="+c.getHeaderField("X-RateLimit-Reset")+
                    "; retry-after="+c.getHeaderField("Retry-After"));
            assertEquals("Public GitHub API response",200,code);
        } catch(Exception e){report("API failure: "+e.getClass().getSimpleName()+": "+e.getMessage());throw e;}
        finally{c.disconnect();}
        org.json.JSONObject release=AppUpdater.readJson(UpdateRelease.API,2*1024*1024);
        report("Release tag="+release.optString("tag_name"));
        try {
            org.json.JSONObject metadata=AppUpdater.readJson(UpdateRelease.metadataUrl(release),64*1024);
            UpdateRelease parsed=UpdateRelease.parse(release,metadata);report("Metadata accepted: "+parsed.version+" / "+parsed.code);
        }catch(Exception e){report("Metadata failure: "+e.getClass().getSimpleName()+": "+e.getMessage());throw e;}
    }
    public void testInstalled150IsCurrent() throws Exception {
        android.content.Context context=getInstrumentation().getTargetContext();
        android.content.pm.PackageInfo info=context.getPackageManager().getPackageInfo(context.getPackageName(),0);
        assertEquals(29L,info.getLongVersionCode());assertEquals("1.5.0",info.versionName);
        AppUpdater updater=AppUpdater.get(context);updater.check(true);
        long until=android.os.SystemClock.elapsedRealtime()+60000;
        while(updater.state().phase==AppUpdater.Phase.CHECKING && android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(200);
        assertEquals("No repeated update offered after installation",AppUpdater.Phase.CURRENT,updater.state().phase);
        report("PASS installed 1.5.0 / 29 is reported current by the public updater API");
    }
    public void testPublished150DownloadAndSignature() throws Exception {
        android.content.Context context=getInstrumentation().getTargetContext();
        java.io.File settings=new java.io.File(context.getFilesDir(),"settings.enc");
        byte[] before=java.nio.file.Files.readAllBytes(settings.toPath());
        assertTrue("Run before installing 1.5.0",context.getPackageManager().getPackageInfo(context.getPackageName(),0).getLongVersionCode()<29);
        AppUpdater updater=AppUpdater.get(context);updater.check(true);
        long until=android.os.SystemClock.elapsedRealtime()+60000;
        while(updater.state().phase==AppUpdater.Phase.CHECKING && android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(200);
        assertEquals("Real public release offered: "+updater.state().message,AppUpdater.Phase.AVAILABLE,updater.state().phase);
        UpdateRelease release=updater.availableRelease();assertNotNull(release);
        assertEquals(29L,release.code);assertEquals("1.5.0",release.version);
        report("PASS public API offers 1.5.0 / 29 to the installed older app");
        updater.download(release);until=android.os.SystemClock.elapsedRealtime()+240000;int last=-1;
        while(android.os.SystemClock.elapsedRealtime()<until){
            AppUpdater.State state=updater.state();
            if(state.phase==AppUpdater.Phase.READY)break;
            if(state.phase==AppUpdater.Phase.ERROR)fail(state.message);
            if(state.progress>=0 && state.progress/25!=last){last=state.progress/25;report("Download progress: "+state.progress+"%");}
            Thread.sleep(500);
        }
        assertEquals("Verified APK ready",AppUpdater.Phase.READY,updater.state().phase);
        java.io.File apk=UpdateApkProvider.apk(context);assertTrue(apk.isFile());
        AppUpdater.validate(context,apk,release);
        assertTrue("VPN settings not changed",java.util.Arrays.equals(before,java.nio.file.Files.readAllBytes(settings.toPath())));
        report("PASS real DownloadManager download, SHA256, package, version and original signature; settings unchanged");
    }
}
