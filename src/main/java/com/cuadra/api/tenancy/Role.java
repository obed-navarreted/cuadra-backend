package com.cuadra.api.tenancy;

import java.util.EnumSet;
import java.util.Set;

/** Roles del negocio y la matriz de permisos (sección 3.2 del plan). Un solo lugar, probado por celda. */
public enum Role {
    OWNER(EnumSet.allOf(Permission.class)),
    ADMIN(EnumSet.of(
            Permission.VIEW_MEMBERS, Permission.MANAGE_CASHIERS, Permission.INVITE_CASHIER,
            Permission.MANAGE_DEVICES, Permission.SELL, Permission.VIEW_REPORTS, Permission.MANAGE_CATALOG,
            Permission.PROGRAM_NOTIFICATIONS, Permission.VIEW_SETTINGS, Permission.EDIT_SALES, Permission.MANAGE_CREDIT, Permission.MANAGE_EXPENSES, Permission.MANAGE_STOCK)),
    CASHIER(EnumSet.of(Permission.VIEW_MEMBERS, Permission.SELL, Permission.VIEW_SETTINGS));

    public enum Permission {
        VIEW_MEMBERS,
        VIEW_SETTINGS,
        SELL,
        EDIT_SALES,
        /** Condonar deudas, anular abonos, editar límites de crédito y plantillas de mensajes. */
        MANAGE_CREDIT,
        /** Gastos que no salen del cajón, retiros, anular gastos y movimientos, y forzar un cierre. */
        MANAGE_EXPENSES,
        REOPEN_SHIFT,
        /** Contar, ajustar y dar de baja existencias; registrar compras, proveedores y sus pagos. */
        MANAGE_STOCK,
        VIEW_REPORTS,
        MANAGE_CATALOG,
        MANAGE_CASHIERS,
        MANAGE_ADMINS,
        INVITE_CASHIER,
        INVITE_ADMIN,
        MANAGE_DEVICES,
        PROGRAM_NOTIFICATIONS,
        EDIT_BUSINESS,
        MANAGE_PLAN,
        DELETE_BUSINESS,
        TRANSFER_OWNERSHIP
    }

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    public boolean can(Permission permission) {
        return permissions.contains(permission);
    }
}
