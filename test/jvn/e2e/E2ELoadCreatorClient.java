package jvn.e2e;

import jvn.JvnObject;
import jvn.JvnServerImpl;

public class E2ELoadCreatorClient {
    public static void main(String[] args) throws Exception {
        // Ce client initialise l'objet partage utilise par le test de charge.
        // Il tourne dans sa propre JVM, cree l'objet si besoin, puis se termine.
        String name = args[0];

        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        JvnObject object = server.jvnLookupObject(name);

        // L'objet contient une liste de messages vide.
        // Les clients A et B ajouteront ensuite leurs messages dans cette meme liste partagee.
        if (object == null) {
            object = server.jvnCreateObject(new SharedText("load"));
            object.jvnUnLock();
            server.jvnRegisterObject(name, object);
        }

        // On termine proprement pour retirer ce serveur des tables du coordinateur.
        // Ainsi, les appenders ne doivent pas contacter un createur deja arrete pour invalider un verrou.
        server.jvnTerminate();
        System.out.println("LOAD_CREATOR_OK");
        System.exit(0);
    }
}
