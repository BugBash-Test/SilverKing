package com.ms.silverking.cloud.dht.daemon.storage.serverside;

import com.ms.silverking.cloud.dht.common.DHTKey;
import com.ms.silverking.cloud.dht.common.SimpleKey;
import com.ms.silverking.cloud.dht.daemon.storage.DummyTimeSource;
import org.junit.Test;

import java.util.Queue;

import static org.junit.Assert.assertEquals;

public class LRUStateImplTest {
    @Test
    public void testLRUOrder() {
        DummyTimeSource timeSource;
        Queue<LRUKeyedInfo> lruList;

        timeSource = new DummyTimeSource();
        LRUStateImpl impl = new LRUStateImpl(timeSource);
        DHTKey key0 = SimpleKey.randomKey();
        DHTKey key1 = SimpleKey.randomKey();
        DHTKey key2 = SimpleKey.randomKey();
        impl.markPut(key0, 1);
        timeSource.setTime(1L);
        lruList = impl.getLRUList();
        assertEquals(1, lruList.size());
        assertEquals(key0, lruList.remove().getKey());
        assertEquals(0, lruList.size());
        impl.markPut(key1, 1);
        timeSource.setTime(2L);
        lruList = impl.getLRUList();
        assertEquals(2, lruList.size());
        assertEquals(key1, lruList.remove().getKey());
        assertEquals(1, lruList.size());
        assertEquals(key0, lruList.remove().getKey());
        assertEquals(0, lruList.size());
        impl.markPut(key2, 1);
        timeSource.setTime(3L);
        lruList = impl.getLRUList();
        assertEquals(3, lruList.size());
        assertEquals(key2, lruList.remove().getKey());
        assertEquals(2, lruList.size());
        assertEquals(key1, lruList.remove().getKey());
        assertEquals(1, lruList.size());
        assertEquals(key0, lruList.remove().getKey());
        assertEquals(0, lruList.size());
        impl.markRead(key0);
        timeSource.setTime(4L);
        lruList = impl.getLRUList();
        assertEquals(3, lruList.size());
        assertEquals(key0, lruList.remove().getKey());
        assertEquals(2, lruList.size());
        assertEquals(key2, lruList.remove().getKey());
        assertEquals(1, lruList.size());
        assertEquals(key1, lruList.remove().getKey());
        assertEquals(0, lruList.size());
    }
}
