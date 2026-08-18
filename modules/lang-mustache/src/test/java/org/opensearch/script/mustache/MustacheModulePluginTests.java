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

public class MustacheModulePluginTests extends OpenSearchTestCase {

    public void testRegisteredByDefaultRegistersEverything() {
        MustacheModulePlugin plugin = new MustacheModulePlugin(Settings.EMPTY);
        assertNotNull(plugin.getScriptEngine(Settings.EMPTY, Collections.<ScriptContext<?>>singleton(TemplateScript.CONTEXT)));
        assertEquals(3, plugin.getActions().size());
        assertEquals(3, plugin.getRestHandlers(Settings.EMPTY, null, null, null, null, null, null).size());
    }

    public void testNotRegisteredSkipsRegistration() {
        Settings settings = Settings.builder().put(MustacheModulePlugin.MUSTACHE_REGISTERED_SETTING.getKey(), false).build();
        MustacheModulePlugin plugin = new MustacheModulePlugin(settings);
        assertNull(plugin.getScriptEngine(settings, Collections.<ScriptContext<?>>singleton(TemplateScript.CONTEXT)));
        assertTrue(plugin.getActions().isEmpty());
        List<?> handlers = plugin.getRestHandlers(settings, null, null, null, null, null, null);
        assertTrue(handlers.isEmpty());
    }

    public void testRegisteredSettingIsStaticNodeScoped() {
        assertTrue(MustacheModulePlugin.MUSTACHE_REGISTERED_SETTING.hasNodeScope());
        assertFalse(MustacheModulePlugin.MUSTACHE_REGISTERED_SETTING.isDynamic());
        assertTrue(MustacheModulePlugin.MUSTACHE_REGISTERED_SETTING.get(Settings.EMPTY));
        MustacheModulePlugin plugin = new MustacheModulePlugin(Settings.EMPTY);
        assertTrue(plugin.getSettings().contains(MustacheModulePlugin.MUSTACHE_REGISTERED_SETTING));
    }

    public void testEngineExecuteGuardFollowsOnEnabledChanged() {
        ScriptEngine engine = new MustacheScriptEngine();
        TemplateScript.Factory factory = engine.compile("test", "rendered:{{value}}", TemplateScript.CONTEXT, Collections.emptyMap());
        TemplateScript script = factory.newInstance(Collections.singletonMap("value", "ok"));
        assertEquals("rendered:ok", script.execute());

        // Core framework notifies the engine when script.mustache.enabled flips.
        engine.onEnabledChanged(false);
        OpenSearchStatusException e = expectThrows(OpenSearchStatusException.class, script::execute);
        assertEquals(RestStatus.FORBIDDEN, e.status());

        engine.onEnabledChanged(true);
        assertEquals("rendered:ok", script.execute());
    }
}
