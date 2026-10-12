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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Map;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;


public class JvnCoordImpl extends UnicastRemoteObject implements JvnRemoteCoord {

	private static final long serialVersionUID = 1L;

  static {
    JvnRmiConfig.configureExportedHostIfProvided();
  }

  private static final String SNAPSHOT_FILE_PROPERTY = "jvn.coord.snapshot";

  private static final String DEFAULT_SNAPSHOT_FILE = "jvn-coordinator.snapshot";

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
    // Initialisation des structures mémoire du coordinateur
    initializeEmptyState();

    // Chargement des fichiers de snapshot si ils existent.
    loadSnapshotIfExists();
	}

  /**
   * On récupère l'instance du coordinateur
   * @return l'instance du coordinateur
   */
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

    // Sauvegarde dans le snapshot après l'incrémentation de nextObjectId.
    saveSnapshot();
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

    // Sauvegarde dans le snapshot après l'enregistrement de l'objet.
    saveSnapshot();
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

        // Sauvegarde dans le snapshot après l'invalidation du verrou d'écriture.
        saveSnapshot();

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

        // Sauvegarde dans le snapshot après l'invalidation du verrou d'écriture.
        saveSnapshot();

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

    // Sauvegarde dans le snapshot après la mise à jour de l'état de l'objet.
    saveSnapshot();
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

  /**
   * Initialise un coordinateur vide.
   *
   * Spécification :
   * - cette méthode prépare les structures mémoire utilisées par le coordinateur ;
   * - elle est utilisée au premier démarrage ou avant de recharger un snapshot ;
   * - les verrous ne sont pas considérés comme durables ;
   */
  private void initializeEmptyState() {
    nextObjectId = 0;
    this.nameToIdMap = new HashMap<String, Integer>();
    this.objectStates = new HashMap<Integer, Serializable>();
    this.readersMap = new HashMap<Integer, HashSet<JvnRemoteServer>>();
    this.writersMap = new HashMap<Integer, JvnRemoteServer>();
  }

  /**
   * Charge l'état durable du coordinateur depuis le disque si un snapshot existe.
   *
   * Spécification :
   * - seuls nextObjectId, nameToIdMap et objectStates sont restaurés ;
   * - les lecteurs et écrivains ne sont pas restaurés, car ce sont des références RMI
   *   vers des clients qui peuvent avoir disparu pendant la panne ;
   * - après le chargement, chaque objet connu récupère simplement une liste de lecteurs vide.
   *
   * @throws JvnException si le snapshot existe mais ne peut pas être relu
   */
  private void loadSnapshotIfExists() throws JvnException {

    // On initialise les structures mémoire avant de tenter de charger un snapshot.
    File snapshotFile = getSnapshotFile();

    // On regarde si le fichier de snapshot existe. 
    // S'il n'existe pas, on ne fait rien et on reste avec un coordinateur vide.
    if (!snapshotFile.exists()) {
      return;
    }

    // S'il existe, on tente de le charger. S'il est corrompu ou illisible, on échoue.
    try (ObjectInputStream input = new ObjectInputStream(new FileInputStream(snapshotFile))) {
      CoordinatorSnapshot snapshot = (CoordinatorSnapshot) input.readObject();
      this.nextObjectId = snapshot.nextObjectId;
      this.nameToIdMap = snapshot.nameToIdMap;
      this.objectStates = snapshot.objectStates;
      rebuildLockMapsAfterRecovery();
    } catch (Exception e) {
      throw new JvnException("Impossible de charger le snapshot du coordinateur", e);
    }
  }

  /**
   * Sauvegarde sur disque l'état durable du coordinateur.
   *
   * Spécification :
   * - la sauvegarde est faite après chaque modification durable ;
   * - elle protège les noms, les identifiants et le dernier état connu des objets ;
   * - elle ne sauvegarde pas les verrous, ils sont initialisés à NL après un redémarrage ;
   * - l'écriture se fait dans un fichier temporaire puis par renommage pour éviter un fichier
   *   partiellement écrit si la machine tombe pendant la sauvegarde.
   *
   * @throws JvnException si la sauvegarde disque échoue
   */
  private void saveSnapshot() throws JvnException {

    // On récupère le fichier de snapshot.
    File snapshotFile = getSnapshotFile();

    // On s'assure que le répertoire parent (du fichier de snapshot) existe, sinon on le crée.
    File parent = snapshotFile.getParentFile();

    // On crée le répertoire parent si nécessaire. Si la création échoue, on lève une exception.
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
      throw new JvnException("Impossible de créer le répertoire du snapshot du coordinateur", null);
    }

    // On écrit d'abord dans un fichier temporaire pour éviter de corrompre le snapshot existant.
    File temporaryFile = new File(snapshotFile.getPath() + ".tmp");

    // On crée un objet snapshot qui contient les données à sauvegarder.
    CoordinatorSnapshot snapshot = new CoordinatorSnapshot(nextObjectId, nameToIdMap, objectStates);

    // On écrit le snapshot dans le fichier temporaire. Si l'écriture échoue, on lève une exception.
    try (ObjectOutputStream output = new ObjectOutputStream(new FileOutputStream(temporaryFile))) {
      output.writeObject(snapshot);
    } catch (Exception e) {
      throw new JvnException("Impossible de sauvegarder le snapshot du coordinateur", e);
    }

    // On remplace le fichier de snapshot existant par le fichier temporaire.
    if (!temporaryFile.renameTo(snapshotFile)) {

      // Si le fichier de snapshot existant existe déjà, on essaie de le supprimer avant de renommer.
      if (snapshotFile.exists() && !snapshotFile.delete()) {
        throw new JvnException("Impossible de remplacer l'ancien snapshot du coordinateur", null);
      }

      // On essaie à nouveau de renommer le fichier temporaire en fichier de snapshot.
      if (!temporaryFile.renameTo(snapshotFile)) {
        throw new JvnException("Impossible de finaliser le snapshot du coordinateur", null);
      }
    }
  }

  /**
   * Récupère le fichier de snapshot à utiliser pour la sauvegarde et la restauration.
   * La valeur par défaut est "jvn-coordinator.snapshot".
   * @return
   */
  private File getSnapshotFile() {
    return new File(System.getProperty(SNAPSHOT_FILE_PROPERTY, DEFAULT_SNAPSHOT_FILE));
  }

  /**
   * Reconstruit les structures de verrous après la récupération d'un snapshot.
   */
  private void rebuildLockMapsAfterRecovery() {
    this.readersMap = new HashMap<Integer, HashSet<JvnRemoteServer>>();
    this.writersMap = new HashMap<Integer, JvnRemoteServer>();

    // Pour chaque objet connu, on initialise une liste de lecteurs vide et aucun écrivain.
    for (Integer objectId : objectStates.keySet()) {
      readersMap.put(objectId, new HashSet<JvnRemoteServer>());
    }
  }

  /**
   * Classe interne pour représenter un snapshot du coordinateur.
   * Elle est sérialisable pour pouvoir être écrite sur disque.
   */
  private static class CoordinatorSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    // Ce qu'on veut sauvegarder : l'identifiant suivant à attribuer, le dictionnaire des noms et le dernier état connu des objets.
    private int nextObjectId;
    private Map<String, Integer> nameToIdMap;
    private Map<Integer, Serializable> objectStates;

    /**
     * Constructeur pour créer un snapshot du coordinateur.
     * @param nextObjectId l'identifiant suivant à attribuer
     * @param nameToIdMap le dictionnaire des noms d'objets à leurs identifiants
     * @param objectStates le dernier état connu des objets
     */
    private CoordinatorSnapshot(int nextObjectId, Map<String, Integer> nameToIdMap, Map<Integer, Serializable> objectStates) {
      this.nextObjectId = nextObjectId;
      this.nameToIdMap = new HashMap<String, Integer>(nameToIdMap);
      this.objectStates = new HashMap<Integer, Serializable>(objectStates);
    }
  }

  public static void main(String[] args) {
    try {
      applyCommandLineArguments(args);
      JvnRmiConfig.configureExportedHostIfProvided();
      JvnCoordImpl coord = JvnCoordImpl.getCoordInstance();
      Naming.rebind(JvnRmiConfig.coordinatorUrl(), coord);
      System.out.println("Javanaise coordinator ready on " + JvnRmiConfig.coordinatorUrl());
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static void applyCommandLineArguments(String[] args) {
    for (int i = 0; i < args.length; i++) {
      if ("--host".equals(args[i]) && i + 1 < args.length) {
        System.setProperty(JvnRmiConfig.COORD_HOST_PROPERTY, args[++i]);
      } else if ("--port".equals(args[i]) && i + 1 < args.length) {
        System.setProperty(JvnRmiConfig.COORD_PORT_PROPERTY, args[++i]);
      } else if ("--name".equals(args[i]) && i + 1 < args.length) {
        System.setProperty(JvnRmiConfig.COORD_NAME_PROPERTY, args[++i]);
      } else if ("--export-host".equals(args[i]) && i + 1 < args.length) {
        System.setProperty(JvnRmiConfig.RMI_SERVER_HOST_PROPERTY, args[++i]);
      } else if ("--snapshot".equals(args[i]) && i + 1 < args.length) {
        System.setProperty(SNAPSHOT_FILE_PROPERTY, args[++i]);
      } else {
        throw new IllegalArgumentException("Argument inconnu ou incomplet: " + args[i]);
      }
    }
  }
}
