package jvn.e2e.utils.failures;

import irc.model.SentenceItf;
import jvn.core.JvnObject;
import jvn.core.JvnServerImpl;

public class E2EProxySavedStateReaderClient {
    public static void main(String[] args) throws Exception {
        // Scénario : lecture d'un état écrit par un client proxy qui est ensuite tombé.
        // Résultat attendu : le coordinateur retourne l'état sauvegardé par jvnUpdateObjectState.
        String name = args[0];
        String expected = args[1];

        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        JvnObject object = waitForObject(server, name);

        object.jvnLockRead();
        String actual = ((SentenceItf) object.jvnGetObjectState()).read();
        object.jvnUnLock();

        if (!expected.equals(actual)) {
            throw new AssertionError("valeur attendue=" + expected + ", valeur lue=" + actual);
        }

        server.jvnTerminate();
        System.out.println("PROXY_SAVED_STATE_READER_OK " + actual);
        System.exit(0);
    }

    private static JvnObject waitForObject(JvnServerImpl server, String name) throws Exception {
        for (int i = 0; i < 100; i++) {
            JvnObject object = server.jvnLookupObject(name);
            if (object != null) {
                return object;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("objet introuvable: " + name);
    }
}
