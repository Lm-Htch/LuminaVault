package com.lumi.vault.config;

import com.lumi.vault.business.agent.config.AiProperties;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableConfigurationProperties(AiProperties.class)
public class AgentConfig {

    /**
     * 资产增强专用线程池。
     *
     * <p>增强链路会同时打多模态模型与本地模型服务，一次「补全未分析」可能排上百个资产。
     * 用默认的 applicationTaskExecutor 会把并发推到 8 并瞬间打满对端限额；这里限死 2 个并发、
     * 队列兜住积压，宁可慢一点也不要雪崩。</p>
     */
    @Bean(name = "enrichmentExecutor")
    public ThreadPoolTaskExecutor enrichmentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("enrich-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
