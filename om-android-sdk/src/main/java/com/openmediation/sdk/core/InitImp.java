// Copyright 2020 ADTIMING TECHNOLOGY COMPANY LIMITED
// Licensed under the GNU Lesser General Public License Version 3

package com.openmediation.sdk.core;

import android.app.Activity;
import android.text.TextUtils;

import com.openmediation.sdk.utils.ActLifecycle;
import com.openmediation.sdk.utils.AdLog;
import com.openmediation.sdk.utils.AdtUtil;
import com.openmediation.sdk.utils.DeveloperLog;
import com.openmediation.sdk.utils.HandlerUtil;
import com.openmediation.sdk.utils.IOUtil;
import com.openmediation.sdk.utils.helper.ConfigurationHelper;
import com.openmediation.sdk.utils.JsonUtil;
import com.openmediation.sdk.utils.SdkUtil;
import com.openmediation.sdk.utils.WorkExecutor;
import com.openmediation.sdk.utils.cache.DataCache;
import com.openmediation.sdk.utils.constant.CommonConstants;
import com.openmediation.sdk.utils.constant.KeyConstants;
import com.openmediation.sdk.utils.crash.CrashUtil;
import com.openmediation.sdk.utils.device.DeviceUtil;
import com.openmediation.sdk.utils.device.SensorManager;
import com.openmediation.sdk.utils.error.Error;
import com.openmediation.sdk.utils.error.ErrorBuilder;
import com.openmediation.sdk.utils.error.ErrorCode;
import com.openmediation.sdk.utils.event.EventId;
import com.openmediation.sdk.utils.event.EventUploadManager;
import com.openmediation.sdk.utils.model.Configurations;
import com.openmediation.sdk.utils.request.network.Request;
import com.openmediation.sdk.utils.request.network.Response;
import com.openmediation.sdk.InitCallback;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The type Init imp.
 */
public final class InitImp {
    private static AtomicBoolean hasInit = new AtomicBoolean(false);
    private static AtomicBoolean isInitRunning = new AtomicBoolean(false);
    private static InitCallback mCallback;
    private static long sInitStart;

    /**
     * init method
     *
     * @param activity the activity
     * @param appKey   the app key
     * @param callback the callback
     */
    public static void init(final Activity activity, final String appKey, final InitCallback callback) {
        //
        if (hasInit.get()) {
            return;
        }

        if (isInitRunning.get()) {
            return;
        }

        if (activity == null) {
            Error error = new Error(ErrorCode.CODE_INIT_INVALID_REQUEST
                    , ErrorCode.MSG_INIT_INVALID_REQUEST, ErrorCode.CODE_INTERNAL_REQUEST_ACTIVITY);
            AdLog.getSingleton().LogE(error.toString());
            DeveloperLog.LogE(error.toString() + ", init failed because activity is null");
            callbackInitErrorOnUIThread(error);
            return;
        }
        isInitRunning.set(true);
        sInitStart = System.currentTimeMillis();
        mCallback = callback;
        AdtUtil.init(activity);
        SensorManager.getSingleton();
        ActLifecycle.getInstance().init(activity);
        EventUploadManager.getInstance().init(activity.getApplicationContext());
        EventUploadManager.getInstance().uploadEvent(EventId.INIT_START);
        WorkExecutor.execute(new InitAsyncRunnable(appKey));
    }

    /**
     * Re init sdk.
     *
     * @param activity the activity
     * @param callback the callback
     */
    static void reInitSDK(Activity activity, final InitCallback callback) {
        if (DataCache.getInstance().containsKey("AppKey")) {
            String appKey = DataCache.getInstance().get("AppKey", String.class);
            InitImp.init(activity, appKey, new InitCallback() {
                @Override
                public void onSuccess() {
                    DeveloperLog.LogD("reInitSDK success");
                    callback.onSuccess();
                }

                @Override
                public void onError(Error error) {
                    callback.onError(error);
                }
            });
        } else {
            Error error = ErrorBuilder.build(ErrorCode.CODE_LOAD_INVALID_REQUEST
                    , ErrorCode.ERROR_NOT_INIT, ErrorCode.CODE_INTERNAL_REQUEST_APPKEY);
            callback.onError(error);
        }
    }

