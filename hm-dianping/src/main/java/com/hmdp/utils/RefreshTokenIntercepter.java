package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenIntercepter implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    public RefreshTokenIntercepter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取请求头中的token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
            //未登录则放行
            return true;
        }
        //通过tokenKey从redis获取用户
        String tokenKey=RedisConstants.LOGIN_USER_KEY + token;
        Map<Object,Object>userMap=stringRedisTemplate.opsForHash().entries(tokenKey);
        //判断用户是否登录
        if (userMap.isEmpty()) {
            //未登录则放行
            return true;
        }
        //刷新登录时间
        stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //保存用户信息到当前线程ThreadLocal
        UserDTO userDTO= BeanUtil.fillBeanWithMap(userMap,new UserDTO(),false);
        UserHolder.saveUser(userDTO);
        //放行
        return true;
    }
}
