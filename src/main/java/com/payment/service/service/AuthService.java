package com.payment.service.service;

import com.payment.service.dto.request.LoginRequest;
import com.payment.service.dto.request.RegisterRequest;
import com.payment.service.dto.response.AuthResponse;
import com.payment.service.dto.response.UserResponse;
import com.payment.service.exception.PaymentException;
import com.payment.service.model.RefreshToken;
import com.payment.service.model.User;
import com.payment.service.model.Wallet;
import com.payment.service.repository.UserRepository;
import com.payment.service.repository.WalletRepository;
import com.payment.service.security.JwtTokenProvider;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.param.CustomerCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;

    @Value("${jwt.access-token-expiration}")
    private long accessTokenExpiration;

    public AuthService(UserRepository userRepository,
                       WalletRepository walletRepository,
                       JwtTokenProvider jwtTokenProvider,
                       RefreshTokenService refreshTokenService,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenService = refreshTokenService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new PaymentException("Email already registered");
        }
        if (request.phoneNumber() != null && userRepository.existsByPhoneNumber(request.phoneNumber())) {
            throw new PaymentException("Phone number already registered");
        }

        String stripeCustomerId = createStripeCustomer(request.email(), request.firstName(), request.lastName());

        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setPhoneNumber(request.phoneNumber());
        user.setStripeCustomerId(stripeCustomerId);
        user = userRepository.save(user);

        Wallet wallet = new Wallet(user);
        walletRepository.save(wallet);

        return buildAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }
        if (!user.isEnabled()) {
            throw new PaymentException("Account is disabled");
        }

        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse refreshToken(String rawRefreshToken) {
        RefreshToken rt = refreshTokenService.validateToken(rawRefreshToken);
        refreshTokenService.revokeAllUserTokens(rt.getUser());
        return buildAuthResponse(rt.getUser());
    }

    @Transactional
    public void logout(User user) {
        refreshTokenService.revokeAllUserTokens(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user);

        UserResponse userResponse = new UserResponse(
                user.getId(), user.getEmail(), user.getFirstName(),
                user.getLastName(), user.getPhoneNumber(), user.getCreatedAt());

        return new AuthResponse(accessToken, refreshToken.getToken(), accessTokenExpiration / 1000, userResponse);
    }

    private String createStripeCustomer(String email, String firstName, String lastName) {
        try {
            CustomerCreateParams params = CustomerCreateParams.builder()
                    .setEmail(email)
                    .setName(firstName + " " + lastName)
                    .setDescription("Payment service customer")
                    .build();
            Customer customer = Customer.create(params);
            return customer.getId();
        } catch (StripeException e) {
            log.warn("Could not create Stripe customer for {}: {}", email, e.getMessage());
            return null;
        }
    }
}
