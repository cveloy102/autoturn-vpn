package app.autoturn.vpn;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.IDN;
import java.nio.charset.StandardCharsets;

/** Minimal DNS wire codec for resolving VPN endpoints over authenticated TLS, never app names. */
final class BootstrapDns {
    private BootstrapDns() { }

    static byte[] query(String hostname, int id) throws IOException {
        String ascii = IDN.toASCII(hostname, IDN.USE_STD3_ASCII_RULES);
        if (ascii.endsWith(".")) ascii = ascii.substring(0, ascii.length() - 1);
        if (ascii.isEmpty() || ascii.length() > 253) throw new IOException("Invalid endpoint name");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(id); out.writeShort(0x0100); out.writeShort(1);
        out.writeShort(0); out.writeShort(0); out.writeShort(0);
        for (String label : ascii.split("\\.", -1)) {
            byte[] encoded = label.getBytes(StandardCharsets.US_ASCII);
            if (encoded.length == 0 || encoded.length > 63) throw new IOException("Invalid endpoint label");
            out.writeByte(encoded.length); out.write(encoded);
        }
        out.writeByte(0); out.writeShort(1); out.writeShort(1);
        return bytes.toByteArray();
    }

    static byte[] ipv4(byte[] response, int id) throws IOException {
        return answer(response, id, null).address;
    }

    static final class Answer {
        final byte[] address;
        final long ttlSeconds;
        Answer(byte[] address, long ttlSeconds) { this.address=address; this.ttlSeconds=ttlSeconds; }
    }

    static Answer answer(byte[] response, int id, String expectedHost) throws IOException {
        if(response.length<12 || response.length>4096)throw new IOException("Invalid DNS size");
        java.nio.ByteBuffer in=java.nio.ByteBuffer.wrap(response);
        if ((in.getShort() & 65535) != (id & 0xffff)) throw new IOException("DNS transaction mismatch");
        int flags = in.getShort() & 65535;
        if ((flags & 0x8000) == 0 || (flags & 0x7a0f) != 0) throw new IOException("Invalid DNS response");
        int questions = in.getShort() & 65535, answers = in.getShort() & 65535;
        in.getShort(); in.getShort();
        if (questions != 1 || answers > 256) throw new IOException("Invalid DNS record counts");
        int[] position={12};String question=name(response,position);
        if(expectedHost!=null) {
            byte[] expected=query(expectedHost,id);int[] start={12};
            if(!question.equalsIgnoreCase(name(expected,start)))throw new IOException("DNS question mismatch");
        }
        try {
        in.position(position[0]);
        if(in.getShort()!=1 || in.getShort()!=1)throw new IOException("Unexpected DNS question type");
        java.util.Map<String,String> aliases=new java.util.HashMap<>();
        java.util.Map<String,Answer> addresses=new java.util.HashMap<>();
        long ttl=3600;
        for (int i = 0; i < answers; i++) {
            position[0]=in.position();String owner=name(response,position);in.position(position[0]);
            int type = in.getShort() & 65535, recordClass = in.getShort() & 65535;
            long recordTtl=in.getInt() & 0xffffffffL;int length=in.getShort() & 65535;
            if(length>in.remaining())throw new IOException("Truncated DNS record");
            if (type == 1 && recordClass == 1 && length == 4) {
                byte[] address = new byte[4];in.get(address);
                addresses.putIfAbsent(owner,new Answer(address,recordTtl));
            } else {
                if(type==5 && recordClass==1) {
                    int[] target={in.position()};String alias=name(response,target);
                    if(target[0]!=in.position()+length)throw new IOException("Invalid CNAME length");
                    aliases.put(owner,alias);ttl=Math.min(ttl,recordTtl);
                }
                in.position(in.position()+length);
            }
        }
        String current=question;
        for(int i=0;i<=answers;i++) {
            Answer found=addresses.get(current);
            if(found!=null)return new Answer(found.address,Math.min(ttl,found.ttlSeconds));
            current=aliases.get(current);if(current==null)break;
        }
        throw new IOException("VPN endpoint has no IPv4 address");
        }catch(java.nio.BufferUnderflowException|IllegalArgumentException e){throw new IOException("Truncated DNS response",e);}
    }

    private static String name(byte[] wire,int[] position) throws IOException {
        StringBuilder value=new StringBuilder();int cursor=position[0];boolean jumped=false;
        for(int hops=0;hops<256;hops++) {
            if(cursor>=wire.length)throw new IOException("Truncated DNS name");
            int length=wire[cursor++] & 255;
            if(length==0){if(!jumped)position[0]=cursor;return value.toString().toLowerCase(java.util.Locale.ROOT);}
            if((length & 0xc0)==0xc0) {
                if(cursor>=wire.length)throw new IOException("Truncated DNS pointer");
                if(!jumped)position[0]=cursor+1;
                cursor=((length & 63)<<8)|(wire[cursor] & 255);jumped=true;continue;
            }
            if(length>63 || cursor+length>wire.length || value.length()+length+1>254)
                throw new IOException("Invalid DNS name");
            if(value.length()>0)value.append('.');
            value.append(new String(wire,cursor,length,StandardCharsets.US_ASCII));cursor+=length;
        }
        throw new IOException("DNS pointer loop");
    }
}
