package jvn.core;

/**
 * Configuration RMI commune au coordinateur et aux serveurs Javanaise.
 *
 * Spécification :
 * - la configuration peut venir des propriétés Java passées avec -D ;
 * - si une propriété Java n'est pas définie, on regarde la variable d'environnement ;
 * - si rien n'est défini, on garde les valeurs locales historiques pour ne pas casser
 *   les lancements simples et les tests ;
 * - le nom d'hôte d'export RMI permet à une machine distante de rappeler correctement
 *   un serveur ou un coordinateur exporté.
 */
public final class JvnRmiConfig {

    public static final String COORD_HOST_PROPERTY = "jvn.coord.host";
    public static final String COORD_PORT_PROPERTY = "jvn.coord.port";
    public static final String COORD_NAME_PROPERTY = "jvn.coord.name";
    public static final String RMI_SERVER_HOST_PROPERTY = "jvn.rmi.server.host";

    public static final String COORD_HOST_ENV = "JVN_COORD_HOST";
    public static final String COORD_PORT_ENV = "JVN_COORD_PORT";
    public static final String COORD_NAME_ENV = "JVN_COORD_NAME";
    public static final String RMI_SERVER_HOST_ENV = "JVN_RMI_SERVER_HOST";

    private static final String DEFAULT_COORD_HOST = "localhost";
    private static final int DEFAULT_COORD_PORT = 1099;
    private static final String DEFAULT_COORD_NAME = "Coordinator";

    private JvnRmiConfig() {
    }

    /**
     * Récupère le nom d'hôte du coordinateur RMI.
     * @return le nom d'hôte du coordinateur RMI
     */
    public static String coordinatorHost() {
        return stringValue(COORD_HOST_PROPERTY, COORD_HOST_ENV, DEFAULT_COORD_HOST);
    }

    /**
     * Récupère le port du coordinateur RMI.
     * @return le port du coordinateur RMI
     * @throws IllegalArgumentException si le port n'est pas un entier valide
     */
    public static int coordinatorPort() {
        String value = stringValue(COORD_PORT_PROPERTY, COORD_PORT_ENV, String.valueOf(DEFAULT_COORD_PORT));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port RMI invalide: " + value, e);
        }
    }

    /**
     * Récupère le nom du coordinateur RMI.
     * @return le nom du coordinateur RMI
     */
    public static String coordinatorName() {
        return stringValue(COORD_NAME_PROPERTY, COORD_NAME_ENV, DEFAULT_COORD_NAME);
    }

    /**
     * Construit l'URL RMI complète du coordinateur.
     * @return l'URL RMI complète du coordinateur
     */
    public static String coordinatorUrl() {
        return "rmi://" + coordinatorHost() + ":" + coordinatorPort() + "/" + coordinatorName();
    }

    /**
     * Configure le nom d'hôte d'export RMI si une valeur est fournie via les propriétés Java ou les variables d'environnement.
     * Si aucune valeur n'est fournie, la configuration locale est conservée.
     */
    public static void configureExportedHostIfProvided() {
        String exportedHost = stringValue(RMI_SERVER_HOST_PROPERTY, RMI_SERVER_HOST_ENV, null);

        // Si une valeur est fournie, on la configure comme nom d'hôte d'export RMI
        if (exportedHost != null && exportedHost.length() > 0) {
            System.setProperty("java.rmi.server.hostname", exportedHost);
        }
    }

    /**
     * Récupère la valeur d'une propriété Java ou d'une variable d'environnement, avec une valeur par défaut.
     * @param propertyName le nom de la propriété Java
     * @param environmentName le nom de la variable d'environnement
     * @param defaultValue la valeur par défaut à retourner si aucune autre valeur n'est trouvée
     * @return la valeur de la propriété ou de la variable d'environnement, ou la valeur par défaut
     */
    private static String stringValue(String propertyName, String environmentName, String defaultValue) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null && propertyValue.length() > 0) {
            return propertyValue;
        }

        String environmentValue = System.getenv(environmentName);
        if (environmentValue != null && environmentValue.length() > 0) {
            return environmentValue;
        }

        return defaultValue;
    }
}
