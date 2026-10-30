package storage;

/** Small no-network test for the guarded Supabase pooler fallback URL. */
public final class ConnectionFallbackTest {
    public static void main(String[] args) {
        String source = "jdbc:postgresql://aws-0-ap-northeast-2.pooler.supabase.com:5432/postgres?sslmode=require&connectTimeout=8&socketTimeout=15&tcpKeepAlive=true";
        String expected = "jdbc:postgresql://aws-0-ap-northeast-2.pooler.supabase.com:6543/postgres?sslmode=require&connectTimeout=8&socketTimeout=15&tcpKeepAlive=true&prepareThreshold=0";
        String actual = PostgresRecordStorage.buildTransactionFallback(source);
        if (!expected.equals(actual)) throw new AssertionError("Unexpected transaction fallback URL: " + actual);
        if (PostgresRecordStorage.buildTransactionFallback("jdbc:postgresql://db.example.com:5432/postgres") != null)
            throw new AssertionError("Direct database URLs must not be auto-rewritten as shared pooler URLs.");
        if (PostgresRecordStorage.buildTransactionFallback("jdbc:postgresql://aws-0-ap-northeast-2.pooler.supabase.com:6543/postgres") != null)
            throw new AssertionError("A URL already using port 6543 must not be rewritten.");
        System.out.println("PASS: Supabase shared-pooler fallback URL and safeguards.");
    }
}
