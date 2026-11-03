package co.com.finova.audit.api.error;

/** The caller does not have the role AUDITOR (spec §3.10, §3.11, F5.7): 403 FORBIDDEN_ROLE. */
public class ForbiddenRoleException extends RuntimeException {

    public ForbiddenRoleException(String roles) {
        super("role AUDITOR required; X-User-Roles was " + (roles == null ? "missing" : "'" + roles + "'"));
    }
}
