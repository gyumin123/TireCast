package com.tirecast.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 화면 라우팅 전담 컨트롤러 (SSR - Thymeleaf)
 * SCR-LOG-001  : 로그인
 * SCR-JOIN-001 : 회원가입
 * SCR-MOB-001  : 모바일 타이어 촬영 (로그인 필요)
 * SCR-DASH-001 : 웹 대시보드 (로그인 필요)
 */
@Controller
public class PageController {

    @GetMapping("/")
    public String login(HttpSession session) {
        // 이미 로그인된 상태면 촬영 화면으로
        if (session.getAttribute(AuthController.SESSION_USER_ID) != null) return "redirect:/mobile";
        return "login";
    }

    @GetMapping("/join")
    public String join() {
        return "join";
    }

    @GetMapping("/mobile")
    public String mobile(HttpSession session) {
        if (session.getAttribute(AuthController.SESSION_USER_ID) == null) return "redirect:/";
        return "mobile";
    }

    @GetMapping("/dashboard")
    public String dashboard(HttpSession session) {
        if (session.getAttribute(AuthController.SESSION_USER_ID) == null) return "redirect:/";
        return "dashboard";
    }
}
