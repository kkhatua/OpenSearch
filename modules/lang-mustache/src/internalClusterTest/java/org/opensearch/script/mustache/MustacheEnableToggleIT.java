/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.script.mustache;

import org.opensearch.ExceptionsHelper;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.plugins.Plugin;
import org.opensearch.script.ScriptService;
import org.opensearch.script.ScriptType;
import org.opensearch.test.OpenSearchSingleNodeTestCase;

import java.util.Collection;
import java.util.Collections;

import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;

/**
 * End-to-end verification of the generic {@code script.<lang>.enabled} framework using the mustache engine: a live
 * {@code PUT _cluster/settings} of {@code script.mustache.enabled} toggles search-template execution, with the core
 * {@link ScriptService} guard returning 403 while disabled (and evicting only the mustache cache so cached templates
 * are refused too), then restoring service when re-enabled.
 */
public class MustacheEnableToggleIT extends OpenSearchSingleNodeTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singletonList(MustacheModulePlugin.class);
    }

    public void testDynamicDisableReturns403ThenReenable() throws Exception {
        createIndex("test");
        client().prepareIndex("test").setId("1").setSource("text", "value1").setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get();

        // Enabled by default: the search template runs.
        try {
            assertThat(runTemplate().getResponse().getHits().getHits().length, equalTo(1));

            // Disable mustache dynamically.
            setMustacheEnabled(false);
            Exception e = expectThrows(Exception.class, this::runTemplate);
            assertEquals(RestStatus.FORBIDDEN, ExceptionsHelper.status(e));

            // Re-enable and confirm service is restored (template recompiles and runs).
            setMustacheEnabled(true);
            assertThat(runTemplate().getResponse().getHits().getHits().length, equalTo(1));
        } finally {
            // Remove the persistent setting so the test does not leave cluster metadata behind.
            assertAcked(
                client().admin()
                    .cluster()
                    .prepareUpdateSettings()
                    .setPersistentSettings(Settings.builder().putNull(mustacheEnabledKey()))
                    .get()
            );
        }
    }

    private static String mustacheEnabledKey() {
        return ScriptService.SCRIPT_LANG_ENABLED_SETTING.getConcreteSettingForNamespace("mustache").getKey();
    }

    private SearchTemplateResponse runTemplate() {
        SearchTemplateRequest request = new SearchTemplateRequest();
        request.setRequest(new SearchRequest("test"));
        request.setScriptType(ScriptType.INLINE);
        request.setScript("{ \"query\": { \"match_all\": {} }, \"size\": \"{{my_size}}\" }");
        request.setScriptParams(Collections.singletonMap("my_size", 1));
        return client().execute(SearchTemplateAction.INSTANCE, request).actionGet();
    }

    private void setMustacheEnabled(boolean value) {
        assertAcked(
            client().admin()
                .cluster()
                .prepareUpdateSettings()
                .setPersistentSettings(Settings.builder().put(mustacheEnabledKey(), value))
                .get()
        );
    }
}
