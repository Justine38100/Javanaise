package jvn.e2e.utils.multijvm;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import jvn.core.JvnObject;
import jvn.core.JvnServerImpl;
import jvn.e2e.utils.model.SharedText;

public class E2ELoadVerifierClient {
    public static void main(String[] args) throws Exception {
        // Arguments :
        // - name : nom de l'objet partage ;
        // - iterations : nombre de messages ecrits par chaque client ;
        // - releaseFile : fichier signal a creer pour liberer les appenders.
        String name = args[0];
        int iterations = Integer.parseInt(args[1]);
        File releaseFile = new File(args[2]);

        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        JvnObject object = waitForObject(server, name);

        // Prend un verrou lecture pour recuperer une version coherente de la liste.
        // Si un appender possede encore un verrou ecriture en cache, le coordinateur doit recuperer son dernier etat.
        object.jvnLockRead();
        SharedText state = (SharedText) object.jvnGetObjectState();
        Set<String> messages = new HashSet<String>(state.messages);
        object.jvnUnLock();

        // Resultat attendu : 2 clients * iterations messages.
        // On utilise un Set pour verifier la presence de chaque message attendu.
        int expectedTotal = iterations * 2;
        if (messages.size() != expectedTotal) {
            long messagesA = messages.stream().filter(message -> message.startsWith("A-")).count();
            long messagesB = messages.stream().filter(message -> message.startsWith("B-")).count();
            throw new AssertionError("nombre de messages attendu=" + expectedTotal + ", obtenu=" + messages.size()
                    + " (A=" + messagesA + ", B=" + messagesB + ")");
        }

        for (int i = 0; i < iterations; i++) {
            assertContains(messages, "A-" + i);
            assertContains(messages, "B-" + i);
        }

        // Tous les messages sont presents : on libere les clients A et B.
        releaseFile.createNewFile();
        server.jvnTerminate();
        System.out.println("LOAD_VERIFIER_OK " + messages.size());
        System.exit(0);
    }

    private static void assertContains(Set<String> messages, String expected) {
        if (!messages.contains(expected)) {
            throw new AssertionError("message perdu: " + expected);
        }
    }

    private static JvnObject waitForObject(JvnServerImpl server, String name) throws Exception {
        // Attend que l'objet partage existe dans le coordinateur.
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
