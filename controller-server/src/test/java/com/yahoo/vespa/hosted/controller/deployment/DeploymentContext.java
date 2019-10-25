// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.deployment;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.AthenzDomain;
import com.yahoo.config.provision.AthenzService;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.security.KeyAlgorithm;
import com.yahoo.security.KeyUtils;
import com.yahoo.security.SignatureAlgorithm;
import com.yahoo.security.X509CertificateBuilder;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.ControllerTester;
import com.yahoo.vespa.hosted.controller.Instance;
import com.yahoo.vespa.hosted.controller.api.identifiers.DeploymentId;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ApplicationVersion;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.JobId;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.RunId;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.SourceRevision;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.TesterCloud;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.TesterId;
import com.yahoo.vespa.hosted.controller.api.integration.routing.RoutingEndpoint;
import com.yahoo.vespa.hosted.controller.api.integration.routing.RoutingGeneratorMock;
import com.yahoo.vespa.hosted.controller.api.integration.stubs.MockTesterCloud;
import com.yahoo.vespa.hosted.controller.application.ApplicationPackage;
import com.yahoo.vespa.hosted.controller.application.TenantAndApplicationId;
import com.yahoo.vespa.hosted.controller.integration.ConfigServerMock;
import com.yahoo.vespa.hosted.controller.maintenance.JobControl;
import com.yahoo.vespa.hosted.controller.maintenance.JobRunner;
import com.yahoo.vespa.hosted.controller.maintenance.NameServiceDispatcher;
import com.yahoo.vespa.hosted.controller.maintenance.ReadyJobsTrigger;

