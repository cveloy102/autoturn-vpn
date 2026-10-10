package app.autoturn.vpn;

/** Public synthetic fixture only, never user credentials. Installed only with the test APK. */
public final class ProfileFixtureProvider extends android.content.ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(android.net.Uri uri) { return "text/plain"; }
    @Override public android.os.ParcelFileDescriptor openFile(android.net.Uri uri, String mode) throws java.io.FileNotFoundException {
        String text = "[Interface]\nPrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\nAddress = 10.0.0.2/32\n" +
                "[Peer]\nPublicKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\nAllowedIPs = 0.0.0.0/0\nEndpoint = 192.0.2.1:51820\n";
        try {
            android.os.ParcelFileDescriptor[] pipe = android.os.ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (java.io.OutputStream out = new android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception ignored) { }
            }).start();
            return pipe[0];
        } catch (Exception e) { throw new java.io.FileNotFoundException(); }
    }
    @Override public android.database.Cursor query(android.net.Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public android.net.Uri insert(android.net.Uri u, android.content.ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(android.net.Uri u, android.content.ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(android.net.Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
}
