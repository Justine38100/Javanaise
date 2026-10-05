/***
 * JAVANAISE Implementation
 * JvnServerImpl class
 * Contact: 
 * 
 * Authors: 
 */

package jvn.core;

import java.rmi.server.UnicastRemoteObject;
import java.util.Map;
import java.util.HashMap;
import java.io.*;



public class JvnServerImpl extends UnicastRemoteObject implements JvnLocalServer, JvnRemoteServer { 
	
	private static final long serialVersionUID = 1L;

	// A JVN server is managed as a singleton 
	private static JvnServerImpl js = null;

	// Référence vers le coordinateur global.
	private JvnRemoteCoord jvnCoord;

	// Dictionnaire pour stocker les objets JVN avec leur identifiant comme clé.
	// Exemple : id = 1, JvnObject = "Hello World" (De type Sentence)
	// C'est le cache local des objets connus par le serveur.
	private Map<Integer, JvnObject> jvnObjectsMap;

	/**
	 * Default constructor
	 * @throws JvnException
	 **/
	private JvnServerImpl() throws Exception {
		super();
		this.jvnCoord = (JvnRemoteCoord) java.rmi.Naming.lookup("rmi://localhost/Coordinator");
		this.jvnObjectsMap = new HashMap<Integer, JvnObject>();
	}
	
	/**
	* Static method allowing an application to get a reference to 
	* a JVN server instance
	* @throws JvnException
	**/
	public static JvnServerImpl jvnGetServer() {
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
		try {
			// Déconnexion du serveur auprès du coordinateur.
			jvnCoord.jvnTerminate(this);

			// Nettoyage des ressources locales
			jvnObjectsMap.clear();
			js = null;

		} catch (Exception e) {
			throw new jvn.core.JvnException("We can't terminate the JVN server", e);
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
			int newId = jvnCoord.jvnGetObjectId();
			
			// Création d'un nouvel objet JVN avec l'état fourni et l'identifiant obtenu
			JvnObject newobject = new JvnObjectImpl(newId, o, this, true);
			
			// Ajout de l'objet JVN à la map locale
			jvnObjectsMap.put(newId, newobject);

			return newobject;

		} catch (Exception e) {
			throw new jvn.core.JvnException("We can't create a new JVN object", e);
		}
	}
	
	/**
	*  Associate a symbolic name with a JVN object
	* @param jon : the JVN object name
	* @param jo : the JVN object 
	* @throws JvnException
	**/
	public void jvnRegisterObject(String jon, JvnObject jo) throws jvn.core.JvnException {
		try {
			// Enregistrement de l'objet JVN auprès du coordinateur.
			jvnCoord.jvnRegisterObject(jon, jo, this);
		} catch (Exception e) {
			throw new jvn.core.JvnException("We can't register the JVN object", e);
		}
	}
	
	/**
	* Provide the reference of a JVN object beeing given its symbolic name
	* @param jon : the JVN object name
	* @return the JVN object 
	* @throws JvnException
	**/
	public JvnObject jvnLookupObject(String jon) throws jvn.core.JvnException {
		try {
			JvnObjectImpl lookedUpObject = (JvnObjectImpl)jvnCoord.jvnLookupObject(jon, this);

			if (lookedUpObject == null) {
				return null;
			}
			
			// Cet objet JVN reçu par lookup doit maintenant utiliser ce JvnServerImpl local.
			lookedUpObject.setJvnServer(this);

			jvnObjectsMap.put(lookedUpObject.jvnGetObjectId(), lookedUpObject);

			return lookedUpObject;

		} catch (Exception e) {
			throw new jvn.core.JvnException("We can't lookup the JVN object", e);
		}
	}	
	
	/**
	* Get a Read lock on a JVN object 
	* @param joi : the JVN object identification
	* @return the current JVN object state
	* @throws  JvnException
	**/
	public Serializable jvnLockRead(int joi) throws JvnException {
		try {
			// Demande au coordinateur un verrou de lecture pour l'objet JVN identifié par joi
			return jvnCoord.jvnLockRead(joi, this);
		} catch (Exception e) {
			throw new jvn.core.JvnException("We can't get a read lock on the JVN object", e);
		}
	}
	
	/**
	* Get a Write lock on a JVN object 
	* @param joi : the JVN object identification
	* @return the current JVN object state
	* @throws  JvnException
	**/
	public Serializable jvnLockWrite(int joi) throws JvnException {
		try {
			// Demande au coordinateur un verrou d'écriture pour l'objet JVN identifié par joi
			return jvnCoord.jvnLockWrite(joi, this);
		} catch (Exception e) {
			throw new jvn.core.JvnException("We can't get a write lock on the JVN object", e);
		}
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
			JvnObject jvnObject = jvnObjectsMap.get(joi);
			
			if (jvnObject != null) {
				// Invalidation du verrou de lecture pour l'objet JVN
				jvnObject.jvnInvalidateReader();
			}
		} catch (Exception e) {
			throw new jvn.core.JvnException("We can't invalidate the read lock on the JVN object", e);
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
				JvnObject jvnObject = jvnObjectsMap.get(joi);
				
				if (jvnObject != null) {
					// Invalidation du verrou d'écriture pour l'objet JVN et récupération de son état actuel
					return jvnObject.jvnInvalidateWriter();
				}
				return null;
			} catch (Exception e) {
				throw new jvn.core.JvnException("We can't invalidate the write lock on the JVN object", e);
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
				JvnObject jvnObject = jvnObjectsMap.get(joi);
				
				if (jvnObject != null) {
					// Réduction du verrou d'écriture pour l'objet JVN et récupération de son état actuel
					return jvnObject.jvnInvalidateWriterForReader();
				}
				return null;
			} catch (Exception e) {
				throw new jvn.core.JvnException("We can't reduce the write lock on the JVN object", e);
			}
		};
}

 
