package cn.adc.dental;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Read-only staff visitors can inspect business data but cannot mutate it. */
@Configuration
class VisitorAccessGate implements HandlerInterceptor, WebMvcConfigurer {
    private final Identity identity;
    VisitorAccessGate(Identity identity){this.identity=identity;}
    @Override public void addInterceptors(InterceptorRegistry registry){
        registry.addInterceptor(this).addPathPatterns("/api/v1/**")
            .excludePathPatterns("/api/v1/catalog/**","/api/v1/staff/sessions");
    }
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){
        String header=request.getHeader("Authorization");
        // Native mini-program guest sessions use a separate authentication service.
        if(!identity.visitorSession(header))return true;
        var actor=identity.require(header);
        boolean read=java.util.Set.of("GET","HEAD","OPTIONS").contains(request.getMethod());
        boolean logout=request.getRequestURI().equals("/api/v1/sessions/current")&&request.getMethod().equals("DELETE");
        if(actor.readOnly()&&!read&&!logout)throw new ApiError(403,"READ_ONLY","访客账号仅可查看，不能新增、修改或删除数据");
        return true;
    }
}
