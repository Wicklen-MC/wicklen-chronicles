package dev.wicklen.chronicles.smoke;

import java.lang.reflect.Field;
import java.util.Set;

/** Optional integration fixture assertions; never bundled in the released mod. */
public final class CombatProfileChecks {
    private static final Set<String> ENABLED = Set.of("general.pathPassengerAware", "skeletons.general.fleeGoal", "skeletons.general.swimGoal");

    public static void verify() {
        try {
            Object config = Class.forName("com.github.galatynf.sihywtcamd.config.ModConfig").getMethod("get").invoke(null);
            inspect(config, "");
            Class<?> gateway = Class.forName("de.mrstilu2k6.gateway_of_doom.config.GatewayConfig");
            Object settings = gateway.getMethod("getInstance").invoke(null);
            Object automatic = gateway.getMethod("getAutomaticGateways").invoke(settings);
            require(Boolean.FALSE.equals(automatic.getClass().getMethod("isEnabled").invoke(automatic)),
                "Gateway automatic events must be disabled in the managed profile");
            System.out.println("Combat profile PASS: selected SIH behaviour only; Gateway automatic events disabled.");
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Optional combat profile API mismatch", failure); }
    }

    private static void inspect(Object object, String prefix) throws ReflectiveOperationException {
        for (Field field : object.getClass().getFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            String key = prefix + field.getName();
            if (field.getType() == boolean.class) require(field.getBoolean(object) == ENABLED.contains(key), "Unexpected SIH switch: " + key);
            else if (field.getType().getName().startsWith("com.github.galatynf.sihywtcamd.config.")) inspect(field.get(object), key + ".");
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private CombatProfileChecks() {}
}
