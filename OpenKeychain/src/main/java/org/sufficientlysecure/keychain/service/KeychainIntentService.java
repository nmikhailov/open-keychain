/*
 * Copyright (C) 2012-2014 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2014 Vincent Breitmoser <v.breitmoser@mugenguild.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.service;

import android.app.IntentService;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import com.textuality.keybase.lib.Proof;
import com.textuality.keybase.lib.prover.Prover;

import org.json.JSONObject;
import org.spongycastle.openpgp.PGPUtil;
import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.keyimport.HkpKeyserver;
import org.sufficientlysecure.keychain.keyimport.Keyserver;
import org.sufficientlysecure.keychain.keyimport.ParcelableKeyRing;
import org.sufficientlysecure.keychain.operations.CertifyOperation;
import org.sufficientlysecure.keychain.operations.DeleteOperation;
import org.sufficientlysecure.keychain.operations.EditKeyOperation;
import org.sufficientlysecure.keychain.operations.ImportExportOperation;
import org.sufficientlysecure.keychain.operations.NfcKeyToCardOperation;
import org.sufficientlysecure.keychain.operations.PromoteKeyOperation;
import org.sufficientlysecure.keychain.operations.SignEncryptOperation;
import org.sufficientlysecure.keychain.operations.results.CertifyResult;
import org.sufficientlysecure.keychain.operations.results.ConsolidateResult;
import org.sufficientlysecure.keychain.operations.results.DecryptVerifyResult;
import org.sufficientlysecure.keychain.operations.results.DeleteResult;
import org.sufficientlysecure.keychain.operations.results.ExportResult;
import org.sufficientlysecure.keychain.operations.results.ImportKeyResult;
import org.sufficientlysecure.keychain.operations.results.NfcKeyToCardResult;
import org.sufficientlysecure.keychain.operations.results.OperationResult;
import org.sufficientlysecure.keychain.operations.results.OperationResult.OperationLog;
import org.sufficientlysecure.keychain.operations.results.PromoteKeyResult;
import org.sufficientlysecure.keychain.operations.results.SignEncryptResult;
import org.sufficientlysecure.keychain.pgp.CanonicalizedPublicKeyRing;
import org.sufficientlysecure.keychain.pgp.PgpDecryptVerify;
import org.sufficientlysecure.keychain.pgp.Progressable;
import org.sufficientlysecure.keychain.pgp.SignEncryptParcel;
import org.sufficientlysecure.keychain.pgp.exception.PgpGeneralException;
import org.sufficientlysecure.keychain.pgp.exception.PgpGeneralMsgIdException;
import org.sufficientlysecure.keychain.provider.ProviderHelper;
import org.sufficientlysecure.keychain.service.input.CryptoInputParcel;
import org.sufficientlysecure.keychain.service.ServiceProgressHandler.MessageStatus;
import org.sufficientlysecure.keychain.util.FileHelper;
import org.sufficientlysecure.keychain.util.InputData;
import org.sufficientlysecure.keychain.util.Log;
import org.sufficientlysecure.keychain.util.ParcelableFileCache;
import org.sufficientlysecure.keychain.util.Passphrase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.measite.minidns.Client;
import de.measite.minidns.DNSMessage;
import de.measite.minidns.Question;
import de.measite.minidns.Record;
import de.measite.minidns.record.Data;
import de.measite.minidns.record.TXT;

/**
 * This Service contains all important long lasting operations for OpenKeychain. It receives Intents with
 * data from the activities or other apps, queues these intents, executes them, and stops itself
 * after doing them.
 */
public class KeychainIntentService extends IntentService implements Progressable {

    /* extras that can be given by intent */
    public static final String EXTRA_MESSENGER = "messenger";
    public static final String EXTRA_DATA = "data";

    /* possible actions */
    public static final String ACTION_SIGN_ENCRYPT = Constants.INTENT_PREFIX + "SIGN_ENCRYPT";

    public static final String ACTION_DECRYPT_VERIFY = Constants.INTENT_PREFIX + "DECRYPT_VERIFY";

