/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.script.mustache;

import org.opensearch.OpenSearchStatusException;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.script.ScriptContext;
import org.opensearch.script.ScriptEngine;
import org.opensearch.script.TemplateScript;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class MustacheModulePluginTests extends OpenSearchTestCase {

    private static Settings settings(boolean enabled, boolean runtimeEnabled) {
        return Settings.builder()
            .put(MustacheModulePlugin.MUSTACHE_ENABLED_SETTING.getKey(), enabled)
            .put(MustacheModulePlugin.MUSTACHE_RUNTIME_ENABLED_SETTING.getKey(), runtimeEnabled)
            .build();
    }

    public void testDefaultsRegisterEverything() {
        MustacheModulePlugin plugin = new MustacheModulePlugin(Settings.EMPTY);
        assertNotNull(plugin.getScriptEngine(Settings.EMPTY, Collections.<ScriptContext<?>>singleton(TemplateScript.CONTEXT)));
        assertEquals(3, plugin.getActions().size());
        assertEquals(3, plugin.getRestHandlers(Settings.EMPTY, null, null, null, null, null, null).size());
    }

    public void testStaticKillSwitchSkipsRegistration() {
        // enabled=false must skip registration regardless of the runtime setting.
        for (boolean runtime : new boolean[] { true, false }) {
            Settings s = settings(false, runtime);
            MustacheModulePlugin plugin = new MustacheModulePlugin(s);
            assertNull(
                "engine must not be registered when the static kill-switch is off",
                plugin.getScriptEngine(s, Collections.<ScriptContext<?>>singleton(TemplateScript.CONTEXT))
            );
            assertTrue(plugin.getActions().isEmpty());
            assertTrue(plugin.getRestHandlers(s, null, null, null, null, null, null).isEmpty());
        }
    }

    public void testEnabledRegistersRegardlessOfRuntimeFlag() {
        // enabled=true always registers; the runtime flag only affects request handling, not registration.
        for (boolean runtime : new boolean[] { true, false }) {
            Settings s = settings(true, runtime);
            MustacheModulePlugin plugin = new MustacheModulePlugin(s);
            ScriptEngine engine = plugin.getScriptEngine(s, Collections.<ScriptContext<?>>singleton(TemplateScript.CONTEXT));
            assertNotNull(engine);
            assertEquals(MustacheScriptEngine.NAME, engine.getType());
            assertEquals(3, plugin.getActions().size());
            List<?> handlers = plugin.getRestHandlers(s, null, null, null, null, null, null);
            assertEquals(3, handlers.size());
        }
    }

    public void testSettingScopes() {
        assertTrue(MustacheModulePlugin.MUSTACHE_ENABLED_SETTING.hasNodeScope());
        assertFalse("kill-switch is static", MustacheModulePlugin.MUSTACHE_ENABLED_SETTING.isDynamic());

        assertTrue(MustacheModulePlugin.MUSTACHE_RUNTIME_ENABLED_SETTING.hasNodeScope());
        assertTrue("runtime guard is dynamic", MustacheModulePlugin.MUSTACHE_RUNTIME_ENABLED_SETTING.isDynamic());

        MustacheModulePlugin plugin = new MustacheModulePlugin(Settings.EMPTY);
        assertTrue(plugin.getSettings().contains(MustacheModulePlugin.MUSTACHE_ENABLED_SETTING));
        assertTrue(plugin.getSettings().contains(MustacheModulePlugin.MUSTACHE_RUNTIME_ENABLED_SETTING));
        assertTrue(MustacheModulePlugin.MUSTACHE_ENABLED_SETTING.get(Settings.EMPTY));
        assertTrue(MustacheModulePlugin.MUSTACHE_RUNTIME_ENABLED_SETTING.get(Settings.EMPTY));
    }

    public void testRuntimeGuardRejectsCompileWhenDisabled() {
        MustacheScriptEngine engine = new MustacheScriptEngine(() -> false);
        OpenSearchStatusException e = expectThrows(
            OpenSearchStatusException.class,
            () -> engine.compile("test", "{{value}}", TemplateScript.CONTEXT, Collections.emptyMap())
        );
        assertEquals(RestStatus.FORBIDDEN, e.status());
        assertTrue(e.getMessage(), e.getMessage().contains("script.mustache.runtime_enabled"));
    }

    public void testRuntimeToggleBlocksCachedTemplate() {
        AtomicBoolean runtime = new AtomicBoolean(true);
        MustacheScriptEngine engine = new MustacheScriptEngine(runtime::get);

        TemplateScript.Factory factory = engine.compile("test", "rendered:{{value}}", TemplateScript.CONTEXT, Collections.emptyMap());
        TemplateScript script = factory.newInstance(Collections.singletonMap("value", "ok"));
        assertEquals("rendered:ok", script.execute());

        runtime.set(false);
        OpenSearchStatusException e = expectThrows(OpenSearchStatusException.class, script::execute);
        assertEquals(RestStatus.FORBIDDEN, e.status());
        assertTrue(e.getMessage(), e.getMessage().contains("script.mustache.runtime_enabled"));

        runtime.set(true);
        assertEquals("rendered:ok", script.execute());
    }
}
