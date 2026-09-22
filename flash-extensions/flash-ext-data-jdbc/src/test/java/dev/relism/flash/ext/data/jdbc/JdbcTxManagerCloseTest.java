package dev.relism.flash.ext.data.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import dev.relism.flash.ext.data.DataExtension;
import dev.relism.flash.extension.FlashApp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcTxManagerCloseTest {

    /** A pool outlives its app unless someone closes it; the data extension does, once requests have drained. */
    @Test
    void stoppingTheAppClosesThePool() {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(TestDataSource.URL);
        FlashApp.create(0).install(new DataExtension(new JdbcTxManager(pool))).start().stop().join();
        assertTrue(pool.isClosed());
    }
}
