package jvn.e2e.utils.failures;

import jvn.core.JvnObject;
import jvn.core.JvnServerImpl;
import jvn.e2e.utils.model.SharedText;

public class E2EDeadWriterClient {
    public static void main(String[] args) throws Exception {
        // Scénario : ce client crée un objet et devient writer côté coordinateur,
        // puis il s'arrête brutalement sans appeler jvnTerminate.
        // Résultat attendu : le coordinateur devra détecter plus tard la panne via RemoteException.
        String name = args[0];
        String value = args[1];

        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        JvnObject object = server.jvnCreateObject(new SharedText(value));
        object.jvnUnLock();
        server.jvnRegisterObject(name, object);

        System.out.println("DEAD_WRITER_READY " + value);
        System.out.flush();

        // Arrêt brutal volontaire : pas de jvnTerminate, pas de nettoyage RMI.
        Runtime.getRuntime().halt(0);
    }
}
