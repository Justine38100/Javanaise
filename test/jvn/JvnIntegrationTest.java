package jvn;

import java.rmi.Naming;
import java.rmi.registry.LocateRegistry;

public class JvnIntegrationTest {

    public static void main(String[] args) throws Exception {
        startRegistryIfNeeded();
        bindCoordinator();

        testLookupUnknownThenCreateRegisterAndLookup();
        testWriteThenReadThroughRealServerAndObject();

        System.out.println("JvnIntegrationTest OK");
        System.exit(0);
    }

    /*
     * Scénario : on démarre le registre RMI et on publie le coordinateur.
     * Ensuite, un serveur JVN fait un lookup sur un nom inconnu.
     * Le lookup doit retourner null, puis le serveur crée l'objet, l'enregistre,
     * et un nouveau lookup sur le même nom doit retourner un objet existant avec le même id.
     */
    private static void testLookupUnknownThenCreateRegisterAndLookup() throws Exception {
        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        String name = "INTEGRATION_OBJECT";

        JvnObject unknown = server.jvnLookupObject(name);
        assert unknown == null : "un lookup sur un objet inconnu doit retourner null";

        JvnObject created = server.jvnCreateObject("etat-initial");
        int createdId = created.jvnGetObjectId();
        created.jvnUnLock();
        server.jvnRegisterObject(name, created);

        JvnObject lookedUp = server.jvnLookupObject(name);
        assert lookedUp != null : "un lookup apres register doit retourner un objet";
        assert lookedUp.jvnGetObjectId() == createdId : "l objet retrouve doit garder le meme id";
        assert "etat-initial".equals(lookedUp.jvnGetObjectState()) : "l objet retrouve doit garder le meme etat";
    }

    /*
     * Scénario : on utilise le serveur local et le coordinateur pour prendre des verrous.
     * L'objet est créé, enregistré, puis on prend un verrou en écriture pour modifier son état,
     * et ensuite un verrou en lecture pour relire l'état modifié.
     */
    private static void testWriteThenReadThroughRealServerAndObject() throws Exception {
        JvnServerImpl server = JvnServerImpl.jvnGetServer();
        String name = "INTEGRATION_READ_WRITE";

        JvnObject object = server.jvnCreateObject(new MutableValue("avant"));
        object.jvnUnLock();
        server.jvnRegisterObject(name, object);

        object.jvnLockWrite();
        ((MutableValue) object.jvnGetObjectState()).value = "apres";
        object.jvnUnLock();

        object.jvnLockRead();
        assert ((MutableValue) object.jvnGetObjectState()).value.equals("apres") : "la lecture doit voir l etat ecrit";
        object.jvnUnLock();
    }

    private static void startRegistryIfNeeded() throws Exception {
        try {
            LocateRegistry.createRegistry(1099);
        } catch (Exception e) {
            // Le registre existe déjà, on le réutilise.
        }
    }

    private static void bindCoordinator() throws Exception {
        JvnCoordImpl coord = JvnCoordImpl.getCoordInstance();
        Naming.rebind("rmi://localhost/Coordinator", coord);
    }

    private static class MutableValue implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private String value;

        MutableValue(String value) {
            this.value = value;
        }
    }
}
