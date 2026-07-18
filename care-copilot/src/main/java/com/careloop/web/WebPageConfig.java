package com.careloop.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebPageConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/nurse").setViewName("forward:/nurse/index.html");
        registry.addViewController("/nurse/").setViewName("forward:/nurse/index.html");
        registry.addViewController("/patient").setViewName("forward:/patient/index.html");
        registry.addViewController("/patient/").setViewName("forward:/patient/index.html");
        // 兼容旧链接
        registry.addViewController("/p/{token}").setViewName("forward:/patient/index.html");
    }
}
