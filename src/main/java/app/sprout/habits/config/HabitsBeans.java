package app.sprout.habits.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class HabitsBeans {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
