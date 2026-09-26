package io.jobplatform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jobplatform.api.ApiError;
import io.jobplatform.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {
    private final ObjectMapper json;

    public ApiAccessDeniedHandler(ObjectMapper json) { this.json = json; }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), new ApiError("FORBIDDEN", "Permission denied.", CorrelationIdFilter.value(request)));
    }
}
