package com.tirecast.controller;

import com.tirecast.entity.User;
import com.tirecast.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 모듈 1: 회원 관리
 * F-USR-01 회원가입 / F-USR-02 로그인(세션) / F-USR-03 로그아웃
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public static final String SESSION_USER_ID = "userId";
    public static final String SESSION_NICKNAME = "nickname";

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public record RegisterRequest(String name, String email, String password) {}
    public record LoginRequest(String email, String password) {}

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        if (isBlank(req.name()) || isBlank(req.email()) || isBlank(req.password())) {
            return ResponseEntity.badRequest().body(Map.of("message", "모든 항목을 입력해주세요."));
        }
        if (req.password().length() < 8) {
            return ResponseEntity.badRequest().body(Map.of("message", "비밀번호는 8자 이상이어야 합니다."));
        }
        if (userRepository.existsByEmail(req.email())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "이미 사용 중인 이메일입니다."));
        }
        User user = new User(req.email(), encoder.encode(req.password()), req.name());
        userRepository.save(user);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("message", "가입이 완료되었습니다."));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req, HttpSession session) {
        return userRepository.findByEmail(req.email() == null ? "" : req.email())
                .filter(u -> encoder.matches(req.password() == null ? "" : req.password(), u.getPasswordHash()))
                .<ResponseEntity<?>>map(u -> {
                    session.setAttribute(SESSION_USER_ID, u.getUserId());
                    session.setAttribute(SESSION_NICKNAME, u.getNickname());
                    return ResponseEntity.ok(Map.of("nickname", u.getNickname()));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "이메일 또는 비밀번호가 올바르지 않습니다.")));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok(Map.of("message", "로그아웃되었습니다."));
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }
}
