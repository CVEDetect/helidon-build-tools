/*
 * Copyright (c) 2021, 2022 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.build.maven.services;

import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.helidon.build.common.logging.Log;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Service provider plugin.
 * This plugin generates {@code META-INF/services} records based on
 * {@code module-info.java} files.
 */
@Mojo(name = "services",
      defaultPhase = LifecyclePhase.COMPILE,
      threadSafe = true)
public class ServicesMojo extends AbstractMojo {
    /**
     * Skip execution of this plugin.
     */
    @Parameter(defaultValue = "false", property = "helidon.services.skip")
    private boolean skip;

    /**
     * Source directory.
     */
    @Parameter(defaultValue = "${project.build.sourceDirectory}")
    private File javaDirectory;

    /**
     * Directory where the {@code classes} files reside.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}")
    private File targetDirectory;

    /**
     * Source directory for resources.
     */
    @Parameter(defaultValue = "${project.basedir}")
    private File baseDir;

    /**
     * Source directory for resources.
     */
    @Parameter(defaultValue = "${project.basedir}/src/main/resources")
    private File resourceDirectory;

    /**
     * Whether to fail if there is no module-info.java present.
     */
    @Parameter(property = "failOnMissingModuleInfo", defaultValue = "true")
    private boolean failOnMissingModuleInfo;

    /**
     * Plugin modes:
     * <ul>
     *  <li>fail - fail if META-INF/services in source; otherwise generate and overwrite
     *  META-INF/services in target from module-info.java (default).</li>
     *  <li>overwrite - generate and overwrite META-INF/services files in target from module-info.java</li>
     *  <li>ignore - if META-INF/services in source, ignore module-info.java and just let Maven copy
     *  files. If META-INF/services not in source, same as overwrite mode.</li>
     *  <li>validate - validate that module-info.java and services files in target contain the same
     *  records.</li>
     *  <li>clean - delete existing META-INF/services in source if they are in module-info, fail otherwise.</li>
     * </ul>
     */
    @Parameter(property = "mode", defaultValue = "fail")
    private String mode;

    /**
     * The Maven project this mojo executes on.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * The comment used in the generated files.
     */
    @Parameter(property = "comment", defaultValue = "# This file was generated by Helidon services Maven plugin.")
    private String comment;

