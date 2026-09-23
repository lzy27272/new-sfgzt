package cn.sifangguan.hotelaios.storeassets;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Procurement must not depend on an unrelated WeCom/retention worker enabling scheduling. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.procurement", name = "scheduler-enabled", havingValue = "true")
public class ProcurementSchedulingConfiguration { }
