/*
 * ====================
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2016 ConnId. All rights reserved.
 *
 * The contents of this file are subject to the terms of the Common Development
 * and Distribution License("CDDL") (the "License").  You may not use this file
 * except in compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://opensource.org/licenses/cddl1.php
 * See the License for the specific language governing permissions and limitations
 * under the License.
 *
 * When distributing the Covered Code, include this CDDL Header Notice in each file
 * and include the License file at http://opensource.org/licenses/cddl1.php.
 * If applicable, add the following below this CDDL Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 * ====================
 */
package net.tirasa.connid.commons.scripted;

import static net.tirasa.connid.commons.scripted.Constants.MSG_OBJECT_CLASS_REQUIRED;
import static net.tirasa.connid.commons.scripted.Constants.MSG_INVALID_ATTRIBUTE_SET;
import static net.tirasa.connid.commons.scripted.Constants.MSG_BLANK_UID;
import static net.tirasa.connid.commons.scripted.Constants.MSG_BLANK_RESULT_HANDLER;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.identityconnectors.common.IOUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.script.ScriptExecutor;
import org.identityconnectors.common.script.ScriptExecutorFactory;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.api.operations.ResolveUsernameApiOp;
import org.identityconnectors.framework.common.FrameworkUtil;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.AttributeUtil;
import org.identityconnectors.framework.common.objects.ConnectorObjectBuilder;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.OperationalAttributes;
import org.identityconnectors.framework.common.objects.ResultsHandler;
import org.identityconnectors.framework.common.objects.Schema;
import org.identityconnectors.framework.common.objects.SchemaBuilder;
import org.identityconnectors.framework.common.objects.SearchResult;
import org.identityconnectors.framework.common.objects.SyncDeltaBuilder;
import org.identityconnectors.framework.common.objects.SyncDeltaType;
import org.identityconnectors.framework.common.objects.SyncResultsHandler;
import org.identityconnectors.framework.common.objects.SyncToken;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.spi.Configuration;
import org.identityconnectors.framework.spi.Connector;
import org.identityconnectors.framework.spi.SearchResultsHandler;
import org.identityconnectors.framework.spi.operations.AuthenticateOp;
import org.identityconnectors.framework.spi.operations.CreateOp;
import org.identityconnectors.framework.spi.operations.DeleteOp;
import org.identityconnectors.framework.spi.operations.SchemaOp;
import org.identityconnectors.framework.spi.operations.SearchOp;
import org.identityconnectors.framework.spi.operations.SyncOp;
import org.identityconnectors.framework.spi.operations.TestOp;
import org.identityconnectors.framework.spi.operations.UpdateAttributeValuesOp;
import org.identityconnectors.framework.spi.operations.UpdateOp;

