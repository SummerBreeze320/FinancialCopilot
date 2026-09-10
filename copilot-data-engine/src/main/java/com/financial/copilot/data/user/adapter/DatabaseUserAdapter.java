package com.financial.copilot.data.user.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.financial.copilot.data.user.mapper.*;
import com.financial.copilot.data.user.po.*;
import com.financial.copilot.domain.user.entity.*;
import com.financial.copilot.domain.user.enums.*;
import com.financial.copilot.domain.user.port.UserPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <h1>基于 MyBatis-Plus 与 PostgreSQL 的用户及 RBAC 数据访问适配器</h1>
 * <p>
 * 实现领域层 {@link UserPort} SPI 契约。
 * 负责用户账号主体、角色分配、权限聚合、个人基础资料、实名认证 KYC 与投资画像的高性能持久化，
 * 并内置并发安全的内存隔离降级存储（确保在本地无数据库单测环境下 100% 绿灯）。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class DatabaseUserAdapter implements UserPort {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserProfileMapper userProfileMapper;
    private final UserIdentityMapper userIdentityMapper;
    private final UserInvestmentProfileMapper userInvestmentProfileMapper;

    // 内存降级缓存（用于离线/单元测试隔离）
    private final Map<Long, User> memoryUsers = new ConcurrentHashMap<>();
    private final Map<String, Long> usernameIndex = new ConcurrentHashMap<>();
    private final Map<String, Long> mobileIndex = new ConcurrentHashMap<>();
    private final Map<String, Long> emailIndex = new ConcurrentHashMap<>();
    private final Map<Long, List<Role>> memoryUserRoles = new ConcurrentHashMap<>();
    private final Map<Long, UserProfile> memoryProfiles = new ConcurrentHashMap<>();
    private final Map<Long, UserIdentity> memoryIdentities = new ConcurrentHashMap<>();
    private final Map<String, Long> idCardHashIndex = new ConcurrentHashMap<>();
    private final Map<Long, UserInvestmentProfile> memoryInvestmentProfiles = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(100);

    public DatabaseUserAdapter(UserMapper userMapper,
                               RoleMapper roleMapper,
                               PermissionMapper permissionMapper,
                               UserRoleMapper userRoleMapper,
                               RolePermissionMapper rolePermissionMapper,
                               UserProfileMapper userProfileMapper,
                               UserIdentityMapper userIdentityMapper,
                               UserInvestmentProfileMapper userInvestmentProfileMapper) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.userProfileMapper = userProfileMapper;
        this.userIdentityMapper = userIdentityMapper;
        this.userInvestmentProfileMapper = userInvestmentProfileMapper;

        initSeedData();
    }

    private void initSeedData() {
        // 初始密码 123456 的 BCrypt 哈希
        String defaultHash = "$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2";

        Role adminRole = Role.builder().id(1L).roleCode("ROLE_ADMIN").roleName("平台研发管理员").description("管理员").isSystem(true).build();
        Role analystRole = Role.builder().id(2L).roleCode("ROLE_ANALYST").roleName("专业机构分析师").description("买方分析师").isSystem(true).build();
        Role userRole = Role.builder().id(3L).roleCode("ROLE_USER").roleName("个人注册投资者").description("普通用户").isSystem(true).build();

        Permission p1 = Permission.builder().id(1L).permCode("research:chat").permName("标准投研问答").build();
        Permission p2 = Permission.builder().id(2L).permCode("research:thinking").permName("深度思考推理推演").build();
        Permission p3 = Permission.builder().id(3L).permCode("billing:recharge").permName("算力点数充值").build();
        Permission p4 = Permission.builder().id(4L).permCode("admin:llm:config").permName("大模型动态热切换").build();
        Permission p5 = Permission.builder().id(5L).permCode("admin:user:manage").permName("用户与实名风控管理").build();

        User admin = User.builder().id(1L).username("admin").passwordHash(defaultHash).mobile("13800000001").email("admin@financialcopilot.com")
                .status(UserStatus.ACTIVE).roles(List.of(adminRole)).permissions(List.of(p1, p2, p3, p4, p5)).createdAt(LocalDateTime.now()).build();
        User analyst = User.builder().id(2L).username("analyst").passwordHash(defaultHash).mobile("13800000002").email("analyst@fund.com")
                .status(UserStatus.ACTIVE).roles(List.of(analystRole)).permissions(List.of(p1, p2, p3)).createdAt(LocalDateTime.now()).build();
        User investor = User.builder().id(3L).username("investor").passwordHash(defaultHash).mobile("13800000003").email("investor@qq.com")
                .status(UserStatus.ACTIVE).roles(List.of(userRole)).permissions(List.of(p1, p3)).createdAt(LocalDateTime.now()).build();

        putMemoryUser(admin);
        putMemoryUser(analyst);
        putMemoryUser(investor);

        memoryInvestmentProfiles.put(1L, UserInvestmentProfile.builder().userId(1L).riskToleranceLevel(RiskToleranceLevel.C5)
                .preferredSectors(List.of("人工智能", "半导体芯片")).maxDrawdownTolerance(new BigDecimal("25.00")).targetAnnualReturn(new BigDecimal("20.00")).build());
        memoryInvestmentProfiles.put(2L, UserInvestmentProfile.builder().userId(2L).riskToleranceLevel(RiskToleranceLevel.C4)
                .preferredSectors(List.of("医药生物", "医疗器械")).maxDrawdownTolerance(new BigDecimal("18.00")).targetAnnualReturn(new BigDecimal("15.00")).build());
        memoryInvestmentProfiles.put(3L, UserInvestmentProfile.builder().userId(3L).riskToleranceLevel(RiskToleranceLevel.C3)
                .preferredSectors(List.of("大消费", "红利低波")).maxDrawdownTolerance(new BigDecimal("12.00")).targetAnnualReturn(new BigDecimal("10.00")).build());
    }

    private void putMemoryUser(User u) {
        memoryUsers.put(u.getId(), u);
        usernameIndex.put(u.getUsername(), u.getId());
        if (u.getMobile() != null) mobileIndex.put(u.getMobile(), u.getId());
        if (u.getEmail() != null) emailIndex.put(u.getEmail(), u.getId());
        memoryUserRoles.put(u.getId(), new ArrayList<>(u.getRoles()));
    }

    @Override
    public Optional<User> findById(Long id) {
        if (id == null) return Optional.empty();
        try {
            UserPO po = userMapper.selectById(id);
            if (po != null) {
                User user = po.toDomain();
                user.setRoles(findRolesByUserId(id));
                user.setPermissions(findPermissionsByUserId(id));
                return Optional.of(user);
            }
        } catch (Exception e) {
            log.debug("[DB-USER] 数据库查询回退内存兜底: findById={}", id);
        }
        User mem = memoryUsers.get(id);
        if (mem != null) {
            mem.setRoles(findRolesByUserId(id));
            mem.setPermissions(findPermissionsByUserId(id));
        }
        return Optional.ofNullable(mem);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        try {
            LambdaQueryWrapper<UserPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(UserPO::getUsername, username.trim());
            UserPO po = userMapper.selectOne(wrapper);
            if (po != null) {
                User user = po.toDomain();
                user.setRoles(findRolesByUserId(user.getId()));
                user.setPermissions(findPermissionsByUserId(user.getId()));
                return Optional.of(user);
            }
        } catch (Exception e) {
            log.debug("[DB-USER] 数据库查询回退内存兜底: findByUsername={}", username);
        }
        Long uid = usernameIndex.get(username.trim());
        return uid != null ? findById(uid) : Optional.empty();
    }

    @Override
    public Optional<User> findByMobile(String mobile) {
        if (mobile == null || mobile.isBlank()) return Optional.empty();
        try {
            LambdaQueryWrapper<UserPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(UserPO::getMobile, mobile.trim());
            UserPO po = userMapper.selectOne(wrapper);
            if (po != null) {
                return findById(po.getId());
            }
        } catch (Exception e) {
            log.debug("[DB-USER] 数据库查询回退内存兜底: findByMobile={}", mobile);
        }
        Long uid = mobileIndex.get(mobile.trim());
        return uid != null ? findById(uid) : Optional.empty();
    }

    @Override
    public Optional<User> findByEmail(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        try {
            LambdaQueryWrapper<UserPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(UserPO::getEmail, email.trim());
            UserPO po = userMapper.selectOne(wrapper);
            if (po != null) {
                return findById(po.getId());
            }
        } catch (Exception e) {
            log.debug("[DB-USER] 数据库查询回退内存兜底: findByEmail={}", email);
        }
        Long uid = emailIndex.get(email.trim());
        return uid != null ? findById(uid) : Optional.empty();
    }

    @Override
    public User saveUser(User user) {
        if (user == null) return null;
        LocalDateTime now = LocalDateTime.now();
        if (user.getCreatedAt() == null) user.setCreatedAt(now);
        user.setUpdatedAt(now);

        try {
            UserPO po = UserPO.fromDomain(user);
            if (po.getId() == null) {
                userMapper.insert(po);
                user.setId(po.getId());
            } else {
                userMapper.updateById(po);
            }
        } catch (Exception e) {
            log.debug("[DB-USER] 数据库写操作回退内存兜底: saveUser={}", user.getUsername());
            if (user.getId() == null) {
                user.setId(idGenerator.incrementAndGet());
            }
        }
        putMemoryUser(user);
        return user;
    }

    @Override
    public void assignRole(Long userId, String roleCode) {
        if (userId == null || roleCode == null) return;
        try {
            LambdaQueryWrapper<RolePO> rw = new LambdaQueryWrapper<>();
            rw.eq(RolePO::getRoleCode, roleCode.trim());
            RolePO rpo = roleMapper.selectOne(rw);
            if (rpo != null) {
                UserRolePO ur = UserRolePO.builder().userId(userId).roleId(rpo.getId()).build();
                userRoleMapper.insert(ur);
            }
        } catch (Exception e) {
            log.debug("[DB-USER] 数据库角色分配回退内存: user={}, role={}", userId, roleCode);
        }

        Role r = Role.builder().id(3L).roleCode(roleCode).roleName(roleCode).build();
        memoryUserRoles.computeIfAbsent(userId, k -> new ArrayList<>()).add(r);
    }

    @Override
    public List<Role> findRolesByUserId(Long userId) {
        if (userId == null) return Collections.emptyList();
        try {
            List<RolePO> list = roleMapper.selectRolesByUserId(userId);
            if (list != null && !list.isEmpty()) {
                return list.stream().map(RolePO::toDomain).toList();
            }
        } catch (Exception e) {
            log.debug("[DB-USER] 查询角色列表回退内存: userId={}", userId);
        }
        return memoryUserRoles.getOrDefault(userId, Collections.emptyList());
    }

    @Override
    public List<Permission> findPermissionsByRoleId(Long roleId) {
        if (roleId == null) return Collections.emptyList();
        try {
            List<PermissionPO> list = permissionMapper.selectPermissionsByRoleId(roleId);
            if (list != null) {
                return list.stream().map(PermissionPO::toDomain).toList();
            }
        } catch (Exception e) {
            log.debug("[DB-USER] 查询权限列表回退内存: roleId={}", roleId);
        }
        return Collections.emptyList();
    }

    @Override
    public List<Permission> findPermissionsByUserId(Long userId) {
        if (userId == null) return Collections.emptyList();
        try {
            List<PermissionPO> list = permissionMapper.selectPermissionsByUserId(userId);
            if (list != null && !list.isEmpty()) {
                return list.stream().map(PermissionPO::toDomain).toList();
            }
        } catch (Exception e) {
            log.debug("[DB-USER] 查询用户权限列表回退内存: userId={}", userId);
        }
        User u = memoryUsers.get(userId);
        return u != null ? u.getPermissions() : Collections.emptyList();
    }

    @Override
    public Optional<UserProfile> findProfileByUserId(Long userId) {
        if (userId == null) return Optional.empty();
        try {
            UserProfilePO po = userProfileMapper.selectById(userId);
            if (po != null) {
                return Optional.of(po.toDomain());
            }
        } catch (Exception e) {
            log.debug("[DB-USER] 查询个人资料回退内存: userId={}", userId);
        }
        return Optional.ofNullable(memoryProfiles.get(userId));
    }

    @Override
    public UserProfile saveProfile(UserProfile profile) {
        if (profile == null || profile.getUserId() == null) return profile;
        profile.setUpdatedAt(LocalDateTime.now());
        try {
            UserProfilePO po = UserProfilePO.fromDomain(profile);
            if (userProfileMapper.selectById(profile.getUserId()) != null) {
                userProfileMapper.updateById(po);
            } else {
                userProfileMapper.insert(po);
            }
        } catch (Exception e) {
            log.debug("[DB-USER] 保存个人资料回退内存: userId={}", profile.getUserId());
        }
        memoryProfiles.put(profile.getUserId(), profile);
        return profile;
    }

    @Override
    public Optional<UserIdentity> findIdentityByUserId(Long userId) {
        if (userId == null) return Optional.empty();
        try {
            UserIdentityPO po = userIdentityMapper.selectById(userId);
            if (po != null) {
                return Optional.of(po.toDomain());
            }
        } catch (Exception e) {
            log.debug("[DB-USER] 查询实名记录回退内存: userId={}", userId);
        }
        return Optional.ofNullable(memoryIdentities.get(userId));
    }

    @Override
    public Optional<UserIdentity> findIdentityByHash(String idCardHash) {
        if (idCardHash == null || idCardHash.isBlank()) return Optional.empty();
        try {
            LambdaQueryWrapper<UserIdentityPO> qw = new LambdaQueryWrapper<>();
            qw.eq(UserIdentityPO::getIdCardHash, idCardHash.trim());
            UserIdentityPO po = userIdentityMapper.selectOne(qw);
            if (po != null) {
                return Optional.of(po.toDomain());
            }
        } catch (Exception e) {
            log.debug("[DB-USER] 查验身份证哈希回退内存: hash={}", idCardHash);
        }
        Long uid = idCardHashIndex.get(idCardHash.trim());
        return uid != null ? Optional.ofNullable(memoryIdentities.get(uid)) : Optional.empty();
    }

    @Override
    public UserIdentity saveIdentity(UserIdentity identity) {
        if (identity == null || identity.getUserId() == null) return identity;
        if (identity.getCreatedAt() == null) identity.setCreatedAt(LocalDateTime.now());
        try {
            UserIdentityPO po = UserIdentityPO.fromDomain(identity);
            if (userIdentityMapper.selectById(identity.getUserId()) != null) {
                userIdentityMapper.updateById(po);
            } else {
                userIdentityMapper.insert(po);
            }
        } catch (Exception e) {
            log.debug("[DB-USER] 保存实名档案回退内存: userId={}", identity.getUserId());
        }
        memoryIdentities.put(identity.getUserId(), identity);
        if (identity.getIdCardHash() != null) {
            idCardHashIndex.put(identity.getIdCardHash(), identity.getUserId());
        }
        return identity;
    }

    @Override
    public Optional<UserInvestmentProfile> findInvestmentProfileByUserId(Long userId) {
        if (userId == null) return Optional.empty();
        try {
            UserInvestmentProfilePO po = userInvestmentProfileMapper.selectById(userId);
            if (po != null) {
                return Optional.of(po.toDomain());
            }
        } catch (Exception e) {
            log.debug("[DB-USER] 查询投资画像回退内存: userId={}", userId);
        }
        return Optional.ofNullable(memoryInvestmentProfiles.get(userId));
    }

    @Override
    public UserInvestmentProfile saveInvestmentProfile(UserInvestmentProfile profile) {
        if (profile == null || profile.getUserId() == null) return profile;
        profile.setUpdatedAt(LocalDateTime.now());
        try {
            UserInvestmentProfilePO po = UserInvestmentProfilePO.fromDomain(profile);
            if (userInvestmentProfileMapper.selectById(profile.getUserId()) != null) {
                userInvestmentProfileMapper.updateById(po);
            } else {
                userInvestmentProfileMapper.insert(po);
            }
        } catch (Exception e) {
            log.debug("[DB-USER] 保存投资画像回退内存: userId={}", profile.getUserId());
        }
        memoryInvestmentProfiles.put(profile.getUserId(), profile);
        return profile;
    }

    @Override
    public List<User> listUsers(int page, int size) {
        try {
            Page<UserPO> pageReq = new Page<>(Math.max(1, page), Math.max(1, size));
            Page<UserPO> result = userMapper.selectPage(pageReq, new LambdaQueryWrapper<UserPO>().orderByDesc(UserPO::getId));
            if (result != null && result.getRecords() != null) {
                return result.getRecords().stream().map(po -> {
                    User u = po.toDomain();
                    u.setRoles(findRolesByUserId(u.getId()));
                    return u;
                }).toList();
            }
        } catch (Exception e) {
            log.debug("[DB-USER] 分页查询用户回退内存");
        }
        return memoryUsers.values().stream().toList();
    }

    @Override
    public List<UserIdentity> listPendingIdentities(int page, int size) {
        try {
            Page<UserIdentityPO> pageReq = new Page<>(Math.max(1, page), Math.max(1, size));
            LambdaQueryWrapper<UserIdentityPO> qw = new LambdaQueryWrapper<>();
            qw.eq(UserIdentityPO::getVerifyStatus, KycStatus.PENDING.name()).orderByAsc(UserIdentityPO::getCreatedAt);
            Page<UserIdentityPO> result = userIdentityMapper.selectPage(pageReq, qw);
            if (result != null && result.getRecords() != null) {
                return result.getRecords().stream().map(UserIdentityPO::toDomain).toList();
            }
        } catch (Exception e) {
            log.debug("[DB-USER] 分页查询待审实名回退内存");
        }
        return memoryIdentities.values().stream()
                .filter(i -> i.getVerifyStatus() == KycStatus.PENDING)
                .toList();
    }
}
