package io.github.jerrt92.jrag.controller;

import io.github.jerrt92.jrag.model.LoginRequestDto;
import io.github.jerrt92.jrag.model.security.SessionBo;
import io.github.jerrt92.jrag.server.api.LoginApi;
import io.github.jerrt92.jrag.service.security.LoginService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@RestController
public class LoginController implements LoginApi {
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final LoginService loginService;

    public LoginController(HttpServletRequest request, HttpServletResponse response, LoginService loginService) {
        this.request = request;
        this.response = response;
        this.loginService = loginService;
    }

    @Override
    public ResponseEntity<Void> login(LoginRequestDto loginRequestDto) {
        SessionBo sessionBo = loginService.login(loginRequestDto.getUsername(), loginRequestDto.getPassword());
        if (sessionBo != null) {
            Cookie cookie = new Cookie("SESSION", sessionBo.getSessionId());
            cookie.setHttpOnly(true); // 阻止JavaScript 访问 Cookie
            cookie.setPath("/");      // 根据实际需求设置 path
            response.addCookie(cookie);
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Void> logout() {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals("SESSION")) {
                    if (cookie.getValue() != null) {
                        loginService.logout(cookie.getValue());
                    }
                    cookie.setPath("/");
                    cookie.setMaxAge(0);
                    response.addCookie(cookie);
                }
            }
        }
        return ResponseEntity.ok().build();
    }
}
