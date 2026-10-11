package io.github.llm4j.loom.inspect.tls;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * How a process decides whether a server is genuine, and how a server gets a certificate. The process decides: it always verifies the
 * certificate (the host name in the URL must be in it), and never offers an insecure option. What it trusts is the default roots, plus
 * a private CA given as a PEM file (public, not secret) for this connection only, or one pinned server key (SHA-256 of the public key,
 * which survives certificate renewal as long as the key is reused).
 */
public final class Tls {

    private Tls() { }

    /** What {@code fabric tls init} wrote. */
    public record Created(Path caPem, Path serverKeystore, Path passwordFile, String pin) { }

    // ------------------------------------------------------------------------------ the process side

    /**
     * The trust a process uses toward its server.
     *
     * @param ca a PEM file of CA certificates, or null
     * @param caOnly trust only {@code ca}, not the default roots
     * @param pinHex lower-case hex SHA-256 of the server's public key, or null
     */
    public static SSLContext clientContext(Path ca, boolean caOnly, String pinHex) {
        try {
            X509TrustManager trust;
            if (pinHex != null) {
                trust = new Pinned(pinHex);
            } else if (ca != null) {
                X509TrustManager given = managerFor(loadCertificates(ca));
                trust = caOnly ? given : new Either(given, defaultManager());
            } else {
                trust = defaultManager();
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {trust}, null);
            return ctx;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException("cannot set up trust for the server: " + e.getMessage(), e);
        }
    }

    private static X509TrustManager defaultManager() throws GeneralSecurityException {
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init((KeyStore) null);
        return pick(f);
    }

