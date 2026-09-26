package io.jobplatform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jobplatform.api.ApiError;
import io.jobplatform.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final ObjectMapper json;

    public ApiAuthenticationEntryPoint(ObjectMapper json) { this.json = json; }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), new ApiError("UNAUTHORIZED", "Authentication is required.", CorrelationIdFilter.value(request)));
    }
}
