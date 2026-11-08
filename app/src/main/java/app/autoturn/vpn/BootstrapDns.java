package app.autoturn.vpn;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
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
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(response));
        if (in.readUnsignedShort() != (id & 0xffff)) throw new IOException("DNS transaction mismatch");
        int flags = in.readUnsignedShort();
        if ((flags & 0x8000) == 0 || (flags & 0x020f) != 0) throw new IOException("Invalid DNS response");
        int questions = in.readUnsignedShort(), answers = in.readUnsignedShort();
        in.readUnsignedShort(); in.readUnsignedShort();
        if (questions != 1 || answers > 256) throw new IOException("Invalid DNS record counts");
        skipName(in); in.readUnsignedShort(); in.readUnsignedShort();
        for (int i = 0; i < answers; i++) {
            skipName(in);
            int type = in.readUnsignedShort(), recordClass = in.readUnsignedShort();
            in.readInt(); int length = in.readUnsignedShort();
            if (type == 1 && recordClass == 1 && length == 4) {
                byte[] address = new byte[4]; in.readFully(address); return address;
            }
            if (length > in.available()) throw new IOException("Truncated DNS record");
            in.skipBytes(length);
        }
        throw new IOException("VPN endpoint has no IPv4 address");
    }

    private static void skipName(DataInputStream in) throws IOException {
        int size = 0;
        while (true) {
            int length = in.readUnsignedByte();
            if (length == 0) return;
            if ((length & 0xc0) == 0xc0) { in.readUnsignedByte(); return; }
            if (length > 63 || (size += length + 1) > 254 || length > in.available())
                throw new IOException("Invalid DNS name");
            in.skipBytes(length);
        }
    }
}
