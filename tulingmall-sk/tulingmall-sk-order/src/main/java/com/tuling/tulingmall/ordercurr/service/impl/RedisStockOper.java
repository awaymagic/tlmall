package com.tuling.tulingmall.ordercurr.service.impl;

import com.tuling.tulingmall.common.constant.RedisKeyPrefixConst;
import com.tuling.tulingmall.rediscomm.util.RedisSingleUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
public class RedisStockOper {

    private final static String STOCK_LUA_NAME = "Redis扣减库存脚本";
    /*
        -- 调用Redis的get指令，查询活动库存，其中KEYS[1]为传入的参数1，即库存key
        local c_s = redis.call('get', KEYS[1])
        -- 判断活动库存是否充足，其中ARGV[1]为传入当前抢购数量
        if not c_s or tonumber(c_s) < tonumber(ARGV[1]) then
           return -1
        end
        -- 如果活动库存充足，则进行扣减操作。其中ARGV[1]为传入当前抢购数量
        redis.call('decrby',KEYS[1], ARGV[1])
    * */
    private final static String STOCK_LUA = "local c_s = redis.call('get', KEYS[1]);" +
            "if not c_s or tonumber(c_s) < tonumber(ARGV[1]) then " +
            "return -1 end " +
            "return redis.call('decrby',KEYS[1], ARGV[1])";
    @Autowired
    @Qualifier("redisSingleTemplate")
    private RedisTemplate redisTemplate;
    private AtomicBoolean isLoadScript = new AtomicBoolean(false);
    private DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();

    @PostConstruct
    public void loadScript(){
        if(isLoadScript.get()) return;
        redisScript.setScriptText(STOCK_LUA);
        redisScript.setResultType(Long.class);
        loadRedisScript(redisScript,STOCK_LUA_NAME);
        isLoadScript.set(true);
    }

    private void loadRedisScript(DefaultRedisScript<Long> redisScript, String luaName) {
        try {
            List<Boolean> results = redisTemplate.getConnectionFactory().getConnection()
                    .scriptExists(redisScript.getSha1());
            if (Boolean.FALSE.equals(results.get(0))) {
                String sha = redisTemplate.getConnectionFactory().getConnection()
                        .scriptLoad(redisScript.getScriptAsString().getBytes(StandardCharsets.UTF_8));
                log.info("预加载lua脚本成功：{}, sha=[{}]", luaName, sha);
            }
        } catch (Exception e) {
            log.error("预加载lua脚本异常：{}", luaName, e);
        }
    }
//TODO 这一部分手敲 先分析两次读取Redis案例，先读库存再减库存 -- 缺点：性能低，并发超卖
    // 1、分析分布式锁的缺点 setNX --  分析缺点，分布式锁不可靠，并发性能影响大
    // 2、现在这个lua脚本实现方式
    public boolean preDecrRedisStock(Long productId,Long reduceCount) {
        Long stock = (Long)redisTemplate.execute(redisScript,
                Collections.singletonList(RedisKeyPrefixConst.MIAOSHA_STOCK_CACHE_PREFIX + productId),reduceCount);
        if(stock < 0)
            return false;
        else
            return true;
    }

}
