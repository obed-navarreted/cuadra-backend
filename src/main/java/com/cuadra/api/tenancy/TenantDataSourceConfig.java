package com.cuadra.api.tenancy;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;

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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TenantDataSourceConfig.class);

    /**
     * Falla el arranque si el aislamiento no protege con el rol EFECTIVO de las peticiones (ver `RlsSelfCheck`): mejor no arrancar que arrancar sin aislamiento.
     * Deja en el registro con qué rol conecta la API; que sea superusuario es esperado (cada petición de un negocio baja a `cuadra_app`), pero se ve.
     */
    @Bean
    ApplicationRunner rlsStartupCheck(JdbcClient jdbc, @Value("${cuadra.rls.enabled:true}") boolean enabled) {
        return args -> {
            if (!enabled) {
                log.warn("Aislamiento por negocio (RLS) APAGADO (cuadra.rls.enabled=false): solo para desarrollo.");
                return;
            }
            log.info("Base de datos: la API conecta como {}; las peticiones de un negocio bajan al rol {}.", RlsSelfCheck.connectionSummary(jdbc), RlsSelfCheck.APP_ROLE);
            List<String> problems = RlsSelfCheck.problems(jdbc);
            if (!problems.isEmpty()) {
                throw new IllegalStateException("El aislamiento por negocio (RLS) no está activo: " + String.join("; ", problems));
            }
        };
    }
}
