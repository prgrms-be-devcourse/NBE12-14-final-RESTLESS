package com.restless.auth.infrastructure;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.member.application.MemberService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 탈퇴한 회원의 남은 Access Token으로 오는 쓰기 요청을 막는다. 조회와 공개 인증 API는 확인하지 않는다.
 *
 * <p>인가 뒤에 실행되므로 401, 403이 먼저다. 확인한 뒤에 탈퇴하는 동시 요청까지 막지는 않는다.
 * 빈으로 등록하면 서블릿 필터로 한 번 더 등록되므로 WithdrawnMemberSecurityConfig가 직접 만든다.
 */
@RequiredArgsConstructor
class WithdrawnMemberFilter extends OncePerRequestFilter {

    private static final Set<String> WRITE_METHODS = Set.of(HttpMethod.POST.name(), HttpMethod.PUT.name(),
            HttpMethod.PATCH.name(), HttpMethod.DELETE.name());

    private final MemberService memberService;
    private final HandlerExceptionResolver exceptionResolver;
    private final RequestMatcher publicPaths;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !WRITE_METHODS.contains(request.getMethod()) || publicPaths.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            rejectWithdrawn();
        } catch (RuntimeException e) {
            // 컨트롤러 밖이라 공통 예외 처리(GlobalExceptionHandler)에 직접 넘긴다. 회원 조회 실패도 통과시키지 않는다.
            if (exceptionResolver.resolveException(request, response, null, e) == null) {
                throw e;
            }
            return;
        }
        chain.doFilter(request, response);
    }

    private void rejectWithdrawn() {
        Long memberId = memberId();
        if (memberId != null && memberService.isWithdrawn(memberId)) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND, MemberService.WITHDRAWN_MESSAGE);
        }
    }

    /** JWT subject가 회원 ID가 아니면 null. 그 요청은 LoginMemberArgumentResolver가 401로 거절한다. */
    private static Long memberId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
