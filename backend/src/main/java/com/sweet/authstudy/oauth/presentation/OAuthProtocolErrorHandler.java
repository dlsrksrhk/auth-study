package com.sweet.authstudy.oauth.presentation;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public final class OAuthProtocolErrorHandler {

    private static final String GENERIC_BROWSER_ERROR =
            "요청을 처리할 수 없습니다. 로그인을 다시 시작해 주세요.";

    public void renderLocal(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.resetBuffer();
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        request.setAttribute("message", GENERIC_BROWSER_ERROR);
        request.getRequestDispatcher("/idp/error").forward(request, response);
    }
}
