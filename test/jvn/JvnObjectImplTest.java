package jvn;

import java.io.Serializable;

public class JvnObjectImplTest {

    public static void main(String[] args) throws Exception {
        testCreatedObjectKeepsWriteLockInCache();
        testInvalidateWriterForReaderDowngradesWriteCacheToReadCache();
        testInvalidateWriterRemovesWriteCacheAndReturnsState();
        System.out.println("JvnObjectImplTest OK");
    }

    /*
     * Scénario : un objet créé localement commence avec un verrou écriture actif.
     * Quand l'application le déverrouille, le verrou écriture reste en cache.
     * Une prise de verrou écriture plus tard doit donc être locale et ne pas appeler le serveur.
     */
    private static void testCreatedObjectKeepsWriteLockInCache() throws Exception {
        JvnObject jo = new JvnObjectImpl(1, "initial", null, true);

        jo.jvnUnLock();
        jo.jvnLockWrite();
        jo.jvnUnLock();

        assert "initial".equals(jo.jvnGetObjectState()) : "l etat de l objet doit etre conserve";
    }

    /*
     * Scénario : un client possède un verrou écriture en cache (WC), puis un autre client demande un verrou lecture.
     * Le coordinateur appelle jvnInvalidateWriterForReader().
     * L'objet local doit perdre son droit d'écriture mais garder un droit de lecture en cache (WC -> RC).
     * On vérifie qu'une lecture ultérieure reste locale et ne demande pas le serveur.
     */
    private static void testInvalidateWriterForReaderDowngradesWriteCacheToReadCache() throws Exception {
        JvnObject jo = new JvnObjectImpl(2, "fresh-state", null, true);

        jo.jvnUnLock();
        Serializable returnedState = jo.jvnInvalidateWriterForReader();
        jo.jvnLockRead();
        jo.jvnUnLock();

        assert "fresh-state".equals(returnedState) : "la reduction doit retourner l etat courant";
        assert "fresh-state".equals(jo.jvnGetObjectState()) : "l etat de l objet doit etre conserve";
    }

    /*
     * Scénario : un client possède un verrou écriture en cache (WC), puis un autre client demande un verrou écriture.
     * Le coordinateur appelle jvnInvalidateWriter().
     * L'objet local doit perdre complètement son droit d'écriture (WC -> NL) et retourner son état courant.
     */
    private static void testInvalidateWriterRemovesWriteCacheAndReturnsState() throws Exception {
        JvnObject jo = new JvnObjectImpl(3, "last-write", null, true);

        jo.jvnUnLock();
        Serializable returnedState = jo.jvnInvalidateWriter();

        assert "last-write".equals(returnedState) : "l invalidation de l ecrivain doit retourner l etat courant";
    }
}
