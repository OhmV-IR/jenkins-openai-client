package io.ohmvir.plugins.openaiclient;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import org.junit.jupiter.api.Test;

public class OpenAIClientTest {
    @Test
    public void testClientSettingsDefaults() throws Descriptor.FormException {
        OpenAIClientSettings settings = new OpenAIClientSettings();
        assertEquals(4096L, settings.getDefaultMaxTokens());
        assertEquals(120L, settings.getTimeoutSeconds());
        assertEquals("OpenAI Client Settings", settings.getDisplayName());
    }

    @Test
    public void testClientSettingsCustom() throws Descriptor.FormException {
        OpenAIClientSettings settings = new OpenAIClientSettings(60L, 8192L);
        assertEquals(8192L, settings.getDefaultMaxTokens());
        assertEquals(60L, settings.getTimeoutSeconds());
        assertThrows(Descriptor.FormException.class, () -> new OpenAIClientSettings(60L, 0L));
    }

    @Test
    public void testModelSettingsSanitizeUrl() {
        assertEquals("https://api.openai.com/v1", OpenAIModelSettings.sanitizeUrl(null));
        assertEquals("https://api.openai.com/v1", OpenAIModelSettings.sanitizeUrl(""));
        assertEquals("https://api.openai.com/v1", OpenAIModelSettings.sanitizeUrl("https://api.openai.com/v1/"));
        assertEquals(
                "https://custom.gateway.com/v1", OpenAIModelSettings.sanitizeUrl("https://custom.gateway.com/v1///"));
    }

    @Test
    public void testModelSettingsValidation() {
        OpenAIModelSettings.DescriptorImpl desc = new OpenAIModelSettings.DescriptorImpl();
        assertEquals(FormValidation.Kind.OK, desc.doCheckApiBaseUrl("").kind);
        assertEquals(FormValidation.Kind.OK, desc.doCheckApiBaseUrl("https://api.openai.com/v1").kind);
        assertEquals(FormValidation.Kind.ERROR, desc.doCheckApiBaseUrl("invalid-url-without-host").kind);
        assertEquals(FormValidation.Kind.ERROR, desc.doCheckApiBaseUrl("ftp://api.openai.com").kind);
        assertEquals(FormValidation.Kind.ERROR, desc.doCheckApiKeyCredentialsId("").kind);
        assertEquals(FormValidation.Kind.ERROR, desc.doCheckApiKeyCredentialsId(null).kind);
        assertEquals(FormValidation.Kind.OK, desc.doCheckApiKeyCredentialsId("dummy-id-outside-jenkins").kind);
        assertEquals(FormValidation.Kind.ERROR, desc.doCheckModelName("").kind);
        assertEquals(FormValidation.Kind.ERROR, desc.doCheckModelName(null).kind);
        assertEquals(FormValidation.Kind.OK, desc.doCheckModelName("gpt-4o-mini").kind);
    }

    @Test
    public void testModelSettingsConstructorValidation() {
        assertThrows(
                Descriptor.FormException.class,
                () -> new OpenAIModelSettings("", "valid-id", "https://api.openai.com/v1"));
        assertThrows(
                Descriptor.FormException.class,
                () -> new OpenAIModelSettings("gpt-4o-mini", "", "https://api.openai.com/v1"));
        assertThrows(
                Descriptor.FormException.class,
                () -> new OpenAIModelSettings("gpt-4o-mini", null, "https://api.openai.com/v1"));
        assertThrows(
                Descriptor.FormException.class,
                () -> new OpenAIModelSettings("gpt-4o-mini", "valid-id", "ftp://api.openai.com"));
    }

    @Test
    public void testModelSettingsDropdownNoFallbackWhenNoCredentials() {
        OpenAIModelSettings.DescriptorImpl desc = new OpenAIModelSettings.DescriptorImpl();
        ListBoxModel items = desc.doFillModelNameItems("", "");
        assertEquals(1, items.size());
        assertTrue(items.get(0).name.contains("Please select a valid API Key credential"));
    }

    @Test
    public void testModelDataRetrieverThrowsWhenCredentialsUnresolvable() {
        OpenAIModelDataRetriever retriever = new OpenAIModelDataRetriever();
        OpenAIModelSettings config;
        try {
            config = new OpenAIModelSettings();
        } catch (Descriptor.FormException e) {
            fail("Default config should not throw: " + e.getMessage());
            return;
        }
        assertThrows(IOException.class, () -> retriever.retrieveFromConfiguration(config));
    }
}
