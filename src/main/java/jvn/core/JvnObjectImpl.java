package jvn.core;
import java.io.Serializable;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.concurrent.locks.ReentrantLock;

public class JvnObjectImpl implements JvnObject {

    // Identifiant de l'objet partagé
    private final int objectId;

    // État de l'objet partagé dans la mémoire
    private Serializable objectState;
    
    // Etat du verrou local
    private enum LockState {
        NL, // No lock
        RC, // Read lock cached
        WC, // Write lock cached
        R, // Read lock taken
        W, // Write lock taken
        RWC // Write lock cached and read taken
    }

    private LockState lockState;

    private enum LockRequest {
        READ,
        WRITE
    }

    // Une seule acquisition réseau à la fois pour cet objet local.
    private LockRequest lockRequestInProgress;

    // Sérialise les sections critiques locales entre jvnLock... et jvnUnLock.
    private transient ReentrantLock invocationLock;
    private transient ThreadLocal<Boolean> invocationHeldByThread;

    // Référence vers le serveur local JvnServerImpl
    private transient JvnServerImpl jvnServer;

    public JvnObjectImpl(int objectId, Serializable objectState, JvnServerImpl jvnServer, boolean createdLocally) {
        this.objectId = objectId;
        this.objectState = objectState;
        this.jvnServer = jvnServer;
        this.lockState = createdLocally ? LockState.W : LockState.NL;
        initializeThreadCoordination();
    }

    @Override
    public void jvnLockRead() throws JvnException {
        acquireInvocationScope();
        try {
            synchronized (this) {
                awaitLockRequest();
                switch (lockState) {
                    case NL:
                        lockRequestInProgress = LockRequest.READ;
                        break;
                    case RC:
                        lockState = LockState.R;
                        return;
                    case WC:
                        lockState = LockState.RWC;
                        return;
                    case R:
                    case W:
                    case RWC:
                        return;
                }
            }

            requestReadLockFromCoordinator();
        } catch (JvnException e) {
            releaseInvocationScope();
            throw e;
        } catch (RuntimeException e) {
            finishFailedLockRequest();
            releaseInvocationScope();
            throw e;
        }
    }

    @Override
    public void jvnLockWrite() throws JvnException {
        acquireInvocationScope();
        try {
            synchronized (this) {
                awaitLockRequest();
                switch (lockState) {
                    case NL:
                    case RC:
                    case R:
                        lockRequestInProgress = LockRequest.WRITE;
                        break;
                    case WC:
                    case RWC:
                        lockState = LockState.W;
                        return;
                    case W:
                        return;
                }
            }

            requestWriteLockFromCoordinator();
        } catch (JvnException e) {
            releaseInvocationScope();
            throw e;
        } catch (RuntimeException e) {
            finishFailedLockRequest();
            releaseInvocationScope();
            throw e;
        }
    }

    private void requestReadLockFromCoordinator() throws JvnException {
        try {
            Serializable freshState = jvnServer.jvnLockRead(objectId);
            synchronized (this) {
                objectState = freshState;
                lockState = LockState.R;
                lockRequestInProgress = null;
                notifyAll();
            }
        } catch (JvnException e) {
            finishFailedLockRequest();
            throw e;
        }
    }

    private void requestWriteLockFromCoordinator() throws JvnException {
        try {
            Serializable freshState = jvnServer.jvnLockWrite(objectId);
            synchronized (this) {
                objectState = freshState;
                lockState = LockState.W;
                lockRequestInProgress = null;
                notifyAll();
            }
        } catch (JvnException e) {
            finishFailedLockRequest();
            throw e;
        }
    }

    private void finishFailedLockRequest() {
        synchronized (this) {
            lockRequestInProgress = null;
            notifyAll();
        }
    }

