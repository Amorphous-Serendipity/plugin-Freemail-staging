package freemail;

import java.io.File;
import java.io.PrintWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;
import java.security.SecureRandom;
import java.math.BigInteger;
import java.net.MalformedURLException;

import org.bouncycastle.crypto.digests.MD5Digest;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.bouncycastle.util.encoders.Hex;

import org.archive.util.Base32;

import freemail.FreenetURI;
import freemail.fcp.HighLevelFCPClient;
import freemail.fcp.SSKKeyPair;
import freemail.utils.PropsFile;
import freemail.utils.EmailAddress;

public class AccountManager {
	public static final String DATADIR = "data";
	// this really doesn't matter a great deal
	public static final String NIMDIR = "nim";
	
	private static final String ACCOUNT_FILE = "accprops";
	private static final int RTS_KEY_LENGTH = 32;
	
	private static final int ASYM_KEY_MODULUS_LENGTH = 4096;
	private static final BigInteger ASYM_KEY_EXPONENT = new BigInteger("17", 10);
	private static final int ASYM_KEY_CERTAINTY = 80;
	
	public static final String MAILSITE_SUFFIX = "mailsite";
	public static final String MAILSITE_VERSION = "-1";
	

	public static void Create(String username) throws IOException {
		File datadir = new File(DATADIR);
		if (!datadir.exists()) {
			if (!datadir.mkdir()) throw new IOException("Failed to create data directory");
		}
		
		File accountdir = new File(DATADIR, username);
		if (!accountdir.mkdir()) throw new IOException("Failed to create directory "+username+" in "+DATADIR);
		getAccountFile(accountdir);
	}
	
	public static void setupNIM(String username) throws IOException {
		File accountdir = new File(DATADIR, username);
		
		File contacts_dir = new File(accountdir, SingleAccountWatcher.CONTACTS_DIR);
		if (!contacts_dir.exists()) {
			if (!contacts_dir.mkdir()) throw new IOException("Failed to create contacts directory");
		}
		
		File nimdir = new File(contacts_dir, NIMDIR);
		if (!nimdir.exists()) {
			if (!nimdir.mkdir()) throw new IOException("Failed to create nim directory");
		}
		
		File keyfile = new File(nimdir, NIMContact.KEYFILE);
		PrintWriter pw = new PrintWriter(new FileOutputStream(keyfile));
		
		pw.println(MessageSender.NIM_KEY_PREFIX + username + "-");
		
		pw.close();
	}
	
	public static void ChangePassword(String username, String newpassword) throws Exception {
		MD5Digest md5 = new MD5Digest();
		
		File accountdir = new File(DATADIR, username);
		if (!accountdir.exists()) {
			throw new Exception("No such account - "+username+".");
		}
		
		PropsFile accfile = getAccountFile(accountdir);
		
		md5.update(newpassword.getBytes(), 0, newpassword.getBytes().length);
		byte[] md5passwd = new byte[md5.getDigestSize()];
		md5.doFinal(md5passwd, 0);
		String strmd5 = new String(Hex.encode(md5passwd));
		
		accfile.put("md5passwd", strmd5);
	}
	
	public static PropsFile getAccountFile(File accdir) {
		PropsFile accfile = new PropsFile(new File(accdir, ACCOUNT_FILE));
		
		if (!accfile.exists()) {
			initAccFile(accfile);
		}
		
		return accfile;
	}
	
	public static EmailAddress getFreemailAddress(File accdir) {
		PropsFile accfile = getAccountFile(accdir);
		
		return getFreemailAddress(accfile);
	}
	
	public static EmailAddress getFreemailAddress(PropsFile accfile) {
		FreenetURI mailsite;
		try {
			mailsite = new FreenetURI(accfile.get("mailsite.pubkey"));
		} catch (MalformedURLException mfue) {
			System.out.println("Warning: Couldn't fetch mailsite public key from account file! Your account file is probably corrupt.");
			return null;
		}
		
		return new EmailAddress("anything@"+Base32.encode(mailsite.getKeyBody().getBytes())+".freemail");
	}
	
	public static EmailAddress getKSKFreemailAddress(File accdir) {
		PropsFile accfile = getAccountFile(accdir);
		
		String alias = accfile.get("domain_alias");
		
		if (alias == null) return null;
		
		return new EmailAddress("anything@"+alias+".freemail");
	}
	
