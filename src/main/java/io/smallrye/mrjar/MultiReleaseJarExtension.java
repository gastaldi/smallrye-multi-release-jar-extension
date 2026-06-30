package io.smallrye.mrjar;

import java.io.File;
import java.util.Properties;
import java.util.TreeSet;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

@Named("multi-release-jar")
@Singleton
public class MultiReleaseJarExtension extends AbstractMavenLifecycleParticipant {

    static final int DEFAULT_BASELINE = 17;
    static final String SKIP_PROPERTY = "smallrye.mr-jar.skip";
    static final String BASELINE_PROPERTY = "smallrye.mr-jar.baseline";

    private static final String SUREFIRE_GROUP_ID = "org.apache.maven.plugins";
    private static final String SUREFIRE_ARTIFACT_ID = "maven-surefire-plugin";
    private static final String COMPILER_GROUP_ID = "org.apache.maven.plugins";
    private static final String COMPILER_ARTIFACT_ID = "maven-compiler-plugin";
    private static final String IMPSORT_GROUP_ID = "net.revelc.code";
    private static final String IMPSORT_ARTIFACT_ID = "impsort-maven-plugin";
    private static final String FORMATTER_GROUP_ID = "net.revelc.code.formatter";
    private static final String FORMATTER_ARTIFACT_ID = "formatter-maven-plugin";

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        int currentJdk = Runtime.version().feature();