    private void awaitLockRequest() throws JvnException {
        while (lockRequestInProgress != null) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JvnException("Interrupted while waiting for a lock request", e);
            }
        }
    }

    private void acquireInvocationScope() {
        if (!invocationHeldByThread.get()) {
            invocationLock.lock();
            invocationHeldByThread.set(true);
        }
    }

    private void releaseInvocationScope() {
        if (invocationHeldByThread.get()) {
            invocationHeldByThread.set(false);
            invocationLock.unlock();
        }
    }

    @Override
    public synchronized void jvnUnLock() throws JvnException {
        if (!invocationHeldByThread.get() && invocationLock.isLocked()) {
            throw new JvnException("The current thread does not own the local object lock", null);
        }

        switch(lockState) {
            case R:
                // On lâche le verrou de lecture.
                // On passe R en cache.
                lockState = LockState.RC;
                break;

            case W:
                // On lâche le verrou d'écriture.
                // On passe W en cache.
                lockState = LockState.WC;
                break;

            case RWC:
                // On lâche le verrou de lecture.
                // Mais on a toujours W en cache.
                lockState = LockState.WC;
                break;

            case NL:
            case RC:
            case WC:
                // On n'a pas de verrou à libérer.
                break;
        }
        notifyAll(); // On veut réveiller les threads RMI qui attendent de rétrograder les droits.
        releaseInvocationScope();
    }

    @Override
    public int jvnGetObjectId() throws JvnException {
        return objectId;
    }

    @Override
    public synchronized Serializable jvnGetObjectState() throws JvnException {
        return objectState;
    }

    @Override
    public synchronized void jvnInvalidateReader() throws JvnException {
        try {
            // Une demande READ déjà acceptée doit d'abord appliquer sa réponse locale.
            // Pour WRITE, le callback peut être provoqué par cette demande elle-même :
            // on invalide alors la lecture pour permettre au coordinateur d'accorder l'écriture.
            while (lockRequestInProgress == LockRequest.READ) {
                wait();
            }
            while (lockState == LockState.R) {
                wait(); // On attend que le verrou de lecture soit libéré
            }
            if (lockState == LockState.RC) {
                lockState = LockState.NL;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JvnException("Interrupted while invalidating reader for a writer", e);
        }
    }

    @Override
    public synchronized Serializable jvnInvalidateWriter() throws JvnException {
        try {
            while (lockRequestInProgress != null || lockState == LockState.W || lockState == LockState.RWC) {
                wait(); // On attend que le verrou d'écriture soit libéré
            }
            if (lockState == LockState.WC ) {
                lockState = LockState.NL;
            }
            return objectState;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JvnException("Interrupted while invalidating writer for another writer", e);
        }

    }

    @Override
    public synchronized Serializable jvnInvalidateWriterForReader() throws JvnException {
        try {
            while (lockRequestInProgress != null || lockState == LockState.W) {
                wait(); // On attend que le verrou d'écriture soit libéré
            }
            if (lockState == LockState.WC) {
                // On veut garder le verrou en lecture car perdre les droits d'écriture ne nous empêche pas de lire.
                lockState = LockState.RC;
            }
            if (lockState == LockState.RWC) {
                // Comme c'est pour un autre lecteur, on peut garder le verrou en lecture qu'on avait.
                lockState = LockState.R;
            }
            return objectState;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JvnException("Interrupted while invalidating writer for another reader", e);
        }
    }

    public void setJvnServer(JvnServerImpl jvnServer) {
        this.jvnServer = jvnServer;
    }

    private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
        input.defaultReadObject();
        initializeThreadCoordination();
    }

    private void initializeThreadCoordination() {
        invocationLock = new ReentrantLock(true);
        invocationHeldByThread = ThreadLocal.withInitial(() -> Boolean.FALSE);
    }

    /**
     * Réinitialise le verrou local après une reconnexion au coordinateur.
     *
     * Spécification :
     * - cette méthode est appelée quand le serveur local détecte que le coordinateur
     *   a probablement redémarré ;
     * - les verrous locaux en cache ne sont plus fiables, car le nouveau coordinateur
     *   ne connaît pas les anciens readers/writers ;
     * - l'état métier de l'objet est conservé, mais le prochain accès devra redemander
     *   un verrou propre au coordinateur.
     */
    public synchronized void jvnResetLockAfterCoordinatorRecovery() {
        lockState = LockState.NL;
        notifyAll();
    }
}
