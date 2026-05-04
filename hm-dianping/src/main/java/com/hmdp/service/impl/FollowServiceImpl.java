package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    private final StringRedisTemplate stringRedisTemplate;
    private final IUserService userService;

    public FollowServiceImpl(StringRedisTemplate stringRedisTemplate, IUserService userService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.userService = userService;
    }

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            log.warn("用户未登录");
            return Result.fail("用户未登录");
        }
        Long userId = user.getId();
        if (isFollow) {
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            if (save(follow)) {
                stringRedisTemplate.opsForSet().add(RedisConstants.USER_FOLLOW_KEY + userId, followUserId.toString());
            }
        } else {
            if (remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId))) {
                stringRedisTemplate.opsForSet().remove(RedisConstants.USER_FOLLOW_KEY + userId, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            log.warn("用户未登录");
            return Result.fail("用户未登录");
        }
        Long userId = user.getId();
        Integer count = query().eq("user_id", userId)
                .eq("follow_user_id", followUserId)
                .count();
        return Result.ok(count > 0);
    }

    @Override
    public Result getCommon(Long id) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            log.warn("用户未登录");
            return Result.ok();
        }
        Long userId = user.getId();
        Set<String> commonFollow = stringRedisTemplate.opsForSet().intersect(RedisConstants.USER_FOLLOW_KEY + userId, RedisConstants.USER_FOLLOW_KEY + id);
        if (commonFollow == null || commonFollow.isEmpty()) {
            return Result.ok();
        }
        List<Long> ids = commonFollow.stream().map(Long::parseLong).collect(Collectors.toList());
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(theUser -> BeanUtil.copyProperties(theUser, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
