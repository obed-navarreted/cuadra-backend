package com.cuadra.api.tenancy;

import java.util.EnumSet;
import java.util.Set;

/** Roles del negocio y la matriz de permisos (sección 3.2 del plan). Un solo lugar, probado por celda. */
public enum Role {
    /** Todo, y lo único que solo él puede: el código del negocio, el plan, eliminarlo y traspasarlo. Nadie más puede modificarlo a él. */
    OWNER(EnumSet.allOf(Permission.class)),
    /** Gestiona a TODAS las personas menos al dueño (cajeros y otros admins: crear, invitar, cambiar datos y PIN, dar de baja). */
    ADMIN(EnumSet.of(
            Permission.VIEW_MEMBERS, Permission.MANAGE_CASHIERS,
            Permission.MANAGE_DEVICES, Permission.SELL, Permission.VIEW_REPORTS, Permission.MANAGE_CATALOG, Permission.MANAGE_ADMINS,
            Permission.PROGRAM_NOTIFICATIONS, Permission.VIEW_SETTINGS, Permission.EDIT_BUSINESS, Permission.EDIT_PRODUCTS, Permission.EDIT_SALES, Permission.MANAGE_CREDIT, Permission.MANAGE_EXPENSES, Permission.MANAGE_STOCK)),
    CASHIER(EnumSet.of(Permission.VIEW_MEMBERS, Permission.SELL, Permission.VIEW_SETTINGS, Permission.EDIT_PRODUCTS));

    public enum Permission {
        VIEW_MEMBERS,
        VIEW_SETTINGS,
        SELL,
        /** Agregar y modificar productos y precios (cualquier rol que atiende la caja). Dar de baja un producto exige MANAGE_CATALOG. Todo cambio queda en el historial del producto. */
        EDIT_PRODUCTS,
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
        MANAGE_DEVICES,
        PROGRAM_NOTIFICATIONS,
        /** Ajustes del negocio (nombre, módulos, cobro en caja, reglas, notificaciones...). Dueño y admin. */
        EDIT_BUSINESS,
        /** Código de acceso del negocio (renovar o elegir uno). Solo el dueño. */
        MANAGE_ACCESS_CODE,
        MANAGE_PLAN,
        DELETE_BUSINESS,
        TRANSFER_OWNERSHIP
    }

    /** Jerarquía: dueño > admin > cajero (para comparar el poder de un teléfono con el de la persona que actúa). */
    public int rank() {
        return switch (this) { case OWNER -> 3; case ADMIN -> 2; case CASHIER -> 1; };
    }

    public boolean atMost(Role limit) { return rank() <= limit.rank(); }

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    public boolean can(Permission permission) {
        return permissions.contains(permission);
    }
}
