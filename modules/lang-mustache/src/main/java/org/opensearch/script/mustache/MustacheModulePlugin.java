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
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.IndexScopedSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.settings.SettingsFilter;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.plugins.ActionPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.ScriptPlugin;
import org.opensearch.plugins.SearchPlugin;
import org.opensearch.rest.RestController;
import org.opensearch.rest.RestHandler;
import org.opensearch.script.ScriptContext;
import org.opensearch.script.ScriptEngine;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class MustacheModulePlugin extends Plugin implements ScriptPlugin, ActionPlugin, SearchPlugin {

    /**
     * Static kill-switch controlling whether the mustache templating feature is loaded at all. When {@code false},
     * the mustache script engine, transport actions, and search-template REST endpoints are never registered.
     * Node-scoped and static: set in {@code opensearch.yml} and applied on restart. Defaults to {@code true}.
     *
     * <p>This is distinct from the generic dynamic {@code script.mustache.enabled} switch (provided by the core
     * {@code script.<lang>.enabled} framework), which gates request handling at runtime while the feature is
     * registered. When {@code script.mustache.registered} is {@code false} nothing is registered, so the dynamic
     * switch has nothing to gate.
     */
    public static final Setting<Boolean> MUSTACHE_REGISTERED_SETTING = Setting.boolSetting(
        "script.mustache.registered",
        true,
        Setting.Property.NodeScope
    );

    private final boolean registered;

    public MustacheModulePlugin(Settings settings) {
        this.registered = MUSTACHE_REGISTERED_SETTING.get(settings);
    }

    @Override
    public List<Setting<?>> getSettings() {
        return Collections.singletonList(MUSTACHE_REGISTERED_SETTING);
    }

    @Override
    public ScriptEngine getScriptEngine(Settings settings, Collection<ScriptContext<?>> contexts) {
        if (registered == false) {
            return null;
        }
        return new MustacheScriptEngine();
    }

    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        if (registered == false) {
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
        if (registered == false) {
            return Collections.emptyList();
        }
        return Arrays.asList(
            new RestSearchTemplateAction(),
            new RestMultiSearchTemplateAction(settings),
            new RestRenderSearchTemplateAction()
        );
    }
}
