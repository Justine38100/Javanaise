package jvn.e2e.utils.multijvm;

import java.io.File;

import jvn.core.JvnObject;
import jvn.core.JvnServerImpl;
import jvn.e2e.utils.model.SharedText;

public class E2ELoadAppenderClient {
    public static void main(String[] args) throws Exception {
        // Arguments :
        // - name : nom de l'objet partage ;
        // - prefix : identifiant du client, par exemple A ou B ;
        // - iterations : nombre de messages a ajouter ;
        // - releaseFile : fichier signal cree par le verificateur a la fin du test.
        String name = args[0];
        String prefix = args[1];
        int iterations = Integer.parseInt(args[2]);
        File releaseFile = new File(args[3]);

        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        JvnObject object = waitForObject(server, name);

        // Chaque iteration prend un verrou ecriture, ajoute exactement un message, puis deverrouille.
        // Comme deux clients executent cette boucle en parallele, le coordinateur doit serialiser les ecritures.
        for (int i = 0; i < iterations; i++) {
            object.jvnLockWrite();
            SharedText state = (SharedText) object.jvnGetObjectState();
            state.messages.add(prefix + "-" + i);
            object.jvnUnLock();
        }

        // Signale que toutes les ecritures de ce client sont terminees.
        // Le client reste vivant pour que le verificateur puisse declencher les invalidations RMI necessaires.
        System.out.println("APPENDER_DONE " + prefix + " " + iterations);
        while (!releaseFile.exists()) {
            Thread.sleep(100);
        }

        server.jvnTerminate();
        System.exit(0);
    }

    private static JvnObject waitForObject(JvnServerImpl server, String name) throws Exception {
        // Attend que le createur ait fini d'enregistrer l'objet partage.
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
