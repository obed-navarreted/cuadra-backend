package com.cuadra.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.cuadra.api.tenancy.Role;
import com.cuadra.api.tenancy.Role.Permission;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** La matriz de la sección 3.2 del plan, celda por celda. */
class RolePermissionTest {

    @Test
    void ownerCanDoEverything() {
        for (Permission p : Permission.values()) assertThat(Role.OWNER.can(p)).as("OWNER %s", p).isTrue();
    }

    @Test
    void adminManagesEveryoneExceptTheOwnerAndNeverTheBusiness() {
        Set<Permission> allowed = EnumSet.of(Permission.VIEW_MEMBERS, Permission.MANAGE_CASHIERS,
                Permission.MANAGE_DEVICES, Permission.SELL, Permission.VIEW_REPORTS, Permission.MANAGE_CATALOG, Permission.MANAGE_ADMINS,
                Permission.PROGRAM_NOTIFICATIONS, Permission.VIEW_SETTINGS, Permission.EDIT_PRODUCTS, Permission.EDIT_SALES, Permission.MANAGE_CREDIT, Permission.MANAGE_EXPENSES, Permission.MANAGE_STOCK);
        for (Permission p : Permission.values()) assertThat(Role.ADMIN.can(p)).as("ADMIN %s", p).isEqualTo(allowed.contains(p));
        assertThat(Role.ADMIN.can(Permission.MANAGE_ADMINS)).isTrue();
        assertThat(Role.ADMIN.can(Permission.EDIT_BUSINESS)).isFalse();
        assertThat(Role.ADMIN.can(Permission.TRANSFER_OWNERSHIP)).isFalse();
    }

    @Test
    void cashierSellsViewsAndEditsProductsButNeverDeletesThem() {
        Set<Permission> allowed = EnumSet.of(Permission.VIEW_MEMBERS, Permission.SELL, Permission.VIEW_SETTINGS, Permission.EDIT_PRODUCTS);
        for (Permission p : Permission.values()) assertThat(Role.CASHIER.can(p)).as("CASHIER %s", p).isEqualTo(allowed.contains(p));
    }
}
