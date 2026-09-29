package com.cuadra.api.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AppConfig {

    /** Reloj inyectable: las reglas de jornada, turnos y notificaciones se prueban con un reloj simulado. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Hash lento para los PIN de los cajeros. */
    @Bean
    public PasswordEncoder pinEncoder(CuadraProperties props) {
        return new BCryptPasswordEncoder(props.pinBcryptStrength());
    }
}
