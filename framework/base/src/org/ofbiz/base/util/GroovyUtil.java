/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.ofbiz.base.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import javax.script.ScriptContext;

import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.ofbiz.base.location.FlexibleLocation;
import org.ofbiz.base.util.cache.UtilCache;

import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyShell;
import groovy.lang.Script;

/**
 * Groovy Utilities.
 *
 */
public class GroovyUtil {

    private static final Debug.OfbizLogger module = Debug.getOfbizLogger(java.lang.invoke.MethodHandles.lookup().lookupClass());

    private static final UtilCache<String, Class<?>> parsedScripts = UtilCache.createUtilCache("script.GroovyLocationParsedCache", 0, 0, false);

    private static final GroovyClassLoader groovyScriptClassLoader;
    static {
        GroovyClassLoader groovyClassLoader = null;
        String scriptBaseClass = UtilProperties.getPropertyValue("groovy", "scriptBaseClass");
        if (!scriptBaseClass.isEmpty()) {
            CompilerConfiguration conf = new CompilerConfiguration();
            conf.setScriptBaseClass(scriptBaseClass);
            groovyClassLoader = new GroovyClassLoader(GroovyUtil.class.getClassLoader(), conf);
        }
        groovyScriptClassLoader = groovyClassLoader;
        /*
         *  With the implementation of @BaseScript annotations (introduced with Groovy 2.3.0) something was broken
         *  in the CompilerConfiguration.setScriptBaseClass method and an error is thrown when our scripts are executed;
         *  the workaround is to execute at startup a script containing the @BaseScript annotation.
         */
        try {
            GroovyUtil.runScriptAtLocation("component://base/config/GroovyInit.groovy", null, null);
        } catch(Exception e) {
            Debug.logWarning("The following error occurred during the initialization of Groovy: " + e.getMessage(), module);
        }
    }

    /**
     * Evaluate a Groovy condition or expression
     * @param expression The expression to evaluate
     * @param context The context to use in evaluation (re-written)
     * @see <a href="StringUtil.html#convertOperatorSubstitutions(java.lang.String)">StringUtil.convertOperatorSubstitutions(java.lang.String)</a>
     * @return Object The result of the evaluation
     * @throws CompilationFailedException
     */
    @SuppressWarnings("unchecked")
    public static Object eval(String expression, Map<String, Object> context) throws CompilationFailedException {
        Object o;
        if (expression == null || expression.equals("")) {
            Debug.logError("Groovy Evaluation error. Empty expression", module);
            return null;
        }
        if (Debug.verboseOn()) {
            Debug.logVerbose("Evaluating -- " + expression, module);
            Debug.logVerbose("Using Context -- " + context, module);
        }
        try {
            GroovyShell shell = new GroovyShell(getBinding(context));
            o = shell.evaluate(StringUtil.convertOperatorSubstitutions(expression));
            if (Debug.verboseOn()) {
                Debug.logVerbose("Evaluated to -- " + o, module);
            }
            // read back the context info
            Binding binding = shell.getContext();
            context.putAll(binding.getVariables());
        } catch (CompilationFailedException e) {
            Debug.logError(e, "Groovy Evaluation error.", module);
            throw e;
        }
        return o;
    }

    /** Returns a <code>Binding</code> instance initialized with the
     * variables contained in <code>context</code>. If <code>context</code>
     * is <code>null</code>, an empty <code>Binding</code> is returned.
     * <p>The <code>context Map</code> is added to the <code>Binding</code>
     * as a variable called "context" so that variables can be passed
     * back to the caller. Any variables that are created in the script
     * are lost when the script ends unless they are copied to the
     * "context" <code>Map</code>.</p>
     * 
     * @param context A <code>Map</code> containing initial variables
     * @return A <code>Binding</code> instance
     */
    public static Binding getBinding(Map<String, Object> context) {
        Map<String, Object> vars = new HashMap<>();
        if (context != null) {
            vars.putAll(context);
            vars.put("context", context);
            if (vars.get(ScriptUtil.SCRIPT_HELPER_KEY) == null) {
                ScriptContext scriptContext = ScriptUtil.createScriptContext(context);
                ScriptHelper scriptHelper = (ScriptHelper)scriptContext.getAttribute(ScriptUtil.SCRIPT_HELPER_KEY);
                if (scriptHelper != null) {
                    vars.put(ScriptUtil.SCRIPT_HELPER_KEY, scriptHelper);
                }
            }
        }
        return new Binding(vars);
    }

    public static Class<?> getScriptClassFromLocation(String location) throws GeneralException {
        try {
            Class<?> scriptClass = parsedScripts.get(location);
            if (scriptClass == null) {
                URL scriptUrl = FlexibleLocation.resolveLocation(location);
                if (scriptUrl == null) {
                    throw new GeneralException("Script not found at location [" + location + "]");
                }
                if (groovyScriptClassLoader != null) {
                    scriptClass = parseClass(scriptUrl.openStream(), location, groovyScriptClassLoader);
                } else {
                    scriptClass = parseClass(scriptUrl.openStream(), location);
                }
                Class<?> scriptClassCached = parsedScripts.putIfAbsent(location, scriptClass);
                if (scriptClassCached == null) { // putIfAbsent returns null if the class is added to the cache
                    if (Debug.verboseOn()) {
                        Debug.logVerbose("Cached Groovy script at: " + location, module);
                    }
                } else {
                    // the newly parsed script is discarded and the one found in the cache (that has been created by a concurrent thread in the meantime) is used
                    scriptClass = scriptClassCached;
                }
            }
            return scriptClass;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralException("Error loading Groovy script at [" + location + "]: ", e);
        }
    }

