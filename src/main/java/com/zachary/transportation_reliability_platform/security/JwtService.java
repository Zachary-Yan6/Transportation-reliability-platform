package com.zachary.transportation_reliability_platform.security;

import com.zachary.transportation_reliability_platform.entity.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/** Signs and validates compact JWT access tokens without creating an HTTP session. */
@Service
public class JwtService {

    private static final String ROLE_CLAIM = "role";

    private final SecurityProperties properties;
    private final SecretKey signingKey;

    public JwtService(SecurityProperties properties) {
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(
                Decoders.BASE64.decode(properties.jwtSecret())
        );
    }

    /** Creates a signed, expiring token that contains only the user identity and role. */
    public String issue(AppUser account) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(account.getEmail())
                .claim(ROLE_CLAIM, account.getRole())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(properties.jwtExpirationSeconds())))
                .signWith(signingKey)
                .compact();
    }

    /** Reads a token only when its signature, expiry, subject, and role are valid. */
    public Optional<JwtPrincipal> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String email = claims.getSubject();
            String role = claims.get(ROLE_CLAIM, String.class);
            if (email == null || email.isBlank()
                    || !("ADMIN".equals(role) || "USER".equals(role))) {
                return Optional.empty();
            }
            return Optional.of(new JwtPrincipal(email, role));
        } catch (JwtException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /** Extracts the expiry used by the HTTP login response. */
    public Instant getExpiry(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getExpiration()
                .toInstant();
    }
}
