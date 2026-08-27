/*
 *  Copyright 2024 J2ME Loader contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ru.woesss.j2me.apk;

import com.android.apksig.ApkSigner;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Signs a port, with a key kept for as long as the ports it signed are installed.
 *
 * <p>Android will not install an unsigned APK, and it will not replace an installed app with
 * one signed by a different key. Those two facts together decide the design: the key has to
 * outlive any single export, or the first update to a port would have to uninstall it and
 * take the player's saved games with it. So it is generated once, written to a file, and
 * reused - which also means it can be backed up, or carried to another device.
 *
 * <p>The signing itself is done by {@code apksig}, the same code {@code apksigner} runs, using
 * the v2 scheme alone. v1 exists for Android before 7.0, which is below anything apksig can
 * run on in the first place - and apksig writes v1 with {@code java.util.Base64}, which
 * Android only gained in 8.0. So v2 is not a reduced choice here, it is the whole of what
 * these devices read.
 */
public final class PortSigner {
	/**
	 * What a port declares it needs, matching the template it is stamped into. apksig is told
	 * this rather than left to read it back, so that a template built for something older
	 * cannot quietly produce a port signed for a floor it does not meet.
	 */
	public static final int MIN_SDK = 30;

	private static final String KEY_ALIAS = "port";
	private static final String KEY_ALGORITHM = "RSA";
	private static final int KEY_SIZE = 2048;
	private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

	/** OID of sha256WithRSAEncryption, naming how the certificate is signed. */
	private static final String OID_SHA256_RSA = "1.2.840.113549.1.1.11";
	/** OID of the common name attribute, the only one the certificate carries. */
	private static final String OID_COMMON_NAME = "2.5.4.3";

	private static final long VALIDITY_MS = 30L * 365 * 24 * 60 * 60 * 1000;

	private final File keyStoreFile;
	private final char[] password;

	/**
	 * @param keyStoreFile where the signing key lives; created on first use
	 * @param password     protects the file; a local key guards against nothing, so this only
	 *                     has to be the same every time
	 */
	public PortSigner(File keyStoreFile, char[] password) {
		this.keyStoreFile = keyStoreFile;
		this.password = password;
	}

	/** Signs {@code unsigned} into {@code target}, which must be a different file. */
	public void sign(File unsigned, File target) throws IOException, GeneralSecurityException {
		KeyStore.PrivateKeyEntry entry = loadOrCreateKey();
		ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder(
				KEY_ALIAS,
				entry.getPrivateKey(),
				Collections.singletonList((X509Certificate) entry.getCertificate()))
				.build();
		try {
			new ApkSigner.Builder(Collections.singletonList(config))
					.setInputApk(unsigned)
					.setOutputApk(target)
					// v1 is the scheme for Android before 7.0, and apksig writes it using
					// java.util.Base64, which Android itself only gained in 8.0. Since nothing
					// signed here can run on 6.0 anyway, v2 alone is what these devices read.
					.setV1SigningEnabled(false)
					.setV2SigningEnabled(true)
					// Nothing here rotates keys, which is what v3 exists for, and its signer
					// is the part of apksig that reaches furthest past what old Android has.
					.setV3SigningEnabled(false)
					// Below this, apksig would insist on v1 to cover devices the port does
					// not claim to support anyway.
					.setMinSdkVersion(MIN_SDK)
					.build()
					.sign();
		} catch (Exception e) {
			if (e instanceof IOException) {
				throw (IOException) e;
			}
			throw new IOException("Can't sign " + unsigned.getName(), e);
		}
	}

	// --- the key -----------------------------------------------------------------------

	private KeyStore.PrivateKeyEntry loadOrCreateKey() throws IOException, GeneralSecurityException {
		KeyStore keyStore = KeyStore.getInstance("PKCS12");
		if (keyStoreFile.isFile()) {
			try (InputStream in = new FileInputStream(keyStoreFile)) {
				keyStore.load(in, password);
			}
			KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS,
					new KeyStore.PasswordProtection(password));
			if (entry instanceof KeyStore.PrivateKeyEntry) {
				return (KeyStore.PrivateKeyEntry) entry;
			}
			throw new IOException("No signing key in " + keyStoreFile);
		}

		KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
		generator.initialize(KEY_SIZE);
		KeyPair pair = generator.generateKeyPair();
		X509Certificate certificate = selfSign(pair, "J2ME Loader MIDlet Port");

		keyStore.load(null, password);
		keyStore.setKeyEntry(KEY_ALIAS, pair.getPrivate(), password,
				new Certificate[]{certificate});
		File parent = keyStoreFile.getAbsoluteFile().getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
			throw new IOException("Can't create directory: " + parent);
		}
		try (OutputStream out = new FileOutputStream(keyStoreFile)) {
			keyStore.store(out, password);
		}
		return new KeyStore.PrivateKeyEntry(pair.getPrivate(), new Certificate[]{certificate});
	}

	/**
	 * Builds a self-signed certificate for {@code pair}.
	 *
	 * <p>A certificate is a body, the algorithm that signed it, and the signature over that
	 * body. The body's public key field is already DER - that is what {@link
	 * java.security.PublicKey#getEncoded()} returns - so only the fields around it are
	 * assembled here.
	 */
	private static X509Certificate selfSign(KeyPair pair, String commonName)
			throws GeneralSecurityException {
		long now = System.currentTimeMillis();
		byte[] algorithm = Der.sequence(Der.objectId(OID_SHA256_RSA), Der.nul());
		byte[] name = Der.sequence(Der.tagged(Der.SET, Der.sequence(
				Der.objectId(OID_COMMON_NAME), Der.printableString(commonName))));

		byte[] body = Der.sequence(
				// Version 3, which is what the [0] wrapper and the value 2 together mean.
				Der.explicit(0, Der.integer(2)),
				Der.integer(new BigInteger(64, new SecureRandom()).abs()),
				algorithm,
				name,
				Der.sequence(Der.utcTime(utcTime(now)), Der.utcTime(utcTime(now + VALIDITY_MS))),
				// Self-signed: the issuer and the subject are the same name.
				name,
				pair.getPublic().getEncoded());

		Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
		signature.initSign(pair.getPrivate());
		signature.update(body);
		byte[] certificate = Der.sequence(body, algorithm, Der.bitString(signature.sign()));

		return (X509Certificate) CertificateFactory.getInstance("X.509")
				.generateCertificate(new ByteArrayInputStream(certificate));
	}

	/** {@code YYMMDDHHMMSSZ}, how X.509 writes a time that is this side of the year 2050. */
	private static String utcTime(long millis) {
		SimpleDateFormat format = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
		format.setTimeZone(TimeZone.getTimeZone("UTC"));
		return format.format(new Date(millis));
	}
}
