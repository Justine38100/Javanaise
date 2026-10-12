/***
 * JAVANAISE Implementation
 * JvnServerImpl class
 * Contact:  
 *
 * Authors: 
 */

package jvn.core;
import java.rmi.Naming;
import java.rmi.RemoteException;

import java.rmi.server.UnicastRemoteObject;
import java.io.Serializable;
import java.util.Map;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;


public class JvnCoordImpl extends UnicastRemoteObject implements JvnRemoteCoord {

	private static final long serialVersionUID = 1L;

  // Singleton du coordinateur
  private static JvnCoordImpl coordInstance = null;

  // Compteur pour donner les identifiants uniques aux objets JVN
  private int nextObjectId = 0;

  // Dictionnaire pour stocker les noms des objets JVN avec leur identifiant comme clé.
  private Map<String, Integer> nameToIdMap;

  // Pour chaque objet, on veut stocker son dernier état connu (Serializable) pour pouvoir le renvoyer aux serveurs qui demandent un verrou.
  private Map<Integer, Serializable> objectStates;

  // Pour chaque objet, on veut stocker la liste des serveurs qui ont un verrou de lecture sur cet objet.
  private Map<Integer, HashSet<JvnRemoteServer>> readersMap;

  // Pour chaque objet, on veut stocker la liste des serveurs qui ont un verrou d'écriture sur cet objet.
  private Map<Integer, JvnRemoteServer> writersMap;

  /**
  * Default constructor
  * @throws JvnException
  **/
	private JvnCoordImpl() throws Exception {
    super();
    nextObjectId = 0;
    this.nameToIdMap = new HashMap<String, Integer>();
    this.objectStates = new HashMap<Integer, Serializable>();
    this.readersMap = new HashMap<Integer, HashSet<JvnRemoteServer>>();
    this.writersMap = new HashMap<Integer, JvnRemoteServer>();
	}

  public static JvnCoordImpl getCoordInstance() throws Exception {
    if (coordInstance == null) {
      coordInstance = new JvnCoordImpl();
    }
    return coordInstance;
  }

  /**
  *  Allocate a NEW JVN object id (usually allocated to a 
  *  newly created JVN object)
  * @throws java.rmi.RemoteException,JvnException
  **/
  public synchronized int jvnGetObjectId() throws java.rmi.RemoteException,jvn.core.JvnException {
    int idTmp = nextObjectId;
    nextObjectId++;
    return idTmp;
  }
  
  /**
  * Associate a symbolic name with a JVN object
  * @param jon : the JVN object name
  * @param jo  : the JVN object 
  * @param js  : the remote reference of the JVNServer
  * @throws java.rmi.RemoteException,JvnException
  **/
  public void jvnRegisterObject(String jon, JvnObject jo, JvnRemoteServer js) throws java.rmi.RemoteException,jvn.core.JvnException{
    int objectId = jo.jvnGetObjectId();
    nameToIdMap.put(jon, objectId);
    objectStates.put(objectId, jo.jvnGetObjectState());

    // On initialise le serveur qui a le verrou d'écriture pour cet objet
    writersMap.put(objectId, js);

    // On initialise la liste des lecteurs pour cet objet
    readersMap.put(objectId, new HashSet<JvnRemoteServer>());
  }
  
  /**
  * Get the reference of a JVN object managed by a given JVN server 
  * @param jon : the JVN object name
  * @param js : the remote reference of the JVNServer
  * @throws java.rmi.RemoteException,JvnException
  **/
  public JvnObject jvnLookupObject(String jon, JvnRemoteServer js) throws java.rmi.RemoteException,jvn.core.JvnException{
    Integer objectId = nameToIdMap.get(jon);

    if (objectId == null) {
      return null; // L'objet n'existe pas
    }

    // On récupère l'état actuel de l'objet
    Serializable objectState = objectStates.get(objectId);

    return new JvnObjectImpl(objectId, objectState, null, false);
  }
  
  /**
  * Get a Read lock on a JVN object managed by a given JVN server 
  * @param joi : the JVN object identification
  * @param js  : the remote reference of the server
  * @return the current JVN object state
  * @throws java.rmi.RemoteException, JvnException
  **/
  public synchronized Serializable jvnLockRead(int joi, JvnRemoteServer js) throws java.rmi.RemoteException, JvnException{
    JvnRemoteServer currentWriter = writersMap.get(joi);

    if (currentWriter != null && !currentWriter.equals(js)) {
      try {
        // On veut invalider le verrou d'écriture du serveur qui le possède.
        Serializable newState = currentWriter.jvnInvalidateWriterForReader(joi);

        // On a récupéré le verrou on doit mettre à jour notre état :
        objectStates.put(joi, newState);

        // On supprime le serveur qui avait le verrou d'écriture de la liste des écrivains
        writersMap.remove(joi);

        // On ajoute le serveur qui avait le verrou d'écriture à la liste des lecteurs
        readersMap.get(joi).add(currentWriter);
      } catch (RemoteException e) {
        // Le serveur écrivain ne répond plus : il ne doit pas bloquer les futurs verrous.
        removeDeadServer(currentWriter);
      }
    }

    // On ajoute le serveur qui demande le verrou de lecture à la liste des lecteurs
    readersMap.get(joi).add(js);

    return objectStates.get(joi);
  }

