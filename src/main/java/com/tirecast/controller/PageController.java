package com.tirecast.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 화면 라우팅 전담 컨트롤러 (SSR - Thymeleaf)
 * SCR-LOG-001  : 로그인
 * SCR-JOIN-001 : 회원가입
 * SCR-MOB-001  : 모바일 타이어 촬영
 * SCR-DASH-001 : 웹 대시보드 (인사이트 결과)
 */
@Controller
public class PageController {

    @GetMapping("/")
    public String login() {
        return "login";
    }

    @GetMapping("/join")
    public String join() {
        return "join";
    }

    @GetMapping("/mobile")
    public String mobile() {
        return "mobile";
    }

    @GetMapping("/dashboard")
    public String dashboard() {
        return "dashboard";
    }
}
