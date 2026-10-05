package jvn.units;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.Serializable;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

import org.junit.jupiter.api.Test;

import jvn.core.JvnCoordImpl;
import jvn.core.JvnException;
import jvn.core.JvnObject;
import jvn.core.JvnObjectImpl;
import jvn.core.JvnRemoteServer;

class JvnCoordImplTest {

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

    private static class FakeServer extends UnicastRemoteObject implements JvnRemoteServer {
        private static final long serialVersionUID = 1L;

        private Serializable state;
        private int invalidateReaderCount;
        private int invalidateWriterCount;
        private int invalidateWriterForReaderCount;

        FakeServer(Serializable state) throws RemoteException {
            super();
            this.state = state;
        }

        public void jvnInvalidateReader(int joi) throws RemoteException, JvnException {
            invalidateReaderCount++;
        }

        public Serializable jvnInvalidateWriter(int joi) throws RemoteException, JvnException {
            invalidateWriterCount++;
            return state;
        }

        public Serializable jvnInvalidateWriterForReader(int joi) throws RemoteException, JvnException {
            invalidateWriterForReaderCount++;
            return state;
        }
    }
}
