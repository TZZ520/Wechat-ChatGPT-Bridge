package com.zhaozhan.wxchatgptbridge;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class MediaWaitTest {
    private static final class FakeClock implements MediaWait.Clock {
        long time;
        public long now() { return time; }
        public void pause(long ms) { time += ms; }
    }

    @Test public void waitsForActualBytesAfterEarlyCallback() throws Exception {
        FakeClock clock = new FakeClock(); AtomicInteger attempts = new AtomicInteger();
        byte[] content = MediaWait.untilReady(() -> {
            attempts.incrementAndGet();
            return clock.now() >= 15_000 ? new byte[]{1} : null;
        }, 60_000, 1_000, clock);
        assertArrayEquals(new byte[]{1}, content);
        assertEquals(15_000, clock.time);
        assertEquals(16, attempts.get());
    }

    @Test public void stopsAtDeadlineWhenFileNeverArrives() throws Exception {
        FakeClock clock = new FakeClock(); AtomicInteger attempts = new AtomicInteger();
        Object content = MediaWait.untilReady(() -> { attempts.incrementAndGet(); return null; },
                3_500, 1_000, clock);
        assertNull(content); assertEquals(3_500, clock.time); assertEquals(5, attempts.get());
    }
}
