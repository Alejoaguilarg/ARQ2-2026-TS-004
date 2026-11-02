package co.com.finova.gateway.filters;

public final class AuthenticatedUser {

    // Request attribute with the token's sub, set by AuthenticationFilter (spec.md §3.1 step 1).
    public static final String ATTRIBUTE = "finova.userId";

    // Request attribute with the verified AccessToken, for checks that need more than the user id.
    public static final String TOKEN_ATTRIBUTE = "finova.accessToken";

    private AuthenticatedUser() {
    }
}
