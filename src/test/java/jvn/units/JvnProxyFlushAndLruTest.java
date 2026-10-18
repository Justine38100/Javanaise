package jvn.units;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.rmi.Naming;
import java.rmi.registry.LocateRegistry;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import irc.model.Sentence;
import irc.model.SentenceItf;
import jvn.core.JvnCoordImpl;
import jvn.core.JvnObject;
import jvn.core.JvnServerImpl;
import jvn.proxy.JvnProxy;

class JvnProxyFlushAndLruTest {

    private static final int CACHE_CAPACITY = 10;

    @BeforeAll
    static void setupCoordinator() throws Exception {
        try {
            LocateRegistry.createRegistry(1099);
        } catch (Exception ignored) {
            // Le registre peut déjà être démarré par un autre test.
        }
        JvnCoordImpl coord = JvnCoordImpl.getCoordInstance();
        Naming.rebind("rmi://localhost/Coordinator", coord);
    }

    @BeforeEach
    void resetServerState() throws Exception {
        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        if (server != null) {
            getInternalCache(server).clear();
        }
    }

    /**
     * Vérifie que le cache LRU évince l'objet le moins récemment utilisé :
     * dix objets sont créés, le premier est relu, puis la création du onzième
     * doit évincer le deuxième et conserver le premier ainsi que le nouveau.
     */
    @Test
    void proxyUsageTriggersLruEvictionTransparently() throws Exception {
        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        SentenceItf[] sentences = new SentenceItf[CACHE_CAPACITY];

        for (int i = 0; i < CACHE_CAPACITY; i++) {
            String name = "SENTENCE_" + i + "_" + System.nanoTime();
            sentences[i] = (SentenceItf) JvnProxy.newInstance(new Sentence(), name);
            sentences[i].write("Message initial " + i);
        }

        Map<Integer, JvnObject> cache = getInternalCache(server);
        assertEquals(CACHE_CAPACITY, cache.size());
        assertEquals("Message initial 0", sentences[0].read());

        String name10 = "SENTENCE_10_" + System.nanoTime();
        SentenceItf sentence10 = (SentenceItf) JvnProxy.newInstance(new Sentence(), name10);
        sentence10.write("Message 10");

        assertEquals(CACHE_CAPACITY, cache.size());
        boolean found0 = false;
        boolean found1 = false;
        boolean found10 = false;
        for (JvnObject jo : cache.values()) {
            Sentence sentence = (Sentence) jo.jvnGetObjectState();
            found0 |= "Message initial 0".equals(sentence.read());
            found1 |= "Message initial 1".equals(sentence.read());
            found10 |= "Message 10".equals(sentence.read());
        }

        assertTrue(found0, "l'objet récemment relu doit rester en cache");
        assertFalse(found1, "l'objet le moins récemment utilisé doit être évincé");
        assertTrue(found10, "le nouvel objet doit être présent en cache");
    }

    /**
     * Vérifie qu'un flush global vide le cache local et conserve l'état métier :
     * après éviction puis lookup, la dernière valeur écrite doit être relue.
     */
    @Test
    void proxyFlushAllCleansCacheAndPreservesState() throws Exception {
        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        String name = "PERSISTENT_" + System.nanoTime();
        SentenceItf sentence = (SentenceItf) JvnProxy.newInstance(new Sentence(), name);
        sentence.write("Valeur persistante");

        Map<Integer, JvnObject> cache = getInternalCache(server);
        assertFalse(cache.isEmpty());

        server.jvnFlushAll();
        assertEquals(0, cache.size());

        // Le proxy déjà remis à l'application reste utilisable après le flush.
        sentence.write("Écriture via l'ancien proxy après flush");
        assertEquals(1, cache.size(), "l'objet réutilisé doit être réinscrit dans le cache local");

        SentenceItf reloaded = (SentenceItf) JvnProxy.newInstance(new Sentence(), name);
        assertEquals("Écriture via l'ancien proxy après flush", reloaded.read());
    }

    /**
     * Vérifie le cas de saturation sans candidat évictable : si les dix objets
     * sont encore verrouillés, créer un onzième objet doit échouer explicitement.
     */
    @Test
    void cacheFullWithAllObjectsLockedThrowsException() throws Exception {
        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        for (int i = 0; i < CACHE_CAPACITY; i++) {
            server.jvnCreateObject("locked-data-" + i);
        }

        Map<Integer, JvnObject> cache = getInternalCache(server);
        assertEquals(CACHE_CAPACITY, cache.size());
        org.junit.jupiter.api.Assertions.assertThrows(jvn.core.JvnException.class,
                () -> server.jvnCreateObject("overflow-attempt"));
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, JvnObject> getInternalCache(JvnServerImpl server) throws Exception {
        Field field = JvnServerImpl.class.getDeclaredField("jvnObjectsMap");
        field.setAccessible(true);
        return (Map<Integer, JvnObject>) field.get(server);
    }
}