    /**
     * @throws IOException 
     * @deprecated SCIPIO: 2017-01-30: ambiguous; method specifying an explicit ClassLoader should be used instead, to ensure library loading consistency.
     */
    @Deprecated
    public static Class<?> loadClass(String path) throws ClassNotFoundException, IOException {
        GroovyClassLoader groovyClassLoader = new GroovyClassLoader();
        Class<?> classLoader = groovyClassLoader.loadClass(path);
        groovyClassLoader.close();
        return classLoader;
    }

    /**
     * @deprecated SCIPIO: 2017-01-30: ambiguous; method specifying an explicit ClassLoader should be used instead, to ensure library loading consistency.
     */
    @Deprecated
    public static Class<?> parseClass(InputStream in, String location) throws IOException {
        GroovyClassLoader groovyClassLoader = new GroovyClassLoader();
        Class<?> classLoader = groovyClassLoader.parseClass(UtilIO.readString(in), location);
        groovyClassLoader.close();
        return classLoader;
    }
    
    public static Class<?> parseClass(InputStream in, String location, GroovyClassLoader groovyClassLoader) throws IOException {
        return groovyClassLoader.parseClass(UtilIO.readString(in), location);
    }

    /**
     * @throws IOException 
     * @deprecated SCIPIO: 2017-01-30: ambiguous; method specifying an explicit ClassLoader should be used instead, to ensure library loading consistency.
     */
    @Deprecated
    public static Class<?> parseClass(String text) throws IOException {
        GroovyClassLoader groovyClassLoader = new GroovyClassLoader();
        Class<?> classLoader = groovyClassLoader.parseClass(text);
        groovyClassLoader.close();
        return classLoader;
    }
    
    public static Class<?> parseClass(String text, GroovyClassLoader groovyClassLoader) throws IOException { // SCIPIO: added 2017-01-27
        return groovyClassLoader.parseClass(text);
    }

    public static Object runScriptAtLocation(String location, String methodName, Map<String, Object> context) throws GeneralException {
        Script script = InvokerHelper.createScript(getScriptClassFromLocation(location), getBinding(context));
        Object result = null;
        if (UtilValidate.isEmpty(methodName)) {
            result = script.run();
        } else {
            result = script.invokeMethod(methodName, new Object[] { context });
        }
        return result;
    }
    
    /**
     * SCIPIO: Special case of {@link #runScriptAtLocation} where no methodName is specified
     */
    public static Object runScriptAtLocation(String location, Map<String, Object> context) throws GeneralException {
        return runScriptAtLocation(location,"",context);
    }
    
    /**
     * SCIPIO: Special case of {@link #runScriptAtLocation} that uses an empty context and returns the new
     * context (instead of script result).
     */
    public static Map<String, Object> runScriptAtLocationNewEmptyContext(String location, String methodName) throws GeneralException {
        Map<String, Object> context = new HashMap<String, Object>();
        runScriptAtLocation(location, methodName, context);
        return context;
    }
    
    /**
     * SCIPIO: Returns a {@link groovy.lang.Script} instance for the script groovy class at the given location,
     * initialized with the given {@link groovy.lang.Binding}.
     * <p>
     * Similar to calling (in Groovy code):
     * <pre>
     * {@code
     * def inst = GroovyUtil.getScriptClassFromLocation(location).newInstance();
     * inst.setBinding(binding);
     * }
     * </pre>
     * <p>
     * NOTE: Although this can handle groovy classes that do not extend <code>groovy.lang.Script</code>,
     * in such cases you may want to call {@link #getScriptClassFromLocation}
     * together with <code>.newInstance()</code> instead of this.
     * <p>
     * Added 2017-01-26.
     * @see #getScriptClassFromLocation
     */
    public static Script getScriptFromLocation(String location, Binding binding) throws GeneralException {
        return InvokerHelper.createScript(getScriptClassFromLocation(location), binding);
    }
    
    /**
     * SCIPIO: Returns a {@link groovy.lang.Script} instance for the script groovy class at the given location,
     * initialized with binding built from the given context.
     * <p>
     * Added 2017-01-26.
     * @see #getScriptFromLocation(String, Binding)
     */
    public static Script getScriptFromLocation(String location, Map<String, Object> context) throws GeneralException {
        return InvokerHelper.createScript(getScriptClassFromLocation(location), getBinding(context));
    }
    
    /**
     * SCIPIO: Returns the static <code>GroovyClassLoader</code> instance, configured
     * for Ofbiz script base class and other settings.
     */
    public static GroovyClassLoader getGroovyScriptClassLoader() {
        return groovyScriptClassLoader;
    }
    
    private GroovyUtil() {}
}
