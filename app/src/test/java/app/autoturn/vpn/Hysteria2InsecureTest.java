package app.autoturn.vpn;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class Hysteria2InsecureTest {
    @Test public void insecureWithoutPinIsFlagged() {
        assertTrue(Hysteria2Config.skipsCertificateCheck("hy2://key@example.com:443?insecure=1#Home"));
        assertTrue(Hysteria2Config.skipsCertificateCheck("hysteria2://key@example.com:443/?sni=a.b&insecure=1"));
        assertTrue(Hysteria2Config.skipsCertificateCheck("  HY2://key@example.com?insecure=1  "));
    }

    @Test public void checkedOrPinnedLinksAreNotFlagged() {
        assertFalse(Hysteria2Config.skipsCertificateCheck("hy2://key@example.com:443?insecure=0"));
        assertFalse(Hysteria2Config.skipsCertificateCheck("hy2://key@example.com:443?sni=a.b"));
        assertFalse(Hysteria2Config.skipsCertificateCheck("hy2://key@example.com:443"));
        assertFalse(Hysteria2Config.skipsCertificateCheck("hy2://key@example.com:443?insecure=1&pinSHA256=AB:CD"));
        assertFalse(Hysteria2Config.skipsCertificateCheck("hy2://key@example.com:443#insecure=1"));
    }

    @Test public void otherProtocolsAreNotFlagged() {
        assertFalse(Hysteria2Config.skipsCertificateCheck("vless://id@example.com:443?insecure=1"));
        assertFalse(Hysteria2Config.skipsCertificateCheck(null));
    }
}
