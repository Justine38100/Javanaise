package jvn.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serializable;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import jvn.annotations.JvnRead;
import jvn.core.JvnObjectImpl;

class JvnProxyConcurrencyTest {

    /*
     * Scénario : deux threads appellent simultanément une méthode @JvnRead du même proxy.
     * Résultat attendu : les appels métier du même objet sont sérialisés localement ;
     * le deuxième appel ne commence qu'après la fin du premier.
     */
    @Test
    void callsOnTheSameProxyAreSerialized() throws Exception {
        Probe probe = new Probe();
        JvnObjectImpl object = new JvnObjectImpl(31, probe, null, true);
        object.jvnUnLock();

        ProbeItf proxy = (ProbeItf) Proxy.newProxyInstance(
                ProbeItf.class.getClassLoader(),
                new Class<?>[] { ProbeItf.class },
                new JvnProxy(object, null));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> firstCall = executor.submit(proxy::read);
            assertTrue(probe.firstCallEntered.await(1, TimeUnit.SECONDS), "le premier appel métier doit entrer");

            Future<Integer> secondCall = executor.submit(proxy::read);
            Thread.sleep(100);
            assertEquals(1, probe.callsEntered.get(), "le deuxième appel doit attendre le premier");

            probe.releaseFirstCall.countDown();
            assertEquals(1, firstCall.get(2, TimeUnit.SECONDS));
            assertEquals(2, secondCall.get(2, TimeUnit.SECONDS));
            assertEquals(1, probe.maximumConcurrentCalls.get(), "les appels métier ne doivent pas se chevaucher");
        } finally {
            probe.releaseFirstCall.countDown();
            executor.shutdownNow();
        }
    }

    public interface ProbeItf {
        @JvnRead
        int read();
    }

    public static class Probe implements ProbeItf, Serializable {
        private static final long serialVersionUID = 1L;

        private final CountDownLatch firstCallEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstCall = new CountDownLatch(1);
        private final AtomicInteger callsEntered = new AtomicInteger();
        private final AtomicInteger activeCalls = new AtomicInteger();
        private final AtomicInteger maximumConcurrentCalls = new AtomicInteger();

        @Override
        public int read() {
            int callNumber = callsEntered.incrementAndGet();
            int active = activeCalls.incrementAndGet();
            maximumConcurrentCalls.accumulateAndGet(active, Math::max);

            try {
                if (callNumber == 1) {
                    firstCallEntered.countDown();
                    if (!releaseFirstCall.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("le test n'a pas libéré le premier appel à temps");
                    }
                }
                return callNumber;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("appel métier interrompu", e);
            } finally {
                activeCalls.decrementAndGet();
            }
        }
    }
}
