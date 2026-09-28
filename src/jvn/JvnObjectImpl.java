package jvn;
import java.io.Serializable;
import java.util.concurrent.locks.Lock;

public class JvnObjectImpl implements JvnObject {

    // Identifiant de l'objet partagé
    private int objectId;

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

    // Référence vers le serveur local JvnServerImpl
    private JvnServerImpl jvnServer;

    public JvnObjectImpl(int objectId, Serializable objectState, JvnServerImpl jvnServer) {
        this.objectId = objectId;
        this.objectState = objectState;
        this.jvnServer = jvnServer;
        this.lockState = LockState.NL;
        // @TODO: Réfléchir si c'est bien à NL qu'il faut l'initialiser
    }

    @Override
    public synchronized void jvnLockRead() throws JvnException {
        switch(lockState) {
            case NL:
                // On prend le verrou de lecture auprès du serveur
                this.objectState = jvnServer.jvnLockRead(objectId);
                // Une fois qu'on a le verrou, on dit qu'on le possède.
                lockState = LockState.R;
                break;

            case RC:
                lockState = LockState.R;
                break;

            case WC:
                lockState = LockState.RWC;
                break;

            case R:
            case W:
            case RWC:
                // On a déjà un verrou qui permet de lire.
                break;
        }
    }

    @Override
    public synchronized void jvnLockWrite() throws JvnException {
        switch(lockState) {
            case NL:
            case RC:
            case R:
                // Pour nous, on doit forcément passer de R à RC avant de pouvoir passer à W.
                // On prend le verrou d'écriture auprès du serveur
                this.objectState = jvnServer.jvnLockWrite(objectId);
                lockState = LockState.W;
                break;

            case WC:
            case RWC:
                lockState = LockState.W;
                break;

            case W:
                // On a déjà un verrou qui permet d'écrire.
                break;
        }
    }

    @Override
    public synchronized void jvnUnLock() throws JvnException {
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
    }

    @Override
    public int jvnGetObjectId() throws JvnException {
        return objectId;
    }

    @Override
    public Serializable jvnGetObjectState() throws JvnException {
        return objectState;
    }

    @Override
    public synchronized void jvnInvalidateReader() throws JvnException {
        try {
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
            while (lockState == LockState.W || lockState == LockState.RWC) {
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
            while (lockState == LockState.W) {
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
}