    public static final String ACTION_VERIFY_KEYBASE_PROOF = Constants.INTENT_PREFIX + "VERIFY_KEYBASE_PROOF";

    public static final String ACTION_DECRYPT_METADATA = Constants.INTENT_PREFIX + "DECRYPT_METADATA";

    public static final String ACTION_EDIT_KEYRING = Constants.INTENT_PREFIX + "EDIT_KEYRING";

    public static final String ACTION_PROMOTE_KEYRING = Constants.INTENT_PREFIX + "PROMOTE_KEYRING";

    public static final String ACTION_IMPORT_KEYRING = Constants.INTENT_PREFIX + "IMPORT_KEYRING";
    public static final String ACTION_EXPORT_KEYRING = Constants.INTENT_PREFIX + "EXPORT_KEYRING";

    public static final String ACTION_NFC_KEYTOCARD = Constants.INTENT_PREFIX + "NFC_KEYTOCARD";

    public static final String ACTION_UPLOAD_KEYRING = Constants.INTENT_PREFIX + "UPLOAD_KEYRING";

    public static final String ACTION_CERTIFY_KEYRING = Constants.INTENT_PREFIX + "SIGN_KEYRING";

    public static final String ACTION_DELETE = Constants.INTENT_PREFIX + "DELETE";

    public static final String ACTION_CONSOLIDATE = Constants.INTENT_PREFIX + "CONSOLIDATE";

    public static final String ACTION_CANCEL = Constants.INTENT_PREFIX + "CANCEL";

    /* keys for data bundle */

    // encrypt, decrypt, import export
    public static final String TARGET = "target";
    public static final String SOURCE = "source";

    // possible targets:
    public static enum IOType {
        UNKNOWN,
        BYTES,
        URI;

        private static final IOType[] values = values();

        public static IOType fromInt(int n) {
            if (n < 0 || n >= values.length) {
                return UNKNOWN;
            } else {
                return values[n];
            }
        }
    }

    // encrypt
    public static final String ENCRYPT_DECRYPT_INPUT_URI = "input_uri";
    public static final String ENCRYPT_DECRYPT_OUTPUT_URI = "output_uri";
    public static final String SIGN_ENCRYPT_PARCEL = "sign_encrypt_parcel";

    // decrypt/verify
    public static final String DECRYPT_CIPHERTEXT_BYTES = "ciphertext_bytes";

    // keybase proof
    public static final String KEYBASE_REQUIRED_FINGERPRINT = "keybase_required_fingerprint";
    public static final String KEYBASE_PROOF = "keybase_proof";

    // save keyring
    public static final String EDIT_KEYRING_PARCEL = "save_parcel";
    public static final String EDIT_KEYRING_PASSPHRASE = "passphrase";
    public static final String EXTRA_CRYPTO_INPUT = "crypto_input";

    // delete keyring(s)
    public static final String DELETE_KEY_LIST = "delete_list";
    public static final String DELETE_IS_SECRET = "delete_is_secret";

    // import key
    public static final String IMPORT_KEY_LIST = "import_key_list";
    public static final String IMPORT_KEY_SERVER = "import_key_server";

    // export key
    public static final String EXPORT_FILENAME = "export_filename";
    public static final String EXPORT_URI = "export_uri";
    public static final String EXPORT_SECRET = "export_secret";
    public static final String EXPORT_ALL = "export_all";
    public static final String EXPORT_KEY_RING_MASTER_KEY_ID = "export_key_ring_id";

    // NFC export key to card
    public static final String NFC_KEYTOCARD_SUBKEY_ID = "nfc_keytocard_subkey_id";

    // upload key
    public static final String UPLOAD_KEY_SERVER = "upload_key_server";

    // certify key
    public static final String CERTIFY_PARCEL = "certify_parcel";

    // promote key
    public static final String PROMOTE_MASTER_KEY_ID = "promote_master_key_id";
    public static final String PROMOTE_CARD_AID = "promote_card_aid";

