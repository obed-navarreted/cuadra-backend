package com.cuadra.api.security;

public record GoogleIdentity(String sub, String email, boolean emailVerified, String name, String picture) {}
