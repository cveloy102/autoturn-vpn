package app.autoturn.vpn;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** Local JVM checks: privacy intent across control states and hostile/truncated DNS responses. */
public final class ProtectedModeTest {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++; if (!value) throw new AssertionError(label);
    }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action, String label) throws Exception {
        try { action.run(); throw new AssertionError("Accepted " + label); }
        catch (IOException | IllegalArgumentException expected) { checks++; }
    }
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
    public static void main(String[] args) throws Exception {
        for (String mode : new String[] {"auto", "on", "off"}) for (boolean home : new boolean[] {false, true}) {
            check(!ProtectionRules.needsBarrier(false, mode, home), "Disabled mode never owns a barrier");
            check(!ProtectionRules.shouldConnect(false, mode, home, true), "Disabled mode uses legacy engine");
            boolean barrier = !home || "on".equals(mode);
            check(ProtectionRules.needsBarrier(true, mode, home) == barrier, "Barrier intent " + mode + home);
            check(!ProtectionRules.shouldConnect(true, mode, home, false), "No profiles must not open traffic");
            check(ProtectionRules.shouldConnect(true, mode, home, true) == (barrier && !"off".equals(mode)),
                    "Only an explicit off pauses protected reconnects");
        }
        check(ProtectionRules.needsBarrier(true, "off", false), "Manual OFF outside home keeps protection");
        check(!ProtectionRules.needsBarrier(true, "auto", true), "Home AUTO intentionally releases protection");
        check(ProtectionRules.needsBarrier(true, "on", true), "Manual ON protects even at home");
        check(ProtectionRules.needsBarrier(true, "auto", false), "Unknown SSID is treated as outside home");

        byte[] address = {1, 2, 3, 4};
        byte[] valid = answer(42, 0x8180, 1, address);
        check(Arrays.equals(address, BootstrapDns.ipv4(valid, 42)), "Compressed A answer accepted");
        check(BootstrapDns.answer(valid,42,"endpoint.example").ttlSeconds==60,"DNS TTL retained");
        rejects(()->BootstrapDns.answer(valid,42,"another.example"),"mismatched DNS question");
        byte[] pointerLoop=valid.clone();pointerLoop[12]=(byte)0xc0;pointerLoop[13]=12;
        rejects(()->BootstrapDns.ipv4(pointerLoop,42),"DNS compression pointer loop");
        check(BootstrapDns.query("endpoint.example.", 42).length == BootstrapDns.query("endpoint.example", 42).length,
                "Absolute endpoint names normalized");
        rejects(() -> BootstrapDns.ipv4(valid, 43), "wrong transaction");
        rejects(() -> BootstrapDns.ipv4(answer(42, 0x0100, 1, address), 42), "query used as response");
        rejects(() -> BootstrapDns.ipv4(answer(42, 0x8380, 1, address), 42), "truncated response");
        rejects(() -> BootstrapDns.ipv4(answer(42, 0x8183, 1, address), 42), "NXDOMAIN");
        rejects(() -> BootstrapDns.ipv4(answer(42, 0x8182, 1, address), 42), "SERVFAIL");
        rejects(() -> BootstrapDns.ipv4(answer(42, 0x8180, 28, new byte[16]), 42), "IPv6-only endpoint");
        rejects(() -> BootstrapDns.ipv4(answer(42, 0x8180, 1, new byte[3]), 42), "invalid A record size");
        for (int length = 0; length < valid.length; length++) {
            final byte[] shortened = Arrays.copyOf(valid, length);
            rejects(() -> BootstrapDns.ipv4(shortened, 42), "truncation at " + length);
        }
        rejects(() -> BootstrapDns.query("a..example", 1), "empty DNS label");
        rejects(() -> BootstrapDns.query("x".repeat(64) + ".example", 1), "oversized label");
        rejects(() -> BootstrapDns.query("", 1), "empty endpoint");
        byte[] badName = valid.clone(); badName[12] = 0x40;
        rejects(() -> BootstrapDns.ipv4(badName, 42), "reserved DNS label form");
        System.out.println("Protected-mode checks passed: " + checks);
    }
}
