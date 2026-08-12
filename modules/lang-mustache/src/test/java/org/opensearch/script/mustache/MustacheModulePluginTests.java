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

public class MustacheModulePluginTests extends OpenSearchTestCase {

    public void testMustacheEnabledByDefaultRegistersEverything() {
        MustacheModulePlugin plugin = new MustacheModulePlugin(Settings.EMPTY);

        ScriptEngine engine = plugin.getScriptEngine(Settings.EMPTY, Collections.<ScriptContext<?>>singleton(TemplateScript.CONTEXT));
        assertNotNull("mustache script engine should be registered when enabled", engine);
        assertEquals(MustacheScriptEngine.NAME, engine.getType());

        assertEquals("all three transport actions should be registered when enabled", 3, plugin.getActions().size());

        List<?> restHandlers = plugin.getRestHandlers(Settings.EMPTY, null, null, null, null, null, null);
        assertEquals("all three REST handlers should be registered when enabled", 3, restHandlers.size());
    }

    public void testMustacheDisabledRegistersNothing() {
        Settings settings = Settings.builder().put(MustacheModulePlugin.MUSTACHE_ENABLED_SETTING.getKey(), false).build();
        MustacheModulePlugin plugin = new MustacheModulePlugin(settings);

        assertNull(
            "mustache script engine must not be registered when disabled",
            plugin.getScriptEngine(settings, Collections.<ScriptContext<?>>singleton(TemplateScript.CONTEXT))
        );
        assertTrue("no transport actions should be registered when disabled", plugin.getActions().isEmpty());
        assertTrue(
            "no REST handlers should be registered when disabled",
            plugin.getRestHandlers(settings, null, null, null, null, null, null).isEmpty()
        );
    }

    public void testSettingIsExposed() {
        MustacheModulePlugin plugin = new MustacheModulePlugin(Settings.EMPTY);
        assertTrue(plugin.getSettings().contains(MustacheModulePlugin.MUSTACHE_ENABLED_SETTING));
        // default is opt-out preserving prior behavior
        assertTrue(MustacheModulePlugin.MUSTACHE_ENABLED_SETTING.get(Settings.EMPTY));
    }
}
