package jvn;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class JvnMultiJvmIntegrationTest {
    private static final String CLASSPATH = "bin";

    public static void main(String[] args) throws Exception {
        List<ManagedProcess> processes = new ArrayList<ManagedProcess>();

        try {
            // Démarre le registre RMI dans un processus séparé.
            // C'est l'équivalent du terminal : rmiregistry -J-Djava.class.path=bin
            ManagedProcess registry = startProcess("rmiregistry", "-J-Djava.class.path=bin");
            processes.add(registry);
            Thread.sleep(1000);

            // Démarre le coordinateur dans une JVM séparée.
            // Le coordinateur publie ensuite son objet distant sous le nom rmi://localhost/Coordinator.
            ManagedProcess coordinator = startProcess("java", "-cp", CLASSPATH, "jvn.JvnCoordImpl");
            processes.add(coordinator);
            coordinator.waitForOutput("Javanaise coordinator ready", 5000);

            // Démarre deux clients dans deux JVM séparées pour tester le partage d'une valeur.
            testTwoSeparateJvmClientsShareAValue(processes);

            // Démarre deux clients dans deux JVM séparées pour tester le partage d'une liste sous charge.
            testTwoSeparateJvmClientsDoNotLoseMessagesUnderLoad(processes);

            System.out.println("JvnMultiJvmIntegrationTest OK");
        } finally {
            // Nettoie tous les processus lancés par le test, même si une assertion échoue.
            // Cela évite de laisser un rmiregistry ou un coordinateur tourner en arrière-plan.
            for (int i = processes.size() - 1; i >= 0; i--) {
                processes.get(i).destroy();
            }
        }
    }

    /*
     * Scenario : on lance un registre RMI, un coordinateur dans une JVM séparée,
     * puis deux vrais clients dans deux JVM séparées.
     *
     * Actions réalisées :
     * - le client writer cherche l'objet partagé ; s'il n'existe pas, il le crée et l'enregistre ;
     * - le writer prend ensuite un verrou écriture, modifie la valeur partagée, puis déverrouille ;
     * - le writer reste vivant pour pouvoir répondre aux invalidations du coordinateur ;
     * - le client reader fait un lookup sur le même nom, prend un verrou lecture et lit la valeur ;
     * - le reader crée un fichier signal pour dire au writer qu'il peut se terminer.
     *
     * Résultat attendu :
     * - le reader doit lire exactement la valeur écrite par le writer ;
     * - les deux processus clients doivent se terminer avec un code de sortie 0.
     */
    private static void testTwoSeparateJvmClientsShareAValue(List<ManagedProcess> processes) throws Exception {
        String name = "MULTI_JVM_VALUE_" + System.nanoTime();
        String expected = "bonjour-multi-jvm";
        File releaseFile = tempSignalFile("javanaise-simple-release");

        ManagedProcess writer = startProcess("java", "-ea", "-cp", CLASSPATH,
                "jvn.e2e.E2EWriterClient", name, expected, releaseFile.getAbsolutePath());
        processes.add(writer);

        // Attend que le writer ait créé/enregistré l'objet et écrit la valeur.
        // Sans cette attente, le reader pourrait chercher l'objet trop tôt.
        writer.waitForOutput("WRITER_READY", 10000);

        ManagedProcess reader = startProcess("java", "-ea", "-cp", CLASSPATH,
                "jvn.e2e.E2EReaderClient", name, expected, releaseFile.getAbsolutePath());
        processes.add(reader);

        reader.waitForExit(10000);
        writer.waitForExit(10000);

        if (reader.exitCode() != 0) {
            throw new AssertionError("le lecteur multi-JVM a echoue:\n" + reader.output());
        }
        if (writer.exitCode() != 0) {
            throw new AssertionError("l ecrivain multi-JVM a echoue:\n" + writer.output());
        }
    }

    /*
     * Scenario : on lance deux clients dans deux JVM séparées.
     * Chaque client ajoute 1000 messages dans le même objet partagé en prenant un verrou écriture à chaque itération.
     *
     * Actions réalisées :
     * - un client créateur crée l'objet partagé vide puis se termine proprement ;
     * - le client A ajoute les messages A-0, A-1, ..., A-999 ;
     * - le client B ajoute les messages B-0, B-1, ..., B-999 ;
     * - A et B tournent en même temps, donc les demandes de verrous écriture se croisent ;
     * - un client vérificateur lit ensuite l'objet partagé ;
     * - le vérificateur crée un fichier signal pour libérer A et B.
     *
     * Résultat attendu :
     * - la liste finale doit contenir exactement 2000 messages ;
     * - aucun message A-i ou B-i ne doit manquer ;
     * - les trois processus de charge doivent se terminer avec un code de sortie 0.
     */
    private static void testTwoSeparateJvmClientsDoNotLoseMessagesUnderLoad(List<ManagedProcess> processes) throws Exception {
        String name = "MULTI_JVM_LOAD_" + System.nanoTime();
        int iterations = 1000;
        File releaseFile = tempSignalFile("javanaise-load-release");

        ManagedProcess creator = startProcess("java", "-ea", "-cp", CLASSPATH,
                "jvn.e2e.E2ELoadCreatorClient", name);
        processes.add(creator);
        creator.waitForExit(10000);
        if (creator.exitCode() != 0) {
            throw new AssertionError("le createur du test de charge a echoue:\n" + creator.output());
        }

        ManagedProcess clientA = startProcess("java", "-ea", "-cp", CLASSPATH,
                "jvn.e2e.E2ELoadAppenderClient", name, "A", String.valueOf(iterations), releaseFile.getAbsolutePath());
        ManagedProcess clientB = startProcess("java", "-ea", "-cp", CLASSPATH,
                "jvn.e2e.E2ELoadAppenderClient", name, "B", String.valueOf(iterations), releaseFile.getAbsolutePath());
        processes.add(clientA);
        processes.add(clientB);

        // Attend que les deux clients aient fini leurs 1000 iterations.
        // Ils restent volontairement vivants ensuite pour pouvoir répondre aux invalidations RMI.
        clientA.waitForOutput("APPENDER_DONE A", 30000);
        clientB.waitForOutput("APPENDER_DONE B", 30000);

        ManagedProcess verifier = startProcess("java", "-ea", "-cp", CLASSPATH,
                "jvn.e2e.E2ELoadVerifierClient", name, String.valueOf(iterations), releaseFile.getAbsolutePath());
        processes.add(verifier);
        verifier.waitForExit(15000);

        // Le vérificateur a créé releaseFile, donc A et B peuvent sortir de leur boucle d'attente.
        clientA.waitForExit(10000);
        clientB.waitForExit(10000);

        if (verifier.exitCode() != 0) {
            throw new AssertionError("le vérificateur du test de charge a échoué:\n" + verifier.output());
        }
        if (clientA.exitCode() != 0) {
            throw new AssertionError("le client A du test de charge a échoué:\n" + clientA.output());
        }
        if (clientB.exitCode() != 0) {
            throw new AssertionError("le client B du test de charge a échoué:\n" + clientB.output());
        }
    }

    private static File tempSignalFile(String prefix) throws Exception {
        // Crée un nom de fichier temporaire unique, puis supprime le fichier.
        // Le chemin sert ensuite de signal entre processus : tant que le fichier n'existe pas, le client attend.
        File file = File.createTempFile(prefix, ".signal");
        file.delete();
        file.deleteOnExit();
        return file;
    }

    private static ManagedProcess startProcess(String... command) throws Exception {
        // Lance un processus externe, comme si on exécutait la commande dans un terminal.
        // stdout et stderr sont fusionnés pour simplifier le diagnostic si le test échoue.
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File("."));
        builder.redirectErrorStream(true);
        return new ManagedProcess(builder.start(), command);
    }

    private static class ManagedProcess {
        private final Process process;
        private final String[] command;
        private final StringBuilder output = new StringBuilder();
        private volatile Integer exitCode;

        ManagedProcess(Process process, String[] command) {
            this.process = process;
            this.command = command;

            // Lit la sortie du processus dans un thread séparé.
            // Cela permet à waitForOutput(...) d'attendre une ligne précise sans bloquer le processus enfant.
            Thread reader = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(ManagedProcess.this.process.getInputStream()));
                        String line;
                        while ((line = bufferedReader.readLine()) != null) {
                            synchronized (output) {
                                output.append(line).append('\n');
                                output.notifyAll();
                            }
                        }
                    } catch (Exception e) {
                        synchronized (output) {
                            output.append("OUTPUT_READER_ERROR: ").append(e.getMessage()).append('\n');
                            output.notifyAll();
                        }
                    }
                }
            });
            reader.setDaemon(true);
            reader.start();
        }

        void waitForOutput(String expected, long timeoutMillis) throws Exception {
            // Attend qu'un processus imprime une ligne indiquant qu'il a atteint une étape importante.
            // Exemple : WRITER_READY signifie que le writer a fini son écriture et reste disponible pour RMI.
            long deadline = System.currentTimeMillis() + timeoutMillis;
            synchronized (output) {
                while (output.indexOf(expected) < 0) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        throw new AssertionError("sortie attendue introuvable: " + expected + "\ncommande: " + commandLine() + "\nsortie:\n" + output());
                    }
                    output.wait(remaining);
                }
            }
        }

        void waitForExit(long timeoutMillis) throws Exception {
            // Attend la fin du processus avec timeout.
            // Si le processus ne sort pas, le test échoue pour éviter un blocage infini.
            boolean exited = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!exited) {
                throw new AssertionError("processus trop long: " + commandLine() + "\nsortie:\n" + output());
            }
            exitCode = process.exitValue();
        }

        Integer exitCode() {
            if (exitCode == null) {
                try {
                    exitCode = process.exitValue();
                } catch (IllegalThreadStateException e) {
                    return null;
                }
            }
            return exitCode;
        }

        String output() {
            synchronized (output) {
                return output.toString();
            }
        }

        void destroy() {
            // Arrête le processus proprement, puis force l'arrêt s'il reste bloqué.
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (Exception e) {
                process.destroyForcibly();
            }
        }

        private String commandLine() {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < command.length; i++) {
                if (i > 0) {
                    builder.append(' ');
                }
                builder.append(command[i]);
            }
            return builder.toString();
        }
    }
}
