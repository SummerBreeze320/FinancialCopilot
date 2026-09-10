package com.financial.copilot.controller.user;

import com.financial.copilot.agent.core.security.UserPrincipal;
import com.financial.copilot.agent.core.user.dto.*;
import com.financial.copilot.agent.core.user.service.UserService;
import com.financial.copilot.common.result.ApiResult;
import com.financial.copilot.controller.admin.UserAdminController;
import com.financial.copilot.domain.user.entity.*;
import com.financial.copilot.domain.user.enums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * <h1>用户中心、认证与管理控制台 Web 端点单元测试</h1>
 *
 * @author FinancialCopilot
 */
class UserControllerTest {

    private UserService mockUserService;
    private AuthController authController;
    private UserController userController;
    private UserAdminController adminController;

    @BeforeEach
    void setUp() {
        mockUserService = Mockito.mock(UserService.class);
        authController = new AuthController(mockUserService);
        userController = new UserController(mockUserService);
        adminController = new UserAdminController(mockUserService);
    }

    @Test
    @DisplayName("验证用户注册与登录开放接口")
    void testAuthEndpoints() {
        AuthResponse dummyAuth = AuthResponse.builder()
                .userId(1001L)
                .username("test_user")
                .accessToken("mock.access.token")
                .refreshToken("mock.refresh.token")
                .roles(List.of("ROLE_USER"))
                .build();

        when(mockUserService.register(any())).thenReturn(dummyAuth);
        when(mockUserService.login(any())).thenReturn(dummyAuth);
        when(mockUserService.refreshToken("mock.refresh.token")).thenReturn(dummyAuth);

        // 1. 注册
        UserRegisterRequest regReq = UserRegisterRequest.builder()
                .username("test_user")
                .password("Password123")
                .build();
        ApiResult<AuthResponse> regResult = authController.register(regReq);
        assertEquals(200, regResult.getCode());
        assertEquals(1001L, regResult.getData().getUserId());

        // 2. 登录
        UserLoginRequest loginReq = UserLoginRequest.builder()
                .username("test_user")
                .password("Password123")
                .build();
        ApiResult<AuthResponse> loginResult = authController.login(loginReq);
        assertEquals(200, loginResult.getCode());

        // 3. 换票
        ApiResult<AuthResponse> refreshResult = authController.refreshToken("mock.refresh.token", null);
        assertEquals(200, refreshResult.getCode());
        assertEquals("mock.access.token", refreshResult.getData().getAccessToken());
    }

    @Test
    @DisplayName("验证在已认证上下文中查询个人资料与投资偏好画像")
    void testUserProfileAndInvestmentProfileWithContext() {
        Long userId = 2002L;
        UserPrincipal principal = UserPrincipal.builder()
                .userId(userId)
                .username("quant_user")
                .roles(List.of("ROLE_USER"))
                .build();

        UserProfile profile = UserProfile.builder()
                .userId(userId)
                .nickname("量化极客")
                .build();
        when(mockUserService.getProfile(userId)).thenReturn(profile);

        UserInvestmentProfile investProfile = UserInvestmentProfile.builder()
                .userId(userId)
                .riskToleranceLevel(RiskToleranceLevel.C4)
                .investmentStyle(InvestmentStyle.GROWTH)
                .build();
        when(mockUserService.getInvestmentProfile(userId)).thenReturn(investProfile);

        // 模拟已登录的响应式上下文
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, "token", principal.getAuthorities());

        // 查询资料
        ApiResult<UserProfile> profileResult = userController.getProfile()
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();
        assertNotNull(profileResult);
        assertEquals(200, profileResult.getCode());
        assertEquals("量化极客", profileResult.getData().getNickname());

        // 查询投资画像
        ApiResult<UserInvestmentProfile> investResult = userController.getInvestmentProfile()
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();
        assertNotNull(investResult);
        assertEquals(200, investResult.getCode());
        assertEquals(RiskToleranceLevel.C4, investResult.getData().getRiskToleranceLevel());
    }

    @Test
    @DisplayName("验证管理员用户列表与实名审批端点")
    void testAdminEndpoints() {
        User user1 = User.builder().id(1L).username("admin").build();
        User user2 = User.builder().id(2L).username("user2").build();
        when(mockUserService.listUsers(1, 20)).thenReturn(List.of(user1, user2));

        UserIdentity pending = UserIdentity.builder()
                .userId(2L)
                .realName("李四")
                .verifyStatus(KycStatus.PENDING)
                .build();
        when(mockUserService.listPendingIdentities(1, 20)).thenReturn(List.of(pending));

        UserIdentity approved = UserIdentity.builder()
                .userId(2L)
                .realName("李四")
                .verifyStatus(KycStatus.VERIFIED)
                .build();
        when(mockUserService.reviewIdentity(any())).thenReturn(approved);

        // 1. 查询用户
        ApiResult<List<User>> userListRes = adminController.listUsers(1, 20);
        assertEquals(200, userListRes.getCode());
        assertEquals(2, userListRes.getData().size());

        // 2. 待审核实名
        ApiResult<List<UserIdentity>> pendingRes = adminController.listPendingIdentities(1, 20);
        assertEquals(200, pendingRes.getCode());
        assertEquals(1, pendingRes.getData().size());

        // 3. 执行审批
        IdentityReviewRequest reviewReq = IdentityReviewRequest.builder()
                .userId(2L)
                .approved(true)
                .build();
        ApiResult<UserIdentity> reviewRes = adminController.reviewIdentity(reviewReq);
        assertEquals(200, reviewRes.getCode());
        assertEquals(KycStatus.VERIFIED, reviewRes.getData().getVerifyStatus());
    }
}