        for (MavenProject project : session.getProjects()) {
            if (isSkipped(project)) {
                continue;
            }

            int baseline = getBaseline(project);
            TreeSet<Integer> mrVersions = detectMrVersions(project, baseline, currentJdk);

            if (mrVersions.isEmpty()) {
                continue;
            }

            log(project, "Detected multi-release JAR versions: " + mrVersions + " (JDK " + currentJdk + ")");

            configureMrBuilds(project, mrVersions);
            configureTestClasspath(project, currentJdk, baseline);
            configureCrossJdkTests(project, session.getUserProperties(), currentJdk, baseline);
        }
    }

    static TreeSet<Integer> detectMrVersions(MavenProject project, int baseline, int currentJdk) {
        TreeSet<Integer> versions = new TreeSet<>();
        File basedir = project.getBasedir();
        if (basedir == null) {
            return versions;
        }
        for (int v = baseline; v <= currentJdk; v++) {
            File sourceDir = new File(basedir, "src/main/java" + v);
            if (sourceDir.isDirectory()) {
                versions.add(v);
            }
        }
        return versions;
    }

    static void configureMrBuilds(MavenProject project, TreeSet<Integer> mrVersions) {
        Build build = getOrCreateBuild(project);

        for (int version : mrVersions) {
            String sourceRoot = new File(project.getBasedir(), "src/main/java" + version).getAbsolutePath();
            project.addCompileSourceRoot(sourceRoot);

            addCompilerExecution(build, project, version);

            if (hasPlugin(project, IMPSORT_GROUP_ID, IMPSORT_ARTIFACT_ID)) {
                addImpsortExecution(build, project, version);
            }
            if (hasPlugin(project, FORMATTER_GROUP_ID, FORMATTER_ARTIFACT_ID)) {
                addFormatterExecution(build, project, version);
            }
        }
    }

    static void configureTestClasspath(MavenProject project, int currentJdk, int baseline) {
        Build build = getOrCreateBuild(project);
        String outputDir = project.getBuild().getOutputDirectory();

        Plugin surefire = findOrCreatePlugin(build, SUREFIRE_GROUP_ID, SUREFIRE_ARTIFACT_ID);

        PluginExecution execution = findOrCreateExecution(surefire, "default-test");

        Xpp3Dom config = getOrCreateConfiguration(execution);

        setChild(config, "classesDirectory",
                outputDir + "/META-INF/versions/" + currentJdk);

        Xpp3Dom classpathElements = new Xpp3Dom("additionalClasspathElements");
        for (int v = currentJdk - 1; v >= baseline; v--) {
            addChild(classpathElements, "additionalClasspathElement",
                    outputDir + "/META-INF/versions/" + v);
        }
        addChild(classpathElements, "additionalClasspathElement", outputDir);
        config.addChild(classpathElements);
    }

    static void configureCrossJdkTests(MavenProject project, Properties userProperties,
            int currentJdk, int baseline) {
        Build build = getOrCreateBuild(project);
        String outputDir = project.getBuild().getOutputDirectory();
        Properties projectProps = project.getProperties();
        File basedir = project.getBasedir();
        if (basedir == null) {
            return;
        }

        for (int v = baseline; v < currentJdk; v++) {
            String homeProperty = "java" + v + ".home";
            String homeValue = userProperties.getProperty(homeProperty,
                    projectProps.getProperty(homeProperty));
            File markerFile = new File(basedir, "build-test-java" + v);

            if (homeValue != null && markerFile.exists()) {
                addCrossJdkTestExecution(build, v, homeValue, outputDir, baseline);
            }
        }
    }

    private static void addCompilerExecution(Build build, MavenProject project, int version) {
        Plugin compiler = findOrCreatePlugin(build, COMPILER_GROUP_ID, COMPILER_ARTIFACT_ID);

        PluginExecution execution = new PluginExecution();
        execution.setId("compile-java" + version);
        execution.setPhase("compile");
        execution.addGoal("compile");

        Xpp3Dom config = new Xpp3Dom("configuration");
        setChild(config, "release", String.valueOf(version));

        Xpp3Dom sourceRoots = new Xpp3Dom("compileSourceRoots");
        addChild(sourceRoots, "compileSourceRoot",
                project.getBasedir().getAbsolutePath() + "/src/main/java" + version);
        config.addChild(sourceRoots);

        setChild(config, "multiReleaseOutput", "true");

        execution.setConfiguration(config);
        compiler.addExecution(execution);
    }

    private static void addImpsortExecution(Build build, MavenProject project, int version) {
        Plugin impsort = findOrCreatePlugin(build, IMPSORT_GROUP_ID, IMPSORT_ARTIFACT_ID);

        PluginExecution execution = new PluginExecution();
        execution.setId("sort-imports-java" + version);
        execution.addGoal("sort");

        Xpp3Dom config = new Xpp3Dom("configuration");
        setChild(config, "sourceDirectory",
                project.getBasedir().getAbsolutePath() + "/src/main/java" + version);
        setChild(config, "compliance", String.valueOf(version));

        execution.setConfiguration(config);
        impsort.addExecution(execution);
    }

    private static void addFormatterExecution(Build build, MavenProject project, int version) {
        Plugin formatter = findOrCreatePlugin(build, FORMATTER_GROUP_ID, FORMATTER_ARTIFACT_ID);

        PluginExecution execution = new PluginExecution();
        execution.setId("format-sources-java" + version);
        execution.setPhase("process-sources");
        execution.addGoal("format");

        Xpp3Dom config = new Xpp3Dom("configuration");
        setChild(config, "sourceDirectory",
                project.getBasedir().getAbsolutePath() + "/src/main/java" + version);
        setChild(config, "compilerCompliance", String.valueOf(version));

        execution.setConfiguration(config);
        formatter.addExecution(execution);
    }

    private static void addCrossJdkTestExecution(Build build, int version, String javaHome,
            String outputDir, int baseline) {
        Plugin surefire = findOrCreatePlugin(build, SUREFIRE_GROUP_ID, SUREFIRE_ARTIFACT_ID);

        PluginExecution execution = new PluginExecution();
        execution.setId("java" + version + "-test");
        execution.setPhase("test");
        execution.addGoal("test");

        Xpp3Dom config = new Xpp3Dom("configuration");
        setChild(config, "jvm", javaHome + "/bin/java");
        setChild(config, "classesDirectory",
                outputDir + "/META-INF/versions/" + version);

        Xpp3Dom classpathElements = new Xpp3Dom("additionalClasspathElements");
        for (int v = version - 1; v >= baseline; v--) {
            addChild(classpathElements, "additionalClasspathElement",
                    outputDir + "/META-INF/versions/" + v);
        }
        addChild(classpathElements, "additionalClasspathElement", outputDir);
        config.addChild(classpathElements);

        execution.setConfiguration(config);
        surefire.addExecution(execution);
    }

    // -- Model helpers --

    static Build getOrCreateBuild(MavenProject project) {
        Build build = project.getModel().getBuild();
        if (build == null) {
            build = new Build();
            project.getModel().setBuild(build);
        }
        return build;
    }

    static Plugin findOrCreatePlugin(Build build, String groupId, String artifactId) {
        for (Plugin plugin : build.getPlugins()) {
            if (groupId.equals(plugin.getGroupId()) && artifactId.equals(plugin.getArtifactId())) {
                return plugin;
            }
        }
        Plugin plugin = new Plugin();
        plugin.setGroupId(groupId);
        plugin.setArtifactId(artifactId);
        build.addPlugin(plugin);
        return plugin;
    }

    static PluginExecution findOrCreateExecution(Plugin plugin, String executionId) {
        for (PluginExecution exec : plugin.getExecutions()) {
            if (executionId.equals(exec.getId())) {
                return exec;
            }
        }
        PluginExecution exec = new PluginExecution();
        exec.setId(executionId);
        plugin.addExecution(exec);
        return exec;
    }

    static Xpp3Dom getOrCreateConfiguration(PluginExecution execution) {
        Xpp3Dom config = (Xpp3Dom) execution.getConfiguration();
        if (config == null) {
            config = new Xpp3Dom("configuration");
            execution.setConfiguration(config);
        }
        return config;
    }

    static boolean hasPlugin(MavenProject project, String groupId, String artifactId) {
        Build build = project.getBuild();
        if (build != null) {
            for (Plugin plugin : build.getPlugins()) {
                if (groupId.equals(plugin.getGroupId()) && artifactId.equals(plugin.getArtifactId())) {
                    return true;
                }
            }
            if (build.getPluginManagement() != null) {
                for (Plugin plugin : build.getPluginManagement().getPlugins()) {
                    if (groupId.equals(plugin.getGroupId()) && artifactId.equals(plugin.getArtifactId())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static void setChild(Xpp3Dom parent, String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        parent.addChild(child);
    }

    static void addChild(Xpp3Dom parent, String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        parent.addChild(child);
    }

    private boolean isSkipped(MavenProject project) {
        return Boolean.parseBoolean(
                project.getProperties().getProperty(SKIP_PROPERTY, "false"));
    }

    static int getBaseline(MavenProject project) {
        String value = project.getProperties().getProperty(BASELINE_PROPERTY);
        if (value != null) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        return DEFAULT_BASELINE;
    }

    private void log(MavenProject project, String message) {
        System.out.println("[multi-release-jar] " + project.getArtifactId() + ": " + message);
    }
}
