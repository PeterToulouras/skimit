package dev.skimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a critical sink: code whose behaviour a human must always review.
 *
 * <p>Put it on the few places where the important thing actually happens (the ledger write, the
 * permission check, the personal-data store). Everything that can reach a sink is derived by the
 * analyzer; nothing else needs tagging.
 *
 * <p>On a type, every method of the type is a sink. Overrides of a sink method are sinks too.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE})
public @interface Critical {
  /** Domain labels, for example {@code "MONEY"} or {@code "AUTH"}. */
  String[] value() default {"CRITICAL"};
}
