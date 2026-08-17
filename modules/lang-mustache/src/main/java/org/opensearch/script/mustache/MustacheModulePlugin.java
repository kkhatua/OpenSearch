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
     * Dynamic switch that lets a cluster administrator enable or disable the mustache templating feature at
     * runtime via the cluster settings API. Defaults to {@code true} to preserve existing behavior. Because it
     * is dynamic, unlike a {@code NodeScope}-only setting, the mustache script engine and the search-template
     * REST endpoints and transport actions remain registered; requests are instead rejected at runtime while the
     * setting is {@code false}. The guard is enforced in {@link MustacheScriptEngine} on both template
     * compilation and execution, so cached (previously compiled) templates are also blocked once disabled.
     */
    public static final Setting<Boolean> MUSTACHE_ENABLED_SETTING = Setting.boolSetting(
        "script.mustache.enabled",
        true,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    // Shared, mutable enabled-state read by the mustache script engine on every compile/execute. Seeded from the
    // node settings and kept up to date by the cluster-settings update consumer registered in createComponents.
    private final AtomicBoolean mustacheEnabled = new AtomicBoolean(true);

    @Override
    public List<Setting<?>> getSettings() {
        return Collections.singletonList(MUSTACHE_ENABLED_SETTING);
    }

    @Override
    public ScriptEngine getScriptEngine(Settings settings, Collection<ScriptContext<?>> contexts) {
        mustacheEnabled.set(MUSTACHE_ENABLED_SETTING.get(settings));
        return new MustacheScriptEngine(mustacheEnabled::get);
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
        // Seed from the effective node settings and subscribe to dynamic updates from the cluster settings API.
        mustacheEnabled.set(MUSTACHE_ENABLED_SETTING.get(clusterService.getSettings()));
        clusterService.getClusterSettings().addSettingsUpdateConsumer(MUSTACHE_ENABLED_SETTING, mustacheEnabled::set);
        return Collections.emptyList();
    }

    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
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
        return Arrays.asList(
            new RestSearchTemplateAction(),
            new RestMultiSearchTemplateAction(settings),
            new RestRenderSearchTemplateAction()
        );
    }
}
