package app.autoturn.vpn;

import android.content.Context;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Updates saved addresses without issuing tunnel commands or editing profile configurations. */
final class EndpointUpdates implements AutoCloseable {
    private final Context context;
    private final boolean protectedMode;
    private volatile boolean closed;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{
        Thread thread=new Thread(r,"endpoint-updates");thread.setDaemon(true);return thread;
    });
    EndpointUpdates(Context context,boolean protectedMode) {
        this.context=context;this.protectedMode=protectedMode;
        worker.scheduleWithFixedDelay(this::refreshKnown,30,15*60,TimeUnit.SECONDS);
    }
    private boolean allowed(String id) {
        if(closed)return false;
        try {SecureStore store=new SecureStore(context);if(store.protectedMode!=protectedMode)return false;
            for(Profile p:store.profiles)if(p.id.equals(id))return p.enabled;
        }catch(Exception ignored){}
        return false;
    }
    private void refreshKnown() {
        try {SecureStore store=new SecureStore(context);if(closed || store.protectedMode!=protectedMode)return;
            for(Profile profile:store.profiles)if(profile.enabled)refresh(profile.id,false);
        }catch(Exception ignored){/* Keep the last saved addresses when offline. */}
    }
    private void refresh(String id,boolean force) {
        try {if(!allowed(id))return;
            for(Profile profile:new SecureStore(context).profiles)if(profile.id.equals(id)) {
                new EndpointResolver(context,()->allowed(id),protectedMode).refreshProfile(profile,force);return;
            }
        }catch(Exception ignored){/* Failed refresh never deletes a saved IP or resets a working tunnel. */}
    }
    void failed(String id) {
        if(closed)return;
        try {worker.execute(()->refresh(id,true));}catch(java.util.concurrent.RejectedExecutionException ignored){}
    }
    @Override public void close(){closed=true;worker.shutdownNow();}
}
