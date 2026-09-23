package io.ohmvir.plugins.openaiclient;

import hudson.Extension;
import io.ohmvir.plugins.jenkinsaisynapse.configuration.client.ModelClientConfiguration;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.DataBoundConstructor;

@Extension
public class OpenAIClientSettings extends ModelClientConfiguration {
    public static final long DEFAULT_MAX_TOKENS = 4096L;

    private @Getter final long defaultMaxTokens;

    public OpenAIClientSettings() throws FormException {
        super(120L);
        this.defaultMaxTokens = DEFAULT_MAX_TOKENS;
    }

    @DataBoundConstructor
    public OpenAIClientSettings(long timeoutSeconds, long defaultMaxTokens) throws FormException {
        super(timeoutSeconds);
        if (defaultMaxTokens <= 0) {
            throw new FormException("Default max tokens must be greater than zero", "defaultMaxTokens");
        }
        this.defaultMaxTokens = defaultMaxTokens;
    }

    @Override
    public @NonNull String getDisplayName() {
        return "OpenAI Client Settings";
    }
}
