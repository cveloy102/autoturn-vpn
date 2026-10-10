package app.autoturn.vpn;

import android.app.Activity;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import java.lang.reflect.Method;
import java.nio.file.Files;

/** Exercises real import handlers and confirmation dialogs with a synthetic profile. */
public final class ProfileUiTest extends InstrumentationTestCase {
    private MainActivity main;
    private void call(String method, Class<?>[] types, Object... values) throws Exception {
        Method action = MainActivity.class.getDeclaredMethod(method, types); action.setAccessible(true);
        getInstrumentation().runOnMainSync(() -> {
            try { action.invoke(main, values); } catch (Exception e) { throw new RuntimeException(e); }
        });
        Thread.sleep(350); getInstrumentation().waitForIdleSync();
    }
    private Button button(View view, String text) {
        if (view instanceof Button && (text.contentEquals(((Button)view).getText()) || text.contentEquals(
                view.getContentDescription() == null ? "" : view.getContentDescription()))) return (Button)view;
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) {
            Button found=button(((ViewGroup)view).getChildAt(i),text); if(found!=null)return found;
        }
        return null;
    }
    private void clickDialog(String text) throws Exception {
        Thread.sleep(400);
        AccessibilityNodeInfo root = root();
        assertNotNull(root);
        java.util.List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(text);
        assertFalse("Dialog action exists", found.isEmpty());
        AccessibilityNodeInfo action=null;
        for(AccessibilityNodeInfo node:found)
            if(node.isClickable() && text.contentEquals(node.getText()==null?"":node.getText())) { action=node; break; }
        assertNotNull("Clickable exact dialog action",action);
        assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        Thread.sleep(400); getInstrumentation().waitForIdleSync();
    }
    private AccessibilityNodeInfo editable(AccessibilityNodeInfo root) {
        if(root==null)return null;
        if(root.isEditable())return root;
        for(int i=0;i<root.getChildCount();i++) {
            AccessibilityNodeInfo child=root.getChild(i); if(child==null)continue;
            AccessibilityNodeInfo found=editable(child); if(found!=null)return found;
        }
        return null;
    }
    private AccessibilityNodeInfo root() throws Exception {
        for(int i=0;i<20;i++) {
            AccessibilityNodeInfo value=getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if(value!=null)return value;
            Thread.sleep(150);
        }
        return null;
    }
    private void clickMain(String description) throws Exception {
        Button found=button(main.getWindow().getDecorView(),description); assertNotNull(found);
        getInstrumentation().runOnMainSync(found::performClick); Thread.sleep(400);
    }
    private View profileLabel(View view,String title) {
        if(view instanceof android.widget.TextView && title.contentEquals(((android.widget.TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            View found=profileLabel(((ViewGroup)view).getChildAt(i),title); if(found!=null)return found;
        }
        return null;
    }

    public void testImportsRenameAndDeleteConfirmation() throws Exception {
        android.content.Context context=getInstrumentation().getTargetContext();
        java.io.File file=new java.io.File(context.getFilesDir(),"settings.enc");
        byte[] original=Files.readAllBytes(file.toPath());
        SecureStore baseline=new SecureStore(context);
        android.content.ClipboardManager clipboard=context.getSystemService(android.content.ClipboardManager.class);
        android.content.ClipData previousClipboard=null;
        boolean clipboardChanged=false;
        try {
            SecureStore fixture=new SecureStore(context);
            fixture.manualMode=SecureStore.MANUAL_OFF; fixture.automation=false; fixture.save();
            context.startForegroundService(new Intent(context,AutomationService.class).setAction(AutomationService.ACTION_REFRESH));
            Thread.sleep(1200);
            main=(MainActivity)getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Thread.sleep(500);
            java.lang.reflect.Field notesField=MainActivity.class.getDeclaredField("releaseNotesDialog"); notesField.setAccessible(true);
            android.app.AlertDialog notes=(android.app.AlertDialog)notesField.get(main);
            if(notes!=null)getInstrumentation().runOnMainSync(() -> button(notes.getWindow().getDecorView(),"Понятно").performClick());
            call("navigateTo",new Class<?>[]{int.class},1);
            call("onActivityResult",new Class<?>[]{int.class,int.class,Intent.class},10,Activity.RESULT_OK,
                    new Intent().setData(android.net.Uri.parse("content://app.autoturn.vpn.test.fixture/Fixture.conf")));
            SecureStore saved=new SecureStore(context);
            assertEquals(baseline.profiles.size()+1,saved.profiles.size());
            Profile imported=saved.profiles.get(saved.profiles.size()-1);
            assertEquals(Profile.AMNEZIA,imported.type);
            View label=profileLabel(main.getWindow().getDecorView(),imported.name); assertNotNull(label);
            Button select=button((View)label.getParent(),"Выбрать"); assertNotNull(select);
            getInstrumentation().runOnMainSync(select::performClick); Thread.sleep(400);
            assertEquals(imported.id,new SecureStore(context).activeId);
            clickMain("Переименовать профиль "+imported.name);
            AccessibilityNodeInfo input=editable(root()); assertNotNull(input);
            android.os.Bundle text=new android.os.Bundle(); text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"QA renamed");
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text));
            clickDialog("Сохранить");
            saved=new SecureStore(context);
            assertEquals("QA renamed",saved.profiles.get(saved.profiles.size()-1).name);
            clickMain("Удалить профиль QA renamed"); clickDialog("Отмена");
            assertEquals(baseline.profiles.size()+1,new SecureStore(context).profiles.size());
            clickMain("Удалить профиль QA renamed"); clickDialog("Удалить");
            assertEquals(baseline.profiles.size(),new SecureStore(context).profiles.size());
            byte[] beforeInvalid=Files.readAllBytes(file.toPath());
            call("importText",new Class<?>[]{String.class,String.class},"broken","not a profile");
            clickDialog("Понятно");
            assertTrue(java.util.Arrays.equals(beforeInvalid,Files.readAllBytes(file.toPath())));
            previousClipboard=clipboard.getPrimaryClip(); clipboardChanged=true;
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("QA","vless://12345678-1234-1234-1234-123456789abc@example.invalid:443?security=reality&type=tcp#ClipboardFixture"));
            call("importClipboard",new Class<?>[]{});
            saved=new SecureStore(context);
            assertEquals(baseline.profiles.size()+1,saved.profiles.size());
            assertEquals("ClipboardFixture",saved.profiles.get(saved.profiles.size()-1).name);
        } finally {
            if(clipboardChanged) {
                if(previousClipboard!=null)clipboard.setPrimaryClip(previousClipboard); else clipboard.clearPrimaryClip();
            }
            if(main!=null)getInstrumentation().runOnMainSync(main::finish);
            SecureStore stopped=new SecureStore(context);
            stopped.automation=false; stopped.manualMode=SecureStore.MANUAL_AUTO; stopped.save();
            context.startForegroundService(new Intent(context,AutomationService.class).setAction(AutomationService.ACTION_REFRESH));
            Thread.sleep(1100);
            Files.write(file.toPath(),original);
            if(baseline.automation || SecureStore.MANUAL_ON.equals(baseline.manualMode))
                context.startForegroundService(new Intent(context,AutomationService.class).setAction(AutomationService.ACTION_REFRESH));
        }
    }
}
