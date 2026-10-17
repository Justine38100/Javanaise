/***
 * JAVANAISE Implementation
 * JvnServerImpl class
 * Contact: 
 * 
 * Authors: 
 */

package jvn.core;

import java.rmi.server.UnicastRemoteObject;
import java.util.LinkedHashMap;
import java.rmi.RemoteException;
import java.util.Map;
//import java.util.concurrent.ConcurrentLinkedHashMap;
import java.io.*;



public class JvnServerImpl extends UnicastRemoteObject implements JvnLocalServer, JvnRemoteServer { 
	
	private static final long serialVersionUID = 1L;

	static {
		JvnRmiConfig.configureExportedHostIfProvided();
	}

	// A JVN server is managed as a singleton 
	private static volatile JvnServerImpl js = null;

	// Référence vers le coordinateur global.
	private JvnRemoteCoord jvnCoord;

	// Capacité maximale du cache avant déclenchement d'un flush
	private static final int CACHE_CAPACITY = 10;

	// Dictionnaire pour stocker les objets JVN avec leur identifiant comme clé.
	// Exemple : id = 1, JvnObject = "Hello World" (De type Sentence)
	// C'est le cache local des objets connus par le serveur.
	private final Map<Integer, JvnObject> jvnObjectsMap;

	/**
	 * Default constructor
	 * @throws JvnException
	 **/
	private JvnServerImpl() throws Exception {
		super();
        this.jvnCoord = lookupCoordinator();
        this.jvnCoord = (JvnRemoteCoord) java.rmi.Naming.lookup("rmi://localhost/Coordinator");
		this.jvnObjectsMap = new LinkedHashMap<Integer, JvnObject>(CACHE_CAPACITY, 0.75f, true);
        JvnEventBus.publish("serveur local connecté au coordinateur " + JvnRmiConfig.coordinatorUrl());
    }
	
	/**
	* Static method allowing an application to get a reference to 
	* a JVN server instance
	* @throws JvnException
	**/
	public static synchronized JvnServerImpl jvnGetServer() {
		if (js == null){
			try {
				js = new JvnServerImpl();
			} catch (Exception e) {
				return null;
			}
		}
		return js;
	}
	
	/**
	* The JVN service is not used anymore
	* @throws JvnException
	**/
	public void jvnTerminate() throws jvn.core.JvnException {
		callCoordinator(() -> {
			// Déconnexion du serveur auprès du coordinateur.
			jvnCoord.jvnTerminate(this);
			return null;
		}, "Erreur lors de la terminaison du serveur JVN");

		// Nettoyage des ressources locales
		jvnObjectsMap.clear();
		synchronized (JvnServerImpl.class) {
			if (js == this) {
				js = null;
			}
		}
	}

	/**
	* creation of a JVN object
	* @param o : the JVN object state
	* @throws JvnException
	**/
	public JvnObject jvnCreateObject(Serializable o) throws jvn.core.JvnException {
		try {
			// Demande au coordinateur un nouvel identifiant pour l'objet JVN
			int newId = callCoordinator(() -> jvnCoord.jvnGetObjectId(), "Erreur lors de la création d'un nouvel objet JVN");
			
			// Création d'un nouvel objet JVN avec l'état fourni et l'identifiant obtenu
			JvnObject newobject = new JvnObjectImpl(newId, o, this, true);

			// Ajout de l'objet JVN à la map locale : On vérifie la capacité et on insert de manière thread-safe
			synchronized (this) {
				evictIfFull();
				jvnObjectsMap.putIfAbsent(newId, newobject);
			}

			return newobject;
		} catch (JvnException je) {
			throw je;
		} catch (Exception e) {
			throw new jvn.core.JvnException("Erreur lors de la création d'un nouvel objet JVN", e);
		}
	}
	
	/**
	*  Associate a symbolic name with a JVN object
	* @param jon : the JVN object name
	* @param jo : the JVN object 
	* @throws JvnException
	**/
	public void jvnRegisterObject(String jon, JvnObject jo) throws jvn.core.JvnException {
		callCoordinator(() -> {
			// Enregistrement de l'objet JVN auprès du coordinateur.
			jvnCoord.jvnRegisterObject(jon, jo, this);
			return null;
		}, "Erreur lors de l'enregistrement de l'objet JVN");
	}
	
