package jvn.units;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jvn.core.JvnCoordImpl;
import jvn.core.JvnException;
import jvn.core.JvnObject;
import jvn.core.JvnObjectImpl;
import jvn.core.JvnRemoteServer;

class JvnCoordImplTest {

    @TempDir
    Path temporaryDirectory;

    /*
     * Scénario : plusieurs objets sont créés.
     * Le coordinateur doit attribuer des identifiants uniques et croissants.
     */
    @Test
    void objectIdsAreUniqueAndIncreasing() throws Exception {
        JvnCoordImpl coord = JvnCoordImpl.getCoordInstance();

        int first = coord.jvnGetObjectId();
        int second = coord.jvnGetObjectId();

        assertEquals(first + 1, second, "les ids des objets doivent être croissants");
    }

    /*
     * Scénario : un serveur enregistre un nom symbolique pour un objet.
     * Un lookup avec le même nom doit retourner un objet non null avec le même id et le même état.
     */
    @Test
    void registerThenLookupReturnsObjectWithSameIdAndState() throws Exception {
        JvnCoordImpl coord = JvnCoordImpl.getCoordInstance();
        FakeServer owner = new FakeServer("owner-state");
        int id = coord.jvnGetObjectId();
        JvnObject object = new JvnObjectImpl(id, "registered-state", null, true);
        String name = "TEST_REGISTER_" + id;

        coord.jvnRegisterObject(name, object, owner);
        JvnObject lookedUp = coord.jvnLookupObject(name, new FakeServer("reader-state"));

        assertNotNull(lookedUp, "le lookup doit retourner l'objet enregistré");
        assertEquals(id, lookedUp.jvnGetObjectId(), "le lookup doit conserver le même id objet");
        assertEquals("registered-state", lookedUp.jvnGetObjectState(), "le lookup doit retourner le dernier état connu");
    }

    /*
     * Scénario : le serveur A possède le verrou en écriture pour un objet.
     * Le serveur B demande un verrou en lecture.
     * Le coordinateur doit appeler invalidateWriterForReader sur A, stocker l'état retourné par A,
     * transformer A en lecteur, ajouter B comme lecteur, puis retourner le nouvel état à B.
     */
    @Test
    void readLockDowngradesExistingWriter() throws Exception {
        JvnCoordImpl coord = JvnCoordImpl.getCoordInstance();
        FakeServer writer = new FakeServer("state-from-writer");
        FakeServer reader = new FakeServer("unused-reader-state");
        int id = coord.jvnGetObjectId();
        JvnObject object = new JvnObjectImpl(id, "old-state", null, true);
        String name = "TEST_READ_" + id;

        coord.jvnRegisterObject(name, object, writer);
        Serializable state = coord.jvnLockRead(id, reader);

        assertEquals(1, writer.invalidateWriterForReaderCount, "l'écrivain doit être réduit pour permettre la lecture");
        assertEquals("state-from-writer", state, "le verrou lecture doit retourner l'état frais de l'écrivain");
    }

    /*
     * Scénario : le serveur A possède le verrou écriture, puis le serveur B obtient un verrou lecture,
     * puis le serveur C demande un verrou écriture.
     * Le coordinateur doit invalider les lecteurs courants,
     * puis faire de C le seul écrivain et retourner le dernier état connu.
     */
    @Test
    void writeLockInvalidatesReadersAndWriter() throws Exception {
        JvnCoordImpl coord = JvnCoordImpl.getCoordInstance();
        FakeServer firstWriter = new FakeServer("state-after-first-writer");
        FakeServer reader = new FakeServer("reader-state");
        FakeServer newWriter = new FakeServer("new-writer-state");
        int id = coord.jvnGetObjectId();
        JvnObject object = new JvnObjectImpl(id, "initial-state", null, true);
        String name = "TEST_WRITE_" + id;

        coord.jvnRegisterObject(name, object, firstWriter);
        coord.jvnLockRead(id, reader);
        Serializable state = coord.jvnLockWrite(id, newWriter);

        assertEquals(1, firstWriter.invalidateWriterForReaderCount, "le premier écrivain doit d'abord être réduit pour le lecteur");
        assertEquals(1, firstWriter.invalidateReaderCount, "l'écrivain réduit doit ensuite être invalidé comme lecteur");
        assertEquals(1, reader.invalidateReaderCount, "le lecteur doit être invalidé avant le verrou écriture");
        assertEquals("state-after-first-writer", state, "le verrou écriture doit retourner le dernier état connu");
    }

