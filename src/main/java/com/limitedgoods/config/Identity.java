package com.limitedgoods.config;
public final class Identity {
    private Identity() {}
    // Local test identity, not production authentication.
    public static void validate(String user, String key) {
        if (user == null || user.isBlank() || user.length() > 100 ||
            key == null || key.isBlank() || key.length() > 100) throw new ApiError(400, "INVALID_IDENTITY_OR_KEY");
    }
}
