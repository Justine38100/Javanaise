/***
 * JAVANAISE API
 * Contact: 
 * 
 * Authors: 
 */

package jvn.core;

import java.io.*;

/**
 * Interface of a JVN object. 
 * The serializable property is required in order to be able to transfer 
 * a reference to a JVN object remotely
 */

public interface JvnObject extends Serializable {

	/**
	* Get a Read lock on the object 
	* @throws JvnException
	**/
	public void jvnLockRead() throws jvn.core.JvnException; 

	/**
	* Get a Write lock on the object 
	* @throws JvnException
	**/
	public void jvnLockWrite() throws jvn.core.JvnException; 

	/**
	* Unlock  the object 
	* @throws JvnException
	**/
	public void jvnUnLock() throws jvn.core.JvnException; 
	
	/**
	* Get the object identification
	* @throws JvnException
	**/
	public int jvnGetObjectId() throws jvn.core.JvnException; 
	
	/**
	* Get the object state
	* @throws JvnException
	**/
	public Serializable jvnGetObjectState() throws jvn.core.JvnException; 
	
	/**
	* Invalidate the Read lock of the JVN object
	* @throws JvnException
	**/
  public void jvnInvalidateReader() throws jvn.core.JvnException;
	    
	/**
	* Invalidate the Write lock of the JVN object  
	* @return the current JVN object state
	* @throws JvnException
	**/
  public Serializable jvnInvalidateWriter() throws jvn.core.JvnException;
	
	/**
	* Reduce the Write lock of the JVN object 
	* @return the current JVN object state
	* @throws JvnException
	**/
   public Serializable jvnInvalidateWriterForReader() throws jvn.core.JvnException;	
}
