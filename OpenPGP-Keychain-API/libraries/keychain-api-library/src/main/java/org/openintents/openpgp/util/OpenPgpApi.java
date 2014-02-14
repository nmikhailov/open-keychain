/*
 * Copyright (C) 2014 Dominik Schürmann <dominik@dominikschuermann.de>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openintents.openpgp.util;

import android.os.AsyncTask;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.openintents.openpgp.IOpenPgpService;
import org.openintents.openpgp.OpenPgpError;

import java.io.InputStream;
import java.io.OutputStream;

public class OpenPgpApi {

    IOpenPgpService mService;

    private static final int OPERATION_SIGN = 0;
    private static final int OPERATION_ENCRYPT = 1;
    private static final int OPERATION_SIGN_ENCRYPT = 2;
    private static final int OPERATION_DECRYPT_VERIFY = 3;

    public OpenPgpApi(IOpenPgpService service) {
        this.mService = service;
    }

    public Bundle sign(InputStream is, final OutputStream os) {
        return executeApi(OPERATION_SIGN, new Bundle(), is, os);
    }

    public Bundle sign(Bundle params, InputStream is, final OutputStream os) {
        return executeApi(OPERATION_SIGN, params, is, os);
    }

    public void sign(Bundle params, InputStream is, final OutputStream os, IOpenPgpCallback callback) {
        executeApiAsync(OPERATION_SIGN, params, is, os, callback);
    }

    public Bundle encrypt(InputStream is, final OutputStream os) {
        return executeApi(OPERATION_ENCRYPT, new Bundle(), is, os);
    }

    public Bundle encrypt(Bundle params, InputStream is, final OutputStream os) {
        return executeApi(OPERATION_ENCRYPT, params, is, os);
    }

    public void encrypt(Bundle params, InputStream is, final OutputStream os, IOpenPgpCallback callback) {
        executeApiAsync(OPERATION_ENCRYPT, params, is, os, callback);
    }

    public Bundle signAndEncrypt(InputStream is, final OutputStream os) {
        return executeApi(OPERATION_SIGN_ENCRYPT, new Bundle(), is, os);
    }

    public Bundle signAndEncrypt(Bundle params, InputStream is, final OutputStream os) {
        return executeApi(OPERATION_SIGN_ENCRYPT, params, is, os);
    }

    public void signAndEncrypt(Bundle params, InputStream is, final OutputStream os, IOpenPgpCallback callback) {
        executeApiAsync(OPERATION_SIGN_ENCRYPT, params, is, os, callback);
    }

    public Bundle decryptAndVerify(InputStream is, final OutputStream os) {
        return executeApi(OPERATION_DECRYPT_VERIFY, new Bundle(), is, os);
    }

    public Bundle decryptAndVerify(Bundle params, InputStream is, final OutputStream os) {
        return executeApi(OPERATION_DECRYPT_VERIFY, params, is, os);
    }

    public void decryptAndVerify(Bundle params, InputStream is, final OutputStream os, IOpenPgpCallback callback) {
        executeApiAsync(OPERATION_DECRYPT_VERIFY, params, is, os, callback);
    }

    public interface IOpenPgpCallback {
        void onReturn(final Bundle result);
    }

    private class OpenPgpAsyncTask extends AsyncTask<Void, Integer, Bundle> {
        int operationId;
        Bundle params;
        InputStream is;
        OutputStream os;
        IOpenPgpCallback callback;

        private OpenPgpAsyncTask(int operationId, Bundle params, InputStream is, OutputStream os, IOpenPgpCallback callback) {
            this.operationId = operationId;
            this.params = params;
            this.is = is;
            this.os = os;
            this.callback = callback;
        }

        @Override
        protected Bundle doInBackground(Void... unused) {
            return executeApi(operationId, params, is, os);
        }

        protected void onPostExecute(Bundle result) {
            callback.onReturn(result);
        }

    }

    private void executeApiAsync(int operationId, Bundle params, InputStream is, OutputStream os, IOpenPgpCallback callback) {
        new OpenPgpAsyncTask(operationId, params, is, os, callback).execute((Void[]) null);
    }

    private Bundle executeApi(int operationId, Bundle params, InputStream is, OutputStream os) {
        try {
            // send the input and output pfds
            ParcelFileDescriptor input = ParcelFileDescriptorUtil.pipeFrom(is,
                    new ParcelFileDescriptorUtil.IThreadListener() {

                        @Override
                        public void onThreadFinished(Thread thread) {
                            Log.d(OpenPgpConstants.TAG, "Copy to service finished");
                        }
                    });
            ParcelFileDescriptor output = ParcelFileDescriptorUtil.pipeTo(os,
                    new ParcelFileDescriptorUtil.IThreadListener() {

                        @Override
                        public void onThreadFinished(Thread thread) {
                            Log.d(OpenPgpConstants.TAG, "Service finished writing!");
                        }
                    });

            params.putInt(OpenPgpConstants.PARAMS_API_VERSION, OpenPgpConstants.API_VERSION);

            // default result is error
            Bundle result = new Bundle();
            result.putInt(OpenPgpConstants.RESULT_CODE, OpenPgpConstants.RESULT_CODE_ERROR);
            result.putParcelable(OpenPgpConstants.RESULT_ERRORS,
                    new OpenPgpError(OpenPgpError.GENERIC_ERROR, "This should never happen!"));

            // blocks until result is ready
            switch (operationId) {
                case OPERATION_SIGN:
                    result = mService.sign(params, input, output);
                    break;
                case OPERATION_ENCRYPT:
                    result = mService.encrypt(params, input, output);
                    break;
                case OPERATION_SIGN_ENCRYPT:
                    result = mService.signAndEncrypt(params, input, output);
                    break;
                case OPERATION_DECRYPT_VERIFY:
                    result = mService.decryptAndVerify(params, input, output);
                    break;
            }
            // close() is required to halt the TransferThread
            output.close();

            return result;
        } catch (Exception e) {
            Log.e(OpenPgpConstants.TAG, "Exception", e);
            Bundle result = new Bundle();
            result.putInt(OpenPgpConstants.RESULT_CODE, OpenPgpConstants.RESULT_CODE_ERROR);
            result.putParcelable(OpenPgpConstants.RESULT_ERRORS,
                    new OpenPgpError(OpenPgpError.CLIENT_SIDE_ERROR, e.getMessage()));
            return result;
        }
    }


}
