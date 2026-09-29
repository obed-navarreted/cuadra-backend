package com.cuadra.api.tenancy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Al entregar una conexión durante una petición de un negocio, la pone bajo el rol restringido `cuadra_app` con `app.business_id` fijado;
 * al devolverla la deja limpia (el pool reutiliza conexiones: una conexión que conservara el rol o el negocio filtraría datos a otra petición).
 */
public class TenantDataSource extends DelegatingDataSource {
    public TenantDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = super.getConnection();
        UUID business = TenantContext.current().orElse(null);
        if (business == null) return connection;
        try (Statement st = connection.createStatement()) {
            // El UUID ya es un UUID (no texto libre): no hay nada que inyectar.
            st.execute("SET ROLE cuadra_app; SELECT set_config('app.business_id', '" + business + "', false)");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, new Restore(connection));
    }

    /** Un método `close()` para que Spring cierre el pool de abajo al apagar. */
    public void close() throws Exception {
        if (getTargetDataSource() instanceof AutoCloseable c) c.close();
    }

    private record Restore(Connection target) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            if (method.getName().equals("close") && (args == null || args.length == 0)) {
                try {
                    if (!target.isClosed()) {
                        if (!target.getAutoCommit()) target.rollback(); // una transacción sin terminar no puede ocultar el reinicio del rol
                        try (Statement st = target.createStatement()) {
                            st.execute("RESET ROLE; RESET app.business_id");
                        }
                    }
                } catch (SQLException e) {
                    // Una conexión que no se pudo limpiar no vuelve al pool.
                    try { target.unwrap(java.sql.Connection.class).abort(Runnable::run); } catch (SQLException | RuntimeException ignored) { /* ya cerrada */ }
                    throw e;
                }
                target.close();
                return null;
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
