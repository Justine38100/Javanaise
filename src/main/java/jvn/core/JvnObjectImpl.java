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

    // état javanaise de l'objet local
    // Donne l'info des droits accordés au client.
    private LockState lockState;

    private enum LockRequest {
        READ,
        WRITE
    }

    // Une seule acquisition réseau à la fois pour cet objet local.
    // état possible : null, soit READ, soit WRITE. 
    // Il indique qu’une demande de verrouillage est en cours vers le coordinateur.
    // Nécessaire pour les callback RMI du coordinateur.
    private LockRequest lockRequestInProgress;

    // Sérialise les sections critiques locales entre jvnLock... et jvnUnLock.

    // Chaque objet local possède sont propre invocationLock
    // Les méthodes jvnLockRead() et jvnLockWrite() le prennent au début de l’accès. 
    // Il reste pris pendant que le thread utilise l’objet et jvnUnLock() le libère.
    private transient ReentrantLock invocationLock;

    // Indicateur séparé pour chaque thread. Il mémorise si le thread courant a pris invocationLock.
    // Utilisé pour éviter qu’un thread libère le verrou d’un autre thread et pour gérer les appels réentrants du même thread.
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
        // On essaie d'acquérir le verrou d'invocation pour le thread courant. 
        // Si le thread a déjà acquis ce verrou, cette méthode ne fait rien.
        // Si un autre thread a déjà acquis le verrou, le thread courant attend que le verrou soit libéré.
        acquireInvocationScope();
        try {
            // Si 
            if (jvnServer != null) {
                jvnServer.touchObject(objectId, this);
            }
            // Cette synchronisation est nécessaire pour les modifications internes
            // qui surviennent dans les callbacks RMI du coordinateur (ex : jvnInvalidateReader(), jvnInvalidateWriter(), jvnInvalidateWriterForReader()).
            // ou pour les autres threads qui appellent jvnLockRead() ou jvnLockWrite() sur le même objet local.
            synchronized (this) {

                // On regarde si un thread est déjà en train de demander un verrou au coordinateur. 
                // Si c'est le cas, on attend que cette demande se termine avant de continuer.
                awaitLockRequest();

                switch (lockState) {
                    case NL:
                        lockRequestInProgress = LockRequest.READ;
                        // On doit passer par le coordinateur pour obtenir le verrou de lecture.
                        // On ne veut pas retourner avant d'avoir obtenu le verrou de lecture.
                        break;
                    case RC:
                        lockState = LockState.R;
                        // Pas besoin de passer par le coordinateur, on peut directement lire.
                        // => on retourne sans faire de requête réseau.
                        return;
                    case WC:
                        lockState = LockState.RWC;
                        // Pas besoin de passer par le coordinateur, on peut directement lire.
                        // => on retourne sans faire de requête réseau.
                        return;
                    case R:
                    case W:
                    case RWC:
                        // Pas besoin de passer par le coordinateur, on peut directement lire.
                        // => on retourne sans faire de requête réseau.
                        return;
                }
            }
            // Pour faire l'appel réseau, on sort du synchronized(this) pour ne pas bloquer les autres threads qui veulent accéder à l'objet local.
            requestReadLockFromCoordinator();

        } catch (JvnException e) {
            // Si la demande de verrouillage échoue, on réinitialise lockRequestInProgress à null et on notifie les threads en attente.
            releaseInvocationScope();

            throw e;
        } catch (RuntimeException e) {
            // Si la demande de verrouillage échoue, on réinitialise lockRequestInProgress à null et on notifie les threads en attente.
            // On besoin de faire finishFailedLockRequest() ici car requestWriteLockFromCoordinator() ne l'a pas fait.
            // Car l'exception est levée avant l'appel à requestWriteLockFromCoordinator().
            finishFailedLockRequest();

            // On libère le verrou d'invocation pour le thread courant.
            releaseInvocationScope();
            throw e;
        }
    }

    @Override
    public void jvnLockWrite() throws JvnException {
        // Idem que pour jvnLockRead() : on essaie d'acquérir le verrou d'invocation pour le thread courant.
        acquireInvocationScope();
        try {
            if (jvnServer != null) {
                jvnServer.touchObject(objectId, this);
            }

            // Cette synchronisation est nécessaire pour les modifications internes
            // qui surviennent dans les callbacks RMI du coordinateur (ex : jvnInvalidateReader(), jvnInvalidateWriter(), jvnInvalidateWriterForReader()).
            // ou pour les autres threads qui appellent jvnLockRead() ou jvnLockWrite() sur le même objet local.
            synchronized (this) {

                // On regarde si un thread est déjà en train de demander un verrou au coordinateur. 
                // Si c'est le cas, on attend que cette demande se termine avant de continuer.
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
            // Pour faire l'appel réseau, on sort du synchronized(this) pour ne pas bloquer les autres threads qui veulent accéder à l'objet local.
            requestWriteLockFromCoordinator();

        } catch (JvnException e) {
            // Si la demande de verrouillage échoue, on réinitialise lockRequestInProgress à null et on notifie les threads en attente.
            releaseInvocationScope();
            // On ne fait pas de finishFailedLockRequest() ici car requestWriteLockFromCoordinator() le fait déjà en cas d'échec.
            throw e;

        } catch (RuntimeException e) {
            // Si la demande de verrouillage échoue, on réinitialise lockRequestInProgress à null et on notifie les threads en attente.
            // On besoin de faire finishFailedLockRequest() ici car requestWriteLockFromCoordinator() ne l'a pas fait.
            // Car l'exception est levée avant l'appel à requestWriteLockFromCoordinator().
            finishFailedLockRequest();

            // On libère le verrou d'invocation pour le thread courant.
            releaseInvocationScope();

            throw e;
        }
    }

    /**
     * Demande un verrou de lecture au coordinateur et met à jour l'état local de l'objet.
     * Logique de la méthode :
     * 1. Appelle jvnLockRead() sur le serveur local pour obtenir un verrou de lecture du coordinateur.
     * 2. Met à jour l'état local de l'objet avec l'état renvoyé par le coordinateur.
     * 3. Met à jour l'état du verrou local à R (lecture).
     * 4. Réinitialise lockRequestInProgress à null et notifie tous les threads en attente.
     * @throws JvnException
     */
    private void requestReadLockFromCoordinator() throws JvnException {
        try {
            // On demande un verrou de lecture au coordinateur.
            Serializable freshState = jvnServer.jvnLockRead(objectId);

            // On reprend la synchronisation pour protéger l'accès à l'état local de l'objet et à lockRequestInProgress.
            synchronized (this) {
                objectState = freshState;
                lockState = LockState.R;
                lockRequestInProgress = null;
                // On notifie les threads qui attendent que lockRequestInProgress soit null pour continuer.
                notifyAll();
            }
        } catch (JvnException e) {
            // Si la demande de verrouillage échoue, on réinitialise lockRequestInProgress à null et on notifie les threads en attente.
            finishFailedLockRequest();
            throw e;
        }
    }

    /**
     * Demande un verrou d'écriture au coordinateur et met à jour l'état local de l'objet.
     * Logique de la méthode :
     * 1. Appelle jvnLockWrite() sur le serveur local pour obtenir un verrou d'écriture du coordinateur.
     * 2. Met à jour l'état local de l'objet avec l'état renvoyé par le coordinateur.
     * 3. Met à jour l'état du verrou local à W (écriture).
     * 4. Réinitialise lockRequestInProgress à null et notifie tous les threads en attente.
     * @throws JvnException
     */
    private void requestWriteLockFromCoordinator() throws JvnException {
        try {
            // On demande un verrou d'écriture au coordinateur.
            Serializable freshState = jvnServer.jvnLockWrite(objectId);

            // On reprend la synchronisation pour protéger l'accès à l'état local de l'objet et à lockRequestInProgress.
            synchronized (this) {
                objectState = freshState;
                lockState = LockState.W;
                lockRequestInProgress = null;
                // On notifie les threads qui attendent que lockRequestInProgress soit null pour continuer.
                notifyAll();
            }
        } catch (JvnException e) {
            // Si la demande de verrouillage échoue, on réinitialise lockRequestInProgress à null et on notifie les threads en attente.
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

    /**
     * Attend que lockRequestInProgress soit null, ce qui signifie qu'aucune demande de verrouillage n'est en cours.
     * 
     * Rappel : lockRequestInProgress est différent de null si un thread a demandé un verrou de lecture ou d'écriture au coordinateur et attend la réponse.
     * @throws JvnException
     */
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

    /**
     * Acquiert le verrou d'invocation pour le thread courant si ce n'est pas déjà fait.
     */
    private void acquireInvocationScope() {
        // Si le thread courant n'a pas encore acquis le verrou d'invocation, on le prend.
        if (!invocationHeldByThread.get()) {
            // On prend le verrou d'invocation pour le thread courant.
            invocationLock.lock();
            // On marque que le thread courant a acquis le verrou d'invocation.
            invocationHeldByThread.set(true);
        }
    }

    /**
     * Libère le verrou d'invocation pour le thread courant si c'est lui qui l'a acquis. 
     * Sinon, ne fait rien.
     */
    private void releaseInvocationScope() {
        // Si le thread courant a acquis le verrou d'invocation, on le libère.
        if (invocationHeldByThread.get()) {
            // On libère le verrou d'invocation pour le thread courant.
            invocationHeldByThread.set(false);
            // On libère le verrou d'invocation pour le thread courant.
            invocationLock.unlock();
        }
    }

    @Override
    public synchronized void jvnUnLock() throws JvnException {
        // On vérifie que le thread courant a bien acquis le verrou d'invocation avant de libérer le verrou local.
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
        // On veut réveiller les threads qui attendent.
        notifyAll();
        
        // On libère le verrou d'invocation pour le thread courant.
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
            // Si un thread est en train de modifier l'état local, on attend.
            // Si l'objet est en écriture ou lecture + écriture, on attend que le verrou soit libéré.
            while (lockRequestInProgress != null || lockState == LockState.W || lockState == LockState.RWC) {
                wait(); // On attend que le verrou d'écriture soit libéré
            }
            // Si l'objet est en écriture en cache, on le passe en NL.
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

    /**
     * Méthode appelée lors de la désérialisation de l'objet local.
     * Elle permet de réinitialiser les structures de coordination des threads qui ne sont pas sérialisables.
     * @param input l'ObjectInputStream utilisé pour la désérialisation
     * @throws IOException
     * @throws ClassNotFoundException
     */
    private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
        input.defaultReadObject();
        initializeThreadCoordination();
    }

    /**
     * Initialise les structures de coordination des threads pour cet objet local.
     */
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

    /**
     * Flush l'état local de l'objet vers le coordinateur.
     * 
     * Logique de la méthode :
     * 1- La méthode vérifie que le thread courant n’utilise pas déjà cet objet. Si c’est le cas, elle lève une exception : sinon, le thread pourrait attendre le déverrouillage de sa propre section et se bloquer lui-même.
     * 2- Elle prend invocationLock. Si un autre thread utilise l’objet, le flush attend son tour.
     * 3- Elle examine lockState sous synchronized (this).
     * 4- Pour RC, elle informe le coordinateur qu’il libère son droit de lecture.
     * 5- Pour WC, elle envoie également l’état métier local, car il peut contenir une écriture récente.
     * 6- L’appel réseau est fait sans tenir le moniteur this, mais invocationLock reste détenu : aucun autre thread local ne peut commencer à utiliser l’objet pendant ce temps.
     * 7- Après le succès, l’état local passe à NL.
        Le finally libère toujours invocationLock, même si le réseau ou le coordinateur renvoie une erreur.
     * @throws JvnException si le flush échoue
     */
    public void jvnFlush() throws JvnException {
        Serializable stateToSend = null;
        boolean needsFlush = false;

        // 1
        if (invocationHeldByThread.get()) {
            throw new JvnException("Cannot flush an object while the current thread is using it", null);
        }

        // 2
        acquireInvocationScope();


        try {
            synchronized (this) {
                // Si un autre thread est en train de demander un verrou au coordinateur, on ne peut pas flush l'objet.
                if (lockRequestInProgress != null) {
                    throw new JvnException("Cannot flush an object while a lock request is in progress", null);
                }

                // 3
                if (lockState == LockState.RC) {
                    // 4
                    needsFlush = true;
                } else if (lockState == LockState.WC) {
                    // 5
                    needsFlush = true;
                    stateToSend = objectState;
                }
            }

            // 6
            if (needsFlush) {
                // Appel réseau hors du moniteur, mais le verrou d'invocation empêche
                // un autre thread local d'utiliser l'objet pendant le flush.
                jvnServer.jvnFlushObjectState(objectId, stateToSend);

                synchronized (this) {
                    lockState = LockState.NL;
                    notifyAll();
                }
            }
        } finally {
            // 7
            releaseInvocationScope();
        }
    }

    /**
     * Vérifie si un verrou est détenu sur l'objet JVN
     */
    public synchronized boolean isLockHeld() {
        return lockState == LockState.R || lockState == LockState.W || lockState == LockState.RWC;
    }
}
