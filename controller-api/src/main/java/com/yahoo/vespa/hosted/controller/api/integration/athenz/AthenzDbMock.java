// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.integration.athenz;

import com.yahoo.vespa.athenz.api.AthenzDomain;
import com.yahoo.vespa.athenz.api.AthenzIdentity;
import com.yahoo.vespa.hosted.controller.api.identifiers.ApplicationId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author bjorncs
 */
public class AthenzDbMock {

    public final Map<AthenzDomain, Domain> domains = new HashMap<>();
    public final List<AthenzIdentity> hostedOperators = new ArrayList<>();

    public AthenzDbMock addDomain(Domain domain) {
        domains.put(domain.name, domain);
        return this;
    }

    public Domain getOrCreateDomain(AthenzDomain domain) {
        return domains.computeIfAbsent(domain, Domain::new);
    }

    public AthenzDbMock addHostedOperator(AthenzIdentity athenzIdentity) {
        hostedOperators.add(athenzIdentity);
        return this;
    }

    public static class Domain {

        public final AthenzDomain name;
        public final Set<AthenzIdentity> admins = new HashSet<>();
        public final Set<AthenzIdentity> tenantAdmins = new HashSet<>();
        public final Map<ApplicationId, Application> applications = new HashMap<>();
        public final Map<String, Service> services = new HashMap<>();
        public boolean isVespaTenant = false;

        public Domain(AthenzDomain name) {
            this.name = name;
        }

        public Domain admin(AthenzIdentity identity) {
            admins.add(identity);
            return this;
        }

        public Domain tenantAdmin(AthenzIdentity identity) {
            tenantAdmins.add(identity);
            return this;
        }

        /**
         * Simulates establishing Vespa tenancy in Athens.
         */
        public void markAsVespaTenant() {
            isVespaTenant = true;
        }

    }

    public static class Application {

        public final Map<ApplicationAction, Set<AthenzIdentity>> acl = new HashMap<>();

        public Application() {
            acl.put(ApplicationAction.deploy, new HashSet<>());
            acl.put(ApplicationAction.read, new HashSet<>());
            acl.put(ApplicationAction.write, new HashSet<>());
        }

        public Application addRoleMember(ApplicationAction action, AthenzIdentity identity) {
            acl.get(action).add(identity);
            return this;
        }
    }

    public static class Service {

        public final boolean allowLaunch;

        public Service(boolean allowLaunch) {
            this.allowLaunch = allowLaunch;
        }
    }
}
