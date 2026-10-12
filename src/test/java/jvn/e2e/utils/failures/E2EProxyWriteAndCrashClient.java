package jvn.e2e.utils.failures;

import irc.model.Sentence;
import irc.model.SentenceItf;
import jvn.proxy.JvnProxy;

public class E2EProxyWriteAndCrashClient {
    public static void main(String[] args) throws Exception {
        // Scénario : écriture via proxy, puis arrêt brutal du client.
        // Résultat attendu : comme le proxy sauvegarde après @JvnWrite,
        // le coordinateur possède déjà l'état écrit malgré la panne du client.
        String name = args[0];
        String value = args[1];

        SentenceItf sentence = (SentenceItf) JvnProxy.newInstance(new Sentence(), name);
        sentence.write(value);

        System.out.println("PROXY_WRITE_SAVED " + value);
        System.out.flush();

        // Arrêt brutal volontaire : le test vérifie que la sauvegarde proxy suffit.
        Runtime.getRuntime().halt(0);
    }
}
