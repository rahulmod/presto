/*
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
package com.facebook.presto.execution.resourceGroups;

import com.facebook.presto.Session;
import com.facebook.presto.execution.QueryExecution;
import com.facebook.presto.execution.resourceGroups.InternalResourceGroup.RootInternalResourceGroup;
import com.facebook.presto.server.ResourceGroupInfo;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.memory.ClusterMemoryPoolManager;
import com.facebook.presto.spi.resourceGroups.ResourceGroupConfigurationManager;
import com.facebook.presto.spi.resourceGroups.ResourceGroupConfigurationManagerContext;
import com.facebook.presto.spi.resourceGroups.ResourceGroupConfigurationManagerFactory;
import com.facebook.presto.spi.resourceGroups.ResourceGroupId;
import com.facebook.presto.spi.resourceGroups.SelectionContext;
import com.facebook.presto.spi.resourceGroups.SelectionCriteria;
import com.facebook.presto.sql.tree.Statement;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.airlift.log.Logger;
import io.airlift.node.NodeInfo;
import org.weakref.jmx.JmxException;
import org.weakref.jmx.MBeanExporter;
import org.weakref.jmx.Managed;
import org.weakref.jmx.ObjectNames;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.facebook.presto.SystemSessionProperties.getQueryPriority;
import static com.facebook.presto.execution.resourceGroups.LegacyResourceGroupConfigurationManagerFactory.LEGACY_RESOURCE_GROUP_MANAGER;
import static com.facebook.presto.spi.StandardErrorCode.QUERY_REJECTED;
import static com.facebook.presto.util.PropertiesUtil.loadProperties;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

@ThreadSafe
public final class InternalResourceGroupManager<C>
        implements ResourceGroupManager
{
    private static final Logger log = Logger.get(InternalResourceGroupManager.class);
    private static final File RESOURCE_GROUPS_CONFIGURATION = new File("etc/resource-groups.properties");
    private static final String CONFIGURATION_MANAGER_PROPERTY_NAME = "resource-groups.configuration-manager";

    private final ScheduledExecutorService refreshExecutor = newSingleThreadScheduledExecutor(daemonThreadsNamed("ResourceGroupManager"));
    private final List<RootInternalResourceGroup> rootGroups = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<ResourceGroupId, InternalResourceGroup> groups = new ConcurrentHashMap<>();
    private final AtomicReference<ResourceGroupConfigurationManager<C>> configurationManager = new AtomicReference<>();
    private final ResourceGroupConfigurationManagerContext configurationManagerContext;
    private final MBeanExporter exporter;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicLong lastCpuQuotaGenerationNanos = new AtomicLong(System.nanoTime());
    private final Map<String, ResourceGroupConfigurationManagerFactory> configurationManagerFactories = new ConcurrentHashMap<>();

    @Inject
    public InternalResourceGroupManager(LegacyResourceGroupConfigurationManagerFactory builtinFactory, ClusterMemoryPoolManager memoryPoolManager, NodeInfo nodeInfo, MBeanExporter exporter)
    {
        this.exporter = requireNonNull(exporter, "exporter is null");
        this.configurationManagerContext = new ResourceGroupConfigurationManagerContextInstance(memoryPoolManager, nodeInfo.getEnvironment());
        requireNonNull(builtinFactory, "builtinFactory is null");
        addConfigurationManagerFactory(builtinFactory);
    }

    @Override
    public ResourceGroupInfo getResourceGroupInfo(ResourceGroupId id)
    {
        checkArgument(groups.containsKey(id), "Group %s does not exist", id);
        return groups.get(id).getInfo();
    }

    @Override
    public void submit(Statement statement, QueryExecution queryExecution, Executor executor)
    {
        checkState(configurationManager.get() != null, "configurationManager not set");
        SelectionContext<C> group;
        try {
            group = selectGroup(queryExecution);
        }
        catch (PrestoException e) {
            queryExecution.fail(e);
            return;
        }
        createGroupIfNecessary(group, executor);
        groups.get(group.getResourceGroupId()).run(queryExecution);
    }

    @Override
    public void addConfigurationManagerFactory(ResourceGroupConfigurationManagerFactory factory)
    {
        if (configurationManagerFactories.putIfAbsent(factory.getName(), factory) != null) {
            throw new IllegalArgumentException(format("Resource group configuration manager '%s' is already registered", factory.getName()));
        }
    }

    @Override
    public void loadConfigurationManager()
            throws Exception
    {
        if (RESOURCE_GROUPS_CONFIGURATION.exists()) {
            Map<String, String> properties = new HashMap<>(loadProperties(RESOURCE_GROUPS_CONFIGURATION));

            String configurationManagerName = properties.remove(CONFIGURATION_MANAGER_PROPERTY_NAME);
            checkArgument(!isNullOrEmpty(configurationManagerName),
                    "Resource groups configuration %s does not contain %s", RESOURCE_GROUPS_CONFIGURATION.getAbsoluteFile(), CONFIGURATION_MANAGER_PROPERTY_NAME);

            setConfigurationManager(configurationManagerName, properties);
        }
        else {
            setConfigurationManager(LEGACY_RESOURCE_GROUP_MANAGER, ImmutableMap.of());
        }
    }

    @VisibleForTesting
    public void setConfigurationManager(String name, Map<String, String> properties)
    {
        requireNonNull(name, "name is null");
        requireNonNull(properties, "properties is null");

        log.info("-- Loading resource group configuration manager --");

        ResourceGroupConfigurationManagerFactory configurationManagerFactory = configurationManagerFactories.get(name);
        checkState(configurationManagerFactory != null, "Resource group configuration manager %s is not registered", name);

        @SuppressWarnings("unchecked")
        ResourceGroupConfigurationManager<C> configurationManager = (ResourceGroupConfigurationManager<C>) configurationManagerFactory.create(ImmutableMap.copyOf(properties), configurationManagerContext);
        checkState(this.configurationManager.compareAndSet(null, configurationManager), "configurationManager already set");

        log.info("-- Loaded resource group configuration manager %s --", name);
    }

    @VisibleForTesting
    public ResourceGroupConfigurationManager<C> getConfigurationManager()
    {
        return configurationManager.get();
    }

    @PreDestroy
    public void destroy()
    {
        refreshExecutor.shutdownNow();
    }

    @PostConstruct
    public void start()
    {
        if (started.compareAndSet(false, true)) {
            refreshExecutor.scheduleWithFixedDelay(this::refreshAndStartQueries, 1, 1, TimeUnit.MILLISECONDS);
        }
    }

    private void refreshAndStartQueries()
    {
        long nanoTime = System.nanoTime();
        long elapsedSeconds = NANOSECONDS.toSeconds(nanoTime - lastCpuQuotaGenerationNanos.get());
        if (elapsedSeconds > 0) {
            // Only advance our clock on second boundaries to avoid calling generateCpuQuota() too frequently, and because it would be a no-op for zero seconds.
            lastCpuQuotaGenerationNanos.addAndGet(elapsedSeconds * 1_000_000_000L);
        }
        else if (elapsedSeconds < 0) {
            // nano time has overflowed
            lastCpuQuotaGenerationNanos.set(nanoTime);
        }
        for (RootInternalResourceGroup group : rootGroups) {
            try {
                if (elapsedSeconds > 0) {
                    group.generateCpuQuota(elapsedSeconds);
                }
            }
            catch (RuntimeException e) {
                log.error(e, "Exception while generation cpu quota for %s", group);
            }
            try {
                group.processQueuedQueries();
            }
            catch (RuntimeException e) {
                log.error(e, "Exception while processing queued queries for %s", group);
            }
        }
    }

    private synchronized void createGroupIfNecessary(SelectionContext<C> context, Executor executor)
    {
        ResourceGroupId id = context.getResourceGroupId();
        if (!groups.containsKey(id)) {
            InternalResourceGroup group;
            if (id.getParent().isPresent()) {
                createGroupIfNecessary(new SelectionContext<>(id.getParent().get(), context.getContext()), executor);
                InternalResourceGroup parent = groups.get(id.getParent().get());
                requireNonNull(parent, "parent is null");
                group = parent.getOrCreateSubGroup(id.getLastSegment());
            }
            else {
                RootInternalResourceGroup root = new RootInternalResourceGroup(id.getSegments().get(0), this::exportGroup, executor);
                group = root;
                rootGroups.add(root);
            }
            configurationManager.get().configure(group, context);
            checkState(groups.put(id, group) == null, "Unexpected existing resource group");
        }
    }

    private void exportGroup(InternalResourceGroup group, Boolean export)
    {
        String objectName = ObjectNames.builder(InternalResourceGroup.class, group.getId().toString()).build();
        try {
            if (export) {
                exporter.export(objectName, group);
            }
            else {
                exporter.unexport(objectName);
            }
        }
        catch (JmxException e) {
            log.error(e, "Error %s resource group %s", export ? "exporting" : "unexporting", group.getId());
        }
    }

    private SelectionContext<C> selectGroup(QueryExecution queryExecution)
    {
        Session session = queryExecution.getSession();
        SelectionCriteria context = new SelectionCriteria(
                session.getIdentity().getPrincipal().isPresent(),
                session.getUser(),
                session.getSource(),
                session.getClientTags(),
                getQueryPriority(session),
                determineQueryType(queryExecution));

        return configurationManager.get().match(context)
                .orElseThrow(() -> new PrestoException(QUERY_REJECTED, "Query did not match any selection rule"));
    }

    private Optional<String> determineQueryType(QueryExecution queryExecution)
    {
        return queryExecution.getQueryType().map(Enum::toString);
    }

    @Managed
    public int getQueriesQueuedOnInternal()
    {
        int queriesQueuedInternal = 0;
        for (RootInternalResourceGroup rootGroup : rootGroups) {
            synchronized (rootGroup) {
                queriesQueuedInternal += getQueriesQueuedOnInternal(rootGroup);
            }
        }

        return queriesQueuedInternal;
    }

    private static int getQueriesQueuedOnInternal(InternalResourceGroup resourceGroup)
    {
        if (resourceGroup.subGroups().isEmpty()) {
            return Math.min(resourceGroup.getQueuedQueries(), resourceGroup.getSoftConcurrencyLimit() - resourceGroup.getRunningQueries());
        }

        int queriesQueuedInternal = 0;
        for (InternalResourceGroup subGroup : resourceGroup.subGroups()) {
            queriesQueuedInternal += getQueriesQueuedOnInternal(subGroup);
        }

        return queriesQueuedInternal;
    }
}