public abstract class AbstractScriptedConnector<C extends AbstractScriptedConfiguration> implements Connector,
        CreateOp, UpdateOp, UpdateAttributeValuesOp, DeleteOp,
        AuthenticateOp, ResolveUsernameApiOp, SchemaOp, SyncOp, TestOp, SearchOp<Map<String, Object>> {

    protected static final Log LOG = Log.getLog(AbstractScriptedConnector.class);

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{[a-zA-Z]+\\w*\\}");

    public static final String resolveVariables(final String input) {
        Set<String> vars = new HashSet<String>();
        Matcher matcher = VARIABLE.matcher(input);
        while (matcher.find()) {
            int n = 0;
            for (int i = matcher.start() - 1; i >= 0 && input.charAt(i) == '\\'; i--) {
                n++;
            }
            if (n % 2 != 0) {
                continue;
            }

            vars.add(input.substring(matcher.start() + 2, matcher.end() - 1));
        }

        String resolved = input;
        for (String var : vars) {
            String replacement = System.getProperty(var);
            if (replacement != null) {
                resolved = resolved.replace("${" + var + "}", replacement);
            }
        }
        return resolved;
    }

    protected C config;

    private Schema schema;

    private ScriptExecutorFactory factory;

    private ScriptExecutor createExecutor;

    private ScriptExecutor updateExecutor;

    private ScriptExecutor deleteExecutor;

    private ScriptExecutor searchExecutor;

    private ScriptExecutor authenticateExecutor;

    private ScriptExecutor resolveUsernameExecutor;

    private ScriptExecutor syncExecutor;

    private ScriptExecutor schemaExecutor;

    private ScriptExecutor testExecutor;

    @Override
    public C getConfiguration() {
        return config;
    }

    @Override
    public void dispose() {
        // nothing to do
    }

    private ScriptExecutor getScriptExecutor(String script, String scriptFileName) {
        String scriptCode = script;
        ScriptExecutor scriptExec = null;

        try {
            if (scriptFileName != null) {
                scriptCode = IOUtil.readFileUTF8(new File(resolveVariables(scriptFileName)));
            }
            if (scriptCode.length() > 0) {
                scriptExec = factory.newScriptExecutor(getClass().getClassLoader(), scriptCode, true);
            }
        } catch (IOException e) {
            throw new ConnectorException("Script error", e);
        }
        return scriptExec;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void init(final Configuration cfg) {
        this.config = (C) cfg;

        this.factory = ScriptExecutorFactory.newInstance(config.getScriptingLanguage());

        // We need an executor for each and every script. At least, they'll get evaluated and compiled.
        // We privilege the script file over the script string if script filename is null, then we use the script string
        createExecutor = getScriptExecutor(config.getCreateScript(), config.getCreateScriptFileName());
        LOG.ok("Create script loaded");

        updateExecutor = getScriptExecutor(config.getUpdateScript(), config.getUpdateScriptFileName());
        LOG.ok("Update script loaded");

        deleteExecutor = getScriptExecutor(config.getDeleteScript(), config.getDeleteScriptFileName());
        LOG.ok("Delete script loaded");

        searchExecutor = getScriptExecutor(config.getSearchScript(), config.getSearchScriptFileName());
        LOG.ok("Search script loaded");

        authenticateExecutor = getScriptExecutor(
                config.getAuthenticateScript(), config.getAuthenticateScriptFileName());
        LOG.ok("Search script loaded");

        resolveUsernameExecutor = getScriptExecutor(
                config.getResolveUsernameScript(), config.getResolveUsernameScriptFileName());
        LOG.ok("Search script loaded");

        syncExecutor = getScriptExecutor(config.getSyncScript(), config.getSyncScriptFileName());
        LOG.ok("Sync script loaded");

        schemaExecutor = getScriptExecutor("", config.getSchemaScriptFileName());
        LOG.ok("Sync script loaded");

        testExecutor = getScriptExecutor(config.getTestScript(), config.getTestScriptFileName());
        LOG.ok("Test script loaded");

        LOG.ok("Connector {0} successfully inited", getClass().getName());
    }

    protected abstract Map<String, Object> buildArguments();

    @Override
    public Uid create(
            final ObjectClass objectClass,
            final Set<Attribute> createAttributes,
            final OperationOptions options) {

        if (config.isReloadScriptOnExecution()) {
            createExecutor = getScriptExecutor(config.getCreateScript(), config.getCreateScriptFileName());
            LOG.ok("Create script loaded");
        }
        if (createExecutor != null) {
            if (objectClass == null) {
                throw new IllegalArgumentException(config.getMessage(MSG_OBJECT_CLASS_REQUIRED));
            }
            LOG.ok("Object class: {0}", objectClass.getObjectClassValue());

            if (createAttributes == null || createAttributes.isEmpty()) {
                throw new IllegalArgumentException(config.getMessage(MSG_INVALID_ATTRIBUTE_SET));
            }

            final Map<String, Object> arguments = buildArguments();

            arguments.put("action", "CREATE");
            arguments.put("log", LOG);
            arguments.put("objectClass", objectClass.getObjectClassValue());
            arguments.put("options", options.getOptions());
            // We give the id (name) as an argument, more friendly than dealing with __NAME__
            arguments.put("id", AttributeUtil.getNameFromAttributes(createAttributes).getNameValue() == null
                    ? AttributeUtil.getUidAttribute(createAttributes).getUidValue()
                    : AttributeUtil.getNameFromAttributes(createAttributes).getNameValue());

            Map<String, List<Object>> attrMap = new HashMap<String, List<Object>>();
            for (Attribute attr : createAttributes) {
                attrMap.put(attr.getName(), attr.getValue());
            }
            // let's get rid of __NAME__
            attrMap.remove(Name.NAME);
            arguments.put("attributes", attrMap);

            try {
                Object uidAfter = createExecutor.execute(arguments);
                if (uidAfter instanceof String) {
                    LOG.ok("{0} created", uidAfter);
                    return new Uid((String) uidAfter);
                } else {
                    throw new ConnectorException("Create script didn't return with the __UID__ value");
                }
            } catch (Exception e) {
                throw new ConnectorException("Create script error", e);
            }
        } else {
            throw new UnsupportedOperationException();
        }
    }

    private Uid genericUpdate(
            final String method,
            final ObjectClass objClass,
            final Uid uid, Set<Attribute> attrs,
            final OperationOptions options) {

        if (config.isReloadScriptOnExecution()) {
            updateExecutor = getScriptExecutor(config.getUpdateScript(), config.getUpdateScriptFileName());
            LOG.ok("Update ({0}) script loaded", method);
        }
        if (updateExecutor != null) {
            if (objClass == null) {
                throw new IllegalArgumentException(config.getMessage(MSG_OBJECT_CLASS_REQUIRED));
            }
            LOG.ok("Object class: {0}", objClass.getObjectClassValue());

            if (attrs == null || attrs.isEmpty()) {
                throw new IllegalArgumentException(config.getMessage(MSG_INVALID_ATTRIBUTE_SET));
            }

            if (uid == null || (uid.getUidValue() == null)) {
                throw new IllegalArgumentException(config.getMessage(MSG_BLANK_UID));
            }
            final String id = uid.getUidValue();

            final Map<String, Object> arguments = buildArguments();

            arguments.put("action", method);
            arguments.put("log", LOG);
            arguments.put("objectClass", objClass.getObjectClassValue());
            arguments.put("uid", id);
            arguments.put("options", options.getOptions());

            Map<String, List<Object>> attrMap = new HashMap<String, List<Object>>();
            for (Attribute attr : attrs) {
                if (OperationalAttributes.isOperationalAttribute(attr)) {
                    if (method.equalsIgnoreCase("UPDATE")) {
                        attrMap.put(attr.getName(), attr.getValue());
                    }
                } else {
                    attrMap.put(attr.getName(), attr.getValue());
                }
            }
            arguments.put("attributes", attrMap);

            try {
                Object uidAfter = updateExecutor.execute(arguments);
                if (uidAfter instanceof String) {
                    LOG.ok("{0} updated ({1})", uidAfter, method);
                    return new Uid((String) uidAfter);
                }
            } catch (Exception e) {
                throw new ConnectorException("Update(" + method + ") script error", e);
            }
            throw new ConnectorException("Update script didn't return with the __UID__ value");
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public Uid update(
            final ObjectClass objectClass,
            final Uid uid,
            final Set<Attribute> replaceAttributes,
            final OperationOptions options) {

        return genericUpdate("UPDATE", objectClass, uid, replaceAttributes, options);
    }

    @Override
    public Uid addAttributeValues(
            final ObjectClass objectClass,
            final Uid uid,
            final Set<Attribute> valuesToAdd,
            final OperationOptions options) {

        return genericUpdate("ADD_ATTRIBUTE_VALUES", objectClass, uid, valuesToAdd, options);
    }

    @Override
    public Uid removeAttributeValues(
            final ObjectClass objectClass,
            final Uid uid,
            final Set<Attribute> valuesToRemove,
            final OperationOptions options) {

        return genericUpdate("REMOVE_ATTRIBUTE_VALUES", objectClass, uid, valuesToRemove, options);
    }

    @Override
    public void delete(
            final ObjectClass objectClass,
            final Uid uid,
            final OperationOptions options) {

        if (config.isReloadScriptOnExecution()) {
            deleteExecutor = getScriptExecutor(config.getDeleteScript(), config.getDeleteScriptFileName());
            LOG.ok("Delete script loaded");
        }
        if (deleteExecutor != null) {
            if (objectClass == null) {
                throw new IllegalArgumentException(config.getMessage(MSG_OBJECT_CLASS_REQUIRED));
            }
            LOG.ok("Object class: {0}", objectClass.getObjectClassValue());

            if (uid == null || (uid.getUidValue() == null)) {
                throw new IllegalArgumentException(config.getMessage(MSG_BLANK_UID));
            }
            final String id = uid.getUidValue();

            final Map<String, Object> arguments = buildArguments();

            arguments.put("action", "DELETE");
            arguments.put("log", LOG);
            arguments.put("objectClass", objectClass.getObjectClassValue());
            arguments.put("uid", id);
            arguments.put("options", options.getOptions());

            try {
                deleteExecutor.execute(arguments);
                LOG.ok("{0} deleted", id);
            } catch (Exception e) {
                throw new ConnectorException("Delete script error", e);
            }
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public Uid authenticate(
            final ObjectClass objectClass,
            final String username,
            final GuardedString password,
            final OperationOptions options) {

        if (config.isReloadScriptOnExecution()) {
            authenticateExecutor = getScriptExecutor(
                    config.getAuthenticateScript(), config.getAuthenticateScriptFileName());
            LOG.ok("Authenticate script loaded");
        }
        if (authenticateExecutor != null) {
            if (objectClass == null) {
                throw new IllegalArgumentException(config.getMessage(MSG_OBJECT_CLASS_REQUIRED));
            }
            LOG.ok("Object class: {0}", objectClass.getObjectClassValue());

            final Map<String, Object> arguments = buildArguments();

            arguments.put("action", "AUTHENTICATE");
            arguments.put("log", LOG);
            arguments.put("objectClass", objectClass.getObjectClassValue());
            arguments.put("username", username);

            final String[] clearPwd = new String[0];
            password.access(new GuardedString.Accessor() {

                @Override
                public void access(char[] clearChars) {
                    clearPwd[0] = new String(clearChars);
                }
            });
            arguments.put("password", clearPwd[0]);
            arguments.put("options", options.getOptions());

            try {
                Object uid = authenticateExecutor.execute(arguments);
                if (uid instanceof String) {
                    LOG.ok("{0} authenticated", uid);
                    return new Uid((String) uid);
                }
            } catch (Exception e) {
                throw new ConnectorException("Authenticate script error", e);
            }
            throw new ConnectorException("Authenticate script didn't return with the __UID__ value");
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public Uid resolveUsername(
            final ObjectClass objectClass,
            final String username,
            final OperationOptions options) {

        if (config.isReloadScriptOnExecution()) {
            resolveUsernameExecutor = getScriptExecutor(
                    config.getResolveUsernameScript(), config.getResolveUsernameScriptFileName());
            LOG.ok("ResolveUsername script loaded");
        }
        if (resolveUsernameExecutor != null) {
            if (objectClass == null) {
                throw new IllegalArgumentException(config.getMessage(MSG_OBJECT_CLASS_REQUIRED));
            }
            LOG.ok("Object class: {0}", objectClass.getObjectClassValue());

            final Map<String, Object> arguments = buildArguments();

            arguments.put("action", "RESOLVE USERNAME");
            arguments.put("log", LOG);
            arguments.put("objectClass", objectClass.getObjectClassValue());
            arguments.put("username", username);
            arguments.put("options", options.getOptions());

            try {
                Object uid = resolveUsernameExecutor.execute(arguments);
                if (uid instanceof String) {
                    LOG.ok("{0} resolved", uid);
                    return new Uid((String) uid);
                }
            } catch (Exception e) {
                throw new ConnectorException("ResolveUsername script error", e);
            }
            throw new ConnectorException("ResolveUsername script didn't return with the __UID__ value");
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public Schema schema() {
        SchemaBuilder scmb = new SchemaBuilder(getClass());
        if (config.isReloadScriptOnExecution()) {
            schemaExecutor = getScriptExecutor("", config.getSchemaScriptFileName());
        }
        if (schemaExecutor != null) {
            final Map<String, Object> arguments = buildArguments();

            arguments.put("action", "SCHEMA");
            arguments.put("log", LOG);
            arguments.put("builder", scmb);
            try {
                schemaExecutor.execute(arguments);
            } catch (Exception e) {
                throw new ConnectorException("Schema script error", e);
            }
        } else {
            throw new UnsupportedOperationException("SCHEMA script executor is null. Problem loading Schema script");
        }
        schema = scmb.build();
        return schema;
    }

    @Override
    public void executeQuery(
            final ObjectClass objectClass,
            final Map<String, Object> query,
            final ResultsHandler handler,
            final OperationOptions options) {

        if (config.isReloadScriptOnExecution()) {
            searchExecutor = getScriptExecutor(config.getSearchScript(), config.getSearchScriptFileName());
            LOG.ok("Search script loaded");
        }
        if (searchExecutor != null) {
            if (objectClass == null) {
                throw new IllegalArgumentException(config.getMessage(MSG_OBJECT_CLASS_REQUIRED));
            }
            LOG.ok("ObjectClass: {0}", objectClass.getObjectClassValue());
            if (handler == null) {
                throw new IllegalArgumentException(config.getMessage(MSG_BLANK_RESULT_HANDLER));
            }

            final Map<String, Object> arguments = buildArguments();

            arguments.put("objectClass", objectClass.getObjectClassValue());
            arguments.put("action", "SEARCH");
            arguments.put("log", LOG);
            arguments.put("options", options.getOptions());
            arguments.put("query", query);
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> results = (List<Map<String, Object>>) searchExecutor.execute(arguments);
                LOG.ok("Search ok");
                processResults(objectClass, results, handler);
            } catch (Exception e) {
                throw new ConnectorException("Search script error", e);
            }
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public void sync(
            final ObjectClass objectClass,
            final SyncToken token,
            final SyncResultsHandler handler,
            final OperationOptions options) {

        if (config.isReloadScriptOnExecution()) {
            syncExecutor = getScriptExecutor(config.getSyncScript(), config.getSyncScriptFileName());
            LOG.ok("Sync script loaded");
        }
        if (syncExecutor != null) {
            if (objectClass == null) {
                throw new IllegalArgumentException(config.getMessage(MSG_OBJECT_CLASS_REQUIRED));
            }
            LOG.ok("ObjectClass: {0}", objectClass.getObjectClassValue());
            if (handler == null) {
                throw new IllegalArgumentException(config.getMessage(MSG_BLANK_RESULT_HANDLER));
            }

            final Map<String, Object> arguments = buildArguments();

            arguments.put("objectClass", objectClass.getObjectClassValue());
            arguments.put("action", "SYNC");
            arguments.put("log", LOG);
            arguments.put("options", options.getOptions());
            arguments.put("token", token != null ? token.getValue() : null);
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> results = (List<Map<String, Object>>) syncExecutor.execute(arguments);
                LOG.ok("Sync ok");
                processDeltas(objectClass, results, handler);
            } catch (Exception e) {
                throw new ConnectorException("Sync script error", e);
            }
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public SyncToken getLatestSyncToken(final ObjectClass objectClass) {
        if (config.isReloadScriptOnExecution()) {
            syncExecutor = getScriptExecutor(config.getSyncScript(), config.getSyncScriptFileName());
            LOG.ok("Sync script loaded");
        }
        if (syncExecutor != null) {
            SyncToken st = null;
            if (objectClass == null) {
                throw new IllegalArgumentException(config.getMessage(MSG_OBJECT_CLASS_REQUIRED));
            }
            LOG.ok("ObjectClass: {0}", objectClass.getObjectClassValue());

            final Map<String, Object> arguments = buildArguments();

            arguments.put("objectClass", objectClass.getObjectClassValue());
            arguments.put("action", "GET_LATEST_SYNC_TOKEN");
            arguments.put("log", LOG);
            try {
                // We expect the script to return a value (or null) that makes the sync token
                // !! result has to be one of the framework known types...
                Object result = syncExecutor.execute(arguments);
                LOG.ok("GetLatestSyncToken ok");
                FrameworkUtil.checkAttributeType(result.getClass());
                st = new SyncToken(result);
            } catch (java.lang.IllegalArgumentException ae) {
                throw new ConnectorException("Unknown Token type", ae);
            } catch (Exception e) {
                throw new ConnectorException("Sync (GetLatestSyncToken) script error", e);
            }
            return st;
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public void test() {
        config.validate();

        if (config.isReloadScriptOnExecution()) {
            testExecutor = getScriptExecutor(config.getTestScript(), config.getTestScriptFileName());
            LOG.ok("Test script loaded");
        }
        if (testExecutor != null) {
            final Map<String, Object> arguments = buildArguments();

            arguments.put("action", "TEST");
            arguments.put("log", LOG);
            try {
                testExecutor.execute(arguments);
                LOG.ok("Test ok");
            } catch (Exception e) {
                throw new ConnectorException("Test script error", e);
            }
        }
    }

    private void processResults(ObjectClass objClass, List<Map<String, Object>> results, ResultsHandler handler) {
        // Let's iterate over the results:
        String pagedResultCookie = null;
        for (Map<String, Object> result : results) {
            // special handling of paged result cookie
            if (result.size() == 1) {
                Map.Entry<String, Object> entry = result.entrySet().iterator().next();
                if (OperationOptions.OP_PAGED_RESULTS_COOKIE.equalsIgnoreCase(entry.getKey())
                        && entry.getValue() != null) {

                    pagedResultCookie = entry.getValue().toString();
                }
            } else {
                ConnectorObjectBuilder cobld = new ConnectorObjectBuilder();
                for (Map.Entry<String, Object> entry : result.entrySet()) {
                    final String attrName = entry.getKey();
                    final Object attrValue = entry.getValue();
                    // Special first
                    if (Uid.NAME.equalsIgnoreCase(attrName)) {
                        if (attrValue == null) {
                            throw new IllegalArgumentException("Uid cannot be null");
                        }
                        cobld.setUid(attrValue.toString());
                    } else if (Name.NAME.equalsIgnoreCase(attrName)) {
                        if (attrValue == null) {
                            throw new IllegalArgumentException("Name cannot be null");
                        }
                        cobld.setName(attrValue.toString());
                    } else if (attrName.equalsIgnoreCase("password")) {
                        // is there a chance we fetch password from search?
                    } else if (attrValue instanceof Collection) {
                        cobld.addAttribute(AttributeBuilder.build(attrName, (Collection<?>) attrValue));
                    } else if (attrValue != null) {
                        cobld.addAttribute(AttributeBuilder.build(attrName, attrValue));
                    } else {
                        cobld.addAttribute(AttributeBuilder.build(attrName));
                    }
                }
                cobld.setObjectClass(objClass);
                handler.handle(cobld.build());
                LOG.ok("ConnectorObject is built");
            }
        }

        if (handler instanceof SearchResultsHandler) {
            SearchResultsHandler.class.cast(handler).handleResult(new SearchResult(pagedResultCookie, -1));
        } else {
            LOG.warn("Not expected, but found {0}: {1}",
                    OperationOptions.OP_PAGED_RESULTS_COOKIE, pagedResultCookie);
        }
    }

    @SuppressWarnings("unchecked")
    private void processDeltas(ObjectClass objClass, List<Map<String, Object>> results, SyncResultsHandler handler) {
        // Let's iterate over the results:
        for (Map<String, Object> result : results) {
            // The Map should look like:
            // token: <Object> token
            // operation: <String> CREATE_OR_UPDATE|DELETE (defaults to CREATE_OR_UPDATE)
            // uid: <String> uid
            // previousUid: <String> prevuid (This is for rename ops)
            // password: <String> password
            // attributes: <Map> of attributes <String>name/<List>values
            SyncDeltaBuilder syncbld = new SyncDeltaBuilder();
            String uid = (String) result.get("uid");
            if (uid != null && !uid.isEmpty()) {
                syncbld.setUid(new Uid(uid));
                Object token = result.get("token");
                // Null token, set some acceptable value
                if (token == null) {
                    LOG.ok("token value is null, replacing to 0L");
                    token = 0L;
                }
                syncbld.setToken(new SyncToken(token));

                // Start building the connector object
                ConnectorObjectBuilder cobld = new ConnectorObjectBuilder();
                cobld.setName(uid);
                cobld.setUid(uid);
                cobld.setObjectClass(objClass);

                // operation
                // We assume that if DELETE, then we don't need to care about the rest
                String op = (String) result.get("operation");
                if (op != null && op.equalsIgnoreCase("DELETE")) {
                    syncbld.setDeltaType(SyncDeltaType.DELETE);

                } else {
                    // we assume this is CREATE_OR_UPDATE
                    syncbld.setDeltaType(SyncDeltaType.CREATE_OR_UPDATE);

                    // previous UID
                    String prevUid = (String) result.get("previousUid");
                    if (prevUid != null && !prevUid.isEmpty()) {
                        syncbld.setPreviousUid(new Uid(prevUid));
                    }

                    // password? is password valid if empty string? let's assume yes...
                    if (result.get("password") != null) {
                        cobld.addAttribute(AttributeBuilder.buildCurrentPassword(((String) result.get("password")).
                                toCharArray()));
                    }

                    // Remaining attributes
                    for (Map.Entry<String, Object> attr : ((Map<String, Object>) result.get("attributes")).entrySet()) {
                        final String attrName = attr.getKey();
                        final Object attrValue = attr.getValue();
                        if (attrValue instanceof Collection) {
                            cobld.addAttribute(AttributeBuilder.build(attrName, (Collection<?>) attrValue));
                        } else if (attrValue != null) {
                            cobld.addAttribute(AttributeBuilder.build(attrName, attrValue));
                        } else {
                            cobld.addAttribute(AttributeBuilder.build(attrName));
                        }
                    }
                }
                syncbld.setObject(cobld.build());
                if (!handler.handle(syncbld.build())) {
                    LOG.ok("Stop processing of the sync result set");
                    break;
                }
            } else {
                // we have a null uid... mmmm....
            }
        }
    }

}
