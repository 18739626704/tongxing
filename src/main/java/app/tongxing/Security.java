package app.tongxing;

import app.tongxing.store.Member;
import app.tongxing.store.MemberMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class Security {
    @Bean
    PasswordEncoder passwords() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    @Order(1)
    SecurityFilterChain adminSecurity(HttpSecurity http, AdminAccess admin, PasswordEncoder passwords) throws Exception {
        var provider = new DaoAuthenticationProvider(admin::load);
        provider.setPasswordEncoder(passwords);
        http.securityMatcher("/admin", "/admin/**").authenticationManager(new ProviderManager(provider))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin", "/admin/", "/admin/index.html", "/admin/admin.css",
                                "/admin/admin.js", "/admin/csrf", "/admin/login").permitAll()
                        .anyRequest().hasRole("ADMIN"))
                .addFilterBefore(new AdminSessionCheck(admin), LogoutFilter.class)
                .headers(headers -> headers.contentSecurityPolicy(policy -> policy.policyDirectives(
                        "default-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")));
        return sessionEndpoints(http, "/admin").build();
    }

    @Bean
    DaoAuthenticationProvider memberAuthentication(MemberMapper members, PasswordEncoder passwords) {
        var provider = new DaoAuthenticationProvider(username -> {
            Member member = members.selectOne(new QueryWrapper<Member>().eq("username", username));
            if (member == null) throw new UsernameNotFoundException("Account not found");
            return new MemberPrincipal(member);
        });
        provider.setPasswordEncoder(passwords);
        return provider;
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiSecurity(HttpSecurity http, MemberMapper members, DaoAuthenticationProvider memberAuthentication)
            throws Exception {
        http.authenticationManager(new ProviderManager(memberAuthentication))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/csrf", "/api/health", "/api/register", "/api/login", "/error").permitAll()
                        .anyRequest().hasRole("MEMBER"))
                .addFilterBefore(new MemberSessionCheck(members), LogoutFilter.class);
        return sessionEndpoints(http, "/api").build();
    }

    private HttpSecurity sessionEndpoints(HttpSecurity http, String prefix) throws Exception {
        return http.requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        (request, response, exception) -> response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)))
                .formLogin(login -> login.loginPage(prefix.equals("/admin") ? "/admin/" : "/api/login")
                        .loginProcessingUrl(prefix + "/login")
                        .successHandler((request, response, authentication) -> response.setStatus(204))
                        .failureHandler((request, response, exception) -> response.setStatus(401)))
                .logout(logout -> logout.logoutUrl(prefix + "/logout").deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)));
    }

    private static final class AdminSessionCheck extends OncePerRequestFilter {
        private final AdminAccess admin;

        AdminSessionCheck(AdminAccess admin) { this.admin = admin; }

        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                  FilterChain chain) throws ServletException, IOException {
            String path = request.getServletPath();
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (path.startsWith("/admin/api/") && authentication != null
                    && authentication.getPrincipal() instanceof AdminAccess.Principal principal) {
                if (!admin.valid(principal)) {
                    new SecurityContextLogoutHandler().logout(request, response, authentication);
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                if (principal.mustChangePassword && !"/admin/api/session".equals(path)
                        && !AdminAccess.CHANGE_PASSWORD.equals(path)) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }

    private static final class MemberPrincipal extends User {
        final String id;
        final int version;

        MemberPrincipal(Member member) {
            super(member.username, member.passwordHash, !member.disabled, true, true, true,
                    List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
            id = member.id;
            version = member.sessionVersion;
        }
    }

    private static final class MemberSessionCheck extends OncePerRequestFilter {
        private final MemberMapper members;

        MemberSessionCheck(MemberMapper members) { this.members = members; }

        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                  FilterChain chain) throws ServletException, IOException {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof MemberPrincipal principal) {
                Member member = members.selectById(principal.id);
                if (member == null || member.disabled || member.sessionVersion != principal.version) {
                    new SecurityContextLogoutHandler().logout(request, response, authentication);
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }
}
