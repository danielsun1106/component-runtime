/**
 * Copyright (C) 2006-2019 Talend Inc. - www.talend.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.talend.sdk.component.maven;

import static java.util.stream.Collectors.toList;
import static org.apache.maven.plugins.annotations.LifecyclePhase.PACKAGE;
import static org.apache.maven.plugins.annotations.ResolutionScope.TEST;
import static org.talend.sdk.component.maven.api.Audience.Type.TALEND_INTERNAL;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.ziplock.Files;
import org.apache.ziplock.IO;
import org.eclipse.aether.artifact.Artifact;
import org.talend.sdk.component.maven.api.Audience;

@Audience(TALEND_INTERNAL)
@Mojo(name = "prepare-repository", defaultPhase = PACKAGE, threadSafe = true, requiresDependencyResolution = TEST)
public class BuildComponentM2RepositoryMojo extends CarConsumer {

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    @Parameter(property = "talend-m2.registryBase")
    private File componentRegistryBase;

    @Parameter(property = "talend-m2.root",
            defaultValue = "${maven.multiModuleProjectDirectory}/target/talend-component-kit/maven")
    protected File m2Root;

    @Parameter(property = "talend-m2.clean", defaultValue = "true")
    private boolean cleanBeforeGeneration;

    @Parameter(defaultValue = "true", property = "talend.repository.createDigestRegistry")
    private boolean createDigestRegistry;

    @Parameter(defaultValue = "SHA-512", property = "talend.repository.digestAlgorithm")
    private String digestAlgorithm;

    @Override
    public void doExecute() throws MojoExecutionException {
        final Set<Artifact> componentArtifacts = getComponentsCar(getComponentArtifacts());

        doGenerate(componentArtifacts);
    }

    private void doGenerate(final Set<Artifact> componentArtifacts) {
        if (cleanBeforeGeneration && m2Root.exists()) {
            Files.remove(m2Root);
        }
        m2Root.mkdirs();
        final List<String> coordinates = componentArtifacts
                .stream()
                .map(car -> copyComponentDependencies(car,
                        (entry, read) -> copyFile(entry, read,
                                entry.getName().substring("MAVEN-INF/repository/".length()))))
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(toList());

        if (getLog().isDebugEnabled()) {
            coordinates.forEach(it -> getLog().debug("Including component " + it));
        } else {
            getLog().info("Included components " + String.join(", ", coordinates));
        }

        writeRegistry(getNewComponentRegistry(coordinates));
        if (createDigestRegistry) {
            writeDigest(getDigests());
        }

        getLog().info("Created component repository at " + m2Root);
    }

    private void writeProperties(final Properties content, final File location) {
        try (final Writer output = new FileWriter(location)) {
            content.store(output, "Generated by Talend Component Kit " + getClass().getSimpleName());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    protected void writeDigest(final Properties digestRegistry) {
        writeProperties(digestRegistry, getDigestRegistry());
    }

    protected void writeRegistry(final Properties components) {
        writeProperties(components, getRegistry());
    }

    protected File copyFile(final ZipEntry entry, final InputStream read, final String depPath) {
        final File file = new File(m2Root, depPath);
        Files.mkdir(file.getParentFile());
        try {
            IO.copy(read, file);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        final long lastModified = entry.getTime();
        if (lastModified > 0) {
            file.setLastModified(lastModified);
        }
        if (getLog().isDebugEnabled()) {
            getLog().debug("Adding " + depPath);
        }
        return file;
    }

    protected Properties getNewComponentRegistry(final List<String> coordinates) {
        final Properties components = new Properties();
        if (componentRegistryBase != null && componentRegistryBase.exists()) {
            try (final InputStream source = new FileInputStream(componentRegistryBase)) {
                components.load(source);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        coordinates.stream().filter(it -> it.contains(":")).forEach(it -> components.put(it.split(":")[1], it.trim()));
        return components;
    }

    protected Properties getDigests() {
        final Properties index = new Properties();
        final Path root = m2Root.toPath();
        try {
            java.nio.file.Files.walkFileTree(root, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    if (!file.getFileName().toString().startsWith(".")) {
                        index.setProperty(root.relativize(file).toString(), hash(file));
                    }
                    return super.visitFile(file, attrs);
                }
            });
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return index;
    }

    protected String hash(final Path file) {
        try (final DigestOutputStream out = new DigestOutputStream(new OutputStream() {

            @Override
            public void write(final byte[] b) {
                // no-op
            }

            @Override
            public void write(final byte[] b, final int off, final int len) {
                // no-op
            }

            @Override
            public void write(final int b) {
                // no-op
            }
        }, MessageDigest.getInstance("SHA-512"))) {
            java.nio.file.Files.copy(file, out);
            out.flush();
            return hex(out.getMessageDigest().digest());
        } catch (final NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(final byte[] data) {
        final StringBuilder out = new StringBuilder(data.length * 2);
        for (final byte b : data) {
            out.append(HEX_CHARS[b >> 4 & 15]).append(HEX_CHARS[b & 15]);
        }
        return out.toString();
    }

    protected String copyComponentDependencies(final Artifact car,
            final BiConsumer<ZipEntry, InputStream> onDependency) {
        String gav = null;
        try (final ZipInputStream read =
                new ZipInputStream(new BufferedInputStream(new FileInputStream(car.getFile())))) {
            ZipEntry entry;
            while ((entry = read.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                final String path = entry.getName();
                if ("TALEND-INF/metadata.properties".equals(path)) {
                    final Properties properties = new Properties();
                    properties.load(read);
                    gav = properties.getProperty("component_coordinates").replace("\\:", "");
                    continue;
                }
                if (!path.startsWith("MAVEN-INF/repository/")) {
                    continue;
                }

                onDependency.accept(entry, read);
            }
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
        return gav;
    }

    protected File getRegistry() {
        return new File(m2Root, "component-registry.properties");
    }

    protected File getDigestRegistry() {
        return new File(m2Root, "component-registry-digest.properties");
    }
}
