package dev.relism.flash.ext.data.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.ext.data.DataModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcTxManagerCloseTest {

    /** A pool outlives its app unless someone closes it; the data extension does, once requests have drained. */
    @Test
    void stoppingTheAppClosesThePool() {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(TestDataSource.URL);
        Server.start(App.create().install(new DataModule(new JdbcTxManager(pool))), ServerConfig.builder().port(0).build()).stop().join();
        assertTrue(pool.isClosed());
    }
}
