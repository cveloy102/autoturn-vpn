package app.autoturn.vpn;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypted endpoint hints, separate from the user's profiles and application DNS. */
final class EndpointCache {
    private static final Object LOCK=new Object();
    private static final byte[] AAD="autoturn-endpoint-cache-v1".getBytes(StandardCharsets.US_ASCII);
    private final File directory;
    private final String filename;

    static final class Entry {
        final String ip;
        final long savedAt, freshUntil;
        Entry(String ip,long savedAt,long freshUntil){this.ip=ip;this.savedAt=savedAt;this.freshUntil=freshUntil;}
        boolean usable(long now){return savedAt>0 && now>=savedAt;}
        boolean fresh(long now){return usable(now) && now<freshUntil;}
    }

    EndpointCache(Context context){this(context,true);}
    EndpointCache(Context context,boolean encryptedDns){directory=context.getFilesDir();filename=encryptedDns?"endpoint-cache.enc":"endpoint-cache-ordinary.enc";}

    Entry get(String host) {
        synchronized(LOCK) {
            try(FileChannel channel=FileChannel.open(new File(directory,filename+".lock").toPath(),
                    StandardOpenOption.CREATE,StandardOpenOption.WRITE);java.nio.channels.FileLock lock=channel.lock()) {
                JSONObject value=read().optJSONObject(host);if(value==null)return null;
                String ip=value.getString("ip");
                if(!(android.net.InetAddresses.parseNumericAddress(ip) instanceof java.net.Inet4Address))return null;
                return new Entry(ip,value.getLong("savedAt"),value.getLong("freshUntil"));
            }catch(Exception ignored){return null;}
        }
    }

    void put(String host,String ip,long ttlSeconds,long now) {
        synchronized(LOCK) {
            try(FileChannel channel=FileChannel.open(new File(directory,filename+".lock").toPath(),
                    StandardOpenOption.CREATE,StandardOpenOption.WRITE);java.nio.channels.FileLock lock=channel.lock()) {
                JSONObject entries=read();
                if(entries.length()>=64 && !entries.has(host)) {
                    String oldest=null;long time=Long.MAX_VALUE;
                    java.util.Iterator<String> iterator=entries.keys();
                    while(iterator.hasNext()) {String key=iterator.next();long saved=entries.getJSONObject(key).optLong("savedAt");
                        if(saved<time){oldest=key;time=saved;}}
                    if(oldest!=null)entries.remove(oldest);
                }
                entries.put(host,new JSONObject().put("ip",ip).put("savedAt",now)
                        .put("freshUntil",now+Math.max(0,Math.min(3600,ttlSeconds))*1000));
                write(entries);
            }catch(Exception ignored){/* Cache failure must not prevent an authenticated fresh lookup. */}
        }
    }

    private JSONObject read() {
        try(java.io.FileInputStream in=new AtomicFile(new File(directory,filename)).openRead()) {
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] block=new byte[4096];int count;
            while((count=in.read(block))!=-1){if(bytes.size()+count>65536)return new JSONObject();bytes.write(block,0,count);}
            JSONObject envelope=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,SecureStore.key(),new GCMParameterSpec(128,
                    Base64.decode(envelope.getString("iv"),Base64.NO_WRAP)));
            cipher.updateAAD(AAD);
            return new JSONObject(new String(cipher.doFinal(Base64.decode(envelope.getString("data"),Base64.NO_WRAP)),StandardCharsets.UTF_8));
        }catch(Exception ignored){return new JSONObject();}
    }

    private void write(JSONObject entries) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,SecureStore.key());cipher.updateAAD(AAD);
        JSONObject envelope=new JSONObject().put("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
                .put("data",Base64.encodeToString(cipher.doFinal(entries.toString().getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP));
        AtomicFile file=new AtomicFile(new File(directory,filename));FileOutputStream out=null;
        try {out=file.startWrite();out.write(envelope.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
        catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
    }
}
