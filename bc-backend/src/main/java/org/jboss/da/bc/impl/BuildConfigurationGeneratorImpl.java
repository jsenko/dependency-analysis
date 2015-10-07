package org.jboss.da.bc.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import org.jboss.da.bc.backend.api.POMInfo;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import org.apache.maven.scm.ScmException;
import org.jboss.da.bc.api.BuildConfigurationGenerator;
import org.jboss.da.bc.backend.api.BCSetGenerator;
import org.jboss.da.bc.backend.api.BcChecker;
import org.jboss.da.bc.backend.api.POMInfoGenerator;
import org.jboss.da.bc.backend.api.RepositoryCloner;
import org.jboss.da.bc.model.GeneratorEntity;
import org.jboss.da.bc.model.ProjectDetail;
import org.jboss.da.bc.model.ProjectHiearchy;
import org.jboss.da.communication.CommunicationException;
import org.jboss.da.communication.model.GAV;
import org.jboss.da.communication.pnc.api.PNCConnector;
import org.jboss.da.communication.pnc.model.BuildConfiguration;
import org.jboss.da.communication.pnc.model.BuildConfigurationCreate;
import org.jboss.da.communication.pom.PomAnalysisException;
import org.jboss.da.reports.api.SCMLocator;
import org.jboss.da.reports.backend.api.DependencyTreeGenerator;
import org.jboss.da.reports.backend.api.GAVToplevelDependencies;
import org.jboss.da.reports.backend.api.VersionFinder;
import org.jboss.da.scm.SCMType;
import org.slf4j.Logger;

/**
 *
 * @author Honza Brázdil <jbrazdil@redhat.com>
 */
@ApplicationScoped
public class BuildConfigurationGeneratorImpl implements BuildConfigurationGenerator {

    @Inject
    private Logger log;

    @Inject
    private PNCConnector pnc;

    @Inject
    private DependencyTreeGenerator depGenerator;

    @Inject
    private POMInfoGenerator pom;

    @Inject
    private BcChecker bcFinder;

    @Inject
    private VersionFinder versionFinder;

    @Inject
    private BCSetGenerator bcSetGenerator;

    @Inject
    private RepositoryCloner repoCloner;

    @Override
    public GeneratorEntity startBCGeneration(SCMLocator scm, String productName)
            throws CommunicationException, ScmException, PomAnalysisException {
        GAVToplevelDependencies deps = depGenerator.getToplevelDependencies(scm);
        Optional<POMInfo> pomInfo = pom.getPomInfo(scm.getScmUrl(), scm.getRevision(),
                scm.getPomPath());

        GeneratorEntity ge = new GeneratorEntity(scm, productName, deps.getGav());
        ge.setBcSetName(String.format("Build configuration set for %s", deps.getGav()));

        setProjectInfoFromPom(ge.getToplevelProject(), pomInfo);
        ge.getToplevelProject().setName(getName(deps.getGav()));

        ge.getToplevelBc().setDependencies(
                Optional.of(toProjectHierarchies(deps.getDependencies())));

        return ge;
    }

    @Override
    public GeneratorEntity iterateBCGeneration(GeneratorEntity projects)
            throws CommunicationException {
        iterateDependencies(projects.getToplevelBc());
        return projects;
    }

    @Override
    public void createBC(GeneratorEntity projects) throws Exception {
        Set<Integer> ids = create(projects.getToplevelBc());
        int productVersionId = bcSetGenerator.createProduct(projects.getName());
        bcSetGenerator.createBCSet(projects.getName(), productVersionId, new ArrayList(ids));
    }

    private ProjectHiearchy iterateDependencies(ProjectHiearchy hiearchy) throws CommunicationException {
        Optional<Set<ProjectHiearchy>> deps = hiearchy.getDependencies();
        if (hiearchy.isSelected() && deps.isPresent()) {
            if (deps.get().isEmpty()) {
                GAV gav = hiearchy.getProject().getGav();
                Optional<GAVToplevelDependencies> gavs = depGenerator.getToplevelDependencies(gav);
                hiearchy.setDependencies(gavs.map(x -> toProjectHierarchies(x.getDependencies())));
            } else {
                for (ProjectHiearchy dep : deps.get()) {
                    iterateDependencies(dep);
                }
            }
        }
        return hiearchy;
    }

    private Set toProjectHierarchies(Collection<GAV> gavs){
        return gavs.stream().map(gav -> toProjectHiearchy(gav)).collect(Collectors.toSet());
    }

