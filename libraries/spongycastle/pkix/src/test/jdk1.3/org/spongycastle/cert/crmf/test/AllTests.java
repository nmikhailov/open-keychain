package org.spongycastle.cert.crmf.test;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import org.spongycastle.asn1.ASN1ObjectIdentifier;
import org.spongycastle.asn1.crmf.CRMFObjectIdentifiers;
import org.spongycastle.asn1.crmf.EncKeyWithID;
import org.spongycastle.asn1.crmf.EncryptedValue;
import org.spongycastle.asn1.x500.X500Name;
import org.spongycastle.asn1.x509.GeneralName;
import org.spongycastle.cert.X509CertificateHolder;
import org.spongycastle.cert.X509v1CertificateBuilder;
import org.spongycastle.cert.crmf.EncryptedValueBuilder;
import org.spongycastle.cert.crmf.EncryptedValuePadder;
import org.spongycastle.cert.crmf.EncryptedValueParser;
import org.spongycastle.cert.crmf.FixedLengthMGF1Padder;
import org.spongycastle.cert.crmf.PKIArchiveControl;
import org.spongycastle.cert.crmf.PKMACBuilder;
import org.spongycastle.cert.crmf.ValueDecryptorGenerator;
import org.spongycastle.cert.crmf.jcajce.JcaCertificateRequestMessage;
import org.spongycastle.cert.crmf.jcajce.JcaCertificateRequestMessageBuilder;
import org.spongycastle.cert.crmf.jcajce.JcaEncryptedValueBuilder;
import org.spongycastle.cert.crmf.jcajce.JcaPKIArchiveControlBuilder;
import org.spongycastle.cert.crmf.jcajce.JceAsymmetricValueDecryptorGenerator;
import org.spongycastle.cert.crmf.jcajce.JceCRMFEncryptorBuilder;
import org.spongycastle.cert.crmf.jcajce.JcePKMACValuesCalculator;
import org.spongycastle.cert.jcajce.JcaX509CertificateConverter;
import org.spongycastle.cert.jcajce.JcaX509v1CertificateBuilder;
import org.spongycastle.cert.jcajce.JcaX509CertificateHolder;
import org.spongycastle.cms.CMSAlgorithm;
import org.spongycastle.cms.CMSEnvelopedDataGenerator;
import org.spongycastle.cms.RecipientId;
import org.spongycastle.cms.RecipientInformation;
import org.spongycastle.cms.RecipientInformationStore;
import org.spongycastle.cms.jcajce.JceCMSContentEncryptorBuilder;
import org.spongycastle.cms.jcajce.JceKeyTransEnvelopedRecipient;
import org.spongycastle.cms.jcajce.JceKeyTransRecipientId;
import org.spongycastle.cms.jcajce.JceKeyTransRecipientInfoGenerator;
import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.spongycastle.operator.OperatorCreationException;
import org.spongycastle.operator.jcajce.JcaContentSignerBuilder;
import org.spongycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.spongycastle.operator.jcajce.JceAsymmetricKeyWrapper;
import org.spongycastle.util.Arrays;

