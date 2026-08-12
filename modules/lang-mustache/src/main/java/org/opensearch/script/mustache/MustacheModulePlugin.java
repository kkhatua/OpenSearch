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
     * Opt-out switch that lets a cluster administrator disable the mustache templating feature entirely
     * (the {@code mustache} script engine as well as the search/render template REST endpoints and their
     * transport actions). Defaults to {@code true} to preserve existing behavior. Static and node-scoped:
     * it must be set in {@code opensearch.yml} consistently across all nodes and takes effect on restart.
     */
    public static final Setting<Boolean> MUSTACHE_ENABLED_SETTING = Setting.boolSetting(
        "script.mustache.enabled",
        true,
        Setting.Property.NodeScope
    );

    private final boolean enabled;

    public MustacheModulePlugin(Settings settings) {
        this.enabled = MUSTACHE_ENABLED_SETTING.get(settings);
    }

    @Override
    public List<Setting<?>> getSettings() {
        return Collections.singletonList(MUSTACHE_ENABLED_SETTING);
    }

    @Override
    public ScriptEngine getScriptEngine(Settings settings, Collection<ScriptContext<?>> contexts) {
        if (enabled == false) {
            return null;
        }
        return new MustacheScriptEngine();
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