    /**
     * init success?
     *
     * @return the boolean
     */
    public static boolean isInit() {
        return hasInit.get();
    }

    /**
     * Is init running boolean.
     *
     * @return the boolean
     */
    static boolean isInitRunning() {
        return isInitRunning.get();
    }

    private static void requestConfig(String appKey) throws Exception {
        DeveloperLog.LogD("Om init request config");
        //requests Config
        ConfigurationHelper.getConfiguration(appKey, new InitRequestCallback(appKey));
    }

    /**
     * Inits global utils
     */
    private static void initUtil() throws Exception {
        DataCache.getInstance().init(AdtUtil.getApplication());
        DataCache.getInstance().set(DeviceUtil.preFetchDeviceInfo(AdtUtil.getApplication()));
    }

    private static void doAfterGetConfig(String appKey, Configurations config) {
        try {
            DeveloperLog.enableDebug(AdtUtil.getApplication(), config.getD() == 1);
            AdLog.getSingleton().init(AdtUtil.getApplication());
            EventUploadManager.getInstance().updateReportSettings(config);
            //reports error logs
            CrashUtil.getSingleton().uploadException(config, appKey);
        } catch (Exception e) {
            DeveloperLog.LogD("doAfterGetConfig  exception : ", e);
            CrashUtil.getSingleton().saveException(e);
        }
    }

    private static void callbackInitErrorOnUIThread(final Error result) {
        HandlerUtil.runOnUiThread(new InitFailRunnable(result));
    }

    private static void callbackInitSuccessOnUIThread() {
        HandlerUtil.runOnUiThread(new InitSuccessRunnable());
    }

    private static void initCompleteReport(int eventId, Error error) {
        JSONObject jsonObject = new JSONObject();
        if (error != null) {
            JsonUtil.put(jsonObject, "msg", error);
        }
        if (sInitStart != 0) {
            int dur = (int) (System.currentTimeMillis() - sInitStart) / 1000;
            JsonUtil.put(jsonObject, "duration", dur);
        }
        EventUploadManager.getInstance().uploadEvent(eventId, jsonObject);
    }

    private static class InitSuccessRunnable implements Runnable {

        @Override
        public void run() {
            DeveloperLog.LogD("Om init Success ");
            hasInit.set(true);
            isInitRunning.set(false);
            if (mCallback != null) {
                mCallback.onSuccess();
            }
            initCompleteReport(EventId.INIT_COMPLETE, null);
        }
    }

    private static class InitAsyncRunnable implements Runnable {

        private String appKey;

        /**
         * Instantiates a new Init async runnable.
         *
         * @param appKey the app key
         */
        InitAsyncRunnable(String appKey) {
            this.appKey = appKey;
        }

        @Override
        public void run() {
            try {
                Activity activity = ActLifecycle.getInstance().getActivity();
                //filters banning conditions
                Error error = SdkUtil.banRun(activity, appKey);
                if (error != null) {
                    callbackInitErrorOnUIThread(error);
                    return;
                }
                initUtil();
                DataCache.getInstance().set(KeyConstants.KEY_APP_KEY, appKey);
                requestConfig(appKey);
            } catch (Exception e) {
                DeveloperLog.LogD("initOnAsyncThread  exception : ", e);
                CrashUtil.getSingleton().saveException(e);
                Error error = new Error(ErrorCode.CODE_INIT_UNKNOWN_INTERNAL_ERROR
                        , ErrorCode.MSG_INIT_UNKNOWN_INTERNAL_ERROR, ErrorCode.CODE_INTERNAL_UNKNOWN_OTHER);
                AdLog.getSingleton().LogE(error.toString());
                DeveloperLog.LogE(error.toString() + ", initOnAsyncThread");
                callbackInitErrorOnUIThread(error);
            }
        }
    }

    private static class InitFailRunnable implements Runnable {
        private Error mError;

