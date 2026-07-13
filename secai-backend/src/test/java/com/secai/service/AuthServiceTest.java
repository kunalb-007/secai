package com.secai.service;

import com.secai.domain.organization.Organization;
import com.secai.domain.organization.OrganizationRepository;
import com.secai.domain.user.AppUser;
import com.secai.domain.user.AppUserRepository;
import com.secai.dto.auth.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock OrganizationRepository orgRepo;
    @Mock AppUserRepository userRepo;
    @Mock PasswordEncoder encoder;
    @Mock JwtService jwtService;
    @InjectMocks AuthService authService;

    @Test
    void register_success() {
        var org = Organization.builder().id(UUID.randomUUID()).name("Acme").build();
        var user = AppUser.builder()
                .id(UUID.randomUUID()).email("a@b.com").organization(org).build();

        when(userRepo.existsByEmail(any())).thenReturn(false);
        when(orgRepo.save(any())).thenReturn(org);
        when(userRepo.save(any())).thenReturn(user);
        when(jwtService.generateToken(any())).thenReturn("tok");
        when(encoder.encode(any())).thenReturn("hashed");

        var req = new RegisterRequest("Acme", "a@b.com", "password123");
        var res = authService.register(req);

        assertThat(res.token()).isEqualTo("tok");
        assertThat(res.email()).isEqualTo("a@b.com");
        verify(orgRepo).save(any());
        verify(userRepo).save(any());
    }

    @Test
    void register_duplicateEmail_throws() {
        when(userRepo.existsByEmail(any())).thenReturn(true);
        var req = new RegisterRequest("Acme", "a@b.com", "password123");
        assertThatThrownBy(() -> authService.register(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already registered");
    }
}