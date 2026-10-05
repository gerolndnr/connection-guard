package bench.fixture;

/** Offline contract: no external socket is ever created. */
@SuppressWarnings("removal")
public final class GuardContract {
    public static void main(String[] args) {
        SecurityManager guard = System.getSecurityManager();
        if (guard == null) throw new AssertionError("Guard absent");
        guard.checkConnect("127.0.0.1", 12345);
        guard.checkConnect("2001:218::", -1); // numeric metadata only
        for (String host : new String[]{"81.2.69.142", "proxycheck.io", "2001:218::"}) {
            try { guard.checkConnect(host, 443); throw new AssertionError("External target accepted"); }
            catch (SecurityException expected) { }
        }
        guard.checkPermission(new java.io.FilePermission("plugins/owned/test.netset", "read"));
        guard.checkPermission(new java.net.URLPermission("http://127.0.0.1:12345", "GET:Accept,User-Agent"));
        guard.checkPermission(new java.net.URLPermission("http://127.0.0.1:12345/fixture", "GET:Accept,User-Agent"));
        try {
            guard.checkPermission(new java.net.URLPermission("https://proxycheck.io/v2/example", "GET:"));
            throw new AssertionError("External URL accepted");
        } catch (SecurityException expected) { }
        try {
            guard.checkPermission(new java.io.FilePermission("plugins/../../outside-secret", "read"));
            throw new AssertionError("Relative traversal accepted");
        } catch (SecurityException expected) { }
        System.out.println("Guard contract passed");
    }
}
