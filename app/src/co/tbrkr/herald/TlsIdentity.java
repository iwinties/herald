package co.tbrkr.herald;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;

final class TlsIdentity {
    private final SSLContext context;
    private final String fingerprint;
    private final String pin;

    TlsIdentity(KeyStore store, String alias, char[] password) throws GeneralSecurityException {
        if (!store.isKeyEntry(alias)) throw new GeneralSecurityException("TLS key unavailable");
        X509Certificate cert = (X509Certificate) store.getCertificate(alias);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        StringBuilder hex = new StringBuilder(64);
        for (byte b : sha.digest(cert.getEncoded())) hex.append(String.format(Locale.ROOT, "%02x", b));
        fingerprint = hex.toString();
        pin = Base64.getEncoder().encodeToString(sha.digest(cert.getPublicKey().getEncoded()));

        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, password);
        context = SSLContext.getInstance("TLS");
        for (KeyManager manager : factory.getKeyManagers()) {
            if (manager instanceof X509KeyManager) {
                context.init(new KeyManager[]{new ServerKey(cert, (X509KeyManager) manager)}, null, null);
                return;
            }
        }
        throw new GeneralSecurityException("TLS key manager unavailable");
    }

    // A keystore can contain other keys. Only offer the chain whose leaf we advertised.
    private static final class ServerKey extends X509ExtendedKeyManager {
        final X509KeyManager keys;
        final X509Certificate leaf;
        ServerKey(X509Certificate leaf, X509KeyManager keys) {
            this.keys = keys;
            this.leaf = leaf;
        }
        @Override public String[] getServerAliases(String type, Principal[] issuers) {
            String[] candidates = keys.getServerAliases(type, issuers);
            if (candidates != null) for (String candidate : candidates) {
                X509Certificate[] chain = keys.getCertificateChain(candidate);
                if (chain != null && chain.length > 0 && leaf.equals(chain[0])) return new String[]{candidate};
            }
            return null;
        }
        @Override public String chooseServerAlias(String type, Principal[] issuers, Socket socket) {
            String[] aliases = getServerAliases(type, issuers);
            return aliases == null ? null : aliases[0];
        }
        @Override public String chooseEngineServerAlias(String type, Principal[] issuers, javax.net.ssl.SSLEngine engine) {
            return chooseServerAlias(type, issuers, null);
        }
        @Override public X509Certificate[] getCertificateChain(String alias) { return keys.getCertificateChain(alias); }
        @Override public PrivateKey getPrivateKey(String alias) { return keys.getPrivateKey(alias); }
        @Override public String[] getClientAliases(String type, Principal[] issuers) { return null; }
        @Override public String chooseClientAlias(String[] types, Principal[] issuers, Socket socket) { return null; }
    }

    SSLContext sslContext() { return context; }
    String fingerprint() { return fingerprint; }
    String pin() { return pin; }

    static String grouped(String fingerprint) {
        String s = fingerprint.substring(0, 16).toUpperCase(Locale.ROOT);
        return String.join(" ", s.substring(0, 4), s.substring(4, 8), s.substring(8, 12), s.substring(12, 16));
    }

    static void configure(SSLServerSocket socket) {
        List<String> versions = new ArrayList<>(Arrays.asList("TLSv1.3", "TLSv1.2"));
        versions.retainAll(Arrays.asList(socket.getSupportedProtocols()));
        socket.setEnabledProtocols(versions.toArray(new String[0]));
        socket.setNeedClientAuth(false);
    }

    static boolean handshake(SSLSocket socket, AtomicInteger failures) throws IOException {
        try {
            socket.startHandshake();
            return true;
        } catch (SSLException | SocketTimeoutException e) {
            failures.incrementAndGet();
            return false;
        }
    }
}