  /**
  * Get a Write lock on a JVN object managed by a given JVN server 
  * @param joi : the JVN object identification
  * @param js  : the remote reference of the server
  * @return the current JVN object state
  * @throws java.rmi.RemoteException, JvnException
  **/
  public synchronized Serializable jvnLockWrite(int joi, JvnRemoteServer js) throws java.rmi.RemoteException, JvnException{
    // Partie Lecture
    HashSet<JvnRemoteServer> currentReaders = readersMap.get(joi);
    if(currentReaders != null) {
      for (JvnRemoteServer reader : new HashSet<JvnRemoteServer>(currentReaders)) {
        if (!reader.equals(js)) {
          try {
            // On veut invalider le verrou de lecture du serveur qui le possède.
            reader.jvnInvalidateReader(joi);
          } catch (RemoteException e) {
            // Le lecteur est mort : on le retire des structures du coordinateur.
            removeDeadServer(reader);
          }
        }
      }
      // On vide la liste des lecteurs pour cet objet
      currentReaders.clear();
    }
    
    // Partie Ecriture
    JvnRemoteServer currentWriter = writersMap.get(joi);
    if (currentWriter != null && !currentWriter.equals(js)) {
      try {
        // On veut invalider le verrou d'écriture du serveur qui le possède.
        Serializable newState = currentWriter.jvnInvalidateWriter(joi);

        // On a récupéré le verrou on doit mettre à jour notre état :
        objectStates.put(joi, newState);

        // On supprime le serveur qui avait le verrou d'écriture de la liste des écrivains
        writersMap.remove(joi);
      } catch (RemoteException e) {
        // Le serveur écrivain ne répond plus : on libère son verrou côté coordinateur.
        removeDeadServer(currentWriter);
      }
    }

    // On ajoute le serveur qui demande le verrou d'écriture à la liste des écrivains
    writersMap.put(joi, js);

    return objectStates.get(joi);
  }

  /**
   * Sauvegarde le dernier état connu d'un objet après une écriture réussie.
   *
   * Spécification :
   * - seul le serveur actuellement enregistré comme écrivain de l'objet peut appeler cette méthode ;
   * - si le serveur appelant n'est pas le writer courant, la mise à jour est refusée ;
   * - si la mise à jour est acceptée, objectStates devient la copie de sauvegarde du coordinateur ;
   * - cette sauvegarde ne remplace pas le protocole de verrouillage : elle complète le cache en
   *   limitant la perte de données si un client tombe après une méthode @JvnWrite.
   *
   * @param joi l'identifiant de l'objet JVN
   * @param state l'état courant à sauvegarder
   * @param js le serveur qui demande la sauvegarde
   * @throws RemoteException si l'appel RMI échoue
   * @throws JvnException si le serveur appelant n'est pas le writer courant
   */
  public synchronized void jvnUpdateObjectState(int joi, Serializable state, JvnRemoteServer js) throws RemoteException, JvnException {
    // Seul le serveur qui possède actuellement le verrou d'écriture a le droit
    // de sauvegarder l'état. Cela évite qu'un ancien client écrase l'objet avec
    // une version périmée après avoir perdu son verrou.
    JvnRemoteServer currentWriter = writersMap.get(joi);

    if (currentWriter == null || !currentWriter.equals(js)) {
      throw new JvnException("Only the current writer can update the object state", null);
    }

    // On mémorise l'état envoyé par le proxy après une méthode @JvnWrite réussie.
    // Si le client tombe ensuite en panne, le coordinateur garde déjà cette version.
    objectStates.put(joi, state);
  }

	/**
	* A JVN server terminates
	* @param js  : the remote reference of the server
	* @throws java.rmi.RemoteException, JvnException
	**/
  public void jvnTerminate(JvnRemoteServer js) throws java.rmi.RemoteException, JvnException {
    removeDeadServer(js);
  }

  private void removeDeadServer(JvnRemoteServer js) {
    // On retire le serveur de toutes les listes de lecteurs et écrivains.
    for (HashSet<JvnRemoteServer> readers : readersMap.values()) {
      readers.remove(js);
    }
    // Un serveur peut posséder plusieurs verrous d'écriture, il faut tous les supprimer.
    writersMap.values().removeAll(Collections.singleton(js));
  }
  public static void main(String[] args) {
    try {
      JvnCoordImpl coord = JvnCoordImpl.getCoordInstance();
      Naming.rebind("rmi://localhost/Coordinator", coord);
      System.out.println("Javanaise coordinator ready");
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
