/***
 * JAVANAISE API
 * Contact: 
 * 
 * Authors: 
 */

package jvn.core;

import java.rmi.*;
import java.io.*;


/**
 * Remote Interface of the JVN Coordinator  
 */

public interface JvnRemoteCoord extends Remote {

	/**
	*  Allocate a NEW JVN object id (usually allocated to a 
	*  newly created JVN object)
	* @throws java.rmi.RemoteException,JvnException
	**/
	public int jvnGetObjectId()
		throws java.rmi.RemoteException,jvn.core.JvnException;  
	
	/**
	* Associate a symbolic name with a JVN object
	* @param jon : the JVN object name
	* @param jo  : the JVN object 
	* @param joi : the JVN object identification
	* @param js  : the remote reference of the JVNServer
	* @throws java.rmi.RemoteException,JvnException
	**/
	public void jvnRegisterObject(String jon, JvnObject jo, JvnRemoteServer js)
		throws java.rmi.RemoteException,jvn.core.JvnException; 
	
	/**
	* Get the reference of a JVN object managed by a given JVN server 
	* @param jon : the JVN object name
	* @param js : the remote reference of the JVNServer
	* @throws java.rmi.RemoteException,JvnException
	**/ 
	public JvnObject jvnLookupObject(String jon, JvnRemoteServer js)
		throws java.rmi.RemoteException,jvn.core.JvnException; 
	 
	/**
	* Get a Read lock on a JVN object managed by a given JVN server 
	* @param joi : the JVN object identification
	* @param js  : the remote reference of the server
	* @return the current JVN object state
	* @throws java.rmi.RemoteException, JvnException
	**/
	public Serializable jvnLockRead(int joi, JvnRemoteServer js)
		throws java.rmi.RemoteException, JvnException;

	/**
	* Get a Write lock on a JVN object managed by a given JVN server 
	* @param joi : the JVN object identification
	* @param js  : the remote reference of the server
	* @return the current JVN object state
	* @throws java.rmi.RemoteException, JvnException
	**/
	public Serializable jvnLockWrite(int joi, JvnRemoteServer js)
		throws java.rmi.RemoteException, JvnException;

	/**
	* Sauvegarde l'état courant d'un objet JVN après une écriture réussie.
	*
	* Spécification :
	* - cette méthode doit être appelée après l'exécution complète d'une méthode
	*   métier annotée @JvnWrite ;
	* - le serveur appelant doit encore posséder le verrou d'écriture sur l'objet ;
	* - le coordinateur remplace son dernier état connu par l'état fourni ;
	* - si le client tombe en panne après cette sauvegarde, le coordinateur peut
	*   continuer à fournir cette version aux autres clients ;
	* - si le client tombe en panne avant cette sauvegarde, l'écriture en cours
	*   peut être perdue.
	*
	* Cette méthode est utilisée par la version proxy pour que le coordinateur
	* possède une copie récente de l'objet sans attendre une future invalidation.
	* @param joi : l'identifiant de l'objet JVN
	* @param state : l'état courant de l'objet
	* @param js : la référence distante du serveur qui possède le verrou écriture
	* @throws java.rmi.RemoteException, JvnException
	**/
	public void jvnUpdateObjectState(int joi, Serializable state, JvnRemoteServer js)
		throws java.rmi.RemoteException, JvnException;

	/**
	* A JVN server terminates
	* @param js  : the remote reference of the server
	* @throws java.rmi.RemoteException, JvnException
	**/
	public void jvnTerminate(JvnRemoteServer js)
		throws java.rmi.RemoteException, JvnException;

 }