import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.yahoo.vespa.hosted.controller.deployment.Step.Status.unfinished;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A deployment context for an application. This allows fine-grained control of the deployment of an application's
 * instances.
 *
 * References to this should be acquired through {@link InternalDeploymentTester#deploymentContext}.
 *
 * Tester code that is not specific to deployments should be added to either {@link ControllerTester} or
 * {@link InternalDeploymentTester} instead of this class.
 *
 * @author mpolden
 */
public class DeploymentContext {

    // Set a long interval so that maintainers never do scheduled runs during tests
    private static final Duration maintenanceInterval = Duration.ofDays(1);

    public static final ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
            .athenzIdentity(AthenzDomain.from("domain"), AthenzService.from("service"))
            .upgradePolicy("default")
            .region("us-central-1")
            .parallel("us-west-1", "us-east-3")
            .emailRole("author")
            .emailAddress("b@a")
            .build();

    public static final ApplicationPackage publicCdApplicationPackage = new ApplicationPackageBuilder()
            .athenzIdentity(AthenzDomain.from("domain"), AthenzService.from("service"))
            .upgradePolicy("default")
            .region("aws-us-east-1c")
            .emailRole("author")
            .emailAddress("b@a")
            .trust(generateCertificate())
            .build();

    private final TenantAndApplicationId applicationId;
    private final ApplicationId instanceId;
    private final TesterId testerId;
    private final JobController jobs;
    private final RoutingGeneratorMock routing;
    private final MockTesterCloud cloud;
    private final JobRunner runner;
    private final ControllerTester tester;
    private final ReadyJobsTrigger readyJobsTrigger;
    private final NameServiceDispatcher nameServiceDispatcher;

    private ApplicationVersion lastSubmission = null;
    private boolean deferDnsUpdates = false;

    public DeploymentContext(ApplicationId instanceId, ControllerTester tester, JobRunner runner) {
        var jobControl = new JobControl(tester.controller().curator());
        this.applicationId = TenantAndApplicationId.from(instanceId);
        this.instanceId = instanceId;
        this.testerId = TesterId.of(instanceId);
        this.jobs = tester.controller().jobController();
        this.routing = tester.serviceRegistry().routingGeneratorMock();
        this.cloud = tester.serviceRegistry().testerCloud();
        this.runner = runner;
        this.tester = tester;
        this.readyJobsTrigger = new ReadyJobsTrigger(tester.controller(), maintenanceInterval, jobControl);
        this.nameServiceDispatcher = new NameServiceDispatcher(tester.controller(), maintenanceInterval, jobControl,
                                                               Integer.MAX_VALUE);
        createTenantAndApplication();
    }

    private void createTenantAndApplication() {
        try {
            var tenant = tester.createTenant(instanceId.tenant().value());
            tester.createApplication(tenant, instanceId.application().value(), instanceId.instance().value());
        } catch (IllegalArgumentException ignored) {
            // TODO(mpolden): Application already exists. Remove this once InternalDeploymentTester stops implicitly creating applications
        }
    }

    public Application application() {
        return tester.controller().applications().requireApplication(applicationId);
    }

    public Instance instance() {
        return tester.controller().applications().requireInstance(instanceId);
    }

    public ApplicationId instanceId() {
        return instanceId;
    }

    public DeploymentId deploymentIdIn(ZoneId zone) {
        return new DeploymentId(instanceId, zone);
    }


    /** Completely deploy the latest change */
    public DeploymentContext deploy() {
        assertNotNull("Application package submitted", lastSubmission);
        assertFalse("Submission is not already deployed", application().instances().values().stream()
                                                                       .anyMatch(instance -> instance.deployments().values().stream()
                                                                                                     .anyMatch(deployment -> deployment.applicationVersion().equals(lastSubmission))));
        assertEquals(lastSubmission, application().change().application().get());
        assertFalse(application().change().platform().isPresent());
        completeRollout();
        assertFalse(application().change().hasTargets());
        return this;
    }

    /** Upgrade platform of this to given version */
    public DeploymentContext deployPlatform(Version version) {
        assertEquals(tester.controller().systemVersion(), version);
        assertFalse(application().instances().values().stream()
                          .anyMatch(instance -> instance.deployments().values().stream()
                                                        .anyMatch(deployment -> deployment.version().equals(version))));
        assertEquals(version, application().change().platform().get());
        assertFalse(application().change().application().isPresent());

        completeRollout();

        assertTrue(application().productionDeployments().values().stream()
                         .allMatch(deployments -> deployments.stream()
                                                             .allMatch(deployment -> deployment.version().equals(version))));

        for (JobType type : new DeploymentSteps(application().deploymentSpec(), tester.controller()::system).productionJobs())
            assertTrue(tester.configServer().nodeRepository()
                             .list(type.zone(tester.controller().system()), applicationId.defaultInstance()).stream() // TODO jonmv: support more
                             .allMatch(node -> node.currentVersion().equals(version)));

        assertFalse(application().change().hasTargets());
        return this;
    }


    /** Defer DNS updates */
    public DeploymentContext deferDnsUpdates() {
        deferDnsUpdates = true;
        return this;
    }

    /** Flush all pending DNS updates */
    public DeploymentContext flushDnsUpdates() {
        nameServiceDispatcher.run();
        assertTrue("All name service requests dispatched",
                   tester.controller().curator().readNameServiceQueue().requests().isEmpty());
        return this;
    }

    /** Submit given application package for deployment */
    public DeploymentContext submit(ApplicationPackage applicationPackage) {
        return submit(applicationPackage, BuildJob.defaultSourceRevision);
    }

    /** Submit given application package for deployment */
    public DeploymentContext submit(ApplicationPackage applicationPackage, SourceRevision sourceRevision) {
        var projectId = tester.controller().applications()
                              .requireApplication(applicationId)
                              .projectId()
                              .orElseThrow(() -> new IllegalArgumentException("No project ID set for " + applicationId));
        lastSubmission = jobs.submit(applicationId, sourceRevision, "a@b", projectId, applicationPackage, new byte[0]);
        return this;
    }


    /** Submit the default application package for deployment */
    public DeploymentContext submit() {
        return submit(tester.controller().system().isPublic() ? publicCdApplicationPackage : applicationPackage);
    }

    /** Trigger all outstanding jobs, if any */
    public DeploymentContext triggerJobs() {
        while (tester.controller().applications().deploymentTrigger().triggerReadyJobs() > 0);
        return this;
    }

    /** Fail current deployment in given job */
    public DeploymentContext failDeployment(JobType type) {
        triggerJobs();
        var job = jobId(type);
        RunId id = currentRun(job).id();
        configServer().throwOnNextPrepare(new IllegalArgumentException("Exception"));
        runner.advance(currentRun(job));
        assertTrue(jobs.run(id).get().hasFailed());
        assertTrue(jobs.run(id).get().hasEnded());
        doTeardown(job);
        return this;
    }

    /** Returns the last submitted application version */
    public Optional<ApplicationVersion> lastSubmission() {
        return Optional.ofNullable(lastSubmission);
    }

    /** Runs and returns all remaining jobs for the application, at most once, and asserts the current change is rolled out. */
    public DeploymentContext completeRollout() {
        readyJobsTrigger.run();
        Set<JobType> jobs = new HashSet<>();
        List<Run> activeRuns;
        while ( ! (activeRuns = this.jobs.active(applicationId)).isEmpty())
            for (Run run : activeRuns)
                if (jobs.add(run.id().type())) {
                    runJob(run.id().type());
                    readyJobsTrigger.run();
                }
                else
                    throw new AssertionError("Job '" + run.id().type() + "' was run twice for '" + instanceId + "'");

        assertFalse("Change should have no targets, but was " + application().change(), application().change().hasTargets());
        if (!deferDnsUpdates) {
            flushDnsUpdates();
        }
        return this;
    }

    /** Pulls the ready job trigger, and then runs the whole of the given job, successfully. */
    public DeploymentContext runJob(JobType type) {
        var job = jobId(type);
        triggerJobs();
        doDeploy(job);
        doUpgrade(job);
        doConverge(job);
        if (job.type().environment().isManuallyDeployed())
            return this;
        doInstallTester(job);
        doTests(job);
        doTeardown(job);
        return this;
    }

    /** Simulate upgrade time out in given job */
    public DeploymentContext timeOutUpgrade(JobType type) {
        var job = jobId(type);
        triggerJobs();
        RunId id = currentRun(job).id();
        doDeploy(job);
        tester.clock().advance(InternalStepRunner.installationTimeout.plusSeconds(1));
        runner.advance(currentRun(job));
        assertTrue(jobs.run(id).get().hasFailed());
        assertTrue(jobs.run(id).get().hasEnded());
        doTeardown(job);
        return this;
    }

    /** Simulate convergence time out in given job */
    public void timeOutConvergence(JobType type) {
        var job = jobId(type);
        triggerJobs();
        RunId id = currentRun(job).id();
        doDeploy(job);
        doUpgrade(job);
        tester.clock().advance(InternalStepRunner.installationTimeout.plusSeconds(1));
        runner.advance(currentRun(job));
        assertTrue(jobs.run(id).get().hasFailed());
        assertTrue(jobs.run(id).get().hasEnded());
        doTeardown(job);
    }

    /** Sets a single endpoint in the routing layer for the instance in this */
    public DeploymentContext setEndpoints(ZoneId zone) {
        return setEndpoints(zone, false);
    }

    /** Deploy default application package, start a run for that change and return its ID */
    public RunId newRun(JobType type) {
        assertFalse(application().internal()); // Use this only once per test.
        submit();
        readyJobsTrigger.maintain();

        if (type.isProduction()) {
            runJob(JobType.systemTest);
            runJob(JobType.stagingTest);
            readyJobsTrigger.maintain();
        }

        Run run = jobs.active().stream()
                      .filter(r -> r.id().type() == type)
                      .findAny()
                      .orElseThrow(() -> new AssertionError(type + " is not among the active: " + jobs.active()));
        return run.id();
    }

    /** Start tests in system test stage */
    public RunId startSystemTestTests() {
        RunId id = newRun(JobType.systemTest);
        runner.run();
        configServer().convergeServices(instanceId, JobType.systemTest.zone(tester.controller().system()));
        configServer().convergeServices(testerId.id(), JobType.systemTest.zone(tester.controller().system()));
        setEndpoints(JobType.systemTest.zone(tester.controller().system()));
        setTesterEndpoints(JobType.systemTest.zone(tester.controller().system()));
        runner.run();
        assertEquals(unfinished, jobs.run(id).get().steps().get(Step.endTests));
        return id;
    }

    /** Deploys tester and real app, and completes initial staging installation first if needed. */
    private void doDeploy(JobId job) {
        RunId id = currentRun(job).id();
        ZoneId zone = zone(job);
        DeploymentId deployment = new DeploymentId(job.application(), zone);

        // First steps are always deployments.
        runner.advance(currentRun(job));

        if (job.type() == JobType.stagingTest) { // Do the initial deployment and installation of the real application.
            assertEquals(unfinished, jobs.run(id).get().steps().get(Step.installInitialReal));
            Versions versions = currentRun(job).versions();
            tester.configServer().nodeRepository().doUpgrade(deployment, Optional.empty(), versions.sourcePlatform().orElse(versions.targetPlatform()));
            configServer().convergeServices(id.application(), zone);
            setEndpoints(zone);
            runner.advance(currentRun(job));
            assertEquals(Step.Status.succeeded, jobs.run(id).get().steps().get(Step.installInitialReal));
        }
    }

    /** Upgrades nodes to target version. */
    private void doUpgrade(JobId job) {
        RunId id = currentRun(job).id();
        ZoneId zone = zone(job);
        DeploymentId deployment = new DeploymentId(job.application(), zone);

        assertEquals(unfinished, jobs.run(id).get().steps().get(Step.installReal));
        configServer().nodeRepository().doUpgrade(deployment, Optional.empty(), currentRun(job).versions().targetPlatform());
        runner.advance(currentRun(job));
    }

    /** Returns the current run for the given job type, and verifies it is still running normally. */
    private Run currentRun(JobId job) {
        Run run = jobs.last(job)
                      .filter(r -> r.id().type() == job.type())
                      .orElseThrow(() -> new AssertionError(job.type() + " is not among the active: " + jobs.active()));
        assertFalse(run.hasFailed());
        assertFalse(run.hasEnded());
        return run;
    }

    /** Sets a single endpoint in the routing layer for the tester instance in this */
    private DeploymentContext setTesterEndpoints(ZoneId zone) {
        return setEndpoints(zone, true);
    }

    /** Sets a single endpoint in the routing layer; this matches that required for the tester */
    private DeploymentContext setEndpoints(ZoneId zone, boolean tester) {
        var id = instanceId;
        if (tester) {
            id = testerId.id();
        }
        routing.putEndpoints(new DeploymentId(id, zone),
                             Collections.singletonList(new RoutingEndpoint(String.format("https://%s--%s--%s.%s.%s.vespa:43",
                                                                                         id.instance().value(),
                                                                                         id.application().value(),
                                                                                         id.tenant().value(),
                                                                                         zone.region().value(),
                                                                                         zone.environment().value()),
                                                                           "host1",
                                                                           false,
                                                                           String.format("cluster1.%s.%s.%s.%s",
                                                                                         id.application().value(),
                                                                                         id.tenant().value(),
                                                                                         zone.region().value(),
                                                                                         zone.environment().value()))));
        return this;
    }

    /** Lets nodes converge on new application version. */
    private void doConverge(JobId job) {
        RunId id = currentRun(job).id();
        ZoneId zone = zone(job);

        assertEquals(unfinished, jobs.run(id).get().steps().get(Step.installReal));
        configServer().convergeServices(id.application(), zone);
        setEndpoints(zone);
        runner.advance(currentRun(job));
        if (job.type().environment().isManuallyDeployed()) {
            assertEquals(Step.Status.succeeded, jobs.run(id).get().steps().get(Step.installReal));
            assertTrue(jobs.run(id).get().hasEnded());
            return;
        }
        assertEquals(Step.Status.succeeded, jobs.run(id).get().steps().get(Step.installReal));
    }

    /** Installs tester and starts tests. */
    private void doInstallTester(JobId job) {
        RunId id = currentRun(job).id();
        ZoneId zone = zone(job);

        assertEquals(unfinished, jobs.run(id).get().steps().get(Step.installTester));
        configServer().nodeRepository().doUpgrade(new DeploymentId(TesterId.of(job.application()).id(), zone), Optional.empty(), currentRun(job).versions().targetPlatform());
        runner.advance(currentRun(job));
        assertEquals(unfinished, jobs.run(id).get().steps().get(Step.installTester));
        configServer().convergeServices(TesterId.of(id.application()).id(), zone);
        runner.advance(currentRun(job));
        assertEquals(unfinished, jobs.run(id).get().steps().get(Step.installTester));
        setTesterEndpoints(zone);
        runner.advance(currentRun(job));
    }

    /** Completes tests with success. */
    private void doTests(JobId job) {
        RunId id = currentRun(job).id();
        ZoneId zone = zone(job);

        // All installation is complete and endpoints are ready, so tests may begin.
        assertEquals(Step.Status.succeeded, jobs.run(id).get().steps().get(Step.installReal));
        assertEquals(Step.Status.succeeded, jobs.run(id).get().steps().get(Step.installTester));
        assertEquals(Step.Status.succeeded, jobs.run(id).get().steps().get(Step.startTests));

        assertEquals(unfinished, jobs.run(id).get().steps().get(Step.endTests));
        cloud.set(TesterCloud.Status.SUCCESS);
        runner.advance(currentRun(job));
        assertTrue(jobs.run(id).get().hasEnded());
        assertFalse(jobs.run(id).get().hasFailed());
        assertEquals(job.type().isProduction(), instance().deployments().containsKey(zone));
        assertTrue(configServer().nodeRepository().list(zone, TesterId.of(id.application()).id()).isEmpty());
    }

    /** Removes endpoints from routing layer — always call this. */
    private void doTeardown(JobId job) {
        ZoneId zone = zone(job);
        DeploymentId deployment = new DeploymentId(job.application(), zone);

        if ( ! instance().deployments().containsKey(zone))
            routing.removeEndpoints(deployment);
        routing.removeEndpoints(new DeploymentId(TesterId.of(job.application()).id(), zone));
    }

    private JobId jobId(JobType type) {
        return new JobId(instanceId, type);
    }

    private ZoneId zone(JobId job) {
        return job.type().zone(tester.controller().system());
    }

    private ConfigServerMock configServer() {
        return tester.configServer();
    }

    private static X509Certificate generateCertificate() {
        KeyPair keyPair = KeyUtils.generateKeypair(KeyAlgorithm.EC, 256);
        X500Principal subject = new X500Principal("CN=subject");
        return X509CertificateBuilder.fromKeypair(keyPair,
                                                  subject,
                                                  Instant.now(),
                                                  Instant.now().plusSeconds(1),
                                                  SignatureAlgorithm.SHA512_WITH_ECDSA,
                                                  BigInteger.valueOf(1))
                                     .build();
    }

}
