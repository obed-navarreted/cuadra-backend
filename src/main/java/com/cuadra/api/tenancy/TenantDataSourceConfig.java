package com.cuadra.api.tenancy;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Value;
import java.util.UUID;

/** Pone `TenantDataSource` sobre el pool y verifica al arrancar que el aislamiento realmente está activo. `cuadra.rls.enabled=false` lo apaga. */
@Configuration
public class TenantDataSourceConfig {
    @Bean
    static BeanPostProcessor tenantDataSourcePostProcessor(@Value("${cuadra.rls.enabled:true}") boolean enabled) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                return enabled && bean instanceof DataSource ds && !(bean instanceof TenantDataSource) ? new TenantDataSource(ds) : bean;
            }
        };
    }

    /** Falla el arranque si el rol restringido no se puede asumir o no restringe (mejor no arrancar que arrancar sin aislamiento). */
    @Bean
    ApplicationRunner rlsStartupCheck(JdbcClient jdbc, @Value("${cuadra.rls.enabled:true}") boolean enabled) {
        return args -> {
            if (!enabled) return;
            UUID probe = UUID.randomUUID();
            String user = TenantContext.call(probe, () -> jdbc.sql("SELECT current_user").query(String.class).single());
            long visible = TenantContext.call(probe, () -> jdbc.sql("SELECT count(*) FROM sale").query(Long.class).single());
            if (!"cuadra_app".equals(user) || visible != 0) {
                throw new IllegalStateException("El aislamiento por negocio (RLS) no está activo: usuario " + user + ", filas visibles " + visible);
            }
        };
    }
}
