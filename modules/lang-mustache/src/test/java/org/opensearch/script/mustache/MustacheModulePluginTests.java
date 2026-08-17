/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.script.mustache;

import org.opensearch.common.settings.Settings;
import org.opensearch.script.ScriptContext;
import org.opensearch.script.ScriptEngine;
import org.opensearch.script.TemplateScript;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class MustacheModulePluginTests extends OpenSearchTestCase {

    public void testEngineAndHandlersAlwaysRegistered() {
        // The runtime-guard approach keeps everything registered regardless of the setting; requests are rejected
        // at runtime by the engine rather than by skipping registration.
        for (boolean enabled : new boolean[] { true, false }) {
            Settings settings = Settings.builder().put(MustacheModulePlugin.MUSTACHE_ENABLED_SETTING.getKey(), enabled).build();
            MustacheModulePlugin plugin = new MustacheModulePlugin();

            ScriptEngine engine = plugin.getScriptEngine(settings, Collections.<ScriptContext<?>>singleton(TemplateScript.CONTEXT));
            assertNotNull("mustache engine is always registered", engine);
            assertEquals(MustacheScriptEngine.NAME, engine.getType());

            assertEquals("transport actions are always registered", 3, plugin.getActions().size());
            List<?> restHandlers = plugin.getRestHandlers(settings, null, null, null, null, null, null);
            assertEquals("REST handlers are always registered", 3, restHandlers.size());
        }
    }

    public void testSettingIsDynamicAndExposed() {
        MustacheModulePlugin plugin = new MustacheModulePlugin();
        assertTrue(plugin.getSettings().contains(MustacheModulePlugin.MUSTACHE_ENABLED_SETTING));
        assertTrue("setting must be dynamic to support runtime toggling", MustacheModulePlugin.MUSTACHE_ENABLED_SETTING.isDynamic());
        assertTrue("setting must be node scoped", MustacheModulePlugin.MUSTACHE_ENABLED_SETTING.hasNodeScope());
        assertTrue("default preserves existing behavior", MustacheModulePlugin.MUSTACHE_ENABLED_SETTING.get(Settings.EMPTY));
    }

    public void testCompileRejectedWhenDisabled() {
        AtomicBoolean enabled = new AtomicBoolean(false);
        MustacheScriptEngine engine = new MustacheScriptEngine(enabled::get);
        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> engine.compile("test", "{{value}}", TemplateScript.CONTEXT, Collections.emptyMap())
        );
        assertTrue(e.getMessage(), e.getMessage().contains("script.mustache.enabled"));
    }

    public void testRuntimeToggleBlocksCachedTemplate() {
        // Compile while enabled, then disable and confirm even the already-compiled (cached) template is blocked at
        // execute time. This is the key behavioral difference from skipping registration.
        AtomicBoolean enabled = new AtomicBoolean(true);
        MustacheScriptEngine engine = new MustacheScriptEngine(enabled::get);

        TemplateScript.Factory factory = engine.compile("test", "rendered:{{value}}", TemplateScript.CONTEXT, Collections.emptyMap());
        TemplateScript script = factory.newInstance(Collections.singletonMap("value", "ok"));
        assertEquals("rendered:ok", script.execute());

        enabled.set(false);
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, script::execute);
        assertTrue(e.getMessage(), e.getMessage().contains("script.mustache.enabled"));

        // Re-enabling restores execution without recompilation.
        enabled.set(true);
        assertEquals("rendered:ok", script.execute());
    }
}