    /**
     * Whether to add comment to the generated files.
     */
    @Parameter(property = "addComment", defaultValue = "true")
    private boolean addComment;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            Log.info("Skipping execution.");
            return;
        }

        if ("pom".equals(project.getPackaging())) {
            Log.info("Skipping POM packaging project.");
            return;
        }

        Path sourcePath = javaDirectory.toPath();
        if (Files.notExists(sourcePath)) {
            Log.info("$(YELLOW Skipping project with no sources): " + sourcePath);
            return;
        }

        Path targetPath = targetDirectory.toPath();
        Path moduleInfo = targetPath.resolve("module-info.class");

        if (Files.notExists(moduleInfo)) {
            if (failOnMissingModuleInfo) {
                throw new MojoExecutionException("Project does not contain module-info.class: " + moduleInfo);
            }
            Log.info("$(YELLOW Skipping project with no module-info.)");
            return;
        }

        Log.info("Mode is $(YELLOW " + mode + ")");

        // now we have module info, let's validate existing files
        Path targetServices = targetPath.resolve("META-INF/services");

        if ("validate".equals(mode)) {
            validate(moduleInfo, existing(targetServices));
            return;
        }

        // source services files
        Path sourceServices = resourceDirectory.toPath().resolve("META-INF/services");
        List<Path> existing = existing(sourceServices);

        if ("fail".equals(mode)) {
            if (!existing.isEmpty()) {
                throw new MojoExecutionException("Project must not contain META-INF/services in source folder");
            }
            mode = "overwrite";     // fall back
        }

        if ("ignore".equals(mode)) {
            if (!existing.isEmpty()) {
                Log.info("Ignoring module-info.java, existing META-INF/services in source folder");
                Log.verbose("Existing files: " + existing);
                return;
            }
            mode = "overwrite";     // fall back
        }

        if ("overwrite".equals(mode)) {
            deleteExisting(targetServices);
            try {
                createServices(targetServices, moduleInfo);
            } catch (IOException e) {
                throw new MojoFailureException("Failed to create META-INF/services in target folder for "
                        + moduleInfo, e);
            }
            return;
        }

        if ("clean".equals(mode)) {
            if (!existing.isEmpty()) {
                clean(sourceServices, moduleInfo, existing);
            }
            return;
        }

        throw new MojoExecutionException("Invalid plugin mode '" + mode + "'");
    }

    private void validate(Path moduleInfo, List<Path> existing)
            throws MojoFailureException, MojoExecutionException {

        Map<String, Provider> fromModuleInfo = loadModuleInfo(moduleInfo);
        Map<String, Provider> fromMetaInf = loadProviders(existing);

        if (fromModuleInfo.isEmpty() && fromMetaInf.isEmpty()) {
            Log.info("There are no services defined in module.");
            return;
        }

        List<String> problems = new LinkedList<>();

        // first let's find all missing from module-info.java
        fromMetaInf.forEach((service, provider) -> {
            if (!fromModuleInfo.containsKey(service)) {
                problems.add("Service " + service + " missing from module-info.java, providers: " + provider.providers());
            } else {
                Provider moduleInfoProvider = fromModuleInfo.get(service);
                if (!moduleInfoProvider.providers().equals(provider.providers)) {
                    Set<String> missing = new LinkedHashSet<>(provider.providers());
                    moduleInfoProvider.providers().forEach(missing::remove);
                    if (!missing.isEmpty()) {
                        problems.add("Service " + service
                                             + " is missing the following providers in module-info.java: " + missing);
                    }
                }
            }
        });

        // now let's find all missing from META-INF
        fromModuleInfo.forEach((service, provider) -> {
            if (!fromMetaInf.containsKey(service)) {
                problems.add("Service " + service + " missing from META-INF/services, providers: " + provider.providers());
            } else {
                Provider metaInfProvider = fromMetaInf.get(service);
                if (!metaInfProvider.providers().equals(provider.providers)) {
                    Set<String> missing = new LinkedHashSet<>(provider.providers());
                    metaInfProvider.providers().forEach(missing::remove);
                    if (!missing.isEmpty()) {
                        problems.add("Service " + service
                                             + " is missing the following providers in META-INF/services: " + missing);
                    }
                }
            }
        });

        if (!problems.isEmpty()) {
            throw new MojoExecutionException("Mismatch between module-info.java and META-INF/services:\n"
                                                     + String.join("\n", problems));
        }
        Log.info("Services are valid.");
    }

    private void clean(Path metaInfServices, Path moduleInfo, List<Path> existing)
            throws MojoFailureException, MojoExecutionException {
        // existing is not empty
        Map<String, Provider> fromModuleInfo = loadModuleInfo(moduleInfo);

        if (fromModuleInfo.isEmpty()) {
            throw new MojoExecutionException("module-info.java does not define any service providers, yet the following providers"
                                                     + " are defined in META-INF/services: "
                                                     + relativize(metaInfServices, existing));
        }

        Map<String, Provider> fromMetaInf = loadProviders(existing);

        List<String> problems = new LinkedList<>();

        // I just care about stuff that is in meta inf, but missing in module info. Inverse is OK.
        fromMetaInf.forEach((service, provider) -> {
            if (!fromModuleInfo.containsKey(service)) {
                problems.add("Service " + service + " missing from module-info.java, providers: " + provider.providers());
            } else {
                Provider moduleInfoProvider = fromModuleInfo.get(service);
                if (!moduleInfoProvider.providers().equals(provider.providers)) {
                    Set<String> missing = new LinkedHashSet<>(provider.providers());
                    moduleInfoProvider.providers().forEach(missing::remove);
                    if (!missing.isEmpty()) {
                        problems.add("Service "
                                             + service
                                             + " is missing the following providers in module-info.java: "
                                             + missing);
                    }
                }
            }
        });

        if (!problems.isEmpty()) {
            throw new MojoExecutionException("Mismatch between module-info.java and META-INF/services:\n"
                                                     + String.join("\n", problems));
        }

        // if we got here, the information in module-info.java is a superset of META-INF/services, we can safely delete all
        // META-INF/services in sources
        Path resourcePath = resourceDirectory.toPath();
        Path srcMetaInfServices = resourcePath.resolve("META-INF/services");
        if (!Files.exists(srcMetaInfServices)) {
            throw new MojoExecutionException("Cannot clean META-INF/services in sources, as path does not exist: "
                                                     + srcMetaInfServices);
        }

        Path basePath = baseDir.toPath();

        for (Path path : existing) {
            Path source = srcMetaInfServices.resolve(path.getFileName());
            if (!Files.exists(source)) {
                throw new MojoExecutionException("Cannot clean META-INF/services, file " + path + " exists in output,"
                                                         + " but file " + source + " does not exist in sources");
            }
            Log.info("Deleting source file " + basePath.relativize(source));
            try {
                Files.delete(source);
            } catch (IOException e) {
                throw new MojoFailureException("Failed to delete file " + source, e);
            }
        }
        try {
            Files.delete(srcMetaInfServices);
        } catch (IOException e) {
            throw new MojoFailureException("Failed to delete directory " + srcMetaInfServices, e);
        }
    }

    private List<String> relativize(Path metaInfServices, List<Path> existing) {
        List<String> relative = new ArrayList<>(existing.size());

        for (Path path : existing) {
            relative.add(metaInfServices.relativize(path).toString());
        }

        return relative;
    }

    private Map<String, Provider> loadProviders(List<Path> existing) throws MojoFailureException {
        Map<String, Provider> providerMap = new HashMap<>();

        for (Path path : existing) {
            String service = path.getFileName().toString();
            try {
                List<String> lines = Files.readAllLines(path);
                List<String> providers = new ArrayList<>(lines.size());
                for (String line : lines) {
                    if (line.startsWith("#")) {
                        continue;
                    }
                    if (line.isBlank()) {
                        continue;
                    }
                    providers.add(line);
                }
                providerMap.put(service, Provider.create(service, providers));
            } catch (IOException e) {
                throw new MojoFailureException("Failed to read service provider definition: " + path);
            }
        }

        return providerMap;
    }

    private void createServices(Path metaInfServices, Path moduleInfo) throws IOException, MojoFailureException {
        // make sure the directories exist
        metaInfServices = Files.createDirectories(metaInfServices);

        // now we can safely create the new META-INF/services
        Map<String, Provider> provides = loadModuleInfo(moduleInfo);
        if (provides.isEmpty()) {
            Log.info("There are no services provided by this module.");
            return;
        }
        for (Provider provide : provides.values()) {
            Path serviceFile = metaInfServices.resolve(provide.service());

            List<String> providers = new ArrayList<>();
            if (addComment) {
                providers.add(comment);
            }
            providers.addAll(provide.providers());

            Log.verbose("Creating service file " + serviceFile);
            Log.debug("File lines: \n" + String.join("\n", providers));
            Files.write(serviceFile, providers, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        Log.info("Created $(CYAN " + provides.size() + ") META-INF/services files");
    }

    private Map<String, Provider> loadModuleInfo(Path moduleInfo) throws MojoFailureException {
        try {
            ModuleDescriptor module = ModuleDescriptor.read(Files.newInputStream(moduleInfo));
            return module.provides()
                    .stream()
                    .map(Provider::create)
                    .collect(Collectors.toMap(Provider::service, Function.identity()));
        } catch (IOException e) {
            throw new MojoFailureException("Failed to load module descriptor " + moduleInfo, e);
        }
    }

    private List<Path> existing(Path metaInfServices) throws MojoFailureException {
        if (!Files.exists(metaInfServices)) {
            return List.of();
        }

        try {
            return Files.list(metaInfServices)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new MojoFailureException("Failed to list META-INF/services directory " + metaInfServices, e);
        }
    }

    private void deleteExisting(Path metaInfServices) throws MojoFailureException {
        try {
            for (Path path : existing(metaInfServices)) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new MojoFailureException("Failed to delete existing META-INF/services records", e);
        }
    }

    static final class Provider {
        private final String service;
        private final Set<String> providers;

        private Provider(String service, List<String> providers) {
            this.service = service;
            this.providers = new LinkedHashSet<>(providers);
        }

        static Provider create(ModuleDescriptor.Provides provides) {
            String service = provides.service();
            List<String> providers = provides.providers();

            return new Provider(service, providers);
        }

        public static Provider create(String service, List<String> providers) {
            return new Provider(service, providers);
        }

        String service() {
            return service;
        }

        Set<String> providers() {
            return providers;
        }
    }
}
