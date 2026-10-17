package jvn.units;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        } catch (Exception e) {
            // Le registre existe déjà
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

    /*
     * Scénario : utilisation transparente via JvnProxy.
     * On crée 10 proxys Sentence.
     * Chaque appel write() prend le verrou en écriture puis déverrouille automatiquement.
     * Les 10 objets sont donc en cache local (WC).
     * On lit le premier objet s[0] via read() : le proxy fait lockRead() puis unLock(),
     * ce qui le remet en tête de la pile LRU.
     * La création du 11e proxy doit évincer le plus ancien non accédé (s[1]).
     */
    @Test
    void proxyUsageTriggersLruEvictionTransparently() throws Exception {
        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        SentenceItf[] sentences = new SentenceItf[CACHE_CAPACITY];
        String[] names = new String[CACHE_CAPACITY];

        // Création transparente de 10 objets
        for (int i = 0; i < CACHE_CAPACITY; i++) {
            names[i] = "SENTENCE_" + i + "_" + System.nanoTime();
            sentences[i] = (SentenceItf) JvnProxy.newInstance(new Sentence(), names[i]);
            sentences[i].write("Message initial " + i); // Lock write + Unlock transparents
        }

        Map<Integer, JvnObject> cache = getInternalCache(server);
        assertEquals(CACHE_CAPACITY, cache.size(), "le cache doit être saturé à 10 objets");

        // Réaccès en lecture transparente sur le 1er objet : rafraîchit son ordre LRU
        String readVal = sentences[0].read(); // Lock read + Unlock transparents
        assertEquals("Message initial 0", readVal);

        // Création transparente du 11e objet : déclenche l'éviction LRU
        String name10 = "SENTENCE_10_" + System.nanoTime();
        SentenceItf sentence10 = (SentenceItf) JvnProxy.newInstance(new Sentence(), name10);
        sentence10.write("Message 10");

        assertEquals(CACHE_CAPACITY, cache.size(), "la taille du cache doit rester bornée à 10");

        // Vérification : s[0] et sentence10 sont toujours en cache
        // s[1] qui était le plus ancien candidat a été évincé
        boolean found0 = false;
        boolean found1 = false;
        boolean found10 = false;
        for (JvnObject jo : cache.values()) {
            Sentence s = (Sentence) jo.jvnGetObjectState();
            if ("Message initial 0".equals(s.read())) {
                found0 = true;
            }
            if ("Message initial 1".equals(s.read())) {
                found1 = true;
            }
            if ("Message 10".equals(s.read())) {
                found10 = true;
            }
        }

        assertTrue(found0, "l'objet 0 réaccédé récemment doit toujours être dans le cache");
        assertFalse(found1, "l'objet 1 aurait dû être évincé car c'était le plus ancien");
        assertTrue(found10, "le 11e objet créé doit être présent dans le cache");
    }

    /*
     * Scénario : flush global après utilisation transparente.
     * Après plusieurs lectures/écritures via proxy, jvnFlushAll() doit
     * vider le cache local et renvoyer les états modifiés au coordinateur.
     */
    @Test
    void proxyFlushAllCleansCacheAndPreservesState() throws Exception {
        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        String name = "PERSISTENT_" + System.nanoTime();

        // Écriture via proxy
        SentenceItf sentence = (SentenceItf) JvnProxy.newInstance(new Sentence(), name);
        sentence.write("Valeur persistante");

        Map<Integer, JvnObject> cache = getInternalCache(server);
        assertFalse(cache.isEmpty(), "le cache doit contenir l'objet après utilisation");

        // Flush explicite de tout le cache
        server.jvnFlushAll();
        assertEquals(0, cache.size(), "le cache doit être vide après jvnFlushAll()");

        // Relookup via proxy : doit recharger l'objet depuis le coordinateur avec sa valeur à jour
        SentenceItf reloadedSentence = (SentenceItf) JvnProxy.newInstance(new Sentence(), name);
        assertEquals("Valeur persistante", reloadedSentence.read(), "la valeur modifiée doit avoir été sauvegardée sur le coordinateur lors du flush");
    }

    /*
     * Scénario limite : le cache est saturé (10 objets) et tous les objets possèdent
     * un verrou actif non libéré.
     * Aucun objet ne peut être évincé : la création d'un 11e objet doit lever une JvnException.
     */
    @Test
    void cacheFullWithAllObjectsLockedThrowsException() throws Exception {
        JvnServerImpl server = JvnServerImpl.jvnGetServer();

        // Création directe sans libérer le verrou d'écriture initial (LockState.W)
        for (int i = 0; i < CACHE_CAPACITY; i++) {
            server.jvnCreateObject("locked-data-" + i);
        }

        Map<Integer, JvnObject> cache = getInternalCache(server);
        assertEquals(CACHE_CAPACITY, cache.size());

        // La tentative de création du 11e objet ne trouve aucun candidat libre
        org.junit.jupiter.api.Assertions.assertThrows(jvn.core.JvnException.class, () -> {
            server.jvnCreateObject("overflow-attempt");
        }, "Le serveur doit lever une JvnException si aucun objet n'est éligible au flush");
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, JvnObject> getInternalCache(JvnServerImpl server) throws Exception {
        Field field = JvnServerImpl.class.getDeclaredField("jvnObjectsMap");
        field.setAccessible(true);
        return (Map<Integer, JvnObject>) field.get(server);
    }
}