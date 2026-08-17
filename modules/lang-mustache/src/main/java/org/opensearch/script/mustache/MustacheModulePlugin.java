/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.script.mustache;

import org.opensearch.action.ActionRequest;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.IndexScopedSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.settings.SettingsFilter;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.plugins.ActionPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.ScriptPlugin;
import org.opensearch.plugins.SearchPlugin;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.rest.RestController;
import org.opensearch.rest.RestHandler;
import org.opensearch.script.ScriptContext;
import org.opensearch.script.ScriptEngine;
import org.opensearch.script.ScriptService;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;
import org.opensearch.watcher.ResourceWatcherService;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public class MustacheModulePlugin extends Plugin implements ScriptPlugin, ActionPlugin, SearchPlugin {

    /**
     * Static kill-switch that controls whether the mustache templating feature is loaded at all. When {@code false},
     * the mustache script engine, transport actions, and search-template REST endpoints are never registered
     * (they return 404 / {@code script_lang not supported [mustache]}). This is node-scoped and static: it must be
     * set in {@code opensearch.yml} consistently across nodes and takes effect on restart. Defaults to {@code true}.
     *
     * <p>This is the hard off-switch. When it is {@code false}, {@link #MUSTACHE_RUNTIME_ENABLED_SETTING} has no
     * effect because there is nothing registered to gate, and a runtime update to it cannot resurrect the feature
     * without a restart.
     */
    public static final Setting<Boolean> MUSTACHE_ENABLED_SETTING = Setting.boolSetting(
        "script.mustache.enabled",
        true,
        Setting.Property.NodeScope
    );

    /**
     * Dynamic runtime guard applied only when the feature is registered (i.e. {@link #MUSTACHE_ENABLED_SETTING} is
     * {@code true}). When {@code false}, the engine rejects both template compilation and execution, so requests to
     * the still-registered endpoints fail and even previously cached templates are blocked. Can be toggled at runtime
     * via the cluster settings API. Defaults to {@code true}.
     */
    public static final Setting<Boolean> MUSTACHE_RUNTIME_ENABLED_SETTING = Setting.boolSetting(
        "script.mustache.runtime_enabled",
        true,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    // Resolved once at construction from the static kill-switch; decides whether anything is registered.
    private final boolean enabled;
    // Shared, mutable runtime-guard state read by the engine on every compile/execute; kept current by the
    // cluster-settings update consumer registered in createComponents. Only meaningful when enabled == true.
    private final AtomicBoolean runtimeEnabled = new AtomicBoolean(true);

    public MustacheModulePlugin(Settings settings) {
        this.enabled = MUSTACHE_ENABLED_SETTING.get(settings);
        this.runtimeEnabled.set(MUSTACHE_RUNTIME_ENABLED_SETTING.get(settings));
    }

    @Override
    public List<Setting<?>> getSettings() {
        return Arrays.asList(MUSTACHE_ENABLED_SETTING, MUSTACHE_RUNTIME_ENABLED_SETTING);
    }

    @Override
    public ScriptEngine getScriptEngine(Settings settings, Collection<ScriptContext<?>> contexts) {
        if (enabled == false) {
            return null;
        }
        return new MustacheScriptEngine(runtimeEnabled::get);
    }

    @Override
    public Collection<Object> createComponents(
        Client client,
        ClusterService clusterService,
        ThreadPool threadPool,
        ResourceWatcherService resourceWatcherService,
        ScriptService scriptService,
        NamedXContentRegistry xContentRegistry,
        Environment environment,
        NodeEnvironment nodeEnvironment,
        NamedWriteableRegistry namedWriteableRegistry,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<RepositoriesService> repositoriesServiceSupplier
    ) {
        // The dynamic runtime guard only matters when the feature is registered.
        if (enabled) {
            runtimeEnabled.set(MUSTACHE_RUNTIME_ENABLED_SETTING.get(clusterService.getSettings()));
            clusterService.getClusterSettings().addSettingsUpdateConsumer(MUSTACHE_RUNTIME_ENABLED_SETTING, runtimeEnabled::set);
        }
        return Collections.emptyList();
    }

    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        if (enabled == false) {
            return Collections.emptyList();
        }
        return Arrays.asList(
            new ActionHandler<>(SearchTemplateAction.INSTANCE, TransportSearchTemplateAction.class),
            new ActionHandler<>(RenderSearchTemplateAction.INSTANCE, TransportRenderSearchTemplateAction.class),
            new ActionHandler<>(MultiSearchTemplateAction.INSTANCE, TransportMultiSearchTemplateAction.class)
        );
    }

    @Override
    public List<RestHandler> getRestHandlers(
        Settings settings,
        RestController restController,
        ClusterSettings clusterSettings,
        IndexScopedSettings indexScopedSettings,
        SettingsFilter settingsFilter,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<DiscoveryNodes> nodesInCluster
    ) {
        if (enabled == false) {
            return Collections.emptyList();
        }
        return Arrays.asList(
            new RestSearchTemplateAction(),
            new RestMultiSearchTemplateAction(settings),
            new RestRenderSearchTemplateAction()
        );
    }
}
