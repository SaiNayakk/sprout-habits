package app.sprout.habits.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code sprout.habits} in habits.yml. */
@ConfigurationProperties("sprout.habits")
public record HabitsProperties(String serviceKey, int squadSize, String marketdataUrl, String omsUrl, String accountsUrl) {}
