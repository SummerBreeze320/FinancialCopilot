package com.financial.copilot.agent.core.user.service;

import com.financial.copilot.agent.core.billing.WalletBillingService;
import com.financial.copilot.agent.core.security.JwtUtils;
import com.financial.copilot.agent.core.security.UserPrincipal;
import com.financial.copilot.agent.core.user.dto.*;
import com.financial.copilot.domain.user.entity.*;
import com.financial.copilot.domain.user.enums.*;
import com.financial.copilot.domain.user.port.UserPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * <h1>用户体系与金融合规业务服务实现类 (Default User Service)</h1>
 * <p>
 * 职责：
 * <ul>
 *   <li>用户注册密码强哈希落库、分配基础角色、初始化画像，并<b>跨模块事务联动向个人钱包注入 10,000 点体验算力</b>；</li>
 *   <li>用户登录账号密码比对与 JWT 双令牌签发；</li>
 *   <li>金融实名认证 (KYC) 证件脱敏、SHA-256 哈希排重、状态机审核流转；</li>
 *   <li>投资画像与风险偏好管理，为后续投研 Agent 提供结构化数字画像。</li>
 * </ul>
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Service
public class DefaultUserService implements UserService {

    private final UserPort userPort;
    private final WalletBillingService walletBillingService;
    private final JwtUtils jwtUtils;
    private final PasswordEncoder passwordEncoder;

    public DefaultUserService(UserPort userPort,
                              WalletBillingService walletBillingService,
                              JwtUtils jwtUtils) {
        this.userPort = userPort;
        this.walletBillingService = walletBillingService;
        this.jwtUtils = jwtUtils;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    @Override
    public AuthResponse register(UserRegisterRequest request) {
        if (request == null || request.getUsername() == null || request.getUsername().isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (request.getPassword() == null || request.getPassword().length() < 6) {
            throw new IllegalArgumentException("密码长度不能少于 6 位");
        }

        String username = request.getUsername().trim();
        if (userPort.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("用户名 [" + username + "] 已被注册");
        }
        if (request.getMobile() != null && !request.getMobile().isBlank()) {
            if (userPort.findByMobile(request.getMobile().trim()).isPresent()) {
                throw new IllegalArgumentException("手机号已被绑定");
            }
        }
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            if (userPort.findByEmail(request.getEmail().trim()).isPresent()) {
                throw new IllegalArgumentException("电子邮箱已被绑定");
            }
        }

        // 1. 创建账号主体并持久化
        String hash = passwordEncoder.encode(request.getPassword());
        User newUser = User.builder()
                .username(username)
                .passwordHash(hash)
                .mobile(request.getMobile() != null ? request.getMobile().trim() : null)
                .email(request.getEmail() != null ? request.getEmail().trim() : null)
                .status(UserStatus.ACTIVE)
                .build();
        User savedUser = userPort.saveUser(newUser);

        // 2. 分配基础角色 ROLE_USER
        userPort.assignRole(savedUser.getId(), "ROLE_USER");

        // 3. 初始化个人资料
        String nickname = (request.getNickname() != null && !request.getNickname().isBlank())
                ? request.getNickname().trim() : username;
        UserProfile profile = UserProfile.builder()
                .userId(savedUser.getId())
                .nickname(nickname)
                .avatarUrl("https://avatar.vercel.sh/" + username)
                .build();
        userPort.saveProfile(profile);

        // 4. 初始化默认投资画像 (C3 平衡型)
        UserInvestmentProfile investmentProfile = UserInvestmentProfile.builder()
                .userId(savedUser.getId())
                .riskToleranceLevel(RiskToleranceLevel.C3)
                .investmentHorizon(InvestmentHorizon.MEDIUM_TERM)
                .preferredAssetClasses(List.of("FUND"))
                .preferredSectors(List.of("优质公募基金"))
                .maxDrawdownTolerance(new BigDecimal("15.00"))
                .targetAnnualReturn(new BigDecimal("12.00"))
                .investmentStyle(InvestmentStyle.BALANCED)
                .singlePositionLimit(new BigDecimal("20.00"))
                .build();
        userPort.saveInvestmentProfile(investmentProfile);

        // 5. 跨系统业务联动：自动创建点数钱包并赠送 10,000 体验算力点
        walletBillingService.grantInitialTrialPoints(savedUser.getId(), 10000L);

        log.info("[USER-REGISTER] 新用户注册成功并初始化钱包及投资画像: userId={}, username={}",
                savedUser.getId(), username);

        // 6. 构造登录态凭据
        User fullUser = userPort.findById(savedUser.getId()).orElse(savedUser);
        return buildAuthResponse(fullUser);
    }

    @Override
    public AuthResponse login(UserLoginRequest request) {
        if (request == null || request.getUsername() == null || request.getPassword() == null) {
            throw new IllegalArgumentException("用户名与密码不能为空");
        }

        String account = request.getUsername().trim();
        User user = userPort.findByUsername(account)
                .or(() -> userPort.findByMobile(account))
                .or(() -> userPort.findByEmail(account))
                .orElseThrow(() -> new IllegalArgumentException("账号或密码错误"));

        if (!user.isActive()) {
            throw new IllegalStateException("账号已被锁定或禁用，请联系平台合规客服");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("账号或密码错误");
        }

        log.info("[USER-LOGIN] 用户登录成功: userId={}, username={}", user.getId(), user.getUsername());
        return buildAuthResponse(user);
    }

    @Override
    public AuthResponse refreshToken(String refreshToken) {
        if (!jwtUtils.validateToken(refreshToken)) {
            throw new IllegalArgumentException("刷新令牌无效或已过期，请重新登录");
        }
        Long userId = jwtUtils.extractUserId(refreshToken);
        User user = userPort.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        return buildAuthResponse(user);
    }

    @Override
    public UserPrincipal loadUserByUsername(String username) {
        User user = userPort.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("未找到用户: " + username));
        return UserPrincipal.fromDomain(user);
    }