    /*
     * Scénario : un lecteur est enregistré côté coordinateur, puis tombe en panne.
     * Quand un autre serveur demande un verrou écriture, l'invalidation du lecteur mort lève une RemoteException.
     * Le coordinateur doit retirer ce lecteur mort et accorder le verrou écriture au nouveau serveur.
     */
    @Test
    void writeLockIgnoresDeadReader() throws Exception {
        JvnCoordImpl coord = JvnCoordImpl.getCoordInstance();
        FakeServer owner = new FakeServer("state-from-owner");
        FakeServer deadReader = new FakeServer("dead-reader-state");
        FakeServer newWriter = new FakeServer("new-writer-state");
        int id = coord.jvnGetObjectId();
        JvnObject object = new JvnObjectImpl(id, "initial-state", null, true);
        String name = "TEST_DEAD_READER_" + id;

        coord.jvnRegisterObject(name, object, owner);
        coord.jvnLockRead(id, deadReader);
        deadReader.failOnInvalidateReader = true;

        Serializable state = coord.jvnLockWrite(id, newWriter);

        assertEquals(1, deadReader.invalidateReaderCount, "le coordinateur doit tenter d'invalider le lecteur mort");
        assertEquals("state-from-owner", state, "le verrou écriture doit être accordé avec le dernier état connu");
    }

    /*
     * Scénario : un écrivain possède le verrou, puis tombe en panne.
     * Quand un autre serveur demande un verrou lecture, l'invalidation de l'écrivain mort lève une RemoteException.
     * Le coordinateur doit retirer cet écrivain mort et accorder le verrou lecture avec son dernier état connu.
     */
    @Test
    void readLockIgnoresDeadWriter() throws Exception {
        JvnCoordImpl coord = JvnCoordImpl.getCoordInstance();
        FakeServer deadWriter = new FakeServer("state-that-cannot-be-recovered");
        FakeServer reader = new FakeServer("reader-state");
        int id = coord.jvnGetObjectId();
        JvnObject object = new JvnObjectImpl(id, "last-coordinator-state", null, true);
        String name = "TEST_DEAD_WRITER_READ_" + id;

        coord.jvnRegisterObject(name, object, deadWriter);
        deadWriter.failOnInvalidateWriterForReader = true;

        Serializable state = coord.jvnLockRead(id, reader);

        assertEquals(1, deadWriter.invalidateWriterForReaderCount, "le coordinateur doit tenter de réduire l'écrivain mort");
        assertEquals("last-coordinator-state", state, "le coordinateur garde son dernier état connu si l'écrivain est mort");
    }

    /*
     * Scénario : un écrivain possède le verrou, puis tombe en panne.
     * Quand un autre serveur demande un verrou écriture, l'invalidation de l'écrivain mort lève une RemoteException.
     * Le coordinateur doit retirer l'ancien écrivain et donner le verrou au nouveau.
     */
    @Test
    void writeLockIgnoresDeadWriter() throws Exception {
        JvnCoordImpl coord = JvnCoordImpl.getCoordInstance();
        FakeServer deadWriter = new FakeServer("state-that-cannot-be-recovered");
        FakeServer newWriter = new FakeServer("new-writer-state");
        int id = coord.jvnGetObjectId();
        JvnObject object = new JvnObjectImpl(id, "last-coordinator-state", null, true);
        String name = "TEST_DEAD_WRITER_WRITE_" + id;

        coord.jvnRegisterObject(name, object, deadWriter);
        deadWriter.failOnInvalidateWriter = true;

        Serializable state = coord.jvnLockWrite(id, newWriter);

        assertEquals(1, deadWriter.invalidateWriterCount, "le coordinateur doit tenter d'invalider l'écrivain mort");
        assertEquals("last-coordinator-state", state, "le nouveau writer reçoit le dernier état connu du coordinateur");
    }

    /*
     * Scénario : le serveur qui possède le verrou écriture sauvegarde son état auprès du coordinateur.
     * Le coordinateur doit accepter la mise à jour du writer courant,
     * puis refuser une mise à jour venant d'un serveur qui n'est pas writer.
     */
    @Test
    void onlyCurrentWriterCanUpdateObjectState() throws Exception {
        JvnCoordImpl coord = JvnCoordImpl.getCoordInstance();
        FakeServer writer = new FakeServer("writer-state");
        FakeServer otherServer = new FakeServer("other-state");
        int id = coord.jvnGetObjectId();
        JvnObject object = new JvnObjectImpl(id, "initial-state", null, true);
        String name = "TEST_UPDATE_STATE_" + id;

        coord.jvnRegisterObject(name, object, writer);
        coord.jvnUpdateObjectState(id, "state-saved-by-writer", writer);

        JvnObject lookedUp = coord.jvnLookupObject(name, otherServer);
        assertEquals("state-saved-by-writer", lookedUp.jvnGetObjectState(), "le writer courant doit pouvoir sauvegarder l'état");

        assertThrows(
            JvnException.class,
            () -> coord.jvnUpdateObjectState(id, "stale-state", otherServer),
            "un serveur qui n'est pas writer ne doit pas écraser l'état"
        );
    }

