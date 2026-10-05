package jvn.proxy;

import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import jvn.annotations.JvnRead;
import jvn.annotations.JvnWrite;
import jvn.core.JvnObject;
import jvn.core.JvnServerImpl;

public class JvnProxy implements InvocationHandler {
    
    private JvnObject jvnObject;

    private JvnProxy(JvnObject jvnObject) {
        this.jvnObject = jvnObject;
    }
    
    /**
     * Crée un proxy Java pour un objet partagé JVN.
     * @param obj objet partagé initial (exemple : new Sentence())
     * @param name nom symbolique de l'objet partagé (exemple : "IRC")
     * @return le proxy Java qui intercepte les appels aux méthodes de l'objet partagé
     * @throws Exception si le serveur JVN ne peut pas être initialisé ou si l'objet n'a pas d'interface
     */
    public static Object newInstance(Serializable obj, String name) throws Exception {
        // 1. Récupérer le serveur local JVN.
        JvnServerImpl jvnServer = JvnServerImpl.jvnGetServer();
        if (jvnServer == null) {
            throw new Exception("Le serveur JVN n'a pas été initialisé.");
        }

        // 2. Récupérer l'objet partagé s'il existe déjà, sinon le créer et l'enregistrer.
        JvnObject jvnObject = jvnServer.jvnLookupObject(name);
        if (jvnObject == null) {
            jvnObject = jvnServer.jvnCreateObject(obj);
            // Un objet créé localement commence avec un verrou écriture actif.
            // On le relâche pour le garder en cache avant de l'enregistrer.
            jvnObject.jvnUnLock();
            jvnServer.jvnRegisterObject(name, jvnObject);
        }

        Class<?>[] interfaces = obj.getClass().getInterfaces();
        if (interfaces.length == 0) {
            throw new IllegalArgumentException("L'objet partagé doit implémenter au moins une interface.");
        }

        // 3. Retourner un proxy qui implémente les mêmes interfaces que l'objet métier.
        return Proxy.newProxyInstance(
            obj.getClass().getClassLoader(),
            interfaces,
            new JvnProxy(jvnObject)
        );
    }
    
    @Override 
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Les méthodes de Object ne décrivent pas un accès à l'objet partagé.
        // On les traite donc sans verrou JVN.
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        boolean lockTaken = false;

        if (method.isAnnotationPresent(JvnRead.class)) {
            jvnObject.jvnLockRead();
            lockTaken = true;
        } else if (method.isAnnotationPresent(JvnWrite.class)) {
            jvnObject.jvnLockWrite();
            lockTaken = true;
        } else {
            throw new IllegalStateException("La méthode " + method.getName() + " doit être annotée avec @JvnRead ou @JvnWrite.");
        }

        try {
            return method.invoke(jvnObject.jvnGetObjectState(), args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        } finally {
            if (lockTaken) {
                jvnObject.jvnUnLock();
            }
        }
    }
}
