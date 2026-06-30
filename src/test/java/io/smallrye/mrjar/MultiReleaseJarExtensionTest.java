package io.smallrye.mrjar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultiReleaseJarExtensionTest {

    @TempDir
    Path tempDir;

    MavenProject project;

    @BeforeEach
    void setUp() {
        Model model = new Model();
        model.setGroupId("io.test");
        model.setArtifactId("test-project");
        model.setVersion("1.0.0");

        Build build = new Build();
        build.setOutputDirectory(tempDir.resolve("target/classes").toString());
        model.setBuild(build);

        project = new MavenProject(model);
        project.setFile(tempDir.resolve("pom.xml").toFile());
    }

    @Test
    void detectMrVersions_findsExistingSourceDirs() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java17"));
        Files.createDirectories(tempDir.resolve("src/main/java21"));

        TreeSet<Integer> versions = MultiReleaseJarExtension.detectMrVersions(project, 17, 25);

        assertThat(versions).containsExactly(17, 21);
    }

    @Test
    void detectMrVersions_emptyWhenNoSourceDirs() {
        TreeSet<Integer> versions = MultiReleaseJarExtension.detectMrVersions(project, 17, 25);

        assertThat(versions).isEmpty();
    }

    @Test
    void detectMrVersions_respectsBaseline() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java11"));
        Files.createDirectories(tempDir.resolve("src/main/java17"));

        TreeSet<Integer> versions = MultiReleaseJarExtension.detectMrVersions(project, 17, 25);

        assertThat(versions).containsExactly(17);
    }

    @Test
    void detectMrVersions_respectsCurrentJdk() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java21"));
        Files.createDirectories(tempDir.resolve("src/main/java25"));

        TreeSet<Integer> versions = MultiReleaseJarExtension.detectMrVersions(project, 17, 21);

        assertThat(versions).containsExactly(21);
    }

    @Test
    void configureMrBuilds_addsCompilerExecution() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java21"));

        TreeSet<Integer> versions = new TreeSet<>();
        versions.add(21);

        MultiReleaseJarExtension.configureMrBuilds(project, versions);

        Build build = project.getModel().getBuild();
        Plugin compiler = findPlugin(build, "org.apache.maven.plugins", "maven-compiler-plugin");
        assertThat(compiler).isNotNull();

        PluginExecution exec = findExecution(compiler, "compile-java21");
        assertThat(exec).isNotNull();
        assertThat(exec.getPhase()).isEqualTo("compile");
        assertThat(exec.getGoals()).containsExactly("compile");

        Xpp3Dom config = (Xpp3Dom) exec.getConfiguration();
        assertThat(config.getChild("release").getValue()).isEqualTo("21");
        assertThat(config.getChild("multiReleaseOutput").getValue()).isEqualTo("true");
        assertThat(config.getChild("compileSourceRoots").getChild("compileSourceRoot").getValue())
                .endsWith("/src/main/java21");
    }

    @Test
    void configureMrBuilds_addsMultipleVersions() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java17"));
        Files.createDirectories(tempDir.resolve("src/main/java21"));

        TreeSet<Integer> versions = new TreeSet<>();
        versions.add(17);
        versions.add(21);

        MultiReleaseJarExtension.configureMrBuilds(project, versions);

        Build build = project.getModel().getBuild();
        Plugin compiler = findPlugin(build, "org.apache.maven.plugins", "maven-compiler-plugin");

        assertThat(findExecution(compiler, "compile-java17")).isNotNull();
        assertThat(findExecution(compiler, "compile-java21")).isNotNull();
    }

    @Test
    void configureMrBuilds_skipsImpsortWhenNotDeclared() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java21"));

        TreeSet<Integer> versions = new TreeSet<>();
        versions.add(21);

        MultiReleaseJarExtension.configureMrBuilds(project, versions);

        Build build = project.getModel().getBuild();
        Plugin impsort = findPlugin(build, "net.revelc.code", "impsort-maven-plugin");
        assertThat(impsort).isNull();
    }

    @Test
    void configureMrBuilds_addsImpsortWhenDeclaredInPluginManagement() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java21"));

        PluginManagement pm = new PluginManagement();
        Plugin impsortManaged = new Plugin();
        impsortManaged.setGroupId("net.revelc.code");
        impsortManaged.setArtifactId("impsort-maven-plugin");
        impsortManaged.setVersion("1.12.0");
        pm.addPlugin(impsortManaged);
        project.getBuild().setPluginManagement(pm);

        TreeSet<Integer> versions = new TreeSet<>();
        versions.add(21);

        MultiReleaseJarExtension.configureMrBuilds(project, versions);

        Build build = project.getModel().getBuild();
        Plugin impsort = findPlugin(build, "net.revelc.code", "impsort-maven-plugin");
        assertThat(impsort).isNotNull();

        PluginExecution exec = findExecution(impsort, "sort-imports-java21");
        assertThat(exec).isNotNull();
        assertThat(exec.getGoals()).containsExactly("sort");

        Xpp3Dom config = (Xpp3Dom) exec.getConfiguration();
        assertThat(config.getChild("sourceDirectory").getValue()).endsWith("/src/main/java21");
        assertThat(config.getChild("compliance").getValue()).isEqualTo("21");
    }

    @Test
    void configureMrBuilds_addsFormatterWhenDeclared() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java21"));

        PluginManagement pm = new PluginManagement();
        Plugin fmtManaged = new Plugin();
        fmtManaged.setGroupId("net.revelc.code.formatter");
        fmtManaged.setArtifactId("formatter-maven-plugin");
        fmtManaged.setVersion("2.29.0");
        pm.addPlugin(fmtManaged);
        project.getBuild().setPluginManagement(pm);

        TreeSet<Integer> versions = new TreeSet<>();
        versions.add(21);

        MultiReleaseJarExtension.configureMrBuilds(project, versions);

        Build build = project.getModel().getBuild();
        Plugin formatter = findPlugin(build, "net.revelc.code.formatter", "formatter-maven-plugin");
        assertThat(formatter).isNotNull();

        PluginExecution exec = findExecution(formatter, "format-sources-java21");
        assertThat(exec).isNotNull();
        assertThat(exec.getPhase()).isEqualTo("process-sources");
        assertThat(exec.getGoals()).containsExactly("format");
    }

    @Test
    void configureTestClasspath_configuresDefaultTestExecution() {
        MultiReleaseJarExtension.configureTestClasspath(project, 21, 17);

        Build build = project.getModel().getBuild();
        Plugin surefire = findPlugin(build, "org.apache.maven.plugins", "maven-surefire-plugin");
        assertThat(surefire).isNotNull();

        PluginExecution exec = findExecution(surefire, "default-test");
        assertThat(exec).isNotNull();

        Xpp3Dom config = (Xpp3Dom) exec.getConfiguration();
        assertThat(config.getChild("classesDirectory").getValue())
                .endsWith("/META-INF/versions/21");

        Xpp3Dom elements = config.getChild("additionalClasspathElements");
        assertThat(elements.getChildCount()).isEqualTo(5);
        assertThat(elements.getChild(0).getValue()).contains("/META-INF/versions/20");
        assertThat(elements.getChild(1).getValue()).contains("/META-INF/versions/19");
        assertThat(elements.getChild(2).getValue()).contains("/META-INF/versions/18");
        assertThat(elements.getChild(3).getValue()).contains("/META-INF/versions/17");
        assertThat(elements.getChild(4).getValue()).doesNotContain("/META-INF/versions/");
    }

    @Test
    void configureTestClasspath_baseline17_jdk17() {
        MultiReleaseJarExtension.configureTestClasspath(project, 17, 17);

        Build build = project.getModel().getBuild();
        Plugin surefire = findPlugin(build, "org.apache.maven.plugins", "maven-surefire-plugin");
        PluginExecution exec = findExecution(surefire, "default-test");
        Xpp3Dom config = (Xpp3Dom) exec.getConfiguration();

        assertThat(config.getChild("classesDirectory").getValue())
                .endsWith("/META-INF/versions/17");

        Xpp3Dom elements = config.getChild("additionalClasspathElements");
        assertThat(elements.getChildCount()).isEqualTo(1);
        assertThat(elements.getChild(0).getValue()).doesNotContain("/META-INF/versions/");
    }

    @Test
    void configureCrossJdkTests_addsExecutionWhenConditionsMet() throws Exception {
        Files.createFile(tempDir.resolve("build-test-java17"));

        Properties userProps = new Properties();
        userProps.setProperty("java17.home", "/opt/jdk-17");

        MavenExecutionRequest request = new DefaultMavenExecutionRequest();
        request.setUserProperties(userProps);
        MavenSession session = new MavenSession(null, request, null, List.of(project));

        MultiReleaseJarExtension.configureCrossJdkTests(project, session, 21, 17);

        Build build = project.getModel().getBuild();
        Plugin surefire = findPlugin(build, "org.apache.maven.plugins", "maven-surefire-plugin");
        assertThat(surefire).isNotNull();

        PluginExecution exec = findExecution(surefire, "java17-test");
        assertThat(exec).isNotNull();
        assertThat(exec.getPhase()).isEqualTo("test");

        Xpp3Dom config = (Xpp3Dom) exec.getConfiguration();
        assertThat(config.getChild("jvm").getValue()).isEqualTo("/opt/jdk-17/bin/java");
        assertThat(config.getChild("classesDirectory").getValue())
                .endsWith("/META-INF/versions/17");

        Xpp3Dom elements = config.getChild("additionalClasspathElements");
        assertThat(elements.getChildCount()).isEqualTo(1);
        assertThat(elements.getChild(0).getValue()).doesNotContain("/META-INF/versions/");
    }

    @Test
    void configureCrossJdkTests_skipsWhenMarkerFileMissing() {
        Properties userProps = new Properties();
        userProps.setProperty("java17.home", "/opt/jdk-17");

        MavenExecutionRequest request = new DefaultMavenExecutionRequest();
        request.setUserProperties(userProps);
        MavenSession session = new MavenSession(null, request, null, List.of(project));

        MultiReleaseJarExtension.configureCrossJdkTests(project, session, 21, 17);

        Build build = project.getModel().getBuild();
        assertThat(build.getPlugins()).isEmpty();
    }

    @Test
    void configureCrossJdkTests_skipsWhenPropertyMissing() throws Exception {
        Files.createFile(tempDir.resolve("build-test-java17"));

        MavenExecutionRequest request = new DefaultMavenExecutionRequest();
        MavenSession session = new MavenSession(null, request, null, List.of(project));

        MultiReleaseJarExtension.configureCrossJdkTests(project, session, 21, 17);

        Build build = project.getModel().getBuild();
        assertThat(build.getPlugins()).isEmpty();
    }

    @Test
    void configureCrossJdkTests_multipleVersions() throws Exception {
        Files.createFile(tempDir.resolve("build-test-java17"));
        Files.createFile(tempDir.resolve("build-test-java19"));

        Properties userProps = new Properties();
        userProps.setProperty("java17.home", "/opt/jdk-17");
        userProps.setProperty("java19.home", "/opt/jdk-19");

        MavenExecutionRequest request = new DefaultMavenExecutionRequest();
        request.setUserProperties(userProps);
        MavenSession session = new MavenSession(null, request, null, List.of(project));

        MultiReleaseJarExtension.configureCrossJdkTests(project, session, 21, 17);

        Build build = project.getModel().getBuild();
        Plugin surefire = findPlugin(build, "org.apache.maven.plugins", "maven-surefire-plugin");

        assertThat(findExecution(surefire, "java17-test")).isNotNull();
        assertThat(findExecution(surefire, "java19-test")).isNotNull();

        Xpp3Dom config19 = (Xpp3Dom) findExecution(surefire, "java19-test").getConfiguration();
        Xpp3Dom elements = config19.getChild("additionalClasspathElements");
        assertThat(elements.getChildCount()).isEqualTo(3);
        assertThat(elements.getChild(0).getValue()).contains("/META-INF/versions/18");
        assertThat(elements.getChild(1).getValue()).contains("/META-INF/versions/17");
    }

    @Test
    void getBaseline_defaultsTo17() {
        assertThat(MultiReleaseJarExtension.getBaseline(project)).isEqualTo(17);
    }

    @Test
    void getBaseline_readsProperty() {
        project.getProperties().setProperty("smallrye.mr-jar.baseline", "11");
        assertThat(MultiReleaseJarExtension.getBaseline(project)).isEqualTo(11);
    }

    // -- helpers --

    private Plugin findPlugin(Build build, String groupId, String artifactId) {
        for (Plugin plugin : build.getPlugins()) {
            if (groupId.equals(plugin.getGroupId()) && artifactId.equals(plugin.getArtifactId())) {
                return plugin;
            }
        }
        return null;
    }

    private PluginExecution findExecution(Plugin plugin, String id) {
        for (PluginExecution exec : plugin.getExecutions()) {
            if (id.equals(exec.getId())) {
                return exec;
            }
        }
        return null;
    }
}
