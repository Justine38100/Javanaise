package jvn.e2e.utils.failures;

import java.io.File;

import irc.model.Sentence;
import irc.model.SentenceItf;
import jvn.core.JvnObject;
import jvn.core.JvnServerImpl;
import jvn.proxy.JvnProxy;

public class E2ECoordinatorRecoveryClient {
    public static void main(String[] args) throws Exception {
        // Scénario : ce client écrit via proxy, attend que le coordinateur redémarre,
        // puis réutilise le vrai serveur local JVN pour forcer une reconnexion RMI.
        // Résultat attendu : le lookup après redémarrage retrouve l'objet restauré depuis le snapshot.
        String name = args[0];
        String expected = args[1];
        File restartSignal = new File(args[2]);

        SentenceItf sentence = (SentenceItf) JvnProxy.newInstance(new Sentence(), name);
        sentence.write(expected);
        System.out.println("COORD_RECOVERY_CLIENT_READY " + expected);
        System.out.flush();

        while (!restartSignal.exists()) {
            Thread.sleep(100);
        }

        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        JvnObject object = waitForObject(server, name);

        object.jvnLockRead();
        String actual = ((SentenceItf) object.jvnGetObjectState()).read();
        object.jvnUnLock();

        if (!expected.equals(actual)) {
            throw new AssertionError("valeur attendue=" + expected + ", valeur lue=" + actual);
        }

        server.jvnTerminate();
        System.out.println("COORDINATOR_RECOVERY_CLIENT_OK " + actual);
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
        throw new AssertionError("objet introuvable après redémarrage coordinateur: " + name);
    }
}