	/**
	* Provide the reference of a JVN object beeing given its symbolic name
	* @param jon : the JVN object name
	* @return the JVN object 
	* @throws JvnException
	**/
	public JvnObject jvnLookupObject(String jon) throws jvn.core.JvnException {
		try {
			JvnObjectImpl lookedUpObject = (JvnObjectImpl) callCoordinator(
				() -> jvnCoord.jvnLookupObject(jon, this),
				"Erreur lors de la recherche de l'objet JVN"
			);

			if (lookedUpObject == null) {
				return null;
			}

			int objectId = lookedUpObject.jvnGetObjectId();

			synchronized (this) {
				// Si l'objet est déjà présent dans le cache local, on le retourne directement
				if (jvnObjectsMap.containsKey(objectId)) {
					return jvnObjectsMap.get(objectId);
				}

				// Si le cache est plein, on effectue un flush pour libérer de l'espace
				evictIfFull();

				// Cet objet JVN reçu par lookup doit maintenant utiliser ce JvnServerImpl local.
				lookedUpObject.setJvnServer(this);
                
                JvnObject existingObject = jvnObjectsMap.putIfAbsent(lookedUpObject.jvnGetObjectId(), lookedUpObject);
                return existingObject == null ? lookedUpObject : existingObject;
            }
			
		} catch (JvnException je) {
			throw je;
		} catch (Exception e) {
			throw new jvn.core.JvnException("Erreur lors de la recherche de l'objet JVN", e);
		}
	}	
	
	/**
	* Get a Read lock on a JVN object 
	* @param joi : the JVN object identification
	* @return the current JVN object state
	* @throws  JvnException
	**/
	public Serializable jvnLockRead(int joi) throws JvnException {
        synchronized (this) {
            jvnObjectsMap.get(joi); // Marque l'objet comme accédé dans la LinkedHashMap
        }
			
        // Demande au coordinateur un verrou de lecture pour l'objet JVN identifié par joi
        return callCoordinator(() -> jvnCoord.jvnLockRead(joi, this), "Erreur lors de l'obtention d'un verrou de lecture sur l'objet JVN");
     }
	
	/**
	* Get a Write lock on a JVN object 
	* @param joi : the JVN object identification
	* @return the current JVN object state
	* @throws  JvnException
	**/
	public Serializable jvnLockWrite(int joi) throws JvnException {
        synchronized (this) {
            jvnObjectsMap.get(joi); // Marque l'objet comme accédé dans la LinkedHashMap
        }
        
        return callCoordinator(() -> jvnCoord.jvnLockWrite(joi, this), "Erreur lors de l'obtention d'un verrou d'écriture sur l'objet JVN");
    }

    /**
     * Sauvegarde auprès du coordinateur l'état courant d'un objet modifié localement.
     *
     * Spécification :
     * - cette méthode est appelée par le proxy après une méthode @JvnWrite réussie ;
     * - elle ne prend pas de verrou elle-même : le verrou écriture doit déjà être détenu ;
     * - elle transmet au coordinateur l'identifiant de l'objet, son état courant et ce serveur local ;
     * - elle permet au coordinateur de conserver une version récente sans attendre une invalidation.
     *
     * @param joi l'identifiant de l'objet JVN
     * @param state l'état courant à sauvegarder
     * @throws JvnException si la sauvegarde auprès du coordinateur échoue
     */
    public void jvnUpdateObjectState(int joi, Serializable state) throws JvnException {
        callCoordinator(() -> {
            // Relais local utilisé par le proxy : après une écriture réussie,
            // le serveur transmet l'état courant au coordinateur pour le sauvegarder.
            jvnCoord.jvnUpdateObjectState(joi, state, this);
            return null;
        }, "Erreur lors de la mise à jour de l'état de l'objet JVN");
    }

