package jvn;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

@Retention(RetentionPolicy.RUNTIME) // On dit que l'annotation @JvnRead sera disponible pendant l'exécution.
@Target(ElementType.METHOD) // On indique que l'annotation @JvnRead peut être appliquée aux méthodes.
public @interface JvnRead {
}