        /**
         * Instantiates a new Init fail runnable.
         *
         * @param result the result
         */
        InitFailRunnable(Error result) {
            mError = result;
        }

        @Override
        public void run() {
            DeveloperLog.LogD("Om init error  " + mError);
            hasInit.set(false);
            isInitRunning.set(false);
            if (mCallback != null) {
                mCallback.onError(mError);
            }
            initCompleteReport(EventId.INIT_FAILED, mError);
        }
    }

    private static class InitRequestCallback implements Request.OnRequestCallback {

        private String appKey;

        /**
         * Instantiates a new Init request callback.
         *
         * @param appKey the app key
         */
        InitRequestCallback(String appKey) {
            this.appKey = appKey;
        }

        @Override
        public void onRequestSuccess(Response response) {
            try {
                if (response.code() != HttpURLConnection.HTTP_OK) {
                    Error error = new Error(ErrorCode.CODE_INIT_SERVER_ERROR
                            , ErrorCode.MSG_INIT_SERVER_ERROR, ErrorCode.CODE_INTERNAL_SERVER_ERROR);
                    AdLog.getSingleton().LogE(error.toString() + ", Om init response code: " + response.code());
                    DeveloperLog.LogE(error.toString() + "Om init request config response code not 200 : " + response.code());
                    callbackInitErrorOnUIThread(error);
                    return;
                }

                String requestData = new String(ConfigurationHelper.checkResponse(response), Charset.forName(CommonConstants.CHARTSET_UTF8));
                if (TextUtils.isEmpty(requestData)) {
                    Error error = new Error(ErrorCode.CODE_INIT_SERVER_ERROR
                            , ErrorCode.MSG_INIT_SERVER_ERROR, ErrorCode.CODE_INTERNAL_SERVER_ERROR);
                    AdLog.getSingleton().LogE(error.toString() + ", Om init response data is null: " + requestData);
                    DeveloperLog.LogE(error.toString() + ", Om init response data is null: " + requestData);
                    callbackInitErrorOnUIThread(error);
                    return;
                }
                //adds global data to memory
                Configurations config = ConfigurationHelper.parseFormServerResponse(requestData);
                if (config != null) {
                    DeveloperLog.LogD("Om init request config success");
                    DataCache.getInstance().setMEM(KeyConstants.KEY_CONFIGURATION, config); //将从服务器拉取的config数据 存入本地数据库
                    callbackInitSuccessOnUIThread();
                    //获取config数据后的后续操作
                    doAfterGetConfig(appKey, config);
                } else {
                    Error error = new Error(ErrorCode.CODE_INIT_SERVER_ERROR
                            , ErrorCode.MSG_INIT_SERVER_ERROR, ErrorCode.CODE_INTERNAL_SERVER_ERROR);
                    AdLog.getSingleton().LogE(error.toString() + ", Om init format config is null");
                    DeveloperLog.LogE(error.toString() + ", Om init format config is null");
                    callbackInitErrorOnUIThread(error);
                }
            } catch (Exception e) {
                CrashUtil.getSingleton().saveException(e);
                Error error = new Error(ErrorCode.CODE_INIT_SERVER_ERROR
                        , ErrorCode.MSG_INIT_SERVER_ERROR, ErrorCode.CODE_INTERNAL_UNKNOWN_OTHER);
                AdLog.getSingleton().LogE(error.toString());
                DeveloperLog.LogE(error.toString() + ", request config exception:" + e);
                callbackInitErrorOnUIThread(error);
            } finally {
                IOUtil.closeQuietly(response);
            }
        }

        @Override
        public void onRequestFailed(String error) {
            Error result = new Error(ErrorCode.CODE_INIT_SERVER_ERROR
                    , ErrorCode.MSG_INIT_SERVER_ERROR, ErrorCode.CODE_INTERNAL_SERVER_FAILED);
            AdLog.getSingleton().LogD("request config failed : " + result + ", error:" + error);
            DeveloperLog.LogD("request config failed : " + result + ", error:" + error);
            callbackInitErrorOnUIThread(result);
        }
    }
}
