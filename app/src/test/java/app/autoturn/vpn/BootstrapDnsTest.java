package app.autoturn.vpn;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;

public class BootstrapDnsTest {
    private static byte[] answer(int id, int flags, int type, byte[] payload) throws Exception {
        byte[] request = BootstrapDns.query("endpoint.example", id);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(data);
        out.writeShort(id); out.writeShort(flags); out.writeShort(1);
        out.writeShort(1); out.writeShort(0); out.writeShort(0);
        out.write(request, 12, request.length - 12);
        out.writeShort(0xc00c); out.writeShort(type); out.writeShort(1); out.writeInt(60);
        out.writeShort(payload.length); out.write(payload);
        return data.toByteArray();
    }

    private interface Action { void run() throws Exception; }

    private static void rejects(String label, Action action) throws Exception {
        try { action.run(); fail("Accepted " + label); }
        catch (IOException | IllegalArgumentException expected) { /* rejected */ }
    }

    private static final byte[] ADDRESS = {1, 2, 3, 4};

    @Test public void acceptsCompressedAnswerAndKeepsTtl() throws Exception {
        byte[] valid = answer(42, 0x8180, 1, ADDRESS);
        assertArrayEquals(ADDRESS, BootstrapDns.ipv4(valid, 42));
        assertEquals(60, BootstrapDns.answer(valid, 42, "endpoint.example").ttlSeconds);
        assertEquals(BootstrapDns.query("endpoint.example", 42).length, BootstrapDns.query("endpoint.example.", 42).length);
    }

    @Test public void rejectsHostileResponses() throws Exception {
        byte[] valid = answer(42, 0x8180, 1, ADDRESS);
        rejects("other question", () -> BootstrapDns.answer(valid, 42, "another.example"));
        rejects("wrong transaction", () -> BootstrapDns.ipv4(valid, 43));
        rejects("query as response", () -> BootstrapDns.ipv4(answer(42, 0x0100, 1, ADDRESS), 42));
        rejects("truncated flag", () -> BootstrapDns.ipv4(answer(42, 0x8380, 1, ADDRESS), 42));
        rejects("NXDOMAIN", () -> BootstrapDns.ipv4(answer(42, 0x8183, 1, ADDRESS), 42));
        rejects("SERVFAIL", () -> BootstrapDns.ipv4(answer(42, 0x8182, 1, ADDRESS), 42));
        rejects("IPv6 only", () -> BootstrapDns.ipv4(answer(42, 0x8180, 28, new byte[16]), 42));
        rejects("bad A size", () -> BootstrapDns.ipv4(answer(42, 0x8180, 1, new byte[3]), 42));
        byte[] loop = valid.clone(); loop[12] = (byte) 0xc0; loop[13] = 12;
        rejects("pointer loop", () -> BootstrapDns.ipv4(loop, 42));
        byte[] reserved = valid.clone(); reserved[12] = 0x40;
        rejects("reserved label", () -> BootstrapDns.ipv4(reserved, 42));
    }

    @Test public void everyTruncationIsRejected() throws Exception {
        byte[] valid = answer(42, 0x8180, 1, ADDRESS);
        for (int length = 0; length < valid.length; length++) {
            final byte[] shortened = Arrays.copyOf(valid, length);
            rejects("truncation at " + length, () -> BootstrapDns.ipv4(shortened, 42));
        }
    }

    @Test public void rejectsInvalidNames() throws Exception {
        rejects("empty label", () -> BootstrapDns.query("a..example", 1));
        rejects("long label", () -> BootstrapDns.query("x".repeat(64) + ".example", 1));
        rejects("empty", () -> BootstrapDns.query("", 1));
    }
}
