package io.jobplatform.identity;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Operators are deployment-managed, never self-selected by a registration request. The allowlist
 * is intentionally an environment setting so a demo can have an operator without a public role
 * management API.
 */
@Component
public class OperatorRolePolicy {
    private final Set<String> operatorEmails;

    public OperatorRolePolicy(@Value("${app.security.operator-emails:}") String configuredEmails) {
        this.operatorEmails = Arrays.stream(configuredEmails.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public String roleFor(String email) {
        return operatorEmails.contains(email.toLowerCase(Locale.ROOT)) ? "OPERATOR" : "USER";
    }
}
