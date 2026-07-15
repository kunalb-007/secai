package com.secai.service;

import com.secai.domain.organization.Organization;
import com.secai.domain.organization.OrganizationRepository;
import com.secai.domain.user.AppUser;
import com.secai.domain.user.AppUserRepository;
import com.secai.dto.auth.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class AuthService {

    private final OrganizationRepository orgRepo;
    private final AppUserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            OrganizationRepository orgRepo,
            AppUserRepository userRepo,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.orgRepo = orgRepo;
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * Register creates org + user atomically.
     * One transaction — if anything fails, both roll back.
     */
    @Transactional
    public AuthResponse register(RegisterRequest req) {
        log.info("Registration request received for email {}",
                req.email().toLowerCase().trim());

        if (userRepo.existsByEmail(req.email())) {
            throw new IllegalArgumentException("Email already registered");
        }

        // 1. Create organization
        Organization org = orgRepo.save(
                Organization.builder()
                        .name(req.organizationName())
                        .build()
        );

        log.info("Created organization {} ({})",
                org.getName(),
                org.getId());

        // 2. Create user
        AppUser user = userRepo.save(
                AppUser.builder()
                        .email(req.email().toLowerCase().trim())
                        .passwordHash(passwordEncoder.encode(req.password()))
                        .organization(org)
                        .build()
        );

        log.info("Created user {} for organization {}",
                user.getEmail(),
                org.getId());

        // 3. Return JWT immediately — no separate login step needed
        String token = jwtService.generateToken(user);
        log.info("Registration completed successfully for {}",
                user.getEmail());
        return new AuthResponse(token, user.getEmail(), org.getId(), org.getName());
    }

    /**
     * Login validates credentials and returns JWT.
     */
    public AuthResponse login(LoginRequest req) {
        log.info("Login attempt for {}",
                req.email().toLowerCase().trim());

        AppUser user = userRepo.findByEmail(req.email().toLowerCase().trim())
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        log.info("Login successful for {}",
                user.getEmail());

        String token = jwtService.generateToken(user);
        return new AuthResponse(
                token,
                user.getEmail(),
                user.getOrganization().getId(),
                user.getOrganization().getName()
        );
    }
}