package app.autoturn.vpn;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;

/** Real touch/drag and menu tests using synthetic profiles; user settings restored in tearDown. */
public final class CompactProfilesUiTest extends InstrumentationTestCase {
    private Context context;
    private MainActivity main;
    private byte[] original;
    private SecureStore baseline;
    private int notesVersion;
    private Profile primary,a,b,c,d;

    private void report(String message) {
        android.os.Bundle out=new android.os.Bundle();out.putString("stream",message+"\n");getInstrumentation().sendStatus(0,out);
    }
    private void ui(Runnable action) {getInstrumentation().runOnMainSync(action);}
    private void settle() throws Exception {Thread.sleep(400);getInstrumentation().waitForIdleSync();}
    private Object field(String name) throws Exception {
        Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(main);
    }
    private void call(String name,Class<?>[] types,Object... args) throws Exception {
        Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);
        ui(()->{try{m.invoke(main,args);}catch(Exception e){throw new RuntimeException(e);}});settle();
    }
    private View find(View root,String text) {
        if(root instanceof TextView && text.contentEquals(((TextView)root).getText()))return root;
        if(text.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++) {
            View v=find(((ViewGroup)root).getChildAt(i),text);if(v!=null)return v;
        }
        return null;
    }
    private View find(String text) {return find(main.getWindow().getDecorView(),text);}
    private void click(String description) throws Exception {
        View v=find(description);assertNotNull(description,v);ui(v::performClick);settle();
    }
    private void dialogClick(String text) throws Exception {
        AccessibilityNodeInfo root=getInstrumentation().getUiAutomation().getRootInActiveWindow();assertNotNull(root);
        AccessibilityNodeInfo action=null;
        for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText(text))
            if(n.isClickable() && text.contentEquals(n.getText()==null?"":n.getText())){action=n;break;}
        assertNotNull("Dialog action "+text,action);assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK));settle();
    }
    private AccessibilityNodeInfo editable(AccessibilityNodeInfo root) {
        if(root==null)return null;if(root.isEditable())return root;
        for(int i=0;i<root.getChildCount();i++){AccessibilityNodeInfo n=editable(root.getChild(i));if(n!=null)return n;}
        return null;
    }
    private void typeDialog(String value) {
        AccessibilityNodeInfo input=editable(getInstrumentation().getUiAutomation().getRootInActiveWindow());assertNotNull(input);
        android.os.Bundle args=new android.os.Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args));
    }
    @Override protected void setUp() throws Exception {
        super.setUp();context=getInstrumentation().getTargetContext();
        try(android.os.ParcelFileDescriptor fd=getInstrumentation().getUiAutomation().executeShellCommand("input keyevent 224")) {
            try(java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){in.readAllBytes();}
        }
        original=Files.readAllBytes(new File(context.getFilesDir(),"settings.enc").toPath());baseline=new SecureStore(context);
        notesVersion=context.getSharedPreferences("release_notes",Context.MODE_PRIVATE).getInt("shown_version",0);
        SecureStore s=new SecureStore(context);s.setControlMode(SecureStore.MANUAL_OFF);refresh();Thread.sleep(1000);
        s=new SecureStore(context);s.profiles.clear();
        primary=new Profile("Main profile",Profile.AMNEZIA,"synthetic");
        a=new Profile("Reserve A",Profile.AMNEZIA,"synthetic");b=new Profile("Reserve B",Profile.VLESS,"synthetic");
        c=new Profile("Last C",Profile.QWDTT,"synthetic");d=new Profile("Last D",Profile.QWDTT,"synthetic");
        for(Profile p:new Profile[]{primary,a,b,c,d})s.profiles.add(p);
        s.primaryId=primary.id;s.activeId=primary.id;s.theme=SecureStore.THEME_DARK;
        s.homeSsids.clear();s.homeSsids.add("QA Home");s.homeSsids.add("QA Office");s.save();
        main=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ui(()->{main.setShowWhenLocked(true);main.setTurnScreenOn(true);
            main.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);});settle();
        AlertDialog notes=(AlertDialog)field("releaseNotesDialog");
        if(notes!=null){View button=find(notes.getWindow().getDecorView(),"Понятно");assertNotNull(button);ui(button::performClick);settle();}
        call("navigateTo",new Class<?>[]{int.class},1);
    }
    private void refresh(){context.startForegroundService(new Intent(context,AutomationService.class).setAction(AutomationService.ACTION_REFRESH));}
    private java.util.List<String> order() throws Exception {
        java.util.List<String> ids=new java.util.ArrayList<>();for(Profile p:new SecureStore(context).orderedProfiles())ids.add(p.id);return ids;
    }
    private void touch(long down,int action,float x,float y) {
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{assertTrue(getInstrumentation().getUiAutomation().injectInputEvent(event,true));}finally{event.recycle();}
    }
    private void drag(String from,String to) throws Exception {
        awaitRendered();
        View handle=find("Переместить профиль "+from),target=find("Переместить профиль "+to);
        assertNotNull(handle);assertNotNull(target);
        ScrollView scroll=(ScrollView)field("currentScroll");
        View group=(View)handle.getParent().getParent();
        ui(()->scroll.scrollTo(0,Math.max(0,group.getTop()-TerminalUi.dp(context,180))));settle();
        int[] start=new int[2],end=new int[2];handle.getLocationOnScreen(start);target.getLocationOnScreen(end);
        float x=start[0]+handle.getWidth()/2f,y=start[1]+handle.getHeight()/2f;
        float destY=end[1]+target.getHeight()/2f+(end[1]>start[1]?12:-12);
        long down=SystemClock.uptimeMillis();touch(down,MotionEvent.ACTION_DOWN,x,y);Thread.sleep(250);
        for(int i=1;i<=12;i++){touch(down,MotionEvent.ACTION_MOVE,x,y+(destY-y)*i/12f);Thread.sleep(35);}
        touch(down,MotionEvent.ACTION_UP,x,destY);settle();awaitRendered();
    }
    private void awaitRendered() throws Exception {
        long until=SystemClock.elapsedRealtime()+4000;
        while(SystemClock.elapsedRealtime()<until) {
            java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean();
            ui(()->{try{
                Method key=MainActivity.class.getDeclaredMethod("contentKey");key.setAccessible(true);
                ready.set(!((Boolean)field("profileDragging")) && key.invoke(main).equals(field("renderedKey")));
            }catch(Exception e){throw new RuntimeException(e);}});
            if(ready.get()){Thread.sleep(300);getInstrumentation().waitForIdleSync();return;}
            Thread.sleep(60);
        }
        fail("UI did not settle after drag");
    }
    private void screenshot(String label) throws Exception {
        android.graphics.Bitmap bitmap=getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new File(context.getFilesDir(),"ui-1.3.0-"+label+".png"))) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));
        }finally{bitmap.recycle();}
    }
    public void testNativeDragBothGroupsAndMenus() throws Exception {
        assertNotNull(find("01 / ОСНОВНОЙ"));assertNotNull(find("02 / ОБЫЧНЫЕ РЕЗЕРВЫ"));assertNotNull(find("03 / ПОСЛЕДНИЕ РЕЗЕРВЫ"));
        assertNull("No inline rename button",find("Имя"));assertNull("No inline delete button",find("Удалить"));
        View row=(View)find("Reserve A").getParent().getParent();
        assertTrue("Compact row",row.getHeight()<=TerminalUi.dp(context,86));
        screenshot("profiles-dark");
        drag(a.name,b.name);
        assertEquals(java.util.Arrays.asList(primary.id,b.id,a.id,c.id,d.id),order());
        report("PASS real touch drag of ordinary reserves persisted");
        drag(c.name,d.name);
        assertEquals(java.util.Arrays.asList(primary.id,b.id,a.id,d.id,c.id),order());
        report("PASS real touch drag of last reserves persisted");
        drag(a.name,c.name);
        assertEquals("Cross-group drop is cancelled",java.util.Arrays.asList(primary.id,b.id,a.id,d.id,c.id),order());
        click("Действия профиля "+b.name);dialogClick("Ниже в очереди");
        assertEquals(java.util.Arrays.asList(primary.id,a.id,b.id,d.id,c.id),order());
        click("Действия профиля "+b.name);dialogClick("Выше в очереди");
        assertEquals(java.util.Arrays.asList(primary.id,b.id,a.id,d.id,c.id),order());
        report("PASS cancelled cross-group drag and menu-based accessible ranking");
        click("Действия профиля "+d.name);screenshot("profile-menu-dark");dialogClick("В обычные резервы");
        assertEquals(java.util.Arrays.asList(primary.id,b.id,a.id,d.id,c.id),order());
        assertFalse(new SecureStore(context).orderedProfiles().get(3).lastResort);
        click("Действия профиля "+c.name);dialogClick("Сделать основным");
        assertEquals(java.util.Arrays.asList(c.id,primary.id,b.id,a.id,d.id),order());
        click("Действия профиля "+a.name);dialogClick("Переименовать");typeDialog("Renamed reserve");dialogClick("Сохранить");
        assertNotNull(find("Renamed reserve"));
        click("Действия профиля Renamed reserve");dialogClick("Удалить");dialogClick("Отмена");
        assertEquals(5,new SecureStore(context).profiles.size());
        click("Действия профиля Renamed reserve");dialogClick("Удалить");dialogClick("Удалить");
        assertEquals(4,new SecureStore(context).profiles.size());
        SecureStore s=new SecureStore(context);s.theme=SecureStore.THEME_LIGHT;s.save();
        call("onResume",new Class<?>[]{});screenshot("profiles-light");
        click("Действия профиля "+b.name);screenshot("profile-menu-light");dialogClick("Закрыть");
        report("PASS role menus, primary demotion, rename, delete confirmation, both themes");
    }
    public void testHomeWifiListDialogsAndDuplicate() throws Exception {
        call("navigateTo",new Class<?>[]{int.class},2);assertNotNull(find("QA Home"));assertNotNull(find("QA Office"));
        screenshot("wifi-dark");click("Добавить по имени →");typeDialog("QA Studio");dialogClick("Добавить");
        assertEquals(3,new SecureStore(context).homeSsids.size());assertNotNull(find("QA Studio"));
        click("Добавить по имени →");typeDialog("QA Studio");dialogClick("Добавить");
        assertEquals("Duplicate not added",3,new SecureStore(context).homeSsids.size());
        click("Удалить домашнюю сеть QA Office");dialogClick("Отмена");assertEquals(3,new SecureStore(context).homeSsids.size());
        click("Удалить домашнюю сеть QA Office");dialogClick("Удалить");assertEquals(2,new SecureStore(context).homeSsids.size());
        click("Добавить по имени →");dialogClick("Добавить");
        assertNotNull("Invalid input remains open",editable(getInstrumentation().getUiAutomation().getRootInActiveWindow()));dialogClick("Отмена");
        SecureStore s=new SecureStore(context);s.theme=SecureStore.THEME_LIGHT;s.save();call("onResume",new Class<?>[]{});screenshot("wifi-light");
        assertTrue(new SecureStore(context).isHomeSsid("QA Studio"));report("PASS multiple Wi-Fi add, duplicate, remove confirmation, validation, both themes");
        click("Удалить домашнюю сеть QA Home");dialogClick("Удалить");
        click("Удалить домашнюю сеть QA Studio");dialogClick("Удалить");
        assertTrue(new SecureStore(context).homeSsids.isEmpty());assertNotNull(find("Пока нет домашних сетей."));
        click("+ Добавить текущую сеть");assertEquals(1,new SecureStore(context).homeSsids.size());
        click("+ Добавить текущую сеть");assertEquals(1,new SecureStore(context).homeSsids.size());
        report("PASS empty home list and adding current Wi-Fi without duplicates");
    }
    @Override protected void tearDown() throws Exception {
        try {
            if(main!=null)ui(()->{main.setShowWhenLocked(false);main.finish();});
            SecureStore s=new SecureStore(context);s.automation=false;s.manualMode=SecureStore.MANUAL_AUTO;s.save();refresh();Thread.sleep(1200);
            Files.write(new File(context.getFilesDir(),"settings.enc").toPath(),original);
            context.getSharedPreferences("release_notes",Context.MODE_PRIVATE).edit().putInt("shown_version",notesVersion).commit();
            refresh();Thread.sleep(1000);
            assertTrue(java.util.Arrays.equals(original,Files.readAllBytes(new File(context.getFilesDir(),"settings.enc").toPath())));
            report("RESTORED original user settings byte-for-byte");
        }finally{super.tearDown();}
    }
}
