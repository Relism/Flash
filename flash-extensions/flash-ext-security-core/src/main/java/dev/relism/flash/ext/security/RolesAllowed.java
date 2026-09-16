package dev.relism.flash.ext.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The caller must hold at least one of these roles, as decided by the configured
 * {@link RoleResolver}. Implies {@link Authenticated}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@java.lang.annotation.Target(ElementType.TYPE)
public @interface RolesAllowed {

    String[] value();

    /**
     * Names of the path or query parameters (tool arguments on MCP) that identify the resource the
     * role is held on — {@code on = "project"} checks the role on {@code /projects/{project}}.
     */
    String[] on() default {};
}
