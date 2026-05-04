package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        log.debug("发送验证码请求:{}", phone);
        //校验手机号合法性
        if (RegexUtils.isPhoneInvalid(phone)) {
            //不符合返回错误信息

            return Result.fail("无效手机号");
        }
        //生成验证码
        String code = RandomUtil.randomNumbers(6);
        //保存验证码 到redis
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        //返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        log.debug("登录请求:{}", loginForm);
        //校验手机号合法性
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("无效手机号");
        }
        //校验验证码
        if (RegexUtils.isCodeInvalid(loginForm.getCode())) {
            return Result.fail("无效验证码");
        }
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + loginForm.getPhone());
        if (cacheCode == null || !cacheCode.equals(loginForm.getCode())) {
            return Result.fail("验证码错误");
        }
        session.removeAttribute("code");
        stringRedisTemplate.delete(RedisConstants.LOGIN_CODE_KEY + loginForm.getPhone());
        //根据手机号查询用户
        String phone = loginForm.getPhone();
        User user = query().eq("phone", phone).one();
        if (user == null) {
            //若不存在则创建用户
            user = createUserWithPhone(phone);
            save(user);
        }
        //保存登录用户信息到redis
        //1.生成token
        String token = UUID.randomUUID().toString(true);
        //2.将user转为json
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );
        //3.保存到redis hash
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        //返回token到前端
        return Result.ok(token);
    }

    @Override
    public Result logout(String token) {
        log.debug("退出登录，login:token:{}", token);
        if (token == null || token.isEmpty()) {
            return Result.fail("未登录");
        }
        // 删除Redis中的用户登录信息
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        if (stringRedisTemplate.delete(tokenKey)) {
            log.debug("已删除login:token:{}", tokenKey);
        } else {
            log.debug("未找到login:token:{}", tokenKey);
        }
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

        return user;
    }
}