    // consolidate
    public static final String CONSOLIDATE_RECOVERY = "consolidate_recovery";


    /*
     * possible data keys as result send over messenger
     */

    // decrypt/verify
    public static final String RESULT_DECRYPTED_BYTES = "decrypted_data";

    Messenger mMessenger;

    // this attribute can possibly merged with the one above? not sure...
    private AtomicBoolean mActionCanceled = new AtomicBoolean(false);

    public KeychainIntentService() {
        super("KeychainIntentService");
    }

    /**
     * The IntentService calls this method from the default worker thread with the intent that
     * started the service. When this method returns, IntentService stops the service, as
     * appropriate.
     */
    @Override
    protected void onHandleIntent(Intent intent) {

        // We have not been cancelled! (yet)
        mActionCanceled.set(false);

        Bundle extras = intent.getExtras();
        if (extras == null) {
            Log.e(Constants.TAG, "Extras bundle is null!");
            return;
        }

        if (!(extras.containsKey(EXTRA_MESSENGER) || extras.containsKey(EXTRA_DATA) || (intent
                .getAction() == null))) {
            Log.e(Constants.TAG,
                    "Extra bundle must contain a messenger, a data bundle, and an action!");
            return;
        }

        Uri dataUri = intent.getData();

        mMessenger = (Messenger) extras.get(EXTRA_MESSENGER);
        Bundle data = extras.getBundle(EXTRA_DATA);
        if (data == null) {
            Log.e(Constants.TAG, "data extra is null!");
            return;
        }

        Log.logDebugBundle(data, "EXTRA_DATA");

        ProviderHelper providerHelper = new ProviderHelper(this);

        String action = intent.getAction();

        // executeServiceMethod action from extra bundle
        switch (action) {
            case ACTION_CERTIFY_KEYRING: {

                // Input
                CertifyActionsParcel parcel = data.getParcelable(CERTIFY_PARCEL);
                CryptoInputParcel cryptoInput = data.getParcelable(EXTRA_CRYPTO_INPUT);
                String keyServerUri = data.getString(UPLOAD_KEY_SERVER);

                // Operation
                CertifyOperation op = new CertifyOperation(this, providerHelper, this, mActionCanceled);
                CertifyResult result = op.certify(parcel, cryptoInput, keyServerUri);

                // Result
                sendMessageToHandler(MessageStatus.OKAY, result);

                break;
            }
            case ACTION_CONSOLIDATE: {

                // Operation
                ConsolidateResult result;
                if (data.containsKey(CONSOLIDATE_RECOVERY) && data.getBoolean(CONSOLIDATE_RECOVERY)) {
                    result = new ProviderHelper(this).consolidateDatabaseStep2(this);
                } else {
                    result = new ProviderHelper(this).consolidateDatabaseStep1(this);
                }

                // Result
                sendMessageToHandler(MessageStatus.OKAY, result);

                break;
            }
            case ACTION_DECRYPT_METADATA: {

                try {
                    /* Input */
                    CryptoInputParcel cryptoInput = data.getParcelable(EXTRA_CRYPTO_INPUT);

                    InputData inputData = createDecryptInputData(data);

                    // verifyText and decrypt returning additional resultData values for the
                    // verification of signatures
                    PgpDecryptVerify.Builder builder = new PgpDecryptVerify.Builder(
                            this, new ProviderHelper(this), this, inputData, null
                    );
                    builder.setAllowSymmetricDecryption(true)
                            .setDecryptMetadataOnly(true);

                    DecryptVerifyResult decryptVerifyResult = builder.build().execute(cryptoInput);

                    sendMessageToHandler(MessageStatus.OKAY, decryptVerifyResult);
                } catch (Exception e) {
                    sendErrorToHandler(e);
                }

                break;
            }
            case ACTION_VERIFY_KEYBASE_PROOF: {

                try {
                    Proof proof = new Proof(new JSONObject(data.getString(KEYBASE_PROOF)));
                    setProgress(R.string.keybase_message_fetching_data, 0, 100);

                    Prover prover = Prover.findProverFor(proof);

                    if (prover == null) {
                        sendProofError(getString(R.string.keybase_no_prover_found) + ": " + proof.getPrettyName());
                        return;
                    }

                    if (!prover.fetchProofData()) {
                        sendProofError(prover.getLog(), getString(R.string.keybase_problem_fetching_evidence));
                        return;
                    }
                    String requiredFingerprint = data.getString(KEYBASE_REQUIRED_FINGERPRINT);
                    if (!prover.checkFingerprint(requiredFingerprint)) {
                        sendProofError(getString(R.string.keybase_key_mismatch));
                        return;
                    }

                    String domain = prover.dnsTxtCheckRequired();
                    if (domain != null) {
                        DNSMessage dnsQuery = new Client().query(new Question(domain, Record.TYPE.TXT));
                        if (dnsQuery == null) {
                            sendProofError(prover.getLog(), getString(R.string.keybase_dns_query_failure));
                            return;
                        }
                        Record[] records = dnsQuery.getAnswers();
                        List<List<byte[]>> extents = new ArrayList<List<byte[]>>();
                        for (Record r : records) {
                            Data d = r.getPayload();
                            if (d instanceof TXT) {
                                extents.add(((TXT) d).getExtents());
                            }
                        }
                        if (!prover.checkDnsTxt(extents)) {
                            sendProofError(prover.getLog(), null);
                            return;
                        }
                    }

                    byte[] messageBytes = prover.getPgpMessage().getBytes();
                    if (prover.rawMessageCheckRequired()) {
                        InputStream messageByteStream = PGPUtil.getDecoderStream(new ByteArrayInputStream(messageBytes));
                        if (!prover.checkRawMessageBytes(messageByteStream)) {
                            sendProofError(prover.getLog(), null);
                            return;
                        }
                    }

                    // kind of awkward, but this whole class wants to pull bytes out of “data”
                    data.putInt(KeychainIntentService.TARGET, IOType.BYTES.ordinal());
                    data.putByteArray(KeychainIntentService.DECRYPT_CIPHERTEXT_BYTES, messageBytes);

                    InputData inputData = createDecryptInputData(data);
                    OutputStream outStream = createCryptOutputStream(data);

                    PgpDecryptVerify.Builder builder = new PgpDecryptVerify.Builder(
                            this, new ProviderHelper(this), this,
                            inputData, outStream
                    );
                    builder.setSignedLiteralData(true).setRequiredSignerFingerprint(requiredFingerprint);

                    DecryptVerifyResult decryptVerifyResult = builder.build().execute(
                            new CryptoInputParcel());
                    outStream.close();

                    if (!decryptVerifyResult.success()) {
                        OperationLog log = decryptVerifyResult.getLog();
                        OperationResult.LogEntryParcel lastEntry = null;
                        for (OperationResult.LogEntryParcel entry : log) {
                            lastEntry = entry;
                        }
                        sendProofError(getString(lastEntry.mType.getMsgId()));
                        return;
                    }

                    if (!prover.validate(outStream.toString())) {
                        sendProofError(getString(R.string.keybase_message_payload_mismatch));
                        return;
                    }

                    Bundle resultData = new Bundle();
                    resultData.putString(ServiceProgressHandler.DATA_MESSAGE, "OK");

                    // these help the handler construct a useful human-readable message
                    resultData.putString(ServiceProgressHandler.KEYBASE_PROOF_URL, prover.getProofUrl());
                    resultData.putString(ServiceProgressHandler.KEYBASE_PRESENCE_URL, prover.getPresenceUrl());
                    resultData.putString(ServiceProgressHandler.KEYBASE_PRESENCE_LABEL, prover.getPresenceLabel());
                    sendMessageToHandler(MessageStatus.OKAY, resultData);
                } catch (Exception e) {
                    sendErrorToHandler(e);
                }

                break;
            }
            case ACTION_DECRYPT_VERIFY: {

                try {
                    /* Input */
                    CryptoInputParcel cryptoInput = data.getParcelable(EXTRA_CRYPTO_INPUT);

                    InputData inputData = createDecryptInputData(data);
                    OutputStream outStream = createCryptOutputStream(data);

                    /* Operation */
                    Bundle resultData = new Bundle();

                    // verifyText and decrypt returning additional resultData values for the
                    // verification of signatures
                    PgpDecryptVerify.Builder builder = new PgpDecryptVerify.Builder(
                            this, new ProviderHelper(this), this,
                            inputData, outStream
                    );
                    builder.setAllowSymmetricDecryption(true);

                    DecryptVerifyResult decryptVerifyResult = builder.build().execute(cryptoInput);

                    outStream.close();

                    resultData.putParcelable(DecryptVerifyResult.EXTRA_RESULT, decryptVerifyResult);

                    /* Output */
                    finalizeDecryptOutputStream(data, resultData, outStream);
                    Log.logDebugBundle(resultData, "resultData");

                    sendMessageToHandler(MessageStatus.OKAY, resultData);

                } catch (IOException | PgpGeneralException e) {
                    // TODO get rid of this!
                    sendErrorToHandler(e);
                }

                break;
            }
            case ACTION_DELETE: {

                // Input
                long[] masterKeyIds = data.getLongArray(DELETE_KEY_LIST);
                boolean isSecret = data.getBoolean(DELETE_IS_SECRET);

                // Operation
                DeleteOperation op = new DeleteOperation(this, new ProviderHelper(this), this);
                DeleteResult result = op.execute(masterKeyIds, isSecret);

                // Result
                sendMessageToHandler(MessageStatus.OKAY, result);

                break;
            }
            case ACTION_EDIT_KEYRING: {

                // Input
                SaveKeyringParcel saveParcel = data.getParcelable(EDIT_KEYRING_PARCEL);
                CryptoInputParcel cryptoInput = data.getParcelable(EXTRA_CRYPTO_INPUT);

                // Operation
                EditKeyOperation op = new EditKeyOperation(this, providerHelper, this, mActionCanceled);
                OperationResult result = op.execute(saveParcel, cryptoInput);

                // Result
                sendMessageToHandler(MessageStatus.OKAY, result);

                break;
            }
            case ACTION_PROMOTE_KEYRING: {

                // Input
                long keyRingId = data.getLong(PROMOTE_MASTER_KEY_ID);
                byte[] cardAid = data.getByteArray(PROMOTE_CARD_AID);

                // Operation
                PromoteKeyOperation op = new PromoteKeyOperation(this, providerHelper, this, mActionCanceled);
                PromoteKeyResult result = op.execute(keyRingId, cardAid);

                // Result
                sendMessageToHandler(MessageStatus.OKAY, result);

                break;
            }
            case ACTION_EXPORT_KEYRING: {

                // Input
                boolean exportSecret = data.getBoolean(EXPORT_SECRET, false);
                String outputFile = data.getString(EXPORT_FILENAME);
                Uri outputUri = data.getParcelable(EXPORT_URI);

                boolean exportAll = data.getBoolean(EXPORT_ALL);
                long[] masterKeyIds = exportAll ? null : data.getLongArray(EXPORT_KEY_RING_MASTER_KEY_ID);

                // Operation
                ImportExportOperation importExportOperation = new ImportExportOperation(this, new ProviderHelper(this), this);
                ExportResult result;
                if (outputFile != null) {
                    result = importExportOperation.exportToFile(masterKeyIds, exportSecret, outputFile);
                } else {
                    result = importExportOperation.exportToUri(masterKeyIds, exportSecret, outputUri);
                }

                // Result
                sendMessageToHandler(MessageStatus.OKAY, result);

                break;
            }
            case ACTION_IMPORT_KEYRING: {

                // Input
                String keyServer = data.getString(IMPORT_KEY_SERVER);
                ArrayList<ParcelableKeyRing> list = data.getParcelableArrayList(IMPORT_KEY_LIST);
                ParcelableFileCache<ParcelableKeyRing> cache =
                        new ParcelableFileCache<>(this, "key_import.pcl");

                // Operation
                ImportExportOperation importExportOperation = new ImportExportOperation(
                        this, providerHelper, this, mActionCanceled);
                // Either list or cache must be null, no guarantees otherwise.
                ImportKeyResult result = list != null
                        ? importExportOperation.importKeyRings(list, keyServer)
                        : importExportOperation.importKeyRings(cache, keyServer);

                // Result
                sendMessageToHandler(MessageStatus.OKAY, result);

                break;

            }
            case ACTION_NFC_KEYTOCARD: {
                // Input
                long subKeyId = data.getLong(NFC_KEYTOCARD_SUBKEY_ID);

                // Operation
                NfcKeyToCardOperation exportOp = new NfcKeyToCardOperation(this, providerHelper, this);
                NfcKeyToCardResult result = exportOp.execute(subKeyId);

                // Result
                sendMessageToHandler(MessageStatus.OKAY, result);

                break;
            }
            case ACTION_SIGN_ENCRYPT: {

                // Input
                SignEncryptParcel inputParcel = data.getParcelable(SIGN_ENCRYPT_PARCEL);
                CryptoInputParcel cryptoInput = data.getParcelable(EXTRA_CRYPTO_INPUT);

                // Operation
                SignEncryptOperation op = new SignEncryptOperation(
                        this, new ProviderHelper(this), this, mActionCanceled);
                SignEncryptResult result = op.execute(inputParcel, cryptoInput);

                // Result
                sendMessageToHandler(MessageStatus.OKAY, result);

                break;
            }
            case ACTION_UPLOAD_KEYRING: {
                try {

                    /* Input */
                    String keyServer = data.getString(UPLOAD_KEY_SERVER);
                    // and dataUri!

                    /* Operation */
                    HkpKeyserver server = new HkpKeyserver(keyServer);

                    CanonicalizedPublicKeyRing keyring = providerHelper.getCanonicalizedPublicKeyRing(dataUri);
                    ImportExportOperation importExportOperation = new ImportExportOperation(this, new ProviderHelper(this), this);

                    try {
                        importExportOperation.uploadKeyRingToServer(server, keyring);
                    } catch (Keyserver.AddKeyException e) {
                        throw new PgpGeneralException("Unable to export key to selected server");
                    }

                    sendMessageToHandler(MessageStatus.OKAY);
                } catch (Exception e) {
                    sendErrorToHandler(e);
                }
                break;
            }
        }
    }

