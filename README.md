# SmallRye Multi-Release JAR Extension

A Maven core extension that automatically configures multi-release JAR (MR JAR) builds, eliminating the need to manually maintain hundreds of lines of profile boilerplate in parent POMs.

Instead of adding ~150 lines of XML profiles for every new JDK version, this extension detects `src/main/javaN` source directories at build time and dynamically injects the required compiler, test, and formatting configurations.

## Problem

Multi-release JAR support in Maven typically requires three profiles per JDK version:

1. **`javaN-mr-build`** — Compiles version-specific sources from `src/main/javaN` into `META-INF/versions/N`
2. **`javaN-test-classpath`** — Configures Surefire to use the correct multi-release classpath when running tests
3. **`java(N-1)-test`** — Runs tests against a previous JDK version using `javaN.home`

These profiles are nearly identical across versions, differing only in version numbers. Each new JDK release requires copy-pasting and adjusting ~150 lines of XML. Over time this accumulates to thousands of lines of mechanical boilerplate (e.g., JDK 17 through 27 = ~1,600 lines).

## Solution

This extension replaces all of those profiles with zero-configuration automatic detection:

- Scans each reactor module for `src/main/javaN` directories
_- Detects the running JDK version_
- Dynamically injects the equivalent plugin configurations before the build starts

New JDK versions are supported automatically — no POM changes required.

## Setup

### 1. Install the extension

Add the extension to your project's `.mvn/extensions.xml` (create the file if it doesn't exist):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<extensions xmlns="http://maven.apache.org/EXTENSIONS/1.1.0"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/EXTENSIONS/1.1.0
        http://maven.apache.org/xsd/core-extensions-1.1.0.xsd">
    <extension>
        <groupId>io.smallrye</groupId>
        <artifactId>smallrye-multi-release-jar-extension</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </extension>
</extensions>
```

### 2. Remove existing MR JAR profiles

Remove the manually maintained `javaN-test-classpath`, `javaN-test`, and `javaN-mr-build` profiles from your parent POM. The extension generates equivalent configurations automatically.

### 3. Build as usual

```shell
mvn clean install
```

The extension logs which MR JAR versions it detects:

```
[multi-release-jar] my-module: Detected multi-release JAR versions: [17, 21] (JDK 25)
```

## What It Replaces

Without the extension, each JDK version requires manually maintained profiles in your parent POM. For example, adding multi-release support for JDK 21 sources requires two separate profiles because they have different activation ranges — the MR build activates on JDK 21+ (you need to compile `src/main/java21` on any JDK >= 21), while the test classpath activates on JDK 21 only (on JDK 22, the classpath should point to version 22 instead):

```xml
<!-- Compile src/main/java21 into META-INF/versions/21 -->
<profile>
    <id>java21-mr-build</id>
    <activation>
        <jdk>[21,)</jdk>
        <file>
            <exists>${basedir}/src/main/java21</exists>
        </file>
    </activation>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <executions>
                    <execution>
                        <id>compile-java21</id>
                        <phase>compile</phase>
                        <goals>
                            <goal>compile</goal>
                        </goals>
                        <configuration>
                            <release>21</release>
                            <compileSourceRoots>
                                <compileSourceRoot>${project.basedir}/src/main/java21</compileSourceRoot>
                            </compileSourceRoots>
                            <multiReleaseOutput>true</multiReleaseOutput>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>net.revelc.code</groupId>
                <artifactId>impsort-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <id>sort-imports-java21</id>
                        <goals>
                            <goal>sort</goal>
                        </goals>
                        <configuration>
                            <sourceDirectory>${project.basedir}/src/main/java21</sourceDirectory>
                            <compliance>21</compliance>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>net.revelc.code.formatter</groupId>
                <artifactId>formatter-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <id>format-sources-java21</id>
                        <phase>process-sources</phase>
                        <goals>
                            <goal>format</goal>
                        </goals>
                        <configuration>
                            <sourceDirectory>${project.basedir}/src/main/java21</sourceDirectory>
                            <compilerCompliance>21</compilerCompliance>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</profile>

<!-- Configure Surefire test classpath when running on JDK 21 -->
<profile>
    <id>java21-test-classpath</id>
    <activation>
        <jdk>[21,22)</jdk>
    </activation>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <executions>
                    <execution>
                        <id>default-test</id>
                        <configuration>
                            <classesDirectory>${project.build.outputDirectory}/META-INF/versions/21</classesDirectory>
                            <additionalClasspathElements>
                                <additionalClasspathElement>${project.build.outputDirectory}/META-INF/versions/20</additionalClasspathElement>
                                <additionalClasspathElement>${project.build.outputDirectory}/META-INF/versions/19</additionalClasspathElement>
                                <additionalClasspathElement>${project.build.outputDirectory}/META-INF/versions/18</additionalClasspathElement>
                                <additionalClasspathElement>${project.build.outputDirectory}/META-INF/versions/17</additionalClasspathElement>
                                <additionalClasspathElement>${project.build.outputDirectory}</additionalClasspathElement>
                            </additionalClasspathElements>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</profile>
