package com.mimecast.robin.storage.rocksdb;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Foundation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import javax.naming.ConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = "mailbox-store-factory", mode = ResourceAccessMode.READ_WRITE)
class RocksDbMailboxStoreManagerTest {

    @BeforeAll
    static void beforeAll() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");
    }

    @AfterEach
    void tearDown() throws IOException {
        RocksDbMailboxStoreManager.closeAll();
        Config.getServer().getStorage().getMap().remove("rocksdb");
    }

    @Test
    void getConfiguredStore_returnsCachedStoreUntilLifecycleReset() throws Exception {
        Path dbPath = Files.createTempDirectory("robin-rocksdb-store-manager-");

        Map<String, Object> rocksDb = new HashMap<>();
        rocksDb.put("enabled", true);
        rocksDb.put("path", dbPath.toString());
        rocksDb.put("inboxFolder", "Inbox");
        rocksDb.put("sentFolder", "Sent");
        Config.getServer().getStorage().getMap().put("rocksdb", rocksDb);

        MailboxStore first = RocksDbMailboxStoreManager.getConfiguredStore();
        MailboxStore second = RocksDbMailboxStoreManager.getConfiguredStore();
        assertSame(first, second);

        RocksDbMailboxStoreManager.closeAll();

        MailboxStore third = RocksDbMailboxStoreManager.getConfiguredStore();
        assertNotSame(first, third);
    }
}
