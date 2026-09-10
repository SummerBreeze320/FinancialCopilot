package com.financial.copilot.agent.core.security;

import com.financial.copilot.domain.user.entity.User;
import com.financial.copilot.domain.user.enums.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * <h1>Spring Security 认证用户主体上下文 (User Principal)</h1>
 * <p>
 * 实现 {@link UserDetails} 契约，承载经过 JWT 验证后的用户身份、租户及角色权限集合。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPrincipal implements UserDetails, Serializable {

    private Long userId;

    private String username;

    private String password;

    private String mobile;

    private String email;

    private UserStatus status;

    @Builder.Default
    private List<String> roles = new ArrayList<>();

    @Builder.Default
    private List<String> permissions = new ArrayList<>();

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (roles != null) {
            for (String r : roles) {
                authorities.add(new SimpleGrantedAuthority(r.startsWith("ROLE_") ? r : "ROLE_" + r));
            }
        }
        if (permissions != null) {
            for (String p : permissions) {
                authorities.add(new SimpleGrantedAuthority(p));
            }
        }
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return status != UserStatus.LOCKED;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return status == UserStatus.ACTIVE;
    }

    public static UserPrincipal fromDomain(User user) {
        if (user == null) return null;
        List<String> roleCodes = user.getRoles() != null
                ? user.getRoles().stream().map(r -> r.getRoleCode()).toList()
                : List.of("ROLE_USER");
        List<String> permCodes = user.getPermissions() != null
                ? user.getPermissions().stream().map(p -> p.getPermCode()).toList()
                : List.of();

        return UserPrincipal.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .password(user.getPasswordHash())
                .mobile(user.getMobile())
                .email(user.getEmail())
                .status(user.getStatus())
                .roles(roleCodes)
                .permissions(permCodes)
                .build();
    }
}