```

On top of this, each JDK version also needs a cross-JDK testing profile (to run tests against the previous JDK when a `javaN.home` property is set). Multiply all of this by every JDK version you need to support (17 through 27 and counting) and the parent POM grows by thousands of lines. The extension replaces all of it with zero configuration.

## How It Works

### Multi-release compilation

When the extension finds a `src/main/javaN` directory in a module, it adds:

- A **`maven-compiler-plugin`** execution (`compile-javaN`) that compiles the version-specific sources with `--release N` and `multiReleaseOutput=true`, placing classes under `META-INF/versions/N` in the output directory.

- An **`impsort-maven-plugin`** execution (`sort-imports-javaN`) for the version-specific source directory — only if the plugin is already declared in the project (in `<build>` or `<pluginManagement>`).

- A **`formatter-maven-plugin`** execution (`format-sources-javaN`) for the version-specific source directory — only if the plugin is already declared in the project.

### Test classpath

The extension configures the Surefire `default-test` execution to use the multi-release classpath appropriate for the running JDK. For example, when running on JDK 21 with a baseline of 17:

- `classesDirectory` is set to `target/classes/META-INF/versions/21`
- Additional classpath elements are added in descending order: versions 20, 19, 18, 17, then the base `target/classes`

This ensures tests run against the correct version-specific classes.

### Cross-JDK testing

When both conditions are met:

- A system property `javaN.home` is set (e.g., `-Djava17.home=/opt/jdk-17`)
- A marker file `build-test-javaN` exists in the module's base directory

The extension adds a Surefire execution (`javaN-test`) that runs the test suite using that JDK, with the appropriate multi-release classpath for version N.

## Project Structure

A typical module using multi-release JARs:

```
my-module/
├── build-test-java17          # Marker: enable cross-JDK testing for Java 17
├── pom.xml
└── src/
    └── main/
        ├── java/              # Base sources (compiled with the project's default release)
        │   └── com/example/
        │       └── MyClass.java
        ├── java17/            # Java 17 version-specific sources
        │   └── com/example/
        │       └── MyClass.java
        └── java21/            # Java 21 version-specific sources
            └── com/example/
                └── MyClass.java
```

## Configuration Properties

All properties are optional. Set them in your POM `<properties>` or pass via `-D` on the command line.

| Property | Default | Description |
|---|---|---|
| `smallrye.mr-jar.baseline` | `17` | The lowest Java version to consider for multi-release classpath entries. Versions below this are ignored. |
| `smallrye.mr-jar.skip` | `false` | Set to `true` to disable the extension entirely for a specific module. |

### Examples

Override the baseline in a specific module:

```xml
<properties>
    <smallrye.mr-jar.baseline>11</smallrye.mr-jar.baseline>
</properties>
```

Disable the extension for a module that doesn't need it:

```xml
<properties>
    <smallrye.mr-jar.skip>true</smallrye.mr-jar.skip>
</properties>
```

## Cross-JDK Testing

To run tests against a previous JDK version:

1. Create a marker file in the module directory:

   ```shell
   touch build-test-java17
   ```

2. Pass the JDK home path when invoking Maven:

   ```shell
   mvn test -Djava17.home=/opt/jdk-17
   ```

This creates an additional Surefire execution that runs the full test suite using JDK 17 with the Java 17 multi-release classpath.

Multiple cross-JDK test configurations can be active simultaneously:

```shell
mvn test -Djava17.home=/opt/jdk-17 -Djava21.home=/opt/jdk-21
```

## Verifying the Generated Configuration

To inspect what the extension generates, use:

```shell
mvn help:effective-pom -pl my-module
```

The effective POM will show all the injected plugin executions, which should match the equivalent hand-written profiles.

## Scope and Inheritance

The extension is loaded via `.mvn/extensions.xml`, which means:

- It applies to **all modules** in the reactor when building from the project root.
- It does **not** use POM inheritance. If a module is built independently from a directory without `.mvn/extensions.xml`, the extension will not be active.
- It is safe to use alongside existing profiles — the extension only adds configurations, it does not remove or override existing plugin declarations.

## Building from Source

```shell
git clone https://github.com/smallrye/smallrye-multi-release-jar-extension.git
cd smallrye-multi-release-jar-extension
mvn clean install
```

Requires JDK 17 or later.
