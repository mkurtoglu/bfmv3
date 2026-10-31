package com.bisoft.bfm.watcher.api;

import com.bisoft.bfm.watcher.service.ClusterHub;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * Gives an API handler the cluster its URL names ({@code /api/v1/clusters/{cluster}/...}), or the only cluster for
 * the short form ({@code /api/v1/...}); unknown names answer 404, the short form on a multi-cluster watcher 409.
 */
public class ClusterArgumentResolver implements HandlerMethodArgumentResolver {

    private final ClusterHub hub;

    public ClusterArgumentResolver(ClusterHub hub) {
        this.hub = hub;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == ClusterHub.ClusterContext.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                  WebDataBinderFactory binders) {
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) request.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return hub.require(vars == null ? null : vars.get("cluster"));
    }
}
