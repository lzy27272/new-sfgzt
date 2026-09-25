package cn.sifangguan.hotelaios.aireview;

import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@ConditionalOnProperty(prefix="app.ai-evidence",name="enabled",havingValue="true")
class AiEvidenceScheduling {
    @Bean
    ThreadPoolTaskScheduler aiEvidenceScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ai-evidence-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
