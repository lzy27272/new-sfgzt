package cn.sifangguan.hotelaios.dataretention;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(prefix="app.data-retention",name="cleanup-worker-enabled",havingValue="true")
public class ImageRetentionSchedulingConfiguration { }
