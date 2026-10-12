package jvn.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.rmi.Naming;
import java.rmi.registry.LocateRegistry;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jvn.core.JvnCoordImpl;
import jvn.core.JvnObject;
import jvn.core.JvnServerImpl;
import jvn.proxy.JvnProxy;
import irc.model.Sentence;
import irc.model.SentenceItf;

class JvnIntegrationTest {

    @BeforeAll
    static void startRmiAndBindCoordinator() throws Exception {
        startRegistryIfNeeded();
        bindCoordinator();
    }

    /*
     * Scénario : on démarre le registre RMI et on publie le coordinateur.
     * Ensuite, un serveur JVN fait un lookup sur un nom inconnu.
     * Le lookup doit retourner null, puis le serveur crée l'objet, l'enregistre,
     * et un nouveau lookup sur le même nom doit retourner un objet existant avec le même id.
     */
    @Test
    void lookupUnknownThenCreateRegisterAndLookup() throws Exception {
        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        String name = "INTEGRATION_OBJECT_" + System.nanoTime();

        JvnObject unknown = server.jvnLookupObject(name);
        assertNull(unknown, "un lookup sur un objet inconnu doit retourner null");

        JvnObject created = server.jvnCreateObject("etat-initial");
        int createdId = created.jvnGetObjectId();
        created.jvnUnLock();
        server.jvnRegisterObject(name, created);

        JvnObject lookedUp = server.jvnLookupObject(name);
        assertNotNull(lookedUp, "un lookup après register doit retourner un objet");
        assertEquals(createdId, lookedUp.jvnGetObjectId(), "l'objet retrouvé doit garder le même id");
        assertEquals("etat-initial", lookedUp.jvnGetObjectState(), "l'objet retrouvé doit garder le même état");
    }

    /*
     * Scénario : on utilise le serveur local et le coordinateur pour prendre des verrous.
     * L'objet est créé, enregistré, puis on prend un verrou en écriture pour modifier son état,
     * et ensuite un verrou en lecture pour relire l'état modifié.
     */
    @Test
    void writeThenReadThroughRealServerAndObject() throws Exception {
        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        String name = "INTEGRATION_READ_WRITE_" + System.nanoTime();

        JvnObject object = server.jvnCreateObject(new MutableValue("avant"));
        object.jvnUnLock();
        server.jvnRegisterObject(name, object);

        object.jvnLockWrite();
        ((MutableValue) object.jvnGetObjectState()).value = "apres";
        object.jvnUnLock();

        object.jvnLockRead();
        assertEquals("apres", ((MutableValue) object.jvnGetObjectState()).value, "la lecture doit voir l'état écrit");
        object.jvnUnLock();
    }

    /*
     * Scénario : on utilise la version 2 avec proxy dynamique.
     * Une méthode annotée @JvnWrite est appelée sur l'objet partagé.
     * Le proxy doit écrire localement, sauvegarder l'état auprès du coordinateur,
     * puis un lookup doit retrouver cet état sans attendre une invalidation future.
     */
    @Test
    void proxyWriteSavesStateToCoordinator() throws Exception {
        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        String name = "INTEGRATION_PROXY_SAVE_" + System.nanoTime();

        SentenceItf sentence = (SentenceItf) JvnProxy.newInstance(new Sentence(), name);
        sentence.write("etat-sauvegarde-par-proxy");

        JvnObject lookedUp = server.jvnLookupObject(name);
        assertNotNull(lookedUp, "l'objet proxy doit être enregistré côté coordinateur");
        assertEquals(
            "etat-sauvegarde-par-proxy",
            ((SentenceItf) lookedUp.jvnGetObjectState()).read(),
            "le coordinateur doit connaître l'état écrit par le proxy"
        );
    }

    private static void startRegistryIfNeeded() throws Exception {
        try {
            LocateRegistry.createRegistry(1099);
        } catch (Exception e) {
            // Le registre existe déjà, on le réutilise.
        }
    }

    private static void bindCoordinator() throws Exception {
        JvnCoordImpl coord = JvnCoordImpl.getCoordInstance();
        Naming.rebind("rmi://localhost/Coordinator", coord);
    }

    private static class MutableValue implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private String value;

        MutableValue(String value) {
            this.value = value;
        }
    }
}