    private static X509TrustManager managerFor(Collection<? extends Certificate> certs) throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        int i = 0;
        for (Certificate c : certs) ks.setCertificateEntry("ca" + i++, c);
        if (i == 0) throw new CertificateException("the CA file holds no certificate");
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init(ks);
        return pick(f);
    }

    private static X509TrustManager pick(TrustManagerFactory f) throws GeneralSecurityException {
        for (TrustManager m : f.getTrustManagers()) if (m instanceof X509TrustManager x) return x;
        throw new GeneralSecurityException("no X509 trust manager");
    }

    private static List<X509Certificate> loadCertificates(Path pem) throws IOException, CertificateException {
        try (InputStream in = Files.newInputStream(pem)) {
            List<X509Certificate> out = new ArrayList<>();
            for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) out.add((X509Certificate) c);
            return out;
        }
    }

    /** Hex SHA-256 of a certificate's public key (SPKI), the value to give {@code --observe-pin}. */
    public static String pin(X509Certificate cert) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getPublicKey().getEncoded()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Trusts either of two managers. Host name checking is still done by the TLS layer. */
    private record Either(X509TrustManager first, X509TrustManager second) implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException {
            throw new CertificateException("client certificates are not used");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
            try {
                first.checkServerTrusted(chain, type);
            } catch (CertificateException e) {
                second.checkServerTrusted(chain, type);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<>(List.of(first.getAcceptedIssuers()));
            all.addAll(List.of(second.getAcceptedIssuers()));
            return all.toArray(new X509Certificate[0]);
        }
    }

    /** Accepts only the server whose public key hashes to the pin. Names and chains do not matter, the key does. */
    private record Pinned(String pinHex) implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException {
            throw new CertificateException("client certificates are not used");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
            if (chain == null || chain.length == 0 || !MessageDigest.isEqual(pin(chain[0]).getBytes(StandardCharsets.US_ASCII), pinHex.getBytes(StandardCharsets.US_ASCII))) {
                throw new CertificateException("the server's key does not match --observe-pin");
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    // ------------------------------------------------------------------------------ the server side

    /** A server context from a PKCS12 keystore. */
    public static SSLContext serverContext(Path keystore, char[] password) {
        try (InputStream in = Files.newInputStream(keystore)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, password);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException("cannot use the keystore " + keystore + ": " + e.getMessage(), e);
        }
    }

    /** A server context from a PEM certificate chain and a PKCS#8 PEM private key ({@code BEGIN PRIVATE KEY}). */
    public static SSLContext serverContextFromPem(Path certPem, Path keyPem) {
        try {
            List<X509Certificate> chain = loadCertificates(certPem);
            if (chain.isEmpty()) throw new IllegalArgumentException("no certificate in " + certPem);
            String text = Files.readString(keyPem);
            if (text.contains("BEGIN RSA PRIVATE KEY") || text.contains("BEGIN EC PRIVATE KEY")) {
                throw new IllegalArgumentException("the key must be PKCS#8 (BEGIN PRIVATE KEY); convert it with: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key8.pem");
            }
            String b64 = text.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(b64);
            PrivateKey key = null;
            for (String alg : new String[] {"RSA", "EC", "Ed25519"}) {
                try {
                    key = KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(der));
                    break;
                } catch (GeneralSecurityException ignored) {
                    // try the next algorithm
                }
            }
            if (key == null) throw new IllegalArgumentException("unsupported private key type in " + keyPem);
            char[] pw = new char[0];
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry("server", key, pw, chain.toArray(new Certificate[0]));
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pw);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException("cannot use the certificate and key: " + e.getMessage(), e);
        }
    }

    /** The public key pin of a keystore's server certificate, to print at start-up. */
    public static String pinOfKeystore(Path keystore, char[] password) {
        try (InputStream in = Files.newInputStream(keystore)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, password);
            return pin((X509Certificate) ks.getCertificate("server"));
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException("cannot read the keystore " + keystore + ": " + e.getMessage(), e);
        }
    }

    /** The pin of the first certificate in a PEM file. */
    public static String pinOfPem(Path certPem) {
        try {
            return pin(loadCertificates(certPem).get(0));
        } catch (IOException | CertificateException e) {
            throw new IllegalArgumentException("cannot read " + certPem + ": " + e.getMessage(), e);
        }
    }

    /**
     * Makes a small private CA and a server certificate for {@code host} (and any extra names or addresses), with the JDK's own
     * {@code keytool}. Writes {@code ca.pem} (hand it to processes with {@code --observe-ca}), {@code server.p12} and its password file.
     */
    public static Created createCa(Path dir, String host, List<String> alsoNames) {
        try {
            Files.createDirectories(dir);
            Path caKs = dir.resolve("ca.p12");
            Path serverKs = dir.resolve("server.p12");
            Path caPem = dir.resolve("ca.pem");
            Path pwFile = dir.resolve("server.password");
            if (Files.exists(serverKs) || Files.exists(caPem)) throw new IllegalStateException("there is already a CA or server certificate in " + dir + "; remove it first");
            byte[] raw = new byte[24];
            new SecureRandom().nextBytes(raw);
            String pw = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            Path csr = dir.resolve("server.csr");
            Path signed = dir.resolve("server.crt");
            Path caCrt = dir.resolve("ca.crt");
            StringBuilder san = new StringBuilder("san=dns:" + host);
            for (String n : alsoNames) san.append(isIp(n) ? ",ip:" : ",dns:").append(n);
            if (isIp(host)) san = new StringBuilder("san=ip:" + host + alsoNames.stream().map(n -> (isIp(n) ? ",ip:" : ",dns:") + n).reduce("", String::concat));
            keytool(dir, "-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=Loom Fabric CA", "-ext", "bc:c",
                    "-ext", "ku:c=keyCertSign,cRLSign", "-validity", "3650", "-keystore", caKs.toString(), "-storetype", "PKCS12", "-storepass", pw);
            keytool(dir, "-exportcert", "-rfc", "-alias", "ca", "-keystore", caKs.toString(), "-storepass", pw, "-file", caCrt.toString());
            keytool(dir, "-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + host, "-validity", "825",
                    "-keystore", serverKs.toString(), "-storetype", "PKCS12", "-storepass", pw, "-ext", san.toString());
            keytool(dir, "-certreq", "-alias", "server", "-keystore", serverKs.toString(), "-storepass", pw, "-file", csr.toString(), "-ext", san.toString());
            keytool(dir, "-gencert", "-alias", "ca", "-keystore", caKs.toString(), "-storepass", pw, "-infile", csr.toString(), "-outfile", signed.toString(),
                    "-rfc", "-validity", "825", "-ext", san.toString(), "-ext", "eku=serverAuth");
            keytool(dir, "-importcert", "-noprompt", "-alias", "ca", "-file", caCrt.toString(), "-keystore", serverKs.toString(), "-storepass", pw);
            keytool(dir, "-importcert", "-noprompt", "-alias", "server", "-file", signed.toString(), "-keystore", serverKs.toString(), "-storepass", pw);
            Files.copy(caCrt, caPem);
            Files.writeString(pwFile, pw);
            for (Path p : List.of(caKs, serverKs, pwFile)) restrict(p);
            for (Path p : List.of(csr, signed, caCrt)) Files.deleteIfExists(p);
            return new Created(caPem, serverKs, pwFile, pinOfKeystore(serverKs, pw.toCharArray()));
        } catch (IOException e) {
            throw new IllegalStateException("cannot create the certificates: " + e.getMessage(), e);
        }
    }

    private static boolean isIp(String s) {
        if (!s.matches("[0-9.]+") && !s.contains(":")) return false;
        try {
            InetAddress.getByName(s);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static void restrict(Path p) throws IOException {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX file system
        }
    }

    private static void keytool(Path dir, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
                throw new IOException("keytool " + args[0] + " failed: " + out.strip());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }
}
