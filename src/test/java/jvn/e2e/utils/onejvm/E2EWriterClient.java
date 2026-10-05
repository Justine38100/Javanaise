package jvn.e2e.utils.onejvm;

import java.io.File;

import jvn.core.JvnObject;
import jvn.core.JvnServerImpl;
import jvn.e2e.utils.model.SharedText;

public class E2EWriterClient {
    public static void main(String[] args) throws Exception {
        // Arguments recus depuis l'orchestrateur :
        // - name : nom symbolique de l'objet partage dans Javanaise ;
        // - value : valeur que ce client doit ecrire ;
        // - releaseFile : fichier signal cree par le reader quand le test peut se terminer.
        String name = args[0];
        String value = args[1];
        File releaseFile = new File(args[2]);

        // Recupere un vrai serveur local JVN.
        // Dans cette JVM, ce serveur communique avec le vrai coordinateur via RMI.
        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        JvnObject object = server.jvnLookupObject(name);

        // Si l'objet n'existe pas encore, ce client est responsable de le creer et de l'enregistrer.
        // Apres createObject, l'objet possede un verrou ecriture actif ; jvnUnLock le met en cache.
        if (object == null) {
            object = server.jvnCreateObject(new SharedText("initial"));
            object.jvnUnLock();
            server.jvnRegisterObject(name, object);
        }

        // Prend un verrou ecriture, modifie l'etat partage, puis libere le verrou.
        // Le resultat attendu est que le reader lise ensuite exactement cette valeur.
        object.jvnLockWrite();
        ((SharedText) object.jvnGetObjectState()).value = value;
        object.jvnUnLock();

        // Indique a l'orchestrateur que l'ecriture est terminee.
        // Le processus reste vivant pour pouvoir repondre a une eventuelle invalidation RMI.
        System.out.println("WRITER_READY " + value);
        while (!releaseFile.exists()) {
            Thread.sleep(100);
        }

        server.jvnTerminate();
        System.exit(0);
    }
}
