package io.github.iwinties.herald;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.time.ZonedDateTime;
import java.util.Date;
import javax.security.auth.x500.X500Principal;

final class AndroidTls {
    private static final String ALIAS = "herald-tls";
    private static final String STORE = "AndroidKeyStore";

    static synchronized TlsIdentity identity(Context context, String deviceId) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(STORE);
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            ZonedDateTime now = ZonedDateTime.now();
            BigInteger serial = new BigInteger(63, new SecureRandom()).setBit(62);
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE);
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                            KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(new X500Principal("CN=Herald " + deviceId.substring(0, 8)))
                    .setCertificateSerialNumber(serial)
                    .setCertificateNotBefore(Date.from(now.minusDays(1).toInstant()))
                    .setCertificateNotAfter(Date.from(now.plusYears(25).toInstant()))
                    .build());
            generator.generateKeyPair();
        }
        return new TlsIdentity(store, ALIAS, null);
    }
}
