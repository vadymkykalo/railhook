package com.webhook.platform.api.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(1)
@Slf4j
public class CorrelationIdFilter implements Filter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String CORRELATION_ID_KEY = "correlationId";
    
    private static final int MAX_CORRELATION_ID_LENGTH = 128;
    private static final Pattern VALID_CORRELATION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        String correlationId = httpRequest.getHeader(CORRELATION_ID_HEADER);
        
        if (correlationId != null && !correlationId.isEmpty()) {
            if (!isValidCorrelationId(correlationId)) {
                log.warn("Invalid X-Correlation-ID received (length={}, valid=false), generating new one", 
                        correlationId.length());
                correlationId = UUID.randomUUID().toString();
            }
        } else {
            correlationId = UUID.randomUUID().toString();
        }
        
        MDC.put(CORRELATION_ID_KEY, correlationId);
        httpResponse.setHeader(CORRELATION_ID_HEADER, correlationId);
        
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CORRELATION_ID_KEY);
        }
    }

    public static String getCurrentCorrelationId() {
        return MDC.get(CORRELATION_ID_KEY);
    }
    
    private static boolean isValidCorrelationId(String correlationId) {
        if (correlationId.length() > MAX_CORRELATION_ID_LENGTH) {
            return false;
        }
        return VALID_CORRELATION_ID_PATTERN.matcher(correlationId).matches();
    }
}
