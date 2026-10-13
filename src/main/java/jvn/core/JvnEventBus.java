package jvn.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bus d'événements local pour suivre les pannes et les reconnexions Javanaise.
 *
 * Spécification :
 * - ce bus ne fait pas partie du protocole distribué ;
 * - il sert uniquement à informer l'application locale, par exemple l'interface graphique ;
 * - les événements sont aussi écrits sur stdout pour rester visibles sans interface ;
 * - CopyOnWriteArrayList évite les erreurs si un listener est ajouté pendant une notification.
 */
public final class JvnEventBus {

    private static final List<JvnEventListener> LISTENERS = new CopyOnWriteArrayList<JvnEventListener>();

    private JvnEventBus() {
    }

    public static void addListener(JvnEventListener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(JvnEventListener listener) {
        LISTENERS.remove(listener);
    }

    public static void publish(String message) {
        System.out.println("[JVN] " + message);

        for (JvnEventListener listener : LISTENERS) {
            listener.onJvnEvent(message);
        }
    }
}