    /*
     * Scénario : le coordinateur sauvegarde son état durable sur disque,
     * puis on simule un redémarrage en supprimant l'instance singleton en mémoire.
     * Le nouveau coordinateur doit recharger le nom de l'objet, son dernier état connu
     * et le prochain identifiant à utiliser. Les verrous ne doivent pas être restaurés.
     */
    @Test
    void coordinatorReloadsDurableStateFromSnapshotAfterRestart() throws Exception {
        String previousSnapshotProperty = System.getProperty("jvn.coord.snapshot");
        System.setProperty("jvn.coord.snapshot", temporaryDirectory.resolve("coord.snapshot").toString());
        resetCoordinatorSingleton();

        try {
            JvnCoordImpl coord = JvnCoordImpl.getCoordInstance();
            FakeServer writerBeforeRestart = new FakeServer("writer-before-restart");
            int id = coord.jvnGetObjectId();
            String name = "TEST_COORD_RECOVERY_" + id;

            coord.jvnRegisterObject(name, new JvnObjectImpl(id, "initial-state", null, true), writerBeforeRestart);
            coord.jvnUpdateObjectState(id, "state-saved-before-restart", writerBeforeRestart);

            resetCoordinatorSingleton();
            JvnCoordImpl recoveredCoord = JvnCoordImpl.getCoordInstance();
            FakeServer readerAfterRestart = new FakeServer("reader-after-restart");

            JvnObject lookedUp = recoveredCoord.jvnLookupObject(name, readerAfterRestart);
            Serializable state = recoveredCoord.jvnLockRead(id, readerAfterRestart);
            int nextId = recoveredCoord.jvnGetObjectId();

            assertNotNull(lookedUp, "l'objet nommé doit être restauré après redémarrage");
            assertEquals(id, lookedUp.jvnGetObjectId(), "l'identifiant de l'objet doit être restauré");
            assertEquals("state-saved-before-restart", lookedUp.jvnGetObjectState(), "le dernier état connu doit être restauré");
            assertEquals("state-saved-before-restart", state, "un verrou lecture après redémarrage doit retourner l'état restauré");
            assertEquals(id + 1, nextId, "le coordinateur ne doit pas réutiliser un identifiant déjà attribué");
            assertEquals(0, writerBeforeRestart.invalidateWriterForReaderCount, "l'ancien writer RMI ne doit pas être restauré");
        } finally {
            resetCoordinatorSingleton();
            if (previousSnapshotProperty == null) {
                System.clearProperty("jvn.coord.snapshot");
            } else {
                System.setProperty("jvn.coord.snapshot", previousSnapshotProperty);
            }
        }
    }

    private static void resetCoordinatorSingleton() throws Exception {
        Field coordInstance = JvnCoordImpl.class.getDeclaredField("coordInstance");
        coordInstance.setAccessible(true);
        coordInstance.set(null, null);
    }

    private static class FakeServer extends UnicastRemoteObject implements JvnRemoteServer {
        private static final long serialVersionUID = 1L;

        private Serializable state;
        private int invalidateReaderCount;
        private int invalidateWriterCount;
        private int invalidateWriterForReaderCount;
        private boolean failOnInvalidateReader;
        private boolean failOnInvalidateWriter;
        private boolean failOnInvalidateWriterForReader;

        FakeServer(Serializable state) throws RemoteException {
            super();
            this.state = state;
        }

        public void jvnInvalidateReader(int joi) throws RemoteException, JvnException {
            invalidateReaderCount++;
            if (failOnInvalidateReader) {
                throw new RemoteException("client reader unreachable");
            }
        }

        public Serializable jvnInvalidateWriter(int joi) throws RemoteException, JvnException {
            invalidateWriterCount++;
            if (failOnInvalidateWriter) {
                throw new RemoteException("client writer unreachable");
            }
            return state;
        }

        public Serializable jvnInvalidateWriterForReader(int joi) throws RemoteException, JvnException {
            invalidateWriterForReaderCount++;
            if (failOnInvalidateWriterForReader) {
                throw new RemoteException("client writer unreachable");
            }
            return state;
        }
    }
}
