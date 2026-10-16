package com.azhardev.tenderengine;

public class H2Functions {

    public static int hashtext(String text) {
        return text != null ? text.hashCode() : 0;
    }

    public static boolean pg_try_advisory_xact_lock(int key) {
        return true;
    }
}
