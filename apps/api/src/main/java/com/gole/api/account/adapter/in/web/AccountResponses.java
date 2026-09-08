package com.gole.api.account.adapter.in.web;

/**
 * 계정 관련 응답 DTO 모음.
 */
public final class AccountResponses {

    private AccountResponses() {}

    public record RegisterResponse(String accountId) {}

    public record SignInResponse(String accountId, String sessionToken, String role, boolean onboardingRequired) {}

    public record RefreshSessionResponse(String accountId, String sessionToken, String role, boolean rotated) {}

    public record MeResponse(
            String accountId, String email, String role, boolean onboardingRequired, String nickname) {}
}
