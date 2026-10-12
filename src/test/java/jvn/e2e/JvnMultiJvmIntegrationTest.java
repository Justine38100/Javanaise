package jvn.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JvnMultiJvmIntegrationTest {
    private static final String CLASSPATH = "target/classes:target/test-classes";

    private final List<ManagedProcess> processes = new ArrayList<ManagedProcess>();
    private ManagedProcess coordinatorProcess;

    @BeforeEach
    void startRmiRegistryAndCoordinator() throws Exception {
        // Démarre le registre RMI dans un processus séparé.
        // C'est l'équivalent du terminal : rmiregistry -J-Djava.class.path=target/classes:target/test-classes
        ManagedProcess registry = startProcess("rmiregistry", "-J-Djava.class.path=target/classes:target/test-classes");
        processes.add(registry);
        Thread.sleep(1000);

        // Démarre le coordinateur dans une JVM séparée.
        // Le coordinateur publie ensuite son objet distant avec la configuration RMI du test.
        coordinatorProcess = startCoordinator(null);
    }

    @AfterEach
    void stopStartedProcesses() {
        // Nettoie tous les processus lancés par le test, même si une assertion échoue.
        // Cela évite de laisser un rmiregistry ou un coordinateur tourner en arrière-plan.
        for (int i = processes.size() - 1; i >= 0; i--) {
            processes.get(i).destroy();
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
    @Test
    void twoSeparateJvmClientsShareAValue() throws Exception {
        String name = "MULTI_JVM_VALUE_" + System.nanoTime();
        String expected = "bonjour-multi-jvm";
        File releaseFile = tempSignalFile("javanaise-simple-release");

        ManagedProcess writer = startProcess("java", "-ea", "-cp", CLASSPATH,
                "jvn.e2e.utils.onejvm.E2EWriterClient", name, expected, releaseFile.getAbsolutePath());
        processes.add(writer);

        // Attend que le writer ait créé/enregistré l'objet et écrit la valeur.
        // Sans cette attente, le reader pourrait chercher l'objet trop tôt.
        writer.waitForOutput("WRITER_READY", 10000);

        ManagedProcess reader = startProcess("java", "-ea", "-cp", CLASSPATH,
                "jvn.e2e.utils.onejvm.E2EReaderClient", name, expected, releaseFile.getAbsolutePath());
        processes.add(reader);

        reader.waitForExit(10000);
        writer.waitForExit(10000);

        assertEquals(0, reader.exitCode(), "le lecteur multi-JVM a échoué:\n" + reader.output());
        assertEquals(0, writer.exitCode(), "l'écrivain multi-JVM a échoué:\n" + writer.output());
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
    @Test
    void twoSeparateJvmClientsDoNotLoseMessagesUnderLoad() throws Exception {
        String name = "MULTI_JVM_LOAD_" + System.nanoTime();
        int iterations = 1000;
        File releaseFile = tempSignalFile("javanaise-load-release");

        ManagedProcess creator = startProcess("java", "-ea", "-cp", CLASSPATH,
                "jvn.e2e.utils.multijvm.E2ELoadCreatorClient", name);
        processes.add(creator);
        creator.waitForExit(10000);
        assertEquals(0, creator.exitCode(), "le créateur du test de charge a échoué:\n" + creator.output());

        ManagedProcess clientA = startProcess("java", "-ea", "-cp", CLASSPATH,
                "jvn.e2e.utils.multijvm.E2ELoadAppenderClient", name, "A", String.valueOf(iterations), releaseFile.getAbsolutePath());
        ManagedProcess clientB = startProcess("java", "-ea", "-cp", CLASSPATH,
                "jvn.e2e.utils.multijvm.E2ELoadAppenderClient", name, "B", String.valueOf(iterations), releaseFile.getAbsolutePath());
        processes.add(clientA);
        processes.add(clientB);

        // Attend que les deux clients aient fini leurs 1000 iterations.
        // Ils restent volontairement vivants ensuite pour pouvoir répondre aux invalidations RMI.
        clientA.waitForOutput("APPENDER_DONE A", 30000);
        clientB.waitForOutput("APPENDER_DONE B", 30000);

        ManagedProcess verifier = startProcess("java", "-ea", "-cp", CLASSPATH,
                "jvn.e2e.utils.multijvm.E2ELoadVerifierClient", name, String.valueOf(iterations), releaseFile.getAbsolutePath());
        processes.add(verifier);
        verifier.waitForExit(15000);

        // Le vérificateur a créé releaseFile, donc A et B peuvent sortir de leur boucle d'attente.
        clientA.waitForExit(10000);
        clientB.waitForExit(10000);

        assertEquals(0, verifier.exitCode(), "le vérificateur du test de charge a échoué:\n" + verifier.output());
        assertEquals(0, clientA.exitCode(), "le client A du test de charge a échoué:\n" + clientA.output());
        assertEquals(0, clientB.exitCode(), "le client B du test de charge a échoué:\n" + clientB.output());
    }

    /*
     * Scénario : un client writer tombe brutalement sans appeler jvnTerminate.
     *
     * Actions réalisées :
     * - un premier client crée un objet et devient writer côté coordinateur ;
     * - ce client s'arrête avec Runtime.halt, donc le coordinateur garde une référence RMI morte ;
     * - un second client demande un verrou écriture sur le même objet.
     *
     * Résultat attendu :
     * - le coordinateur tente d'invalider le writer mort ;
     * - l'appel RMI lève une RemoteException ;
     * - le coordinateur retire le writer mort et accorde le verrou au second client ;
     * - le second client écrit une nouvelle valeur et termine correctement.
     */
    @Test
    void deadWriterDoesNotBlockFutureWriter() throws Exception {
        String name = "E2E_DEAD_WRITER_" + System.nanoTime();
        String initialValue = "valeur-avant-panne-client";
        String recoveredValue = "valeur-apres-panne-client";

        ManagedProcess deadWriter = startProcess("java", "-ea", "-cp", CLASSPATH,
                "jvn.e2e.utils.failures.E2EDeadWriterClient", name, initialValue);
        processes.add(deadWriter);
        deadWriter.waitForOutput("DEAD_WRITER_READY", 10000);
        deadWriter.waitForExit(10000);
        assertEquals(0, deadWriter.exitCode(), "le writer mort contrôlé a échoué:\n" + deadWriter.output());

        ManagedProcess writerAfterCrash = startProcess("java", "-ea", "-cp", CLASSPATH,
                "jvn.e2e.utils.failures.E2EWriteAfterDeadWriterClient", name, recoveredValue);
        processes.add(writerAfterCrash);
        writerAfterCrash.waitForExit(15000);

        assertEquals(0, writerAfterCrash.exitCode(), "le writer après panne client a échoué:\n" + writerAfterCrash.output());
    }

    /*
     * Scénario : un client utilise la version proxy, écrit une valeur, puis tombe brutalement.
     *
     * Actions réalisées :
     * - le client proxy appelle une méthode @JvnWrite ;
     * - le proxy sauvegarde l'état auprès du coordinateur via jvnUpdateObjectState ;
     * - le client tombe sans appeler jvnTerminate ;
     * - un autre client lit l'objet partagé.
     *
     * Résultat attendu :
     * - la lecture doit retrouver la valeur écrite avant la panne ;
     * - cela prouve que la sauvegarde proxy protège les écritures terminées.
     */
    @Test
    void proxyWriteSurvivesClientCrash() throws Exception {
        String name = "E2E_PROXY_CRASH_" + System.nanoTime();
        String expected = "valeur-sauvee-par-proxy";

        ManagedProcess proxyWriter = startProcess("java", "-ea", "-cp", CLASSPATH,
                "jvn.e2e.utils.failures.E2EProxyWriteAndCrashClient", name, expected);
        processes.add(proxyWriter);
        proxyWriter.waitForOutput("PROXY_WRITE_SAVED", 10000);
        proxyWriter.waitForExit(10000);
        assertEquals(0, proxyWriter.exitCode(), "le writer proxy contrôlé a échoué:\n" + proxyWriter.output());

        ManagedProcess reader = startProcess("java", "-ea", "-cp", CLASSPATH,
                "jvn.e2e.utils.failures.E2EProxySavedStateReaderClient", name, expected);
        processes.add(reader);
        reader.waitForExit(15000);

        assertEquals(0, reader.exitCode(), "le lecteur de l'état sauvegardé par proxy a échoué:\n" + reader.output());
    }

    /*
     * Scénario : le coordinateur tombe puis redémarre avec le même fichier snapshot.
     *
     * Actions réalisées :
     * - on redémarre le coordinateur du test avec un fichier snapshot dédié ;
     * - un client écrit via proxy, ce qui sauvegarde l'état en mémoire et sur disque ;
     * - l'orchestrateur tue le coordinateur ;
     * - l'orchestrateur relance un coordinateur avec le même snapshot ;
     * - le même client force une reconnexion en faisant un lookup via JvnServerImpl.
     *
     * Résultat attendu :
     * - le client retrouve l'objet et son état après redémarrage du coordinateur ;
     * - les anciens verrous locaux sont remis à NL avant de redemander un verrou propre.
     */
    @Test
    void coordinatorRestartReloadsSnapshotAndClientReconnects() throws Exception {
        File snapshotFile = tempSignalFile("javanaise-coord-recovery-snapshot");
        snapshotFile.delete();

        restartCoordinatorWithSnapshot(snapshotFile);

        String name = "E2E_COORD_RECOVERY_" + System.nanoTime();
        String expected = "valeur-restauree-apres-redemarrage-coordinateur";
        File restartSignal = tempSignalFile("javanaise-coord-restarted");

        ManagedProcess client = startProcess("java", "-ea", "-cp", CLASSPATH,
                "jvn.e2e.utils.failures.E2ECoordinatorRecoveryClient", name, expected, restartSignal.getAbsolutePath());
        processes.add(client);
        client.waitForOutput("COORD_RECOVERY_CLIENT_READY", 10000);

        restartCoordinatorWithSnapshot(snapshotFile);
        restartSignal.createNewFile();

        client.waitForExit(20000);
        assertEquals(0, client.exitCode(), "le client après redémarrage coordinateur a échoué:\n" + client.output());
    }

    private static File tempSignalFile(String prefix) throws Exception {
        // Crée un nom de fichier temporaire unique, puis supprime le fichier.
        // Le chemin sert ensuite de signal entre processus : tant que le fichier n'existe pas, le client attend.
        File file = File.createTempFile(prefix, ".signal");
        file.delete();
        file.deleteOnExit();
        return file;
    }

    private ManagedProcess startCoordinator(File snapshotFile) throws Exception {
        ManagedProcess coordinator;

        if (snapshotFile == null) {
            coordinator = startProcess("java", "-cp", CLASSPATH, "jvn.core.JvnCoordImpl");
        } else {
            coordinator = startProcess("java", "-Djvn.coord.snapshot=" + snapshotFile.getAbsolutePath(),
                    "-cp", CLASSPATH, "jvn.core.JvnCoordImpl");
        }

        processes.add(coordinator);
        coordinatorProcess = coordinator;
        coordinator.waitForOutput("Javanaise coordinator ready", 5000);
        return coordinator;
    }

    private void restartCoordinatorWithSnapshot(File snapshotFile) throws Exception {
        if (coordinatorProcess != null) {
            coordinatorProcess.destroy();
            processes.remove(coordinatorProcess);
            coordinatorProcess = null;
            Thread.sleep(1000);
        }

        startCoordinator(snapshotFile);
    }

    private static ManagedProcess startProcess(String... command) throws Exception {
        // Lance un processus externe, comme si on exécutait la commande dans un terminal.
        // stdout et stderr sont fusionnés pour simplifier le diagnostic si le test échoue.
        ProcessBuilder builder = new ProcessBuilder(withLocalRmiConfiguration(command));
        builder.directory(new File("."));
        builder.redirectErrorStream(true);
        return new ManagedProcess(builder.start(), command);
    }

    private static String[] withLocalRmiConfiguration(String[] command) {
        // Les tests e2e doivent rester reproductibles même si la machine de développement
        // définit des variables d'environnement JVN_COORD_HOST ou JVN_COORD_PORT.
        // On force donc explicitement la configuration locale pour les JVM de test.
        if (command.length == 0 || !"java".equals(command[0])) {
            return command;
        }

        String[] configuredCommand = new String[command.length + 3];
        configuredCommand[0] = command[0];
        configuredCommand[1] = "-Djvn.coord.host=localhost";
        configuredCommand[2] = "-Djvn.coord.port=1099";
        configuredCommand[3] = "-Djvn.coord.name=Coordinator";

        for (int i = 1; i < command.length; i++) {
            configuredCommand[i + 3] = command[i];
        }

        return configuredCommand;
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
