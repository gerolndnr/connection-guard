package bench.fixture;

/** Cooperative Java 21 fixture guard. Numeric subjects are metadata, not network targets. */
@SuppressWarnings("removal")
public final class LoopbackSecurityManager extends SecurityManager {
    private final java.io.FileOutputStream audit;
    private final java.nio.file.Path plugins;
    public LoopbackSecurityManager() throws java.io.IOException {
        audit = new java.io.FileOutputStream(System.getProperty("bench.audit.path"));
        plugins = java.nio.file.Path.of(System.getProperty("user.dir")).resolve("plugins").normalize();
    }
    @Override public void checkPermission(java.security.Permission permission) {
        // Some asynchronous tasks inherit an ACC which rejects relative FilePermission.
        // Explicitly allow read-only access inside the owned, freshly created fixture.
        if (permission instanceof java.io.FilePermission && permission.getActions().equals("read")
                && permission.getName().startsWith("plugins/")
                && plugins.resolve(permission.getName().substring(8)).normalize().startsWith(plugins)) return;
        try { super.checkPermission(permission); }
        catch (SecurityException denied) {
            // An already opened stream avoids Log4j/security-debug recursion.
            try { audit.write((permission.toString() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            catch (java.io.IOException ignored) { }
            throw denied;
        }
    }
    @Override public void checkConnect(String host, int port) {
        boolean loopback = host.equals("127.0.0.1") || host.equals("localhost")
                || host.equals("::1") || host.equals("[::1]") || host.equals("0:0:0:0:0:0:0:1");
        // Netty enumerates numeric interface addresses with port=-1. Do not trigger reverse DNS.
        if (port == -1) return; // DNS/metadata resolution; no transport connection.
        if (!loopback) {
            try { audit.write(("external transport denied: " + host + ":" + port + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            catch (java.io.IOException ignored) { }
            throw new SecurityException("Benchmark external transport denied");
        }
    }
    @Override public void checkConnect(String host, int port, Object context) { checkConnect(host, port); }
    @Override public void checkAccept(String host, int port) { checkConnect(host, port); }
}