    /**
     * Creates new ProjectHiearchy from GAV.
     * Tries to get pom file for GAV and if
     */
    private ProjectHiearchy toProjectHiearchy(GAV gav) {
        ProjectDetail project = new ProjectDetail(gav);
        project.setName(getName(gav));
        tryGetInfoFromPom(project, gav);

        try {
            project.setInternallyBuilt(versionFinder.getBestMatchVersionFor(gav));
        } catch (CommunicationException ex) {
            log.error("Could not obtain best match version", ex);
        }

        ProjectHiearchy ph = new ProjectHiearchy(project, false);
        return ph;
    }

    /**
     * Tries to get pomfile for given GAV and fill Project detail with informations from pomfile.
     * Also tries to find if build configuration for this GAV already exists.
     */
    private void tryGetInfoFromPom(ProjectDetail project, GAV gav) {
        try {
            Optional<POMInfo> pomInfo = pom.getPomInfo(gav);

            setProjectInfoFromPom(project, pomInfo);
            setSCMData(project, pomInfo);

            Optional<BuildConfiguration> bc = findExistingBuildConfiguration(pomInfo);
            project.setBcExists(bc.isPresent());
            project.setUseExistingBc(bc.isPresent());
        } catch (Exception ex) {
            log.error("Could not obtain pom info.", ex);
        }
    }

    private Set<Integer> create(ProjectHiearchy hiearchy) throws Exception {
        Set<Integer> deps = new HashSet<>();
        if (hiearchy.isSelected()) {

            for (ProjectHiearchy dep : hiearchy.getDependencies().orElseGet(Collections::emptySet)) {
                deps.addAll(create(dep));
            }

            ProjectDetail project = hiearchy.getProject();
            BuildConfigurationCreate bcc;
            if(project.isUseExistingBc()){
                Optional<BuildConfiguration> bc = bcFinder.lookupBcByScm(project.getScmUrl(), project.getScmRevision());
                bcc = bc.orElseThrow(() -> new IllegalStateException("useExistingBC is true, but there is no BC to use."));
            }else{
                bcc = toBC(project, deps);
                if(project.isCloneRepo()){
                    String newScmUrl = repoCloner.cloneRepository(project.getScmUrl(), project.getScmRevision(), SCMType.GIT, "Repository of " + project.getGav());
                    bcc.setScmRepoURL(newScmUrl);
                }
            }
            BuildConfiguration bc = pnc.createBuildConfiguration(bcc);
            deps.add(bc.getId());
        }
        return deps;
    }

    private String getDescription(POMInfo pomInfo) {
        Optional<String> name = pomInfo.getName();

        return name.map(n -> String.format("Build Configuration for %s - %s.", pomInfo.getGav(), n))
                   .orElse(String.format("Build Configuration for %s.", pomInfo.getGav()));
    }

    private String getName(GAV gav) {
        return String.format("%s-%s", gav.getArtifactId(), gav.getVersion());
    }

    private BuildConfigurationCreate toBC(ProjectDetail project, Set<Integer> deps) {
        BuildConfigurationCreate bc = new BuildConfigurationCreate();
        bc.setBuildScript(project.getBuildScript());
        bc.setDependencyIds(new ArrayList<>(deps));
        bc.setDescription(project.getDescription());
        bc.setEnvironmentId(project.getEnvironmentId());
        bc.setName(project.getName());
        bc.setProjectId(project.getProjectId());
        bc.setScmRepoURL(project.getScmUrl());
        bc.setScmRevision(project.getScmRevision());
        return bc;
    }

    private void setSCMData(ProjectDetail project, Optional<POMInfo> pomInfo) {
        pomInfo.flatMap(POMInfo::getScmURL).ifPresent(url -> project.setScmUrl(url));
        pomInfo.flatMap(POMInfo::getScmURL).ifPresent(revison -> project.setScmUrl(revison));
    }

    private void setProjectInfoFromPom(ProjectDetail project, Optional<POMInfo> pomInfo) {
        pomInfo.ifPresent(info -> project.setDescription(getDescription(info)));
    }

    private Optional<BuildConfiguration> findExistingBuildConfiguration(Optional<POMInfo> pomInfo) throws Exception {
        Optional<String> url = pomInfo.flatMap(POMInfo::getScmURL);
        Optional<String> revision = pomInfo.flatMap(POMInfo::getScmRevision);
        if(url.isPresent() && revision.isPresent()){
            return bcFinder.lookupBcByScm(url.get(), revision.get());
        }else{
            return Optional.empty();
        }
    }
}