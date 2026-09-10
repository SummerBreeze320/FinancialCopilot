package com.financial.copilot.agent.core.user;

import com.financial.copilot.agent.core.billing.WalletBillingService;
import com.financial.copilot.agent.core.security.JwtUtils;
import com.financial.copilot.agent.core.user.dto.*;
import com.financial.copilot.agent.core.user.service.DefaultUserService;
import com.financial.copilot.agent.core.user.service.UserService;
import com.financial.copilot.domain.user.entity.*;
import com.financial.copilot.domain.user.enums.*;
import com.financial.copilot.domain.user.port.UserPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * <h1>用户注册、认证、金融实名 (KYC) 与投资画像核心业务服务测试 (User Service Test)</h1>
 *
 * @author FinancialCopilot
 */
class UserServiceTest {

    private UserPort mockUserPort;
    private WalletBillingService mockBillingService;
    private JwtUtils jwtUtils;
    private UserService userService;

    @BeforeEach
    void setUp() {
        mockUserPort = Mockito.mock(UserPort.class);
        mockBillingService = Mockito.mock(WalletBillingService.class);
        // 使用合规 256 位测试密钥初始化 JwtUtils
        jwtUtils = new JwtUtils("SecretKeyForTestAntigravityFinancialCopilot2026SecureKey!", 7200000, 604800000);
        userService = new DefaultUserService(mockUserPort, mockBillingService, jwtUtils);
    }

    @Test
    @DisplayName("用户注册成功：自动分配 ROLE_USER、挂载初始画像、自动充值 10,000 点算力体验金并返回 JWT")
    void testRegisterSuccess() {
        UserRegisterRequest request = UserRegisterRequest.builder()
                .username("test_investor")
                .password("Password123!")
                .mobile("13800138000")
                .email("investor@copilot.com")
                .nickname("价值投资小白")
                .build();

        when(mockUserPort.findByUsername("test_investor")).thenReturn(Optional.empty());

        Role defaultRole = Role.builder()
                .id(3L)
                .roleCode("ROLE_USER")
                .roleName("普通注册用户")
                .build();

        // 模拟保存并回填 ID
        when(mockUserPort.saveUser(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(1001L);
            u.setRoles(List.of(defaultRole));
            return u;
        });
        when(mockUserPort.findById(1001L)).thenAnswer(inv -> {
            User u = User.builder()
                    .id(1001L)
                    .username("test_investor")
                    .status(UserStatus.ACTIVE)
                    .roles(List.of(defaultRole))
                    .build();
            return Optional.of(u);
        });

        AuthResponse authResponse = userService.register(request);

        assertNotNull(authResponse);
        assertEquals("test_investor", authResponse.getUsername());
        assertEquals(1001L, authResponse.getUserId());
        assertNotNull(authResponse.getAccessToken());
        assertNotNull(authResponse.getRefreshToken());
        assertTrue(authResponse.getRoles().contains("ROLE_USER"));

        // 验证自动调用钱包充值赠送 10,000 点
        verify(mockBillingService, times(1)).grantInitialTrialPoints(eq(1001L), eq(10000L));

        // 验证初始化资料与投资画像已持久化及角色分配
        verify(mockUserPort, times(1)).saveProfile(any(UserProfile.class));
        verify(mockUserPort, times(1)).saveInvestmentProfile(any(UserInvestmentProfile.class));
        verify(mockUserPort, times(1)).assignRole(eq(1001L), eq("ROLE_USER"));
    }

    @Test
    @DisplayName("用户注册校验：用户名重复时抛出异常")
    void testRegisterDuplicateUsernameThrows() {
        UserRegisterRequest request = UserRegisterRequest.builder()
                .username("existing_user")
                .password("Password123!")
                .build();

        when(mockUserPort.findByUsername("existing_user")).thenReturn(Optional.of(User.builder().id(99L).build()));

        assertThrows(IllegalArgumentException.class, () -> userService.register(request));
        verify(mockBillingService, never()).grantInitialTrialPoints(anyLong(), anyLong());
    }

    @Test
    @DisplayName("用户登录认证：账号密码正确签发有效 JWT 双令牌")
    void testLoginSuccess() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String hash = encoder.encode("ValidPass888");

        Role role = Role.builder().roleCode("ROLE_ANALYST").roleName("投资分析师").build();
        Permission perm = Permission.builder().permCode("research:create").build();

        User user = User.builder()
                .id(2002L)
                .username("analyst_wang")
                .passwordHash(hash)
                .status(UserStatus.ACTIVE)
                .roles(List.of(role))
                .permissions(List.of(perm))
                .build();

        when(mockUserPort.findByUsername("analyst_wang")).thenReturn(Optional.of(user));

        UserLoginRequest request = UserLoginRequest.builder()
                .username("analyst_wang")
                .password("ValidPass888")
                .build();

        AuthResponse response = userService.login(request);

