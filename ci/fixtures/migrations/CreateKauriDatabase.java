import java.nio.file.*;
import java.sql.*;

/** Owned synthetic H2 fixture; does not load or execute KauriVPN. */
public final class CreateKauriDatabase {
    public static void main(String[] args) throws Exception {
        Path base=Paths.get(args[0]).toAbsolutePath();Files.createDirectories(base.getParent());
        try(Connection db=DriverManager.getConnection("jdbc:h2:file:"+base,"root","fixture-only");Statement sql=db.createStatement()) {
            sql.execute("CREATE TABLE \"whitelisted\" (\"uuid\" VARCHAR(36))");
            sql.execute("INSERT INTO \"whitelisted\" VALUES ('01234567-89ab-cdef-0123-456789abcdef')");
            sql.execute("CREATE TABLE \"whitelisted-ranges\" (\"cidr_string\" VARCHAR(50))");
            sql.execute("INSERT INTO \"whitelisted-ranges\" VALUES ('192.0.2.0/24')");
            sql.execute("CREATE TABLE \"responses\" (\"ip\" VARCHAR(50))");
            sql.execute("INSERT INTO \"responses\" VALUES ('198.51.100.99')");
        }
    }
}
