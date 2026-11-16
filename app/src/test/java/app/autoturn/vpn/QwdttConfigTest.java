package app.autoturn.vpn;

import static org.junit.Assert.*;
import org.junit.Test;

public class QwdttConfigTest {
    private static final String BASE = "qwdtt://config?peer=vpn.example%3A56002" +
            "&hashes=abcdefgh_12345678&pass=fixture-only&name=Router+WDTT&workers=18&port=9000";

    private static void reject(String source) {
        try { QwdttConfig.parse(source); fail("Accepted " + source); }
        catch (Exception expected) { /* rejected */ }
    }

    @Test public void parsesFullLink() throws Exception {
        QwdttConfig config = QwdttConfig.parse(BASE);
        assertEquals("Router WDTT", config.name);
        assertEquals("vpn.example:56002", config.peer);
        assertEquals(18, config.workers);
        assertEquals(9000, config.port);
        assertEquals("fixture-only", config.password);
    }

    @Test public void appliesDefaults() throws Exception {
        assertEquals("vpn.example:56000", QwdttConfig.parse(BASE.replace("%3A56002", "")).peer);
        assertEquals(9, QwdttConfig.parse(BASE.replace("&workers=18&port=9000", "")).workers);
        assertEquals("abcdefgh,ijklmnop",
                QwdttConfig.parse(BASE.replace("abcdefgh_12345678", "abcdefgh,ijklmnop")).hashes);
    }

    @Test public void rejectsMalformedLinks() {
        reject(BASE + "&pass=other");
        reject(BASE + "&%70ass=other");
        reject(BASE.replace("workers=18", "workers=0"));
        reject(BASE.replace("workers=18", "workers=109"));
        reject(BASE.replace("port=9000", "port=65536"));
        reject(BASE.replace("port=9000", "port=80"));
        reject(BASE.replace("%3A56002", "%3A65536"));
        reject(BASE.replace("%3A56002", "%3A0"));
        reject(BASE.replace("vpn.example", "user%40vpn.example"));
        reject(BASE.replace("vpn.example", "vpn.example%2Fpath"));
        reject(BASE.replace("fixture-only", "%0Ainjection"));
        reject(BASE.replace("fixture-only", "%00injection"));
        reject(BASE.replace("Router+WDTT", "Router%0DWDTT"));
        reject(BASE.replace("abcdefgh_12345678", "abcdefgh,abcdefgh"));
        reject(BASE.replace("abcdefgh_12345678", "abcdefgh,ijklmnop,qrstuvwx,yzabcdef,12345678"));
        reject(BASE.replace("abcdefgh_12345678", ""));
        reject(BASE.replace("config?", "evil?"));
        reject(BASE.replace("config?", "config/path?"));
        reject(BASE + "#ignored");
        reject(BASE.replace("fixture-only", "%not-encoded"));
        reject(null);
    }
}
