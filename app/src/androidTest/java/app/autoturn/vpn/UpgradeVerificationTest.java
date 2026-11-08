package app.autoturn.vpn;

/** Finalizes the additive settings migration without changing any user preference. */
public final class UpgradeVerificationTest extends android.test.InstrumentationTestCase {
    public void testPersistMigrationPreservesAllUserSettings() throws Exception {
        android.content.Context context=getInstrumentation().getTargetContext();
        java.lang.reflect.Method encode=SecureStore.class.getDeclaredMethod("encodeSettings");encode.setAccessible(true);
        SecureStore before=new SecureStore(context);
        String expected=encode.invoke(before).toString();
        java.io.File baselineFile=new java.io.File(context.getFilesDir(),"upgrade-baseline.enc");
        if(baselineFile.exists()) {
            java.io.File dir=new java.io.File(context.getCacheDir(),"upgrade-verification");dir.mkdirs();
            java.io.File settings=new java.io.File(dir,"settings.enc");
            java.nio.file.Files.copy(baselineFile.toPath(),settings.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            android.content.Context isolated=new android.content.ContextWrapper(context) {
                @Override public android.content.Context getApplicationContext(){return this;}
                @Override public java.io.File getFilesDir(){return dir;}
            };
            try {assertEquals("Exact pre-upgrade user preferences and profiles",encode.invoke(new SecureStore(isolated)).toString(),expected);}
            finally{settings.delete();dir.delete();}
        }
        before.save();
        SecureStore after=new SecureStore(context);
        assertEquals("All profiles, credentials, ranks, modes and preferences preserved",expected,encode.invoke(after).toString());
        assertFalse("Existing home migrated",after.homeSsids.isEmpty());
        context.getSharedPreferences("release_notes",android.content.Context.MODE_PRIVATE).edit().putInt("shown_version",27).commit();
        context.startForegroundService(new android.content.Intent(context,AutomationService.class).setAction(AutomationService.ACTION_REFRESH));
        android.os.Bundle result=new android.os.Bundle();
        result.putString("stream","PASS migration persisted; all user settings semantically identical; profiles="+
                after.profiles.size()+"; home networks="+after.homeSsids.size()+"\n");getInstrumentation().sendStatus(0,result);
        result.putString("stream","FINAL preferences: automation="+after.automation+"; mode="+after.manualMode+
                "; routing="+after.appMode+"; guard="+after.connectionGuard+"\n");getInstrumentation().sendStatus(0,result);
    }
}
