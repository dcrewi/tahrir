package tahrir.io.crypto;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import tahrir.TrConstants;
import tahrir.io.serialization.TrSerializableException;
import tahrir.io.serialization.TrSerializer;
import tahrir.tools.ByteArraySegment;
import tahrir.tools.ByteArraySegment.ByteArraySegmentBuilder;
import tahrir.tools.Tuple2;

import com.google.common.io.BaseEncoding;

/**
 * A simple implementation of the RSA algorithm
 * 
 * @author Ian Clarke <ian.clarke@gmail.com>
 */
public class TrCrypto {
	static SecureRandom sRand = new SecureRandom();

	static {
		Security.addProvider(new BouncyCastleProvider());
	}

	public static Tuple2<RSAPublicKey, RSAPrivateKey> createRsaKeyPair() {
		try {
			final KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
			keyGen.initialize(2048);
			final KeyPair key = keyGen.generateKeyPair();
			return Tuple2.of((RSAPublicKey) key.getPublic(), (RSAPrivateKey) key.getPrivate());
		} catch (final NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	public static TrSymKey createAesKey() {
		KeyGenerator kgen;
		try {
			kgen = KeyGenerator.getInstance("AES");
			kgen.init(128);
			final SecretKey skey = kgen.generateKey();
			return new TrSymKey(new ByteArraySegment(skey.getEncoded()));
		} catch (final NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	private static Cipher getRSACipher() {
		try {
			return Cipher.getInstance("RSA/None/NoPadding", "BC");
		} catch (final NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		} catch (final NoSuchProviderException e) {
			throw new RuntimeException(e);
		} catch (final NoSuchPaddingException e) {
			throw new RuntimeException(e);
		}
	}

	public static String toBase64(final RSAPublicKey pubKey) {
		return BaseEncoding.base64().encode(pubKey.getEncoded());
	}

	public static RSAPublicKey decodeBase64(final String base64String) {
		final byte[] bytes = BaseEncoding.base64().decode(base64String);
		try {
			return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
		} catch (final Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static TrSignature sign(final Object toSign, final RSAPrivateKey privKey) throws TrSerializableException {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(TrConstants.DEFAULT_BAOS_SIZE);
		final DataOutputStream dos = new DataOutputStream(baos);
		try {
			TrSerializer.serializeTo(toSign, dos);
			dos.flush();
			final Signature signature = Signature.getInstance("SHA256withRSA", "BC");
			signature.initSign(privKey);
			signature.update(baos.toByteArray());
			return new TrSignature(signature.sign());
		} catch (final Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static boolean verify(final TrSignature signature, final Object toVerify, final RSAPublicKey pubKey)
			throws TrSerializableException {
		// TODO: We serialize this object and then throw the result away, which
		// is probably wasteful as frequently the object will be serialized
		// elsewhere
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(TrConstants.DEFAULT_BAOS_SIZE);
		final DataOutputStream dos = new DataOutputStream(baos);
		try {
			TrSerializer.serializeTo(toVerify, dos);
			final Signature sig = Signature.getInstance("SHA256withRSA", "BC");
			sig.initVerify(pubKey);
			sig.update(baos.toByteArray());
			return sig.verify(signature.signature);
		} catch (final Exception e) {
			throw new RuntimeException(e);
		}

	}

	public static <T> TrPPKEncrypted<T> encrypt(final T plainText, final RSAPublicKey pubKey)
			throws TrSerializableException {
		// TODO: Lots of reading from and writing to byte arrays, inefficient
		final ByteArrayOutputStream serializedPlaintext = new ByteArrayOutputStream(TrConstants.DEFAULT_BAOS_SIZE);
		final ByteArraySegmentBuilder dos = ByteArraySegment.builder();
		try {
			TrSerializer.serializeTo(plainText, dos);
			dos.flush();
			final TrSymKey aesKey = createAesKey();
			final ByteArraySegment aesEncrypted = aesKey.encrypt(dos.build());
			final Cipher cipher = getRSACipher();
			cipher.init(Cipher.ENCRYPT_MODE, pubKey);
			final byte[] rsaEncryptedAesKey = cipher.doFinal(aesKey.toBytes());
			return new TrPPKEncrypted<T>(rsaEncryptedAesKey, aesEncrypted);
		} catch (final Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static ByteArraySegment encryptRaw(final ByteArraySegment pt, final RSAPublicKey pubKey) {
		final Cipher cipher = getRSACipher();
		try {
			cipher.init(Cipher.ENCRYPT_MODE, pubKey);
			return new ByteArraySegment(cipher.doFinal(pt.array, pt.offset, pt.length));
		} catch (final Exception e) {
			throw new RuntimeException(e);
		}
	}


	public static ByteArraySegment decryptRaw(final ByteArraySegment cipherText, final RSAPrivateKey privKey) {
		final Cipher cipher = getRSACipher();
		try {
			cipher.init(Cipher.DECRYPT_MODE, privKey);
			return new ByteArraySegment(cipher.doFinal(cipherText.array, cipherText.offset, cipherText.length));
		} catch (final Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static <T> T decrypt(final Class<T> c, final TrPPKEncrypted<T> cipherText, final RSAPrivateKey privKey) {
		final Cipher cipher = getRSACipher();
		try {
			cipher.init(Cipher.DECRYPT_MODE, privKey);
			final TrSymKey aesKey = new TrSymKey(new ByteArraySegment(cipher.doFinal(cipherText.rsaEncryptedAesKey)));
			final ByteArraySegment serializedPlainTextByteArray = aesKey.decrypt(cipherText.aesCypherText);
			final DataInputStream dis = serializedPlainTextByteArray.toDataInputStream();
			return TrSerializer.deserializeFrom(c, dis);
		} catch (final Exception e) {
			throw new RuntimeException(e);
		}
	}
}