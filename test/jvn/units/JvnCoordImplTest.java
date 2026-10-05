package jvn.units;

import java.io.Serializable;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

import jvn.core.JvnCoordImpl;
import jvn.core.JvnException;
import jvn.core.JvnObject;
import jvn.core.JvnObjectImpl;
import jvn.core.JvnRemoteServer;

public class JvnCoordImplTest {

    public static void main(String[] args) throws Exception {
        testObjectIdsAreUniqueAndIncreasing();
        testRegisterThenLookupReturnsObjectWithSameIdAndState();
        testReadLockDowngradesExistingWriter();
        testWriteLockInvalidatesReadersAndWriter();
        System.out.println("JvnCoordImplTest OK");
        System.exit(0);
    }

    /*
     * Scénario : plusieurs objets sont créés.
     * Le coordinateur doit attribuer des identifiants uniques et croissants.
     */
    private static void testObjectIdsAreUniqueAndIncreasing() throws Exception {
        JvnCoordImpl coord = JvnCoordImpl.getCoordInstance();

        int first = coord.jvnGetObjectId();
        int second = coord.jvnGetObjectId();

        assert second == first + 1 : "les ids des objets doivent etre croissants";
    }

    /*
     * Scénario : un serveur enregistre un nom symbolique pour un objet.
     * Un lookup avec le même nom doit retourner un objet non null avec le même id et le même état.
     */
    private static void testRegisterThenLookupReturnsObjectWithSameIdAndState() throws Exception {
        JvnCoordImpl coord = JvnCoordImpl.getCoordInstance();
        FakeServer owner = new FakeServer("owner-state");
        int id = coord.jvnGetObjectId();
        JvnObject object = new JvnObjectImpl(id, "registered-state", null, true);
        String name = "TEST_REGISTER_" + id;

        coord.jvnRegisterObject(name, object, owner);
        JvnObject lookedUp = coord.jvnLookupObject(name, new FakeServer("reader-state"));

        assert lookedUp != null : "le lookup doit retourner l objet enregistre";
        assert lookedUp.jvnGetObjectId() == id : "le lookup doit conserver le meme id objet";
        assert "registered-state".equals(lookedUp.jvnGetObjectState()) : "le lookup doit retourner le dernier etat connu";
    }

    /*
     * Scénario : le serveur A possède le verrou en écriture pour un objet.
     * Le serveur B demande un verrou en lecture.
     * Le coordinateur doit appeler invalidateWriterForReader sur A, stocker l'état retourné par A,
     * transformer A en lecteur, ajouter B comme lecteur, puis retourner le nouvel état à B.
     */
    private static void testReadLockDowngradesExistingWriter() throws Exception {
        JvnCoordImpl coord = JvnCoordImpl.getCoordInstance();
        FakeServer writer = new FakeServer("state-from-writer");
        FakeServer reader = new FakeServer("unused-reader-state");
        int id = coord.jvnGetObjectId();
        JvnObject object = new JvnObjectImpl(id, "old-state", null, true);
        String name = "TEST_READ_" + id;

        coord.jvnRegisterObject(name, object, writer);
        Serializable state = coord.jvnLockRead(id, reader);

        assert writer.invalidateWriterForReaderCount == 1 : "l ecrivain doit etre reduit pour permettre la lecture";
        assert "state-from-writer".equals(state) : "le verrou lecture doit retourner l etat frais de l ecrivain";
    }

    /*
     * Scénario : le serveur A possède le verrou écriture, puis le serveur B obtient un verrou lecture,
     * puis le serveur C demande un verrou écriture.
     * Le coordinateur doit invalider les lecteurs courants,
     * puis faire de C le seul écrivain et retourner le dernier état connu.
     */
    private static void testWriteLockInvalidatesReadersAndWriter() throws Exception {
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

        assert firstWriter.invalidateWriterForReaderCount == 1 : "le premier ecrivain doit d abord etre reduit pour le lecteur";
        assert firstWriter.invalidateReaderCount == 1 : "l ecrivain reduit doit ensuite etre invalide comme lecteur";
        assert reader.invalidateReaderCount == 1 : "le lecteur doit etre invalide avant le verrou ecriture";
        assert "state-after-first-writer".equals(state) : "le verrou ecriture doit retourner le dernier etat connu";
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
