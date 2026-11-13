package app.autoturn.vpn;

import android.content.Context;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs actual published 1.5.0 storage/updater classes against isolated encrypted fixtures. */
public final class UpdateMigrationTest extends InstrumentationTestCase {
    private Object get(Object object,String name) throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private void set(Object object,String name,Object value) throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    private Object call(Object object,String name,Class<?>[] types,Object... args) throws Exception {
        Method method=object.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(object,args);
    }
    private void sameJson(Object expected,Object actual,String path) throws Exception {
        if(expected instanceof JSONObject) {
            assertTrue(path,actual instanceof JSONObject);JSONObject a=(JSONObject)expected,b=(JSONObject)actual;
            java.util.Iterator<String> keys=a.keys();while(keys.hasNext()) {String key=keys.next();assertTrue(path+"."+key,b.has(key));sameJson(a.get(key),b.get(key),path+"."+key);}
        }else if(expected instanceof JSONArray) {
            assertTrue(path,actual instanceof JSONArray);JSONArray a=(JSONArray)expected,b=(JSONArray)actual;assertEquals(path,a.length(),b.length());
            for(int i=0;i<a.length();i++)sameJson(a.get(i),b.get(i),path+"["+i+"]");
        }else assertEquals(path,String.valueOf(expected),String.valueOf(actual));
    }
    @SuppressWarnings("unchecked")
    public void testPublished150ProfilesAndUpdaterUpgradeTo170() throws Exception {
        if(!"true".equals(((InstrumentationTestRunner)getInstrumentation()).getArguments().getString("legacyApkAudit")))return;
        Context target=getInstrumentation().getTargetContext();File apk=new File(target.getFilesDir(),"migration-1.5.0.apk");
        assertTrue("Published 1.5.0 APK staged",apk.isFile());assertTrue(apk.setReadOnly());
        android.content.pm.PackageInfo info=target.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),0);
        assertNotNull(info);assertEquals("1.5.0",info.versionName);assertEquals(29L,info.getLongVersionCode());
        DexClassLoader legacy=new DexClassLoader(apk.getAbsolutePath(),target.getCodeCacheDir().getAbsolutePath(),null,ClassLoader.getSystemClassLoader());
        assertEquals(29,legacy.loadClass("app.autoturn.vpn.BuildConfig").getField("VERSION_CODE").getInt(null));
        Class<?> legacyStore=legacy.loadClass("app.autoturn.vpn.SecureStore"),legacyProfile=legacy.loadClass("app.autoturn.vpn.Profile");
        assertSame("Use the published classes, not current parent classes",legacy,legacyStore.getClassLoader());
        File directory=new File(target.getCacheDir(),"migration-150-170");directory.mkdirs();
        for(File file:directory.listFiles())file.delete();
        Context fixture=new android.content.ContextWrapper(target) {
            @Override public Context getApplicationContext(){return this;}
            @Override public File getFilesDir(){return directory;}
        };
        try {
            Constructor<?> ctor=legacyStore.getDeclaredConstructor(Context.class);ctor.setAccessible(true);Object old=ctor.newInstance(fixture);
            Constructor<?> profileCtor=legacyProfile.getDeclaredConstructor(String.class,String.class,String.class);profileCtor.setAccessible(true);
            Object first=profileCtor.newInstance("Основной AWG","amneziawg","legacy-awg-fixture");
            Object second=profileCtor.newInstance("Резерв Hysteria","hysteria2","hy2://fixture@example.invalid:443?sni=tls.invalid");
            Object third=profileCtor.newInstance("Последний qWDTT","qwdtt","legacy-qwdtt-fixture");
            for(Object profile:new Object[]{first,second,third})call(old,"add",new Class<?>[]{legacyProfile},profile);
            call(old,"selectPrimary",new Class<?>[]{String.class},get(second,"id"));
            assertEquals(Boolean.TRUE,call(old,"chooseConnection",new Class<?>[]{String.class,String.class,String.class},get(second,"id"),get(second,"id"),get(first,"id")));
            ((Set<String>)get(old,"homeSsids")).add("Домашний Wi-Fi");((Set<String>)get(old,"homeSsids")).add("Office 5G");
            set(old,"theme","light");set(old,"autoReturnHours",24);set(old,"connectionGuard",true);
            Set<String> only=new LinkedHashSet<>(),excluded=new LinkedHashSet<>();only.add("app.autoturn.vpn.test");excluded.add("com.android.settings");
            call(old,"setAppPolicy",new Class<?>[]{String.class,Set.class,Set.class},"only",only,excluded);
            call(old,"setControlMode",new Class<?>[]{String.class},"off");
            call(old,"save",new Class<?>[]{String[].class},(Object)new String[0]);
            JSONObject before=(JSONObject)call(old,"encodeSettings",new Class<?>[]{});
            SecureStore upgraded=new SecureStore(fixture);
            assertFalse("Protected mode is opt-in for 1.5.0 users",upgraded.protectedMode);
            for(Profile profile:upgraded.profiles)assertTrue("All 1.5.0 profiles remain allowed",profile.enabled);
            Method encode=SecureStore.class.getDeclaredMethod("encodeSettings");encode.setAccessible(true);
            sameJson(before,encode.invoke(upgraded),"settings");
            upgraded.save();sameJson(before,encode.invoke(new SecureStore(fixture)),"savedSettings");

            JSONObject release=new JSONObject().put("tag_name","v1.7.0").put("draft",false).put("prerelease",false)
                    .put("body","Изменения с 1.5.0").put("assets",new JSONArray()
                    .put(new JSONObject().put("name","update.json").put("browser_download_url","https://github.com/cveloy102/autoturn-vpn/releases/download/v1.7.0/update.json"))
                    .put(new JSONObject().put("name","AutoTurn-1.7.0.apk").put("size",new File(target.getApplicationInfo().sourceDir).length())
                    .put("browser_download_url","https://github.com/cveloy102/autoturn-vpn/releases/download/v1.7.0/AutoTurn-1.7.0.apk")));
            JSONObject metadata=new JSONObject().put("versionCode",31).put("versionName","1.7.0").put("apkName","AutoTurn-1.7.0.apk").put("sha256","a".repeat(64));
            Class<?> updater=legacy.loadClass("app.autoturn.vpn.UpdateRelease");Method parse=updater.getDeclaredMethod("parse",JSONObject.class,JSONObject.class);parse.setAccessible(true);
            Object accepted=parse.invoke(null,release,metadata);assertEquals(31L,((Number)get(accepted,"code")).longValue());
            android.os.Bundle out=new android.os.Bundle();out.putString("stream","PASS published 1.5.0 encrypted settings -> 1.7.0; profiles/order/modes/app lists preserved; legacy updater accepts 1.7.0\n");
            getInstrumentation().sendStatus(0,out);
        }finally{for(File file:directory.listFiles())file.delete();directory.delete();}
    }
}