public class AllTests
    extends TestCase
{
    private static final byte[] TEST_DATA = "Hello world!".getBytes();
    private static final String BC = BouncyCastleProvider.PROVIDER_NAME;
    private static final String PASSPHRASE = "hello world";

    /*
     *
     *  INFRASTRUCTURE
     *
     */

    public AllTests(String name)
    {
        super(name);
    }

    public static void main(String args[])
    {
        junit.textui.TestRunner.run(AllTests.class);
    }

    public static Test suite()
    {
        return new TestSuite(AllTests.class);
    }

    public void setUp()
    {
        Security.addProvider(new BouncyCastleProvider());
    }

    public void tearDown()
    {

    }

    public void testBasicMessageWithArchiveControl()
        throws Exception
    {
        KeyPairGenerator kGen = KeyPairGenerator.getInstance("RSA", BC);

        kGen.initialize(512);

        KeyPair kp = kGen.generateKeyPair();
        X509Certificate cert = makeV1Certificate(kp, "CN=Test", kp, "CN=Test");

        JcaCertificateRequestMessageBuilder certReqBuild = new JcaCertificateRequestMessageBuilder(BigInteger.ONE);

        certReqBuild.setPublicKey(kp.getPublic())
                    .setSubject(new X500Name("CN=Test"));

        certReqBuild.addControl(new JcaPKIArchiveControlBuilder(kp.getPrivate(), new X500Name("CN=Test"))
                                      .addRecipientGenerator(new JceKeyTransRecipientInfoGenerator(cert).setProvider(BC))
                                      .build(new JceCMSContentEncryptorBuilder(new ASN1ObjectIdentifier(CMSEnvelopedDataGenerator.AES128_CBC)).setProvider(BC).build()));

        JcaCertificateRequestMessage certReqMsg = new JcaCertificateRequestMessage(certReqBuild.build());

        assertEquals(new X500Name("CN=Test"), certReqMsg.getCertTemplate().getSubject());
        assertEquals(kp.getPublic(), certReqMsg.getPublicKey());

        PKIArchiveControl archiveControl = (PKIArchiveControl)certReqMsg.getControl(CRMFObjectIdentifiers.id_regCtrl_pkiArchiveOptions);

        assertEquals(PKIArchiveControl.encryptedPrivKey, archiveControl.getArchiveType());

        assertTrue(archiveControl.isEnvelopedData());

        RecipientInformationStore recips = archiveControl.getEnvelopedData().getRecipientInfos();

        RecipientId recipientId = new JceKeyTransRecipientId(cert);

        RecipientInformation recipientInformation = recips.get(recipientId);

        assertNotNull(recipientInformation);

        EncKeyWithID encKeyWithID = EncKeyWithID.getInstance(recipientInformation.getContent(new JceKeyTransEnvelopedRecipient(kp.getPrivate()).setProvider(BC)));

        assertTrue(encKeyWithID.hasIdentifier());
        assertFalse(encKeyWithID.isIdentifierUTF8String());

        assertEquals(new GeneralName(X500Name.getInstance(new X500Name("CN=Test").getEncoded())), encKeyWithID.getIdentifier());
        assertTrue(Arrays.areEqual(kp.getPrivate().getEncoded(), encKeyWithID.getPrivateKey().getEncoded()));
    }

    public void testProofOfPossessionWithoutSender()
        throws Exception
    {
        KeyPairGenerator kGen = KeyPairGenerator.getInstance("RSA", BC);

        kGen.initialize(512);

        KeyPair kp = kGen.generateKeyPair();
        X509Certificate cert = makeV1Certificate(kp, "CN=Test", kp, "CN=Test");

        JcaCertificateRequestMessageBuilder certReqBuild = new JcaCertificateRequestMessageBuilder(BigInteger.ONE);

        certReqBuild.setPublicKey(kp.getPublic())
                    .setAuthInfoPKMAC(new PKMACBuilder(new JcePKMACValuesCalculator()), "fred".toCharArray())
                    .setProofOfPossessionSigningKeySigner(new JcaContentSignerBuilder("SHA1withRSA").setProvider(BC).build(kp.getPrivate()));

        certReqBuild.addControl(new JcaPKIArchiveControlBuilder(kp.getPrivate(), new X500Name("CN=test"))
                                      .addRecipientGenerator(new JceKeyTransRecipientInfoGenerator(cert).setProvider(BC))
                                      .build(new JceCMSContentEncryptorBuilder(new ASN1ObjectIdentifier(CMSEnvelopedDataGenerator.AES128_CBC)).setProvider(BC).build()));

        JcaCertificateRequestMessage certReqMsg = new JcaCertificateRequestMessage(certReqBuild.build());

        // check that internal check on popo signing is working okay
        try
        {
            certReqMsg.isValidSigningKeyPOP(new JcaContentVerifierProviderBuilder().setProvider(BC).build(kp.getPublic()));
            fail("IllegalStateException not thrown");
        }
        catch (IllegalStateException e)
        {
            // ignore
        }

        assertTrue(certReqMsg.isValidSigningKeyPOP(new JcaContentVerifierProviderBuilder().setProvider(BC).build(kp.getPublic()), new PKMACBuilder(new JcePKMACValuesCalculator().setProvider(BC)), "fred".toCharArray()));

        assertEquals(kp.getPublic(), certReqMsg.getPublicKey());
    }

    public void testProofOfPossessionWithSender()
        throws Exception
    {
        KeyPairGenerator kGen = KeyPairGenerator.getInstance("RSA", BC);

        kGen.initialize(512);

        KeyPair kp = kGen.generateKeyPair();
        X509Certificate cert = makeV1Certificate(kp, "CN=Test", kp, "CN=Test");

        JcaCertificateRequestMessageBuilder certReqBuild = new JcaCertificateRequestMessageBuilder(BigInteger.ONE);

        certReqBuild.setPublicKey(kp.getPublic())
                    .setAuthInfoSender(new X500Name("CN=Test"))
                    .setProofOfPossessionSigningKeySigner(new JcaContentSignerBuilder("SHA1withRSA").setProvider(BC).build(kp.getPrivate()));

        certReqBuild.addControl(new JcaPKIArchiveControlBuilder(kp.getPrivate(), new X500Name("CN=test"))
                                      .addRecipientGenerator(new JceKeyTransRecipientInfoGenerator(cert).setProvider(BC))
                                      .build(new JceCMSContentEncryptorBuilder(new ASN1ObjectIdentifier(CMSEnvelopedDataGenerator.AES128_CBC)).setProvider(BC).build()));

        JcaCertificateRequestMessage certReqMsg = new JcaCertificateRequestMessage(certReqBuild.build());

        // check that internal check on popo signing is working okay
        try
        {
            certReqMsg.isValidSigningKeyPOP(new JcaContentVerifierProviderBuilder().setProvider(BC).build(kp.getPublic()), new PKMACBuilder(new JcePKMACValuesCalculator().setProvider(BC)), "fred".toCharArray());

            fail("IllegalStateException not thrown");
        }
        catch (IllegalStateException e)
        {
            // ignore
        }


        assertTrue(certReqMsg.isValidSigningKeyPOP(new JcaContentVerifierProviderBuilder().setProvider(BC).build(kp.getPublic())));

        assertEquals(kp.getPublic(), certReqMsg.getPublicKey());
    }

    public void testEncryptedValue()
        throws Exception
    {
        KeyPairGenerator kGen = KeyPairGenerator.getInstance("RSA", BC);

        kGen.initialize(512);

        KeyPair kp = kGen.generateKeyPair();
        X509Certificate cert = makeV1Certificate(kp, "CN=Test", kp, "CN=Test");

        JcaEncryptedValueBuilder build = new JcaEncryptedValueBuilder(new JceAsymmetricKeyWrapper(cert.getPublicKey()).setProvider(BC), new JceCRMFEncryptorBuilder(CMSAlgorithm.AES128_CBC).setProvider(BC).build());
        EncryptedValue value = build.build(cert);
        ValueDecryptorGenerator decGen = new JceAsymmetricValueDecryptorGenerator(kp.getPrivate()).setProvider(BC);

        // try direct
        encryptedValueParserTest(value, decGen, cert);

        // try indirect
        encryptedValueParserTest(EncryptedValue.getInstance(value.getEncoded()), decGen, cert);
    }

    private void encryptedValueParserTest(EncryptedValue value, ValueDecryptorGenerator decGen, X509Certificate cert)
        throws Exception
    {
        EncryptedValueParser  parser = new EncryptedValueParser(value);

        X509CertificateHolder holder = parser.readCertificateHolder(decGen);

        assertTrue(Arrays.areEqual(cert.getEncoded(), holder.getEncoded()));
    }

    public void testEncryptedValuePassphrase()
        throws Exception
    {
        char[] passphrase = PASSPHRASE.toCharArray();
        KeyPairGenerator kGen = KeyPairGenerator.getInstance("RSA", BC);

        kGen.initialize(512);

        KeyPair kp = kGen.generateKeyPair();
        X509Certificate cert = makeV1Certificate(kp, "CN=Test", kp, "CN=Test");

        EncryptedValueBuilder build = new EncryptedValueBuilder(new JceAsymmetricKeyWrapper(cert.getPublicKey()).setProvider(BC), new JceCRMFEncryptorBuilder(CMSAlgorithm.AES128_CBC).setProvider(BC).build());
        EncryptedValue value = build.build(passphrase);
        ValueDecryptorGenerator decGen = new JceAsymmetricValueDecryptorGenerator(kp.getPrivate()).setProvider(BC);

        // try direct
        encryptedValuePassphraseParserTest(value, null, decGen, cert);

        // try indirect
        encryptedValuePassphraseParserTest(EncryptedValue.getInstance(value.getEncoded()), null, decGen, cert);
    }

    public void testEncryptedValuePassphraseWithPadding()
        throws Exception
    {
        char[] passphrase = PASSPHRASE.toCharArray();
        KeyPairGenerator kGen = KeyPairGenerator.getInstance("RSA", BC);

        kGen.initialize(512);

        KeyPair kp = kGen.generateKeyPair();
        X509Certificate cert = makeV1Certificate(kp, "CN=Test", kp, "CN=Test");

        FixedLengthMGF1Padder mgf1Padder = new FixedLengthMGF1Padder(200, new SecureRandom());
        EncryptedValueBuilder build = new EncryptedValueBuilder(new JceAsymmetricKeyWrapper(cert.getPublicKey()).setProvider(BC), new JceCRMFEncryptorBuilder(CMSAlgorithm.AES128_CBC).setProvider(BC).build(), mgf1Padder);
        EncryptedValue value = build.build(passphrase);
        ValueDecryptorGenerator decGen = new JceAsymmetricValueDecryptorGenerator(kp.getPrivate()).setProvider(BC);

        // try direct
        encryptedValuePassphraseParserTest(value, mgf1Padder, decGen, cert);

        // try indirect
        encryptedValuePassphraseParserTest(EncryptedValue.getInstance(value.getEncoded()), mgf1Padder, decGen, cert);
    }

    private void encryptedValuePassphraseParserTest(EncryptedValue value, EncryptedValuePadder padder, ValueDecryptorGenerator decGen, X509Certificate cert)
        throws Exception
    {
        EncryptedValueParser  parser = new EncryptedValueParser(value, padder);

        assertTrue(Arrays.areEqual(PASSPHRASE.toCharArray(), parser.readPassphrase(decGen)));
    }

    private static X509Certificate makeV1Certificate(KeyPair subKP, String _subDN, KeyPair issKP, String _issDN)
        throws GeneralSecurityException, IOException, OperatorCreationException
    {

        PublicKey subPub  = subKP.getPublic();
        PrivateKey issPriv = issKP.getPrivate();
        PublicKey  issPub  = issKP.getPublic();

        X509v1CertificateBuilder v1CertGen = new JcaX509v1CertificateBuilder(
            new X500Name(_issDN),
            BigInteger.valueOf(System.currentTimeMillis()),
            new Date(System.currentTimeMillis()),
            new Date(System.currentTimeMillis() + (1000L * 60 * 60 * 24 * 100)),
            new X500Name(_subDN),
            subPub);

        JcaContentSignerBuilder signerBuilder = null;

        if (issPub instanceof RSAPublicKey)
        {
            signerBuilder = new JcaContentSignerBuilder("SHA1WithRSA");
        }
        else if (issPub.getAlgorithm().equals("DSA"))
        {
            signerBuilder = new JcaContentSignerBuilder("SHA1withDSA");
        }
        else if (issPub.getAlgorithm().equals("ECDSA"))
        {
            signerBuilder = new JcaContentSignerBuilder("SHA1withECDSA");
        }
        else if (issPub.getAlgorithm().equals("ECGOST3410"))
        {
            signerBuilder = new JcaContentSignerBuilder("GOST3411withECGOST3410");
        }
        else
        {
            signerBuilder = new JcaContentSignerBuilder("GOST3411WithGOST3410");
        }

        signerBuilder.setProvider(BC);

        X509Certificate _cert = new JcaX509CertificateConverter().setProvider(BC).getCertificate(v1CertGen.build(signerBuilder.build(issPriv)));

        _cert.checkValidity(new Date());
        _cert.verify(issPub);

        return _cert;
    }
}