    private void sendProofError(List<String> log, String label) {
        String msg = null;
        label = (label == null) ? "" : label + ": ";
        for (String m : log) {
            Log.e(Constants.TAG, label + m);
            msg = m;
        }
        sendProofError(label + msg);
    }

    private void sendProofError(String msg) {
        Bundle bundle = new Bundle();
        bundle.putString(ServiceProgressHandler.DATA_ERROR, msg);
        sendMessageToHandler(MessageStatus.OKAY, bundle);
    }

    private void sendErrorToHandler(Exception e) {
        // TODO: Implement a better exception handling here
        // contextualize the exception, if necessary
        String message;
        if (e instanceof PgpGeneralMsgIdException) {
            e = ((PgpGeneralMsgIdException) e).getContextualized(this);
            message = e.getMessage();
        } else {
            message = e.getMessage();
        }
        Log.d(Constants.TAG, "KeychainIntentService Exception: ", e);

        Bundle data = new Bundle();
        data.putString(ServiceProgressHandler.DATA_ERROR, message);
        sendMessageToHandler(MessageStatus.EXCEPTION, null, data);
    }

    private void sendMessageToHandler(MessageStatus status, Integer arg2, Bundle data) {

        Message msg = Message.obtain();
        assert msg != null;
        msg.arg1 = status.ordinal();
        if (arg2 != null) {
            msg.arg2 = arg2;
        }
        if (data != null) {
            msg.setData(data);
        }

        try {
            mMessenger.send(msg);
        } catch (RemoteException e) {
            Log.w(Constants.TAG, "Exception sending message, Is handler present?", e);
        } catch (NullPointerException e) {
            Log.w(Constants.TAG, "Messenger is null!", e);
        }
    }