        assertNotNull(response);
        assertEquals(2002L, response.getUserId());
        assertEquals("analyst_wang", response.getUsername());
        assertTrue(jwtUtils.validateToken(response.getAccessToken()));
        assertEquals(2002L, jwtUtils.extractUserId(response.getAccessToken()));
        assertTrue(jwtUtils.extractRoles(response.getAccessToken()).contains("ROLE_ANALYST"));
    }

    @Test
    @DisplayName("用户登录认证：密码错误抛出异常")
    void testLoginInvalidPasswordThrows() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String hash = encoder.encode("CorrectPassword");

        User user = User.builder()
                .id(2002L)
                .username("analyst_wang")
                .passwordHash(hash)
                .status(UserStatus.ACTIVE)
                .build();

        when(mockUserPort.findByUsername("analyst_wang")).thenReturn(Optional.of(user));

        UserLoginRequest request = UserLoginRequest.builder()
                .username("analyst_wang")
                .password("WrongPassword")
                .build();

        assertThrows(IllegalArgumentException.class, () -> userService.login(request));
    }

    @Test
    @DisplayName("长效 Refresh Token 换发新 Access Token 成功")
    void testRefreshTokenSuccess() {
        String refreshToken = jwtUtils.generateRefreshToken(3003L, "trader_li");

        User user = User.builder()
                .id(3003L)
                .username("trader_li")
                .status(UserStatus.ACTIVE)
                .roles(List.of(Role.builder().roleCode("ROLE_USER").build()))
                .build();
        when(mockUserPort.findById(3003L)).thenReturn(Optional.of(user));

        AuthResponse response = userService.refreshToken(refreshToken);

        assertNotNull(response);
        assertEquals(3003L, response.getUserId());
        assertEquals("trader_li", response.getUsername());
        assertTrue(jwtUtils.validateToken(response.getAccessToken()));
    }

    @Test
    @DisplayName("金融合规实名认证 (KYC)：申请提交证件脱敏，管理员审批通过流转状态")
    void testKycSubmissionAndReview() {
        Long userId = 4004L;
        IdentityVerificationRequest submitReq = IdentityVerificationRequest.builder()
                .realName("张三")
                .idCardNo("110101199003072345")
                .build();

        when(mockUserPort.findIdentityByUserId(userId)).thenReturn(Optional.empty());
        when(mockUserPort.findIdentityByHash(anyString())).thenReturn(Optional.empty());

        when(mockUserPort.saveIdentity(any(UserIdentity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserIdentity submitted = userService.submitIdentityVerification(userId, submitReq);
        assertNotNull(submitted);
        assertEquals(KycStatus.PENDING, submitted.getVerifyStatus());
        // 验证身份证号码脱敏
        assertTrue(submitted.getIdCardMasked().contains("********"));
        assertNotNull(submitted.getIdCardHash());

        // 管理员审核通过
        when(mockUserPort.findIdentityByUserId(userId)).thenReturn(Optional.of(submitted));
        IdentityReviewRequest reviewReq = IdentityReviewRequest.builder()
                .userId(userId)
                .approved(true)
                .build();

        UserIdentity reviewed = userService.reviewIdentity(reviewReq);
        assertEquals(KycStatus.VERIFIED, reviewed.getVerifyStatus());
        assertNotNull(reviewed.getVerifiedAt());
    }

    @Test
    @DisplayName("用户投资偏好画像：查询默认与更新定制偏好，验证 toAgentPromptSummary 研报指令格式")
    void testInvestmentProfileAndPromptSummary() {
        Long userId = 5005L;
        UserInvestmentProfile defaultProfile = UserInvestmentProfile.builder()
                .userId(userId)
                .riskToleranceLevel(RiskToleranceLevel.C3)
                .investmentHorizon(InvestmentHorizon.MEDIUM_TERM)
                .investmentStyle(InvestmentStyle.BALANCED)
                .preferredSectors(List.of("科技", "消费"))
                .maxDrawdownTolerance(new BigDecimal("20.00"))
                .build();

        when(mockUserPort.findInvestmentProfileByUserId(userId)).thenReturn(Optional.of(defaultProfile));
        when(mockUserPort.saveInvestmentProfile(any(UserInvestmentProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        // 验证默认画像
        UserInvestmentProfile current = userService.getInvestmentProfile(userId);
        assertEquals(RiskToleranceLevel.C3, current.getRiskToleranceLevel());

        // 更新为高风险进取型 (C5)
        InvestmentProfileUpdateRequest updateReq = InvestmentProfileUpdateRequest.builder()
                .riskToleranceLevel("C5")
                .investmentHorizon("LONG_TERM")
                .investmentStyle("GROWTH")
                .preferredSectors(List.of("半导体", "人工智能", "创新药"))
                .maxDrawdownTolerance(new BigDecimal("35.00"))
                .targetAnnualReturn(new BigDecimal("25.00"))
                .singlePositionLimit(new BigDecimal("25.00"))
                .build();

        UserInvestmentProfile updated = userService.updateInvestmentProfile(userId, updateReq);
        assertEquals(RiskToleranceLevel.C5, updated.getRiskToleranceLevel());
        assertEquals(InvestmentStyle.GROWTH, updated.getInvestmentStyle());

        // 验证生成的 Agent 提示词摘要
        String promptSummary = updated.toAgentPromptSummary();
        assertNotNull(promptSummary);
        assertTrue(promptSummary.contains("C5(进取型)"));
        assertTrue(promptSummary.contains("半导体,人工智能,创新药"));
        assertTrue(promptSummary.contains("35.00%"));
    }
}
