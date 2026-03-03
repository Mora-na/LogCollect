package com.logcollect.benchmark.support;

/**
 * Ensures benchmark processes use the intended Logback configuration file.
 */
public final class BenchmarkLoggingBootstrap {

    private static final String LOGBACK_CONFIG_PROPERTY = "logback.configurationFile";
    private static final String SPRING_PROFILES_PROPERTY = "spring.profiles.active";
    private static final String WITH_FILE_OUTPUT_PROFILE = "with-file-output";
    private static final String DEFAULT_CONFIG_RESOURCE = "logback-benchmark.xml";
    private static final String WITH_FILE_CONFIG_RESOURCE = "logback-benchmark-with-file-output.xml";

    private BenchmarkLoggingBootstrap() {
    }

    public static void ensureLogbackConfig() {
        String configured = System.getProperty(LOGBACK_CONFIG_PROPERTY);
        if (configured != null && !configured.trim().isEmpty()) {
            return;
        }

        String activeProfiles = System.getProperty(SPRING_PROFILES_PROPERTY, "");
        String configResource = containsProfile(activeProfiles, WITH_FILE_OUTPUT_PROFILE)
                ? WITH_FILE_CONFIG_RESOURCE
                : DEFAULT_CONFIG_RESOURCE;

        if (BenchmarkLoggingBootstrap.class.getClassLoader().getResource(configResource) != null) {
            System.setProperty(LOGBACK_CONFIG_PROPERTY, configResource);
        }
    }

    private static boolean containsProfile(String activeProfiles, String expectedProfile) {
        if (activeProfiles == null || activeProfiles.trim().isEmpty()) {
            return false;
        }
        String[] profiles = activeProfiles.split(",");
        for (String profile : profiles) {
            if (expectedProfile.equals(profile.trim())) {
                return true;
            }
        }
        return false;
    }
}