	public static RSAKeyParameters getPrivateKey(File accdir) {
		PropsFile props = getAccountFile(accdir);
		
		String mod_str = props.get("asymkey.modulus");
		String privexp_str = props.get("asymkey.privexponent");
		
		if (mod_str == null || privexp_str == null) {
			System.out.println("Couldn't get private key - account file corrupt?");
			return null;
		}
		
		return new RSAKeyParameters(true, new BigInteger(mod_str, 32), new BigInteger(privexp_str, 32));
	}
	
	private static void initAccFile(PropsFile accfile) {
		try {
			System.out.println("Generating mailsite keys...");
			HighLevelFCPClient fcpcli = new HighLevelFCPClient();
			
			SSKKeyPair keypair = fcpcli.makeSSK();
			
			if (keypair == null) {
				System.out.println("Unable to connect to the Freenet nodenode");
				return;
			}
			
			// write private key
			if (!accfile.put("mailsite.privkey", keypair.privkey+MAILSITE_SUFFIX)) {
				throw new IOException("Unable to write account file");
			}
			
			// write public key
			if (!accfile.put("mailsite.pubkey", keypair.pubkey+MAILSITE_SUFFIX)) {
				throw new IOException("Unable to write account file");
			}
			
			// initialise RTS KSK
			Random rnd = new Random();
			String rtskey = new String();
			
			int i;
			for (i = 0; i < RTS_KEY_LENGTH; i++) {
				rtskey += (char)(rnd.nextInt(25) + (int)'a');
			}
			
			if (!accfile.put("rtskey", rtskey)) {
				throw new IOException("Unable to write account file");
			}
			
			System.out.println("Mailsite keys generated.");
			System.out.println("Your Freemail address is: "+getFreemailAddress(accfile));
		} catch (IOException ioe) {
			System.out.println("Couldn't create mailsite key file! "+ioe.getMessage());
		}
		
		// generate an RSA keypair
		System.out.println("Generating cryptographic keypair (this could take a few minutes)...");
		
		SecureRandom rand = new SecureRandom();

		RSAKeyGenerationParameters kparams = new RSAKeyGenerationParameters(ASYM_KEY_EXPONENT, rand, ASYM_KEY_MODULUS_LENGTH, ASYM_KEY_CERTAINTY);

		RSAKeyPairGenerator kpg = new RSAKeyPairGenerator();
		kpg.init(kparams);
		
		AsymmetricCipherKeyPair keypair = kpg.generateKeyPair();
		RSAKeyParameters pub = (RSAKeyParameters) keypair.getPublic();
		RSAKeyParameters priv = (RSAKeyParameters) keypair.getPrivate();

		accfile.put("asymkey.modulus", pub.getModulus().toString(32));
		accfile.put("asymkey.pubexponent", pub.getExponent().toString(32));
		accfile.put("asymkey.privexponent", priv.getExponent().toString(32));
		
		System.out.println("Account creation completed.");
	}
	
	public static void addShortAddress(String username, String alias) throws Exception {
		File accountdir = new File(DATADIR, username);
		if (!accountdir.exists()) {
			throw new Exception("No such account - "+username+".");
		}
		
		PropsFile accfile = getAccountFile(accountdir);
		
		MailSite ms = new MailSite(accfile);
		
		if (ms.insertAlias(alias)) {
			accfile.put("domain_alias", alias);
		}
	}
	
	public static boolean authenticate(String username, String password) {
		if (!validate_username(username)) return false;
		
		//String sep = System.getProperty("file.separator");
		
		File accountdir = new File(DATADIR, username);
		if (!accountdir.exists()) {
			return false;
		}
		PropsFile accfile = getAccountFile(accountdir);
		
		String realmd5str = accfile.get("md5passwd");
		if (realmd5str == null) return false;
		
		MD5Digest md5 = new MD5Digest();
		md5.update(password.getBytes(), 0, password.getBytes().length);
		byte[] givenmd5 = new byte[md5.getDigestSize()];
		md5.doFinal(givenmd5, 0);
		
		String givenmd5str = new String(Hex.encode(givenmd5));
		
		if (realmd5str.equals(givenmd5str)) {
			return true;
		}
		return false;
	}
	
	private static boolean validate_username(String username) {
		if (username.matches("[\\w_]*")) return true;
		return false;
	}
}
