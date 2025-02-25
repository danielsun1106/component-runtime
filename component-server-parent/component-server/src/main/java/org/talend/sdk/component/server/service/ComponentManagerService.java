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
package org.talend.sdk.component.server.service;

import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.empty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Initialized;
import javax.enterprise.event.Event;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;

import org.talend.sdk.component.container.Container;
import org.talend.sdk.component.container.ContainerListener;
import org.talend.sdk.component.dependencies.maven.Artifact;
import org.talend.sdk.component.dependencies.maven.MvnCoordinateToFileConverter;
import org.talend.sdk.component.design.extension.RepositoryModel;
import org.talend.sdk.component.design.extension.repository.Config;
import org.talend.sdk.component.path.PathFactory;
import org.talend.sdk.component.runtime.manager.ComponentFamilyMeta;
import org.talend.sdk.component.runtime.manager.ComponentManager;
import org.talend.sdk.component.runtime.manager.ContainerComponentRegistry;
import org.talend.sdk.component.server.configuration.ComponentServerConfiguration;
import org.talend.sdk.component.server.dao.ComponentActionDao;
import org.talend.sdk.component.server.dao.ComponentDao;
import org.talend.sdk.component.server.dao.ComponentFamilyDao;
import org.talend.sdk.component.server.dao.ConfigurationDao;
import org.talend.sdk.component.server.service.event.DeployedComponent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class ComponentManagerService {

    @Inject
    private ComponentServerConfiguration configuration;

    @Inject
    private ComponentDao componentDao;

    @Inject
    private ComponentFamilyDao componentFamilyDao;

    @Inject
    private ComponentActionDao actionDao;

    @Inject
    private ConfigurationDao configurationDao;

    @Inject
    private VirtualDependenciesService virtualDependenciesService;

    @Inject
    private GlobService globService;

    @Inject
    private Event<DeployedComponent> deployedComponentEvent;

    @Inject
    @Context
    private UriInfo uriInfo;

    @Inject
    private LocaleMapper localeMapper;

    private ComponentManager instance;

    private MvnCoordinateToFileConverter mvnCoordinateToFileConverter;

    private DeploymentListener deploymentListener;

    private volatile Date lastUpdated = new Date();

    private boolean started;

    public void startupLoad(@Observes @Initialized(ApplicationScoped.class) final Object start) {
        // no-op
    }

    @PostConstruct
    private void init() {
        if (log.isWarnEnabled()) {
            final String filter = System.getProperty("jdk.serialFilter");
            if (filter == null) {
                log.warn("No system property 'jdk.serialFilter', ensure it is intended");
            }
        }

        mvnCoordinateToFileConverter = new MvnCoordinateToFileConverter();
        final Path m2 = configuration
                .getMavenRepository()
                .map(PathFactory::get)
                .filter(Files::exists)
                .orElseGet(ComponentManager::findM2);
        log.info("Using maven repository: '{}'", m2);
        instance = new ComponentManager(m2) {

            @Override
            protected Supplier<Locale> getLocalSupplier() {
                return ComponentManagerService.this::readCurrentLocale;
            }
        };
        deploymentListener = new DeploymentListener(componentDao, componentFamilyDao, actionDao, configurationDao,
                virtualDependenciesService);
        instance.getContainer().registerListener(deploymentListener);

        // note: we don't want to download anything from the manager, if we need to download any artifact we need
        // to ensure it is controlled (secured) and allowed so don't make it implicit but enforce a first phase
        // where it is cached locally (provisioning solution)
        final List<String> coords = configuration
                .getComponentCoordinates()
                .map(it -> Stream.of(it.split(",")).map(String::trim).filter(i -> !i.isEmpty()).collect(toList()))
                .orElse(emptyList());
        coords.forEach(this::deploy);
        configuration
                .getComponentRegistry()
                .map(Collection::stream)
                .orElseGet(Stream::empty)
                .flatMap(globService::toFiles)
                .forEach(registry -> {
                    final Properties properties = new Properties();
                    try (final InputStream is = Files.newInputStream(registry)) {
                        properties.load(is);
                    } catch (final IOException e) {
                        throw new IllegalArgumentException(e);
                    }
                    properties
                            .stringPropertyNames()
                            .stream()
                            .map(properties::getProperty)
                            .filter(gav -> !coords.contains(gav))
                            .forEach(this::deploy);
                });
        started = true;
    }

    private Locale readCurrentLocale() {
        try {
            return ofNullable(uriInfo.getQueryParameters().getFirst("lang"))
                    .map(localeMapper::mapLocale)
                    .orElseGet(Locale::getDefault);
        } catch (final RuntimeException ex) {
            log.debug("Can't get the locale from current request in thread '{}'", Thread.currentThread().getName(), ex);
            return Locale.getDefault();
        }
    }

    @PreDestroy
    private void destroy() {
        started = false;
        instance.getContainer().unregisterListener(deploymentListener);
        instance.close();
    }

    public String deploy(final String pluginGAV) {
        final String pluginPath = ofNullable(pluginGAV)
                .map(gav -> mvnCoordinateToFileConverter.toArtifact(gav))
                .map(Artifact::toPath)
                .orElseThrow(() -> new IllegalArgumentException("Plugin GAV can't be empty"));

        final Path m2 = instance.getContainer().getRootRepositoryLocationPath();
        final String plugin =
                instance.addWithLocationPlugin(pluginGAV, m2.resolve(pluginPath).toAbsolutePath().toString());
        lastUpdated = new Date();
        if (started) {
            deployedComponentEvent.fire(new DeployedComponent());
        }
        return plugin;
    }

    public void undeploy(final String pluginGAV) {
        if (pluginGAV == null || pluginGAV.isEmpty()) {
            throw new IllegalArgumentException("plugin maven GAV are required to undeploy a plugin");
        }

        String pluginID = instance
                .find(c -> pluginGAV.equals(c.get(ComponentManager.OriginalId.class).getValue()) ? Stream.of(c.getId())
                        : empty())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No plugin found using maven GAV: " + pluginGAV));

        instance.removePlugin(pluginID);
        lastUpdated = new Date();
    }

    public Date findLastUpdated() {
        return lastUpdated;
    }

    @AllArgsConstructor
    private static class DeploymentListener implements ContainerListener {

        private final ComponentDao componentDao;

        private final ComponentFamilyDao componentFamilyDao;

        private final ComponentActionDao actionDao;

        private final ConfigurationDao configurationDao;

        private final VirtualDependenciesService virtualDependenciesService;

        @Override
        public void onCreate(final Container container) {
            container.set(CleanupTask.class, new CleanupTask(postDeploy(container)));
        }

        @Override
        public void onClose(final Container container) {
            if (container.getState() == Container.State.ON_ERROR) {
                // means it was not deployed so don't drop old state
                return;
            }
            ofNullable(container.get(CleanupTask.class)).ifPresent(c -> c.getCleanup().run());
        }

        private Runnable postDeploy(final Container plugin) {
            final Collection<String> componentIds = plugin
                    .get(ContainerComponentRegistry.class)
                    .getComponents()
                    .values()
                    .stream()
                    .flatMap(c -> Stream
                            .concat(c.getPartitionMappers().values().stream(), c.getProcessors().values().stream()))
                    .peek(componentDao::createOrUpdate)
                    .map(ComponentFamilyMeta.BaseMeta::getId)
                    .collect(toSet());

            final Collection<ComponentActionDao.ActionKey> actions = plugin
                    .get(ContainerComponentRegistry.class)
                    .getServices()
                    .stream()
                    .flatMap(c -> c.getActions().stream())
                    .map(actionDao::createOrUpdate)
                    .collect(toList());

            final Collection<String> families = plugin
                    .get(ContainerComponentRegistry.class)
                    .getComponents()
                    .values()
                    .stream()
                    .map(componentFamilyDao::createOrUpdate)
                    .collect(toList());

            final Collection<String> configs = ofNullable(plugin.get(RepositoryModel.class))
                    .map(r -> r
                            .getFamilies()
                            .stream()
                            .flatMap(f -> configAsStream(f.getConfigs().get().stream()))
                            .collect(toList()))
                    .orElse(emptyList())
                    .stream()
                    .map(configurationDao::createOrUpdate)
                    .collect(toList());

            return () -> {
                virtualDependenciesService.onUnDeploy(plugin);
                componentIds.forEach(componentDao::removeById);
                actions.forEach(actionDao::removeById);
                families.forEach(componentFamilyDao::removeById);
                configs.forEach(configurationDao::removeById);
            };
        }

        private Stream<Config> configAsStream(final Stream<Config> stream) {
            return stream.flatMap(s -> Stream.concat(Stream.of(s), s.getChildConfigs().stream()));
        }
    }

    @Data
    private static class CleanupTask {

        private final Runnable cleanup;
    }

    @Produces
    public ComponentManager manager() {
        return instance;
    }

}
