package jvn.units;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.Serializable;

import org.junit.jupiter.api.Test;

import jvn.core.JvnObject;
import jvn.core.JvnObjectImpl;

class JvnObjectImplTest {

    /*
     * Scénario : un objet créé localement commence avec un verrou écriture actif.
     * Quand l'application le déverrouille, le verrou écriture reste en cache.
     * Une prise de verrou écriture plus tard doit donc être locale et ne pas appeler le serveur.
     */
    @Test
    void createdObjectKeepsWriteLockInCache() throws Exception {
        JvnObject jo = new JvnObjectImpl(1, "initial", null, true);

        jo.jvnUnLock();
        jo.jvnLockWrite();
        jo.jvnUnLock();

        assertEquals("initial", jo.jvnGetObjectState(), "l'état de l'objet doit être conservé");
    }

    /*
     * Scénario : un client possède un verrou écriture en cache (WC), puis un autre client demande un verrou lecture.
     * Le coordinateur appelle jvnInvalidateWriterForReader().
     * L'objet local doit perdre son droit d'écriture mais garder un droit de lecture en cache (WC -> RC).
     * On vérifie qu'une lecture ultérieure reste locale et ne demande pas le serveur.
     */
    @Test
    void invalidateWriterForReaderDowngradesWriteCacheToReadCache() throws Exception {
        JvnObject jo = new JvnObjectImpl(2, "fresh-state", null, true);

        jo.jvnUnLock();
        Serializable returnedState = jo.jvnInvalidateWriterForReader();
        jo.jvnLockRead();
        jo.jvnUnLock();

        assertEquals("fresh-state", returnedState, "la réduction doit retourner l'état courant");
        assertEquals("fresh-state", jo.jvnGetObjectState(), "l'état de l'objet doit être conservé");
    }

    /*
     * Scénario : un client possède un verrou écriture en cache (WC), puis un autre client demande un verrou écriture.
     * Le coordinateur appelle jvnInvalidateWriter().
     * L'objet local doit perdre complètement son droit d'écriture (WC -> NL) et retourner son état courant.
     */
    @Test
    void invalidateWriterRemovesWriteCacheAndReturnsState() throws Exception {
        JvnObject jo = new JvnObjectImpl(3, "last-write", null, true);

        jo.jvnUnLock();
        Serializable returnedState = jo.jvnInvalidateWriter();

        assertEquals("last-write", returnedState, "l'invalidation de l'écrivain doit retourner l'état courant");
    }
}