    /**
     * Exécute un appel vers le coordinateur avec une reconnexion simple.
     *
     * Spécification :
     * - le premier appel utilise la référence RMI courante ;
     * - si l'appel échoue avec une RemoteException, on suppose que le coordinateur
     *   est tombé ou a redémarré ;
     * - le serveur refait alors un lookup RMI du coordinateur ;
     * - après reconnexion, tous les verrous locaux sont remis à NL car le nouveau
     *   coordinateur ne connaît pas les anciens droits en cache ;
     * - l'appel est retenté une seule fois pour éviter une boucle infinie.
     *
     * @param call l'appel à exécuter auprès du coordinateur
     * @param errorMessage le message utilisé si l'appel échoue définitivement
     * @return le résultat de l'appel
     * @throws JvnException si le coordinateur reste indisponible ou si l'appel échoue
     */
    private <T> T callCoordinator(CoordinatorCall<T> call, String errorMessage) throws JvnException {
        try {
            return call.execute();
        } catch (Exception firstFailure) {
            if (!isCoordinatorFailure(firstFailure)) {
                throw new jvn.core.JvnException(errorMessage, firstFailure);
            }

            JvnEventBus.publish("coordinateur indisponible, tentative de reconnexion");

            try {
                reconnectCoordinatorAfterFailure();
                JvnEventBus.publish("reconnexion au coordinateur réussie");
                return call.execute();
            } catch (Exception secondFailure) {
                JvnEventBus.publish("reconnexion au coordinateur échouée : " + secondFailure.getMessage());
                throw new jvn.core.JvnException(errorMessage, secondFailure);
            }
        }
    }

    /**
     * Effectue un lookup RMI pour obtenir une référence vers le coordinateur.
     * @return la référence vers le coordinateur
     * @throws Exception si le lookup échoue
     */
    private JvnRemoteCoord lookupCoordinator() throws Exception {
        return (JvnRemoteCoord) java.rmi.Naming.lookup(JvnRmiConfig.coordinatorUrl());
    }

    /**
     * Reconnecte le serveur au coordinateur après une défaillance.
     * @throws Exception
     */
    private void reconnectCoordinatorAfterFailure() throws Exception {
        this.jvnCoord = lookupCoordinator();
        resetLocalLocksAfterCoordinatorRecovery();
        JvnEventBus.publish("verrous locaux remis à NL après redémarrage du coordinateur");
    }

    /**
     * Détermine si une exception est due à une défaillance du coordinateur ou à un problème de communication RMI.
     * @param exception
     * @return
     */
    private boolean isCoordinatorFailure(Exception exception) {
        Throwable current = exception;

        while (current != null) {
            if (current instanceof RemoteException) {
                return true;
            }
            current = current.getCause();
        }

        return false;
    }

    /**
     * Réinitialise les verrous locaux de tous les objets JVN après la récupération du coordinateur.
     */
    private void resetLocalLocksAfterCoordinatorRecovery() {
        for (JvnObject object : jvnObjectsMap.values()) {
            if (object instanceof JvnObjectImpl) {
                ((JvnObjectImpl) object).jvnResetLockAfterCoordinatorRecovery();
            }
        }
    }

    /**
     * Interface fonctionnelle représentant un appel vers le coordinateur.
     * @param <T>
     */
    private interface CoordinatorCall<T> {
        T execute() throws Exception;
    }

	/**
	* Invalidate the Read lock of the JVN object identified by id
	* called by the JvnCoord
	* @param joi : the JVN object id
	* @return void
	* @throws java.rmi.RemoteException,JvnException
	**/
  	public void jvnInvalidateReader(int joi) throws java.rmi.RemoteException,jvn.core.JvnException {
		try {
			// Récupération de l'objet JVN correspondant à l'identifiant joi
			JvnObject jvnObject;
			synchronized (this) {
				jvnObject = jvnObjectsMap.get(joi);
			}
			
			if (jvnObject != null) {
				// Invalidation du verrou de lecture pour l'objet JVN
				jvnObject.jvnInvalidateReader();
			}
		} catch (Exception e) {
			throw new jvn.core.JvnException("Erreur lors de l'invalidation du verrou de lecture sur l'objet JVN", e);
		}
	};
	    