    private void sendMessageToHandler(MessageStatus status, OperationResult data) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(OperationResult.EXTRA_RESULT, data);
        sendMessageToHandler(status, null, bundle);
    }

    private void sendMessageToHandler(MessageStatus status, Bundle data) {
        sendMessageToHandler(status, null, data);
    }

    private void sendMessageToHandler(MessageStatus status) {
        sendMessageToHandler(status, null, null);
    }

    /**
     * Set progress of ProgressDialog by sending message to handler on UI thread
     */
    public void setProgress(String message, int progress, int max) {
        Log.d(Constants.TAG, "Send message by setProgress with progress=" + progress + ", max="
                + max);

        Bundle data = new Bundle();
        if (message != null) {
            data.putString(ServiceProgressHandler.DATA_MESSAGE, message);
        }
        data.putInt(ServiceProgressHandler.DATA_PROGRESS, progress);
        data.putInt(ServiceProgressHandler.DATA_PROGRESS_MAX, max);

        sendMessageToHandler(MessageStatus.UPDATE_PROGRESS, null, data);
    }

    public void setProgress(int resourceId, int progress, int max) {
        setProgress(getString(resourceId), progress, max);
    }

    public void setProgress(int progress, int max) {
        setProgress(null, progress, max);
    }

    @Override
    public void setPreventCancel() {
        sendMessageToHandler(MessageStatus.PREVENT_CANCEL);
    }

    private InputData createDecryptInputData(Bundle data) throws IOException, PgpGeneralException {
        return createCryptInputData(data, DECRYPT_CIPHERTEXT_BYTES);
    }

    private InputData createCryptInputData(Bundle data, String bytesName) throws PgpGeneralException, IOException {
        int source = data.get(SOURCE) != null ? data.getInt(SOURCE) : data.getInt(TARGET);
        IOType type = IOType.fromInt(source);
        switch (type) {
            case BYTES: /* encrypting bytes directly */
                byte[] bytes = data.getByteArray(bytesName);
                return new InputData(new ByteArrayInputStream(bytes), bytes.length);

            case URI: /* encrypting content uri */
                Uri providerUri = data.getParcelable(ENCRYPT_DECRYPT_INPUT_URI);

                // InputStream
                return new InputData(getContentResolver().openInputStream(providerUri), FileHelper.getFileSize(this, providerUri, 0));

            default:
                throw new PgpGeneralException("No target chosen!");
        }
    }

    private OutputStream createCryptOutputStream(Bundle data) throws PgpGeneralException, FileNotFoundException {
        int target = data.getInt(TARGET);
        IOType type = IOType.fromInt(target);
        switch (type) {
            case BYTES:
                return new ByteArrayOutputStream();

            case URI:
                Uri providerUri = data.getParcelable(ENCRYPT_DECRYPT_OUTPUT_URI);

                return getContentResolver().openOutputStream(providerUri);

            default:
                throw new PgpGeneralException("No target chosen!");
        }
    }

    private void finalizeDecryptOutputStream(Bundle data, Bundle resultData, OutputStream outStream) {
        finalizeCryptOutputStream(data, resultData, outStream, RESULT_DECRYPTED_BYTES);
    }

    private void finalizeCryptOutputStream(Bundle data, Bundle resultData, OutputStream outStream, String bytesName) {
        int target = data.getInt(TARGET);
        IOType type = IOType.fromInt(target);
        switch (type) {
            case BYTES:
                byte output[] = ((ByteArrayOutputStream) outStream).toByteArray();
                resultData.putByteArray(bytesName, output);
                break;
            case URI:
                // nothing, output was written, just send okay and verification bundle

                break;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (ACTION_CANCEL.equals(intent.getAction())) {
            mActionCanceled.set(true);
            return START_NOT_STICKY;
        }
        return super.onStartCommand(intent, flags, startId);
    }
}
