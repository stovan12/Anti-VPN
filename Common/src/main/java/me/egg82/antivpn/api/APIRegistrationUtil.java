package me.egg82.antivpn.api;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// https://github.com/lucko/LuckPerms/blob/master/common/src/main/java/me/lucko/luckperms/common/api/ApiRegistrationUtil.java
public class APIRegistrationUtil {
    private static final Logger logger = LoggerFactory.getLogger(APIRegistrationUtil.class);

    private static final Method REGISTER;
    private static final Method DEREGISTER;

    static {
        try {
            REGISTER = VPNAPIProvider.class.getDeclaredMethod("register", VPNAPI.class);
            REGISTER.setAccessible(true);

            DEREGISTER = VPNAPIProvider.class.getDeclaredMethod("deregister");
            DEREGISTER.setAccessible(true);
        } catch (NoSuchMethodException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private APIRegistrationUtil() { }

    public static void register(@NonNull VPNAPI api) {
        try {
            REGISTER.invoke(null, api);
        } catch (InvocationTargetException | IllegalAccessException ex) {
            logger.error(ex.getMessage(), ex);
        }
    }

    public static void deregister() {
        try {
            DEREGISTER.invoke(null);
        } catch (InvocationTargetException | IllegalAccessException ex) {
            logger.error(ex.getMessage(), ex);
        }
    }
}
