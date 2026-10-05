package jvn.e2e.utils.onejvm;

import java.io.File;

import jvn.core.JvnObject;
import jvn.core.JvnServerImpl;
import jvn.e2e.utils.model.SharedText;

public class E2EReaderClient {
    public static void main(String[] args) throws Exception {
        // Arguments recus depuis l'orchestrateur :
        // - name : nom symbolique de l'objet partage ;
        // - expected : valeur que le reader doit observer ;
        // - releaseFile : fichier signal a creer pour liberer le writer.
        String name = args[0];
        String expected = args[1];
        File releaseFile = new File(args[2]);

        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        JvnObject object = waitForObject(server, name);

        // Prend un verrou lecture avant d'acceder a l'etat.
        // Si un writer possede encore le verrou ecriture en cache, le coordinateur doit le reduire/invalider.
        object.jvnLockRead();
        String actual = ((SharedText) object.jvnGetObjectState()).value;
        object.jvnUnLock();

        // Resultat attendu : la valeur lue doit etre celle ecrite par le writer dans une autre JVM.
        if (!expected.equals(actual)) {
            throw new AssertionError("valeur attendue=" + expected + ", valeur lue=" + actual);
        }

        // Signale au writer qu'il peut quitter sa boucle d'attente.
        releaseFile.createNewFile();
        server.jvnTerminate();
        System.out.println("READER_OK " + actual);
        System.exit(0);
    }

    private static JvnObject waitForObject(JvnServerImpl server, String name) throws Exception {
        // Le writer peut ne pas avoir fini le register au moment ou le reader demarre.
        // On attend donc l'apparition de l'objet pendant quelques secondes.
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
