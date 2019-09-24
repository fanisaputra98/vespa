// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.role;

import com.yahoo.config.provision.ApplicationName;
import com.yahoo.config.provision.TenantName;

import java.util.Objects;

/**
 * A role is a combination of a {@link RoleDefinition} and a {@link Context}, which allows evaluation
 * of access control for a given action on a resource.
 *
 * @author jonmv
 */
public abstract class Role {

    private final RoleDefinition roleDefinition;
    final Context context;

    Role(RoleDefinition roleDefinition, Context context) {
        this.roleDefinition = Objects.requireNonNull(roleDefinition);
        this.context = Objects.requireNonNull(context);
    }

    /** Returns a {@link RoleDefinition#hostedOperator} for the current system. */
    public static UnboundRole hostedOperator() {
        return new UnboundRole(RoleDefinition.hostedOperator);
    }

    /** Returns a {@link RoleDefinition#everyone} for the current system. */
    public static UnboundRole everyone() {
        return new UnboundRole(RoleDefinition.everyone);
    }

    /** Returns a {@link RoleDefinition#athenzTenantAdmin} for the current system and given tenant. */
    public static TenantRole athenzTenantAdmin(TenantName tenant) {
        return new TenantRole(RoleDefinition.athenzTenantAdmin, tenant);
    }

    /** Returns a {@link RoleDefinition#tenantPipeline} for the current system, given tenant, and application */
    public static ApplicationRole tenantPipeline(TenantName tenant, ApplicationName application) {
        return new ApplicationRole(RoleDefinition.tenantPipeline, tenant, application);
    }

    /** Returns a {@link RoleDefinition#publicReader} for the current system and given tenant. */
    public static TenantRole publicReader(TenantName tenant) {
        return new TenantRole(RoleDefinition.publicReader, tenant);
    }

    /** Returns a {@link RoleDefinition#publicDeveloper} for the current system and given tenant. */
    public static TenantRole publicDeveloper(TenantName tenant) {
        return new TenantRole(RoleDefinition.publicDeveloper, tenant);
    }

    /** Returns a {@link RoleDefinition#publicAdministrator} for the current system and given tenant. */
    public static TenantRole publicAdministrator(TenantName tenant) {
        return new TenantRole(RoleDefinition.publicAdministrator, tenant);
    }

    /** Returns a {@link RoleDefinition#publicHeadless} for the current system, given tenant, and application */
    public static ApplicationRole publicHeadless(TenantName tenant, ApplicationName application) {
        return new ApplicationRole(RoleDefinition.publicHeadless, tenant, application);
    }

    /** Returns the role definition of this bound role. */
    public RoleDefinition definition() { return roleDefinition; }

    /** Returns whether the other role is a parent of this, and has a context included in this role's context. */
    public boolean implies(Role other) {
        return    (context.tenant().isEmpty() || context.tenant().equals(other.context.tenant()))
               && (context.application().isEmpty() || context.application().equals(other.context.application()))
               && roleDefinition.inherited().contains(other.roleDefinition);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Role role = (Role) o;
        return roleDefinition == role.roleDefinition &&
               Objects.equals(context, role.context);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roleDefinition, context);
    }

}

