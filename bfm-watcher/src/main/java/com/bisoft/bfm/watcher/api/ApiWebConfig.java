package com.bisoft.bfm.watcher.api;

import com.bisoft.bfm.core.TimeSource;
import com.bisoft.bfm.core.notify.SecretBox;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.service.CaBootstrap;
import com.bisoft.bfm.watcher.service.ClusterHub;
import com.bisoft.bfm.watcher.service.ConsoleTls;
import com.bisoft.bfm.watcher.service.EnrollmentService;
import com.bisoft.bfm.watcher.service.FleetService;
import com.bisoft.bfm.watcher.service.WatcherCa;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
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

    @Bean
    public WatcherCa watcherCa(SecretBox secrets, BfmProperties props) {
        return new WatcherCa(hub.store(), secrets, props.dataDir().resolve("tmp"));
    }

    @Bean
    public EnrollmentService enrollmentService(WatcherCa ca, TimeSource time) {
        return new EnrollmentService(hub, ca, time);
    }

    @Bean(destroyMethod = "close")
    public CaBootstrap caBootstrap(WatcherCa ca, BfmProperties props) {
        CaBootstrap b = new CaBootstrap(ca, hub.store(), props.dataDir());
        b.start();
        return b;
    }

    @Bean
    public ConsoleTls consoleTls(Environment env) {
        return new ConsoleTls(env);
    }
}
