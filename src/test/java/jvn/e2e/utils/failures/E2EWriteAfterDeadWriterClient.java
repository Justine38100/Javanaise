package jvn.e2e.utils.failures;

import jvn.core.JvnObject;
import jvn.core.JvnServerImpl;
import jvn.e2e.utils.model.SharedText;

public class E2EWriteAfterDeadWriterClient {
    public static void main(String[] args) throws Exception {
        // Scénario : un autre client demande un verrou écriture alors que l'ancien writer est mort.
        // Résultat attendu : le coordinateur attrape la RemoteException, retire le writer mort,
        // puis accorde le verrou écriture à ce client.
        String name = args[0];
        String value = args[1];

        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        JvnObject object = waitForObject(server, name);

        object.jvnLockWrite();
        ((SharedText) object.jvnGetObjectState()).value = value;
        object.jvnUnLock();

        server.jvnTerminate();
        System.out.println("WRITE_AFTER_DEAD_WRITER_OK " + value);
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
