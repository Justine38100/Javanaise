package jvn.units;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import jvn.core.JvnObjectImpl;

class JvnObjectImplConcurrencyTest {

    /*
     * Scénario : plusieurs threads d'un même client exécutent des sections d'écriture
     * sur le même objet Javanaise.
     * Résultat attendu : les sections locales ne se chevauchent pas et aucune mise à jour
     * n'est perdue, même si le verrou d'écriture est déjà en cache dans le processus.
     */
    @Test
    void localWriteScopesAreSerializedAcrossThreads() throws Exception {
        SharedCounter counter = new SharedCounter();
        JvnObjectImpl object = new JvnObjectImpl(21, counter, null, true);
        object.jvnUnLock();

        int threadCount = 6;
        int iterations = 30;
        AtomicInteger activeWriters = new AtomicInteger();
        AtomicInteger maximumConcurrentWriters = new AtomicInteger();
        AtomicInteger completedWrites = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> tasks = new ArrayList<Future<?>>();

        try {
            for (int thread = 0; thread < threadCount; thread++) {
                tasks.add(executor.submit(() -> {
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        object.jvnLockWrite();
                        int active = activeWriters.incrementAndGet();
                        maximumConcurrentWriters.accumulateAndGet(active, Math::max);
                        try {
                            counter.value++;
                            Thread.yield();
                            completedWrites.incrementAndGet();
                        } finally {
                            activeWriters.decrementAndGet();
                            object.jvnUnLock();
                        }
                    }
                    return null;
                }));
            }

            for (Future<?> task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(threadCount * iterations, completedWrites.get(), "toutes les sections d'écriture doivent finir");
        assertEquals(threadCount * iterations, counter.value, "aucune incrémentation partagée ne doit être perdue");
        assertEquals(1, maximumConcurrentWriters.get(), "un seul thread local doit entrer dans la section d'écriture à la fois");
    }

    /*
     * Scénario : un thread détient un verrou écriture pendant qu'un callback RMI
     * demande son invalidation.
     * Résultat attendu : le callback attend la libération normale du verrou, puis termine ;
     * il ne peut ni invalider l'objet pendant l'utilisation ni rester bloqué ensuite.
     */
    @Test
    void writerInvalidationWaitsUntilTheLocalScopeIsUnlocked() throws Exception {
        JvnObjectImpl object = new JvnObjectImpl(22, "state", null, true);
        object.jvnLockWrite();
        CountDownLatch invalidationStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> invalidation = executor.submit(() -> {
                invalidationStarted.countDown();
                object.jvnInvalidateWriter();
                return null;
            });

            assertTrue(invalidationStarted.await(1, TimeUnit.SECONDS), "le callback d'invalidation doit démarrer");
            Thread.sleep(100);
            assertFalse(invalidation.isDone(), "l'invalidation doit attendre que le thread libère son verrou écriture");

            object.jvnUnLock();
            invalidation.get(2, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static class SharedCounter implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private int value;
    }
}
