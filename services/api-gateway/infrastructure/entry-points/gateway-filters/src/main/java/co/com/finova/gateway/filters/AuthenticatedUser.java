package co.com.finova.gateway.filters;

public final class AuthenticatedUser {

    // Request attribute with the token's sub, set by the authentication filter (spec.md §3.1 step 1).
    public static final String ATTRIBUTE = "finova.userId";

    private AuthenticatedUser() {
    }
}
