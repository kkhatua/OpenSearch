/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.painless;

import org.opensearch.OpenSearchStatusException;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.painless.spi.Allowlist;
import org.opensearch.painless.spi.AllowlistLoader;
import org.opensearch.script.ScriptContext;
import org.opensearch.test.OpenSearchTestCase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.opensearch.painless.action.PainlessExecuteAction.PainlessTestScript;

public class PainlessDisableTests extends OpenSearchTestCase {

    private static Map<ScriptContext<?>, List<Allowlist>> testContexts() {
        Map<ScriptContext<?>, List<Allowlist>> contexts = new HashMap<>();
        List<Allowlist> allowlists = new ArrayList<>(Allowlist.BASE_ALLOWLISTS);
        allowlists.add(AllowlistLoader.loadFromResourceFiles(Allowlist.class, "org.opensearch.painless.test"));
        contexts.put(PainlessTestScript.CONTEXT, allowlists);
        return contexts;
    }

    public void testCompileRejectedWith403WhenDisabled() {
        PainlessScriptEngine engine = new PainlessScriptEngine(Settings.EMPTY, testContexts(), () -> false);
        OpenSearchStatusException e = expectThrows(
            OpenSearchStatusException.class,
            () -> engine.compile("test", "1", PainlessTestScript.CONTEXT, Collections.emptyMap())
        );
        assertEquals(RestStatus.FORBIDDEN, e.status());
        assertTrue(e.getMessage(), e.getMessage().contains("script.painless.enabled"));
    }

    public void testCompileSucceedsWhenEnabled() {
        PainlessScriptEngine engine = new PainlessScriptEngine(Settings.EMPTY, testContexts(), () -> true);
        assertNotNull(engine.compile("test", "1", PainlessTestScript.CONTEXT, Collections.emptyMap()));
    }

    public void testRuntimeToggleReflectedOnNextCompile() {
        java.util.concurrent.atomic.AtomicBoolean enabled = new java.util.concurrent.atomic.AtomicBoolean(true);
        PainlessScriptEngine engine = new PainlessScriptEngine(Settings.EMPTY, testContexts(), enabled::get);

        assertNotNull(engine.compile("t", "1", PainlessTestScript.CONTEXT, Collections.emptyMap()));

        enabled.set(false);
        OpenSearchStatusException e = expectThrows(
            OpenSearchStatusException.class,
            () -> engine.compile("t2", "1", PainlessTestScript.CONTEXT, Collections.emptyMap())
        );
        assertEquals(RestStatus.FORBIDDEN, e.status());

        enabled.set(true);
        assertNotNull(engine.compile("t3", "1", PainlessTestScript.CONTEXT, Collections.emptyMap()));
    }

    public void testSettingIsDynamicAndDefaultsTrue() {
        assertTrue(PainlessModulePlugin.PAINLESS_ENABLED_SETTING.isDynamic());
        assertTrue(PainlessModulePlugin.PAINLESS_ENABLED_SETTING.hasNodeScope());
        assertTrue(PainlessModulePlugin.PAINLESS_ENABLED_SETTING.get(Settings.EMPTY));
    }
}
