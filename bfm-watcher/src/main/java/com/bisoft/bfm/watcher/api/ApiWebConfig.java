package com.bisoft.bfm.watcher.api;

import com.bisoft.bfm.watcher.service.ClusterHub;
import com.bisoft.bfm.watcher.service.FleetService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Lets API handlers take the cluster their URL names as a parameter. */
@Configuration
public class ApiWebConfig implements WebMvcConfigurer {

    private final ClusterHub hub;

    public ApiWebConfig(ClusterHub hub) {
        this.hub = hub;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new ClusterArgumentResolver(hub));
    }

    @Bean
    public FleetService fleetService() {
        return new FleetService(hub);
    }
}
