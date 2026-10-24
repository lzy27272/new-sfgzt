package cn.sifangguan.hotelaios.finance;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
@Configuration(proxyBeanMethods=false) @EnableScheduling
@ConditionalOnProperty(prefix="app.finance-accrual",name="scheduler-enabled",havingValue="true")
public class FinanceAccrualSchedulingConfiguration {}
