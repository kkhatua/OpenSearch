/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.script.mustache;

import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.opensearch.ExceptionsHelper;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.plugins.Plugin;
import org.opensearch.script.ScriptType;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.opensearch.test.ParameterizedStaticSettingsOpenSearchIntegTestCase;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;

/**
 * End-to-end coverage of the full matrix of the two mustache toggles:
 * <ul>
 *   <li>{@code script.mustache.enabled} (static kill-switch, gates registration)</li>
 *   <li>{@code script.mustache.runtime_enabled} (dynamic guard, gates requests when registered)</li>
 * </ul>
 * The four combinations are supplied as static node settings (a new cluster per combination). Each run also
 * exercises the dynamic runtime toggle so the {@code addSettingsUpdateConsumer} wiring is verified against a live
 * cluster.
 */
@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.TEST, numDataNodes = 1, numClientNodes = 0)
public class MustacheToggleMatrixIT extends ParameterizedStaticSettingsOpenSearchIntegTestCase {

    private static final String KILL_SWITCH = MustacheModulePlugin.MUSTACHE_ENABLED_SETTING.getKey();
    private static final String RUNTIME_GUARD = MustacheModulePlugin.MUSTACHE_RUNTIME_ENABLED_SETTING.getKey();

    public MustacheToggleMatrixIT(Settings nodeSettings) {
        super(nodeSettings);
    }

    @ParametersFactory
    public static Collection<Object[]> parameters() {
        return Arrays.asList(
            new Object[] { Settings.builder().put(KILL_SWITCH, true).put(RUNTIME_GUARD, true).build() },
            new Object[] { Settings.builder().put(KILL_SWITCH, true).put(RUNTIME_GUARD, false).build() },
            new Object[] { Settings.builder().put(KILL_SWITCH, false).put(RUNTIME_GUARD, true).build() },
            new Object[] { Settings.builder().put(KILL_SWITCH, false).put(RUNTIME_GUARD, false).build() }
        );
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Collections.singletonList(MustacheModulePlugin.class);
    }

    public void testToggleMatrix() throws Exception {
        final boolean staticEnabled = MustacheModulePlugin.MUSTACHE_ENABLED_SETTING.get(settings);
        final boolean runtimeEnabled = MustacheModulePlugin.MUSTACHE_RUNTIME_ENABLED_SETTING.get(settings);

        client().prepareIndex("test").setId("1").setSource("text", "value1").setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get();

        if (staticEnabled == false) {
            // Kill-switch off: the feature is not registered at all, regardless of the runtime flag.
            assertUnregistered();
            // And the static kill-switch cannot be overridden at runtime: flipping the dynamic setting is a no-op.
            setRuntimeGuard(true);
            assertUnregistered();
            return;
        }

        // Kill-switch on: initial behavior reflects the runtime guard's boot value.
        if (runtimeEnabled) {
            assertTemplateSucceeds();
        } else {
            assertTemplateForbidden();
        }

        // Exercise the dynamic runtime toggle in both directions against the live cluster.
        setRuntimeGuard(false);
        assertBusy(this::assertTemplateForbidden);

        setRuntimeGuard(true);
        assertBusy(this::assertTemplateSucceeds);
    }

    private SearchTemplateResponse runTemplate() {
        SearchTemplateRequest request = new SearchTemplateRequest();
        request.setRequest(new SearchRequest("test"));
        request.setScriptType(ScriptType.INLINE);
        request.setScript("{ \"query\": { \"match_all\": {} }, \"size\": \"{{my_size}}\" }");
        request.setScriptParams(Collections.singletonMap("my_size", 1));
        return client().execute(SearchTemplateAction.INSTANCE, request).actionGet();
    }

    /** The feature is loaded and enabled: the template renders and executes, returning the single indexed doc. */
    private void assertTemplateSucceeds() throws Exception {
        try {
            SearchTemplateResponse response = runTemplate();
            assertNotNull(response.getResponse());
            assertThat(response.getResponse().getHits().getHits().length, equalTo(1));
        } catch (Exception e) {
            // Convert to AssertionError so assertBusy retries during dynamic-setting propagation.
            throw new AssertionError("expected search template to succeed but it failed", e);
        }
    }

    /** The feature is loaded but the runtime guard is off: requests are refused with 403 Forbidden. */
    private void assertTemplateForbidden() {
        Exception e = expectThrows(Exception.class, this::runTemplate);
        assertEquals("mustache templating disabled should map to 403 Forbidden", RestStatus.FORBIDDEN, ExceptionsHelper.status(e));
    }

    /** The kill-switch is off: the transport action was never registered, so there is no handler for it. */
    private void assertUnregistered() {
        Exception e = expectThrows(Exception.class, this::runTemplate);
        String chain = messageChain(e);
        assertTrue(
            "expected an action-not-registered error but was: " + chain,
            chain.contains("failed to find action") || chain.contains("No handler for action")
        );
    }

    private void setRuntimeGuard(boolean value) {
        assertAcked(
            client().admin().cluster().prepareUpdateSettings().setPersistentSettings(Settings.builder().put(RUNTIME_GUARD, value)).get()
        );
    }

    private static String messageChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null) {
                sb.append(c.getMessage()).append(" | ");
            }
        }
        return sb.toString();
    }
}
