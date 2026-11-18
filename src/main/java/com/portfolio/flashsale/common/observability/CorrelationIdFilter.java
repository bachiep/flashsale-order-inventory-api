package com.portfolio.flashsale.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID_KEY = "requestId";
    public static final String REQUEST_ID_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".REQUEST_ID";

    private static final Pattern SAFE_REQUEST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = resolveOrGenerateRequestId(request);
        MDC.put(MDC_REQUEST_ID_KEY, requestId);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID_KEY);
        }
    }

    public static String resolveOrGenerateRequestId(HttpServletRequest request) {
        String existing = currentRequestId(request);
        if (existing != null) {
            return existing;
        }
        return UUID.randomUUID().toString();
    }

    public static String currentRequestId(HttpServletRequest request) {
        String mdcRequestId = MDC.get(MDC_REQUEST_ID_KEY);
        if (StringUtils.hasText(mdcRequestId)) {
            return mdcRequestId;
        }
        if (request != null) {
            Object attribute = request.getAttribute(REQUEST_ID_ATTRIBUTE);
            if (attribute instanceof String attrId && StringUtils.hasText(attrId)) {
                return attrId;
            }
            String sanitizedHeader = sanitizeRequestId(request.getHeader(REQUEST_ID_HEADER));
            if (sanitizedHeader != null) {
                return sanitizedHeader;
            }
        }
        return null;
    }

    static String sanitizeRequestId(String rawHeader) {
        if (!StringUtils.hasText(rawHeader)) {
            return null;
        }
        String trimmed = rawHeader.trim();
        return SAFE_REQUEST_ID_PATTERN.matcher(trimmed).matches() ? trimmed : null;
    }
}
