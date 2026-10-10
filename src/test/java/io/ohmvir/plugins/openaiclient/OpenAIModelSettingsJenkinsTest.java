package io.ohmvir.plugins.openaiclient;

import static io.ohmvir.plugins.openaiclient.MockOpenAIServer.model;
import static org.junit.jupiter.api.Assertions.*;

import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelCapability;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelData;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelThinkingLevel;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class OpenAIModelSettingsJenkinsTest {
    private MockOpenAIServer server;
    private OpenAIModelSettings.DescriptorImpl descriptor;

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        OpenAIClientStepTest.addApiKeyCredential();
        server = new MockOpenAIServer();
        descriptor = j.jenkins.getDescriptorByType(OpenAIModelSettings.DescriptorImpl.class);
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ)
                .everywhere()
                .to("reader"));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private ModelData retrieve(String modelName) throws Exception {
        server.enqueue(model(modelName));
        OpenAIModelSettings settings =
                new OpenAIModelSettings(modelName, OpenAIClientStepTest.CREDENTIALS_ID, server.baseUrl());
        ModelData data = new OpenAIModelDataRetriever().retrieveFromConfiguration(settings);
        assertEquals("/v1/models/" + modelName, server.lastRequest().path());
        return data;
    }

    @Test
    void chatModelsSupportSamplingButNotThinking() throws Exception {
        ModelData data = retrieve("gpt-4o-mini");

        assertTrue(data.hasCapability(ModelCapability.CUSTOM_TEMPERATURE));
        assertTrue(data.hasCapability(ModelCapability.CUSTOM_TOP_P));
        assertFalse(data.hasCapability(ModelCapability.THINKING));
        assertTrue(data.getSupportedThinkingLevels().isEmpty());
        assertEquals(2.0d, data.getMaxTemperature());
        assertFalse(
                data.hasCapability(ModelCapability.CUSTOM_STOP_SEQUENCES), "The Responses API has no stop parameter");
        assertTrue(data.hasCapability(ModelCapability.TOOLS));
        assertTrue(data.hasCapability(ModelCapability.TOKEN_USAGE_METRICS));
    }

    @Test
    void reasoningModelsSupportThinkingButNotSampling() throws Exception {
        ModelData data = retrieve("o4-mini");

        assertTrue(data.hasCapability(ModelCapability.THINKING));
        assertTrue(data.supportsThinkingLevel(ModelThinkingLevel.OFF));
        assertTrue(data.supportsThinkingLevel(ModelThinkingLevel.ON));
        assertTrue(data.supportsThinkingLevel(ModelThinkingLevel.MAX));
        assertFalse(data.hasCapability(ModelCapability.CUSTOM_TEMPERATURE));
        assertFalse(data.hasCapability(ModelCapability.CUSTOM_TOP_P));
        assertNull(data.getMaxTemperature());
        assertFalse(data.hasCapability(ModelCapability.CUSTOM_STOP_SEQUENCES));
    }

    @Test
    void unknownModelFailsRetrieval() throws Exception {
        server.enqueue(
                404, "{\"error\":{\"message\":\"The model does not exist\",\"type\":\"invalid_request_error\"}}");
        OpenAIModelSettings settings =
                new OpenAIModelSettings("missing-model", OpenAIClientStepTest.CREDENTIALS_ID, server.baseUrl());

        assertThrows(
                java.io.IOException.class, () -> new OpenAIModelDataRetriever().retrieveFromConfiguration(settings));
    }

    @Test
    void adminCanListModels() {
        server.enqueue("{\"object\":\"list\",\"data\":[" + model("gpt-4o") + "," + model("o3") + "]}");

        ListBoxModel items;
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            items = descriptor.doFillModelNameItems(OpenAIClientStepTest.CREDENTIALS_ID, server.baseUrl());
        }

        assertEquals(2, items.size());
        assertEquals("gpt-4o", items.get(0).value);
        assertEquals("o3", items.get(1).value);
        assertEquals("Bearer sk-test", server.lastRequest().authorization());
    }

    @Test
    void nonAdminCannotSendStoredApiKeyToArbitraryUrl() {
        ListBoxModel items;
        try (ACLContext ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
            items = descriptor.doFillModelNameItems(OpenAIClientStepTest.CREDENTIALS_ID, server.baseUrl());
        }

        assertTrue(items.isEmpty());
        assertTrue(server.requests().isEmpty(), "The stored API key must not be sent for a non-admin user");
    }

    @Test
    void credentialCheckOnlyRevealsCredentialsToAdmins() {
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckApiKeyCredentialsId("does-not-exist").kind);
            assertEquals(
                    FormValidation.Kind.OK,
                    descriptor.doCheckApiKeyCredentialsId(OpenAIClientStepTest.CREDENTIALS_ID).kind);
        }
        try (ACLContext ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
            assertEquals(FormValidation.Kind.OK, descriptor.doCheckApiKeyCredentialsId("does-not-exist").kind);
        }
    }
}
