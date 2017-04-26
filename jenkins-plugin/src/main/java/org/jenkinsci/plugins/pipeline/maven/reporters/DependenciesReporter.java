package org.jenkinsci.plugins.pipeline.maven.reporters;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.FingerprintMap;
import hudson.model.Run;
import hudson.model.StreamBuildListener;
import hudson.model.TaskListener;
import hudson.tasks.Fingerprinter;
import jenkins.model.ArtifactManager;
import jenkins.model.Jenkins;
import jenkins.util.BuildListenerAdapter;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.pipeline.maven.MavenSpyLogProcessor;
import org.jenkinsci.plugins.pipeline.maven.ResultsReporter;
import org.jenkinsci.plugins.pipeline.maven.util.XmlUtils;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.w3c.dom.Element;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:cleclerc@cloudbees.com">Cyrille Le Clerc</a>
 */
public class DependenciesReporter implements ResultsReporter{
    private static final Logger LOGGER = Logger.getLogger(MavenSpyLogProcessor.class.getName());

    @Override
    public void process(@Nonnull StepContext context, @Nonnull Element mavenSpyLogsElt) throws IOException, InterruptedException {
        Run run = context.get(Run.class);
        ArtifactManager artifactManager = run.pickArtifactManager();
        Launcher launcher = context.get(Launcher.class);
        TaskListener listener = context.get(TaskListener.class);
        if (listener == null) {
            LOGGER.warning("TaskListener is NULL, default to stderr");
            listener = new StreamBuildListener((OutputStream) System.err);
        }
        FilePath workspace = context.get(FilePath.class); // TODO check that it's the good workspace

        List<MavenSpyLogProcessor.MavenArtifact> mavenArtifacts = listArtifactDependencies(mavenSpyLogsElt);

        Map<String, String> artifactsToArchive = new HashMap<>(); // artifactPathInArchiveZone -> artifactPathInWorkspace
        Map<String, String> artifactsToFingerPrint = new HashMap<>(); // artifactPathInArchiveZone -> artifactMd5
        for (MavenSpyLogProcessor.MavenArtifact mavenArtifact : mavenArtifacts) {
            try {
                if (StringUtils.isEmpty(mavenArtifact.file)) {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        listener.getLogger().println("[withMaven] Can't archive maven artifact with no file attached: " + mavenArtifact);
                    }
                    continue;
                } else if (!(mavenArtifact.file.endsWith("." + mavenArtifact.extension))) {
                    FilePath mavenGeneratedArtifact = workspace.child(XmlUtils.getPathInWorkspace(mavenArtifact.file, workspace));
                    if (mavenGeneratedArtifact.isDirectory()) {
                        if (LOGGER.isLoggable(Level.FINE)) {
                            listener.getLogger().println("[withMaven] Skip archiving for generated maven artifact of type directory (it's likely to be target/classes, see JENKINS-43714) " + mavenArtifact);
                        }
                        continue;
                    }
                }

                String artifactPathInArchiveZone =
                        mavenArtifact.groupId.replace('.', '/') + "/" +
                                mavenArtifact.artifactId + "/" +
                                mavenArtifact.version + "/" +
                                mavenArtifact.getFileName();

                String artifactPathInWorkspace = XmlUtils.getPathInWorkspace(mavenArtifact.file, workspace);

                if (StringUtils.isEmpty(artifactPathInWorkspace)) {
                    listener.error("[withMaven] Invalid path in the workspace (" + workspace.getRemote() + ") for artifact " + mavenArtifact);
                } else {
                    FilePath artifactFilePath = new FilePath(workspace, artifactPathInWorkspace);
                    if (artifactFilePath.exists()) {
                        // the subsequent call to digest could test the existence but we don't want to prematurely optimize performances
                        listener.getLogger().println("[withMaven] Archive artifact " + artifactPathInWorkspace + " under " + artifactPathInArchiveZone);
                        artifactsToArchive.put(artifactPathInArchiveZone, artifactPathInWorkspace);
                        String artifactDigest = artifactFilePath.digest();
                        artifactsToFingerPrint.put(artifactPathInArchiveZone, artifactDigest);
                    } else {
                        listener.getLogger().println("[withMaven] FAILURE to archive " + artifactPathInWorkspace + " under " + artifactPathInArchiveZone + ", file not found");
                    }
                }
            } catch (IOException | RuntimeException e) {
                listener.error("[withMaven] WARNING: Exception archiving and fingerprinting " + mavenArtifact + ", skip archiving of the artifacts");
                e.printStackTrace(listener.getLogger());
                listener.getLogger().flush();
            }
        }
        LOGGER.log(Level.FINE, "Archive and fingerprint {0}", artifactsToArchive);

        // FINGERPRINT GENERATED MAVEN ARTIFACT
        FingerprintMap fingerprintMap = Jenkins.getInstance().getFingerprintMap();
        for (Map.Entry<String, String> artifactToFingerprint : artifactsToFingerPrint.entrySet()) {
            String artifactPathInArchiveZone = artifactToFingerprint.getKey();
            String artifactMd5 = artifactToFingerprint.getValue();
            fingerprintMap.getOrCreate(run, artifactPathInArchiveZone, artifactMd5);
        }

        // add action
        Fingerprinter.FingerprintAction fingerprintAction = run.getAction(Fingerprinter.FingerprintAction.class);
        if (fingerprintAction == null) {
            run.addAction(new Fingerprinter.FingerprintAction(run, artifactsToFingerPrint));
        } else {
            fingerprintAction.add(artifactsToFingerPrint);
        }
    }

    /**
     * @param mavenSpyLogs Root XML element
     * @return list of {@link MavenSpyLogProcessor.MavenArtifact}
     */
    /*
     */
    @Nonnull
    public List<MavenSpyLogProcessor.MavenArtifact> listArtifactDependencies(Element mavenSpyLogs) {

        List<MavenSpyLogProcessor.MavenArtifact> result = new ArrayList<>();

        for (Element dependencyResolutionResult : XmlUtils.getChildrenElements(mavenSpyLogs, "DependencyResolutionResult")) {
            Element resolvedDependenciesElt = XmlUtils.getUniqueChildElementOrNull(dependencyResolutionResult, "resolvedDependencies");

            if(resolvedDependenciesElt == null) {
                continue;
            }

            for(Element dependencyElt : XmlUtils.getChildrenElements(resolvedDependenciesElt, "dependency")) {
                MavenSpyLogProcessor.MavenArtifact dependencyArtifact = XmlUtils.newMavenArtifact(dependencyElt);

                Element fileElt = XmlUtils.getUniqueChildElementOrNull(dependencyElt, "file");
                if (fileElt.getTextContent() == null || fileElt.getTextContent().isEmpty()) {
                    LOGGER.log(Level.WARNING, "listArtifactDependencies: no associated file found for " + dependencyArtifact + " in " + XmlUtils.toString(dependencyElt));
                }
                dependencyArtifact.file = StringUtils.trim(fileElt.getTextContent());

                result.add(dependencyArtifact);
            }
        }

        return result;
    }
}
