package dev.skimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an effect: a call that changes something outside the program, which skimit then maps
 * from every way in that can cause it ({@code writes MONEY via Ledger}).
 *
 * <p>Put it on the few places where the important thing actually happens (the ledger write, the
 * personal-data store) when skimit's built-in rule does not already find them. Everything that can
 * reach them is derived by the analyzer; nothing else needs tagging.
 *
 * <p>On a type, every method of the type is an effect. Overrides of such a method are too.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE})
public @interface Critical {
  /** Domain labels, for example {@code "MONEY"} or {@code "AUTH"}. */
  String[] value() default {"CRITICAL"};
}