    @Override
    public UserProfile getProfile(Long userId) {
        return userPort.findProfileByUserId(userId)
                .orElseGet(() -> UserProfile.builder().userId(userId).nickname("用户_" + userId).build());
    }

    @Override
    public UserProfile updateProfile(Long userId, UserProfileUpdateRequest request) {
        UserProfile existing = getProfile(userId);
        if (request.getNickname() != null && !request.getNickname().isBlank()) {
            existing.setNickname(request.getNickname().trim());
        }
        if (request.getAvatarUrl() != null) {
            existing.setAvatarUrl(request.getAvatarUrl().trim());
        }
        if (request.getCompany() != null) {
            existing.setCompany(request.getCompany().trim());
        }
        if (request.getOccupation() != null) {
            existing.setOccupation(request.getOccupation().trim());
        }
        if (request.getBio() != null) {
            existing.setBio(request.getBio().trim());
        }
        if (request.getCity() != null) {
            existing.setCity(request.getCity().trim());
        }
        return userPort.saveProfile(existing);
    }

    @Override
    public UserIdentity submitIdentityVerification(Long userId, IdentityVerificationRequest request) {
        if (request == null || request.getRealName() == null || request.getIdCardNo() == null) {
            throw new IllegalArgumentException("真实姓名与身份证号码不能为空");
        }
        String idCardClean = request.getIdCardNo().trim().toUpperCase();
        if (idCardClean.length() < 15 || idCardClean.length() > 18) {
            throw new IllegalArgumentException("证件号码格式不合法");
        }

        String hash = sha256(idCardClean);
        userPort.findIdentityByHash(hash).ifPresent(existing -> {
            if (!existing.getUserId().equals(userId)) {
                throw new IllegalArgumentException("该身份证号码已被其他实名账户绑定");
            }
        });

        String masked = maskIdCard(idCardClean);
        String encrypted = encryptIdCard(idCardClean);

        UserIdentity identity = UserIdentity.builder()
                .userId(userId)
                .realName(request.getRealName().trim())
                .idCardType(request.getIdCardType() != null ? request.getIdCardType() : "ID_CARD")
                .idCardHash(hash)
                .idCardEncrypted(encrypted)
                .idCardMasked(masked)
                .verifyStatus(KycStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();

        log.info("[KYC-SUBMIT] 收到实名认证申请: userId={}, realName={}, masked={}", userId, request.getRealName(), masked);
        return userPort.saveIdentity(identity);
    }

    @Override
    public UserIdentity getIdentity(Long userId) {
        return userPort.findIdentityByUserId(userId)
                .orElseGet(() -> UserIdentity.builder()
                        .userId(userId)
                        .verifyStatus(KycStatus.UNVERIFIED)
                        .build());
    }

    @Override
    public UserIdentity reviewIdentity(IdentityReviewRequest request) {
        if (request == null || request.getUserId() == null || request.getApproved() == null) {
            throw new IllegalArgumentException("审核参数不合法");
        }

        UserIdentity identity = userPort.findIdentityByUserId(request.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("未找到该用户的实名认证申请"));

        if (Boolean.TRUE.equals(request.getApproved())) {
            identity.setVerifyStatus(KycStatus.VERIFIED);
            identity.setVerifiedAt(LocalDateTime.now());
            identity.setRejectReason(null);
            log.info("[KYC-REVIEW] 实名认证审核通过: userId={}, realName={}", identity.getUserId(), identity.getRealName());
        } else {
            identity.setVerifyStatus(KycStatus.REJECTED);
            identity.setRejectReason(request.getRejectReason() != null ? request.getRejectReason() : "证件信息不符");
            log.warn("[KYC-REVIEW] 实名认证已驳回: userId={}, reason={}", identity.getUserId(), identity.getRejectReason());
        }

        return userPort.saveIdentity(identity);
    }

    @Override
    public UserInvestmentProfile getInvestmentProfile(Long userId) {
        return userPort.findInvestmentProfileByUserId(userId)
                .orElseGet(() -> UserInvestmentProfile.builder()
                        .userId(userId)
                        .riskToleranceLevel(RiskToleranceLevel.C3)
                        .investmentHorizon(InvestmentHorizon.MEDIUM_TERM)
                        .investmentStyle(InvestmentStyle.BALANCED)
                        .build());
    }

    @Override
    public UserInvestmentProfile updateInvestmentProfile(Long userId, InvestmentProfileUpdateRequest request) {
        UserInvestmentProfile profile = getInvestmentProfile(userId);
        if (request.getRiskToleranceLevel() != null) {
            profile.setRiskToleranceLevel(RiskToleranceLevel.fromCode(request.getRiskToleranceLevel()));
        }
        if (request.getInvestmentHorizon() != null) {
            try {
                profile.setInvestmentHorizon(InvestmentHorizon.valueOf(request.getInvestmentHorizon().trim().toUpperCase()));
            } catch (Exception ignored) {
            }
        }
        if (request.getPreferredAssetClasses() != null) {
            profile.setPreferredAssetClasses(request.getPreferredAssetClasses());
        }
        if (request.getPreferredSectors() != null) {
            profile.setPreferredSectors(request.getPreferredSectors());
        }
        if (request.getMaxDrawdownTolerance() != null) {
            profile.setMaxDrawdownTolerance(request.getMaxDrawdownTolerance());
        }
        if (request.getTargetAnnualReturn() != null) {
            profile.setTargetAnnualReturn(request.getTargetAnnualReturn());
        }
        if (request.getInvestmentStyle() != null) {
            try {
                profile.setInvestmentStyle(InvestmentStyle.valueOf(request.getInvestmentStyle().trim().toUpperCase()));
            } catch (Exception ignored) {
            }
        }
        if (request.getSinglePositionLimit() != null) {
            profile.setSinglePositionLimit(request.getSinglePositionLimit());
        }

        log.info("[INVESTMENT-PROFILE] 投资画像已更新: userId={}, profile={}", userId, profile.toAgentPromptSummary());
        return userPort.saveInvestmentProfile(profile);
    }

    @Override
    public List<User> listUsers(int page, int size) {
        return userPort.listUsers(page, size);
    }

    @Override
    public List<UserIdentity> listPendingIdentities(int page, int size) {
        return userPort.listPendingIdentities(page, size);
    }

    private AuthResponse buildAuthResponse(User user) {
        UserPrincipal principal = UserPrincipal.fromDomain(user);
        String accessToken = jwtUtils.generateAccessToken(principal);
        String refreshToken = jwtUtils.generateRefreshToken(user.getId(), user.getUsername());

        UserProfile profile = userPort.findProfileByUserId(user.getId()).orElse(null);
        String nickname = profile != null ? profile.getNickname() : user.getUsername();

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(jwtUtils.getAccessTokenExpirationMs())
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(nickname)
                .roles(principal.getRoles())
                .permissions(principal.getPermissions())
                .build();
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private String maskIdCard(String idCard) {
        if (idCard.length() < 10) return idCard;
        return idCard.substring(0, 6) + "********" + idCard.substring(idCard.length() - 4);
    }

    private String encryptIdCard(String idCard) {
        return Base64.getEncoder().encodeToString(idCard.getBytes(StandardCharsets.UTF_8));
    }
}
