package jvn.core;

/**
 * Ecouteur utilisé par les applications pour afficher les événements
 * internes de Javanaise.
 */
public interface JvnEventListener {

    void onJvnEvent(String message);
}
