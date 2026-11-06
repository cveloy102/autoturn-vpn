package app.autoturn.vpn;

/** Runs on the JDK, with no Android stubs, network, or user credentials. */
public final class QwdttConfigTest {
    private static final String BASE = "qwdtt://config?peer=vpn.example%3A56002" +
            "&hashes=abcdefgh_12345678&pass=fixture-only&name=Router+WDTT&workers=18&port=9000";
    private static int assertions;
    private static void check(boolean value) {
        assertions++;
        if (!value) throw new AssertionError("Assertion " + assertions);
    }
    private static void reject(String source) throws Exception {
        boolean rejected = false;
        try { QwdttConfig.parse(source); } catch (Exception expected) { rejected = true; }
        check(rejected);
    }
    public static void main(String[] args) throws Exception {
        QwdttConfig config = QwdttConfig.parse(BASE);
        check(config.name.equals("Router WDTT"));
        check(config.peer.equals("vpn.example:56002"));
        check(config.workers == 18 && config.port == 9000);
        check(config.password.equals("fixture-only"));
        check(QwdttConfig.parse(BASE.replace("%3A56002", "")).peer.equals("vpn.example:56000"));
        check(QwdttConfig.parse(BASE.replace("&workers=18&port=9000", "")).workers == 9);
        check(QwdttConfig.parse(BASE.replace("abcdefgh_12345678", "abcdefgh,ijklmnop")).hashes.equals("abcdefgh,ijklmnop"));
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
        System.out.println("QwdttConfig: " + assertions + " assertions passed");
    }
}
