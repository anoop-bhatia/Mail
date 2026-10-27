package in.anoopbhatia.mail;

/** Pure decisions used by the encryption defaults and automatic message actions. */
final class EncryptionTogglePolicy {
    private EncryptionTogglePolicy() {
    }

    static Integer resolveComposeMode(Integer automaticMode, Integer currentMode,
                                      boolean identitySelected,
                                      boolean encryptDefault, boolean identityEncryptDefault,
                                      boolean signDefault, boolean identitySignDefault,
                                      Integer signAndEncryptMode, Integer signOnlyMode) {
        if (automaticMode != null)
            return automaticMode;

        if (identitySelected || currentMode == null) {
            if (encryptDefault || identityEncryptDefault)
                return signAndEncryptMode;
            if (signDefault || identitySignDefault)
                return signOnlyMode;
            if (identitySelected)
                return null;
        }

        return currentMode;
    }

    static boolean shouldAutoDecrypt(Integer messageMode, boolean inlineEncrypted,
                                     Integer pgpSignAndEncryptMode, Integer smimeSignAndEncryptMode,
                                     Integer pgpEncryptOnlyMode, Integer smimeEncryptOnlyMode) {
        return inlineEncrypted || equals(messageMode, pgpSignAndEncryptMode) ||
                equals(messageMode, smimeSignAndEncryptMode) ||
                equals(messageMode, pgpEncryptOnlyMode) ||
                equals(messageMode, smimeEncryptOnlyMode);
    }

    static boolean shouldAutoVerify(Integer messageMode,
                                    Integer pgpSignOnlyMode, Integer smimeSignOnlyMode) {
        return equals(messageMode, pgpSignOnlyMode) || equals(messageMode, smimeSignOnlyMode);
    }

    static boolean isSmimeEncryptedMode(Integer messageMode,
                                        Integer smimeSignAndEncryptMode, Integer smimeEncryptOnlyMode) {
        return equals(messageMode, smimeSignAndEncryptMode) || equals(messageMode, smimeEncryptOnlyMode);
    }

    static boolean shouldClearAutoAttempt(boolean automatic, boolean completed) {
        return automatic && !completed;
    }

    private static boolean equals(Integer value, Integer expected) {
        return value != null && value.equals(expected);
    }
}
