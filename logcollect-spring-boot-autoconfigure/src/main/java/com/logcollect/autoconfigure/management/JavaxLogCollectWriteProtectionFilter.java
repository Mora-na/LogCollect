package com.logcollect.autoconfigure.management;

import com.logcollect.core.internal.LogCollectInternalLogger;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;

/**
 * 无 Spring Security 时阻断管理端写操作。
 */
public class JavaxLogCollectWriteProtectionFilter implements Filter {
    private final LogCollectManagementAuditLogger auditLogger;

    public JavaxLogCollectWriteProtectionFilter(LogCollectManagementAuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    @Override
    public void init(FilterConfig filterConfig) {
        // no-op
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;
        if (!isProtectedWriteRequest(req)) {
            chain.doFilter(request, response);
            return;
        }

        String action = req.getMethod() + " " + req.getRequestURI();
        LogCollectInternalLogger.warn("Rejected management write request without Spring Security: {}", action);
        if (auditLogger != null) {
            Principal principal = req.getUserPrincipal();
            auditLogger.audit("write_protection",
                    principal == null ? null : principal.getName(),
                    req.getRemoteAddr(),
                    false,
                    action);
        }
        resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"error\":\"Forbidden: management write operations require Spring Security\"}");
    }

    @Override
    public void destroy() {
        // no-op
    }

    private boolean isProtectedWriteRequest(HttpServletRequest req) {
        String method = req.getMethod();
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method)) {
            return false;
        }
        String uri = req.getRequestURI();
        String contextPath = req.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri != null && uri.startsWith("/actuator/logcollect/");
    }
}