	/**
	* Invalidate the Write lock of the JVN object identified by id 
	* @param joi : the JVN object id
	* @return the current JVN object state
	* @throws java.rmi.RemoteException,JvnException
	**/
	public Serializable jvnInvalidateWriter(int joi) throws java.rmi.RemoteException,jvn.core.JvnException { 
			try {
				// Récupération de l'objet JVN correspondant à l'identifiant joi
				JvnObject jvnObject;
				synchronized (this) {
					jvnObject = jvnObjectsMap.get(joi);
				}
				
				if (jvnObject != null) {
					// Invalidation du verrou d'écriture pour l'objet JVN et récupération de son état actuel
					return jvnObject.jvnInvalidateWriter();
				}
				return null;
			} catch (Exception e) {
				throw new jvn.core.JvnException("Erreur lors de l'invalidation du verrou d'écriture sur l'objet JVN", e);
			}
		};
	
	/**
	* Reduce the Write lock of the JVN object identified by id 
	* @param joi : the JVN object id
	* @return the current JVN object state
	* @throws java.rmi.RemoteException,JvnException
	**/
	public Serializable jvnInvalidateWriterForReader(int joi) throws java.rmi.RemoteException,jvn.core.JvnException { 
			try {
				// Récupération de l'objet JVN correspondant à l'identifiant joi
				JvnObject jvnObject;
				synchronized (this) {
					jvnObject = jvnObjectsMap.get(joi);
				}
				
				if (jvnObject != null) {
					// Réduction du verrou d'écriture pour l'objet JVN et récupération de son état actuel
					return jvnObject.jvnInvalidateWriterForReader();
				}
				return null;
			} catch (Exception e) {
				throw new jvn.core.JvnException("Erreur lors de la réduction du verrou d'écriture sur l'objet JVN", e);
			}
		};
	
	private synchronized void evictIfFull() throws JvnException {
		if (jvnObjectsMap.size() >= CACHE_CAPACITY) {
			Integer idToEvict = null;
			for (Map.Entry<Integer, JvnObject> entry : jvnObjectsMap.entrySet()) {
				// L'objet doit pouvoir être libéré (ex. s'il est en RC, WC ou NL)
				if (!(entry.getValue().isLockHeld())) {
					idToEvict = entry.getKey();
					break; // On prend le plus ancien candidat
				}
			}
			if (idToEvict != null) {
				jvnFlush(idToEvict);
			} else {
				throw new JvnException("Cache capacity reached: all cached objects are currently locked", null);
			}
		}
	}

	/**
	 * Flushe un objet spécifique du cache local
	 * @param joi : identifiant de l'objet JVN à flusher
	 * @throws JvnException	
	 **/
	public void jvnFlush(int joi) throws JvnException {
		JvnObject jo;
		synchronized (this) {
			jo = jvnObjectsMap.get(joi);
		}
		if (jo != null) {
			// Demander à l'objet de libérer ses verrous et synchroniser son état
			 jo.jvnFlush();
			
			// Supprimer l'objet de la table locale
			synchronized (this) {
				jvnObjectsMap.remove(joi);
			}
		}
	}

	/**
	 * Flushe l'ensemble des objets partagés présents dans le cache
	 * @throws JvnException
	 **/
	public void jvnFlushAll() throws JvnException {
		java.util.List<Integer> keys;
		synchronized (this) {
			// Copie des clés pour éviter les ConcurrentModificationException lors du remove
			keys = new java.util.ArrayList<Integer>(jvnObjectsMap.keySet());
		}
		for (Integer joi : keys) {
			jvnFlush(joi);
		}
	}
	
	public JvnRemoteCoord getCoord() {
		return jvnCoord;
	}

	public synchronized void touchObject(int joi) {
		jvnObjectsMap.get(joi); // Marque l'objet comme accédé dans la LinkedHashMap pour le LRU
	}
}

 
